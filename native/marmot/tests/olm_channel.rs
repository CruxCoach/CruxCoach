//! Spike M0: the direct Olm channel against the abort criteria (research
//! report §5.3, 1–4) and the v2 crash windows. Two or three engines talk
//! through `exchange_*` in memory; a "wire" records every container so tests
//! can replay, reorder, drop or attack it.
#![cfg(feature = "local-harness")]
use cruxcoach_marmot::olm::{MAX_OUTBOUND_BYTES as MAX, OlmEngine, Package, binding_event};
use cruxcoach_marmot::p2p::{self, ChannelBody, Container, SECTION_CHANNEL};
use nostr::Keys;
use std::sync::Arc;
use vodozemac::olm::{Account, AccountPickle, OlmMessage, Session, SessionConfig, SessionPickle};

struct Node {
    engine: Option<OlmEngine>,
    keys: Keys,
    dir: tempfile::TempDir,
    key: [u8; 32],
}

impl Node {
    fn new() -> Self {
        let keys = Keys::generate();
        let dir = tempfile::tempdir().unwrap();
        let key = p2p::random::<32>().unwrap();
        let mut node = Self {
            engine: None,
            keys,
            dir,
            key,
        };
        node.reopen();
        node
    }
    fn path(&self) -> std::path::PathBuf {
        self.dir.path().join("olm.db")
    }
    fn reopen(&mut self) {
        self.engine = None;
        self.engine = Some(
            OlmEngine::open(
                &self.path(),
                &self.key,
                &self.id(),
                Arc::new(self.keys.clone()),
            )
            .unwrap(),
        );
    }
    fn id(&self) -> String {
        self.keys.public_key().to_hex()
    }
    fn e(&mut self) -> &mut OlmEngine {
        self.engine.as_mut().unwrap()
    }
    fn state(&mut self, peer: &str) -> Option<String> {
        self.e().peer(peer).unwrap().map(|p| p.state)
    }
    fn inbox(&mut self) -> Vec<String> {
        self.e()
            .inbox_after(0, 64)
            .unwrap()
            .into_iter()
            .map(|v| v["content"].as_str().unwrap().to_string())
            .collect()
    }
    fn drain(&mut self) -> Vec<String> {
        let items = self.e().inbox_after(0, 64).unwrap();
        let seqs: Vec<i64> = items.iter().map(|v| v["seq"].as_i64().unwrap()).collect();
        self.e().inbox_ack(&seqs).unwrap();
        items
            .into_iter()
            .map(|v| v["content"].as_str().unwrap().to_string())
            .collect()
    }
}

/// Everything that crossed the wire, in order.
#[derive(Default)]
struct Wire(Vec<Vec<u8>>);

/// One exchange loop initiated by `a` towards `b` (as the Kotlin binding does).
fn exchange(a: &mut Node, b: &mut Node, wire: &mut Wire) {
    let peer = b.id();
    for _ in 0..16 {
        let Some(request) = a.e().exchange_begin(&peer, MAX).unwrap() else {
            return;
        };
        wire.0.push(request.clone());
        let (response, _) = b.e().exchange_serve(&request, MAX).unwrap();
        wire.0.push(response.clone());
        let (more, _) = a.e().exchange_end(&response, MAX).unwrap();
        if !more {
            return;
        }
    }
    panic!("exchange did not settle");
}

/// A invites B through the QR form, B accepts, one exchange activates A.
fn befriend(a: &mut Node, b: &mut Node, wire: &mut Wire) {
    a.e().invite(&b.id()).unwrap();
    let package = a.e().invitation(&b.id()).unwrap();
    assert_eq!(package.len(), cruxcoach_marmot::olm::PACKAGE_BYTES);
    assert_eq!(b.e().import_invitation(&package).unwrap(), Some(a.id()));
    assert_eq!(b.state(&a.id()).as_deref(), Some("invited"));
    b.e().accept(&a.id()).unwrap();
    exchange(b, a, wire);
    assert_eq!(a.state(&b.id()).as_deref(), Some("active"));
    exchange(b, a, wire);
}

fn frames_of(container: &[u8]) -> Vec<(p2p::FrameId, Vec<u8>)> {
    let c = Container::decode(container).unwrap();
    c.sections
        .iter()
        .filter(|s| s.flags == SECTION_CHANNEL)
        .filter_map(|s| ChannelBody::decode(&s.body).ok())
        .flat_map(|b| b.frames)
        .collect()
}

fn olm(frame: &[u8]) -> OlmMessage {
    OlmMessage::from_parts(frame[0] as usize, &frame[1..]).unwrap()
}

