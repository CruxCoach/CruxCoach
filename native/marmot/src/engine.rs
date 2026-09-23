//! MLS state for one account: discovery, pairwise groups, sending, ingest and
//! convergence. Every mutation runs in one SQLCipher transaction together with
//! the host rows it produces (outbound event, delivery, token, inbox, cursor).
use crate::error::{Error, Result, checked};
use crate::protocol::{
    APP_KIND, MAX_APP_CONTENT, MAX_LIVE_GROUPS, MAX_PEERS, app_tags, is_hex64, now_ms, now_s,
};
use crate::store::{OWN_RETENTION_MS, Origin, Peer, Store};
use crate::transport::permitted;
use cgka_engine::account_identity_proof::AccountIdentityProofSigner;
use cgka_session::{AccountDeviceSession, PublishWork, SessionConfig, SessionEffects};
use cgka_traits::app_components::{
    AppComponentData, NOSTR_ROUTING_COMPONENT_ID, NostrRoutingV1, decode_nostr_routing_v1,
    encode_nostr_routing_v1,
};
use cgka_traits::app_event::MarmotAppEvent;
use cgka_traits::engine::{CreateGroupRequest, GroupEvent, KeyPackage, SendIntent};
use cgka_traits::group::ProtocolProfile;
use cgka_traits::storage::{GroupStorage, MessageStorage};
use cgka_traits::transport::{TransportEnvelope, TransportMessage};
use cgka_traits::{
    EpochState, GroupId, MemberId, MessageId, OutboundFanout, TransportEndpoint,
    TransportPublishRequest, TransportPublishTarget,
};
use nostr::base64::{Engine as _, engine::general_purpose::STANDARD as BASE64};
use nostr::{Event, EventBuilder, Filter, Kind, NostrSigner, PublicKey, Tag};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use storage_sqlite::SqlCipherKey;
use transport_nostr_adapter::NostrKeyPackagePublication;
use transport_nostr_peeler::{NostrMlsPeeler, NostrTransportEvent};

fn random32() -> [u8; 32] {
    use nostr::secp256k1::rand::RngCore;
    let mut value = [0u8; 32];
    nostr::secp256k1::rand::rngs::OsRng.fill_bytes(&mut value);
    value
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    hex::encode(Sha256::digest(bytes))
}

fn group(value: &str) -> Result<GroupId> {
    Ok(GroupId::new(checked(hex::decode(value), "group_id")?))
}

#[derive(Clone)]
pub struct LocalProofSigner(pub nostr::Keys);
impl AccountIdentityProofSigner for LocalProofSigner {
    fn sign_account_identity_proof(
        &self,
        request: &cgka_engine::account_identity_proof::AccountIdentityProofRequest,
    ) -> std::result::Result<[u8; 64], String> {
        let event = request
            .proof_event()?
            .sign_with_keys(&self.0)
            .map_err(|_| "signer".to_string())?;
        request.signature_from_signed_event(event)
    }
}

/// What a caller must react to after an engine step.
#[derive(Default, Debug, Clone, Copy)]
pub struct Changes {
    pub inbox: bool,
    pub peers: bool,
    pub outbound: bool,
}

impl Changes {
    fn merge(&mut self, other: Changes) {
        self.inbox |= other.inbox;
        self.peers |= other.peers;
        self.outbound |= other.outbound;
    }
}

pub struct Engine {
    pub account: String,
    session: AccountDeviceSession,
    store: Store,
    runtime: tokio::runtime::Runtime,
    signer: Arc<dyn NostrSigner>,
    local_test: bool,
    poisoned: bool,
    database_path: PathBuf,
    _lease: fs_private::PrivateExclusiveFileLease,
}

impl Engine {
    pub fn open(
        path: &Path,
        database_key: SqlCipherKey,
        account: &str,
        initial_relays: &[String],
        local_test: bool,
        signer: Arc<dyn NostrSigner>,
        proof: Arc<dyn AccountIdentityProofSigner>,
    ) -> Result<Self> {
        // Production builds redact panic payloads; the loopback harness keeps
        // them so failing tests remain diagnosable.
        #[cfg(not(feature = "local-harness"))]
        static PANIC_PRIVACY: std::sync::Once = std::sync::Once::new();
        #[cfg(not(feature = "local-harness"))]
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
        if !is_hex64(account) {
            return Err(Error("account"));
        }
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
        let runtime = checked(
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build(),
            "runtime",
        )?;
        let public = checked(runtime.block_on(signer.get_public_key()), "signer")?;
        if public.to_hex() != account {
            return Err(Error("account_mismatch"));
        }
        let session = AccountDeviceSession::open(
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
        )
        .map_err(|error| {
            #[cfg(feature = "local-harness")]
            eprintln!("native session open failed: {error:?}");
            let _ = error;
            Error("native_open")
        })?;
        let store = Store::open(session.cruxcoach_storage(), account)?;
        let engine = Self {
            account: account.to_string(),
            session,
            store,
            runtime,
            signer,
            local_test,
            poisoned: false,
            database_path: path.into(),
            _lease: lease,
        };
        if engine.store.meta("relays")?.is_none() {
            engine.set_relays(initial_relays)?;
        }
        engine.refresh_interest()?;
        Ok(engine)
    }

