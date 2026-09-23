use cruxcoach_marmot::engine::LocalProofSigner;
use cruxcoach_marmot::host::{Host, HostConfig};
use nostr::{JsonUtil, Keys};
use serde_json::{Value, json};
use std::sync::{Arc, atomic::Ordering};
use std::time::{Duration, Instant};
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
    fn account(&self) -> String {
        self.keys.public_key().to_hex()
    }
    fn try_open(&self) -> Result<Host, cruxcoach_marmot::error::Error> {
        Host::open(
            &self.dir.path().join("session.db"),
            SqlCipherKey::new(self.db_key.clone()).unwrap(),
            HostConfig {
                account: self.account(),
                relays: self.relays.clone(),
                local_test: true,
            },
            Arc::new(self.keys.clone()),
            Arc::new(LocalProofSigner(self.keys.clone())),
        )
    }
    fn open(&self) -> Host {
        let host = self.try_open().unwrap();
        call(&host, json!({"op":"set_online","online":true}));
        host
    }
}

fn try_call(host: &Host, value: Value) -> Result<Value, &'static str> {
    host.call(&value.to_string()).map_err(|e| e.0)
}

fn call(host: &Host, value: Value) -> Value {
    try_call(host, value.clone()).unwrap_or_else(|e| panic!("{} refused: {e}", value["op"]))
}

fn settle(hosts: &[&Host]) {
    for host in hosts {
        call(host, json!({"op":"sync"}));
    }
}

fn wait_until(hosts: &[&Host], seconds: u64, mut done: impl FnMut() -> bool) -> bool {
    let deadline = Instant::now() + Duration::from_secs(seconds);
    loop {
        settle(hosts);
        if done() {
            return true;
        }
        if Instant::now() > deadline {
            return false;
        }
        std::thread::sleep(Duration::from_millis(200));
    }
}

fn peer_state(host: &Host, peer: &str) -> Option<String> {
    call(host, json!({"op":"status"}))["peers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["account"] == peer)
        .map(|p| p["state"].as_str().unwrap().to_string())
}

fn inbox(host: &Host) -> Vec<Value> {
    call(
        host,
        json!({"op":"next","after":0,"generation":0,"timeout_ms":0,"limit":64}),
    )["items"]
        .as_array()
        .unwrap()
        .clone()
}

fn send(host: &Host, peer: &str, token: &str, content: &str) -> Result<Value, &'static str> {
    try_call(
        host,
        json!({"op":"send","peer":peer,"token":token,"content":content}),
    )
}

/// Alice invites Bob; Bob accepts and speaks first, which activates Alice.
fn friendship(a: &Participant, b: &Participant, alice: &Host, bob: &Host) {
    for host in [alice, bob] {
        call(host, json!({"op":"set_discovery","enabled":true}));
    }
    assert!(wait_until(&[alice, bob], 20, || call(
        bob,
        json!({"op":"status"})
    )["local"]["outbound_pending"]
        == 0
        && call(alice, json!({"op":"status"}))["local"]["outbound_pending"]
            == 0));
    call(alice, json!({"op":"invite","peer":b.account()}));
    assert_eq!(peer_state(alice, &b.account()).as_deref(), Some("pending"));
    assert!(wait_until(&[alice, bob], 30, || peer_state(
        bob,
        &a.account()
    )
    .as_deref()
        == Some("invited")));
    // An unaccepted invitation delivers nothing to the app.
    assert!(inbox(bob).is_empty());
    call(bob, json!({"op":"accept","peer":a.account()}));
    send(bob, &a.account(), "hello", "synthetic acceptance").unwrap();
    assert!(wait_until(&[alice, bob], 30, || peer_state(
        alice,
        &b.account()
    )
    .as_deref()
        == Some("active")));
    let items = inbox(alice);
    assert_eq!(items.len(), 1);
    assert_eq!(items[0]["content"], "synthetic acceptance");
    call(alice, json!({"op":"ack","seqs":[items[0]["seq"]]}));
}