#[test]
fn invitation_acceptance_delivery_both_ways_and_signer_budget() {
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    befriend(&mut a, &mut b, &mut wire);
    a.e().send(&b.id(), "t1", "synthetic a to b").unwrap();
    b.e().send(&a.id(), "t2", "synthetic b to a").unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic a to b"]);
    assert_eq!(a.drain(), vec!["synthetic b to a"]);
    // Delivered frames are gone on the sender once acknowledged.
    exchange(&mut b, &mut a, &mut wire);
    assert_eq!(a.e().pending_for(&b.id()).unwrap(), 0);
    assert_eq!(b.e().pending_for(&a.id()).unwrap(), 0);
    // Criterion 6: one account signature per installation (the binding), none
    // per relationship message; a second friendship adds nothing.
    let mut c = Node::new();
    befriend(&mut a, &mut c, &mut wire);
    for i in 0..20 {
        a.e()
            .send(&c.id(), &format!("m{i}"), "synthetic data")
            .unwrap();
        a.e()
            .send(&b.id(), &format!("m{i}"), "synthetic data")
            .unwrap();
    }
    exchange(&mut a, &mut c, &mut wire);
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(c.drain().len(), 20);
    assert_eq!(a.e().signer_ops(), 1);
    assert_eq!(b.e().signer_ops(), 1);
    assert_eq!(c.e().signer_ops(), 1);
    // Nothing on the wire names an account or carries plaintext.
    for container in &wire.0 {
        for marker in [a.id(), b.id(), c.id()] {
            assert!(
                !container.windows(32).any(|w| hex::encode(w) == marker),
                "npub on the wire"
            );
        }
        assert!(!container.windows(16).any(|w| w == b"synthetic a to b"));
    }
}

#[test]
fn invitations_verify_binding_signature_and_recipient() {
    let (mut a, mut b, mut c) = (Node::new(), Node::new(), Node::new());
    a.e().invite(&b.id()).unwrap();
    let good = a.e().invitation(&b.id()).unwrap();
    // Addressed to B only.
    assert_eq!(
        c.e().import_invitation(&good).unwrap_err().0,
        "invitation_not_for_me"
    );
    // Any flipped bit breaks the binding or the Olm signature.
    for index in [40, 80, 120, 150, 200, 260, 300, 330, 360, 380] {
        let mut bad = good.clone();
        bad[index] ^= 0x04;
        assert!(b.e().import_invitation(&bad).is_err(), "byte {index}");
    }
    // A binding signed by another account (C claims A's Olm keys).
    let mut package = Package::decode(&good).unwrap();
    let curve = vodozemac::Curve25519PublicKey::from_bytes(package.curve).to_base64();
    let ed = vodozemac::Ed25519PublicKey::from_slice(&package.ed)
        .unwrap()
        .to_base64();
    // C signs a binding that names A: the Schnorr check fails.
    let mut forged_package = package.clone();
    let forged = binding_event(&a.keys.public_key(), &curve, &ed, package.binding_at)
        .unwrap()
        .sign_with_keys(&c.keys);
    if let Ok(event) = forged {
        forged_package.binding_sig = event.sig.serialize();
        assert_eq!(
            b.e()
                .import_invitation(&forged_package.encode())
                .unwrap_err()
                .0,
            "binding"
        );
    }
    let other = binding_event(&c.keys.public_key(), &curve, &ed, package.binding_at)
        .unwrap()
        .sign_with_keys(&c.keys)
        .unwrap();
    package.binding_sig = other.sig.serialize();
    assert_eq!(
        b.e().import_invitation(&package.encode()).unwrap_err().0,
        "binding"
    );
    assert!(b.e().import_invitation(&good).unwrap().is_some());
}

/// A hand-built acceptor with full control over the acceptance message, to
/// prove every unknown-key-share and binding check on the inviter's side.
struct ManualAcceptor {
    keys: Keys,
    account: Account,
    binding_at: u64,
    binding_sig: String,
}

impl ManualAcceptor {
    fn new(keys: Keys) -> Self {
        let account = Account::new();
        let binding_at = nostr::Timestamp::now().as_secs();
        let sig = binding_event(
            &keys.public_key(),
            &account.curve25519_key().to_base64(),
            &account.ed25519_key().to_base64(),
            binding_at,
        )
        .unwrap()
        .sign_with_keys(&keys)
        .unwrap()
        .sig;
        Self {
            keys,
            account,
            binding_at,
            binding_sig: hex::encode(sig.serialize()),
        }
    }