    pub fn store(&self) -> &Store {
        &self.store
    }

    pub fn relays(&self) -> Result<Vec<String>> {
        let raw = self.store.meta("relays")?.ok_or(Error("relays_missing"))?;
        checked(serde_json::from_str(&raw), "relays_format")
    }

    pub fn set_relays(&self, relays: &[String]) -> Result<()> {
        let relays = crate::transport::normalize_relays(relays, self.local_test)?;
        self.store.put_meta(
            "relays",
            &checked(serde_json::to_string(&relays), "encode")?,
        )
    }

    pub fn local_test(&self) -> bool {
        self.local_test
    }

    pub fn discovery_enabled(&self) -> Result<bool> {
        Ok(self.store.meta("discovery")?.as_deref() == Some("1"))
    }

    fn atomic<T>(&mut self, f: impl FnOnce(&mut Self) -> Result<T>) -> Result<T> {
        if self.poisoned {
            return Err(Error("reopen_required"));
        }
        let store = self.store.clone();
        let result = store.transaction(|| f(self));
        // A rolled-back transaction can leave the in-memory ratchet ahead of
        // SQLCipher. Never reuse it: the host must reopen and rehydrate.
        if let Err(error) = &result {
            // Fixed codes only; never payloads or provider messages.
            #[cfg(feature = "local-harness")]
            eprintln!("marmot engine transaction rolled back: {}", error.0);
            let _ = error;
            self.poisoned = true;
        }
        result
    }

    pub fn poisoned(&self) -> bool {
        self.poisoned
    }

    fn sign_event(&self, kind: u16, tags: Vec<Vec<String>>, content: String) -> Result<Event> {
        let tags = tags
            .into_iter()
            .map(|tag| checked(Tag::parse(tag), "tag"))
            .collect::<Result<Vec<_>>>()?;
        let public = checked(PublicKey::from_hex(&self.account), "account")?;
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
        Ok(signed)
    }

    fn stage_own(
        &self,
        event: &Event,
        peer: Option<&str>,
        core: bool,
        relays: &[String],
        expires_at: u64,
    ) -> Result<()> {
        self.store
            .insert_event(event, Origin::Own, peer, core, expires_at)?;
        self.store.add_deliveries(&event.id.to_hex(), relays)
    }

    // ---- discovery --------------------------------------------------------

    /// Explicit opt-in: NIP-65, NIP-17 inbox list and a Marmot KeyPackage.
    /// The KeyPackage private init key stays in SQLCipher.
    pub fn publish_discovery(&mut self) -> Result<bool> {
        let relays = self.relays()?;
        let pool = sha256_hex(&checked(serde_json::to_vec(&relays), "encode")?);
        self.atomic(|engine| {
            let mut changed = false;
            for kind in [10002u16, 10050] {
                let key = format!("discovery/{kind}");
                let current = engine.store.meta(&key)?;
                let fresh = current
                    .as_ref()
                    .map(|id| engine.store.exists(id))
                    .transpose()?
                    .unwrap_or(false)
                    && engine.store.meta(&format!("{key}/pool"))?.as_deref() == Some(pool.as_str());
                if !fresh {
                    let tag = if kind == 10002 { "r" } else { "relay" };
                    let tags = relays
                        .iter()
                        .map(|r| vec![tag.to_string(), r.clone()])
                        .collect();
                    let event = engine.sign_event(kind, tags, String::new())?;
                    engine.stage_own(&event, None, false, &relays, now_ms() + 30 * 86_400_000)?;
                    engine.store.put_meta(&key, &event.id.to_hex())?;
                    engine.store.put_meta(&format!("{key}/pool"), &pool)?;
                    changed = true;
                }
            }
            let owned = checked(
                engine.session.durably_owned_key_packages(),
                "keypackage_storage",
            )?;
            let usable = engine
                .store
                .meta("discovery/keypackage")?
                .map(|id| engine.store.event(&id))
                .transpose()?
                .flatten()
                .is_some_and(|event| {
                    engine
                        .store
                        .meta("discovery/keypackage/until")
                        .ok()
                        .flatten()
                        .and_then(|v| v.parse::<u64>().ok())
                        .is_some_and(|until| until > now_ms() + 86_400_000)
                        && engine
                            .store
                            .meta("discovery/keypackage/pool")
                            .ok()
                            .flatten()
                            .as_deref()
                            == Some(pool.as_str())
                        && owned
                            .iter()
                            .any(|kp| BASE64.encode(&kp.bytes) == event.content)
                });
            if !usable {
                let kp = checked(
                    engine.runtime.block_on(engine.session.fresh_key_package()),
                    "keypackage",
                )?;
                let metadata = checked(
                    engine.session.key_package_metadata(&kp),
                    "keypackage_metadata",
                )?;
                // A random slot, unrelated to identity, stable across refreshes.
                let slot = match engine.store.meta("discovery/slot")? {
                    Some(slot) => slot,
                    None => hex::encode(random32()),
                };
                engine.store.put_meta("discovery/slot", &slot)?;
                let fmt = |values: &[u16]| values.iter().map(|n| format!("0x{n:04x}")).collect();
                let publication = NostrKeyPackagePublication {
                    account_id: engine.session.self_id(),
                    key_package: kp,
                    key_package_slot_id: slot,
                    key_package_ref: metadata.key_package_ref_hex,
                    mls_ciphersuite: format!("0x{:04x}", metadata.ciphersuite),
                    mls_extensions: fmt(&metadata.mls_extensions),
                    mls_proposals: fmt(&metadata.mls_proposals),
                    app_components: fmt(&metadata.app_components),
                    publish_endpoints: relays.iter().cloned().map(TransportEndpoint).collect(),
                };
                let unsigned = checked(publication.to_event(), "keypackage_event")?;
                let event = engine.sign_event(30443, unsigned.tags, unsigned.content)?;
                let until = metadata.not_after.saturating_mul(1000);
                engine.stage_own(&event, None, false, &relays, until)?;
                engine
                    .store
                    .put_meta("discovery/keypackage", &event.id.to_hex())?;
                engine
                    .store
                    .put_meta("discovery/keypackage/until", &until.to_string())?;
                engine.store.put_meta("discovery/keypackage/pool", &pool)?;
                changed = true;
            }
            engine.store.put_meta("discovery", "1")?;
            Ok(changed)
        })
    }