#[test]
fn invite_accept_redundant_relays_restart_and_epoch_change_keep_the_friendship() {
    let relay = Relay::start();
    let redundant = Relay::start();
    // No CruxCoach relay or backend occurs in either pool.
    let a = Participant::new(vec![relay.url.clone(), redundant.url.clone()]);
    // Equivalent URLs with a trailing slash select the same configured endpoint.
    let b = Participant::new(a.relays.iter().map(|u| format!("{u}/")).collect());
    let alice = a.open();
    let bob = b.open();
    friendship(&a, &b, &alice, &bob);

    send(&alice, &b.account(), "d1", "synthetic owner data").unwrap();
    assert!(wait_until(&[&alice, &bob], 30, || inbox(&bob)
        .iter()
        .any(|i| i["content"] == "synthetic owner data")));
    for event in relay.events.lock().unwrap().iter() {
        assert!(!event.as_json().contains("synthetic owner data"));
        if event.kind.as_u16() == 445 {
            assert_ne!(event.pubkey, a.keys.public_key());
            assert_ne!(event.pubkey, b.keys.public_key());
        }
    }
    // Unacknowledged plaintext survives a restart; acknowledged does not return.
    drop(bob);
    let bob = b.open();
    let pending = inbox(&bob);
    assert_eq!(pending.len(), 1);
    call(&bob, json!({"op":"ack","seqs":[pending[0]["seq"]]}));
    assert!(inbox(&bob).is_empty());

    // A self-update (post-compromise security) advances the epoch; the
    // friendship and its data flow survive it.
    let before = call(&alice, json!({"op":"epoch","peer":b.account()}))
        .as_u64()
        .unwrap();
    call(&alice, json!({"op":"rotate","peer":b.account()}));
    assert!(wait_until(&[&alice, &bob], 30, || {
        let a_epoch = call(&alice, json!({"op":"epoch","peer":b.account()}))
            .as_u64()
            .unwrap();
        let b_epoch = call(&bob, json!({"op":"epoch","peer":a.account()}))
            .as_u64()
            .unwrap();
        a_epoch > before && a_epoch == b_epoch
    }));
    let deadline = Instant::now() + Duration::from_secs(20);
    while let Err(code) = send(&alice, &b.account(), "d2", "synthetic after rotation") {
        assert!(
            code == "session_busy" && Instant::now() < deadline,
            "{code}"
        );
        settle(&[&alice, &bob]);
        std::thread::sleep(Duration::from_millis(250));
    }
    assert!(wait_until(&[&alice, &bob], 30, || inbox(&bob)
        .iter()
        .any(|i| i["content"] == "synthetic after rotation")));
    assert_eq!(peer_state(&alice, &b.account()).as_deref(), Some("active"));
    assert_eq!(peer_state(&bob, &a.account()).as_deref(), Some("active"));
}

