use crate::relay::{self, RelayStatus};
use cgka_engine::account_identity_proof::{
    AccountIdentityProofRequest, AccountIdentityProofSigner,
};
use cgka_session::{AccountDeviceSession, PublishWork, SessionConfig, SessionEffects};
use cgka_traits::app_components::{
    AppComponentData, NOSTR_ROUTING_COMPONENT_ID, NostrRoutingV1, decode_nostr_routing_v1,
    encode_nostr_routing_v1,
};
use cgka_traits::app_event::MarmotAppEvent;
use cgka_traits::engine::{CreateGroupRequest, GroupEvent, KeyPackage, SendIntent};
use cgka_traits::group::ProtocolProfile;
use cgka_traits::message::MessageState;
use cgka_traits::storage::{GroupStorage, MessageStorage, StorageProvider};
use cgka_traits::transport::{TransportEnvelope, TransportMessage};
use cgka_traits::{
    EpochState, GroupId, MemberId, MessageId, OutboundFanout, TransportEndpoint,
    TransportPublishRequest, TransportPublishTarget,
};
use nostr::base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use nostr::{Event, EventBuilder, Keys, Kind, NostrSigner, PublicKey, Tag};
use serde::{Deserialize, Serialize, de::DeserializeOwned};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::{
    path::Path,
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};
use storage_sqlite::{SqlCipherKey, SqliteAccountStorage};
use transport_nostr_adapter::NostrKeyPackagePublication;
use transport_nostr_peeler::{NostrMlsPeeler, NostrTransportEvent};

pub type Result<T> = std::result::Result<T, Error>;
/// Deliberately carries only fixed error codes across FFI/log boundaries.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Error(pub &'static str);
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}
impl std::error::Error for Error {}
impl From<cgka_traits::storage::StorageError> for Error {
    fn from(error: cgka_traits::storage::StorageError) -> Self {
        match error {
            cgka_traits::storage::StorageError::Backend(message)
                if message == "host journal capacity" || message == "host journal row limit" =>
            {
                Self("native_storage_quota")
            }
            _ => Self("storage"),
        }
    }
}
fn checked<T, E>(result: std::result::Result<T, E>, code: &'static str) -> Result<T> {
    result.map_err(|_| Error(code))
}
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn random32() -> [u8; 32] {
    use nostr::secp256k1::rand::RngCore;
    let mut value = [0u8; 32];
    nostr::secp256k1::rand::rngs::OsRng.fill_bytes(&mut value);
    value
}
fn id(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}
fn group(value: &str) -> Result<GroupId> {
    Ok(GroupId::new(checked(hex::decode(value), "group_id")?))
}

