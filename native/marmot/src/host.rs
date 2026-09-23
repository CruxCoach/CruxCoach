//! One host per account: the MLS engine, the in-process relay, the relay pool
//! and the replicator. Kotlin reaches it only through `call` (JSON commands) and
//! pulls decrypted application messages with `next`.
use crate::engine::{Changes, Engine};
use crate::error::{Error, Result, checked};
use crate::protocol::{LOCAL_RELAY_URL, is_hex64, now_s};
use crate::store::Store;
use crate::transport::{CruxTransport, normalize_relays};
use cgka_engine::account_identity_proof::AccountIdentityProofSigner;
use nostr::{
    Alphabet, Filter, Kind, NostrSigner, PublicKey, RelayUrl, SingleLetterTag, SubscriptionId,
    Timestamp,
};
use nostr_relay_builder::{LocalRelay, RelayBuilder};
use nostr_relay_pool::RelayLimits;
use nostr_relay_pool::policy::{AdmitPolicy, AdmitStatus, PolicyError};
use nostr_sdk::{Client, ClientOptions, RelayStatus, SyncDirection, SyncOptions};
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::BTreeSet;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex, RwLock};
use std::time::Duration;
use storage_sqlite::SqlCipherKey;

const GROUP_SUBSCRIPTION: &str = "cc2-group";
const INBOX_SUBSCRIPTION: &str = "cc2-inbox";
const CATCH_UP_WINDOW_S: u64 = 7 * 86_400;
/// NIP-59 gift wraps carry randomized timestamps up to two days in the past.
const GIFT_WRAP_SKEW_S: u64 = 2 * 86_400;

#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct HostConfig {
    pub account: String,
    /// Used only when this account has never configured a pool natively.
    pub relays: Vec<String>,
    /// Explicit loopback-only harness option. Android always supplies false.
    pub local_test: bool,
}

#[derive(Default)]
struct Subscribed {
    routes: Vec<String>,
    inbox: bool,
    relays: Vec<String>,
}

struct Shared {
    online: AtomicBool,
    closed: AtomicBool,
    wake_outbound: tokio::sync::Notify,
    wake_catch_up: tokio::sync::Notify,
    wake_ingest: tokio::sync::Notify,
    generation: Mutex<u64>,
    changed: Condvar,
    relays: RwLock<Vec<String>>,
    subscribed: tokio::sync::Mutex<Subscribed>,
}

impl Shared {
    fn bump(&self) {
        if let Ok(mut generation) = self.generation.lock() {
            *generation += 1;
        }
        self.changed.notify_all();
    }

    fn relays(&self) -> Vec<String> {
        self.relays.read().map(|r| r.clone()).unwrap_or_default()
    }
}

#[derive(Debug)]
struct Admission(Store);

impl AdmitPolicy for Admission {
    fn admit_event<'a>(
        &'a self,
        _relay_url: &'a RelayUrl,
        _subscription_id: &'a SubscriptionId,
        event: &'a nostr::Event,
    ) -> nostr::util::BoxedFuture<'a, std::result::Result<AdmitStatus, PolicyError>> {
        Box::pin(async move {
            Ok(if self.0.admissible(event) {
                AdmitStatus::success()
            } else {
                AdmitStatus::rejected("not requested")
            })
        })
    }
}

