use cruxcoach_marmot::{
    node::{Config, LocalProofSigner, Node, now_ms},
    relay::RelayStatus,
};
use nostr::{JsonUtil, Keys};
use std::sync::{Arc, atomic::Ordering};
use storage_sqlite::SqlCipherKey;

#[path = "support/relay.rs"]
mod support;
use support::Relay;

struct Participant {
    keys: Keys,
    dir: tempfile::TempDir,
    db_key: String,
    relays: Vec<String>,
}
impl Participant {
    fn new(relays: Vec<String>) -> Self {
        Self {
            keys: Keys::generate(),
            dir: tempfile::tempdir().unwrap(),
            db_key: Keys::generate().public_key().to_hex(),
            relays,
        }
    }
    fn open(&self) -> Node {
        Node::open(
            &self.dir.path().join("session.db"),
            SqlCipherKey::new(self.db_key.clone()).unwrap(),
            Config {
                account: self.keys.public_key().to_hex(),
                relays: self.relays.clone(),
                local_test: true,
            },
            Arc::new(self.keys.clone()),
            Arc::new(LocalProofSigner(self.keys.clone())),
        )
        .unwrap()
    }
}

#[test]
fn native_bootstrap_duplicate_delivery_restart_and_epoch_fence() {
    let relay = Relay::start();
    let redundant = Relay::start();
    // No CruxCoach relay or backend occurs in either participant's pool.
    let a = Participant::new(vec![relay.url.clone(), redundant.url.clone()]);
    // Peers may serialize the same root relay URL with a trailing slash.
    // Matching transport hints must not mutate authenticated group routing.
    let b = Participant::new(a.relays.iter().map(|url| format!("{url}/")).collect());
    let mut alice = a.open();
    let mut bob = b.open();
    alice.bootstrap().unwrap();
    bob.bootstrap().unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    let peer = b.keys.public_key().to_hex();
    let sender = a.keys.public_key().to_hex();
    let before = alice.invite(&peer).unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    assert_eq!(bob.fence(&sender).unwrap_err().0, "invitation_pending");
    let received = bob.accept_invitation(&sender).unwrap();
    assert_eq!(before.binding, received.binding);
    assert_eq!(before.local_leaf, received.peer_leaf);
    assert_ne!(before.local_leaf, before.local_account);
    let tags = vec![vec![
        "l".into(),
        "cc.sharing.snapshot.v1".into(),
        "cruxcoach.private".into(),
    ]];
    assert!(
        alice
            .handoff(
                &before,
                1220,
                tags.clone(),
                "expired synthetic payload".into(),
                now_ms() - 1
            )
            .is_err()
    );
    let eid = alice
        .handoff(
            &before,
            1220,
            tags.clone(),
            "synthetic encrypted application".into(),
            now_ms() + 60_000,
        )
        .unwrap();
    assert_eq!(
        alice
            .handoff(
                &before,
                1220,
                tags.clone(),
                "synthetic encrypted application".into(),
                now_ms() + 60_000
            )
            .unwrap(),
        eid
    );
    alice.publish_handoff(&eid, &before).unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    let inbox = bob.inbox().unwrap();
    assert_eq!(inbox.len(), 1);
    assert_eq!(inbox[0].content, "synthetic encrypted application");
    assert!(!inbox[0].acknowledged);
    bob.acknowledge(&inbox[0].source, &received).unwrap();
    drop(bob);
    let mut bob = b.open();
    bob.sync().unwrap();
    assert_eq!(bob.inbox().unwrap().len(), 1);
    assert!(bob.inbox().unwrap()[0].acknowledged);
    assert!(
        alice
            .outbox()
            .unwrap()
            .iter()
            .find(|o| o.event.id == eid)
            .unwrap()
            .targets
            .iter()
            .all(|t| t.status == RelayStatus::Accepted)
    );
    for event in relay
        .events
        .lock()
        .unwrap()
        .iter()
        .filter(|e| e.kind.as_u16() == 445)
    {
        assert!(!event.as_json().contains("synthetic encrypted application"));
        assert_ne!(event.pubkey, a.keys.public_key());
        assert_ne!(event.pubkey, b.keys.public_key());
    }
    alice.rotate(&before).unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    assert!(
        alice
            .handoff(&before, 1220, tags, "stale".into(), now_ms() + 60_000)
            .is_err()
    );
    assert!(bob.inbox().unwrap()[0].invalidated);
    assert!(bob.inbox().unwrap()[0].content.is_empty());
}