    /// Build the acceptance request for `package`, with `edit` applied to
    /// the JSON before signing (`sign` false leaves the signature stale).
    fn request(
        &self,
        package: &Package,
        edit: impl Fn(&mut serde_json::Value),
        sign_after_edit: bool,
    ) -> Vec<u8> {
        let me = self.keys.public_key().to_hex();
        let inviter = hex::encode(package.inviter);
        let to_curve = vodozemac::Curve25519PublicKey::from_bytes(package.curve).to_base64();
        let to_ed = vodozemac::Ed25519PublicKey::from_slice(&package.ed)
            .unwrap()
            .to_base64();
        let fallback = self.account.curve25519_key().to_base64();
        let mut value = serde_json::json!({
            "t": "accept", "invite": hex::encode(package.invite_id), "from": me, "to": inviter,
            "from_curve": self.account.curve25519_key().to_base64(), "from_ed": self.account.ed25519_key().to_base64(),
            "to_curve": to_curve, "to_ed": to_ed, "binding_at": self.binding_at, "binding_sig": self.binding_sig,
            "tag": b64(&[7u8; 32]), "fallback": fallback, "beacon": b64(&[9u8; 32]), "spares": [], "sig": "",
        });
        let sign = |value: &mut serde_json::Value| {
            let s = |k: &str| value[k].as_str().unwrap().to_string();
            let mut message = b"cc-share-accept-v1".to_vec();
            for part in [
                s("invite"),
                s("from"),
                s("to"),
                s("from_curve"),
                s("from_ed"),
                s("to_curve"),
                s("to_ed"),
                value["binding_at"].as_u64().unwrap().to_string(),
                s("tag"),
                s("fallback"),
                s("beacon"),
            ] {
                message.extend((part.len() as u32).to_be_bytes());
                message.extend(part.as_bytes());
            }
            value["sig"] = self.account.sign(&message).to_base64().into();
        };
        if sign_after_edit {
            edit(&mut value);
            sign(&mut value);
        } else {
            sign(&mut value);
            edit(&mut value);
        }
        let mut session = self
            .account
            .create_outbound_session(
                SessionConfig::version_1(),
                vodozemac::Curve25519PublicKey::from_bytes(package.curve),
                vodozemac::Curve25519PublicKey::from_bytes(package.otk),
            )
            .unwrap();
        let message = session
            .encrypt([vec![2u8], serde_json::to_vec(&value).unwrap()].concat())
            .unwrap();
        let (kind, bytes) = message.to_parts();
        let body = ChannelBody {
            frames: vec![(p2p::random().unwrap(), [vec![kind as u8], bytes].concat())],
            ..Default::default()
        };
        let mut c = Container::new(
            p2p::KIND_REQUEST,
            [0; 16],
            nostr::Timestamp::now().as_secs(),
        )
        .unwrap();
        let addr = p2p::channel_addr(&package.tag, &c.nonce);
        c.push(&package.tag, addr, SECTION_CHANNEL, body.encode());
        c.encode().unwrap()
    }
}

fn b64(bytes: &[u8]) -> String {
    use nostr::base64::Engine as _;
    nostr::base64::engine::general_purpose::STANDARD_NO_PAD.encode(bytes)
}

#[test]
fn acceptance_refuses_unknown_key_share_wrong_binding_and_replays() {
    let mut a = Node::new();
    let b_keys = Keys::generate();
    let mallory = Keys::generate();
    a.e().invite(&b_keys.public_key().to_hex()).unwrap();
    let package =
        Package::decode(&a.e().invitation(&b_keys.public_key().to_hex()).unwrap()).unwrap();
    let b = ManualAcceptor::new(b_keys.clone());
    let otk_pickle = a.e().account_pickle().unwrap();
    let attempts: Vec<(&str, Vec<u8>)> = vec![
        // Unknown key share: the acceptance names another inviter / other keys.
        (
            "to another npub",
            b.request(
                &package,
                |v| v["to"] = mallory.public_key().to_hex().into(),
                true,
            ),
        ),
        (
            "to another curve key",
            b.request(
                &package,
                |v| v["to_curve"] = Account::new().curve25519_key().to_base64().into(),
                true,
            ),
        ),
        (
            "from other Olm keys than the session",
            b.request(
                &package,
                |v| v["from_curve"] = Account::new().curve25519_key().to_base64().into(),
                true,
            ),
        ),
        (
            "another invitation id",
            b.request(
                &package,
                |v| v["invite"] = hex::encode([1u8; 16]).into(),
                true,
            ),
        ),
        // Wrong npub binding: Mallory's account claims to be B.
        (
            "from an uninvited npub",
            ManualAcceptor::new(mallory.clone()).request(&package, |_| {}, true),
        ),
        ("binding of another account", {
            let m = ManualAcceptor::new(mallory.clone());
            let mut forged = ManualAcceptor::new(b_keys.clone());
            forged.account = m.account;
            forged.binding_at = m.binding_at;
            forged.binding_sig = m.binding_sig;
            forged.request(&package, |_| {}, true)
        }),
        (
            "stale Ed25519 signature",
            b.request(&package, |v| v["tag"] = b64(&[8u8; 32]).into(), false),
        ),
    ];
    for (name, request) in attempts {
        let (_, changes) = a.e().exchange_serve(&request, MAX).unwrap();
        assert!(!changes.changes.peers, "{name}");
        assert_eq!(
            a.state(&b_keys.public_key().to_hex()).as_deref(),
            Some("pending"),
            "{name}"
        );
        assert!(
            a.e()
                .session_pickles(&b_keys.public_key().to_hex())
                .unwrap()
                .is_empty(),
            "{name}"
        );
        // The one-time key survives a refused acceptance.
        assert_eq!(a.e().account_pickle().unwrap(), otk_pickle, "{name}");
    }
    // The positive control with the same builder succeeds.
    let accepted = b.request(&package, |_| {}, true);
    a.e().exchange_serve(&accepted, MAX).unwrap();
    assert_eq!(
        a.state(&b_keys.public_key().to_hex()).as_deref(),
        Some("active")
    );
    let sessions = a
        .e()
        .session_pickles(&b_keys.public_key().to_hex())
        .unwrap()
        .len();
    // The one-time key is gone: the same pre-key message cannot create a
    // session from the stored account any more.
    let pre = match olm(&frames_of(&accepted)[0].1) {
        OlmMessage::PreKey(p) => p,
        _ => unreachable!(),
    };
    let pickle: AccountPickle = serde_json::from_slice(&a.e().account_pickle().unwrap()).unwrap();
    let mut stored = Account::from_pickle(pickle);
    assert!(matches!(
        stored.create_inbound_session(SessionConfig::version_1(), pre.identity_key(), &pre),
        Err(vodozemac::olm::SessionCreationError::MissingOneTimeKey(_))
    ));
    // Replays: the identical request, then with replay records expired, then
    // with the session gone as well. No new session, nothing in the inbox.
    for step in 0..3 {
        if step >= 1 {
            a.e().forget_seen().unwrap();
        }
        if step == 2 {
            a.e()
                .forget_sessions(&b_keys.public_key().to_hex())
                .unwrap();
        }
        let (_, changes) = a.e().exchange_serve(&accepted, MAX).unwrap();
        assert!(
            !changes.changes.peers && !changes.changes.inbox,
            "replay step {step}"
        );
        let expected = if step == 2 { 0 } else { sessions };
        assert_eq!(
            a.e()
                .session_pickles(&b_keys.public_key().to_hex())
                .unwrap()
                .len(),
            expected,
            "replay step {step}"
        );
    }
    assert!(a.inbox().is_empty());
}