pub struct Host {
    pub account: String,
    engine: Arc<Mutex<Engine>>,
    store: Store,
    runtime: Option<tokio::runtime::Runtime>,
    client: Client,
    relay: LocalRelay,
    transport: CruxTransport,
    shared: Arc<Shared>,
    local_test: bool,
}

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    Status,
    SetRelays {
        relays: Vec<String>,
    },
    SetOnline {
        online: bool,
    },
    SetDiscovery {
        enabled: bool,
    },
    PinPeerEndpoints {
        peer: String,
        urls: Vec<String>,
    },
    Invite {
        peer: String,
    },
    Accept {
        peer: String,
    },
    Decline {
        peer: String,
    },
    Send {
        peer: String,
        token: String,
        content: String,
    },
    Cancel {
        peer: String,
    },
    End {
        peer: String,
    },
    Next {
        after: i64,
        generation: u64,
        timeout_ms: u64,
        limit: usize,
    },
    Ack {
        seqs: Vec<i64>,
    },
    Sync,
    #[cfg(feature = "local-harness")]
    PrivateStorageMatches {
        marker: String,
    },
    #[cfg(feature = "local-harness")]
    Rotate {
        peer: String,
    },
    #[cfg(feature = "local-harness")]
    Epoch {
        peer: String,
    },
    #[cfg(feature = "local-harness")]
    LocalQuery {
        kinds: Vec<u16>,
    },
}

fn engine_step(engine: &Mutex<Engine>) -> Result<Changes> {
    let mut engine = engine.lock().map_err(|_| Error("engine_poisoned"))?;
    let mut changes = Changes::default();
    for _ in 0..8 {
        let step = engine.ingest(32)?;
        changes.inbox |= step.inbox;
        changes.peers |= step.peers;
        changes.outbound |= step.outbound;
        let cursor: i64 = engine
            .store()
            .meta("ingest")?
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);
        if cursor >= engine.store().max_seq()? {
            break;
        }
    }
    let advanced = engine.advance()?;
    changes.inbox |= advanced.inbox;
    changes.peers |= advanced.peers;
    changes.outbound |= advanced.outbound;
    Ok(changes)
}

fn classify(error: &str) -> &'static str {
    let error = error.to_ascii_lowercase();
    if error.contains("auth-required") {
        "auth_required"
    } else if [
        "blocked",
        "restricted",
        "invalid",
        "rejected",
        "pow",
        "rate-limited",
        "error:",
    ]
    .iter()
    .any(|p| error.contains(p))
    {
        "rejected"
    } else {
        "unavailable"
    }
}

/// Only relays that are connected right now receive an event. The pool would
/// otherwise buffer it for a disconnected relay and deliver it after a later
/// reconnect, which could outlive a local `cancel`.
async fn connected_relays(client: &Client, configured: &[String]) -> Vec<String> {
    let pool = client.relays().await;
    configured
        .iter()
        .filter(|url| {
            RelayUrl::parse(url)
                .ok()
                .and_then(|u| pool.get(&u).map(|r| r.status() == RelayStatus::Connected))
                .unwrap_or(false)
        })
        .cloned()
        .collect()
}

/// Drop a relay's pool state, including any buffered outbound frames, and add
/// it back. Live subscriptions are re-established by the next resubscribe.
async fn reset_relay(client: &Client, shared: &Shared, url: &str) {
    let _ = client.force_remove_relay(url).await;
    let _ = client.add_relay(url).await;
    if shared.online.load(Ordering::SeqCst) {
        let _ = client.connect_relay(url).await;
    }
    shared.subscribed.lock().await.relays.clear();
}

async fn flush_outbound(client: &Client, store: &Store, shared: &Shared) -> bool {
    let mut core_accepted = false;
    for _ in 0..4 {
        let connected = connected_relays(client, &shared.relays()).await;
        if connected.is_empty() {
            break;
        }
        let Ok(due) = store.due(&connected, 16) else {
            break;
        };
        if due.is_empty() {
            break;
        }
        for (event, targets) in due {
            if shared.closed.load(Ordering::SeqCst) || !shared.online.load(Ordering::SeqCst) {
                return core_accepted;
            }
            let urls: Vec<RelayUrl> = targets
                .iter()
                .filter_map(|t| RelayUrl::parse(t).ok())
                .collect();
            let result =
                tokio::time::timeout(Duration::from_secs(15), client.send_event_to(urls, &event))
                    .await;
            let id = event.id.to_hex();
            for target in &targets {
                let url = RelayUrl::parse(target).ok();
                let status = match (&result, &url) {
                    (Ok(Ok(output)), Some(url)) if output.success.contains(url) => "accepted",
                    (Ok(Ok(output)), Some(url)) => output
                        .failed
                        .get(url)
                        .map(|e| classify(e))
                        .unwrap_or("unavailable"),
                    _ => "unavailable",
                };
                let _ = store.record_delivery(&id, target, status);
                let _ = store.set_relay_state(target, status);
                if status == "accepted" && matches!(event.kind.as_u16(), 445) {
                    core_accepted = true;
                }
                if status == "unavailable"
                    && !connected_relays(client, std::slice::from_ref(target))
                        .await
                        .contains(target)
                {
                    reset_relay(client, shared, target).await;
                }
            }
        }
    }
    core_accepted
}