#[derive(Clone)]
pub struct LocalProofSigner(pub Keys);
impl AccountIdentityProofSigner for LocalProofSigner {
    fn sign_account_identity_proof(
        &self,
        request: &AccountIdentityProofRequest,
    ) -> std::result::Result<[u8; 64], String> {
        let event = request
            .proof_event()?
            .sign_with_keys(&self.0)
            .map_err(|_| "signer".to_string())?;
        request.signature_from_signed_event(event)
    }
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    pub account: String,
    pub relays: Vec<String>,
    /// Explicit loopback-only harness option. Android always supplies false.
    pub local_test: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Fence {
    pub group: String,
    pub binding: String,
    pub epoch: u64,
    pub local_account: String,
    pub local_leaf: String,
    pub peer_account: String,
    pub peer_leaf: String,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Peer {
    pub account: String,
    pub group: String,
    pub accepted: bool,
    pub inbox: Vec<String>,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Inbox {
    pub source: String,
    pub fence: Fence,
    pub kind: u64,
    pub tags: Vec<Vec<String>>,
    pub content: String,
    pub acknowledged: bool,
    pub invalidated: bool,
    pub retained_until: u64,
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Target {
    pub relay: String,
    pub status: RelayStatus,
    pub attempts: u32,
    pub next_attempt: u64,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Outbox {
    pub event: NostrTransportEvent,
    pub targets: Vec<Target>,
    pub group: Option<String>,
    pub binding: Option<String>,
    pub expires_at: u64,
    pub cancelled: bool,
    pub core_fanout: bool,
    pub application: Option<PendingApplication>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PendingApplication {
    pub event_id: String,
    pub fence: Fence,
    pub kind: u64,
    pub tags: Vec<Vec<String>>,
    pub content: String,
    pub expires_at: u64,
}

/// Payload-free provenance survives the bounded replica lease plus wire overlap.
/// Buckets keep the existing journal row/byte quotas useful for frequent deltas.
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct RetainedSource {
    source: String,
    until: u64,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct AcknowledgedSources {
    fence: Fence,
    sources: Vec<RetainedSource>,
}

#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Scan {
    until: u64,
    floor: u64,
    head: u64,
    readable: bool,
    checked_at: u64,
}

/// One owner for each account-device session. No background mutation occurs:
/// the host holds its coordinator from fence acquisition through handoff/ack.
pub struct Node {
    pub config: Config,
    session: AccountDeviceSession,
    store: SqliteAccountStorage,
    runtime: Arc<tokio::runtime::Runtime>,
    signer: Arc<dyn NostrSigner>,
    poisoned: bool,
    database_path: std::path::PathBuf,
    _lease: fs_private::PrivateExclusiveFileLease,
    fence_cache: std::cell::RefCell<std::collections::BTreeMap<String, Fence>>,
}

impl Node {
    pub fn open(
        path: &Path,
        database_key: SqlCipherKey,
        config: Config,
        signer: Arc<dyn NostrSigner>,
        proof: Arc<dyn AccountIdentityProofSigner>,
    ) -> Result<Self> {
        static PANIC_PRIVACY: std::sync::Once = std::sync::Once::new();
        PANIC_PRIVACY.call_once(|| {
            std::panic::set_hook(Box::new(|info| {
                if let Some(location) = info.location() {
                    eprintln!(
                        "Marmot native failure at {}:{} (details redacted)",
                        location.file(),
                        location.line()
                    );
                }
            }))
        });
        let parent = path.parent().ok_or(Error("database_path"))?;
        checked(
            fs_private::create_dir_all_private(parent),
            "database_directory",
        )?;
        let lease = checked(
            fs_private::try_acquire_private_exclusive_file_lease(&path.with_extension("lease")),
            "session_already_open",
        )?;
        checked(
            fs_private::ensure_private_db_files(path),
            "database_permissions",
        )?;
        if config.relays.is_empty() || config.relays.len() > 16 {
            return Err(Error("relay_count"));
        }
        for relay in &config.relays {
            checked(
                relay::validate_url(relay, config.local_test),
                "relay_policy",
            )?;
        }
        let runtime = Arc::new(checked(
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build(),
            "runtime",
        )?);
        let public = checked(runtime.block_on(signer.get_public_key()), "signer")?;
        if public.to_hex() != config.account {
            return Err(Error("account_mismatch"));
        }
        let session = checked(
            AccountDeviceSession::open(
                SessionConfig::new(
                    path,
                    database_key,
                    public.to_bytes().to_vec(),
                    Box::new(NostrMlsPeeler::new().with_welcome_signer(signer.clone())),
                )
                .account_identity_proof_signer(proof)
                .supported_app_components(
                    cgka_traits::app_components::default_group_components()
                        .into_iter()
                        .chain([NOSTR_ROUTING_COMPONENT_ID]),
                ),
            ),
            "native_open",
        )?;
        let store = session.cruxcoach_storage();
        store.cruxcoach_init()?;
        let mut node = Self {
            config,
            session,
            store,
            runtime,
            signer,
            poisoned: false,
            database_path: path.into(),
            _lease: lease,
            fence_cache: Default::default(),
        };
        let identity: Option<String> = node.get("meta/account")?;
        if identity
            .as_ref()
            .is_some_and(|old| old != &node.config.account)
        {
            return Err(Error("account_mismatch"));
        }
        let format: Option<u64> = node.get("meta/format")?;
        if identity.is_some() && format != Some(1) {
            return Err(Error("journal_version"));
        }
        node.put("meta/format", &1u64)?;
        node.put("meta/account", &node.config.account)?;
        node.reconcile()?;
        Ok(node)
    }

    fn get<T: DeserializeOwned>(&self, key: &str) -> Result<Option<T>> {
        self.store
            .cruxcoach_get(key)?
            .map(|bytes| checked(serde_json::from_slice(&bytes), "journal_format"))
            .transpose()
    }
    fn put<T: Serialize>(&self, key: &str, value: &T) -> Result<()> {
        self.store
            .cruxcoach_put(key, &checked(serde_json::to_vec(value), "encode")?)?;
        Ok(())
    }
    fn atomic<T>(&mut self, f: impl FnOnce(&mut Self) -> Result<T>) -> Result<T> {
        if self.poisoned {
            return Err(Error("reopen_required"));
        }
        let storage = self.store.clone();
        self.fence_cache.borrow_mut().clear();
        let result = storage.with_transaction(|_| {
            self.record_routes()?;
            let value = f(self)?;
            self.fence_cache.borrow_mut().clear();
            self.reconcile()?;
            Ok(value)
        });
        // Rollback can leave an in-memory MLS ratchet ahead of SQLCipher. Never
        // use it again: reopen/hydrate is mandatory after any torn operation.
        if result.is_err() {
            self.poisoned = true;
        }
        result
    }

    /// Definite canonical retirement, independent of temporary connectivity.
    /// Application replicas can delete payloads even when no live fence exists.
    pub fn binding_retired(&self, binding: &str) -> Result<bool> {
        if binding.len() != 64 || !binding.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(Error("binding_format"));
        }
        Ok(self
            .get::<bool>(&format!("retired/{binding}"))?
            .unwrap_or(false))
    }

    pub fn own_binding(&self, binding: &str) -> Result<Option<(u64, String)>> {
        if binding.len() != 64 {
            return Err(Error("binding_format"));
        }
        Ok(self
            .get::<(u64, String)>(&format!("hello/{binding}"))?
            .filter(|(expiry, _)| *expiry > now_ms()))
    }

    pub fn discovery_enabled(&self) -> Result<bool> {
        Ok(self.get::<bool>("meta/discovery_enabled")?.unwrap_or(false))
    }

    pub fn peers(&self) -> Result<Vec<Peer>> {
        self.store
            .cruxcoach_keys("peer/")?
            .iter()
            .map(|k| self.get(k)?.ok_or(Error("peer_missing")))
            .collect()
    }
    pub fn invitations(&self) -> Result<Vec<Peer>> {
        self.store
            .cruxcoach_keys("invitation/")?
            .iter()
            .map(|k| self.get(k)?.ok_or(Error("invitation_missing")))
            .collect()
    }
    pub fn reset_peer(&mut self, peer: &str) -> Result<()> {
        checked(PublicKey::from_hex(peer), "peer")?;
        self.atomic(|node| {
            if let Some(old) = node.get::<Peer>(&format!("peer/{peer}"))? {
                if let Ok(fence) = node.raw_fence(&old.group) {
                    node.put(&format!("retired/{}", fence.binding), &true)?;
                }
                node.store.cruxcoach_delete(&format!("peer/{peer}"))?;
            }
            Ok(())
        })
    }

    pub fn fence(&mut self, peer: &str) -> Result<Fence> {
        if self.poisoned {
            return Err(Error("reopen_required"));
        }
        let pin: Peer = self
            .get(&format!("peer/{peer}"))?
            .ok_or(Error("no_session"))?;
        if !pin.accepted {
            return Err(Error("invitation_pending"));
        }
        let fence = self.raw_fence(&pin.group)?;
        if fence.peer_account != peer {
            return Err(Error("peer_mismatch"));
        }
        if self
            .get::<bool>(&format!("retired/{}", fence.binding))?
            .unwrap_or(false)
        {
            return Err(Error("session_retired"));
        }
        Ok(fence)
    }

    fn raw_fence(&self, group_hex: &str) -> Result<Fence> {
        let gid = group(group_hex)?;
        let record = checked(self.session.group_record(&gid), "no_group")?;
        if record.protocol_profile != ProtocolProfile::Current
            || record.is_terminal()
            || record.unrecoverable
            || !matches!(
                self.session.epoch_state(&gid),
                Some(EpochState::Stable { .. })
            )
            || checked(
                self.session.has_pending_convergence_inputs(&gid),
                "convergence",
            )?
        {
            return Err(Error("session_not_stable"));
        }
        if let Some(fence) = self.fence_cache.borrow().get(group_hex)
            && fence.epoch == record.epoch.0
        {
            return Ok(fence.clone());
        }
        let (binding, own_index, leaves) =
            checked(self.session.cruxcoach_leaf_snapshot(&gid), "leaf_snapshot")?;
        if leaves.len() != 2 {
            return Err(Error("two_leaves_required"));
        }
        let local = leaves
            .iter()
            .find(|l| l.0 == own_index)
            .ok_or(Error("local_leaf"))?;
        let peer = leaves
            .iter()
            .find(|l| l.0 != own_index)
            .ok_or(Error("peer_leaf"))?;
        if hex::encode(&local.1) != self.config.account || local.1 == peer.1 {
            return Err(Error("account_leaf"));
        }
        let fence = Fence {
            group: group_hex.into(),
            binding: hex::encode(binding),
            epoch: record.epoch.0,
            local_account: hex::encode(&local.1),
            local_leaf: hex::encode(&local.2),
            peer_account: hex::encode(&peer.1),
            peer_leaf: hex::encode(&peer.2),
        };
        self.fence_cache
            .borrow_mut()
            .insert(group_hex.into(), fence.clone());
        Ok(fence)
    }

    fn routing(&self, gid: &GroupId) -> Result<NostrRoutingV1> {
        let bytes = checked(
            self.session.app_component(gid, NOSTR_ROUTING_COMPONENT_ID),
            "routing",
        )?
        .ok_or(Error("routing_missing"))?;
        checked(decode_nostr_routing_v1(&bytes), "routing_format")
    }
    /// Retained relay endpoints are snapshots of authenticated MLS routing,
    /// indexed separately from relay discovery hints. MDK alone determines
    /// whether an old routing id still belongs to an accepted epoch snapshot.
    fn record_routes(&self) -> Result<()> {
        for peer in self.peers()? {
            if let Ok(route) = self.routing(&group(&peer.group)?) {
                let key = format!("route/{}/{}", peer.group, hex::encode(route.nostr_group_id));
                if self.get::<Vec<String>>(&key)?.as_ref() != Some(&route.relays) {
                    self.put(&key, &route.relays)?;
                }
            }
        }
        Ok(())
    }
    fn route_endpoints(&self, gid: &GroupId, route_id: &[u8]) -> Result<Vec<String>> {
        let current = self.routing(gid)?;
        if current.nostr_group_id.as_slice() == route_id {
            return Ok(self.permitted(&current.relays));
        }
        let retained = self
            .store
            .list_transport_group_routes()?
            .into_iter()
            .any(|r| r.group_id == *gid && r.transport_group_id == route_id);
        if !retained {
            return Err(Error("route_not_retained"));
        }
        let key = format!(
            "route/{}/{}",
            hex::encode(gid.as_slice()),
            hex::encode(route_id)
        );
        let relays = self
            .get::<Vec<String>>(&key)?
            .ok_or(Error("retained_route_missing"))?;
        Ok(self.permitted(&relays))
    }

    /// Hints are used only when explicitly configured by the local user. They
    /// never replace authenticated group routing or change canonical state.
    fn permitted(&self, relays: &[String]) -> Vec<String> {
        let advertised: Vec<_> = relays
            .iter()
            .filter_map(|url| relay::validate_url(url, self.config.local_test).ok())
            .collect();
        let mut seen = std::collections::BTreeSet::new();
        self.config
            .relays
            .iter()
            .filter_map(|configured| {
                let url = relay::validate_url(configured, self.config.local_test).ok()?;
                // URL equivalence (host case/default port/root slash) selects only
                // a locally configured physical endpoint, preserving its UI key.
                // Canonical MLS routing bytes remain exactly as authenticated.
                (advertised.contains(&url) && seen.insert(url.to_string()))
                    .then(|| configured.clone())
            })
            .collect()
    }

    fn stage_event(
        &self,
        event: NostrTransportEvent,
        relays: Vec<String>,
        gid: Option<String>,
        binding: Option<String>,
        expires_at: u64,
        core_fanout: bool,
    ) -> Result<String> {
        checked(event.to_verified_nostr_event(), "outbound_signature")?;
        if checked(serde_json::to_vec(&event), "encode")?.len() > 120_000 {
            return Err(Error("relay_payload_limit"));
        }
        let key = format!("out/{}", event.id);
        if self.get::<Outbox>(&key)?.is_none() {
            self.put(
                &key,
                &Outbox {
                    event: event.clone(),
                    targets: relays
                        .into_iter()
                        .map(|relay| Target {
                            relay,
                            status: RelayStatus::Unavailable,
                            attempts: 0,
                            next_attempt: 0,
                        })
                        .collect(),
                    group: gid,
                    binding,
                    expires_at,
                    cancelled: false,
                    core_fanout,
                    application: None,
                },
            )?;
        }
        Ok(event.id)
    }

    fn sign_event(
        &self,
        kind: u16,
        tags: Vec<Vec<String>>,
        content: String,
    ) -> Result<NostrTransportEvent> {
        let tags = tags
            .into_iter()
            .map(|tag| checked(Tag::parse(tag), "tag"))
            .collect::<Result<Vec<_>>>()?;
        let public = checked(PublicKey::from_hex(&self.config.account), "account")?;
        let unsigned = EventBuilder::new(Kind::Custom(kind), content)
            .tags(tags)
            .build(public);
        let expected = unsigned.id;
        let signed = checked(
            self.runtime.block_on(self.signer.sign_event(unsigned)),
            "signer",
        )?;
        if Some(signed.id) != expected || signed.pubkey != public {
            return Err(Error("signer_mismatch"));
        }
        checked(signed.verify(), "signature")?;
        checked(NostrTransportEvent::from_nostr_event(&signed), "event")
    }

    /// Explicit discovery opt-in: publishes only NIP-65, inbox list and public
    /// KeyPackage. The native MLS private init key remains in SQLCipher.
    pub fn bootstrap(&mut self) -> Result<()> {
        self.atomic(|node| {
            for kind in [10002, 10050] {
                let key = format!("bootstrap/{kind}");
                let old: Option<String> = node.get(&key)?;
                let configured = id(&checked(serde_json::to_vec(&node.config.relays), "encode")?);
                let retained = old
                    .as_ref()
                    .and_then(|eid| node.get::<Outbox>(&format!("out/{eid}")).ok().flatten());
                if retained.is_none_or(|out| out.expires_at <= now_ms())
                    || node.get::<String>(&format!("{key}/pool"))?.as_ref() != Some(&configured)
                {
                    let tag = if kind == 10002 { "r" } else { "relay" };
                    let tags = node
                        .config
                        .relays
                        .iter()
                        .map(|r| vec![tag.into(), r.clone()])
                        .collect();
                    let event = node.sign_event(kind, tags, String::new())?;
                    let eid = node.stage_event(
                        event,
                        node.config.relays.clone(),
                        None,
                        None,
                        now_ms() + 604_800_000,
                        false,
                    )?;
                    node.put(&key, &eid)?;
                    node.put(&format!("{key}/pool"), &configured)?;
                }
            }
            let published: Option<String> = node.get("bootstrap/keypackage")?;
            let owned = checked(
                node.session.durably_owned_key_packages(),
                "keypackage_storage",
            )?;
            let usable = published
                .as_ref()
                .and_then(|eid| node.get::<Outbox>(&format!("out/{eid}")).ok().flatten())
                .is_some_and(|out| {
                    out.expires_at > now_ms() + 86_400_000
                        && owned
                            .iter()
                            .any(|kp| BASE64.encode(&kp.bytes) == out.event.content)
                });
            if !usable {
                let kp = checked(
                    node.runtime.block_on(node.session.fresh_key_package()),
                    "keypackage",
                )?;
                let metadata = checked(
                    node.session.key_package_metadata(&kp),
                    "keypackage_metadata",
                )?;
                // Random slot is unrelated to identity, stable across replacements.
                let slot = node
                    .get::<String>("bootstrap/slot")?
                    .unwrap_or_else(|| hex::encode(random32()));
                node.put("bootstrap/slot", &slot)?;
                let fmt = |values: &[u16]| values.iter().map(|n| format!("0x{n:04x}")).collect();
                let publication = NostrKeyPackagePublication {
                    account_id: node.session.self_id(),
                    key_package: kp,
                    key_package_slot_id: slot,
                    key_package_ref: metadata.key_package_ref_hex,
                    mls_ciphersuite: format!("0x{:04x}", metadata.ciphersuite),
                    mls_extensions: fmt(&metadata.mls_extensions),
                    mls_proposals: fmt(&metadata.mls_proposals),
                    app_components: fmt(&metadata.app_components),
                    publish_endpoints: node
                        .config
                        .relays
                        .iter()
                        .cloned()
                        .map(TransportEndpoint)
                        .collect(),
                };
                let unsigned = checked(publication.to_event(), "keypackage_event")?;
                let signed = node.sign_event(30443, unsigned.tags, unsigned.content)?;
                let eid = node.stage_event(
                    signed,
                    node.config.relays.clone(),
                    None,
                    None,
                    metadata.not_after.saturating_mul(1000),
                    false,
                )?;
                node.put("bootstrap/keypackage", &eid)?;
            }
            node.put("meta/discovery_enabled", &true)?;
            Ok(())
        })
    }

    fn fetch(&self, relays: &[String], filters: Vec<Value>) -> Vec<Event> {
        let request = Value::Array([vec![json!("REQ"), json!("cc-bounded")], filters].concat());
        let results = self.runtime.block_on(futures_util::future::join_all(
            relays
                .iter()
                .map(|relay| relay::exchange(relay, self.config.local_test, request.clone())),
        ));
        let mut events = Vec::new();
        for result in results {
            for event in result.events {
                if !events.iter().any(|e: &Event| e.id == event.id) {
                    events.push(event);
                }
            }
        }
        events
    }

    pub fn invite(&mut self, peer: &str) -> Result<Fence> {
        if !self.get::<bool>("meta/discovery_enabled")?.unwrap_or(false) {
            return Err(Error("discovery_not_enabled"));
        }
        checked(PublicKey::from_hex(peer), "peer")?;
        if peer == self.config.account {
            return Err(Error("self_invite"));
        }
        if self.get::<Peer>(&format!("peer/{peer}"))?.is_some() {
            return self.fence(peer);
        }
        if self.peers()?.len() >= 32 {
            return Err(Error("peer_quota"));
        }
        let directory = self.fetch(
            &self.config.relays,
            vec![json!({"authors":[peer],"kinds":[10002,10050],"limit":16})],
        );
        let latest = |kind: u16| {
            directory
                .iter()
                .filter(|e| {
                    e.created_at.as_secs() <= now_ms() / 1000 + 60
                        && e.pubkey.to_hex() == peer
                        && e.kind.as_u16() == kind
                })
                .max_by(|a, b| {
                    a.created_at
                        .cmp(&b.created_at)
                        .then_with(|| b.id.cmp(&a.id))
                })
        };
        let writes: Vec<String> = latest(10002)
            .ok_or(Error("peer_discovery_missing"))?
            .tags
            .iter()
            .filter_map(|tag| {
                let t = tag.as_slice();
                (t.len() >= 2 && t[0] == "r" && (t.len() == 2 || t[2] == "write"))
                    .then(|| t[1].clone())
            })
            .collect();
        let inbox: Vec<String> = latest(10050)
            .ok_or(Error("peer_inbox_missing"))?
            .tags
            .iter()
            .filter_map(|tag| {
                let t = tag.as_slice();
                (t.len() == 2 && t[0] == "relay").then(|| t[1].clone())
            })
            .collect();
        let writes = self.permitted(&writes);
        let inbox = self.permitted(&inbox);
        if writes.is_empty() || inbox.is_empty() {
            return Err(Error("peer_relays_not_configured"));
        }
        let packages = self.fetch(
            &writes,
            vec![json!({"authors":[peer],"kinds":[30443],"limit":32})],
        );
        let mut candidates = Vec::new();
        for event in packages {
            if event.created_at.as_secs() > now_ms() / 1000 + 60 {
                continue;
            }
            if event.pubkey.to_hex() != peer || event.kind.as_u16() != 30443 {
                continue;
            }
            let Ok(transport) = NostrTransportEvent::from_nostr_event(&event) else {
                continue;
            };
            let Ok(bytes) = BASE64.decode(&event.content) else {
                continue;
            };
            let kp = KeyPackage::with_source_event_id(bytes, MessageId::new(event.id.to_bytes()))
                .with_protocol_profile(ProtocolProfile::Current);
            let Ok(metadata) = self.session.key_package_metadata(&kp) else {
                continue;
            };
            if metadata.credential_identity_hex != peer
                || metadata.not_after.saturating_mul(1000) <= now_ms()
                || metadata.not_before.saturating_mul(1000) > now_ms()
            {
                continue;
            }
            let expected = NostrKeyPackagePublication {
                account_id: MemberId::new(event.pubkey.to_bytes()),
                key_package: kp.clone(),
                key_package_slot_id: transport.single_tag_value("d").unwrap_or("").into(),
                key_package_ref: metadata.key_package_ref_hex.clone(),
                mls_ciphersuite: format!("0x{:04x}", metadata.ciphersuite),
                mls_extensions: metadata
                    .mls_extensions
                    .iter()
                    .map(|n| format!("0x{n:04x}"))
                    .collect(),
                mls_proposals: metadata
                    .mls_proposals
                    .iter()
                    .map(|n| format!("0x{n:04x}"))
                    .collect(),
                app_components: metadata
                    .app_components
                    .iter()
                    .map(|n| format!("0x{n:04x}"))
                    .collect(),
                publish_endpoints: writes.iter().cloned().map(TransportEndpoint).collect(),
            };
            let Ok(shape) = expected.to_event_at(transport.created_at) else {
                continue;
            };
            if shape.tags != transport.tags
                || hex::decode(&expected.key_package_slot_id)
                    .ok()
                    .is_none_or(|s| s.len() != 32)
            {
                continue;
            }
            candidates.push((event.created_at, event.id, kp));
        }
        candidates.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
        let kp = candidates
            .into_iter()
            .next()
            .ok_or(Error("valid_keypackage_missing"))?
            .2;
        self.atomic(|node| {
            let route = random32();
            let routing = checked(
                NostrRoutingV1::new(route, node.config.relays.clone()),
                "routing",
            )?;
            let created = checked(
                node.runtime
                    .block_on(node.session.create_group(CreateGroupRequest {
                        name: String::new(),
                        description: String::new(),
                        members: vec![kp],
                        required_features: vec![],
                        initial_admins: vec![],
                        app_components: vec![AppComponentData {
                            component_id: NOSTR_ROUTING_COMPONENT_ID,
                            data: checked(encode_nostr_routing_v1(&routing), "routing")?,
                        }],
                    })),
                "create_group",
            )?;
            let gid = hex::encode(created.group_id.as_slice());
            node.put(
                &format!("peer/{peer}"),
                &Peer {
                    account: peer.into(),
                    group: gid.clone(),
                    accepted: true,
                    inbox,
                },
            )?;
            node.persist_effects(created.effects, Some(&created.group_id))?;
            node.raw_fence(&gid)
        })
    }

    pub fn accept_invitation(&mut self, peer: &str) -> Result<Fence> {
        self.accept_group_invitation(peer, None)
    }
    pub fn accept_group_invitation(&mut self, peer: &str, group: Option<&str>) -> Result<Fence> {
        let invitation_key = group.map(|g| format!("invitation/{peer}/{g}"));
        let key = invitation_key.as_deref().unwrap_or("");
        let mut pin: Peer = if let Some(invite) = self.get(key)? {
            invite
        } else {
            self.get(&format!("peer/{peer}"))?
                .ok_or(Error("no_invitation"))?
        };
        if group.is_some_and(|g| g != pin.group) {
            return Err(Error("invitation_mismatch"));
        }
        let fence = self.raw_fence(&pin.group)?;
        if fence.peer_account != peer {
            return Err(Error("peer_mismatch"));
        }
        self.atomic(|node| {
            if let Some(old) = node.get::<Peer>(&format!("peer/{peer}"))?
                && old.group != pin.group
                && let Ok(previous) = node.raw_fence(&old.group)
            {
                node.put(&format!("retired/{}", previous.binding), &true)?;
            }
            pin.accepted = true;
            node.put(&format!("peer/{peer}"), &pin)?;
            if let Some(key) = invitation_key {
                node.store.cruxcoach_delete(&key)?;
            }
            Ok(())
        })?;
        self.fence(peer)
    }

    pub fn handoff(
        &mut self,
        expected: &Fence,
        kind: u64,
        tags: Vec<Vec<String>>,
        content: String,
        expires_at: u64,
    ) -> Result<String> {
        if ![1220, 1221, 1222, 1223].contains(&kind)
            || content.len() > 220_000
            || tags.len() > 8
            || tags
                .iter()
                .any(|t| t.len() > 8 || t.iter().any(|v| v.len() > 1024))
            || expires_at <= now_ms()
            || expires_at > now_ms() + 604_800_000
        {
            return Err(Error("payload_or_lifetime"));
        }
        if self.fence(&expected.peer_account)? != *expected {
            return Err(Error("stale_fence"));
        }
        let operation = id(&checked(
            serde_json::to_vec(&(expected.binding.clone(), kind, &tags, &content)),
            "encode",
        )?);
        if let Some(existing) = self.get::<String>(&format!("operation/{operation}"))? {
            return Ok(existing);
        }
        self.atomic(|node| {
            let app = MarmotAppEvent::new(
                node.config.account.clone(),
                now_ms() / 1000,
                kind,
                tags,
                content,
            );
            let effects = checked(
                node.runtime
                    .block_on(node.session.send(SendIntent::AppMessage {
                        group_id: group(&expected.group)?,
                        payload: checked(app.encode(), "app_encode")?,
                    })),
                "native_send",
            )?;
            // Stable-fenced profile never permits SDK regeneration across epochs.
            if !effects.queued.is_empty() {
                return Err(Error("convergence_pending"));
            }
            let eid = effects
                .publish
                .iter()
                .find_map(|p| {
                    if let PublishWork::ApplicationMessage { msg, .. } = p {
                        Some(hex::encode(msg.id.as_slice()))
                    } else {
                        None
                    }
                })
                .ok_or(Error("native_handoff_missing"))?;
            node.persist_effects(effects, Some(&group(&expected.group)?))?;
            let key = format!("out/{eid}");
            let mut out: Outbox = node.get(&key)?.ok_or(Error("outbox_missing"))?;
            out.expires_at = expires_at;
            out.binding = Some(expected.binding.clone());
            out.application = Some(PendingApplication {
                event_id: eid.clone(),
                fence: expected.clone(),
                kind,
                tags: app.tags.clone(),
                content: app.content.clone(),
                expires_at,
            });
            node.put(&key, &out)?;
            node.put(&format!("operation/{operation}"), &eid)?;
            if kind == 1221 {
                node.put(
                    &format!("hello/{}", expected.binding),
                    &(expires_at, app.content),
                )?;
            }
            Ok(eid)
        })
    }

    fn persist_effects(
        &mut self,
        effects: SessionEffects,
        default_group: Option<&GroupId>,
    ) -> Result<()> {
        for work in effects.publish {
            match work {
                PublishWork::FoundingGroupCreated { welcomes } => {
                    for msg in welcomes {
                        self.stage_transport(msg, default_group, None, vec![])?;
                    }
                }
                PublishWork::ApplicationMessage {
                    msg,
                    group_id,
                    queued_intent,
                    ..
                } => {
                    if queued_intent.is_some() {
                        return Err(Error("unexpected_regeneration"));
                    }
                    self.stage_transport(msg, Some(&group_id), None, vec![])?;
                }
                PublishWork::GroupEvolution {
                    msg,
                    welcomes,
                    pending,
                } => {
                    self.stage_transport(msg, default_group, Some(pending), welcomes)?;
                }
                PublishWork::AutoPublish { msg, pending } => {
                    self.stage_transport(msg, default_group, Some(pending), vec![])?;
                }
                PublishWork::Proposal { msg, .. } => {
                    self.stage_transport(msg, default_group, None, vec![])?;
                }
                PublishWork::GroupCreated { .. } => return Err(Error("legacy_profile_forbidden")),
            }
        }
        // Effects are also read from MDK's transactionally persisted pending
        // application journal in reconcile. A lossy drain is never our inbox.
        Ok(())
    }

    fn stage_transport(
        &self,
        msg: TransportMessage,
        gid: Option<&GroupId>,
        pending: Option<cgka_traits::engine_state::PendingStateRef>,
        welcomes: Vec<TransportMessage>,
    ) -> Result<()> {
        let event = checked(
            NostrTransportEvent::from_transport_message(&msg),
            "transport_event",
        )?;
        let resolved = if gid.is_none() {
            match &msg.envelope {
                TransportEnvelope::GroupMessage { transport_group_id } => self
                    .store
                    .list_transport_group_routes()?
                    .into_iter()
                    .find_map(|route| {
                        (route.transport_group_id.as_slice() == transport_group_id.as_slice())
                            .then_some(route.group_id)
                    }),
                _ => None,
            }
        } else {
            None
        };
        let gid = gid.or(resolved.as_ref());
        let (target, relays) = match &msg.envelope {
            TransportEnvelope::GroupMessage { transport_group_id } => {
                let gid = gid.ok_or(Error("group_missing"))?;
                let relays = self.route_endpoints(gid, transport_group_id.as_slice())?;
                (
                    TransportPublishTarget::Group {
                        group_id: gid.clone(),
                        transport_group_id: transport_group_id.clone(),
                        endpoints: relays.iter().cloned().map(TransportEndpoint).collect(),
                    },
                    relays,
                )
            }
            TransportEnvelope::Welcome { recipient } => {
                let peer: Peer = self
                    .get(&format!("peer/{}", hex::encode(recipient.as_slice())))?
                    .ok_or(Error("inbox_unknown"))?;
                let relays = self.permitted(&peer.inbox);
                (
                    TransportPublishTarget::Inbox {
                        recipient: recipient.clone(),
                        endpoints: relays.iter().cloned().map(TransportEndpoint).collect(),
                    },
                    relays,
                )
            }
        };
        if pending.is_some() {
            let fanout = checked(
                OutboundFanout::stage_with_post_confirmation_welcomes(
                    TransportPublishRequest {
                        account_id: self.session.self_id(),
                        message: msg.clone(),
                        target,
                        required_acks: 1,
                    },
                    pending,
                    gid.cloned(),
                    now_ms(),
                    Some(msg.id.clone()),
                    Some(cgka_traits::FanoutPendingKind::GroupEvolution),
                    welcomes,
                ),
                "fanout",
            )?;
            checked(self.session.put_outbound_fanout(&fanout), "fanout_store")?;
        }
        self.stage_event(
            event,
            relays,
            gid.map(|g| hex::encode(g.as_slice())),
            None,
            now_ms() + 604_800_000,
            pending.is_some(),
        )?;
        Ok(())
    }

    pub fn inbox(&mut self) -> Result<Vec<Inbox>> {
        if self.poisoned {
            return Err(Error("reopen_required"));
        }
        // All engine mutations reconcile on their atomic transaction rail;
        // this owner has no independent engine worker.
        self.store
            .cruxcoach_keys("in/")?
            .iter()
            .map(|key| self.get(key)?.ok_or(Error("inbox_missing")))
            .collect()
    }
    fn acknowledged_key(source: &str, fence: &Fence) -> Result<String> {
        if source.len() != 64 || !source.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(Error("source_format"));
        }
        Ok(format!("acked/{}/{}", fence.binding, &source[..1]))
    }
    pub fn acknowledge(&mut self, source: &str, expected: &Fence) -> Result<()> {
        if self.fence(&expected.peer_account)? != *expected {
            return Err(Error("stale_fence"));
        }
        let key = format!("in/{source}");
        let bucket_key = Self::acknowledged_key(source, expected)?;
        let Some(mut row) = self.get::<Inbox>(&key)? else {
            return if self
                .get::<AcknowledgedSources>(&bucket_key)?
                .is_some_and(|b| {
                    b.fence == *expected && b.sources.iter().any(|s| s.source == source)
                }) {
                Ok(())
            } else {
                Err(Error("inbox_missing"))
            };
        };
        if row.invalidated || row.fence != *expected {
            return Err(Error("source_invalidated"));
        }
        if row.kind == 1223 {
            return self.discard_continuous_inbox(source, expected);
        }
        row.acknowledged = true;
        self.put(&key, &row)
    }

    /// Local logical deletion needs no current peer cooperation/fence. Match
    /// the exact stored source/fence and kind; preserve canonical provenance.
    pub fn discard_continuous_inbox(&mut self, source: &str, expected: &Fence) -> Result<()> {
        let key = format!("in/{source}");
        let Some(row) = self.get::<Inbox>(&key)? else {
            return Ok(());
        };
        if row.kind != 1223 || row.fence != *expected {
            return Err(Error("source_fence"));
        }
        let bucket_key = Self::acknowledged_key(source, expected)?;
        self.atomic(|node| {
            let mut bucket =
                node.get::<AcknowledgedSources>(&bucket_key)?
                    .unwrap_or(AcknowledgedSources {
                        fence: expected.clone(),
                        sources: vec![],
                    });
            if bucket.fence != *expected {
                return Err(Error("source_fence"));
            }
            if !bucket.sources.iter().any(|s| s.source == source) {
                if bucket.sources.len() >= 1024 {
                    // Deletion must still succeed at provenance quota. Keep a
                    // payload-free acknowledged source in its existing row;
                    // ordinary row limits then backpressure further ingest.
                    let mut cleared = row.clone();
                    cleared.content.clear();
                    cleared.tags.clear();
                    cleared.acknowledged = true;
                    return node.put(&key, &cleared);
                }
                bucket.sources.push(RetainedSource {
                    source: source.into(),
                    until: row.retained_until,
                });
            }
            node.store.cruxcoach_delete(&key)?;
            node.put(&bucket_key, &bucket)?;
            Ok(())
        })
    }

    /// Test-only inspection of actual durable payload locations, returning only
    /// counts. No key or stored plaintext is emitted. SDK canonical messages are
    /// retained encrypted MLS wires; app_events/timeline are not used by Node.
    #[cfg(feature = "local-harness")]
    pub fn private_storage_matches(&self, marker: &str) -> Result<usize> {
        use cgka_traits::storage::OutboundIntentStorage;
        if marker.len() < 8 || marker.len() > 256 {
            return Err(Error("marker_limit"));
        }
        let contains = |bytes: &[u8]| bytes.windows(marker.len()).any(|w| w == marker.as_bytes());
        let mut count = 0;
        for key in self.store.cruxcoach_keys("")? {
            if let Some(value) = self.store.cruxcoach_get(&key)? {
                count += usize::from(contains(&value));
            }
        }
        for event in self.store.list_pending_application_events()? {
            count += usize::from(contains(&checked(
                serde_json::to_vec(&event),
                "test_encode",
            )?));
        }
        for group in self.store.list_groups()? {
            for message in self.store.list_messages(&group, cgka_traits::EpochId(0))? {
                count += usize::from(contains(&message.payload));
            }
            for intent in self.store.list_queued_outbound_intents(&group)? {
                count += usize::from(contains(&checked(
                    serde_json::to_vec(&intent),
                    "test_encode",
                )?));
            }
        }
        // Node never opts into MDK's chat projection. A future integration must
        // extend deletion coverage before enabling that additional plaintext store.
        if self.store.app_message_count()? != 0 {
            return Err(Error("unexpected_chat_store"));
        }
        Ok(count)
    }

    fn canonical_source(&self, source: &str, fence: &Fence) -> Result<bool> {
        let source = MessageId::new(checked(hex::decode(source), "source")?);
        Ok(self.store.get_message(&source).is_ok_and(|m| {
            m.state == MessageState::Processed
                && hex::encode(m.group_id.as_slice()) == fence.group
                && m.epoch.0 == fence.epoch
        }))
    }

    /// Only the application that durably owns its subscription state retires
    /// an obsolete/completed private operation. Never touches MDK core fanouts.
    pub fn discard_continuous_handoff(&mut self, event_id: &str, expected: &Fence) -> Result<()> {
        let key = format!("out/{event_id}");
        let Some(out) = self.get::<Outbox>(&key)? else {
            return Ok(());
        };
        let app = out.application.as_ref().ok_or(Error("handoff_mismatch"))?;
        if out.core_fanout || app.kind != 1223 || app.fence != *expected {
            return Err(Error("handoff_mismatch"));
        }
        let operation = id(&checked(
            serde_json::to_vec(&(expected.binding.clone(), app.kind, &app.tags, &app.content)),
            "encode",
        )?);
        self.atomic(|node| {
            node.store.cruxcoach_delete(&key)?;
            let operation_key = format!("operation/{operation}");
            if node.get::<String>(&operation_key)?.as_deref() == Some(event_id) {
                node.store.cruxcoach_delete(&operation_key)?;
            }
            Ok(())
        })
    }
    pub fn continuous_obligations(&self) -> Result<Vec<PendingApplication>> {
        Ok(self
            .outbox()?
            .into_iter()
            .filter_map(|o| o.application)
            .filter(|a| a.kind == 1223)
            .collect())
    }

    fn reconcile(&mut self) -> Result<()> {
        if self.poisoned {
            return Err(Error("reopen_required"));
        }
        self.record_routes()?;
        let pending = self.store.list_pending_application_events()?;
        for event in pending {
            match event {
                GroupEvent::GroupJoined {
                    group_id,
                    via_welcome,
                    ..
                } => {
                    let gid = hex::encode(group_id.as_slice());
                    if let Ok(fence) = self.raw_fence(&gid) {
                        let key = format!("peer/{}", fence.peer_account);
                        let prior = self.get::<Peer>(&key)?;
                        if prior.is_none() && self.peers()?.len() < 32 {
                            self.put(
                                &key,
                                &Peer {
                                    account: fence.peer_account,
                                    group: gid,
                                    accepted: false,
                                    inbox: vec![],
                                },
                            )?;
                        } else if prior.as_ref().is_some_and(|p| p.group != gid)
                            && self.invitations()?.len() < 32
                        {
                            self.put(
                                &format!("invitation/{}/{gid}", fence.peer_account),
                                &Peer {
                                    account: fence.peer_account,
                                    group: gid,
                                    accepted: false,
                                    inbox: vec![],
                                },
                            )?;
                        }
                    }
                    self.store
                        .delete_pending_application_events(&[via_welcome])?;
                }
                GroupEvent::MessageReceived {
                    group_id,
                    message_id,
                    sender,
                    epoch,
                    payload,
                    ..
                } => {
                    let source = hex::encode(message_id.as_slice());
                    let key = format!("in/{source}");
                    if self.get::<Inbox>(&key)?.is_none()
                        && let Ok(fence) = self.raw_fence(&hex::encode(group_id.as_slice()))
                        && fence.peer_account == hex::encode(sender.as_slice())
                        && fence.epoch == epoch.0
                    {
                        let app = checked(MarmotAppEvent::decode(&payload), "inner_event")?;
                        checked(app.validate_sender(&fence.peer_account), "inner_sender")?;
                        if app.content.len() <= 220_000
                            && [1220, 1221, 1222, 1223].contains(&app.kind)
                        {
                            self.put(
                                &key,
                                &Inbox {
                                    source: source.clone(),
                                    fence,
                                    kind: app.kind,
                                    tags: app.tags,
                                    content: app.content,
                                    acknowledged: false,
                                    invalidated: false,
                                    retained_until: now_ms() + 8 * 86_400_000,
                                },
                            )?;
                        }
                    }
                    // Replayed engine events already consumed/deleted by the
                    // host must not recreate a plaintext inbox row.
                    if let Some(row) = self.get::<Inbox>(&key)? {
                        let bucket_key = Self::acknowledged_key(&source, &row.fence)?;
                        if self
                            .get::<AcknowledgedSources>(&bucket_key)?
                            .is_some_and(|b| b.sources.iter().any(|s| s.source == source))
                        {
                            self.store.cruxcoach_delete(&key)?;
                        }
                    }
                    // Our durable source-aware journal now owns the effect.
                    self.store
                        .delete_pending_application_events(&[message_id])?;
                }
                _ => {}
            }
        }
        for key in self.store.cruxcoach_keys("acked/")? {
            let bucket: AcknowledgedSources = self.get(&key)?.ok_or(Error("source_missing"))?;
            let mut canonical =
                self.raw_fence(&bucket.fence.group).ok().as_ref() == Some(&bucket.fence);
            if canonical {
                for source in &bucket.sources {
                    if !self.canonical_source(&source.source, &bucket.fence)? {
                        canonical = false;
                        break;
                    }
                }
            }
            if !canonical {
                self.put(&format!("retired/{}", bucket.fence.binding), &true)?;
                self.store.cruxcoach_delete(&key)?;
            }
        }
        for key in self.store.cruxcoach_keys("in/")? {
            let mut row: Inbox = self.get(&key)?.ok_or(Error("inbox_missing"))?;
            if row.invalidated {
                continue;
            }
            let source = MessageId::new(checked(hex::decode(&row.source), "source")?);
            let canonical = self.store.get_message(&source).is_ok_and(|m| {
                m.state == MessageState::Processed
                    && hex::encode(m.group_id.as_slice()) == row.fence.group
                    && m.epoch.0 == row.fence.epoch
            });
            let current = self.raw_fence(&row.fence.group).ok();
            if !canonical || current.as_ref() != Some(&row.fence) {
                row.invalidated = true;
                row.content.clear();
                self.put(&key, &row)?;
                self.put(&format!("retired/{}", row.fence.binding), &true)?;
            }
        }
        Ok(())
    }

    pub fn outbox(&self) -> Result<Vec<Outbox>> {
        self.store
            .cruxcoach_keys("out/")?
            .iter()
            .map(|k| self.get(k)?.ok_or(Error("outbox_missing")))
            .collect()
    }

    pub fn sync(&mut self) -> Result<()> {
        if !self.get::<bool>("meta/discovery_enabled")?.unwrap_or(false) {
            return Err(Error("discovery_not_enabled"));
        }
        if self.poisoned {
            return Err(Error("reopen_required"));
        }
        // Backfill precedes publication: no queued content is sent before known
        // changes/retractions have been applied. No timestamp claims consensus.
        self.compact()?;
        if std::fs::metadata(&self.database_path)
            .map(|m| m.len())
            .unwrap_or(0)
            > 268_435_456
        {
            return Err(Error("native_storage_quota"));
        }
        let mut routes = vec![(
            self.config.relays.clone(),
            json!({"kinds":[1059],"#p":[self.config.account]}),
        )];
        let accepted_groups: Vec<_> = self
            .peers()?
            .iter()
            .map(|p| group(&p.group))
            .collect::<Result<_>>()?;
        for route in self.store.list_transport_group_routes()? {
            if accepted_groups.contains(&route.group_id) {
                let endpoints = self.route_endpoints(&route.group_id, &route.transport_group_id)?;
                routes.push((
                    endpoints,
                    json!({"kinds":[445],"#h":[hex::encode(route.transport_group_id)]}),
                ));
            }
        }
        // Ordinary Android workers have a finite execution window. Persist a
        // round-robin cursor; an unvisited route never receives a fresh-read fence.
        routes.sort_by_key(|(_, filter)| filter.to_string());
        let cursor = self.get::<usize>("meta/route_cursor")?.unwrap_or(0) % routes.len();
        routes.rotate_left(cursor);
        let count = routes.len().min(4);
        self.put("meta/route_cursor", &((cursor + count) % routes.len()))?;
        for (relays, filter) in routes.into_iter().take(count) {
            let jobs = relays
                .iter()
                .map(|relay| self.scan_request(relay, filter.clone()))
                .collect::<Result<Vec<_>>>()?;
            let results = self
                .runtime
                .block_on(futures_util::future::join_all(jobs.iter().map(
                    |(endpoint, filter, _)| {
                        relay::exchange(
                            endpoint,
                            self.config.local_test,
                            json!(["REQ", "cc-backfill", filter]),
                        )
                    },
                )));
            for ((endpoint, filter, scan), result) in jobs.into_iter().zip(results) {
                self.backfill(&endpoint, filter, scan, result)?;
            }
        }
        for peer in self.peers()? {
            let gid = group(&peer.group)?;
            let pending = checked(
                self.session.has_pending_convergence_inputs(&gid),
                "convergence",
            )?;
            let due = checked(
                self.session.prepare_convergence_cutoff_delay_ms(&gid),
                "convergence",
            )? == Some(0);
            if pending || due {
                self.atomic(|node| {
                    let effects = checked(
                        node.runtime
                            .block_on(node.session.advance_convergence_inputs(&gid)),
                        "convergence",
                    )?;
                    node.persist_effects(effects, Some(&gid))
                })?;
            }
        }
        self.reconcile()?;
        self.publish_ready(None)?;
        self.reconcile()?;
        self.bootstrap()
    }

    /// Bounded backwards pagination per relay and authenticated route. The
    /// inclusive boundary never skips a saturated timestamp; it remains Limited.
    /// A cursor advances only after every event is durably classified by MDK.
    fn scan_request(&self, endpoint: &str, mut filter: Value) -> Result<(String, Value, Scan)> {
        let scope = id(&checked(
            serde_json::to_vec(&(endpoint, &filter)),
            "encode",
        )?);
        let time = now_ms() / 1000;
        let scan = self.get::<Scan>(&format!("scan/{scope}"))?.unwrap_or(Scan {
            until: time + 120,
            floor: time.saturating_sub(864_000),
            head: time + 120,
            readable: false,
            checked_at: 0,
        });
        // The smallest advertised max_limit in the six-relay pool is 50.
        filter["limit"] = json!(50);
        filter["until"] = json!(scan.until);
        filter["since"] = json!(scan.floor);
        Ok((endpoint.into(), filter, scan))
    }
    fn backfill(
        &mut self,
        endpoint: &str,
        filter: Value,
        mut scan: Scan,
        mut result: relay::Exchange,
    ) -> Result<()> {
        let mut scope_filter = filter.clone();
        for field in ["limit", "until", "since"] {
            scope_filter.as_object_mut().unwrap().remove(field);
        }
        let scope = id(&checked(
            serde_json::to_vec(&(endpoint, scope_filter)),
            "encode",
        )?);
        let key = format!("scan/{scope}");
        let time = now_ms() / 1000;
        // Match locally too: even correctly signed events outside the REQ cannot
        // advance this route's checkpoint or consume its application authority.
        result.events.retain(|e| {
            let v = serde_json::to_value(e).unwrap_or(Value::Null);
            e.created_at.as_secs() >= scan.floor
                && e.created_at.as_secs() <= scan.until
                && filter["kinds"]
                    .as_array()
                    .is_some_and(|k| k.contains(&v["kind"]))
                && filter
                    .as_object()
                    .unwrap()
                    .iter()
                    .filter(|(k, _)| k.starts_with('#'))
                    .all(|(k, want)| {
                        e.tags.iter().any(|tag| {
                            tag.as_slice().len() >= 2
                                && tag.as_slice()[0] == k[1..]
                                && want
                                    .as_array()
                                    .is_some_and(|a| a.contains(&json!(tag.as_slice()[1])))
                        })
                    })
        });
        result
            .events
            .sort_by(|a, b| a.created_at.cmp(&b.created_at).then(a.id.cmp(&b.id)));
        for event in &result.events {
            if event.kind.as_u16() == 1059
                && checked(self.session.live_group_ids(), "groups")?.len() >= 64
            {
                return Err(Error("native_ingest_quota"));
            }
            let Ok(transport) =
                NostrTransportEvent::from_nostr_event(event).and_then(|e| e.to_transport_message())
            else {
                continue;
            };
            self.atomic(|node| {
                match node.runtime.block_on(node.session.ingest(transport)) {
                    Ok(ingest) => {
                        if ingest.left_object_unpersisted
                            || matches!(
                                ingest.outcome,
                                cgka_traits::ingest::IngestOutcome::ResourceRefused { .. }
                            )
                        {
                            return Err(Error("native_ingest_quota"));
                        }
                        node.persist_effects(ingest.effects, None)?;
                    }
                    // Malformed remote objects are rejected; backend errors
                    // must roll back and force hydration, never fake an ack.
                    Err(cgka_session::SessionError::Engine(
                        cgka_traits::error::EngineError::Peeler(_)
                        | cgka_traits::error::EngineError::InvalidWelcome
                        | cgka_traits::error::EngineError::WelcomeAlreadyProcessed,
                    )) => {}
                    Err(_) => return Err(Error("native_ingest_failed")),
                }
                Ok(())
            })?;
        }
        let count = result.events.len();
        scan.checked_at = now_ms();
        scan.readable = false;
        if matches!(
            result.status,
            RelayStatus::ReadComplete | RelayStatus::Limited
        ) {
            if count >= 50 || result.status == RelayStatus::Limited {
                let oldest = result
                    .events
                    .first()
                    .map(|e| e.created_at.as_secs())
                    .unwrap_or(scan.until);
                if oldest < scan.until {
                    scan.until = oldest;
                }
                result.status = RelayStatus::Limited;
            } else {
                scan.floor = scan.head.saturating_sub(172_800);
                scan.head = time + 120;
                scan.until = scan.head;
                scan.readable = true;
            }
        }
        self.put(&key, &scan)?;
        self.put(
            &format!("relay/{}", id(endpoint.as_bytes())),
            &(endpoint, result.status, now_ms()),
        )
    }

    pub fn relay_summary(&self) -> Result<std::collections::BTreeMap<String, Vec<RelayStatus>>> {
        let mut summary: std::collections::BTreeMap<String, Vec<RelayStatus>> = self
            .config
            .relays
            .iter()
            .map(|r| (r.clone(), vec![]))
            .collect();
        for out in self.outbox()? {
            if out.cancelled || out.expires_at <= now_ms() {
                continue;
            }
            for target in out.targets {
                let states = summary.entry(target.relay).or_default();
                if !states.contains(&target.status) {
                    states.push(target.status);
                }
            }
        }
        for (relay, status, _) in self.relay_states()? {
            let states = summary.entry(relay).or_default();
            if !states.contains(&status) {
                states.push(status);
            }
        }
        Ok(summary)
    }

    pub fn relay_states(&self) -> Result<Vec<(String, RelayStatus, u64)>> {
        self.store
            .cruxcoach_keys("relay/")?
            .iter()
            .map(|k| self.get(k)?.ok_or(Error("relay_status_missing")))
            .collect()
    }

    /// Only expired application obligations are removed. Active and ambiguous
    /// core fanouts remain durable. Expired wire lifetimes prevent replay from
    /// reviving the corresponding permission, even after tombstone collection.
    fn compact(&mut self) -> Result<()> {
        let now = now_ms();
        self.atomic(|node| {
            for key in node.store.cruxcoach_keys("in/")? {
                let row: Inbox = node.get(&key)?.ok_or(Error("inbox_missing"))?;
                if row.retained_until <= now {
                    node.store.cruxcoach_delete(&key)?;
                }
            }
            for key in node.store.cruxcoach_keys("acked/")? {
                let mut bucket: AcknowledgedSources =
                    node.get(&key)?.ok_or(Error("source_missing"))?;
                bucket.sources.retain(|s| s.until > now);
                if bucket.sources.is_empty() {
                    node.store.cruxcoach_delete(&key)?;
                } else {
                    node.put(&key, &bucket)?;
                }
            }
            for key in node.store.cruxcoach_keys("hello/")? {
                if node.get::<(u64, String)>(&key)?.is_some_and(|r| r.0 <= now) {
                    node.store.cruxcoach_delete(&key)?;
                }
            }
            for key in node.store.cruxcoach_keys("out/")? {
                let out: Outbox = node.get(&key)?.ok_or(Error("outbox_missing"))?;
                if !out.core_fanout && out.expires_at.saturating_add(86_400_000) <= now {
                    node.store.cruxcoach_delete(&key)?;
                }
            }
            for key in node.store.cruxcoach_keys("operation/")? {
                let eid: String = node.get(&key)?.ok_or(Error("operation_missing"))?;
                if node.get::<Outbox>(&format!("out/{eid}"))?.is_none() {
                    node.store.cruxcoach_delete(&key)?;
                }
            }
            Ok(())
        })
    }

    pub fn pending_applications(&self) -> Result<Vec<PendingApplication>> {
        let mut rows: Vec<_> = self
            .outbox()?
            .into_iter()
            .filter(|o| {
                !o.cancelled
                    && o.expires_at > now_ms()
                    && o.targets.iter().any(|t| {
                        t.status != RelayStatus::Accepted
                            && (o.application.as_ref().is_none_or(|a| a.kind != 1223)
                                || t.next_attempt <= now_ms())
                    })
            })
            .collect();
        // Durable attempt times provide fairness across grants and restarts.
        rows.sort_by_key(|o| {
            o.targets
                .iter()
                .filter(|t| t.status != RelayStatus::Accepted)
                .map(|t| t.next_attempt)
                .min()
                .unwrap_or(u64::MAX)
        });
        let mut continuous = 0;
        Ok(rows
            .into_iter()
            .filter_map(|o| o.application)
            .filter(|a| {
                if a.kind != 1223 {
                    return true;
                }
                continuous += 1;
                continuous <= 8
            })
            .collect())
    }

    /// Called only while the host holds its current application authorization
    /// transaction and session fence. A network reconnect alone cannot send
    /// private application data whose local policy may have been revoked.
    pub fn publish_handoff(&mut self, event_id: &str, expected: &Fence) -> Result<()> {
        if self.fence(&expected.peer_account)? != *expected {
            return Err(Error("stale_fence"));
        }
        let out: Outbox = self
            .get(&format!("out/{event_id}"))?
            .ok_or(Error("outbox_missing"))?;
        if out
            .application
            .as_ref()
            .is_none_or(|a| a.fence != *expected)
        {
            return Err(Error("handoff_mismatch"));
        }
        if out.application.as_ref().is_some_and(|a| a.kind != 1221)
            && !self.recent_route_read(expected)?
        {
            return Err(Error("history_pending"));
        }
        self.publish_ready(Some(event_id))
    }

    fn recent_route_read(&self, fence: &Fence) -> Result<bool> {
        let routing = self.routing(&group(&fence.group)?)?;
        let filter = json!({"kinds":[445],"#h":[hex::encode(routing.nostr_group_id)]});
        for relay in self.permitted(&routing.relays) {
            let scope = id(&checked(serde_json::to_vec(&(&relay, &filter)), "encode")?);
            if self
                .get::<Scan>(&format!("scan/{scope}"))?
                .is_some_and(|s| s.readable && now_ms().saturating_sub(s.checked_at) <= 60_000)
            {
                return Ok(true);
            }
        }
        Ok(false)
    }

    fn publish_ready(&mut self, permit_application: Option<&str>) -> Result<()> {
        let keys = self.store.cruxcoach_keys("out/")?;
        let mut network_budget = 8usize;
        for key in keys {
            if network_budget == 0 {
                break;
            }
            let mut out: Outbox = self.get(&key)?.ok_or(Error("outbox_missing"))?;
            if out.cancelled {
                continue;
            }
            if out.application.is_some() && permit_application != Some(out.event.id.as_str()) {
                continue;
            }
            if permit_application.is_some() && permit_application != Some(out.event.id.as_str()) {
                continue;
            }
            if out.expires_at <= now_ms()
                || out.binding.as_ref().is_some_and(|b| {
                    out.group
                        .as_ref()
                        .and_then(|g| self.raw_fence(g).ok())
                        .is_none_or(|f| f.binding != *b)
                        || self
                            .get::<bool>(&format!("retired/{b}"))
                            .map(|v| v.unwrap_or(false))
                            .unwrap_or(true)
                })
            {
                out.cancelled = true;
                self.put(&key, &out)?;
                continue;
            }
            let mut attempts = Vec::new();
            for index in 0..out.targets.len() {
                if out.targets[index].status == RelayStatus::Accepted
                    || out.targets[index].next_attempt > now_ms()
                {
                    continue;
                }
                let target = &mut out.targets[index];
                if !self.config.relays.contains(&target.relay) {
                    target.status = RelayStatus::Rejected;
                    continue;
                }
                target.attempts = target.attempts.saturating_add(1);
                let delay = 1000u64.saturating_mul(1u64 << target.attempts.min(9));
                target.next_attempt = now_ms() + delay;
                self.atomic(|node| {
                    node.put(&key, &out)?;
                    if out.core_fanout {
                        let mid = MessageId::new(checked(hex::decode(&out.event.id), "event_id")?);
                        if let Some(mut fanout) =
                            checked(node.session.outbound_fanouts(), "fanouts")?
                                .into_iter()
                                .find(|f| f.message_id() == &mid)
                        {
                            checked(
                                fanout.mark_attempt_started_at(index, now_ms()),
                                "fanout_attempt",
                            )?;
                            checked(
                                node.session.put_outbound_fanout(&fanout),
                                "fanout_attempt_store",
                            )?;
                        }
                    }
                    Ok(())
                })?; // durable attempt BEFORE the socket side effect
                attempts.push(index);
            }
            if !attempts.is_empty() {
                network_budget -= 1;
            }
            let results =
                self.runtime
                    .block_on(futures_util::future::join_all(attempts.iter().map(
                        |index| {
                            relay::exchange(
                                &out.targets[*index].relay,
                                self.config.local_test,
                                json!(["EVENT", out.event]),
                            )
                        },
                    )));
            for (index, exchange) in attempts.into_iter().zip(results) {
                out.targets[index].status = exchange.status;
                self.atomic(|node| {
                    node.put(&key, &out)?;
                    if out.targets[index].status == RelayStatus::Accepted && out.core_fanout {
                        let mid = MessageId::new(checked(hex::decode(&out.event.id), "event_id")?);
                        if let Some(mut fanout) =
                            checked(node.session.outbound_fanouts(), "fanouts")?
                                .into_iter()
                                .find(|f| f.message_id() == &mid)
                        {
                            checked(fanout.mark_target_accepted(index), "fanout_ack")?;
                            checked(fanout.record_published_message_id(mid.clone()), "fanout_id")?;
                            checked(node.session.put_outbound_fanout(&fanout), "fanout_store")?;
                            if let Some(pending) = fanout.pending_ref() {
                                let effects = checked(
                                    node.runtime.block_on(
                                        node.session.confirm_published_fanout(pending, &mut fanout),
                                    ),
                                    "confirm",
                                )?;
                                let gid = out.group.as_ref().map(|g| group(g)).transpose()?;
                                node.persist_effects(effects, gid.as_ref())?;
                            }
                        }
                    }
                    Ok(())
                })?;
            }
        }
        self.reconcile()
    }

    #[cfg(feature = "local-harness")]
    pub fn harness_group_terminal(&self, gid: &str) -> Result<bool> {
        Ok(checked(self.session.group_record(&group(gid)?), "group")?.is_terminal())
    }

    pub fn refresh_discovery(&mut self) -> Result<()> {
        self.atomic(|node| {
            node.store.cruxcoach_delete("bootstrap/keypackage")?;
            Ok(())
        })?;
        self.bootstrap()
    }

    /// Adversarial harness: exercise authenticated membership/routing changes
    /// through official SDK intents. Not compiled into Android or exposed by JNI.
    #[cfg(feature = "local-harness")]
    pub fn harness_change_group(&mut self, expected: &Fence, remove: bool) -> Result<()> {
        if self.fence(&expected.peer_account)? != *expected {
            return Err(Error("stale_fence"));
        }
        self.atomic(|node| {
            let gid = group(&expected.group)?;
            let intent = if remove {
                SendIntent::RemoveMembers {
                    group_id: gid.clone(),
                    members: vec![MemberId::new(checked(
                        hex::decode(&expected.peer_account),
                        "peer",
                    )?)],
                }
            } else {
                SendIntent::UpdateAppComponents {
                    group_id: gid.clone(),
                    updates: vec![AppComponentData {
                        component_id: NOSTR_ROUTING_COMPONENT_ID,
                        data: checked(
                            encode_nostr_routing_v1(&checked(
                                NostrRoutingV1::new(random32(), node.config.relays.clone()),
                                "routing",
                            )?),
                            "routing",
                        )?,
                    }],
                }
            };
            let effects = checked(
                node.runtime.block_on(node.session.send(intent)),
                "harness_group_change",
            )?;
            node.persist_effects(effects, Some(&gid))
        })
    }

    pub fn rotate(&mut self, expected: &Fence) -> Result<()> {
        if self.fence(&expected.peer_account)? != *expected {
            return Err(Error("stale_fence"));
        }
        self.atomic(|node| {
            let gid = group(&expected.group)?;
            let effects = checked(
                node.runtime
                    .block_on(node.session.send(SendIntent::SelfUpdate {
                        group_id: gid.clone(),
                    })),
                "rotate",
            )?;
            node.persist_effects(effects, Some(&gid))?;
            Ok(())
        })
    }
}

/// Explicit host recovery after all application grants have been withdrawn.
/// Keep the old encrypted database intact; a fresh session never imports its
/// group epochs or permissions. The stable lease inode is never renamed.
pub fn archive_storage(path: &Path) -> Result<()> {
    let parent = path.parent().ok_or(Error("database_path"))?;
    let _lease = checked(
        fs_private::try_acquire_private_exclusive_file_lease(&path.with_extension("lease")),
        "session_already_open",
    )?;
    if !path.exists() {
        return Ok(());
    }
    let wal = path.with_file_name(format!(
        "{}-wal",
        path.file_name()
            .ok_or(Error("database_path"))?
            .to_string_lossy()
    ));
    if std::fs::metadata(wal).is_ok_and(|m| m.len() > 0) {
        return Err(Error("uncheckpointed_recovery"));
    }
    let destination = parent.join("archives");
    checked(
        fs_private::create_dir_all_private(&destination),
        "archive_directory",
    )?;
    let name = format!("{}-{}.db", now_ms(), hex::encode(random32()));
    checked(
        std::fs::rename(path, destination.join(name)),
        "archive_failed",
    )?;
    Ok(())
}

#[cfg(all(test, feature = "local-harness"))]
mod deletion_tests {
    use super::*;

    #[test]
    fn full_provenance_bucket_cannot_prevent_payload_deletion() {
        let keys = nostr::Keys::generate();
        let directory = tempfile::tempdir().unwrap();
        let mut node = Node::open(
            &directory.path().join("session.db"),
            SqlCipherKey::new(nostr::Keys::generate().public_key().to_hex()).unwrap(),
            Config {
                account: keys.public_key().to_hex(),
                relays: vec!["ws://127.0.0.1:9".into()],
                local_test: true,
            },
            Arc::new(keys.clone()),
            Arc::new(LocalProofSigner(keys)),
        )
        .unwrap();
        // This is a storage test: no network or authority is asserted by the
        // fixture fence. Disposal must match the exact pre-existing row.
        let fence = Fence {
            group: "synthetic-group".into(),
            binding: "b".repeat(64),
            epoch: 1,
            local_account: "a".repeat(64),
            local_leaf: "c".repeat(64),
            peer_account: "d".repeat(64),
            peer_leaf: "e".repeat(64),
        };
        let source = "f".repeat(64);
        let key = format!("in/{source}");
        node.put(
            &key,
            &Inbox {
                source: source.clone(),
                fence: fence.clone(),
                kind: 1223,
                tags: vec![vec!["synthetic private metadata".into()]],
                content: "synthetic private payload".into(),
                acknowledged: false,
                invalidated: false,
                retained_until: now_ms() + 60_000,
            },
        )
        .unwrap();
        node.put(
            &Node::acknowledged_key(&source, &fence).unwrap(),
            &AcknowledgedSources {
                fence: fence.clone(),
                sources: (0..1024)
                    .map(|n| RetainedSource {
                        source: format!("{n:064x}"),
                        until: now_ms() + 60_000,
                    })
                    .collect(),
            },
        )
        .unwrap();
        node.discard_continuous_inbox(&source, &fence).unwrap();
        let retained = node.get::<Inbox>(&key).unwrap().unwrap();
        assert!(retained.content.is_empty() && retained.tags.is_empty());
        assert!(retained.acknowledged);
        assert_eq!(retained.source, source);
        assert_eq!(retained.fence, fence);
        // The deliberately noncanonical fixture is retired during reconcile.
        // Repeating disposal may now remove its row entirely; in either case
        // no payload or tags can return.
        node.discard_continuous_inbox(&source, &fence).unwrap();
        assert_eq!(
            node.private_storage_matches("synthetic private").unwrap(),
            0
        );
    }
}