    pub fn disable_discovery(&self) -> Result<()> {
        self.store.put_meta("discovery", "0")
    }

    pub fn key_package_until(&self) -> Result<u64> {
        Ok(self
            .store
            .meta("discovery/keypackage/until")?
            .and_then(|v| v.parse().ok())
            .unwrap_or(0))
    }

    // ---- pairwise groups ------------------------------------------------------

    /// Relay lists advertised by `peer` (from the local store), intersected
    /// with the locally configured pool.
    pub fn peer_relays(&self, peer: &str) -> Result<(Vec<String>, Vec<String>)> {
        let public = checked(PublicKey::from_hex(peer), "peer")?;
        let directory = self.store.query_events(
            &Filter::new()
                .author(public)
                .kinds([Kind::Custom(10002), Kind::Custom(10050)]),
        )?;
        let latest = |kind: u16| {
            directory
                .iter()
                .filter(|e| e.kind.as_u16() == kind && e.created_at.as_secs() <= now_s() + 60)
                .max_by(|a, b| {
                    a.created_at
                        .cmp(&b.created_at)
                        .then_with(|| b.id.cmp(&a.id))
                })
        };
        let configured = self.relays()?;
        let writes: Vec<String> = latest(10002)
            .map(|e| {
                e.tags
                    .iter()
                    .filter_map(|tag| {
                        let t = tag.as_slice();
                        (t.len() >= 2 && t[0] == "r" && (t.len() == 2 || t[2] == "write"))
                            .then(|| t[1].clone())
                    })
                    .collect()
            })
            .unwrap_or_default();
        let inbox: Vec<String> = latest(10050)
            .map(|e| {
                e.tags
                    .iter()
                    .filter_map(|tag| {
                        let t = tag.as_slice();
                        (t.len() == 2 && t[0] == "relay").then(|| t[1].clone())
                    })
                    .collect()
            })
            .unwrap_or_default();
        Ok((
            permitted(&configured, &writes, self.local_test),
            permitted(&configured, &inbox, self.local_test),
        ))
    }