fn group_filter(routes: &[String], since: u64) -> Filter {
    Filter::new()
        .kind(Kind::Custom(445))
        .custom_tags(
            SingleLetterTag::lowercase(Alphabet::H),
            routes.iter().cloned(),
        )
        .since(Timestamp::from(since))
}

fn inbox_filter(account: &PublicKey, since: u64) -> Filter {
    Filter::new()
        .kind(Kind::GiftWrap)
        .pubkey(*account)
        .since(Timestamp::from(since))
}

async fn catch_up(
    client: &Client,
    store: &Store,
    shared: &Shared,
    routes: &[String],
    inbox: bool,
    account: &PublicKey,
) {
    let relays = shared.relays();
    let mut filters = Vec::new();
    if !routes.is_empty() {
        filters.push(group_filter(
            routes,
            now_s().saturating_sub(CATCH_UP_WINDOW_S),
        ));
    }
    if inbox {
        filters.push(inbox_filter(
            account,
            now_s().saturating_sub(CATCH_UP_WINDOW_S + GIFT_WRAP_SKEW_S),
        ));
    }
    // Only connected relays can answer; a relay that is down would hold every
    // pass for its full timeout. Relays known to refuse NIP-77 go straight to
    // the REQ window instead of being probed again.
    let refusing: BTreeSet<String> = store
        .relay_rows()
        .unwrap_or_default()
        .into_iter()
        .filter(|row| !row.2)
        .map(|row| row.0)
        .collect();
    let connected: Vec<RelayUrl> = connected_relays(client, &relays)
        .await
        .iter()
        .filter_map(|r| RelayUrl::parse(r).ok())
        .collect();
    let (known, probe): (Vec<RelayUrl>, Vec<RelayUrl>) = connected
        .into_iter()
        .partition(|url| refusing.contains(url.as_str_without_trailing_slash()));
    for filter in filters {
        let mut failed = known.clone();
        if !probe.is_empty() {
            let options = SyncOptions::new()
                .direction(SyncDirection::Down)
                .initial_timeout(Duration::from_secs(8));
            match tokio::time::timeout(
                Duration::from_secs(40),
                client.sync_with(probe.clone(), filter.clone(), &options),
            )
            .await
            {
                Ok(Ok(output)) => {
                    for url in &output.success {
                        let _ = store.set_relay_nip77(url.as_str_without_trailing_slash(), true);
                    }
                    failed.extend(output.failed.keys().cloned());
                }
                _ => failed.extend(probe.iter().cloned()),
            }
        }
        // Relays without NIP-77 get a bounded REQ window instead.
        for url in failed {
            let _ = store.set_relay_nip77(url.as_str_without_trailing_slash(), false);
            let _ = tokio::time::timeout(
                Duration::from_secs(12),
                client.fetch_events_from([url], filter.clone().limit(500), Duration::from_secs(10)),
            )
            .await;
        }
    }
}