#[test]
fn total_outage_keeps_exact_events_durable() {
    let relay = Relay::start();
    let participant = Participant::new(vec![relay.url.clone()]);
    let mut node = participant.open();
    node.bootstrap().unwrap();
    let original: Vec<_> = node
        .outbox()
        .unwrap()
        .iter()
        .map(|o| o.event.id.clone())
        .collect();
    relay.online.store(false, Ordering::SeqCst);
    node.sync().unwrap();
    assert!(
        node.outbox()
            .unwrap()
            .iter()
            .all(|o| o.targets[0].status == RelayStatus::Unavailable)
    );
    drop(node);
    let node = participant.open();
    assert_eq!(
        original,
        node.outbox()
            .unwrap()
            .iter()
            .map(|o| o.event.id.clone())
            .collect::<Vec<_>>()
    );
    assert!(
        node.outbox()
            .unwrap()
            .iter()
            .all(|o| o.targets[0].attempts == 1)
    );
}

#[test]
fn exclusive_account_session_lease_and_private_storage_recovery() {
    use std::os::unix::fs::PermissionsExt;
    let relay = Relay::start();
    let p = Participant::new(vec![relay.url.clone()]);
    let node = p.open();
    let path = p.dir.path().join("session.db");
    let duplicate = Node::open(
        &path,
        SqlCipherKey::new(p.db_key.clone()).unwrap(),
        p.open_config(),
        Arc::new(p.keys.clone()),
        Arc::new(LocalProofSigner(p.keys.clone())),
    );
    assert!(matches!(duplicate,Err(e) if e.0=="session_already_open"));
    assert_eq!(
        std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert_eq!(
        cruxcoach_marmot::node::archive_storage(&path)
            .unwrap_err()
            .0,
        "session_already_open"
    );
    drop(node);
    cruxcoach_marmot::node::archive_storage(&path).unwrap();
    assert!(!path.exists());
    assert_eq!(
        std::fs::read_dir(p.dir.path().join("archives"))
            .unwrap()
            .count(),
        1
    );
    let reopened = p.open();
    assert!(!reopened.discovery_enabled().unwrap());
    assert!(reopened.peers().unwrap().is_empty());
}

impl Participant {
    fn open_config(&self) -> Config {
        Config {
            account: self.keys.public_key().to_hex(),
            relays: self.relays.clone(),
            local_test: true,
        }
    }
}

#[test]
fn mismatched_account_and_wrong_database_key_never_recover_authority() {
    let relay = Relay::start();
    let p = Participant::new(vec![relay.url.clone()]);
    let node = p.open();
    drop(node);
    let mut config = p.open_config();
    config.account = Keys::generate().public_key().to_hex();
    let mismatched = Node::open(
        &p.dir.path().join("session.db"),
        SqlCipherKey::new(p.db_key.clone()).unwrap(),
        config,
        Arc::new(p.keys.clone()),
        Arc::new(LocalProofSigner(p.keys.clone())),
    );
    assert!(matches!(mismatched,Err(e) if e.0=="account_mismatch"));
    let wrong = Node::open(
        &p.dir.path().join("session.db"),
        SqlCipherKey::new(Keys::generate().public_key().to_hex()).unwrap(),
        p.open_config(),
        Arc::new(p.keys.clone()),
        Arc::new(LocalProofSigner(p.keys.clone())),
    );
    assert!(matches!(wrong,Err(e) if e.0=="native_open"));
    assert!(p.open().peers().unwrap().is_empty());
}

#[cfg(feature = "local-harness")]
#[test]
fn authenticated_routing_rotation_and_member_removal_close_old_authority() {
    let relay = Relay::start();
    let a = Participant::new(vec![relay.url.clone()]);
    let b = Participant::new(a.relays.clone());
    let mut alice = a.open();
    let mut bob = b.open();
    alice.bootstrap().unwrap();
    bob.bootstrap().unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    let peer = b.keys.public_key().to_hex();
    let sender = a.keys.public_key().to_hex();
    let original = alice.invite(&peer).unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    bob.accept_invitation(&sender).unwrap();
    alice.harness_change_group(&original, false).unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    let rotated = alice.fence(&peer).unwrap();
    assert_ne!(original.binding, rotated.binding);
    // Production v1 has a 1s quiescence / 5s maximum convergence window.
    // Drive the real timer, without enabling SDK test policy overrides.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(8);
    while bob.fence(&sender).as_ref().map(|f| &f.binding).ok() != Some(&rotated.binding)
        && std::time::Instant::now() < deadline
    {
        std::thread::sleep(std::time::Duration::from_millis(200));
        bob.sync().unwrap();
    }
    assert_eq!(rotated.binding, bob.fence(&sender).unwrap().binding);
    alice.sync().unwrap();
    let message = alice
        .handoff(
            &rotated,
            1220,
            vec![],
            "synthetic rotated route".into(),
            now_ms() + 60_000,
        )
        .unwrap();
    alice.publish_handoff(&message, &rotated).unwrap();
    bob.sync().unwrap();
    assert!(
        bob.inbox()
            .unwrap()
            .iter()
            .any(|m| m.content == "synthetic rotated route")
    );
    alice.harness_change_group(&rotated, true).unwrap();
    alice.sync().unwrap();
    bob.sync().unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(8);
    while !bob.harness_group_terminal(&rotated.group).unwrap()
        && std::time::Instant::now() < deadline
    {
        std::thread::sleep(std::time::Duration::from_millis(200));
        bob.sync().unwrap();
    }
    assert!(bob.harness_group_terminal(&rotated.group).unwrap());
    assert!(bob.fence(&sender).is_err());
    assert_eq!(alice.fence(&peer).unwrap_err().0, "two_leaves_required");
    assert!(
        bob.inbox()
            .unwrap()
            .iter()
            .all(|m| m.invalidated && m.content.is_empty())
    );
    drop(bob);
    let mut bob = b.open();
    assert!(bob.fence(&sender).is_err());
}

#[test]
fn paged_offline_history_resumes_after_restart_and_rejects_invalid_welcomes() {
    use nostr::{EventBuilder, Kind, Tag, Timestamp};
    let relay = Relay::start();
    let participant = Participant::new(vec![relay.url.clone()]);
    let attacker = Keys::generate();
    let time = now_ms() / 1000;
    for offset in 1..=75 {
        let event = EventBuilder::new(Kind::GiftWrap, "synthetic invalid ciphertext")
            .tags([Tag::public_key(participant.keys.public_key())])
            .custom_created_at(Timestamp::from(time - offset))
            .sign_with_keys(&attacker)
            .unwrap();
        relay.events.lock().unwrap().push(event);
    }
    let mut node = participant.open();
    node.bootstrap().unwrap();
    node.sync().unwrap();
    assert!(node.relay_summary().unwrap()[&relay.url].contains(&RelayStatus::Limited));
    assert!(node.inbox().unwrap().is_empty());
    assert!(node.peers().unwrap().is_empty());
    drop(node);
    let mut node = participant.open();
    node.sync().unwrap();
    assert!(node.relay_summary().unwrap()[&relay.url].contains(&RelayStatus::ReadComplete));
    assert!(node.inbox().unwrap().is_empty());
    assert!(node.peers().unwrap().is_empty());
}