#[test]
fn post_compromise_security_heals_after_one_ack_round_trip() {
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    befriend(&mut a, &mut b, &mut wire);
    for i in 0..3 {
        a.e()
            .send(&b.id(), &format!("w{i}"), "synthetic warm-up")
            .unwrap();
        exchange(&mut a, &mut b, &mut wire);
        b.drain();
        b.e()
            .send(&a.id(), &format!("wa{i}"), "synthetic ack")
            .unwrap();
        exchange(&mut b, &mut a, &mut wire);
        a.drain();
    }
    // The attacker copies B's complete state (database file and key).
    let stolen = b.dir.path().join("stolen.db");
    std::fs::copy(b.path(), &stolen).unwrap();
    let mut wire = Wire::default();
    a.e().send(&b.id(), "p1", "synthetic pcs m1").unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic pcs m1"]);
    b.e().send(&a.id(), "pa1", "synthetic pcs ack").unwrap();
    exchange(&mut b, &mut a, &mut wire);
    a.drain();
    a.e().send(&b.id(), "p2", "synthetic pcs m2").unwrap();
    a.e().send(&b.id(), "p3", "synthetic pcs m3").unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic pcs m2", "synthetic pcs m3"]);
    // Passive attacker: stolen sessions, every captured frame in order.
    let conn = rusqlite::Connection::open(&stolen).unwrap();
    conn.execute_batch(&format!("PRAGMA key = \"x'{}'\";", hex::encode(b.key)))
        .unwrap();
    let mut q = conn.prepare("SELECT pickle FROM olm_session").unwrap();
    let mut sessions: Vec<Session> = q
        .query_map([], |r| r.get::<_, Vec<u8>>(0))
        .unwrap()
        .map(|p| {
            Session::from_pickle(serde_json::from_slice::<SessionPickle>(&p.unwrap()).unwrap())
        })
        .collect();
    assert!(!sessions.is_empty());
    let mut read = Vec::new();
    for container in &wire.0 {
        for (_, frame) in frames_of(container) {
            for session in sessions.iter_mut() {
                if let Ok(plain) = session.decrypt(&olm(&frame)) {
                    read.push(String::from_utf8_lossy(&plain[1..]).to_string());
                    break;
                }
            }
        }
    }
    // Before any reply the chain is still the stolen one: m1 is readable.
    assert!(read.contains(&"synthetic pcs m1".to_string()), "{read:?}");
    // After B's acknowledgement reached A, A's chain uses fresh DH output.
    assert!(
        !read
            .iter()
            .any(|m| m.contains("synthetic pcs m2") || m.contains("synthetic pcs m3")),
        "{read:?}"
    );
}

/// Message keys A will use for its next `count` messages (test-only API).
fn next_message_keys(a: &mut Node, peer: &str, count: usize) -> Vec<[u8; 32]> {
    let pickles = a.e().session_pickles(peer).unwrap();
    assert_eq!(pickles.len(), 1);
    let mut clone =
        Session::from_pickle(serde_json::from_slice::<SessionPickle>(&pickles[0]).unwrap());
    (0..count)
        .map(|_| *clone.next_message_key().unwrap().key())
        .collect()
}

fn stored_contains(node: &mut Node, peer: &str, key: &[u8; 32]) -> bool {
    let json_array = format!(
        "[{}]",
        key.iter()
            .map(|b| b.to_string())
            .collect::<Vec<_>>()
            .join(",")
    );
    node.e().session_pickles(peer).unwrap().iter().any(|p| {
        p.windows(32).any(|w| w == key)
            || String::from_utf8_lossy(p).contains(&json_array)
            || String::from_utf8_lossy(p).contains(&b64(key))
    })
}