async fn resubscribe(
    client: &Client,
    shared: &Shared,
    routes: Vec<String>,
    inbox: bool,
    account: &PublicKey,
    force: bool,
) {
    let relays = shared.relays();
    let mut current = shared.subscribed.lock().await;
    if !force && current.routes == routes && current.inbox == inbox && current.relays == relays {
        return;
    }
    let urls: Vec<RelayUrl> = relays
        .iter()
        .filter_map(|r| RelayUrl::parse(r).ok())
        .collect();
    let group_id = SubscriptionId::new(GROUP_SUBSCRIPTION);
    let inbox_id = SubscriptionId::new(INBOX_SUBSCRIPTION);
    client.unsubscribe(&group_id).await;
    client.unsubscribe(&inbox_id).await;
    if !routes.is_empty() {
        let _ = client
            .subscribe_with_id_to(
                urls.clone(),
                group_id,
                group_filter(&routes, now_s().saturating_sub(60)),
                None,
            )
            .await;
    }
    if inbox {
        let _ = client
            .subscribe_with_id_to(
                urls,
                inbox_id,
                inbox_filter(account, now_s().saturating_sub(GIFT_WRAP_SKEW_S)),
                None,
            )
            .await;
    }
    *current = Subscribed {
        routes,
        inbox,
        relays,
    };
}

fn wants_inbox(engine: &Engine) -> bool {
    engine.discovery_enabled().unwrap_or(false)
}

impl Host {
    pub fn open(
        path: &Path,
        key: SqlCipherKey,
        config: HostConfig,
        signer: Arc<dyn NostrSigner>,
        proof: Arc<dyn AccountIdentityProofSigner>,
    ) -> Result<Self> {
        let initial = if config.relays.is_empty() {
            crate::protocol::DEFAULT_RELAYS
                .iter()
                .map(|r| r.to_string())
                .collect()
        } else {
            normalize_relays(&config.relays, config.local_test)?
        };
        let engine = Engine::open(
            path,
            key,
            &config.account,
            &initial,
            config.local_test,
            signer.clone(),
            proof,
        )?;
        let store = engine.store().clone();
        let relays = engine.relays()?;
        let runtime = checked(
            tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .thread_name("cc-marmot-net")
                .enable_all()
                .build(),
            "runtime",
        )?;
        let guard = runtime.enter();
        let relay = LocalRelay::new(
            RelayBuilder::default()
                .database(store.clone())
                .max_connections(8),
        );
        let transport = CruxTransport::new(relay.clone(), config.local_test);
        let limits = RelayLimits::default();
        let options = ClientOptions::new()
            .automatic_authentication(true)
            .verify_subscriptions(true)
            .relay_limits(limits);
        let client = Client::builder()
            .signer(signer)
            .database(store.clone())
            .websocket_transport(transport.clone())
            .admit_policy(Admission(store.clone()))
            .opts(options)
            .build();
        drop(guard);
        let shared = Arc::new(Shared {
            online: AtomicBool::new(false),
            closed: AtomicBool::new(false),
            wake_outbound: Default::default(),
            wake_catch_up: Default::default(),
            wake_ingest: Default::default(),
            generation: Mutex::new(1),
            changed: Condvar::new(),
            relays: RwLock::new(relays.clone()),
            subscribed: Default::default(),
        });
        runtime.block_on(async {
            let _ = client.add_relay(LOCAL_RELAY_URL).await;
            for url in &relays {
                let _ = client.add_relay(url.as_str()).await;
            }
        });
        let host = Self {
            account: config.account.clone(),
            engine: Arc::new(Mutex::new(engine)),
            store,
            runtime: Some(runtime),
            client,
            relay,
            transport,
            shared,
            local_test: config.local_test,
        };
        host.spawn_tasks()?;
        Ok(host)
    }

    fn runtime(&self) -> Result<&tokio::runtime::Runtime> {
        self.runtime.as_ref().ok_or(Error("closed"))
    }

