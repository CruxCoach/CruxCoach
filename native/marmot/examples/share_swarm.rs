//! T3 (PLAN-0.2.6 §10.2) on the native path, not a test and not in CI: one
//! sender and a swarm of real Olm friends (10 and the 0.2.6 limit of 32).
//! Measures what one change costs the sender (admission one by one and in one
//! transaction, B13), how many mailbox events and bytes it becomes, how long
//! public relays take to accept them at the app's pacing, and what fetching
//! and deleting cost the friends. Everything stays in temporary folders.
//!
//! `cargo run --release --example share_swarm` (debug builds are ~10x slower).
use cruxcoach_marmot::olm::{MAX_OUTBOUND_BYTES as MAX, OlmEngine};
use cruxcoach_marmot::p2p;
use nostr::Keys;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

struct Node {
    engine: OlmEngine,
    id: String,
    dir: tempfile::TempDir,
}

fn node() -> Node {
    let keys = Keys::generate();
    let dir = tempfile::tempdir().unwrap();
    let engine = OlmEngine::open(
        &dir.path().join("olm.db"),
        &p2p::random::<32>().unwrap(),
        &keys.public_key().to_hex(),
        Arc::new(keys.clone()),
    )
    .unwrap();
    Node {
        engine,
        id: keys.public_key().to_hex(),
        dir,
    }
}

fn exchange(a: &mut Node, b: &mut Node) {
    for _ in 0..16 {
        let Some(request) = a.engine.exchange_begin(&b.id, MAX).unwrap() else {
            return;
        };
        let (response, _) = b.engine.exchange_serve(&request, MAX).unwrap();
        let (more, _) = a.engine.exchange_end(&response, MAX).unwrap();
        if !more {
            return;
        }
    }
}

/// The sender invites, the friend accepts, two direct rounds settle the link.
fn befriend(sender: &mut Node, friend: &mut Node) {
    sender.engine.invite(&friend.id).unwrap();
    let package = sender.engine.invitation(&friend.id).unwrap();
    friend.engine.import_invitation(&package).unwrap();
    friend.engine.accept(&sender.id).unwrap();
    exchange(friend, sender);
    exchange(friend, sender);
    exchange(sender, friend);
}

fn now_s() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn ms(started: Instant) -> f64 {
    started.elapsed().as_secs_f64() * 1000.0
}

fn db_bytes(node: &Node) -> u64 {
    std::fs::metadata(node.dir.path().join("olm.db"))
        .map(|m| m.len())
        .unwrap_or(0)
}

/// Minutes until a relay paced at `burst` then `per_minute` took `events`.
fn minutes(events: usize, burst: usize, per_minute: usize) -> f64 {
    events.saturating_sub(burst) as f64 / per_minute as f64
}

fn measure(friends: usize) -> Value {
    let mut sender = node();
    let mut swarm: Vec<Node> = (0..friends).map(|_| node()).collect();
    let started = Instant::now();
    for friend in swarm.iter_mut() {
        befriend(&mut sender, friend);
    }
    let setup_ms = ms(started);
    for friend in swarm.iter_mut() {
        exchange(&mut sender, friend);
        let _ = friend.engine.inbox_after(0, 64);
    }
    // A delta of about 2 KiB, as a training session of a few records makes.
    let delta = "x".repeat(2_048);
    let db_before = db_bytes(&sender);

    // One by one: one transaction (and one commit) per friend.
    let started = Instant::now();
    for friend in &swarm {
        sender.engine.send(&friend.id, "single", &delta).unwrap();
    }
    let single_ms = ms(started);

    // All at once (B13): one transaction for every friend.
    let items: Vec<(String, String, String)> = swarm
        .iter()
        .map(|f| (f.id.clone(), "batch".to_string(), delta.clone()))
        .collect();
    let started = Instant::now();
    let results = sender.engine.send_many(&items).unwrap();
    let batch_ms = ms(started);
    assert!(results.iter().all(|r| r.is_ok()));

    // A busy session: ten changes for everyone in one batch.
    let items: Vec<(String, String, String)> = swarm
        .iter()
        .flat_map(|f| (0..10).map(move |n| (f.id.clone(), format!("session{n}"), "y".repeat(512))))
        .collect();
    let started = Instant::now();
    sender.engine.send_many(&items).unwrap();
    let session_ms = ms(started);
    let db_after = db_bytes(&sender);

    // What it becomes in mailboxes: one event per friend (pairwise Olm).
    let now = now_s();
    let started = Instant::now();
    let outgoing = sender.engine.mailbox_outgoing(now, 0, 64 << 20).unwrap();
    let outgoing_ms = ms(started);
    let mut per_peer: HashMap<String, Vec<String>> = HashMap::new();
    let mut bytes = 0;
    let mut classes: HashMap<usize, usize> = HashMap::new();
    for entry in &outgoing {
        let json = entry["event"].as_str().unwrap().to_string();
        bytes += json.len();
        let event: Value = serde_json::from_str(&json).unwrap();
        let raw = event["content"].as_str().unwrap().len() * 3 / 4;
        *classes.entry(raw / 1024).or_default() += 1;
        per_peer
            .entry(entry["peer"].as_str().unwrap().to_string())
            .or_default()
            .push(json);
    }
    let events = outgoing.len();

    // The friends fetch, open and delete (receipts).
    let started = Instant::now();
    let mut deletions = 0;
    for friend in swarm.iter_mut() {
        let theirs = per_peer.remove(&friend.id).unwrap_or_default();
        let result = friend.engine.mailbox_absorb(now, &theirs).unwrap();
        assert_eq!(result["refused"], 0);
        deletions += friend.engine.mailbox_deletions(now).unwrap().len();
    }
    let receive_ms = ms(started);

    // Pacing (MailboxDelivery): public relays 6 at once then 6 a minute, the
    // CruxCoach mailbox 30 then 120. Every event goes to CruxCoach, one of
    // three A relays and one of three B relays (rendezvous spreads them).
    let per_public = events.div_ceil(3);
    json!({
        "friends": friends,
        "setup_ms": setup_ms.round(),
        "admit_one_by_one_ms": single_ms.round(),
        "admit_in_one_transaction_ms": batch_ms.round(),
        "session_10_changes_ms": session_ms.round(),
        "sender_db_growth_kib": (db_after.saturating_sub(db_before)) / 1024,
        "mailbox_events": events,
        "mailbox_bytes_kib": bytes / 1024,
        "size_classes_kib": classes,
        "outgoing_ms": outgoing_ms.round(),
        "friends_receive_and_delete_ms": receive_ms.round(),
        "deletion_events": deletions,
        "minutes_to_place_public_relay": minutes(per_public, 6, 6),
        "minutes_to_place_cruxcoach": minutes(events, 30, 120),
    })
}

fn main() {
    for friends in [10, 32] {
        println!("{}", measure(friends));
    }
}