#[test]
fn forward_secrecy_consumed_message_keys_leave_the_stored_pickle() {
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    befriend(&mut a, &mut b, &mut wire);
    // Keep A on a single session with an established sending chain.
    a.e().send(&b.id(), "s0", "synthetic start").unwrap();
    exchange(&mut a, &mut b, &mut wire);
    b.drain();
    let keys = next_message_keys(&mut a, &b.id(), 6);
    let mut captured = Vec::new();
    for i in 0..6 {
        a.e()
            .send(&b.id(), &format!("f{i}"), &format!("synthetic fs {i}"))
            .unwrap();
    }
    let frames = a.e().queued(&b.id()).unwrap();
    assert_eq!(frames.len(), 6);
    for (_, frame) in &frames {
        captured.push(frame.clone());
    }
    // Deliver 0..3 in order, and 5, but hold 3 and 4 back.
    let peer = b.id();
    let request = a.e().exchange_begin(&peer, MAX).unwrap().unwrap();
    let mut c = Container::decode(&request).unwrap();
    let key_bytes = {
        // Rebuild the section with frames 3 and 4 removed, re-MACed like A would.
        let row = a.e().peer(&peer).unwrap().unwrap();
        let tags = (row.inviter_tag.unwrap(), row.acceptor_tag.unwrap());
        p2p::link_key(&tags.0.try_into().unwrap(), &tags.1.try_into().unwrap())
    };
    let mut body = ChannelBody::decode(&c.sections[0].body).unwrap();
    body.frames
        .retain(|(id, _)| *id != frames[3].0 && *id != frames[4].0);
    let addr = c.sections[0].addr;
    c.sections.clear();
    c.push(&key_bytes, addr, SECTION_CHANNEL, body.encode());
    let (response, _) = b.e().exchange_serve(&c.encode().unwrap(), MAX).unwrap();
    a.e().exchange_end(&response, MAX).unwrap();
    assert_eq!(b.drain().len(), 4);
    // Consumed keys are not stored; the two skipped ones are (Olm keeps at
    // most 40 per chain until the late messages arrive).
    for (i, key) in keys.iter().enumerate() {
        let expected = i == 3 || i == 4;
        assert_eq!(stored_contains(&mut b, &a.id(), key), expected, "key {i}");
    }
    // The stored pickle cannot open consumed messages again.
    for frame in [&captured[0], &captured[1], &captured[2], &captured[5]] {
        for pickle in b.e().session_pickles(&a.id()).unwrap() {
            let mut s =
                Session::from_pickle(serde_json::from_slice::<SessionPickle>(&pickle).unwrap());
            assert!(s.decrypt(&olm(frame)).is_err());
        }
    }
    // The late messages arrive; now no key of this run is stored any more.
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain().len(), 2);
    for key in &keys {
        assert!(!stored_contains(&mut b, &a.id(), key));
    }
}