    fn engine(&self) -> Result<std::sync::MutexGuard<'_, Engine>> {
        self.engine.lock().map_err(|_| Error("engine_poisoned"))
    }

    fn account_key(&self) -> Result<PublicKey> {
        checked(PublicKey::from_hex(&self.account), "account")
    }

    fn spawn_tasks(&self) -> Result<()> {
        let runtime = self.runtime()?;
        let account = self.account_key()?;
        // Ingest, convergence, confirmations; wakes on stored remote events.
        {
            let engine = self.engine.clone();
            let store = self.store.clone();
            let shared = self.shared.clone();
            let client = self.client.clone();
            runtime.spawn(async move {
                loop {
                    tokio::select! {
                        _ = store.remote.notified() => {}
                        _ = shared.wake_ingest.notified() => {}
                        _ = tokio::time::sleep(Duration::from_secs(5)) => {}
                    }
                    if shared.closed.load(Ordering::SeqCst) {
                        break;
                    }
                    let step_engine = engine.clone();
                    let Ok(Ok(changes)) = tokio::task::spawn_blocking(move || {
                        let changes = engine_step(&step_engine)?;
                        let guard = step_engine.lock().map_err(|_| Error("engine_poisoned"))?;
                        Ok::<_, Error>((changes, guard.routes(), wants_inbox(&guard)))
                    })
                    .await
                    else {
                        continue;
                    };
                    let (changes, routes, inbox) = changes;
                    if changes.outbound {
                        shared.wake_outbound.notify_one();
                    }
                    if changes.inbox || changes.peers {
                        shared.bump();
                    }
                    if shared.online.load(Ordering::SeqCst) {
                        resubscribe(&client, &shared, routes, inbox, &account, false).await;
                    }
                }
            });
        }
        // Outbound replication with durable per-relay state and backoff.
        {
            let store = self.store.clone();
            let shared = self.shared.clone();
            let client = self.client.clone();
            runtime.spawn(async move {
                loop {
                    tokio::select! {
                        _ = shared.wake_outbound.notified() => {}
                        _ = tokio::time::sleep(Duration::from_secs(15)) => {}
                    }
                    if shared.closed.load(Ordering::SeqCst) {
                        break;
                    }
                    if !shared.online.load(Ordering::SeqCst) {
                        continue;
                    }
                    if flush_outbound(&client, &store, &shared).await {
                        shared.wake_ingest.notify_one();
                    }
                }
            });
        }
        // Inbound catch-up: NIP-77 where supported, bounded REQ otherwise.
        {
            let store = self.store.clone();
            let shared = self.shared.clone();
            let client = self.client.clone();
            let engine = self.engine.clone();
            runtime.spawn(async move {
                loop {
                    tokio::select! {
                        _ = shared.wake_catch_up.notified() => {}
                        _ = tokio::time::sleep(Duration::from_secs(900)) => {}
                    }
                    if shared.closed.load(Ordering::SeqCst) {
                        break;
                    }
                    if !shared.online.load(Ordering::SeqCst) {
                        continue;
                    }
                    let (routes, inbox) = match engine.lock() {
                        Ok(guard) => (guard.routes(), wants_inbox(&guard)),
                        Err(_) => break,
                    };
                    catch_up(&client, &store, &shared, &routes, inbox, &account).await;
                    shared.wake_ingest.notify_one();
                }
            });
        }
        Ok(())
    }

    fn set_online(&self, online: bool) -> Result<()> {
        let was = self.shared.online.swap(online, Ordering::SeqCst);
        let runtime = self.runtime()?;
        if online {
            let (routes, inbox) = {
                let engine = self.engine()?;
                (engine.routes(), wants_inbox(&engine))
            };
            let account = self.account_key()?;
            runtime.block_on(async {
                let _ = self.client.try_connect(Duration::from_secs(6)).await;
                resubscribe(&self.client, &self.shared, routes, inbox, &account, !was).await;
            });
            self.store.wake_deliveries()?;
            self.shared.wake_outbound.notify_one();
            self.shared.wake_catch_up.notify_one();
        } else if was {
            runtime.block_on(async {
                self.client.disconnect().await;
                *self.shared.subscribed.lock().await = Subscribed::default();
            });
        }
        Ok(())
    }

    fn set_relays(&self, relays: Vec<String>) -> Result<()> {
        let relays = normalize_relays(&relays, self.local_test)?;
        let old = self.shared.relays();
        self.engine()?.set_relays(&relays)?;
        if let Ok(mut current) = self.shared.relays.write() {
            *current = relays.clone();
        }
        let runtime = self.runtime()?;
        runtime.block_on(async {
            for url in old.iter().filter(|u| !relays.contains(u)) {
                let _ = self.client.force_remove_relay(url.as_str()).await;
            }
            for url in relays.iter().filter(|u| !old.contains(u)) {
                let _ = self.client.add_relay(url.as_str()).await;
            }
        });
        let discovery = self.engine()?.discovery_enabled()?;
        if discovery {
            self.engine()?.publish_discovery()?;
        }
        if self.shared.online.load(Ordering::SeqCst) {
            self.set_online(true)?;
        }
        Ok(())
    }

    fn relay_status(&self) -> Result<Value> {
        let runtime = self.runtime()?;
        let pool = runtime.block_on(self.client.relays());
        let rows = self.store.relay_rows()?;
        let relays: Vec<Value> = self
            .shared
            .relays()
            .into_iter()
            .map(|url| {
                let connection = RelayUrl::parse(&url)
                    .ok()
                    .and_then(|u| pool.get(&u).map(|r| r.status()))
                    .map(|s| match s {
                        RelayStatus::Connected => "connected",
                        RelayStatus::Initialized
                        | RelayStatus::Pending
                        | RelayStatus::Connecting => "connecting",
                        RelayStatus::Banned => "banned",
                        RelayStatus::Sleeping => "sleeping",
                        _ => "offline",
                    })
                    .unwrap_or("offline");
                let row = rows
                    .iter()
                    .find(|r| r.0 == url.trim_end_matches('/') || r.0 == url);
                json!({
                    "url": url,
                    "connection": connection,
                    "last_result": row.map(|r| r.1.clone()).unwrap_or_else(|| "none".into()),
                    "nip77": row.map(|r| r.2).unwrap_or(true),
                })
            })
            .collect();
        let local = RelayUrl::parse(LOCAL_RELAY_URL)
            .ok()
            .and_then(|u| pool.get(&u).map(|r| r.status() == RelayStatus::Connected))
            .unwrap_or(false);
        Ok(json!({"relays": relays, "local_relay": local}))
    }

    fn invite(&self, peer: &str) -> Result<Value> {
        if !is_hex64(peer) {
            return Err(Error("peer"));
        }
        if !self.shared.online.load(Ordering::SeqCst) {
            return Err(Error("offline"));
        }
        let public = checked(PublicKey::from_hex(peer), "peer")?;
        self.engine()?.add_author_interest(peer);
        let runtime = self.runtime()?;
        let urls: Vec<RelayUrl> = self
            .shared
            .relays()
            .iter()
            .filter_map(|r| RelayUrl::parse(r).ok())
            .collect();
        let directory = Filter::new()
            .author(public)
            .kinds([Kind::Custom(10002), Kind::Custom(10050)])
            .limit(16);
        runtime.block_on(async {
            let _ = tokio::time::timeout(
                Duration::from_secs(10),
                self.client
                    .fetch_events_from(urls, directory, Duration::from_secs(8)),
            )
            .await;
        });
        let (writes, _) = self.engine()?.peer_relays(peer)?;
        if !writes.is_empty() {
            let urls: Vec<RelayUrl> = writes
                .iter()
                .filter_map(|r| RelayUrl::parse(r).ok())
                .collect();
            let packages = Filter::new()
                .author(public)
                .kind(Kind::Custom(30443))
                .limit(32);
            runtime.block_on(async {
                let _ = tokio::time::timeout(
                    Duration::from_secs(10),
                    self.client
                        .fetch_events_from(urls, packages, Duration::from_secs(8)),
                )
                .await;
            });
        }
        let group = self.engine()?.invite(peer)?;
        self.after_change(true)?;
        Ok(json!({"group": group}))
    }

    /// Nudge tasks after a local mutation and refresh live subscriptions.
    fn after_change(&self, peers: bool) -> Result<()> {
        self.shared.wake_outbound.notify_one();
        if peers {
            self.shared.bump();
            if self.shared.online.load(Ordering::SeqCst) {
                let (routes, inbox) = {
                    let engine = self.engine()?;
                    (engine.routes(), wants_inbox(&engine))
                };
                let account = self.account_key()?;
                self.runtime()?.block_on(resubscribe(
                    &self.client,
                    &self.shared,
                    routes,
                    inbox,
                    &account,
                    false,
                ));
                self.shared.wake_catch_up.notify_one();
            }
        }
        Ok(())
    }

    fn next(&self, after: i64, known: u64, timeout_ms: u64, limit: usize) -> Result<Value> {
        let limit = limit.clamp(1, 64);
        let deadline = std::time::Instant::now() + Duration::from_millis(timeout_ms.min(30_000));
        loop {
            let items = self.store.inbox_after(after, limit)?;
            let generation = *self
                .shared
                .generation
                .lock()
                .map_err(|_| Error("host_poisoned"))?;
            if !items.is_empty() || generation != known || self.shared.closed.load(Ordering::SeqCst)
            {
                return Ok(json!({"items": items, "generation": generation}));
            }
            let now = std::time::Instant::now();
            if now >= deadline {
                return Ok(json!({"items": items, "generation": generation}));
            }
            let guard = self
                .shared
                .generation
                .lock()
                .map_err(|_| Error("host_poisoned"))?;
            if *guard != known {
                continue;
            }
            let _ = self
                .shared
                .changed
                .wait_timeout(guard, (deadline - now).min(Duration::from_millis(500)));
        }
    }

    /// One synchronous pass: publish, catch up, ingest, confirm, publish.
    fn sync_now(&self) -> Result<Value> {
        let online = self.shared.online.load(Ordering::SeqCst);
        let runtime = self.runtime()?;
        let account = self.account_key()?;
        if online {
            self.store.wake_deliveries()?;
            runtime.block_on(flush_outbound(&self.client, &self.store, &self.shared));
            let (routes, inbox) = {
                let engine = self.engine()?;
                (engine.routes(), wants_inbox(&engine))
            };
            runtime.block_on(catch_up(
                &self.client,
                &self.store,
                &self.shared,
                &routes,
                inbox,
                &account,
            ));
        }
        let mut changes = engine_step(&self.engine)?;
        if online {
            runtime.block_on(flush_outbound(&self.client, &self.store, &self.shared));
            let again = engine_step(&self.engine)?;
            changes.inbox |= again.inbox;
            changes.peers |= again.peers;
            if again.outbound {
                runtime.block_on(flush_outbound(&self.client, &self.store, &self.shared));
            }
            let (routes, inbox) = {
                let engine = self.engine()?;
                (engine.routes(), wants_inbox(&engine))
            };
            runtime.block_on(resubscribe(
                &self.client,
                &self.shared,
                routes,
                inbox,
                &account,
                false,
            ));
        }
        if changes.inbox || changes.peers {
            self.shared.bump();
        }
        Ok(json!({"outbound_pending": self.store.outbound_pending()?, "online": online}))
    }

    pub fn call(&self, raw: &str) -> Result<Value> {
        if raw.len() > 524_288 {
            return Err(Error("command_limit"));
        }
        let command: Command = serde_json::from_str(raw).map_err(|_| Error("command_format"))?;
        Ok(match command {
            Command::Status => {
                let mut status = self.engine()?.status()?;
                let relays = self.relay_status()?;
                status["relays"] = relays["relays"].clone();
                status["local_relay"] = relays["local_relay"].clone();
                status["online"] = json!(self.shared.online.load(Ordering::SeqCst));
                status["generation"] = json!(
                    *self
                        .shared
                        .generation
                        .lock()
                        .map_err(|_| Error("host_poisoned"))?
                );
                status
            }
            Command::SetRelays { relays } => {
                self.set_relays(relays)?;
                json!(true)
            }
            Command::SetOnline { online } => {
                self.set_online(online)?;
                json!(true)
            }
            Command::SetDiscovery { enabled } => {
                if enabled {
                    self.engine()?.publish_discovery()?;
                } else {
                    self.engine()?.disable_discovery()?;
                }
                self.after_change(true)?;
                json!(true)
            }
            Command::PinPeerEndpoints { peer, urls } => {
                if !self.local_test && !urls.is_empty() {
                    return Err(Error("peer_endpoints_disabled"));
                }
                self.transport.pin_peer_endpoints(&peer, urls)?;
                json!(true)
            }
            Command::Invite { peer } => self.invite(&peer)?,
            Command::Accept { peer } => {
                self.engine()?.accept(&peer)?;
                self.after_change(true)?;
                json!(true)
            }
            Command::Decline { peer } => {
                self.engine()?.decline(&peer)?;
                self.after_change(true)?;
                json!(true)
            }
            Command::Send {
                peer,
                token,
                content,
            } => {
                let (event, duplicate) = self.engine()?.send(&peer, &token, &content)?;
                self.after_change(false)?;
                json!({"event": event, "duplicate": duplicate})
            }
            Command::Cancel { peer } => {
                let cancelled = self.engine()?.cancel(&peer)?;
                json!({"cancelled": cancelled})
            }
            Command::End { peer } => {
                self.engine()?.end(&peer, "local")?;
                self.after_change(true)?;
                json!(true)
            }
            Command::Next {
                after,
                generation,
                timeout_ms,
                limit,
            } => self.next(after, generation, timeout_ms, limit)?,
            Command::Ack { seqs } => {
                if seqs.len() > 64 {
                    return Err(Error("ack_limit"));
                }
                self.store.inbox_ack(&seqs)?;
                json!(true)
            }
            Command::Sync => self.sync_now()?,
            #[cfg(feature = "local-harness")]
            Command::PrivateStorageMatches { marker } => {
                json!(self.engine()?.private_storage_matches(&marker)?)
            }
            #[cfg(feature = "local-harness")]
            Command::Rotate { peer } => {
                self.engine()?.rotate(&peer)?;
                self.after_change(false)?;
                json!(true)
            }
            #[cfg(feature = "local-harness")]
            Command::Epoch { peer } => json!(self.engine()?.epoch(&peer)?),
            #[cfg(feature = "local-harness")]
            Command::LocalQuery { kinds } => {
                let filter = Filter::new().kinds(kinds.into_iter().map(Kind::Custom));
                let runtime = self.runtime()?;
                let events = runtime.block_on(self.client.fetch_events_from(
                    [LOCAL_RELAY_URL],
                    filter,
                    Duration::from_secs(5),
                ));
                json!(events.map(|e| e.len()).map_err(|_| Error("local_relay"))?)
            }
        })
    }

    /// Wake blocked `next` callers and stop background work.
    pub fn signal_close(&self) {
        self.shared.closed.store(true, Ordering::SeqCst);
        self.shared.bump();
    }

    pub fn poisoned(&self) -> bool {
        self.engine.lock().map(|e| e.poisoned()).unwrap_or(true)
    }

    pub fn close(mut self) {
        self.shutdown();
    }

    /// Stop every task before the engine (and its file lease) is released, so
    /// a reopen in the same process never races a still-running task.
    fn shutdown(&mut self) {
        self.shared.closed.store(true, Ordering::SeqCst);
        self.shared.wake_outbound.notify_waiters();
        self.shared.wake_ingest.notify_waiters();
        self.shared.wake_catch_up.notify_waiters();
        self.shared.bump();
        if let Some(runtime) = self.runtime.take() {
            let client = self.client.clone();
            runtime.block_on(async move {
                let _ = tokio::time::timeout(Duration::from_secs(3), client.shutdown()).await;
            });
            self.relay.shutdown();
            runtime.shutdown_timeout(Duration::from_secs(5));
        }
    }
}

impl Drop for Host {
    fn drop(&mut self) {
        self.shutdown();
    }
}