#[test]
fn token_idempotency_outage_durability_and_cancel() {
    let relay = Relay::start();
    let a = Participant::new(vec![relay.url.clone()]);
    let b = Participant::new(a.relays.clone());
    let alice = a.open();
    let bob = b.open();
    friendship(&a, &b, &alice, &bob);

    let first = send(&alice, &b.account(), "t1", "synthetic token body").unwrap();
    assert_eq!(first["duplicate"], false);
    let again = send(&alice, &b.account(), "t1", "synthetic token body").unwrap();
    assert_eq!(again["duplicate"], true);
    assert_eq!(again["event"], first["event"]);
    assert_eq!(
        send(&alice, &b.account(), "t1", "different content").unwrap_err(),
        "token_conflict"
    );

    // Total outage: the exact ciphertext stays durable across a restart.
    relay.online.store(false, Ordering::SeqCst);
    std::thread::sleep(Duration::from_millis(300));
    send(&alice, &b.account(), "t2", "synthetic queued during outage").unwrap();
    settle(&[&alice]);
    assert!(
        call(&alice, json!({"op":"status"}))["local"]["outbound_pending"]
            .as_i64()
            .unwrap()
            >= 1
    );
    drop(alice);
    let alice = a.open();
    assert!(
        call(&alice, json!({"op":"status"}))["local"]["outbound_pending"]
            .as_i64()
            .unwrap()
            >= 1
    );

    // Narrowing withdraws what no public relay accepted yet.
    send(
        &alice,
        &b.account(),
        "t3",
        "synthetic withdrawn before replication",
    )
    .unwrap();
    let cancelled = call(&alice, json!({"op":"cancel","peer":b.account()}))["cancelled"]
        .as_u64()
        .unwrap();
    assert!(cancelled >= 2, "{cancelled}");
    relay.online.store(true, Ordering::SeqCst);
    call(&alice, json!({"op":"set_online","online":true}));
    std::thread::sleep(Duration::from_millis(500));
    assert!(wait_until(&[&alice, &bob], 20, || inbox(&bob)
        .iter()
        .any(|i| i["content"] == "synthetic token body")));
    settle(&[&alice, &bob]);
    let contents: Vec<Value> = inbox(&bob).iter().map(|i| i["content"].clone()).collect();
    assert!(!contents.contains(&json!("synthetic queued during outage")));
    assert!(!contents.contains(&json!("synthetic withdrawn before replication")));
    let status = call(&alice, json!({"op":"status"}));
    let peer = status["peers"]
        .as_array()
        .unwrap()
        .iter()
        .find(|p| p["account"] == b.account())
        .unwrap()
        .clone();
    assert!(peer["cancelled_at"].as_u64().unwrap() > 0);
}

#[test]
fn ending_purges_pending_plaintext_and_refuses_further_messages() {
    let relay = Relay::start();
    let a = Participant::new(vec![relay.url.clone()]);
    let b = Participant::new(a.relays.clone());
    let alice = a.open();
    let bob = b.open();
    friendship(&a, &b, &alice, &bob);
    send(&alice, &b.account(), "e1", "synthetic ended marker").unwrap();
    assert!(wait_until(&[&alice, &bob], 30, || !inbox(&bob).is_empty()));
    assert!(
        call(
            &bob,
            json!({"op":"private_storage_matches","marker":"synthetic ended marker"})
        )
        .as_u64()
        .unwrap()
            > 0
    );
    call(&bob, json!({"op":"end","peer":a.account()}));
    assert!(inbox(&bob).is_empty());
    assert_eq!(
        call(
            &bob,
            json!({"op":"private_storage_matches","marker":"synthetic ended marker"})
        ),
        0
    );
    assert_eq!(
        send(&bob, &a.account(), "e2", "no longer allowed").unwrap_err(),
        "session_not_active"
    );
    send(&alice, &b.account(), "e3", "synthetic after end").unwrap();
    settle(&[&alice, &bob]);
    settle(&[&alice, &bob]);
    assert!(inbox(&bob).is_empty());
    assert_eq!(
        call(
            &bob,
            json!({"op":"private_storage_matches","marker":"synthetic after end"})
        ),
        0
    );
    assert_eq!(peer_state(&bob, &a.account()).as_deref(), Some("ended"));
}