    fn valid_key_package(&self, peer: &str, writes: &[String]) -> Result<KeyPackage> {
        let public = checked(PublicKey::from_hex(peer), "peer")?;
        let events = self.store.query_events(
            &Filter::new()
                .author(public)
                .kind(Kind::Custom(30443))
                .limit(32),
        )?;
        let mut candidates = Vec::new();
        for event in events {
            if event.created_at.as_secs() > now_s() + 60 || event.pubkey != public {
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
            let fmt = |values: &[u16]| values.iter().map(|n| format!("0x{n:04x}")).collect();
            let expected = NostrKeyPackagePublication {
                account_id: MemberId::new(event.pubkey.to_bytes()),
                key_package: kp.clone(),
                key_package_slot_id: transport.single_tag_value("d").unwrap_or("").into(),
                key_package_ref: metadata.key_package_ref_hex.clone(),
                mls_ciphersuite: format!("0x{:04x}", metadata.ciphersuite),
                mls_extensions: fmt(&metadata.mls_extensions),
                mls_proposals: fmt(&metadata.mls_proposals),
                app_components: fmt(&metadata.app_components),
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
        candidates
            .into_iter()
            .next()
            .map(|c| c.2)
            .ok_or(Error("valid_keypackage_missing"))
    }

    /// Create a pairwise group from the peer's verified KeyPackage. The peer's
    /// directory events must already be in the local store.
    pub fn invite(&mut self, peer: &str) -> Result<String> {
        if !is_hex64(peer) {
            return Err(Error("peer"));
        }
        if peer == self.account {
            return Err(Error("self_invite"));
        }
        if let Some(existing) = self.store.peer(peer)?
            && existing.state != "ended"
        {
            return if existing.invited_by_me {
                Ok(existing.group)
            } else {
                Err(Error("invitation_pending"))
            };
        }
        if self
            .store
            .peers()?
            .iter()
            .filter(|p| p.state != "ended")
            .count()
            >= MAX_PEERS
        {
            return Err(Error("peer_quota"));
        }
        let (writes, inbox) = self.peer_relays(peer)?;
        if writes.is_empty() || inbox.is_empty() {
            return Err(Error("peer_relays_not_configured"));
        }
        let kp = self.valid_key_package(peer, &writes)?;
        let relays = self.relays()?;
        self.atomic(|engine| {
            let routing = checked(NostrRoutingV1::new(random32(), relays.clone()), "routing")?;
            let created = checked(
                engine
                    .runtime
                    .block_on(engine.session.create_group(CreateGroupRequest {
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
            engine.store.put_peer(&Peer {
                account: peer.into(),
                group: gid.clone(),
                state: "pending".into(),
                inbox,
                invited_by_me: true,
                created_at: now_ms(),
                last_seen: 0,
                cancelled_at: 0,
                ended_at: 0,
                reason: String::new(),
            })?;
            engine.persist_effects(created.effects, Some(&created.group_id), None)?;
            engine.record_routes()?;
            if engine.qualified_peer(&created.group_id)?.as_deref() != Some(peer) {
                return Err(Error("two_leaves_required"));
            }
            Ok(gid)
        })
        .inspect(|_| {
            let _ = self.refresh_interest();
        })
    }

    pub fn accept(&mut self, peer: &str) -> Result<()> {
        let mut row = self.store.peer(peer)?.ok_or(Error("no_invitation"))?;
        if row.state == "active" {
            return Ok(());
        }
        if row.state != "invited" {
            return Err(Error("no_invitation"));
        }
        if self.qualified_peer(&group(&row.group)?)?.as_deref() != Some(peer) {
            return Err(Error("two_leaves_required"));
        }
        row.state = "active".into();
        row.last_seen = now_ms();
        self.store.put_peer(&row)
    }

    pub fn decline(&mut self, peer: &str) -> Result<()> {
        let Some(row) = self.store.peer(peer)? else {
            return Ok(());
        };
        if row.state != "invited" {
            return Err(Error("no_invitation"));
        }
        self.store.transaction(|| {
            self.store.inbox_purge(peer)?;
            self.store.delete_peer(peer)
        })?;
        self.refresh_interest()
    }

    /// Two leaves, current profile, not terminal, the local account and one
    /// other account as credentials. Returns that other account.
    pub fn qualified_peer(&self, gid: &GroupId) -> Result<Option<String>> {
        let Ok(record) = self.session.group_record(gid) else {
            return Ok(None);
        };
        if record.protocol_profile != ProtocolProfile::Current
            || record.is_terminal()
            || record.unrecoverable
        {
            return Ok(None);
        }
        let Ok(members) = self.session.members(gid) else {
            return Ok(None);
        };
        if members.len() != 2 {
            return Ok(None);
        }
        let ids: Vec<String> = members
            .iter()
            .map(|m| hex::encode(m.id.as_slice()))
            .collect();
        if !ids.contains(&self.account) || ids[0] == ids[1] {
            return Ok(None);
        }
        Ok(ids.into_iter().find(|id| id != &self.account))
    }

    fn stable(&self, gid: &GroupId) -> Result<bool> {
        Ok(matches!(
            self.session.epoch_state(gid),
            Some(EpochState::Stable { .. })
        ) && !checked(
            self.session.has_pending_convergence_inputs(gid),
            "convergence",
        )?)
    }

    /// Durable local admission of one application message. Idempotent by
    /// token; a reused token with different content is a conflict.
    pub fn send(&mut self, peer: &str, token: &str, content: &str) -> Result<(String, bool)> {
        if token.is_empty() || token.len() > 128 || content.len() > MAX_APP_CONTENT {
            return Err(Error("payload_limit"));
        }
        let hash = sha256_hex(content.as_bytes());
        if let Some((event, stored)) = self.store.token(peer, token)? {
            return if stored == hash {
                Ok((event, true))
            } else {
                Err(Error("token_conflict"))
            };
        }
        let row = self.store.peer(peer)?.ok_or(Error("no_session"))?;
        if row.state != "active" {
            return Err(Error("session_not_active"));
        }
        let gid = group(&row.group)?;
        if self.qualified_peer(&gid)?.as_deref() != Some(peer) {
            return Err(Error("two_leaves_required"));
        }
        if !self.stable(&gid)? {
            return Err(Error("session_busy"));
        }
        let account = self.account.clone();
        self.atomic(|engine| {
            let app =
                MarmotAppEvent::new(account, now_s(), APP_KIND, app_tags(), content.to_string());
            let effects = checked(
                engine
                    .runtime
                    .block_on(engine.session.send(SendIntent::AppMessage {
                        group_id: gid.clone(),
                        payload: checked(app.encode(), "app_encode")?,
                    })),
                "native_send",
            )?;
            // A stable epoch never needs MDK regeneration; refuse rather than
            // queue private data for a later, different epoch.
            if !effects.queued.is_empty() {
                return Err(Error("convergence_pending"));
            }
            let event_id = effects
                .publish
                .iter()
                .find_map(|p| match p {
                    PublishWork::ApplicationMessage { msg, .. } => {
                        Some(hex::encode(msg.id.as_slice()))
                    }
                    _ => None,
                })
                .ok_or(Error("native_handoff_missing"))?;
            engine.persist_effects(effects, Some(&gid), Some(peer))?;
            engine.store.put_token(peer, token, &event_id, &hash)?;
            Ok((event_id, false))
        })
    }

    pub fn cancel(&mut self, peer: &str) -> Result<usize> {
        self.store.transaction(|| {
            let cancelled = self.store.cancel_unreplicated(peer)?;
            if let Some(mut row) = self.store.peer(peer)? {
                row.cancelled_at = now_ms();
                self.store.put_peer(&row)?;
            }
            Ok(cancelled)
        })
    }

    /// Stop delivering this peer's messages and forget pending plaintext.
    /// Already-queued outbound events (for example an `end` message) are still
    /// delivered by the replicator until they expire.
    pub fn end(&mut self, peer: &str, reason: &str) -> Result<()> {
        let Some(mut row) = self.store.peer(peer)? else {
            return Ok(());
        };
        self.store.transaction(|| {
            self.store.inbox_purge(peer)?;
            if row.state != "ended" {
                row.state = "ended".into();
                row.ended_at = now_ms();
                row.reason = reason.into();
                self.store.put_peer(&row)?;
            }
            Ok(())
        })?;
        self.refresh_interest()
    }

    // ---- effects and routes ---------------------------------------------------

    fn persist_effects(
        &mut self,
        effects: SessionEffects,
        default_group: Option<&GroupId>,
        app_peer: Option<&str>,
    ) -> Result<()> {
        for work in effects.publish {
            match work {
                PublishWork::FoundingGroupCreated { welcomes } => {
                    for msg in welcomes {
                        self.stage_transport(msg, default_group, None, vec![], None)?;
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
                    self.stage_transport(msg, Some(&group_id), None, vec![], app_peer)?;
                }
                PublishWork::GroupEvolution {
                    msg,
                    welcomes,
                    pending,
                } => self.stage_transport(msg, default_group, Some(pending), welcomes, None)?,
                PublishWork::AutoPublish { msg, pending } => {
                    self.stage_transport(msg, default_group, Some(pending), vec![], None)?
                }
                PublishWork::Proposal { msg, .. } => {
                    self.stage_transport(msg, default_group, None, vec![], None)?
                }
                PublishWork::GroupCreated { .. } => return Err(Error("legacy_profile_forbidden")),
            }
        }
        Ok(())
    }

    fn routing(&self, gid: &GroupId) -> Result<NostrRoutingV1> {
        let bytes = checked(
            self.session.app_component(gid, NOSTR_ROUTING_COMPONENT_ID),
            "routing",
        )?
        .ok_or(Error("routing_missing"))?;
        checked(decode_nostr_routing_v1(&bytes), "routing_format")
    }

    /// Snapshot authenticated routing so messages for a retained earlier route
    /// still reach its relays; MDK decides which routes remain retained.
    fn record_routes(&self) -> Result<()> {
        for peer in self.store.peers()? {
            if let Ok(route) = self.routing(&group(&peer.group)?) {
                let key = format!("route/{}/{}", peer.group, hex::encode(route.nostr_group_id));
                let value = checked(serde_json::to_string(&route.relays), "encode")?;
                if self.store.meta(&key)?.as_deref() != Some(value.as_str()) {
                    self.store.put_meta(&key, &value)?;
                }
            }
        }
        Ok(())
    }

    fn route_endpoints(&self, gid: &GroupId, route_id: &[u8]) -> Result<Vec<String>> {
        let configured = self.relays()?;
        let current = self.routing(gid)?;
        if current.nostr_group_id.as_slice() == route_id {
            return Ok(permitted(&configured, &current.relays, self.local_test));
        }
        let retained = self
            .store
            .raw()
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
        let relays: Vec<String> = checked(
            serde_json::from_str(
                &self
                    .store
                    .meta(&key)?
                    .ok_or(Error("retained_route_missing"))?,
            ),
            "route_format",
        )?;
        Ok(permitted(&configured, &relays, self.local_test))
    }

    fn stage_transport(
        &mut self,
        msg: TransportMessage,
        gid: Option<&GroupId>,
        pending: Option<cgka_traits::engine_state::PendingStateRef>,
        welcomes: Vec<TransportMessage>,
        app_peer: Option<&str>,
    ) -> Result<()> {
        let transport = checked(
            NostrTransportEvent::from_transport_message(&msg),
            "transport_event",
        )?;
        let event = checked(transport.to_verified_nostr_event(), "outbound_signature")?;
        let resolved = if gid.is_none() {
            match &msg.envelope {
                TransportEnvelope::GroupMessage { transport_group_id } => self
                    .store
                    .raw()
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
                let peer = self
                    .store
                    .peer(&hex::encode(recipient.as_slice()))?
                    .ok_or(Error("inbox_unknown"))?;
                let relays = permitted(&self.relays()?, &peer.inbox, self.local_test);
                (
                    TransportPublishTarget::Inbox {
                        recipient: recipient.clone(),
                        endpoints: relays.iter().cloned().map(TransportEndpoint).collect(),
                    },
                    relays,
                )
            }
        };
        if relays.is_empty() {
            return Err(Error("no_permitted_relay"));
        }
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
            if let Err(error) = self.session.put_outbound_fanout(&fanout) {
                #[cfg(feature = "local-harness")]
                eprintln!("stage put failed: {error:?}");
                let _ = error;
                return Err(Error("fanout_store"));
            }
        }
        self.stage_own(
            &event,
            app_peer,
            pending.is_some(),
            &relays,
            now_ms() + OWN_RETENTION_MS,
        )
    }

    // ---- ingest, convergence, confirmations -----------------------------------------

    pub fn ingest(&mut self, budget: usize) -> Result<Changes> {
        let mut changes = Changes::default();
        let mut cursor: i64 = self
            .store
            .meta("ingest")?
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);
        let batch = self.store.remote_after(cursor, budget)?;
        for (seq, event) in batch {
            let live = checked(self.session.live_group_ids(), "groups")?.len();
            let message = NostrTransportEvent::from_nostr_event(&event)
                .and_then(|e| e.to_transport_message())
                .ok();
            let step = self.atomic(|engine| {
                let mut step = Changes::default();
                if let Some(message) = message
                    && !(event.kind.as_u16() == 1059 && live >= MAX_LIVE_GROUPS)
                {
                    match engine.runtime.block_on(engine.session.ingest(message)) {
                        Ok(ingest) => {
                            if ingest.left_object_unpersisted
                                || matches!(
                                    ingest.outcome,
                                    cgka_traits::ingest::IngestOutcome::ResourceRefused { .. }
                                )
                            {
                                return Err(Error("native_ingest_quota"));
                            }
                            step.outbound |= !ingest.effects.publish.is_empty();
                            engine.persist_effects(ingest.effects, None, None)?;
                        }
                        // Malformed remote objects are dropped; backend errors roll
                        // back and force rehydration, never a fake acknowledgement.
                        Err(cgka_session::SessionError::Engine(
                            cgka_traits::error::EngineError::Peeler(_)
                            | cgka_traits::error::EngineError::InvalidWelcome
                            | cgka_traits::error::EngineError::WelcomeAlreadyProcessed,
                        )) => {}
                        Err(_) => return Err(Error("native_ingest_failed")),
                    }
                }
                step.merge(engine.reconcile()?);
                engine.store.put_meta("ingest", &seq.to_string())?;
                Ok(step)
            })?;
            cursor = seq;
            changes.merge(step);
        }
        let _ = cursor;
        Ok(changes)
    }

    /// Turn MDK's pending application events into host state.
    fn reconcile(&mut self) -> Result<Changes> {
        let mut changes = Changes::default();
        self.record_routes()?;
        for event in self.store.raw().list_pending_application_events()? {
            match event {
                GroupEvent::GroupJoined {
                    group_id,
                    via_welcome,
                    ..
                } => {
                    if let Some(peer) = self.qualified_peer(&group_id)? {
                        let existing = self.store.peer(&peer)?;
                        let active = self
                            .store
                            .peers()?
                            .iter()
                            .filter(|p| p.state != "ended")
                            .count();
                        if existing.as_ref().is_none_or(|p| p.state == "ended")
                            && active < MAX_PEERS
                        {
                            self.store.put_peer(&Peer {
                                account: peer,
                                group: hex::encode(group_id.as_slice()),
                                state: "invited".into(),
                                inbox: vec![],
                                invited_by_me: false,
                                created_at: now_ms(),
                                last_seen: 0,
                                cancelled_at: 0,
                                ended_at: 0,
                                reason: String::new(),
                            })?;
                            changes.peers = true;
                        }
                    }
                    self.store
                        .raw()
                        .delete_pending_application_events(&[via_welcome])?;
                }
                GroupEvent::MessageReceived {
                    group_id,
                    message_id,
                    sender,
                    payload,
                    ..
                } => {
                    let gid = hex::encode(group_id.as_slice());
                    let sender = hex::encode(sender.as_slice());
                    let row = self
                        .store
                        .peers()?
                        .into_iter()
                        .find(|p| p.group == gid && p.account == sender && p.state != "ended");
                    if let Some(mut row) = row
                        && self.qualified_peer(&group_id)?.as_deref() == Some(sender.as_str())
                        && let Ok(app) = MarmotAppEvent::decode(&payload)
                        && app.validate_sender(&sender).is_ok()
                        && app.kind == APP_KIND
                        && app.tags == app_tags()
                    {
                        let source = hex::encode(message_id.as_slice());
                        if self.store.inbox_insert(&source, &sender, &app.content)? {
                            changes.inbox = true;
                        }
                        if row.state == "pending" {
                            row.state = "active".into();
                            changes.peers = true;
                        }
                        row.last_seen = now_ms();
                        self.store.put_peer(&row)?;
                    }
                    self.store
                        .raw()
                        .delete_pending_application_events(&[message_id])?;
                }
                _ => {}
            }
        }
        if changes.peers {
            self.refresh_interest()?;
        }
        Ok(changes)
    }

    /// Convergence, fanout confirmation, qualification and upkeep.
    pub fn advance(&mut self) -> Result<Changes> {
        let mut changes = Changes::default();
        for peer in self.store.peers()? {
            if peer.state == "ended" {
                continue;
            }
            let gid = group(&peer.group)?;
            if self.session.group_record(&gid).is_err() {
                continue;
            }
            let pending = checked(
                self.session.has_pending_convergence_inputs(&gid),
                "convergence",
            )?;
            let due = checked(
                self.session.prepare_convergence_cutoff_delay_ms(&gid),
                "convergence",
            )? == Some(0);
            if pending || due {
                self.atomic(|engine| {
                    let effects = checked(
                        engine
                            .runtime
                            .block_on(engine.session.advance_convergence_inputs(&gid)),
                        "convergence",
                    )?;
                    changes.outbound |= !effects.publish.is_empty();
                    engine.persist_effects(effects, Some(&gid), None)
                })?;
            }
        }
        for (event_id, relay, position) in self.store.unconfirmed_core()? {
            self.atomic(|engine| {
                let mid = MessageId::new(checked(hex::decode(&event_id), "event_id")?);
                // One accepted relay satisfies required_acks; a confirmed fanout
                // is frozen and later acceptances only update host rows.
                if let Some(mut fanout) = checked(engine.session.outbound_fanouts(), "fanouts")?
                    .into_iter()
                    .find(|f| f.message_id() == &mid && f.pending_ref().is_some())
                {
                    let group_id = fanout.group_id().cloned();
                    // MDK's successor rule requires Attempting before Accepted.
                    checked(
                        fanout.mark_attempt_started_at(position, now_ms()),
                        "fanout_attempt",
                    )?;
                    checked(
                        engine.session.put_outbound_fanout(&fanout),
                        "fanout_attempt_store",
                    )?;
                    checked(fanout.mark_target_accepted(position), "fanout_ack")?;
                    checked(fanout.record_published_message_id(mid.clone()), "fanout_id")?;
                    if let Err(error) = engine.session.put_outbound_fanout(&fanout) {
                        #[cfg(feature = "local-harness")]
                        eprintln!("confirm put failed: {error:?}");
                        let _ = error;
                        return Err(Error("fanout_confirm_store"));
                    }
                    if let Some(pending) = fanout.pending_ref() {
                        let effects = checked(
                            engine.runtime.block_on(
                                engine
                                    .session
                                    .confirm_published_fanout(pending, &mut fanout),
                            ),
                            "confirm",
                        )?;
                        changes.outbound |= !effects.publish.is_empty();
                        engine.persist_effects(effects, group_id.as_ref(), None)?;
                    }
                }
                engine.store.mark_confirmed(&event_id, &relay)
            })?;
        }
        self.record_routes()?;
        // A group that lost its two-account shape ends fail-closed.
        for mut peer in self.store.peers()? {
            if !matches!(peer.state.as_str(), "active" | "pending") {
                continue;
            }
            let gid = group(&peer.group)?;
            if self.session.group_record(&gid).is_ok()
                && self.qualified_peer(&gid)?.as_deref() != Some(peer.account.as_str())
            {
                self.store.transaction(|| {
                    self.store.inbox_purge(&peer.account)?;
                    peer.state = "ended".into();
                    peer.ended_at = now_ms();
                    peer.reason = "unqualified".into();
                    self.store.put_peer(&peer)
                })?;
                changes.peers = true;
            }
        }
        if self.discovery_enabled()? && self.key_package_until()? <= now_ms() + 86_400_000 {
            changes.outbound |= self.publish_discovery()?;
        }
        self.store.prune()?;
        if changes.peers {
            self.refresh_interest()?;
        }
        Ok(changes)
    }

    /// Current relay-side routes and peer authors the store may admit.
    pub fn refresh_interest(&self) -> Result<()> {
        let mut routes = std::collections::BTreeSet::new();
        let mut authors = std::collections::BTreeSet::new();
        for peer in self.store.peers()? {
            authors.insert(peer.account.clone());
            if peer.state == "ended" {
                continue;
            }
            if let Ok(gid) = group(&peer.group) {
                if let Ok(route) = self.routing(&gid) {
                    routes.insert(hex::encode(route.nostr_group_id));
                }
                for retained in self.store.raw().list_transport_group_routes()? {
                    if retained.group_id == gid {
                        routes.insert(hex::encode(retained.transport_group_id));
                    }
                }
            }
        }
        if let Ok(mut interest) = self.store.interest.write() {
            interest.routes = routes;
            interest
                .authors
                .retain(|a| authors.contains(a) || a.len() == 64);
            interest.authors.extend(authors);
        }
        Ok(())
    }

    pub fn add_author_interest(&self, author: &str) {
        if let Ok(mut interest) = self.store.interest.write() {
            interest.authors.insert(author.to_string());
        }
    }

    pub fn routes(&self) -> Vec<String> {
        self.store
            .interest
            .read()
            .map(|i| i.routes.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub fn status(&self) -> Result<Value> {
        let (events, inbox) = self.store.counts()?;
        let peers: Vec<Value> = self
            .store
            .peers()?
            .into_iter()
            .map(|p| {
                let pending = self.store.pending_for_peer(&p.account).unwrap_or(0);
                let mut value = serde_json::to_value(&p).unwrap_or(Value::Null);
                value["outbound_pending"] = json!(pending);
                value
            })
            .collect();
        Ok(json!({
            "account": self.account,
            "discovery": {"enabled": self.discovery_enabled()?, "key_package_until": self.key_package_until()?},
            "local": {"events": events, "inbox": inbox, "outbound_pending": self.store.outbound_pending()?},
            "peers": peers,
            "database_bytes": std::fs::metadata(&self.database_path).map(|m| m.len()).unwrap_or(0),
        }))
    }

    /// Adversarial harness: an MLS self-update (epoch change) through the
    /// official intent. Not compiled into Android.
    #[cfg(feature = "local-harness")]
    pub fn rotate(&mut self, peer: &str) -> Result<()> {
        let row = self.store.peer(peer)?.ok_or(Error("no_session"))?;
        let gid = group(&row.group)?;
        self.atomic(|engine| {
            let effects = checked(
                engine
                    .runtime
                    .block_on(engine.session.send(SendIntent::SelfUpdate {
                        group_id: gid.clone(),
                    })),
                "rotate",
            )?;
            engine.persist_effects(effects, Some(&gid), None)
        })
    }

    #[cfg(feature = "local-harness")]
    pub fn epoch(&self, peer: &str) -> Result<u64> {
        let row = self.store.peer(peer)?.ok_or(Error("no_session"))?;
        Ok(
            checked(self.session.group_record(&group(&row.group)?), "group")?
                .epoch
                .0,
        )
    }

    /// Harness-only count of plaintext occurrences in host tables and MDK's
    /// application stores. No payload is returned.
    #[cfg(feature = "local-harness")]
    pub fn private_storage_matches(&self, marker: &str) -> Result<usize> {
        use cgka_traits::storage::OutboundIntentStorage;
        if marker.len() < 8 || marker.len() > 256 {
            return Err(Error("marker_limit"));
        }
        let contains = |bytes: &[u8]| bytes.windows(marker.len()).any(|w| w == marker.as_bytes());
        let mut count = self.store.plaintext_matches(marker)?;
        for event in self.store.raw().list_pending_application_events()? {
            count += usize::from(contains(&checked(
                serde_json::to_vec(&event),
                "test_encode",
            )?));
        }
        for group in self.store.raw().list_groups()? {
            for message in self
                .store
                .raw()
                .list_messages(&group, cgka_traits::EpochId(0))?
            {
                count += usize::from(contains(&message.payload));
            }
            for intent in self.store.raw().list_queued_outbound_intents(&group)? {
                count += usize::from(contains(&checked(
                    serde_json::to_vec(&intent),
                    "test_encode",
                )?));
            }
        }
        if self.store.raw().app_message_count()? != 0 {
            return Err(Error("unexpected_chat_store"));
        }
        Ok(count)
    }
}

/// Explicit recovery: keep the old encrypted database intact under
/// `archives/`; a fresh session never imports its groups or permissions.
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