#[test]
fn crash_windows_roll_back_ratchet_and_rows_together() {
    use cruxcoach_marmot::olm::FAIL_COMMITS;
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    befriend(&mut a, &mut b, &mut wire);
    // O2: a send whose transaction fails leaves no ratchet step and no frame.
    let before = a.e().session_pickles(&b.id()).unwrap();
    FAIL_COMMITS.set(1);
    assert_eq!(
        a.e().send(&b.id(), "o2", "synthetic o2").unwrap_err().0,
        "injected_fault"
    );
    assert_eq!(a.e().session_pickles(&b.id()).unwrap(), before);
    assert_eq!(a.e().pending_for(&b.id()).unwrap(), 0);
    // O3: the retried send is admitted once; same token + same content is a
    // duplicate, other content a conflict.
    let (id, dup) = a.e().send(&b.id(), "o2", "synthetic o2").unwrap();
    assert!(!dup);
    assert_eq!(
        a.e().send(&b.id(), "o2", "synthetic o2").unwrap(),
        (id, true)
    );
    assert_eq!(
        a.e().send(&b.id(), "o2", "synthetic other").unwrap_err().0,
        "token_conflict"
    );
    // R2: the receiving transaction fails; nothing is applied, the frame is
    // retransmitted and applied exactly once.
    let peer = b.id();
    let request = a.e().exchange_begin(&peer, MAX).unwrap().unwrap();
    let b_before = b.e().session_pickles(&a.id()).unwrap();
    FAIL_COMMITS.set(1);
    assert!(b.e().exchange_serve(&request, MAX).is_err());
    assert_eq!(b.e().session_pickles(&a.id()).unwrap(), b_before);
    assert!(b.inbox().is_empty());
    // Duplicate and reordered delivery of the same request.
    let (response, _) = b.e().exchange_serve(&request, MAX).unwrap();
    let (again, _) = b.e().exchange_serve(&request, MAX).unwrap();
    assert_eq!(b.drain(), vec!["synthetic o2"]);
    // A lost response: A retransmits, B acknowledges without re-applying.
    drop(response);
    a.e().exchange_end(&again, MAX).unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert!(b.inbox().is_empty());
    assert_eq!(a.e().pending_for(&b.id()).unwrap(), 0);
    // O4: frames survive a restart of the sender; R3/R4: an unacked inbox
    // item survives a restart of the receiver.
    a.e().send(&b.id(), "o4", "synthetic o4").unwrap();
    a.reopen();
    exchange(&mut a, &mut b, &mut wire);
    b.reopen();
    assert_eq!(b.inbox(), vec!["synthetic o4"]);
    // Reversed frame order within one container.
    for i in 0..5 {
        a.e()
            .send(&b.id(), &format!("r{i}"), &format!("synthetic r{i}"))
            .unwrap();
    }
    b.drain();
    let request = a.e().exchange_begin(&peer, MAX).unwrap().unwrap();
    let mut c = Container::decode(&request).unwrap();
    let mut body = ChannelBody::decode(&c.sections[0].body).unwrap();
    body.frames.reverse();
    let row = a.e().peer(&peer).unwrap().unwrap();
    let link = p2p::link_key(
        &row.inviter_tag.unwrap().try_into().unwrap(),
        &row.acceptor_tag.unwrap().try_into().unwrap(),
    );
    let addr = c.sections[0].addr;
    c.sections.clear();
    c.push(&link, addr, SECTION_CHANNEL, body.encode());
    let (response, _) = b.e().exchange_serve(&c.encode().unwrap(), MAX).unwrap();
    a.e().exchange_end(&response, MAX).unwrap();
    let mut got = b.drain();
    got.sort();
    assert_eq!(
        got,
        (0..5)
            .map(|i| format!("synthetic r{i}"))
            .collect::<Vec<_>>()
    );
    // A tampered section is ignored entirely.
    a.e().send(&b.id(), "x", "synthetic x").unwrap();
    let request = a.e().exchange_begin(&peer, MAX).unwrap().unwrap();
    let mut tampered = request.clone();
    let last = tampered.len() - 1;
    tampered[last] ^= 1;
    let (response, changes) = b.e().exchange_serve(&tampered, MAX).unwrap();
    assert!(!changes.changes.inbox && Container::decode(&response).unwrap().sections.is_empty());
}

#[test]
fn unwedge_after_a_gap_beyond_the_window_and_after_state_loss() {
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    befriend(&mut a, &mut b, &mut wire);
    a.e().send(&b.id(), "u0", "synthetic u0").unwrap();
    exchange(&mut a, &mut b, &mut wire);
    b.drain();
    // Gap beyond Olm's 2,000-message window.
    a.e().burn(&b.id(), 2_100).unwrap();
    a.e().send(&b.id(), "u1", "synthetic lost").unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert!(b.inbox().is_empty());
    let row = a.e().peer(&b.id()).unwrap().unwrap();
    assert!(
        row.cancelled_at > 0,
        "the sync layer must start a new generation"
    );
    assert_eq!(a.e().pending_for(&b.id()).unwrap(), 0, "rebuild delivered");
    a.e()
        .send(&b.id(), "u2", "synthetic after rebuild")
        .unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic after rebuild"]);
    b.e().send(&a.id(), "ub", "synthetic reply").unwrap();
    exchange(&mut b, &mut a, &mut wire);
    assert_eq!(a.drain(), vec!["synthetic reply"]);
    // State loss on the receiver: B forgets every session.
    b.e().forget_sessions(&a.id()).unwrap();
    a.e()
        .send(&b.id(), "u3", "synthetic into the void")
        .unwrap();
    exchange(&mut a, &mut b, &mut wire);
    a.e()
        .send(&b.id(), "u4", "synthetic after second rebuild")
        .unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic after second rebuild"]);
    // And the sender side: B lost state and sends first.
    b.e().forget_sessions(&a.id()).unwrap();
    let a_id = a.id();
    b.e().send(&a_id, "u5", "synthetic from rebuilt b").unwrap();
    exchange(&mut b, &mut a, &mut wire);
    assert_eq!(a.drain(), vec!["synthetic from rebuilt b"]);
    a.e().send(&b.id(), "u6", "synthetic both ways").unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic both ways"]);
    // Every rebuild used a spare one-time key or the fallback key; no
    // account signature was needed.
    assert_eq!(a.e().signer_ops() + b.e().signer_ops(), 2);
}

fn beacons_of(node: &mut Node) -> Vec<String> {
    serde_json::from_value(node.e().hello().unwrap()["beacons"].clone()).unwrap()
}

/// B shows a contact code, A scans it and hands B the sealed invitation once
/// B's device is in reach. Returns what crossed the wire.
fn invite_by_code(a: &mut Node, b: &mut Node) -> Vec<u8> {
    let code = b.e().contact_code().unwrap();
    assert_eq!(code.len(), cruxcoach_marmot::olm::CONTACT_BYTES);
    assert_eq!(a.e().import_contact(&code).unwrap(), b.id());
    let beacons = beacons_of(b);
    let target = a.e().identify(&beacons).unwrap().unwrap();
    assert_eq!(
        target,
        format!("{}{}", cruxcoach_marmot::olm::OFFER_PREFIX, b.id())
    );
    let request = a.e().exchange_begin(&target, MAX).unwrap().unwrap();
    let (response, _) = b.e().exchange_serve(&request, MAX).unwrap();
    a.e().exchange_end(&response, MAX).unwrap();
    assert_eq!(b.state(&a.id()).as_deref(), Some("invited"));
    request
}