#[test]
fn negentropy_catch_up_and_socketless_local_relay() {
    let runtime = tokio::runtime::Runtime::new().unwrap();
    let mock = runtime
        .block_on(nostr_relay_builder::MockRelay::run())
        .unwrap();
    let url = runtime.block_on(mock.url()).to_string();
    let a = Participant::new(vec![url.clone()]);
    let b = Participant::new(vec![url]);
    let alice = a.open();
    let bob = b.open();
    friendship(&a, &b, &alice, &bob);
    // The in-process relay answers REQ over a duplex stream from the store.
    assert!(
        call(
            &alice,
            json!({"op":"local_query","kinds":[10002, 10050, 30443]})
        )
        .as_u64()
        .unwrap()
            >= 3
    );
    // Bob misses live delivery; NIP-77 reconciliation fetches it later.
    call(&bob, json!({"op":"set_online","online":false}));
    send(&alice, &b.account(), "n1", "synthetic missed live delivery").unwrap();
    assert!(wait_until(&[&alice], 20, || call(
        &alice,
        json!({"op":"status"})
    )["local"]["outbound_pending"]
        == 0));
    call(&bob, json!({"op":"set_online","online":true}));
    assert!(wait_until(&[&bob], 30, || inbox(&bob)
        .iter()
        .any(|i| i["content"] == "synthetic missed live delivery")));
    let relays = call(&bob, json!({"op":"status"}))["relays"].clone();
    assert_eq!(relays[0]["nip77"], true);
    drop(mock);
}

#[test]
fn exclusive_session_lease_archive_and_wrong_keys() {
    use std::os::unix::fs::PermissionsExt;
    let relay = Relay::start();
    let p = Participant::new(vec![relay.url.clone()]);
    let host = p.open();
    let path = p.dir.path().join("session.db");
    assert_eq!(
        p.try_open().err().map(|e| e.0),
        Some("session_already_open")
    );
    assert_eq!(
        std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert_eq!(
        cruxcoach_marmot::engine::archive_storage(&path)
            .unwrap_err()
            .0,
        "session_already_open"
    );
    drop(host);
    let mut wrong = Participant::new(p.relays.clone());
    wrong.dir = tempfile::tempdir().unwrap();
    std::fs::copy(&path, wrong.dir.path().join("session.db")).unwrap();
    assert_eq!(wrong.try_open().err().map(|e| e.0), Some("native_open"));
    cruxcoach_marmot::engine::archive_storage(&path).unwrap();
    assert!(!path.exists());
    assert_eq!(
        std::fs::read_dir(p.dir.path().join("archives"))
            .unwrap()
            .count(),
        1
    );
    let reopened = p.open();
    let status = call(&reopened, json!({"op":"status"}));
    assert_eq!(status["discovery"]["enabled"], false);
    assert!(status["peers"].as_array().unwrap().is_empty());
}

#[test]
fn unrequested_and_invalid_relay_input_never_reaches_the_engine() {
    use nostr::{EventBuilder, Kind, Tag, Timestamp};
    let relay = Relay::start();
    let p = Participant::new(vec![relay.url.clone()]);
    let attacker = Keys::generate();
    let now = cruxcoach_marmot::protocol::now_s();
    for offset in 1..=40 {
        let wrap = EventBuilder::new(Kind::GiftWrap, "synthetic invalid ciphertext")
            .tags([Tag::public_key(p.keys.public_key())])
            .custom_created_at(Timestamp::from(now - offset))
            .sign_with_keys(&attacker)
            .unwrap();
        relay.events.lock().unwrap().push(wrap);
        let group = EventBuilder::new(Kind::Custom(445), "synthetic foreign group")
            .tags([Tag::parse(["h", &"a".repeat(64)]).unwrap()])
            .custom_created_at(Timestamp::from(now - offset))
            .sign_with_keys(&attacker)
            .unwrap();
        relay.events.lock().unwrap().push(group);
    }
    let host = p.open();
    call(&host, json!({"op":"set_discovery","enabled":true}));
    for _ in 0..3 {
        settle(&[&host]);
    }
    let status = call(&host, json!({"op":"status"}));
    assert!(status["peers"].as_array().unwrap().is_empty());
    assert!(inbox(&host).is_empty());
    // Gift wraps addressed to us are admitted (and rejected by MDK); foreign
    // group traffic for routes we never joined is not even stored.
    assert_eq!(call(&host, json!({"op":"local_query","kinds":[445]})), 0);
}
