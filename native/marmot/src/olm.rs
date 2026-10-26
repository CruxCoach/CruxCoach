//! Olm channel engine (spike M0): pairwise vodozemac sessions between two
//! CruxCoach installations, delivered only directly (LAN/BLE), never through
//! a relay or a blob server.
//!
//! * One Olm `Account` per installation, created from the OS CSPRNG, never
//!   derived from the nsec. Its pickle and every session pickle are stored in
//!   serde form in this engine's own SQLCipher database.
//! * The account key binds the Olm identity keys once per installation with an
//!   unpublished kind-27777 event (`cc-share-bind-v1`). Relationships need no
//!   further account signature.
//! * An invitation carries the inviter's binding, one fresh one-time key, the
//!   fallback key and an invitation tag, all signed by the Olm Ed25519 key
//!   together with both npubs and the invitation id (ownership proof).
//! * Invitations start from a contact code the invited person shows as a QR
//!   (their npub and a fresh secret). The inviter's device finds exactly that
//!   device by the code's beacon and hands it the package sealed with the
//!   code's secret. No package is ever offered to an unidentified device.
//! * Acceptance is the first Olm pre-key message; its plaintext names both
//!   npubs and both Olm identities (unknown-key-share check) and carries the
//!   acceptor's binding.
//! * Sending = Olm encryption + outbound frame + session pickle in one
//!   transaction; receiving = decryption + inbox row + pickle + replay record
//!   in one transaction. Nothing is cached in memory between operations, so a
//!   rolled-back transaction leaves no ratchet ahead of the database.
//! * A frame the peer cannot decrypt is answered with an authenticated nack;
//!   the sender withdraws it and rebuilds the session from a spare one-time
//!   key (or the fallback key), the Matrix "unwedging" pattern.
use crate::error::{Error, Result, checked};
use crate::mailbox;
use crate::p2p::{
    self, BEACON_BYTES, ChannelBody, Container, FrameId, KIND_MAILBOX, KIND_REQUEST, KIND_RESPONSE,
    MAX_FRAMES, MAX_SKEW_S, SECTION_CHANNEL, SECTION_OFFER,
};
use crate::protocol::{MAX_APP_CONTENT, MAX_PEERS, is_hex64, now_ms, now_s};
use nostr::{Event, EventId, Kind, NostrSigner, PublicKey, Tag, Timestamp, UnsignedEvent};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::{BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use vodozemac::olm::{
    Account, AccountPickle, OlmMessage, PreKeyMessage, Session, SessionConfig, SessionPickle,
};
use vodozemac::{Curve25519PublicKey, Ed25519PublicKey, Ed25519Signature};

/// Unpublished binding event: the ephemeral kind CruxCoach already uses for
/// never-published auxiliary signatures (pre-approved in Amber), separated by
/// its purpose tag.
pub const BINDING_KIND: u16 = 27777;
pub const BINDING_PURPOSE: &str = "cc-share-bind-v1";
const PACKAGE_MAGIC: &[u8; 4] = b"cci1";
pub const PACKAGE_BYTES: usize = 389;
/// Contact code (QR shown by the person to be invited): magic, their npub,
/// a fresh secret. Valid for an hour, used once.
const CONTACT_MAGIC: &[u8; 4] = b"ccc1";
pub const CONTACT_BYTES: usize = 4 + 32 + 32;
const CONTACT_TTL_MS: u64 = 3_600_000;
const MAX_CONTACT_CODES: usize = 2;
const INVITATION_TTL_S: u64 = 7 * 86_400;
const SEEN_RETENTION_MS: u64 = 30 * 86_400_000;
const ENDED_RETENTION_MS: u64 = 14 * 86_400_000;
const MAX_SESSIONS: usize = 3;
const REKEY_PAUSE_MS: u64 = 30_000;
const SPARE_KEYS: usize = 2;
const MAX_INBOX: i64 = 4_096;
/// Default frame budget of one exchange step (LAN). BLE passes a smaller one.
pub const MAX_OUTBOUND_BYTES: usize = 196_608;
/// `identify` names the device of a scanned contact code with this prefix:
/// the endpoint to hand the sealed invitation to.
pub const OFFER_PREFIX: &str = "offer:";
/// Events of one relationship, direction and day (one signing key) with
/// their expiries: what one deletion may name.
type DeletionGroup = ((String, i64, i64), Vec<(Vec<u8>, i64)>);
/// A mailbox frame without an ack this long after publishing is published
/// again (a mailbox may have lost it); the sync layer repairs the rest.
const MAILBOX_REPUBLISH_S: u64 = 86_400;
/// Acks with no data to travel with wait this long for some.
const MAILBOX_ACK_DELAY_S: u64 = 6 * 3_600;
/// At most this many events per relationship and call (the rest next time).
const MAILBOX_EVENTS_PER_PEER: usize = 8;
const ENVELOPE_APP: u8 = 1;
const ENVELOPE_CONTROL: u8 = 2;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS olm_meta(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS olm_account(
  id INTEGER PRIMARY KEY CHECK(id = 1),
  pickle BLOB NOT NULL,
  fallback TEXT NOT NULL,
  beacon BLOB NOT NULL,
  binding_at INTEGER NOT NULL DEFAULT 0,
  binding_sig TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_peer(
  account TEXT PRIMARY KEY NOT NULL,
  rel TEXT NOT NULL,
  state TEXT NOT NULL,
  inviter INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL DEFAULT 0,
  cancelled_at INTEGER NOT NULL DEFAULT 0,
  ended_at INTEGER NOT NULL DEFAULT 0,
  reason TEXT NOT NULL DEFAULT '',
  package BLOB,
  their_curve TEXT NOT NULL DEFAULT '',
  their_ed TEXT NOT NULL DEFAULT '',
  their_fallback TEXT NOT NULL DEFAULT '',
  their_spares TEXT NOT NULL DEFAULT '[]',
  their_beacon BLOB,
  inviter_tag BLOB,
  acceptor_tag BLOB,
  confirmed INTEGER NOT NULL DEFAULT 0,
  rekey_at INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS olm_session(
  peer TEXT NOT NULL, session_id TEXT NOT NULL, pickle BLOB NOT NULL,
  created_at INTEGER NOT NULL, used_at INTEGER NOT NULL, answered_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(peer, session_id));
CREATE TABLE IF NOT EXISTS olm_out(
  id BLOB PRIMARY KEY NOT NULL, peer TEXT NOT NULL, app INTEGER NOT NULL,
  frame BLOB NOT NULL, created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS olm_out_peer ON olm_out(peer, created_at);
CREATE TABLE IF NOT EXISTS olm_token(
  peer TEXT NOT NULL, token TEXT NOT NULL, frame BLOB NOT NULL, content_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL, PRIMARY KEY(peer, token));
CREATE TABLE IF NOT EXISTS olm_inbox(
  seq INTEGER PRIMARY KEY AUTOINCREMENT, source BLOB NOT NULL UNIQUE, peer TEXT NOT NULL,
  content TEXT NOT NULL, received_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_seen(hash BLOB PRIMARY KEY NOT NULL, peer TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_reply(peer TEXT NOT NULL, id BLOB NOT NULL, nack INTEGER NOT NULL, PRIMARY KEY(peer, id));
CREATE TABLE IF NOT EXISTS olm_base(base_key TEXT PRIMARY KEY NOT NULL, at INTEGER NOT NULL);
DROP TABLE IF EXISTS olm_hint;
CREATE TABLE IF NOT EXISTS olm_contact(tag BLOB PRIMARY KEY NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_offer(peer TEXT PRIMARY KEY NOT NULL, tag BLOB NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_mailbox_out(id BLOB PRIMARY KEY NOT NULL, peer TEXT NOT NULL, published_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_mailbox_ack(peer TEXT PRIMARY KEY NOT NULL, since INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_mailbox_sent(
  id BLOB PRIMARY KEY NOT NULL, peer TEXT NOT NULL, direction INTEGER NOT NULL, day INTEGER NOT NULL,
  expires INTEGER NOT NULL, at INTEGER NOT NULL, withdrawn INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS olm_mailbox_queue(
  id BLOB PRIMARY KEY NOT NULL, peer TEXT NOT NULL, role TEXT NOT NULL, seed TEXT NOT NULL, event TEXT NOT NULL,
  items TEXT NOT NULL, created_at INTEGER NOT NULL, accepted TEXT NOT NULL DEFAULT '[]');
CREATE TABLE IF NOT EXISTS olm_mailbox_list(peer TEXT PRIMARY KEY NOT NULL, relays TEXT NOT NULL, at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS olm_mailbox_seen(
  id BLOB PRIMARY KEY NOT NULL, peer TEXT NOT NULL, direction INTEGER NOT NULL, day INTEGER NOT NULL,
  expires INTEGER NOT NULL, at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0);
";

#[cfg(feature = "local-harness")]
thread_local! {
    /// Test-only fault injection: fail the next N commits on this thread
    /// (crash windows O2/R2).
    pub static FAIL_COMMITS: std::cell::Cell<u32> = const { std::cell::Cell::new(0) };
}

fn b64(bytes: &[u8]) -> String {
    use nostr::base64::Engine as _;
    nostr::base64::engine::general_purpose::STANDARD_NO_PAD.encode(bytes)
}

fn unb64(value: &str) -> Result<Vec<u8>> {
    use nostr::base64::Engine as _;
    checked(
        nostr::base64::engine::general_purpose::STANDARD_NO_PAD.decode(value),
        "encoding",
    )
}

fn arr32(bytes: &[u8]) -> Result<[u8; 32]> {
    bytes.try_into().map_err(|_| Error("encoding"))
}

fn curve(value: &str) -> Result<Curve25519PublicKey> {
    checked(Curve25519PublicKey::from_base64(value), "olm_key")
}

fn ed(value: &str) -> Result<Ed25519PublicKey> {
    checked(Ed25519PublicKey::from_base64(value), "olm_key")
}

/// Poll a signer future that completes without I/O (JNI callback or local keys).
fn block_on<F: std::future::Future>(future: F) -> F::Output {
    let mut future = std::pin::pin!(future);
    let mut context = std::task::Context::from_waker(std::task::Waker::noop());
    loop {
        if let std::task::Poll::Ready(value) = future.as_mut().poll(&mut context) {
            return value;
        }
        std::thread::yield_now();
    }
}

/// The unpublished binding event for `account` over the Olm identity keys.
pub fn binding_event(
    account: &PublicKey,
    curve: &str,
    ed: &str,
    created_at: u64,
) -> Result<UnsignedEvent> {
    let tags = [
        vec!["purpose".to_string(), BINDING_PURPOSE.to_string()],
        vec!["curve25519".to_string(), curve.to_string()],
        vec!["ed25519".to_string(), ed.to_string()],
    ]
    .into_iter()
    .map(|t| checked(Tag::parse(t), "tag"))
    .collect::<Result<Vec<_>>>()?;
    let mut event = UnsignedEvent::new(
        *account,
        Timestamp::from(created_at),
        Kind::Custom(BINDING_KIND),
        tags,
        "",
    );
    event.ensure_id();
    Ok(event)
}

/// Verify a binding given only its compact parts (created_at and signature).
pub fn verify_binding(
    account: &PublicKey,
    curve: &str,
    ed: &str,
    created_at: u64,
    sig: &[u8],
) -> Result<()> {
    let mut unsigned = binding_event(account, curve, ed, created_at)?;
    let id: EventId = unsigned.id();
    let sig = checked(
        nostr::secp256k1::schnorr::Signature::from_slice(sig),
        "binding",
    )?;
    let event = Event::new(
        id,
        *account,
        unsigned.created_at,
        unsigned.kind,
        unsigned.tags.to_vec(),
        "",
        sig,
    );
    checked(event.verify(), "binding")?;
    if created_at > now_s() + 86_400 {
        return Err(Error("binding"));
    }
    Ok(())
}

/// A parsed, not yet verified invitation package.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Package {
    pub inviter: [u8; 32],
    pub invited: [u8; 32],
    pub invite_id: [u8; 16],
    pub curve: [u8; 32],
    pub ed: [u8; 32],
    pub binding_at: u64,
    pub binding_sig: [u8; 64],
    pub otk: [u8; 32],
    pub fallback: [u8; 32],
    pub tag: [u8; 32],
    pub expires_at: u64,
    pub sig: [u8; 64],
}

impl Package {
    fn body(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(PACKAGE_BYTES);
        out.extend(PACKAGE_MAGIC);
        out.push(0);
        out.extend(self.inviter);
        out.extend(self.invited);
        out.extend(self.invite_id);
        out.extend(self.curve);
        out.extend(self.ed);
        out.extend(self.binding_at.to_be_bytes());
        out.extend(self.binding_sig);
        out.extend(self.otk);
        out.extend(self.fallback);
        out.extend(self.tag);
        out.extend(self.expires_at.to_be_bytes());
        out
    }

    fn signed_message(&self) -> Vec<u8> {
        [b"cc-share-invite-v1".as_slice(), &self.body()].concat()
    }

    pub fn encode(&self) -> Vec<u8> {
        [self.body(), self.sig.to_vec()].concat()
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() != PACKAGE_BYTES || &bytes[..4] != PACKAGE_MAGIC || bytes[4] != 0 {
            return Err(Error("invitation_format"));
        }
        let mut at = 5;
        let mut take = |n: usize| {
            let slice = &bytes[at..at + n];
            at += n;
            slice
        };
        let a32 = |s: &[u8]| -> [u8; 32] { s.try_into().expect("fixed layout") };
        let inviter = a32(take(32));
        let invited = a32(take(32));
        let invite_id: [u8; 16] = take(16).try_into().expect("fixed layout");
        let curve = a32(take(32));
        let ed = a32(take(32));
        let binding_at = u64::from_be_bytes(take(8).try_into().expect("fixed layout"));
        let binding_sig: [u8; 64] = take(64).try_into().expect("fixed layout");
        let otk = a32(take(32));
        let fallback = a32(take(32));
        let tag = a32(take(32));
        let expires_at = u64::from_be_bytes(take(8).try_into().expect("fixed layout"));
        let sig: [u8; 64] = take(64).try_into().expect("fixed layout");
        Ok(Self {
            inviter,
            invited,
            invite_id,
            curve,
            ed,
            binding_at,
            binding_sig,
            otk,
            fallback,
            tag,
            expires_at,
            sig,
        })
    }

    /// Everything a receiver can check without any secret: the account
    /// binding (Schnorr over the Olm keys), the Olm Ed25519 signature over
    /// the whole package (ownership proof, signed one-time and fallback key),
    /// usable keys and expiry.
    pub fn verify(&self) -> Result<()> {
        let inviter = checked(PublicKey::from_slice(&self.inviter), "invitation")?;
        let curve_b64 = Curve25519PublicKey::from_bytes(self.curve).to_base64();
        let ed_key = checked(Ed25519PublicKey::from_slice(&self.ed), "invitation")?;
        verify_binding(
            &inviter,
            &curve_b64,
            &ed_key.to_base64(),
            self.binding_at,
            &self.binding_sig,
        )?;
        let sig = checked(Ed25519Signature::from_slice(&self.sig), "invitation")?;
        checked(
            ed_key.verify(&self.signed_message(), &sig),
            "invitation_signature",
        )?;
        if self.expires_at < now_s() {
            return Err(Error("invitation_expired"));
        }
        if self.inviter == self.invited {
            return Err(Error("invitation"));
        }
        Ok(())
    }
}

/// Control messages inside Olm (never visible to Kotlin).
#[allow(clippy::large_enum_variant)] // parsed once per frame, never stored
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(tag = "t", rename_all = "snake_case", deny_unknown_fields)]
enum Control {
    Accept {
        invite: String,
        from: String,
        to: String,
        from_curve: String,
        from_ed: String,
        to_curve: String,
        to_ed: String,
        binding_at: u64,
        binding_sig: String,
        tag: String,
        fallback: String,
        beacon: String,
        spares: Vec<String>,
        sig: String,
    },
    Keys {
        beacon: String,
        fallback: String,
        spares: Vec<String>,
        /// The mailboxes a friend wants its frames in (`wss://`). Empty, and
        /// then left out, until own mailboxes exist; when set it replaces the
        /// mailboxes derived from the relationship.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        mailboxes: Vec<String>,
    },
    Rekey {
        at: u64,
    },
}

#[allow(clippy::too_many_arguments)] // one field per signed value, in wire order
fn accept_message(
    invite: &str,
    from: &str,
    to: &str,
    keys: [&str; 4],
    binding_at: u64,
    tag: &str,
    fallback: &str,
    beacon: &str,
) -> Vec<u8> {
    let mut out = b"cc-share-accept-v1".to_vec();
    for part in [
        invite,
        from,
        to,
        keys[0],
        keys[1],
        keys[2],
        keys[3],
        &binding_at.to_string(),
        tag,
        fallback,
        beacon,
    ] {
        out.extend((part.len() as u32).to_be_bytes());
        out.extend(part.as_bytes());
    }
    out
}

#[derive(Clone, Debug)]
pub struct PeerRow {
    pub account: String,
    pub rel: String,
    pub state: String,
    pub inviter: bool,
    pub created_at: u64,
    pub last_seen: u64,
    pub cancelled_at: u64,
    pub ended_at: u64,
    pub reason: String,
    pub package: Option<Vec<u8>>,
    pub their_curve: String,
    pub their_ed: String,
    pub their_fallback: String,
    pub their_spares: Vec<String>,
    pub their_beacon: Option<Vec<u8>>,
    pub inviter_tag: Option<Vec<u8>>,
    pub acceptor_tag: Option<Vec<u8>>,
    pub confirmed: bool,
    pub rekey_at: u64,
    pub expires_at: u64,
}

impl PeerRow {
    fn link(&self) -> Option<[u8; 32]> {
        let a = arr32(self.inviter_tag.as_deref()?).ok()?;
        let b = arr32(self.acceptor_tag.as_deref()?).ok()?;
        Some(p2p::link_key(&a, &b))
    }
}

const PEER_COLUMNS: &str = "account, rel, state, inviter, created_at, last_seen, cancelled_at, ended_at, reason, package,
  their_curve, their_ed, their_fallback, their_spares, their_beacon, inviter_tag, acceptor_tag, confirmed, rekey_at, expires_at";

fn peer_row(r: &rusqlite::Row) -> rusqlite::Result<PeerRow> {
    let spares: String = r.get(13)?;
    Ok(PeerRow {
        account: r.get(0)?,
        rel: r.get(1)?,
        state: r.get(2)?,
        inviter: r.get(3)?,
        created_at: r.get::<_, i64>(4)? as u64,
        last_seen: r.get::<_, i64>(5)? as u64,
        cancelled_at: r.get::<_, i64>(6)? as u64,
        ended_at: r.get::<_, i64>(7)? as u64,
        reason: r.get(8)?,
        package: r.get(9)?,
        their_curve: r.get(10)?,
        their_ed: r.get(11)?,
        their_fallback: r.get(12)?,
        their_spares: serde_json::from_str(&spares).unwrap_or_default(),
        their_beacon: r.get(14)?,
        inviter_tag: r.get(15)?,
        acceptor_tag: r.get(16)?,
        confirmed: r.get(17)?,
        rekey_at: r.get::<_, i64>(18)? as u64,
        expires_at: r.get::<_, i64>(19)? as u64,
    })
}

/// The frame ids (hex) a mailbox container carries.
fn frames_of(container: &[u8]) -> Result<Vec<String>> {
    let container = Container::decode(container)?;
    Ok(container
        .sections
        .iter()
        .filter_map(|s| ChannelBody::decode(&s.body).ok())
        .flat_map(|b| b.frames.into_iter().map(|(id, _)| hex::encode(id)))
        .collect())
}

fn i64_of(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum KeyKind {
    Link,
    Invitation,
    InvitationUntrusted,
}

#[derive(Default, Debug, Clone, Copy)]
pub struct Changes {
    pub inbox: bool,
    pub peers: bool,
}

/// What an exchange step did: app-visible changes and the relationships it
/// reached (the transport uses them for "deliverable now").
#[derive(Default, Debug, Clone)]
pub struct Served {
    pub changes: Changes,
    pub peers: Vec<String>,
}

/// What processing one incoming container produced.
#[derive(Default)]
struct Absorbed {
    changes: Changes,
    peers: Vec<(String, [u8; 32])>,
}

pub struct OlmEngine {
    pub account: String,
    conn: Connection,
    signer: Arc<dyn NostrSigner>,
    path: PathBuf,
    signer_ops: AtomicU64,
    /// Outstanding requests this device sent: nonce → sent at (ms).
    requests: HashMap<[u8; 16], u64>,
    _lease: fs_private::PrivateExclusiveFileLease,
}

impl OlmEngine {
    pub fn open(
        path: &Path,
        key: &[u8; 32],
        account: &str,
        signer: Arc<dyn NostrSigner>,
    ) -> Result<Self> {
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
        let conn = checked(Connection::open(path), "native_open")?;
        // SQLCipher raw key; secure_delete zeroes freed pages so a replaced
        // pickle does not linger in the file (forward secrecy at rest).
        let mut pragma =
            zeroize::Zeroizing::new(format!("PRAGMA key = \"x'{}'\";", hex::encode(key)));
        let keyed = conn.execute_batch(&pragma);
        pragma.clear();
        checked(keyed, "database_key")?;
        checked(
            conn.execute_batch(
                "PRAGMA cipher_memory_security = OFF; PRAGMA secure_delete = ON; PRAGMA journal_mode = DELETE;
                 PRAGMA temp_store = MEMORY; PRAGMA foreign_keys = ON;",
            ),
            "database_key",
        )?;
        checked(
            conn.query_row("SELECT count(*) FROM sqlite_master", [], |r| {
                r.get::<_, i64>(0)
            }),
            "database_key",
        )?;
        checked(conn.execute_batch(SCHEMA), "storage")?;
        let public = checked(block_on(signer.get_public_key()), "signer")?;
        if public.to_hex() != account {
            return Err(Error("account_mismatch"));
        }
        let mut engine = Self {
            account: account.to_string(),
            conn,
            signer,
            path: path.into(),
            signer_ops: AtomicU64::new(0),
            requests: HashMap::new(),
            _lease: lease,
        };
        match engine.meta("account")? {
            None => engine.put_meta("account", account)?,
            Some(existing) if existing == account => {}
            Some(_) => return Err(Error("account_mismatch")),
        }
        engine.ensure_account()?;
        Ok(engine)
    }

    pub fn signer_ops(&self) -> u64 {
        self.signer_ops.load(Ordering::SeqCst)
    }

    pub fn database_path(&self) -> &Path {
        &self.path
    }

    // ---- storage -----------------------------------------------------------------

    fn sql<R>(&self, f: impl FnOnce(&Connection) -> rusqlite::Result<R>) -> Result<R> {
        f(&self.conn).map_err(Error::from)
    }

    /// One SQLCipher transaction. On any error nothing is kept: sessions and
    /// the account are reloaded from the database by every operation.
    fn atomic<R>(&mut self, f: impl FnOnce(&mut Self) -> Result<R>) -> Result<R> {
        self.conn.execute_batch("BEGIN IMMEDIATE")?;
        let result = f(self);
        #[cfg(feature = "local-harness")]
        let result = result.and_then(|value| {
            let pending = FAIL_COMMITS.get();
            if pending > 0 {
                FAIL_COMMITS.set(pending - 1);
                Err(Error("injected_fault"))
            } else {
                Ok(value)
            }
        });
        match result {
            Ok(value) => {
                if let Err(error) = self.conn.execute_batch("COMMIT") {
                    let _ = self.conn.execute_batch("ROLLBACK");
                    return Err(error.into());
                }
                Ok(value)
            }
            Err(error) => {
                let _ = self.conn.execute_batch("ROLLBACK");
                Err(error)
            }
        }
    }

    pub fn meta(&self, key: &str) -> Result<Option<String>> {
        self.sql(|c| {
            c.query_row("SELECT value FROM olm_meta WHERE key=?1", [key], |r| {
                r.get(0)
            })
            .optional()
        })
    }

    pub fn put_meta(&self, key: &str, value: &str) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "INSERT INTO olm_meta(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                params![key, value],
            )
        })?;
        Ok(())
    }

    fn load_account(&self) -> Result<Account> {
        let pickle: Vec<u8> = self.sql(|c| {
            c.query_row("SELECT pickle FROM olm_account WHERE id=1", [], |r| {
                r.get(0)
            })
        })?;
        let pickle: AccountPickle = checked(serde_json::from_slice(&pickle), "account_pickle")?;
        Ok(Account::from_pickle(pickle))
    }

    fn save_account(&self, account: &Account) -> Result<()> {
        let pickle = zeroize::Zeroizing::new(checked(
            serde_json::to_vec(&account.pickle()),
            "account_pickle",
        )?);
        self.sql(|c| {
            c.execute(
                "UPDATE olm_account SET pickle=?1 WHERE id=1",
                [pickle.as_slice()],
            )
        })?;
        Ok(())
    }

    fn account_row(&self) -> Result<(String, Vec<u8>, u64, String)> {
        self.sql(|c| {
            c.query_row(
                "SELECT fallback, beacon, binding_at, binding_sig FROM olm_account WHERE id=1",
                [],
                |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? as u64, r.get(3)?)),
            )
        })
    }

    fn ensure_account(&mut self) -> Result<()> {
        let exists: Option<i64> = self.sql(|c| {
            c.query_row("SELECT 1 FROM olm_account WHERE id=1", [], |r| r.get(0))
                .optional()
        })?;
        if exists.is_some() {
            return Ok(());
        }
        let beacon = p2p::random::<32>()?;
        self.atomic(|engine| {
            let mut account = Account::new();
            account.generate_fallback_key();
            let fallback = account
                .fallback_key()
                .values()
                .next()
                .copied()
                .ok_or(Error("fallback_key"))?;
            account.mark_keys_as_published();
            let pickle = zeroize::Zeroizing::new(checked(serde_json::to_vec(&account.pickle()), "account_pickle")?);
            engine.sql(|c| {
                c.execute(
                    "INSERT INTO olm_account(id,pickle,fallback,beacon,created_at) VALUES(1,?1,?2,?3,?4)",
                    params![pickle.as_slice(), fallback.to_base64(), beacon.as_slice(), i64_of(now_ms())],
                )
            })?;
            Ok(())
        })
    }

    pub fn identity_keys(&self) -> Result<(String, String)> {
        let account = self.load_account()?;
        Ok((
            account.curve25519_key().to_base64(),
            account.ed25519_key().to_base64(),
        ))
    }

    /// The account signs the Olm identity once per installation (P5: no
    /// per-relationship or per-message signature).
    fn ensure_binding(&mut self) -> Result<(u64, Vec<u8>)> {
        let (_, _, at, sig) = self.account_row()?;
        if at > 0 && !sig.is_empty() {
            return Ok((at, checked(hex::decode(&sig), "binding")?));
        }
        let (curve, ed) = self.identity_keys()?;
        let public = checked(PublicKey::from_hex(&self.account), "account")?;
        let created_at = now_s();
        let unsigned = binding_event(&public, &curve, &ed, created_at)?;
        let expected = unsigned.id;
        self.signer_ops.fetch_add(1, Ordering::SeqCst);
        let signed = checked(block_on(self.signer.sign_event(unsigned)), "signer")?;
        if signed.id != expected.ok_or(Error("binding"))? || signed.pubkey != public {
            return Err(Error("signer_mismatch"));
        }
        let sig = signed.sig.serialize().to_vec();
        verify_binding(&public, &curve, &ed, created_at, &sig)?;
        self.sql(|c| {
            c.execute(
                "UPDATE olm_account SET binding_at=?1, binding_sig=?2 WHERE id=1",
                params![i64_of(created_at), hex::encode(&sig)],
            )
        })?;
        Ok((created_at, sig))
    }

    // ---- peers -------------------------------------------------------------------

    pub fn peers(&self) -> Result<Vec<PeerRow>> {
        self.sql(|c| {
            let mut q = c.prepare(&format!(
                "SELECT {PEER_COLUMNS} FROM olm_peer ORDER BY account"
            ))?;
            q.query_map([], peer_row)?.collect()
        })
    }

    pub fn peer(&self, account: &str) -> Result<Option<PeerRow>> {
        self.sql(|c| {
            c.query_row(
                &format!("SELECT {PEER_COLUMNS} FROM olm_peer WHERE account=?1"),
                [account],
                peer_row,
            )
            .optional()
        })
    }

    fn put_peer(&self, p: &PeerRow) -> Result<()> {
        let spares = checked(serde_json::to_string(&p.their_spares), "encode")?;
        self.sql(|c| {
            c.execute(
                "INSERT INTO olm_peer(account, rel, state, inviter, created_at, last_seen, cancelled_at, ended_at, reason, package,
                   their_curve, their_ed, their_fallback, their_spares, their_beacon, inviter_tag, acceptor_tag, confirmed, rekey_at, expires_at)
                 VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20)
                 ON CONFLICT(account) DO UPDATE SET rel=excluded.rel, state=excluded.state, inviter=excluded.inviter,
                   created_at=excluded.created_at, last_seen=excluded.last_seen, cancelled_at=excluded.cancelled_at,
                   ended_at=excluded.ended_at, reason=excluded.reason, package=excluded.package,
                   their_curve=excluded.their_curve, their_ed=excluded.their_ed, their_fallback=excluded.their_fallback,
                   their_spares=excluded.their_spares, their_beacon=excluded.their_beacon, inviter_tag=excluded.inviter_tag,
                   acceptor_tag=excluded.acceptor_tag, confirmed=excluded.confirmed, rekey_at=excluded.rekey_at,
                   expires_at=excluded.expires_at",
                params![
                    p.account, p.rel, p.state, p.inviter, i64_of(p.created_at), i64_of(p.last_seen),
                    i64_of(p.cancelled_at), i64_of(p.ended_at), p.reason, p.package, p.their_curve, p.their_ed,
                    p.their_fallback, spares, p.their_beacon, p.inviter_tag, p.acceptor_tag, p.confirmed,
                    i64_of(p.rekey_at), i64_of(p.expires_at)
                ],
            )
        })?;
        Ok(())
    }

    /// Forget every secret of a relationship: sessions, tags, keys, queue.
    fn purge_relationship(&self, peer: &str) -> Result<()> {
        self.sql(|c| {
            c.execute("DELETE FROM olm_session WHERE peer=?1", [peer])?;
            c.execute("DELETE FROM olm_out WHERE peer=?1", [peer])?;
            c.execute("DELETE FROM olm_reply WHERE peer=?1", [peer])?;
            c.execute("DELETE FROM olm_inbox WHERE peer=?1", [peer])?;
            for table in ["olm_mailbox_list", "olm_mailbox_out", "olm_mailbox_ack", "olm_mailbox_queue"] {
                c.execute(&format!("DELETE FROM {table} WHERE peer=?1"), [peer])?;
            }
            c.execute(
                "UPDATE olm_peer SET package=NULL, their_spares='[]', their_beacon=NULL, inviter_tag=NULL,
                   acceptor_tag=NULL, their_fallback='' WHERE account=?1",
                [peer],
            )
        })?;
        Ok(())
    }

    // ---- invitations -------------------------------------------------------------

    /// Create (or return) the invitation for `peer`. Nothing is sent here:
    /// the package travels only to the device of a scanned contact code
    /// ([Self::import_contact]).
    pub fn invite(&mut self, peer: &str) -> Result<String> {
        if !is_hex64(peer) || checked(PublicKey::from_hex(peer), "peer").is_err() {
            return Err(Error("peer"));
        }
        if peer == self.account {
            return Err(Error("self_invite"));
        }
        if let Some(existing) = self.peer(peer)? {
            match existing.state.as_str() {
                "pending" | "active" => return Ok(existing.rel),
                "invited" => return Err(Error("invitation_pending")),
                // The friend must first learn that the old friendship ended.
                _ if self.pending_for(peer)? > 0 => return Err(Error("ending_pending")),
                _ => {}
            }
        }
        if self.peers()?.iter().filter(|p| p.state != "ended").count() >= MAX_PEERS {
            return Err(Error("peer_quota"));
        }
        let (binding_at, binding_sig) = self.ensure_binding()?;
        let invited = arr32(&checked(hex::decode(peer), "peer")?)?;
        let inviter = arr32(&checked(hex::decode(&self.account), "account")?)?;
        let invite_id = p2p::random::<16>()?;
        let tag = p2p::random::<32>()?;
        let (fallback, _, _, _) = self.account_row()?;
        self.atomic(|engine| {
            engine.purge_relationship(peer)?;
            let mut account = engine.load_account()?;
            let otk = *account
                .generate_one_time_keys(1)
                .created
                .first()
                .ok_or(Error("one_time_key"))?;
            account.mark_keys_as_published();
            let mut package = Package {
                inviter,
                invited,
                invite_id,
                curve: account.curve25519_key().to_bytes(),
                ed: *account.ed25519_key().as_bytes(),
                binding_at,
                binding_sig: binding_sig
                    .as_slice()
                    .try_into()
                    .map_err(|_| Error("binding"))?,
                otk: otk.to_bytes(),
                fallback: curve(&fallback)?.to_bytes(),
                tag,
                expires_at: now_s() + INVITATION_TTL_S,
                sig: [0; 64],
            };
            package.sig = account.sign(package.signed_message()).to_bytes();
            engine.save_account(&account)?;
            let rel = hex::encode(invite_id);
            engine.put_peer(&PeerRow {
                account: peer.to_string(),
                rel: rel.clone(),
                state: "pending".into(),
                inviter: true,
                created_at: now_ms(),
                last_seen: 0,
                cancelled_at: 0,
                ended_at: 0,
                reason: String::new(),
                package: Some(package.encode()),
                their_curve: String::new(),
                their_ed: String::new(),
                their_fallback: String::new(),
                their_spares: vec![],
                their_beacon: None,
                inviter_tag: Some(tag.to_vec()),
                acceptor_tag: None,
                confirmed: false,
                rekey_at: 0,
                expires_at: package.expires_at,
            })?;
            Ok(rel)
        })
    }

    /// The package of a pending own invitation.
    pub fn invitation(&self, peer: &str) -> Result<Vec<u8>> {
        let row = self.peer(peer)?.ok_or(Error("no_invitation"))?;
        if row.state != "pending" || !row.inviter {
            return Err(Error("no_invitation"));
        }
        row.package.ok_or(Error("no_invitation"))
    }

    /// Show this account as a QR: npub and a fresh secret. While the code is
    /// valid this device advertises its beacon; an invitation sealed to it is
    /// taken once, then the code is used up.
    pub fn contact_code(&mut self) -> Result<Vec<u8>> {
        let tag = p2p::random::<32>()?;
        let account = arr32(&checked(hex::decode(&self.account), "account")?)?;
        self.sql(|c| {
            c.execute(
                "DELETE FROM olm_contact WHERE created_at<?1",
                [i64_of(now_ms().saturating_sub(CONTACT_TTL_MS))],
            )?;
            // Keep the newest codes only: an old screenshot stops working.
            c.execute(
                "DELETE FROM olm_contact WHERE tag NOT IN
                   (SELECT tag FROM olm_contact ORDER BY created_at DESC LIMIT ?1)",
                [(MAX_CONTACT_CODES - 1) as i64],
            )?;
            c.execute(
                "INSERT INTO olm_contact(tag, created_at) VALUES(?1,?2)",
                params![tag.as_slice(), i64_of(now_ms())],
            )
        })?;
        Ok([CONTACT_MAGIC.as_slice(), &account, &tag].concat())
    }

    /// Valid contact codes this device shows.
    fn contact_tags(&self) -> Result<Vec<[u8; 32]>> {
        let tags: Vec<Vec<u8>> = self.sql(|c| {
            let mut q = c.prepare(
                "SELECT tag FROM olm_contact WHERE created_at>=?1 ORDER BY created_at DESC LIMIT ?2",
            )?;
            q.query_map(
                params![
                    i64_of(now_ms().saturating_sub(CONTACT_TTL_MS)),
                    MAX_CONTACT_CODES as i64
                ],
                |r| r.get(0),
            )?
            .collect()
        })?;
        tags.iter().map(|t| arr32(t)).collect()
    }

    /// A scanned contact code: invite that account and hand the package to
    /// the device that shows the code once it is in reach. Returns the peer.
    pub fn import_contact(&mut self, bytes: &[u8]) -> Result<String> {
        if bytes.len() != CONTACT_BYTES || &bytes[..4] != CONTACT_MAGIC {
            return Err(Error("contact_format"));
        }
        let peer = hex::encode(&bytes[4..36]);
        self.invite(&peer)?;
        self.sql(|c| {
            c.execute(
                "INSERT INTO olm_offer(peer, tag, created_at) VALUES(?1,?2,?3)
                   ON CONFLICT(peer) DO UPDATE SET tag=excluded.tag, created_at=excluded.created_at",
                params![peer, &bytes[36..], i64_of(now_ms())],
            )
        })?;
        Ok(peer)
    }

    /// The contact secret to hand `peer`'s pending invitation to, if any.
    fn offer_tag(&self, peer: &str) -> Result<Option<[u8; 32]>> {
        let tag: Option<Vec<u8>> = self.sql(|c| {
            c.query_row(
                "SELECT o.tag FROM olm_offer o JOIN olm_peer p ON p.account=o.peer
                   WHERE o.peer=?1 AND o.created_at>=?2 AND p.state='pending' AND p.inviter=1",
                params![peer, i64_of(now_ms().saturating_sub(CONTACT_TTL_MS))],
                |r| r.get(0),
            )
            .optional()
        })?;
        tag.map(|t| arr32(&t)).transpose()
    }

    /// Store a verified invitation addressed to this account. Returns the
    /// inviter when it became (or already was) a visible invitation.
    pub fn import_invitation(&mut self, bytes: &[u8]) -> Result<Option<String>> {
        let package = Package::decode(bytes)?;
        if hex::encode(package.invited) != self.account {
            return Err(Error("invitation_not_for_me"));
        }
        package.verify()?;
        let inviter = hex::encode(package.inviter);
        let rel = hex::encode(package.invite_id);
        if let Some(existing) = self.peer(&inviter)? {
            match existing.state.as_str() {
                // Already imported or already a friend: nothing new.
                "invited" | "active" if existing.rel == rel => return Ok(Some(inviter)),
                "active" => return Ok(None),
                // Both asked each other: the invitation of the smaller npub
                // wins on both sides, so exactly one relationship forms.
                "pending" if self.account < inviter => return Ok(None),
                _ => {}
            }
        }
        if self
            .peers()?
            .iter()
            .filter(|p| p.state != "ended" && p.account != inviter)
            .count()
            >= MAX_PEERS
        {
            return Err(Error("peer_quota"));
        }
        let crossed = self.peer(&inviter)?.is_some_and(|p| p.state == "pending");
        let imported = self.atomic(|engine| {
            engine.purge_relationship(&inviter)?;
            engine.put_peer(&PeerRow {
                account: inviter.clone(),
                rel,
                state: "invited".into(),
                inviter: false,
                created_at: now_ms(),
                last_seen: now_ms(),
                cancelled_at: 0,
                ended_at: 0,
                reason: String::new(),
                package: Some(bytes.to_vec()),
                their_curve: Curve25519PublicKey::from_bytes(package.curve).to_base64(),
                their_ed: checked(Ed25519PublicKey::from_slice(&package.ed), "invitation")?
                    .to_base64(),
                their_fallback: Curve25519PublicKey::from_bytes(package.fallback).to_base64(),
                their_spares: vec![],
                their_beacon: None,
                inviter_tag: Some(package.tag.to_vec()),
                acceptor_tag: None,
                confirmed: false,
                rekey_at: 0,
                expires_at: package.expires_at,
            })?;
            Ok(Some(inviter.clone()))
        })?;
        if crossed {
            // Both asked each other: this device already asked, so accept.
            self.accept(&inviter)?;
        }
        Ok(imported)
    }

    /// Accept a received invitation: the first Olm message (pre-key) carries
    /// the acceptance with both identities and this account's binding.
    pub fn accept(&mut self, peer: &str) -> Result<()> {
        let row = self.peer(peer)?.ok_or(Error("no_invitation"))?;
        if row.state == "active" {
            return Ok(());
        }
        if row.state != "invited" {
            return Err(Error("no_invitation"));
        }
        let package = Package::decode(row.package.as_deref().ok_or(Error("no_invitation"))?)?;
        package.verify()?;
        let (binding_at, binding_sig) = self.ensure_binding()?;
        let (fallback, beacon, _, _) = self.account_row()?;
        let tag = p2p::random::<32>()?;
        let me = self.account.clone();
        self.atomic(|engine| {
            let mut account = engine.load_account()?;
            let spares = engine.fresh_spares(&mut account)?;
            let (my_curve, my_ed) = (
                account.curve25519_key().to_base64(),
                account.ed25519_key().to_base64(),
            );
            let keys = [
                my_curve.as_str(),
                my_ed.as_str(),
                row.their_curve.as_str(),
                row.their_ed.as_str(),
            ];
            let signed = accept_message(
                &row.rel,
                &me,
                peer,
                keys,
                binding_at,
                &b64(&tag),
                &fallback,
                &b64(&beacon),
            );
            let control = Control::Accept {
                invite: row.rel.clone(),
                from: me.clone(),
                to: peer.to_string(),
                from_curve: my_curve.clone(),
                from_ed: my_ed.clone(),
                to_curve: row.their_curve.clone(),
                to_ed: row.their_ed.clone(),
                binding_at,
                binding_sig: hex::encode(&binding_sig),
                tag: b64(&tag),
                fallback: fallback.clone(),
                beacon: b64(&beacon),
                spares,
                sig: account.sign(&signed).to_base64(),
            };
            let mut session = checked(
                account.create_outbound_session(
                    SessionConfig::version_1(),
                    curve(&row.their_curve)?,
                    Curve25519PublicKey::from_bytes(package.otk),
                ),
                "olm_session",
            )?;
            engine.save_account(&account)?;
            let mut row = row.clone();
            row.state = "active".into();
            row.acceptor_tag = Some(tag.to_vec());
            row.last_seen = now_ms();
            row.package = None;
            engine.put_peer(&row)?;
            engine.queue_control(peer, &mut session, &control, true)?;
            Ok(())
        })
    }

    pub fn decline(&mut self, peer: &str) -> Result<()> {
        let Some(row) = self.peer(peer)? else {
            return Ok(());
        };
        if row.state != "invited" {
            return Err(Error("no_invitation"));
        }
        self.atomic(|engine| {
            engine.purge_relationship(peer)?;
            engine.sql(|c| c.execute("DELETE FROM olm_peer WHERE account=?1", [peer]))?;
            Ok(())
        })
    }

    /// Generate spare one-time keys a friend may use to rebuild a session.
    fn fresh_spares(&self, account: &mut Account) -> Result<Vec<String>> {
        let created = account.generate_one_time_keys(SPARE_KEYS).created;
        account.mark_keys_as_published();
        Ok(created.iter().map(|k| k.to_base64()).collect())
    }

    // ---- sessions -----------------------------------------------------------------

    /// Sessions with `peer`, most recently used first (id breaks exact ties, so
    /// both sides order a simultaneous pair of rebuilt sessions alike).
    fn sessions(&self, peer: &str) -> Result<Vec<(String, Vec<u8>, u64)>> {
        self.sql(|c| {
            let mut q = c.prepare(
                "SELECT session_id, pickle, answered_at FROM olm_session WHERE peer=?1 ORDER BY used_at DESC, session_id DESC",
            )?;
            q.query_map([peer], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? as u64)))?.collect()
        })
    }

    fn load_session(pickle: &[u8]) -> Result<Session> {
        let pickle: SessionPickle = checked(serde_json::from_slice(pickle), "session_pickle")?;
        Ok(Session::from_pickle(pickle))
    }

    /// Store a session. `used` marks it as the one to send on: it decrypted a
    /// message now (`answered`), or this device just created it.
    fn save_session(
        &self,
        peer: &str,
        session: &Session,
        used: bool,
        answered: bool,
    ) -> Result<()> {
        let pickle = zeroize::Zeroizing::new(checked(
            serde_json::to_vec(&session.pickle()),
            "session_pickle",
        )?);
        let now = i64_of(now_ms());
        self.sql(|c| {
            c.execute(
                "INSERT INTO olm_session(peer, session_id, pickle, created_at, used_at, answered_at) VALUES(?1,?2,?3,?4,?4,?5)
                 ON CONFLICT(peer, session_id) DO UPDATE SET pickle=excluded.pickle,
                   used_at=CASE WHEN ?6 THEN excluded.used_at ELSE olm_session.used_at END,
                   answered_at=CASE WHEN ?7 THEN excluded.answered_at ELSE olm_session.answered_at END",
                params![peer, session.session_id(), pickle.as_slice(), now, if answered { now } else { 0 }, used, answered],
            )
        })?;
        // Keep the most recently used few; an old one only receives stragglers.
        for (id, ..) in self.sessions(peer)?.into_iter().skip(MAX_SESSIONS) {
            self.sql(|c| {
                c.execute(
                    "DELETE FROM olm_session WHERE peer=?1 AND session_id=?2",
                    params![peer, id],
                )
            })?;
        }
        Ok(())
    }

    /// The Matrix rule: send on the session that most recently received a
    /// message; a session this device just created counts as used now.
    fn sending_session(&self, peer: &str) -> Result<Option<Session>> {
        self.sessions(peer)?
            .first()
            .map(|s| Self::load_session(&s.1))
            .transpose()
    }

    fn queue_frame(&self, peer: &str, app: bool, message: &OlmMessage) -> Result<FrameId> {
        let (kind, bytes) = message.to_parts();
        let frame = [vec![kind as u8], bytes].concat();
        let id = p2p::random::<8>()?;
        self.sql(|c| {
            c.execute(
                "INSERT INTO olm_out(id, peer, app, frame, created_at) VALUES(?1,?2,?3,?4,?5)",
                params![id.as_slice(), peer, app, frame, i64_of(now_ms())],
            )
        })?;
        Ok(id)
    }

    fn queue_control(
        &self,
        peer: &str,
        session: &mut Session,
        control: &Control,
        fresh: bool,
    ) -> Result<FrameId> {
        let json = checked(serde_json::to_vec(control), "encode")?;
        let message = checked(
            session.encrypt([vec![ENVELOPE_CONTROL], json].concat()),
            "olm_encrypt",
        )?;
        // A session this device created counts as in use from now on.
        self.save_session(peer, session, fresh, false)?;
        self.queue_frame(peer, false, &message)
    }

    /// Rebuild the session to `peer` from a spare one-time key (or the
    /// fallback key when none is left). Rate limited per peer.
    fn rekey(&mut self, peer: &str, force: bool) -> Result<bool> {
        let Some(mut row) = self.peer(peer)? else {
            return Ok(false);
        };
        // One rebuild at a time: another only after a pause, or once the
        // friend answered on a session since the last rebuild.
        let answered = self.sessions(peer)?.iter().map(|s| s.2).max().unwrap_or(0);
        let paused = now_ms() < row.rekey_at + REKEY_PAUSE_MS && answered <= row.rekey_at;
        if row.state != "active" || row.their_curve.is_empty() || (!force && paused) {
            return Ok(false);
        }
        let key = if let Some(spare) = row.their_spares.first().cloned() {
            row.their_spares.remove(0);
            spare
        } else if !row.their_fallback.is_empty() {
            row.their_fallback.clone()
        } else {
            return Ok(false);
        };
        let account = self.load_account()?;
        let mut session = checked(
            account.create_outbound_session(
                SessionConfig::version_1(),
                curve(&row.their_curve)?,
                curve(&key)?,
            ),
            "olm_session",
        )?;
        row.rekey_at = now_ms();
        self.put_peer(&row)?;
        self.queue_control(peer, &mut session, &Control::Rekey { at: now_ms() }, true)?;
        Ok(true)
    }

    // ---- sending ------------------------------------------------------------------

    /// Durable local admission of one application message, idempotent by
    /// token within the relationship.
    pub fn send(&mut self, peer: &str, token: &str, content: &str) -> Result<(String, bool)> {
        if token.is_empty() || token.len() > 128 || content.len() > MAX_APP_CONTENT {
            return Err(Error("payload_limit"));
        }
        let hash = hex::encode(p2p::sha256(content.as_bytes()));
        let row = self.peer(peer)?.ok_or(Error("no_session"))?;
        let token = format!("{}/{token}", row.rel);
        let stored: Option<(Vec<u8>, String)> = self.sql(|c| {
            c.query_row(
                "SELECT frame, content_hash FROM olm_token WHERE peer=?1 AND token=?2",
                params![peer, token],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
        })?;
        if let Some((frame, stored)) = stored {
            return if stored == hash {
                Ok((hex::encode(frame), true))
            } else {
                Err(Error("token_conflict"))
            };
        }
        if row.state != "active" {
            return Err(Error("session_not_active"));
        }
        let pending: i64 = self.sql(|c| {
            c.query_row("SELECT count(*) FROM olm_out WHERE peer=?1", [peer], |r| {
                r.get(0)
            })
        })?;
        if pending >= 2_000 {
            return Err(Error("native_outbound_quota"));
        }
        self.atomic(|engine| {
            if engine.sending_session(peer)?.is_none() {
                // Lost state on this side: rebuild before sending.
                engine.rekey(peer, true)?;
            }
            let mut session = engine.sending_session(peer)?.ok_or(Error("no_session"))?;
            let message = checked(
                session.encrypt([vec![ENVELOPE_APP], content.as_bytes().to_vec()].concat()),
                "olm_encrypt",
            )?;
            engine.save_session(peer, &session, false, false)?;
            let id = engine.queue_frame(peer, true, &message)?;
            engine.sql(|c| {
                c.execute(
                    "INSERT INTO olm_token(peer, token, frame, content_hash, created_at) VALUES(?1,?2,?3,?4,?5)",
                    params![peer, token, id.as_slice(), hash, i64_of(now_ms())],
                )
            })?;
            Ok((hex::encode(id), false))
        })
    }

    /// Withdraw this peer's application frames the peer has not acknowledged.
    /// Once bytes may have reached the peer there is no recall; this is the
    /// direct-transport counterpart of "no relay accepted it yet".
    pub fn cancel(&mut self, peer: &str) -> Result<usize> {
        self.atomic(|engine| {
            let cancelled =
                engine.sql(|c| c.execute("DELETE FROM olm_out WHERE peer=?1 AND app=1", [peer]))?;
            if cancelled > 0 {
                engine.sql(|c| {
                    // What already lies in a mailbox is withdrawn as well: its
                    // events are deleted under their own day's key. Whatever
                    // else they carried (control frames) goes out again at once.
                    c.execute(
                        "UPDATE olm_mailbox_sent SET withdrawn=1 WHERE peer=?1 AND deleted=0",
                        [peer],
                    )?;
                    c.execute("DELETE FROM olm_mailbox_out WHERE peer=?1", [peer])
                })?;
            }
            if cancelled > 0
                && let Some(mut row) = engine.peer(peer)?
            {
                row.cancelled_at = now_ms();
                engine.put_peer(&row)?;
            }
            Ok(cancelled)
        })
    }

    /// Stop receiving from `peer` and purge its undelivered plaintext. Queued
    /// frames (for example the `end` message) are still delivered; the
    /// relationship's secrets are purged once the queue is empty.
    pub fn end(&mut self, peer: &str, reason: &str) -> Result<()> {
        let Some(mut row) = self.peer(peer)? else {
            return Ok(());
        };
        let rotate = row.state == "active";
        self.atomic(|engine| {
            engine.sql(|c| {
                c.execute("DELETE FROM olm_inbox WHERE peer=?1", [peer])?;
                c.execute("DELETE FROM olm_reply WHERE peer=?1", [peer])
            })?;
            if row.state != "ended" {
                row.state = "ended".into();
                row.ended_at = now_ms();
                row.reason = reason.into();
                engine.put_peer(&row)?;
            }
            if rotate {
                // The ex-friend could recognise this device's advertisement:
                // rotate the device beacon and tell the remaining friends.
                let fresh = p2p::random::<32>()?;
                engine.sql(|c| {
                    c.execute(
                        "UPDATE olm_account SET beacon=?1 WHERE id=1",
                        [fresh.as_slice()],
                    )
                })?;
                engine.announce_keys(None)?;
            }
            engine.housekeeping_peer(peer)?;
            Ok(())
        })
    }

    /// Queue a `keys` control message (beacon, fallback, spare keys) to one or
    /// every active friend.
    fn announce_keys(&self, only: Option<&str>) -> Result<()> {
        let (fallback, beacon, _, _) = self.account_row()?;
        let mut account = self.load_account()?;
        for row in self
            .peers()?
            .into_iter()
            .filter(|p| p.state == "active" && only.is_none_or(|o| o == p.account))
        {
            let Some(mut session) = self.sending_session(&row.account)? else {
                continue;
            };
            let spares = if only.is_some() {
                self.fresh_spares(&mut account)?
            } else {
                vec![]
            };
            let control = Control::Keys {
                beacon: b64(&beacon),
                fallback: fallback.clone(),
                spares,
                mailboxes: Vec::new(),
            };
            self.queue_control(&row.account, &mut session, &control, false)?;
        }
        self.save_account(&account)
    }

    fn housekeeping_peer(&self, peer: &str) -> Result<()> {
        let Some(row) = self.peer(peer)? else {
            return Ok(());
        };
        if row.state != "ended" {
            return Ok(());
        }
        let queued: i64 = self.sql(|c| {
            c.query_row("SELECT count(*) FROM olm_out WHERE peer=?1", [peer], |r| {
                r.get(0)
            })
        })?;
        if queued == 0 || now_ms() > row.ended_at + ENDED_RETENTION_MS {
            self.purge_relationship(peer)?;
        }
        Ok(())
    }

    /// Periodic upkeep: expire invitations, purge finished endings, prune
    /// replay records.
    pub fn housekeeping(&mut self) -> Result<Changes> {
        let mut changes = Changes::default();
        self.atomic(|engine| {
            for row in engine.peers()? {
                if matches!(row.state.as_str(), "pending" | "invited")
                    && row.expires_at > 0
                    && row.expires_at < now_s()
                {
                    let mut row = row.clone();
                    row.state = "ended".into();
                    row.ended_at = now_ms();
                    row.reason = "invitation_expired".into();
                    engine.put_peer(&row)?;
                    engine.purge_relationship(&row.account)?;
                    changes.peers = true;
                }
                engine.housekeeping_peer(&row.account)?;
            }
            let horizon = i64_of(now_ms().saturating_sub(SEEN_RETENTION_MS));
            let codes = i64_of(now_ms().saturating_sub(CONTACT_TTL_MS));
            // A mailbox event cannot come back once every relay must have dropped it.
            let mailbox_horizon =
                i64_of(now_s().saturating_sub(mailbox::MAX_AGE_S + 2 * mailbox::DAY_S));
            engine.sql(|c| {
                c.execute(
                    "DELETE FROM olm_mailbox_seen WHERE at<?1",
                    [mailbox_horizon],
                )?;
                c.execute(
                    "DELETE FROM olm_mailbox_sent WHERE at<?1",
                    [mailbox_horizon],
                )?;
                c.execute(
                    "DELETE FROM olm_mailbox_queue WHERE created_at<?1",
                    [mailbox_horizon],
                )?;
                c.execute(
                    "DELETE FROM olm_mailbox_out WHERE id NOT IN (SELECT id FROM olm_out)",
                    [],
                )?;
                c.execute("DELETE FROM olm_seen WHERE at<?1", [horizon])?;
                c.execute("DELETE FROM olm_token WHERE created_at<?1", [horizon])?;
                c.execute("DELETE FROM olm_contact WHERE created_at<?1", [codes])?;
                c.execute(
                    "DELETE FROM olm_offer WHERE created_at<?1 OR peer NOT IN
                       (SELECT account FROM olm_peer WHERE state='pending' AND inviter=1)",
                    [codes],
                )
            })?;
            Ok(())
        })?;
        Ok(changes)
    }

    // ---- discovery -----------------------------------------------------------------

    /// What this device advertises now (NSD TXT, BLE service data, hello):
    /// the device beacon, then the newest contact code shown (BLE carries
    /// only the first two), then pending own invitations; at most four. Only
    /// holders of a secret can link a beacon.
    pub fn hello(&self) -> Result<Value> {
        let (_, beacon, _, _) = self.account_row()?;
        let period = p2p::period(now_s());
        let mut secrets: Vec<Vec<u8>> = vec![beacon];
        secrets.extend(self.contact_tags()?.iter().map(|t| t.to_vec()));
        secrets.extend(
            self.peers()?
                .into_iter()
                .filter(|p| p.state == "pending" && p.inviter)
                .filter_map(|p| p.inviter_tag),
        );
        let beacons: Vec<String> = secrets
            .iter()
            .take(4)
            .map(|secret| hex::encode(p2p::beacon(secret, period)))
            .collect();
        Ok(json!({"beacons": beacons}))
    }

    /// Which relationship (if any) advertises one of `beacons`.
    pub fn identify(&self, beacons: &[String]) -> Result<Option<String>> {
        let period = p2p::period(now_s());
        let wanted: Vec<Vec<u8>> = beacons
            .iter()
            .take(8)
            .filter_map(|b| hex::decode(b).ok())
            .filter(|b| b.len() == BEACON_BYTES)
            .collect();
        for row in self.peers()? {
            let secret = match row.state.as_str() {
                "active" if row.their_beacon.is_some() => row.their_beacon.clone(),
                // Acceptor before hearing back: the inviter's invitation beacon.
                "active" | "invited" if !row.inviter => row.inviter_tag.clone(),
                "ended" if row.their_beacon.is_some() => row.their_beacon.clone(),
                _ => None,
            };
            let Some(secret) = secret else { continue };
            for p in [period.saturating_sub(1), period, period + 1] {
                let expected = p2p::beacon(&secret, p);
                if wanted.iter().any(|w| p2p::same(w, &expected)) {
                    return Ok(Some(row.account));
                }
            }
        }
        // The device of a scanned contact code: hand it the invitation.
        for row in self
            .peers()?
            .into_iter()
            .filter(|p| p.state == "pending" && p.inviter)
        {
            let Some(tag) = self.offer_tag(&row.account)? else {
                continue;
            };
            for p in [period.saturating_sub(1), period, period + 1] {
                let expected = p2p::beacon(&tag, p);
                if wanted.iter().any(|w| p2p::same(w, &expected)) {
                    return Ok(Some(format!("{OFFER_PREFIX}{}", row.account)));
                }
            }
        }
        Ok(None)
    }

    // ---- exchange ------------------------------------------------------------------

    /// The key and addressing a section to `row` uses from this side.
    fn section_key(&self, row: &PeerRow) -> Option<[u8; 32]> {
        let tag = || row.inviter_tag.as_deref().and_then(|t| arr32(t).ok());
        if !row.inviter && !row.confirmed {
            // Acceptor until the inviter answered on the link key.
            return tag();
        }
        row.link()
            .or_else(|| if row.inviter { tag() } else { None })
    }

    /// Queued frames within `budget` bytes, oldest first. A frame larger than
    /// the budget waits for a transport that carries it (BLE: small frames
    /// such as deltas and acks; full pages wait for LAN).
    fn channel_body(&self, peer: &str, budget: usize) -> Result<ChannelBody> {
        let frames: Vec<(Vec<u8>, Vec<u8>)> = self.sql(|c| {
            let mut q = c.prepare(
                "SELECT id, frame FROM olm_out WHERE peer=?1 ORDER BY created_at, rowid LIMIT 512",
            )?;
            q.query_map(params![peer], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect()
        })?;
        let mut body = ChannelBody::default();
        let mut size = 0;
        for (id, frame) in frames {
            if body.frames.len() == MAX_FRAMES {
                break;
            }
            if size + frame.len() + 12 > budget.min(MAX_OUTBOUND_BYTES) {
                continue;
            }
            size += frame.len() + 12;
            let id: FrameId = id.as_slice().try_into().map_err(|_| Error("storage"))?;
            self.sql(|c| {
                c.execute(
                    "UPDATE olm_out SET attempts=attempts+1 WHERE id=?1",
                    [id.as_slice()],
                )
            })?;
            body.frames.push((id, frame));
        }
        let replies: Vec<(Vec<u8>, bool)> = self.sql(|c| {
            let mut q = c.prepare("SELECT id, nack FROM olm_reply WHERE peer=?1 LIMIT 256")?;
            q.query_map([peer], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect()
        })?;
        for (id, nack) in replies {
            let id: FrameId = id.as_slice().try_into().map_err(|_| Error("storage"))?;
            if nack {
                body.nacks.push(id)
            } else {
                body.acks.push(id)
            }
            // A lost reply is repaired by the peer's retransmission (duplicate → ack).
            self.sql(|c| {
                c.execute(
                    "DELETE FROM olm_reply WHERE peer=?1 AND id=?2",
                    params![peer, id.as_slice()],
                )
            })?;
        }
        Ok(body)
    }

    /// Build a request to one identified peer, or the sealed invitation for
    /// the device of a scanned contact code (`offer:<peer>`).
    pub fn exchange_begin(&mut self, peer: &str, budget: usize) -> Result<Option<Vec<u8>>> {
        let mut container = Container::new(KIND_REQUEST, [0; 16], now_s())?;
        if let Some(invited) = peer.strip_prefix(OFFER_PREFIX) {
            let Some(tag) = self.offer_tag(invited)? else {
                return Ok(None);
            };
            let package = self.invitation(invited)?;
            let sealed = p2p::seal_offer(&tag, &container.nonce, &package)?;
            let addr = p2p::offer_addr(&tag, &container.nonce);
            container.push(&tag, addr, SECTION_OFFER, sealed);
        } else {
            let row = self.peer(peer)?.ok_or(Error("no_session"))?;
            let Some(key) = self.section_key(&row) else {
                return Ok(None);
            };
            let body = self.channel_body(peer, budget)?;
            let addr = p2p::channel_addr(&key, &container.nonce);
            container.push(&key, addr, SECTION_CHANNEL, body.encode());
        }
        self.requests.retain(|_, at| now_ms() < *at + 120_000);
        self.requests.insert(container.nonce, now_ms());
        Ok(Some(container.encode()?))
    }

    /// Serve one request: absorb its sections, answer each identified
    /// relationship with its queued frames and replies.
    pub fn exchange_serve(&mut self, request: &[u8], budget: usize) -> Result<(Vec<u8>, Served)> {
        let container = Container::decode(request)?;
        if container.kind != KIND_REQUEST || now_s().abs_diff(container.time) > MAX_SKEW_S {
            return Err(Error("wire_refused"));
        }
        let absorbed = self.absorb(&container)?;
        let mut response = Container::new(KIND_RESPONSE, container.nonce, now_s())?;
        let served = Served {
            changes: absorbed.changes,
            peers: absorbed.peers.iter().map(|p| p.0.clone()).collect(),
        };
        for (peer, _) in absorbed.peers {
            let Some(row) = self.peer(&peer)? else {
                continue;
            };
            let Some(key) = self.section_key(&row) else {
                continue;
            };
            let body = self.channel_body(&peer, budget)?;
            let addr = p2p::channel_addr(&key, &response.nonce);
            response.push(&key, addr, SECTION_CHANNEL, body.encode());
        }
        Ok((response.encode()?, served))
    }

    /// Absorb the response to one of this device's requests. Returns whether
    /// another round is useful (frames still queued or replies owed).
    pub fn exchange_end(&mut self, response: &[u8], budget: usize) -> Result<(bool, Served)> {
        let container = Container::decode(response)?;
        if container.kind != KIND_RESPONSE || self.requests.remove(&container.reply_to).is_none() {
            return Err(Error("wire_refused"));
        }
        let absorbed = self.absorb(&container)?;
        let mut more = false;
        for (peer, _) in &absorbed.peers {
            let owed: i64 = self.sql(|c| {
                c.query_row(
                    "SELECT count(*) FROM olm_reply WHERE peer=?1",
                    [peer],
                    |r| r.get(0),
                )
            })?;
            let fresh: i64 = self.sql(|c| {
                c.query_row(
                    "SELECT count(*) FROM olm_out WHERE peer=?1 AND attempts=0 AND length(frame)+12<=?2",
                    params![peer, budget.min(MAX_OUTBOUND_BYTES) as i64],
                    |r| r.get(0),
                )
            })?;
            more |= owed > 0 || fresh > 0;
        }
        let peers = absorbed.peers.iter().map(|p| p.0.clone()).collect();
        Ok((
            more,
            Served {
                changes: absorbed.changes,
                peers,
            },
        ))
    }

    // ---- mailboxes -----------------------------------------------------------------

    /// Mailbox events to publish now: per established relationship the frames
    /// not yet published (or published a day ago without an ack) and the acks
    /// owed, each event within one size class. Acks alone wait some hours so
    /// they mostly travel with data. Returns `{peer, event}` (event JSON).
    pub fn mailbox_outgoing(
        &mut self,
        now_s: u64,
        pow: u8,
        max_bytes: usize,
    ) -> Result<Vec<Value>> {
        self.atomic(|engine| {
            let mut out = Vec::new();
            let mut bytes = 0;
            for row in engine.peers()? {
                let Some(link) = row.link().filter(|_| row.state == "active") else {
                    continue;
                };
                let key = mailbox::mailbox_key(&link);
                let direction = mailbox::Direction::outgoing(row.inviter);
                for _ in 0..MAILBOX_EVENTS_PER_PEER {
                    // The rest goes on the next call (one answer must cross JNI).
                    if bytes + mailbox::MAX_EVENT_JSON > max_bytes {
                        return Ok(out);
                    }
                    let Some(container) = engine.mailbox_container(&row.account, &link, now_s)?
                    else {
                        break;
                    };
                    let event = mailbox::event(&key, direction, now_s, &container, pow)?;
                    // Remembered so a later withdrawal can delete it from the mailboxes.
                    engine.sql(|c| {
                        c.execute(
                            "INSERT OR IGNORE INTO olm_mailbox_sent(id, peer, direction, day, expires, at) VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
                            params![event.id.to_bytes().as_slice(), row.account, direction as i64, i64_of(mailbox::day(now_s)),
                                    i64_of(now_s + mailbox::MAX_AGE_S), i64_of(now_s)],
                        )
                    })?;
                    let entry = engine.enqueue(
                        &row.account,
                        "data",
                        &mailbox::pool_seed(&key, direction),
                        &event,
                        &frames_of(&container)?,
                        now_s,
                    )?;
                    bytes += entry["event"].as_str().map_or(0, str::len);
                    out.push(entry);
                }
            }
            Ok(out)
        })
    }

    fn mailbox_container(
        &self,
        peer: &str,
        link: &[u8; 32],
        now_s: u64,
    ) -> Result<Option<Vec<u8>>> {
        let republish = i64_of(now_s.saturating_sub(MAILBOX_REPUBLISH_S));
        let frames: Vec<(Vec<u8>, Vec<u8>)> = self.sql(|c| {
            let mut q = c.prepare(
                "SELECT o.id, o.frame FROM olm_out o LEFT JOIN olm_mailbox_out m ON m.id = o.id
                 WHERE o.peer=?1 AND (m.id IS NULL OR m.published_at < ?2) ORDER BY o.created_at, o.rowid LIMIT 512",
            )?;
            q.query_map(params![peer, republish], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect()
        })?;
        let replies: Vec<(Vec<u8>, bool)> = self.sql(|c| {
            let mut q = c.prepare("SELECT id, nack FROM olm_reply WHERE peer=?1 LIMIT 256")?;
            q.query_map([peer], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect()
        })?;
        let mut body = ChannelBody::default();
        for (id, nack) in &replies {
            let id: FrameId = id.as_slice().try_into().map_err(|_| Error("storage"))?;
            if *nack {
                body.nacks.push(id)
            } else {
                body.acks.push(id)
            }
        }
        // Container and section framing around the body.
        let mut size = 47 + 37 + body.encode().len();
        for (id, frame) in frames {
            if body.frames.len() == MAX_FRAMES {
                break;
            }
            // A frame no event can carry waits for a direct exchange.
            if size + 12 + frame.len() > mailbox::MAX_CONTAINER {
                continue;
            }
            size += 12 + frame.len();
            let id: FrameId = id.as_slice().try_into().map_err(|_| Error("storage"))?;
            body.frames.push((id, frame));
        }
        if body.frames.is_empty() {
            if body.acks.is_empty() && body.nacks.is_empty() {
                self.sql(|c| c.execute("DELETE FROM olm_mailbox_ack WHERE peer=?1", [peer]))?;
                return Ok(None);
            }
            let since: Option<i64> = self.sql(|c| {
                c.query_row(
                    "SELECT since FROM olm_mailbox_ack WHERE peer=?1",
                    [peer],
                    |r| r.get(0),
                )
                .optional()
            })?;
            match since {
                None => {
                    self.sql(|c| {
                        c.execute(
                            "INSERT INTO olm_mailbox_ack(peer, since) VALUES(?1, ?2)",
                            params![peer, i64_of(now_s)],
                        )
                    })?;
                    return Ok(None);
                }
                Some(since) if now_s.saturating_sub(since.max(0) as u64) < MAILBOX_ACK_DELAY_S => {
                    return Ok(None);
                }
                Some(_) => {}
            }
        }
        self.sql(|c| {
            // A lost event loses its acks too; the peer's republication is acked again.
            for id in body.acks.iter().chain(&body.nacks) {
                c.execute("DELETE FROM olm_reply WHERE peer=?1 AND id=?2", params![peer, id.as_slice()])?;
            }
            for (id, _) in &body.frames {
                c.execute(
                    "INSERT OR REPLACE INTO olm_mailbox_out(id, peer, published_at) VALUES(?1, ?2, ?3)",
                    params![id.as_slice(), peer, i64_of(now_s)],
                )?;
            }
            c.execute("DELETE FROM olm_mailbox_ack WHERE peer=?1", [peer])
        })?;
        let mut container = Container::new(KIND_MAILBOX, [0; 16], now_s)?;
        let addr = p2p::channel_addr(link, &container.nonce);
        container.push(link, addr, SECTION_CHANNEL, body.encode());
        Ok(Some(container.encode()?))
    }

    /// The addresses to fetch: for every established relationship the incoming
    /// direction of today and the `days - 1` days before (no peer is named).
    pub fn mailbox_addresses(&self, now_s: u64, days: u64) -> Result<Vec<String>> {
        let mut out = BTreeSet::new();
        for group in self.mailbox_fetch(now_s, days)? {
            for address in group["addresses"].as_array().into_iter().flatten() {
                out.insert(address.as_str().unwrap_or_default().to_string());
            }
        }
        Ok(out.into_iter().collect())
    }

    /// What to fetch, grouped by the mailbox choice (`seed`) of each incoming
    /// direction: the app asks each chosen mailbox only for its addresses.
    pub fn mailbox_fetch(&self, now_s: u64, days: u64) -> Result<Vec<Value>> {
        let today = mailbox::day(now_s);
        let days = days.clamp(1, mailbox::MAX_AGE_S / mailbox::DAY_S + 1);
        let mut out = Vec::new();
        for row in self.peers()? {
            let Some(link) = row.link().filter(|_| row.state == "active") else {
                continue;
            };
            let key = mailbox::mailbox_key(&link);
            let direction = mailbox::Direction::incoming(row.inviter);
            let addresses: Vec<String> = (0..days)
                .map(|back| mailbox::address(&key, direction, today.saturating_sub(back)))
                .collect();
            // Own explicit mailboxes come with own mailboxes (0.2.7); until then: derive.
            out.push(json!({"seed": mailbox::pool_seed(&key, direction), "addresses": addresses, "relays": []}));
        }
        Ok(out)
    }

    /// The mailboxes `peer` asked for explicitly (empty: derive them).
    fn explicit_mailboxes(&self, peer: &str) -> Result<Vec<String>> {
        let relays: Option<String> = self.sql(|c| {
            c.query_row(
                "SELECT relays FROM olm_mailbox_list WHERE peer=?1",
                [peer],
                |r| r.get(0),
            )
            .optional()
        })?;
        Ok(relays
            .and_then(|r| serde_json::from_str(&r).ok())
            .unwrap_or_default())
    }

    /// Keep an event until the app reports it placed. `items` are what it
    /// carries (frame ids, or the ids a deletion names), so a late resend
    /// can rebuild it from them.
    fn enqueue(
        &self,
        peer: &str,
        role: &str,
        seed: &str,
        event: &Event,
        items: &[String],
        now_s: u64,
    ) -> Result<Value> {
        let json = nostr::JsonUtil::as_json(event);
        self.sql(|c| {
            c.execute(
                "INSERT OR REPLACE INTO olm_mailbox_queue(id, peer, role, seed, event, items, created_at) VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                params![event.id.to_bytes().as_slice(), peer, role, seed, json, json!(items).to_string(), i64_of(now_s)],
            )
        })?;
        Ok(
            json!({"id": event.id.to_hex(), "peer": peer, "role": role, "seed": seed, "event": json, "accepted": [],
                  "relays": self.relays_for(peer, role)?}),
        )
    }

    /// Events not placed yet, oldest first. One older than
    /// [`mailbox::RESIGN_AFTER_S`] is dropped and rebuilt on the next round:
    /// its frames are published again, its deletions issued again.
    pub fn mailbox_queue(&mut self, now_s: u64) -> Result<Vec<Value>> {
        self.atomic(|engine| {
            let stale = i64_of(now_s.saturating_sub(mailbox::RESIGN_AFTER_S));
            let expired: Vec<(Vec<u8>, String, String)> = engine.sql(|c| {
                let mut q = c.prepare("SELECT id, role, items FROM olm_mailbox_queue WHERE created_at < ?1")?;
                q.query_map([stale], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?.collect()
            })?;
            for (id, role, items) in expired {
                let items: Vec<String> = serde_json::from_str(&items).unwrap_or_default();
                let reset = match role.as_str() {
                    "data" => "DELETE FROM olm_mailbox_out WHERE id=?1",
                    "received" => "UPDATE olm_mailbox_seen SET deleted=0 WHERE id=?1",
                    _ => "UPDATE olm_mailbox_sent SET deleted=0 WHERE id=?1",
                };
                engine.sql(|c| {
                    for bytes in items.iter().filter_map(|item| hex::decode(item).ok()) {
                        c.execute(reset, [bytes.as_slice()])?;
                    }
                    c.execute("DELETE FROM olm_mailbox_queue WHERE id=?1", [id.as_slice()])
                })?;
            }
            let mut entries: Vec<Value> = engine.sql(|c| {
                let mut q = c.prepare(
                    "SELECT id, peer, role, seed, event, accepted FROM olm_mailbox_queue ORDER BY created_at, rowid LIMIT 512",
                )?;
                q.query_map([], |r| {
                    let id: Vec<u8> = r.get(0)?;
                    let accepted: String = r.get(5)?;
                    Ok(json!({"id": hex::encode(id), "peer": r.get::<_, String>(1)?, "role": r.get::<_, String>(2)?,
                              "seed": r.get::<_, String>(3)?, "event": r.get::<_, String>(4)?,
                              "accepted": serde_json::from_str::<Value>(&accepted).unwrap_or(json!([]))}))
                })?
                .collect()
            })?;
            for entry in &mut entries {
                let peer = entry["peer"].as_str().unwrap_or_default().to_string();
                let role = entry["role"].as_str().unwrap_or_default().to_string();
                entry["relays"] = json!(engine.relays_for(&peer, &role)?);
            }
            Ok(entries)
        })
    }

    /// Where an event goes when a list was given: frames and withdrawals to the
    /// friend's list, receipts to the own (none before own mailboxes exist).
    fn relays_for(&self, peer: &str, role: &str) -> Result<Vec<String>> {
        if role == "received" {
            Ok(Vec::new())
        } else {
            self.explicit_mailboxes(peer)
        }
    }

    /// A mailbox answered `OK true` for the queued event `id`.
    pub fn mailbox_accepted(&mut self, id: &str, relay: &str) -> Result<()> {
        let bytes = hex::decode(id).map_err(|_| Error("mailbox_id"))?;
        self.atomic(|engine| {
            let accepted: Option<String> = engine.sql(|c| {
                c.query_row(
                    "SELECT accepted FROM olm_mailbox_queue WHERE id=?1",
                    [bytes.as_slice()],
                    |r| r.get(0),
                )
                .optional()
            })?;
            let Some(accepted) = accepted else {
                return Ok(());
            };
            let mut list: Vec<String> = serde_json::from_str(&accepted).unwrap_or_default();
            if !list.iter().any(|r| r == relay) {
                list.push(relay.to_string());
            }
            engine.sql(|c| {
                c.execute(
                    "UPDATE olm_mailbox_queue SET accepted=?1 WHERE id=?2",
                    params![json!(list).to_string(), bytes.as_slice()],
                )
            })?;
            Ok(())
        })
    }

    /// The app placed the queued event `id` well enough (quorum reached and
    /// the rest tried): forget it.
    pub fn mailbox_done(&mut self, id: &str) -> Result<()> {
        let bytes = hex::decode(id).map_err(|_| Error("mailbox_id"))?;
        self.sql(|c| {
            c.execute(
                "DELETE FROM olm_mailbox_queue WHERE id=?1",
                [bytes.as_slice()],
            )
        })?;
        Ok(())
    }

    /// Take mailbox events fetched from relays. Each must be a current event of
    /// the right kind and shape, under the day's address of a relationship and
    /// signed with that day's key; it then opens to a mailbox container whose
    /// frames go the same way as directly received ones (replays and duplicates
    /// across mailboxes are dropped by event id, then by frame hash).
    pub fn mailbox_absorb(&mut self, now_s: u64, events: &[String]) -> Result<Value> {
        let today = mailbox::day(now_s);
        let mut index: HashMap<String, (String, [u8; 32], mailbox::Direction, u64)> =
            HashMap::new();
        for row in self.peers()? {
            let Some(link) = row.link().filter(|_| row.state == "active") else {
                continue;
            };
            let key = mailbox::mailbox_key(&link);
            let direction = mailbox::Direction::incoming(row.inviter);
            for back in 0..=mailbox::MAX_AGE_S / mailbox::DAY_S + 1 {
                let day = today.saturating_sub(back);
                index.insert(
                    mailbox::address(&key, direction, day),
                    (row.account.clone(), key, direction, day),
                );
            }
        }
        let (mut accepted, mut duplicates, mut refused) = (0, 0, 0);
        let mut changes = Changes::default();
        for json in events {
            let Ok(envelope) = mailbox::envelope(json, now_s) else {
                refused += 1;
                continue;
            };
            let Some((peer, key, direction, day)) = index.get(&envelope.address).cloned() else {
                refused += 1;
                continue;
            };
            if !mailbox::matches(&envelope, &key, direction, day) {
                refused += 1;
                continue;
            }
            let id = envelope.event.id.to_bytes();
            let seen: Option<i64> = self.sql(|c| {
                c.query_row(
                    "SELECT 1 FROM olm_mailbox_seen WHERE id=?1",
                    [id.as_slice()],
                    |r| r.get(0),
                )
                .optional()
            })?;
            if seen.is_some() {
                duplicates += 1;
                continue;
            }
            let container = mailbox::content(&envelope, &key, direction, day)
                .and_then(|bytes| Container::decode(&bytes))
                .ok()
                .filter(|c| c.kind == KIND_MAILBOX);
            let Some(container) = container else {
                refused += 1;
                continue;
            };
            // Frames are stored (each in its own transaction, deduplicated by
            // hash) before the event counts as seen: a crash in between fetches
            // it again and applies nothing twice.
            let absorbed = self.absorb(&container)?;
            if !absorbed.peers.iter().any(|(p, _)| *p == peer) {
                refused += 1;
                continue;
            }
            changes.inbox |= absorbed.changes.inbox;
            changes.peers |= absorbed.changes.peers;
            self.sql(|c| {
                c.execute(
                    "INSERT OR IGNORE INTO olm_mailbox_seen(id, peer, direction, day, expires, at) VALUES(?1, ?2, ?3, ?4, ?5, ?6)",
                    params![id.as_slice(), peer, direction as i64, i64_of(day), i64_of(envelope.expiration), i64_of(now_s)],
                )
            })?;
            accepted += 1;
        }
        Ok(
            json!({"accepted": accepted, "duplicates": duplicates, "refused": refused,
                  "inbox": changes.inbox, "peers": changes.peers}),
        )
    }

    /// Deletions to publish: for every event taken from a mailbox (the
    /// receipt to the mailbox, after the frames are stored) and for every
    /// published event of a withdrawal. Each is signed with the day's key the
    /// events were published under and names at most 50 of them. Returns
    /// `{peer, role, event}`; `received` ones go to the own mailboxes,
    /// `withdrawn` ones to the friend's.
    pub fn mailbox_deletions(&mut self, now_s: u64) -> Result<Vec<Value>> {
        self.atomic(|engine| {
            let mut out = Vec::new();
            for (role, table, due) in [
                ("received", "olm_mailbox_seen", "deleted=0"),
                ("withdrawn", "olm_mailbox_sent", "withdrawn=1 AND deleted=0"),
            ] {
                let rows: Vec<(Vec<u8>, String, i64, i64, i64)> = engine.sql(|c| {
                    let mut q = c.prepare(&format!(
                        "SELECT id, peer, direction, day, expires FROM {table} WHERE {due} ORDER BY peer, direction, day, at LIMIT 5000"
                    ))?;
                    q.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)))?
                        .collect()
                })?;
                let mut groups: Vec<DeletionGroup> = Vec::new();
                for (id, peer, direction, day, expires) in rows {
                    match groups.last_mut() {
                        Some((group, ids))
                            if *group == (peer.clone(), direction, day) && ids.len() < mailbox::MAX_DELETE_IDS =>
                        {
                            ids.push((id, expires))
                        }
                        _ => groups.push(((peer, direction, day), vec![(id, expires)])),
                    }
                }
                for ((peer, direction, day), ids) in groups {
                    for (id, _) in &ids {
                        engine.sql(|c| c.execute(&format!("UPDATE {table} SET deleted=1 WHERE id=?1"), [id.as_slice()]))?;
                    }
                    // A relationship whose keys are gone cannot sign; its events expire by themselves.
                    let Some(link) = engine.peer(&peer)?.and_then(|row| row.link()) else {
                        continue;
                    };
                    let direction = if direction == 0 {
                        mailbox::Direction::FromInviter
                    } else {
                        mailbox::Direction::FromAcceptor
                    };
                    let event_ids: Vec<EventId> = ids.iter().filter_map(|(id, _)| EventId::from_slice(id).ok()).collect();
                    let expiration = ids.iter().map(|(_, e)| (*e).max(0).unsigned_abs()).max().unwrap_or(0);
                    let event = mailbox::deletion(
                        &mailbox::mailbox_key(&link),
                        direction,
                        day.max(0).unsigned_abs(),
                        now_s,
                        &event_ids,
                        expiration,
                    )?;
                    let items: Vec<String> = ids.iter().map(|(id, _)| hex::encode(id)).collect();
                    let seed = mailbox::pool_seed(&mailbox::mailbox_key(&link), direction);
                    out.push(engine.enqueue(&peer, role, &seed, &event, &items, now_s)?);
                }
            }
            Ok(out)
        })
    }

    /// Match a section to a relationship. Returns the peer, the key and its
    /// kind. The inviter never trusts replies under its invitation tag (a QR
    /// onlooker may know it); the acceptor must, to learn a refusal.
    fn identify_section(
        &self,
        container: &Container,
        section: &p2p::Section,
    ) -> Result<Option<(PeerRow, [u8; 32], KeyKind)>> {
        for row in self.peers()? {
            let mut keys: Vec<([u8; 32], KeyKind)> = Vec::new();
            if let Some(link) = row.link() {
                keys.push((link, KeyKind::Link));
            }
            if let Some(tag) = row.inviter_tag.as_deref().and_then(|t| arr32(t).ok()) {
                if row.inviter && matches!(row.state.as_str(), "pending" | "active") {
                    keys.push((tag, KeyKind::InvitationUntrusted));
                } else if !row.inviter && row.state == "active" && !row.confirmed {
                    keys.push((tag, KeyKind::Invitation));
                }
            }
            for (key, kind) in keys {
                if p2p::same(&p2p::channel_addr(&key, &container.nonce), &section.addr)
                    && container.verify(section, &key)
                {
                    return Ok(Some((row, key, kind)));
                }
            }
        }
        Ok(None)
    }

    fn absorb(&mut self, container: &Container) -> Result<Absorbed> {
        let mut absorbed = Absorbed::default();
        for section in &container.sections {
            match section.flags {
                SECTION_OFFER => {
                    // Only an offer sealed to a contact code this device shows
                    // is taken; the code is then used up.
                    for tag in self.contact_tags()? {
                        if !p2p::same(&p2p::offer_addr(&tag, &container.nonce), &section.addr)
                            || !container.verify(section, &tag)
                        {
                            continue;
                        }
                        let Ok(package) = p2p::open_offer(&tag, &container.nonce, &section.body)
                        else {
                            continue;
                        };
                        // A bad package never blocks the rest of the container.
                        if let Ok(Some(_)) = self.import_invitation(&package) {
                            self.sql(|c| {
                                c.execute("DELETE FROM olm_contact WHERE tag=?1", [tag.as_slice()])
                            })?;
                            absorbed.changes.peers = true;
                        }
                        break;
                    }
                }
                SECTION_CHANNEL => {
                    let Some((row, key, kind)) = self.identify_section(container, section)? else {
                        continue;
                    };
                    let Ok(body) = ChannelBody::decode(&section.body) else {
                        continue;
                    };
                    let peer = row.account.clone();
                    if kind != KeyKind::InvitationUntrusted {
                        self.replies(&row, &body)?;
                        if kind == KeyKind::Link && !row.inviter && !row.confirmed {
                            // The inviter answered on the link key: done with the invitation tag.
                            let mut row = self.peer(&peer)?.ok_or(Error("no_session"))?;
                            row.confirmed = true;
                            self.put_peer(&row)?;
                        }
                    }
                    for (id, frame) in &body.frames {
                        let changes = self.receive(&peer, id, frame)?;
                        absorbed.changes.inbox |= changes.inbox;
                        absorbed.changes.peers |= changes.peers;
                    }
                    if !absorbed.peers.iter().any(|(p, _)| *p == peer) {
                        absorbed.peers.push((peer, key));
                    }
                }
                _ => {}
            }
        }
        Ok(absorbed)
    }

    /// Acks delete delivered frames; nacks withdraw frames the peer could not
    /// decrypt and rebuild the session.
    fn replies(&mut self, row: &PeerRow, body: &ChannelBody) -> Result<()> {
        if body.acks.is_empty() && body.nacks.is_empty() {
            return Ok(());
        }
        let peer = row.account.clone();
        let nacks = body.nacks.clone();
        let acks = body.acks.clone();
        self.atomic(|engine| {
            for id in &acks {
                engine.sql(|c| {
                    c.execute(
                        "DELETE FROM olm_out WHERE peer=?1 AND id=?2",
                        params![peer, id.as_slice()],
                    )
                })?;
            }
            let mut withdrawn = 0;
            let mut known = 0;
            let mut rekey_nacked = false;
            for id in &nacks {
                let app: Option<bool> = engine.sql(|c| {
                    c.query_row(
                        "SELECT app FROM olm_out WHERE peer=?1 AND id=?2",
                        params![peer, id.as_slice()],
                        |r| r.get(0),
                    )
                    .optional()
                })?;
                // A nack for a frame no longer queued (replayed or late) changes nothing.
                let Some(app) = app else { continue };
                known += 1;
                withdrawn += usize::from(app);
                rekey_nacked |= !app;
                engine.sql(|c| {
                    c.execute(
                        "DELETE FROM olm_out WHERE peer=?1 AND id=?2",
                        params![peer, id.as_slice()],
                    )
                })?;
            }
            if known > 0 {
                // Frames of a broken session would fail too: withdraw every
                // queued application frame, the sync layer resends from a new
                // generation (the peer's cancelled_at moves forward).
                withdrawn += engine
                    .sql(|c| c.execute("DELETE FROM olm_out WHERE peer=?1 AND app=1", [&peer]))?;
                if let Some(mut current) = engine.peer(&peer)? {
                    if withdrawn > 0 {
                        current.cancelled_at = now_ms();
                    }
                    if current.state == "active"
                        && !current.inviter
                        && !current.confirmed
                        && rekey_nacked
                    {
                        // The inviter refused the acceptance itself.
                        current.state = "ended".into();
                        current.ended_at = now_ms();
                        current.reason = "invitation_refused".into();
                    }
                    engine.put_peer(&current)?;
                }
                engine.rekey(&peer, rekey_nacked)?;
            }
            Ok(())
        })?;
        let _ = self.housekeeping_peer(&peer);
        Ok(())
    }

    /// Decrypt one frame from `peer` and commit its effects atomically.
    fn receive(&mut self, peer: &str, id: &FrameId, frame: &[u8]) -> Result<Changes> {
        let hash = p2p::sha256(frame);
        let seen: Option<i64> = self.sql(|c| {
            c.query_row(
                "SELECT 1 FROM olm_seen WHERE hash=?1",
                [hash.as_slice()],
                |r| r.get(0),
            )
            .optional()
        })?;
        let Some(row) = self.peer(peer)? else {
            return Ok(Changes::default());
        };
        if seen.is_some() || row.state == "ended" {
            // Duplicate (or a friendship that ended): acknowledge, never re-apply.
            self.reply(peer, id, false)?;
            return Ok(Changes::default());
        }
        let message = frame
            .split_first()
            .and_then(|(kind, bytes)| OlmMessage::from_parts(*kind as usize, bytes).ok());
        let Some(message) = message else {
            self.reply(peer, id, true)?;
            return Ok(Changes::default());
        };
        let outcome = self.atomic(|engine| engine.decrypt_and_commit(&row, &hash, id, &message));
        match outcome {
            Ok(changes) => Ok(changes),
            #[cfg(feature = "local-harness")]
            Err(Error("injected_fault")) => Err(Error("injected_fault")),
            Err(Error("storage")) | Err(Error("native_inbox_quota")) => Err(Error("storage")),
            Err(_) => {
                // Undecryptable or invalid: tell the sender (authenticated nack).
                self.reply(peer, id, true)?;
                Ok(Changes::default())
            }
        }
    }

    fn reply(&self, peer: &str, id: &FrameId, nack: bool) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "INSERT INTO olm_reply(peer, id, nack) VALUES(?1,?2,?3) ON CONFLICT(peer, id) DO UPDATE SET nack=excluded.nack",
                params![peer, id.as_slice(), nack],
            )
        })?;
        Ok(())
    }

    fn decrypt_and_commit(
        &mut self,
        row: &PeerRow,
        hash: &[u8; 32],
        id: &FrameId,
        message: &OlmMessage,
    ) -> Result<Changes> {
        let peer = row.account.as_str();
        let mut changes = Changes::default();
        let mut plaintext = None;
        // Existing sessions first (pre-key messages keep arriving until the
        // sender hears back; they match their session's keys).
        let sessions = self.sessions(peer)?;
        if let OlmMessage::PreKey(pre) = message
            && let Some(existing) = sessions.iter().find(|s| {
                Self::load_session(&s.1)
                    .map(|x| x.session_keys() == pre.session_keys())
                    .unwrap_or(false)
            })
        {
            let mut session = Self::load_session(&existing.1)?;
            plaintext = Some(checked(session.decrypt(message), "olm_decrypt")?);
            self.save_session(peer, &session, true, true)?;
        } else if let OlmMessage::PreKey(pre) = message {
            plaintext = Some(self.inbound_session(row, pre, &mut changes)?);
        } else {
            for (_, pickle, _) in &sessions {
                let mut session = Self::load_session(pickle)?;
                if let Ok(plain) = session.decrypt(message) {
                    self.save_session(peer, &session, true, true)?;
                    plaintext = Some(plain);
                    break;
                }
            }
        }
        let plaintext = zeroize::Zeroizing::new(plaintext.ok_or(Error("olm_decrypt"))?);
        let (kind, payload) = plaintext.split_first().ok_or(Error("envelope"))?;
        let row = self.peer(peer)?.ok_or(Error("no_session"))?;
        match *kind {
            ENVELOPE_APP => {
                if row.state != "active" {
                    return Err(Error("envelope"));
                }
                let content = std::str::from_utf8(payload).map_err(|_| Error("envelope"))?;
                if content.len() > MAX_APP_CONTENT {
                    return Err(Error("envelope"));
                }
                let count: i64 =
                    self.sql(|c| c.query_row("SELECT count(*) FROM olm_inbox", [], |r| r.get(0)))?;
                if count >= MAX_INBOX {
                    return Err(Error("native_inbox_quota"));
                }
                self.sql(|c| {
                    c.execute(
                        "INSERT OR IGNORE INTO olm_inbox(source, peer, content, received_at) VALUES(?1,?2,?3,?4)",
                        params![hash.as_slice(), peer, content, i64_of(now_ms())],
                    )
                })?;
                changes.inbox = true;
            }
            ENVELOPE_CONTROL => {
                let control: Control = checked(serde_json::from_slice(payload), "envelope")?;
                self.control(&row, control, &mut changes)?;
            }
            _ => return Err(Error("envelope")),
        }
        let mut row = self.peer(peer)?.ok_or(Error("no_session"))?;
        row.last_seen = now_ms();
        self.put_peer(&row)?;
        self.sql(|c| {
            c.execute(
                "INSERT OR IGNORE INTO olm_seen(hash, peer, at) VALUES(?1,?2,?3)",
                params![hash.as_slice(), peer, i64_of(now_ms())],
            )
        })?;
        self.reply(peer, id, false)?;
        Ok(changes)
    }

    /// A pre-key message for a session this device does not have yet: the
    /// acceptance of an own invitation, or a friend's rebuilt session.
    fn inbound_session(
        &mut self,
        row: &PeerRow,
        pre: &PreKeyMessage,
        changes: &mut Changes,
    ) -> Result<Vec<u8>> {
        let base = pre.base_key().to_base64();
        let used: Option<i64> = self.sql(|c| {
            c.query_row("SELECT 1 FROM olm_base WHERE base_key=?1", [&base], |r| {
                r.get(0)
            })
            .optional()
        })?;
        if used.is_some() {
            return Err(Error("prekey_replay"));
        }
        let peer = row.account.as_str();
        let mut account = self.load_account()?;
        match row.state.as_str() {
            "pending" if row.inviter => {
                let created = checked(
                    account.create_inbound_session(
                        SessionConfig::version_1(),
                        pre.identity_key(),
                        pre,
                    ),
                    "olm_session",
                )?;
                let plaintext = zeroize::Zeroizing::new(created.plaintext);
                let (kind, payload) = plaintext.split_first().ok_or(Error("envelope"))?;
                if *kind != ENVELOPE_CONTROL {
                    return Err(Error("acceptance_expected"));
                }
                let Control::Accept {
                    invite,
                    from,
                    to,
                    from_curve,
                    from_ed,
                    to_curve,
                    to_ed,
                    binding_at,
                    binding_sig,
                    tag,
                    fallback,
                    beacon,
                    spares,
                    sig,
                } = checked(serde_json::from_slice(payload), "envelope")?
                else {
                    return Err(Error("acceptance_expected"));
                };
                // Unknown key share: both npubs and both Olm identities must be
                // exactly the ones of this invitation and this session.
                let (my_curve, my_ed) = (
                    account.curve25519_key().to_base64(),
                    account.ed25519_key().to_base64(),
                );
                if invite != row.rel
                    || from != peer
                    || to != self.account
                    || to_curve != my_curve
                    || to_ed != my_ed
                {
                    return Err(Error("unknown_key_share"));
                }
                if curve(&from_curve)? != pre.identity_key() {
                    return Err(Error("unknown_key_share"));
                }
                let from_key = checked(PublicKey::from_hex(&from), "peer")?;
                verify_binding(
                    &from_key,
                    &from_curve,
                    &from_ed,
                    binding_at,
                    &checked(hex::decode(&binding_sig), "binding")?,
                )?;
                let signed = accept_message(
                    &invite,
                    &from,
                    &to,
                    [&from_curve, &from_ed, &to_curve, &to_ed],
                    binding_at,
                    &tag,
                    &fallback,
                    &beacon,
                );
                checked(
                    ed(&from_ed)?.verify(
                        &signed,
                        &checked(Ed25519Signature::from_base64(&sig), "acceptance")?,
                    ),
                    "acceptance",
                )?;
                let tag = arr32(&unb64(&tag)?)?;
                let beacon = arr32(&unb64(&beacon)?)?;
                curve(&fallback)?;
                if spares.len() > 8 || spares.iter().any(|s| curve(s).is_err()) {
                    return Err(Error("acceptance"));
                }
                self.save_account(&account)?;
                let mut session = created.session;
                let mut next = row.clone();
                next.state = "active".into();
                next.their_curve = from_curve;
                next.their_ed = from_ed;
                next.their_fallback = fallback;
                next.their_spares = spares;
                next.their_beacon = Some(beacon.to_vec());
                next.acceptor_tag = Some(tag.to_vec());
                next.package = None;
                next.last_seen = now_ms();
                self.put_peer(&next)?;
                self.save_session(peer, &session, true, true)?;
                self.sql(|c| {
                    c.execute(
                        "INSERT INTO olm_base(base_key, at) VALUES(?1,?2)",
                        params![base, i64_of(now_ms())],
                    )
                })?;
                // Answer on the new session: beacon, fallback and spare keys.
                let (my_fallback, my_beacon, _, _) = self.account_row()?;
                let control = Control::Keys {
                    beacon: b64(&my_beacon),
                    fallback: my_fallback,
                    spares: self.fresh_spares(&mut account)?,
                    mailboxes: Vec::new(),
                };
                self.save_account(&account)?;
                self.queue_control(peer, &mut session, &control, true)?;
                changes.peers = true;
                Ok(plaintext.to_vec())
            }
            "active" => {
                // A friend rebuilt the session; its identity key is known.
                if row.their_curve.is_empty() || curve(&row.their_curve)? != pre.identity_key() {
                    return Err(Error("unknown_key_share"));
                }
                let (current_fallback, ..) = self.account_row()?;
                let used_fallback = curve(&current_fallback)? == pre.one_time_key();
                let created = checked(
                    account.create_inbound_session(
                        SessionConfig::version_1(),
                        pre.identity_key(),
                        pre,
                    ),
                    "olm_session",
                )?;
                let plaintext = created.plaintext;
                if plaintext.first() != Some(&ENVELOPE_CONTROL) {
                    return Err(Error("rekey_expected"));
                }
                let control: Control =
                    checked(serde_json::from_slice(&plaintext[1..]), "envelope")?;
                if !matches!(control, Control::Rekey { .. }) {
                    return Err(Error("rekey_expected"));
                }
                if used_fallback {
                    // Matrix pattern: a used fallback key is replaced; the
                    // previous one stays until the next rotation.
                    account.forget_fallback_key();
                    account.generate_fallback_key();
                    let fresh = account
                        .fallback_key()
                        .values()
                        .next()
                        .copied()
                        .ok_or(Error("fallback_key"))?;
                    account.mark_keys_as_published();
                    self.sql(|c| {
                        c.execute(
                            "UPDATE olm_account SET fallback=?1 WHERE id=1",
                            [fresh.to_base64()],
                        )
                    })?;
                }
                self.save_account(&account)?;
                let mut session = created.session;
                self.save_session(peer, &session, true, true)?;
                self.sql(|c| {
                    c.execute(
                        "INSERT INTO olm_base(base_key, at) VALUES(?1,?2)",
                        params![base, i64_of(now_ms())],
                    )
                })?;
                // Frames queued on the broken session cannot be read: withdraw
                // them; the sync layer starts a new generation.
                let withdrawn =
                    self.sql(|c| c.execute("DELETE FROM olm_out WHERE peer=?1 AND app=1", [peer]))?;
                let mut next = self.peer(peer)?.ok_or(Error("no_session"))?;
                if withdrawn > 0 {
                    next.cancelled_at = now_ms();
                }
                self.put_peer(&next)?;
                // Replace the spare key they used; announce a changed fallback.
                let (my_fallback, my_beacon, _, _) = self.account_row()?;
                let control = Control::Keys {
                    beacon: b64(&my_beacon),
                    fallback: my_fallback,
                    spares: self.fresh_spares(&mut account)?,
                    mailboxes: Vec::new(),
                };
                self.save_account(&account)?;
                self.queue_control(peer, &mut session, &control, true)?;
                if used_fallback {
                    for other in self
                        .peers()?
                        .into_iter()
                        .filter(|p| p.state == "active" && p.account != peer)
                    {
                        if let Some(mut s) = self.sending_session(&other.account)? {
                            let (fb, bc, _, _) = self.account_row()?;
                            self.queue_control(
                                &other.account,
                                &mut s,
                                &Control::Keys {
                                    beacon: b64(&bc),
                                    fallback: fb,
                                    spares: vec![],
                                    mailboxes: Vec::new(),
                                },
                                false,
                            )?;
                        }
                    }
                }
                changes.peers = true;
                Ok(plaintext)
            }
            _ => Err(Error("no_session")),
        }
    }

    fn control(&mut self, row: &PeerRow, control: Control, changes: &mut Changes) -> Result<()> {
        match control {
            // Acceptance is only valid as the first message of a new session.
            Control::Accept { .. } => Ok(()),
            Control::Rekey { .. } => Ok(()),
            Control::Keys {
                beacon,
                fallback,
                spares,
                mailboxes,
            } => {
                if mailboxes.len() > 4
                    || mailboxes
                        .iter()
                        .any(|m| !m.starts_with("wss://") || m.len() > 200)
                {
                    return Err(Error("envelope"));
                }
                if !mailboxes.is_empty() {
                    self.sql(|c| {
                        c.execute(
                            "INSERT OR REPLACE INTO olm_mailbox_list(peer, relays, at) VALUES(?1, ?2, ?3)",
                            params![row.account, json!(mailboxes).to_string(), i64_of(now_ms())],
                        )
                    })?;
                }
                let mut next = row.clone();
                next.their_beacon = Some(arr32(&unb64(&beacon)?)?.to_vec());
                curve(&fallback)?;
                next.their_fallback = fallback;
                if spares.len() > 8 || spares.iter().any(|s| curve(s).is_err()) {
                    return Err(Error("envelope"));
                }
                if !spares.is_empty() {
                    next.their_spares = spares;
                }
                self.put_peer(&next)?;
                changes.peers = true;
                Ok(())
            }
        }
    }

    // ---- inbox -------------------------------------------------------------------------

    /// Only active peers' messages leave the native layer.
    pub fn inbox_after(&self, after: i64, limit: usize) -> Result<Vec<Value>> {
        self.sql(|c| {
            let mut q = c.prepare(
                "SELECT i.seq, i.peer, i.content, i.received_at FROM olm_inbox i JOIN olm_peer p ON p.account=i.peer
                 WHERE i.seq>?1 AND p.state='active' ORDER BY i.seq LIMIT ?2",
            )?;
            q.query_map(params![after, limit as i64], |r| {
                Ok(json!({"seq": r.get::<_, i64>(0)?, "peer": r.get::<_, String>(1)?, "content": r.get::<_, String>(2)?,
                          "received_at": r.get::<_, i64>(3)?}))
            })?
            .collect()
        })
    }

    pub fn inbox_ack(&self, seqs: &[i64]) -> Result<()> {
        for seq in seqs {
            self.sql(|c| c.execute("DELETE FROM olm_inbox WHERE seq=?1", [seq]))?;
        }
        Ok(())
    }

    // ---- status --------------------------------------------------------------------------

    pub fn pending_for(&self, peer: &str) -> Result<i64> {
        self.sql(|c| {
            c.query_row("SELECT count(*) FROM olm_out WHERE peer=?1", [peer], |r| {
                r.get(0)
            })
        })
    }

    pub fn status(&self) -> Result<Value> {
        let (inbox, outbound): (i64, i64) = self.sql(|c| {
            c.query_row(
                "SELECT (SELECT count(*) FROM olm_inbox), (SELECT count(*) FROM olm_out)",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
        })?;
        let peers: Vec<Value> = self
            .peers()?
            .into_iter()
            .map(|p| {
                json!({
                    "account": p.account, "group": p.rel, "state": p.state, "invited_by_me": p.inviter,
                    "created_at": p.created_at, "last_seen": p.last_seen, "cancelled_at": p.cancelled_at,
                    "ended_at": p.ended_at, "reason": p.reason,
                    "outbound_pending": self.pending_for(&p.account).unwrap_or(0),
                })
            })
            .collect();
        Ok(json!({
            "account": self.account,
            // Direct sharing never offers invitations to unknown devices.
            "discovery": {"enabled": false, "key_package_until": 0},
            "local": {"events": outbound, "inbox": inbox, "outbound_pending": outbound},
            "peers": peers,
            "relays": [],
            "local_relay": false,
            "database_bytes": std::fs::metadata(&self.path).map(|m| m.len()).unwrap_or(0),
        }))
    }

    // ---- test harness ------------------------------------------------------------------

    /// Harness: count plaintext occurrences in every native table.
    #[cfg(feature = "local-harness")]
    pub fn plaintext_matches(&self, marker: &str) -> Result<usize> {
        if marker.len() < 8 || marker.len() > 256 {
            return Err(Error("marker_limit"));
        }
        let like = format!("%{marker}%");
        let mut count: i64 = self.sql(|c| {
            c.query_row(
                "SELECT (SELECT count(*) FROM olm_inbox WHERE content LIKE ?1)
                      + (SELECT count(*) FROM olm_meta WHERE value LIKE ?1)
                      + (SELECT count(*) FROM olm_out WHERE CAST(frame AS TEXT) LIKE ?1)
                      + (SELECT count(*) FROM olm_session WHERE CAST(pickle AS TEXT) LIKE ?1)",
                [like],
                |r| r.get(0),
            )
        })?;
        let blobs: Vec<Vec<u8>> = self.sql(|c| {
            let mut q =
                c.prepare("SELECT frame FROM olm_out UNION ALL SELECT pickle FROM olm_session")?;
            q.query_map([], |r| r.get(0))?.collect()
        })?;
        count += blobs
            .iter()
            .filter(|b| b.windows(marker.len()).any(|w| w == marker.as_bytes()))
            .count() as i64;
        Ok(count as usize)
    }

    /// Harness: advance the sending chain by `count` messages that are never
    /// delivered (a gap beyond Olm's 2,000-message window).
    #[cfg(feature = "local-harness")]
    pub fn burn(&mut self, peer: &str, count: usize) -> Result<()> {
        self.atomic(|engine| {
            let mut session = engine.sending_session(peer)?.ok_or(Error("no_session"))?;
            for _ in 0..count {
                checked(session.encrypt([ENVELOPE_APP]), "olm_encrypt")?;
            }
            engine.save_session(peer, &session, false, false)
        })
    }

    /// Harness: lose every session with `peer` (state loss on this device).
    #[cfg(feature = "local-harness")]
    pub fn forget_sessions(&mut self, peer: &str) -> Result<()> {
        self.sql(|c| c.execute("DELETE FROM olm_session WHERE peer=?1", [peer]))?;
        Ok(())
    }

    /// Harness: raw session pickles (for PCS/FS tests on a stolen snapshot).
    #[cfg(feature = "local-harness")]
    pub fn session_pickles(&self, peer: &str) -> Result<Vec<Vec<u8>>> {
        Ok(self.sessions(peer)?.into_iter().map(|s| s.1).collect())
    }

    /// Harness: the serde account pickle (to prove a used one-time key is gone).
    #[cfg(feature = "local-harness")]
    pub fn account_pickle(&self) -> Result<Vec<u8>> {
        self.sql(|c| {
            c.query_row("SELECT pickle FROM olm_account WHERE id=1", [], |r| {
                r.get(0)
            })
        })
    }

    /// Harness: forget replay records (simulates their retention expiring).
    #[cfg(feature = "local-harness")]
    pub fn forget_seen(&self) -> Result<()> {
        self.sql(|c| c.execute("DELETE FROM olm_seen", []))?;
        Ok(())
    }

    #[cfg(feature = "local-harness")]
    pub fn one_time_key_count(&self) -> Result<usize> {
        Ok(self.load_account()?.stored_one_time_key_count())
    }

    #[cfg(feature = "local-harness")]
    pub fn queued(&self, peer: &str) -> Result<Vec<(FrameId, Vec<u8>)>> {
        let rows: Vec<(Vec<u8>, Vec<u8>)> = self.sql(|c| {
            let mut q = c.prepare(
                "SELECT id, frame FROM olm_out WHERE peer=?1 ORDER BY created_at, rowid",
            )?;
            q.query_map([peer], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect()
        })?;
        rows.into_iter()
            .map(|(id, frame)| {
                Ok((
                    id.as_slice().try_into().map_err(|_| Error("storage"))?,
                    frame,
                ))
            })
            .collect()
    }
}

#[cfg(test)]
mod mailbox_list_format {
    use super::*;

    #[test]
    fn an_empty_mailbox_list_leaves_the_keys_message_as_it_was_and_a_set_one_travels() {
        let keys = |mailboxes: Vec<String>| Control::Keys {
            beacon: "b".into(),
            fallback: "f".into(),
            spares: vec![],
            mailboxes,
        };
        let empty = serde_json::to_string(&keys(vec![])).unwrap();
        assert_eq!(
            empty, r#"{"t":"keys","beacon":"b","fallback":"f","spares":[]}"#,
            "unchanged on the wire in 0.2.6"
        );
        assert!(
            matches!(serde_json::from_str::<Control>(&empty).unwrap(), Control::Keys { mailboxes, .. } if mailboxes.is_empty())
        );
        let set = serde_json::to_string(&keys(vec!["wss://own.example/post".into()])).unwrap();
        assert!(set.contains(r#""mailboxes":["wss://own.example/post"]"#));
        assert!(
            matches!(serde_json::from_str::<Control>(&set).unwrap(), Control::Keys { mailboxes, .. } if mailboxes.len() == 1)
        );
    }
}