#[test]
fn contact_code_invitation_ending_and_a_new_friendship() {
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    let offer = invite_by_code(&mut a, &mut b);
    wire.0.push(offer);
    // The code is used up: B no longer advertises it and A stops offering.
    assert_eq!(beacons_of(&mut b).len(), 1);
    assert_eq!(a.e().identify(&beacons_of(&mut b)).unwrap(), None);
    // B finds A by the invitation beacon A advertises.
    assert_eq!(b.e().identify(&beacons_of(&mut a)).unwrap(), Some(a.id()));
    b.e().accept(&a.id()).unwrap();
    exchange(&mut b, &mut a, &mut wire);
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(a.state(&b.id()).as_deref(), Some("active"));
    // After the keys message both sides recognise the other's device beacon.
    let beacons = beacons_of(&mut a);
    assert_eq!(beacons.len(), 1);
    assert_eq!(b.e().identify(&beacons).unwrap(), Some(a.id()));
    assert_eq!(a.e().identify(&beacons_of(&mut b)).unwrap(), Some(b.id()));
    // End: the queued `end` still reaches B, then A forgets the secrets and
    // a new invitation starts a new relationship.
    a.e().send(&b.id(), "end", "synthetic end").unwrap();
    a.e().end(&b.id(), "local").unwrap();
    assert_eq!(a.e().invite(&b.id()).unwrap_err().0, "ending_pending");
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic end"]);
    b.e().end(&a.id(), "local").unwrap();
    assert!(a.e().session_pickles(&b.id()).unwrap().is_empty());
    // A's device beacon rotated: B can no longer recognise it.
    assert_eq!(b.e().identify(&beacons_of(&mut a)).unwrap(), None);
    invite_by_code(&mut a, &mut b);
    b.e().accept(&a.id()).unwrap();
    exchange(&mut b, &mut a, &mut wire);
    exchange(&mut b, &mut a, &mut wire);
    a.e()
        .send(&b.id(), "n", "synthetic new friendship")
        .unwrap();
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(b.drain(), vec!["synthetic new friendship"]);
    // Nothing on the wire names an account.
    for container in &wire.0 {
        for marker in [a.id(), b.id()] {
            assert!(
                !container.windows(32).any(|w| hex::encode(w) == marker),
                "npub on the wire"
            );
        }
    }
}

#[test]
fn crossed_invitations_form_exactly_one_relationship() {
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    a.e().invite(&b.id()).unwrap();
    b.e().invite(&a.id()).unwrap();
    let from_a = a.e().invitation(&b.id()).unwrap();
    let from_b = b.e().invitation(&a.id()).unwrap();
    b.e().import_invitation(&from_a).unwrap();
    a.e().import_invitation(&from_b).unwrap();
    let (small, large) = if a.id() < b.id() {
        (&mut a, &mut b)
    } else {
        (&mut b, &mut a)
    };
    assert_eq!(small.state(&large.id()).as_deref(), Some("pending"));
    assert_eq!(large.state(&small.id()).as_deref(), Some("active"));
    exchange(large, small, &mut wire);
    assert_eq!(small.state(&large.id()).as_deref(), Some("active"));
    let peer = large.id();
    small.e().send(&peer, "x", "synthetic crossed").unwrap();
    exchange(small, large, &mut wire);
    assert_eq!(large.drain(), vec!["synthetic crossed"]);
}

#[test]
fn stored_state_holds_ciphertext_only() {
    let (mut a, mut b, mut wire) = (Node::new(), Node::new(), Wire::default());
    befriend(&mut a, &mut b, &mut wire);
    a.e()
        .send(&b.id(), "p", "synthetic private marker 42")
        .unwrap();
    assert_eq!(
        a.e()
            .plaintext_matches("synthetic private marker 42")
            .unwrap(),
        0
    );
    exchange(&mut a, &mut b, &mut wire);
    assert_eq!(
        b.e()
            .plaintext_matches("synthetic private marker 42")
            .unwrap(),
        1,
        "inbox until acked"
    );
    b.drain();
    assert_eq!(
        b.e()
            .plaintext_matches("synthetic private marker 42")
            .unwrap(),
        0
    );
    // The database file never holds the plaintext (SQLCipher).
    let raw = std::fs::read(b.path()).unwrap();
    assert!(!raw.windows(8).any(|w| w == b"synthetic"));
}

#[test]
fn invitations_reach_only_the_device_that_showed_the_code() {
    let (mut a, mut b, mut c) = (Node::new(), Node::new(), Node::new());
    let code = b.e().contact_code().unwrap();
    a.e().import_contact(&code).unwrap();
    // A stranger in reach is never offered anything, with or without a code
    // of its own, and A cannot be made to seal B's invitation for it.
    assert_eq!(a.e().identify(&beacons_of(&mut c)).unwrap(), None);
    c.e().contact_code().unwrap();
    assert_eq!(a.e().identify(&beacons_of(&mut c)).unwrap(), None);
    let to_c = format!("{}{}", cruxcoach_marmot::olm::OFFER_PREFIX, c.id());
    assert_eq!(a.e().exchange_begin(&to_c, MAX).unwrap(), None);
    // The offer for B neither names an account nor carries the package or
    // the binding in the clear.
    let target = a.e().identify(&beacons_of(&mut b)).unwrap().unwrap();
    let offer = a.e().exchange_begin(&target, MAX).unwrap().unwrap();
    let package = Package::decode(&a.e().invitation(&b.id()).unwrap()).unwrap();
    for marker in [a.id(), b.id()] {
        assert!(!offer.windows(32).any(|w| hex::encode(w) == marker));
    }
    assert!(!offer.windows(64).any(|w| w == package.binding_sig));
    assert!(!offer.windows(4).any(|w| w == b"cci1"));
    // Someone who captured it can neither open nor use it.
    c.e().exchange_serve(&offer, MAX).unwrap();
    assert_eq!(c.state(&a.id()), None);
    // A tampered offer is ignored and leaves B's code valid.
    let mut tampered = Container::decode(&offer).unwrap();
    let last = tampered.sections[0].body.len() - 1;
    tampered.sections[0].body[last] ^= 1;
    b.e()
        .exchange_serve(&tampered.encode().unwrap(), MAX)
        .unwrap();
    assert_eq!(b.state(&a.id()), None);
    b.e().exchange_serve(&offer, MAX).unwrap();
    assert_eq!(b.state(&a.id()).as_deref(), Some("invited"));
    // Used once: a replay changes nothing, and B can decline.
    b.e().decline(&a.id()).unwrap();
    b.e().exchange_serve(&offer, MAX).unwrap();
    assert_eq!(b.state(&a.id()), None);
    // Malformed codes and one's own code are refused.
    assert_eq!(
        a.e().import_contact(&code[..60]).unwrap_err().0,
        "contact_format"
    );
    let own = a.e().contact_code().unwrap();
    assert_eq!(a.e().import_contact(&own).unwrap_err().0, "self_invite");
}

#[test]
fn many_messages_for_many_friends_are_admitted_in_one_transaction() {
    use cruxcoach_marmot::olm::FAIL_COMMITS;
    let mut a = Node::new();
    let mut friends: Vec<Node> = (0..3).map(|_| Node::new()).collect();
    let mut wire = Wire::default();
    for b in friends.iter_mut() {
        befriend(&mut a, b, &mut wire);
    }
    let ids: Vec<String> = friends.iter().map(|b| b.id()).collect();
    let items = |round: &str| -> Vec<(String, String, String)> {
        ids.iter()
            .flat_map(|peer| {
                (0..2).map(move |n| {
                    (
                        peer.clone(),
                        format!("{round}{n}"),
                        format!("synthetic {round}{n}"),
                    )
                })
            })
            .collect()
    };
    // Aborted in the middle (the commit fails): nothing of the batch stays.
    let before: Vec<_> = ids
        .iter()
        .map(|p| a.e().session_pickles(p).unwrap())
        .collect();
    FAIL_COMMITS.set(1);
    assert_eq!(
        a.e().send_many(&items("m")).unwrap_err().0,
        "injected_fault"
    );
    for (peer, pickles) in ids.iter().zip(&before) {
        assert_eq!(&a.e().session_pickles(peer).unwrap(), pickles);
        assert_eq!(a.e().pending_for(peer).unwrap(), 0);
    }
    // Retried: each message once; again: duplicates, nothing new.
    let first = a.e().send_many(&items("m")).unwrap();
    assert!(first.iter().all(|r| matches!(r, Ok((_, false)))));
    let again = a.e().send_many(&items("m")).unwrap();
    assert!(again.iter().all(|r| matches!(r, Ok((_, true)))));
    for (b, peer) in friends.iter_mut().zip(&ids) {
        assert_eq!(a.e().pending_for(peer).unwrap(), 2);
        exchange(&mut a, b, &mut wire);
        assert_eq!(b.drain(), vec!["synthetic m0", "synthetic m1"]);
    }
    // A conflict stops that friend only; the others get theirs.
    let mut mixed = items("n");
    mixed[0].1 = "m0".into();
    mixed[0].2 = "synthetic other".into();
    let results = a.e().send_many(&mixed).unwrap();
    assert_eq!(results[0].as_ref().unwrap_err().0, "token_conflict");
    assert_eq!(
        results[1].as_ref().unwrap_err().0,
        "skipped",
        "no later message without the earlier"
    );
    assert!(results[2..].iter().all(|r| r.is_ok()));
    assert_eq!(a.e().pending_for(&ids[0]).unwrap(), 0);
    assert_eq!(a.e().pending_for(&ids[1]).unwrap(), 2);
}
