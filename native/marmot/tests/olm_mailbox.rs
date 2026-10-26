//! The Olm channel through mailbox events: two real
//! engines become friends directly, then talk only through events a relay
//! would store. Tests play the relay: they may lose, duplicate or replay
//! events, or hand them to a stranger.
use cruxcoach_marmot::mailbox::{self, KIND, MAX_AGE_S};
use cruxcoach_marmot::olm::{MAX_OUTBOUND_BYTES as MAX, OlmEngine};
use cruxcoach_marmot::p2p;
use nostr::{Event, JsonUtil, Keys, Kind};
use std::sync::Arc;

const NOW: u64 = 1_793_000_000;
const HOUR: u64 = 3_600;

struct Node {
    engine: OlmEngine,
    keys: Keys,
    _dir: tempfile::TempDir,
}

impl Node {
    fn new() -> Self {
        let keys = Keys::generate();
        let dir = tempfile::tempdir().unwrap();
        let key = p2p::random::<32>().unwrap();
        let id = keys.public_key().to_hex();
        let engine = OlmEngine::open(
            &dir.path().join("olm.db"),
            &key,
            &id,
            Arc::new(keys.clone()),
        )
        .unwrap();
        Self {
            engine,
            keys,
            _dir: dir,
        }
    }
    fn id(&self) -> String {
        self.keys.public_key().to_hex()
    }
    fn drain(&mut self) -> Vec<String> {
        let items = self.engine.inbox_after(0, 64).unwrap();
        let seqs: Vec<i64> = items.iter().map(|v| v["seq"].as_i64().unwrap()).collect();
        self.engine.inbox_ack(&seqs).unwrap();
        items
            .into_iter()
            .map(|v| v["content"].as_str().unwrap().to_string())
            .collect()
    }
    fn outgoing(&mut self, now: u64) -> Vec<String> {
        self.engine
            .mailbox_outgoing(now, 0, 4 << 20)
            .unwrap()
            .into_iter()
            .map(|v| v["event"].as_str().unwrap().to_string())
            .collect()
    }
    fn absorb(&mut self, now: u64, events: &[String]) -> serde_json::Value {
        self.engine.mailbox_absorb(now, events).unwrap()
    }
}

/// `(peer, role, deletion)` for what `node` should delete now.
fn deletions(node: &mut Node, now: u64) -> Vec<(String, String, Event)> {
    node.engine
        .mailbox_deletions(now)
        .unwrap()
        .into_iter()
        .map(|v| {
            (
                v["peer"].as_str().unwrap().to_string(),
                v["role"].as_str().unwrap().to_string(),
                Event::from_json(v["event"].as_str().unwrap()).unwrap(),
            )
        })
        .collect()
}

fn tags(event: &Event) -> Vec<Vec<String>> {
    event.tags.iter().map(|t| t.as_slice().to_vec()).collect()
}

/// Befriend directly (QR and one exchange), as before any mailbox exists.
fn friends() -> (Node, Node) {
    let (mut a, mut b) = (Node::new(), Node::new());
    a.engine.invite(&b.id()).unwrap();
    let package = a.engine.invitation(&b.id()).unwrap();
    b.engine.import_invitation(&package).unwrap();
    b.engine.accept(&a.id()).unwrap();
    for _ in 0..3 {
        let mut rounds = 0;
        while let Some(request) = b.engine.exchange_begin(&a.id(), MAX).unwrap() {
            let (response, _) = a.engine.exchange_serve(&request, MAX).unwrap();
            let (more, _) = b.engine.exchange_end(&response, MAX).unwrap();
            rounds += 1;
            if !more || rounds > 16 {
                break;
            }
        }
    }
    assert_eq!(a.engine.peer(&b.id()).unwrap().unwrap().state, "active");
    a.drain();
    b.drain();
    (a, b)
}

#[test]
fn messages_travel_both_ways_through_mailbox_events_only() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "from a").unwrap();
    let events = a.outgoing(NOW);
    assert_eq!(events.len(), 1);
    let result = b.absorb(NOW, &events);
    assert_eq!(result["accepted"], 1);
    assert_eq!(b.drain(), vec!["from a"]);
    b.engine.send(&a.id(), "t2", "from b").unwrap();
    let back = b.outgoing(NOW + 60);
    assert_eq!(back.len(), 1, "data carries the ack for a's frame");
    assert_eq!(a.absorb(NOW + 60, &back)["accepted"], 1);
    assert_eq!(a.drain(), vec!["from b"]);
    // a's frame is acknowledged: nothing of it is left to publish.
    assert_eq!(a.engine.pending_for(&b.id()).unwrap(), 0);
}

#[test]
fn an_event_names_no_one_and_only_the_friend_can_take_it() {
    let (mut a, mut b) = friends();
    let mut stranger = Node::new();
    a.engine.send(&b.id(), "t1", "for b only").unwrap();
    let events = a.outgoing(NOW);
    let json = &events[0];
    for identity in [a.id(), b.id()] {
        assert!(!json.contains(&identity), "no npub on the wire");
    }
    let event = Event::from_json(json).unwrap();
    assert_eq!(event.kind.as_u16(), KIND);
    assert!(event.pubkey != a.keys.public_key() && event.pubkey != b.keys.public_key());
    assert!(!json.contains("for b only"));
    assert_eq!(stranger.absorb(NOW, &events)["refused"], 1);
    assert!(stranger.drain().is_empty());
    // b fetches it under the address b asks for today.
    let address = event
        .tags
        .iter()
        .find(|t| t.as_slice()[0] == "z")
        .unwrap()
        .as_slice()[1]
        .clone();
    assert!(
        b.engine
            .mailbox_addresses(NOW, 2)
            .unwrap()
            .contains(&address)
    );
    assert!(
        !a.engine
            .mailbox_addresses(NOW, 2)
            .unwrap()
            .contains(&address),
        "a fetches the other direction"
    );
    assert_eq!(b.absorb(NOW, &events)["accepted"], 1);
}

#[test]
fn duplicates_replays_and_copies_from_several_mailboxes_apply_once() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "once").unwrap();
    let events = a.outgoing(NOW);
    let first = b.absorb(NOW, &[events.clone(), events.clone()].concat());
    assert_eq!(
        (first["accepted"].as_i64(), first["duplicates"].as_i64()),
        (Some(1), Some(1))
    );
    assert_eq!(b.absorb(NOW + HOUR, &events)["duplicates"], 1);
    // A republished frame (new event, same frame) is a duplicate frame, never a second message.
    let again = a.outgoing(NOW + mailbox::DAY_S + 1);
    assert_eq!(
        again.len(),
        1,
        "unacknowledged frames are published again after a day"
    );
    assert_eq!(b.absorb(NOW + mailbox::DAY_S + 1, &again)["accepted"], 1);
    assert_eq!(b.drain(), vec!["once"]);
}

#[test]
fn acks_without_data_wait_and_then_go_out_alone() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "data").unwrap();
    b.absorb(NOW, &a.outgoing(NOW));
    assert!(b.outgoing(NOW).is_empty(), "an ack alone waits for data");
    assert!(b.outgoing(NOW + HOUR).is_empty());
    let acks = b.outgoing(NOW + 7 * HOUR);
    assert_eq!(acks.len(), 1, "after some hours it goes alone");
    a.absorb(NOW + 7 * HOUR, &acks);
    assert_eq!(a.engine.pending_for(&b.id()).unwrap(), 0);
    assert!(
        a.outgoing(NOW + 2 * mailbox::DAY_S).is_empty(),
        "acknowledged: nothing published again"
    );
}

#[test]
fn addresses_change_at_midnight_and_old_events_still_open_until_they_expire() {
    let (mut a, mut b) = friends();
    let today = mailbox::day(NOW);
    let midnight = (today + 1) * mailbox::DAY_S;
    a.engine.send(&b.id(), "t1", "before midnight").unwrap();
    let late = a.outgoing(midnight - 30);
    a.engine.send(&b.id(), "t2", "after midnight").unwrap();
    let early = a.outgoing(midnight + 30);
    let address = |json: &String| {
        Event::from_json(json)
            .unwrap()
            .tags
            .iter()
            .find(|t| t.as_slice()[0] == "z")
            .unwrap()
            .as_slice()[1]
            .clone()
    };
    assert_ne!(address(&late[0]), address(early.last().unwrap()));
    let fetched = b.engine.mailbox_addresses(midnight + 60, 2).unwrap();
    assert!(
        fetched.contains(&address(&late[0])) && fetched.contains(&address(early.last().unwrap()))
    );
    assert_eq!(
        b.absorb(midnight + 60, &[late.clone(), early].concat())["accepted"],
        2
    );
    assert_eq!(b.drain(), vec!["before midnight", "after midnight"]);
    // Past the maximum age nothing is processed, even if a relay still serves it.
    let (mut a2, mut b2) = friends();
    a2.engine.send(&b2.id(), "t1", "old").unwrap();
    let old = a2.outgoing(NOW);
    assert_eq!(b2.absorb(NOW + MAX_AGE_S + HOUR, &old)["refused"], 1);
    assert!(b2.drain().is_empty());
}

#[test]
fn the_largest_sync_message_fits_one_event() {
    let (mut a, mut b) = friends();
    // ShareProtocol.MAX_BYTES on the app side.
    let content = "x".repeat(40_960);
    a.engine.send(&b.id(), "big", &content).unwrap();
    let events = a.outgoing(NOW);
    assert_eq!(events.len(), 1);
    assert!(events[0].len() <= 65_536, "{} bytes", events[0].len());
    assert_eq!(b.absorb(NOW, &events)["accepted"], 1);
    assert_eq!(b.drain(), vec![content]);
}

#[test]
fn one_call_stays_within_what_crosses_jni_and_the_rest_follows() {
    let (mut a, mut b) = friends();
    for i in 0..6 {
        a.engine
            .send(&b.id(), &format!("big{i}"), &"y".repeat(40_000))
            .unwrap();
    }
    let first = a.engine.mailbox_outgoing(NOW, 0, 3 * 65_536).unwrap();
    assert_eq!(first.len(), 3, "a cap of three events");
    let rest = a.engine.mailbox_outgoing(NOW, 0, 4 << 20).unwrap();
    assert_eq!(rest.len(), 3, "the rest on the next call, nothing twice");
    let events: Vec<String> = first
        .iter()
        .chain(&rest)
        .map(|v| v["event"].as_str().unwrap().to_string())
        .collect();
    assert_eq!(b.absorb(NOW, &events)["accepted"], 6);
    assert_eq!(b.drain().len(), 6);
}

#[test]
fn the_receiver_deletes_what_it_stored_with_the_key_it_was_published_under() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "one").unwrap();
    let events = a.outgoing(NOW);
    let published = Event::from_json(&events[0]).unwrap();
    assert!(
        deletions(&mut b, NOW).is_empty(),
        "nothing taken, nothing to delete"
    );
    b.absorb(NOW, &events);
    let found = deletions(&mut b, NOW + 60);
    assert_eq!(found.len(), 1);
    let (peer, role, deletion) = &found[0];
    assert_eq!(
        (peer.as_str(), role.as_str()),
        (a.id().as_str(), "received")
    );
    assert_eq!(deletion.kind, Kind::EventDeletion);
    assert_eq!(
        deletion.pubkey, published.pubkey,
        "the day's key the event was published under"
    );
    assert!(deletion.verify().is_ok());
    let t = tags(deletion);
    assert!(t.contains(&vec!["e".to_string(), published.id.to_hex()]));
    assert!(t.contains(&vec!["k".to_string(), KIND.to_string()]));
    assert!(t.iter().any(|t| t[0] == "expiration"));
    assert!(deletion.content.is_empty());
    let json = deletion.as_json();
    assert!(!json.contains(&a.id()) && !json.contains(&b.id()));
    assert_eq!(deletion.created_at.as_secs() % 60, 0);
    assert!(deletions(&mut b, NOW + 120).is_empty(), "issued once");
    // A duplicate fetched later from another mailbox is not deleted twice.
    b.absorb(NOW + 180, &events);
    assert!(deletions(&mut b, NOW + 240).is_empty());
}

#[test]
fn many_events_are_deleted_in_batches_of_fifty_per_day_and_key() {
    let (mut a, mut b) = friends();
    let mut events = Vec::new();
    for i in 0..60 {
        a.engine.send(&b.id(), &format!("t{i}"), "x").unwrap();
        events.extend(a.outgoing(NOW + i));
    }
    a.engine.send(&b.id(), "late", "y").unwrap();
    events.extend(a.outgoing(NOW + mailbox::DAY_S));
    assert_eq!(events.len(), 61);
    assert_eq!(b.absorb(NOW + mailbox::DAY_S, &events)["accepted"], 61);
    let found = deletions(&mut b, NOW + mailbox::DAY_S);
    let mut sizes: Vec<usize> = found
        .iter()
        .map(|(_, _, d)| tags(d).iter().filter(|t| t[0] == "e").count())
        .collect();
    sizes.sort();
    assert_eq!(sizes, vec![1, 10, 50]);
    let keys: std::collections::BTreeSet<_> = found.iter().map(|(_, _, d)| d.pubkey).collect();
    assert_eq!(keys.len(), 2, "one key per day");
}

#[test]
fn a_withdrawal_deletes_what_already_lies_in_the_mailboxes() {
    let (mut a, b) = friends();
    a.engine.send(&b.id(), "t1", "withdraw me").unwrap();
    let events = a.outgoing(NOW);
    let published = Event::from_json(&events[0]).unwrap();
    assert_eq!(a.engine.cancel(&b.id()).unwrap(), 1);
    let found = deletions(&mut a, NOW + 60);
    assert_eq!(found.len(), 1);
    let (peer, role, deletion) = &found[0];
    assert_eq!(
        (peer.as_str(), role.as_str()),
        (b.id().as_str(), "withdrawn")
    );
    assert_eq!(deletion.pubkey, published.pubkey);
    assert!(tags(deletion).contains(&vec!["e".to_string(), published.id.to_hex()]));
    assert!(
        a.outgoing(NOW + 2 * mailbox::DAY_S).is_empty(),
        "nothing of it goes out again"
    );
    assert!(deletions(&mut a, NOW + 120).is_empty());
    // Without a withdrawal nothing the sender published is deleted by the sender.
    a.engine.send(&b.id(), "t2", "keep me").unwrap();
    a.outgoing(NOW + 300);
    assert!(deletions(&mut a, NOW + 360).is_empty());
}

/// A crash while frames are stored: the event is not marked seen and not
/// deleted, the next fetch takes it again, and nothing is applied twice.
#[cfg(feature = "local-harness")]
#[test]
fn a_crash_between_storing_and_deleting_loses_nothing_and_applies_nothing_twice() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "survives").unwrap();
    let events = a.outgoing(NOW);
    cruxcoach_marmot::olm::FAIL_COMMITS.set(1);
    assert!(b.engine.mailbox_absorb(NOW, &events).is_err());
    cruxcoach_marmot::olm::FAIL_COMMITS.set(0);
    assert!(
        deletions(&mut b, NOW).is_empty(),
        "nothing is deleted before it is stored"
    );
    assert_eq!(b.absorb(NOW + 60, &events)["accepted"], 1);
    b.absorb(NOW + 120, &events);
    assert_eq!(b.drain(), vec!["survives"]);
    assert_eq!(deletions(&mut b, NOW + 180).len(), 1);
}

#[test]
fn both_friends_derive_the_same_mailbox_choice_per_direction() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "x").unwrap();
    b.engine.send(&a.id(), "t2", "y").unwrap();
    let a_out = a.engine.mailbox_outgoing(NOW, 0, 4 << 20).unwrap();
    let b_out = b.engine.mailbox_outgoing(NOW, 0, 4 << 20).unwrap();
    let a_in = a.engine.mailbox_fetch(NOW, 2).unwrap();
    let b_in = b.engine.mailbox_fetch(NOW, 2).unwrap();
    assert_eq!(
        a_out[0]["seed"], b_in[0]["seed"],
        "what a writes is where b reads"
    );
    assert_eq!(b_out[0]["seed"], a_in[0]["seed"]);
    assert_ne!(
        a_out[0]["seed"], a_in[0]["seed"],
        "the two directions choose separately"
    );
    assert_eq!(
        a_out[0]["relays"],
        serde_json::json!([]),
        "no explicit list in 0.2.6: derive"
    );
    assert_eq!(
        b_in[0]["addresses"].as_array().unwrap().len(),
        2,
        "today and yesterday"
    );
}

#[test]
fn the_queue_keeps_an_event_until_it_is_placed_and_rebuilds_it_when_it_is_too_old() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "placed").unwrap();
    let entry = a
        .engine
        .mailbox_outgoing(NOW, 0, 4 << 20)
        .unwrap()
        .remove(0);
    let id = entry["id"].as_str().unwrap().to_string();
    let queue = a.engine.mailbox_queue(NOW + 60).unwrap();
    assert_eq!(queue.len(), 1);
    assert_eq!(queue[0]["event"], entry["event"]);
    a.engine.mailbox_accepted(&id, "wss://one.example").unwrap();
    a.engine.mailbox_accepted(&id, "wss://one.example").unwrap();
    assert_eq!(
        a.engine.mailbox_queue(NOW + 120).unwrap()[0]["accepted"],
        serde_json::json!(["wss://one.example"])
    );
    a.engine.mailbox_done(&id).unwrap();
    assert!(a.engine.mailbox_queue(NOW + 180).unwrap().is_empty());
    // An event nobody placed for 50 minutes is rebuilt with a fresh signature.
    a.engine.send(&b.id(), "t2", "late").unwrap();
    let old = a.outgoing(NOW + 300);
    let later = NOW + 300 + mailbox::RESIGN_AFTER_S + 60;
    assert!(
        a.engine.mailbox_queue(later).unwrap().is_empty(),
        "the stale event is dropped"
    );
    let fresh = a.outgoing(later);
    assert_eq!(fresh.len(), 1);
    assert_ne!(fresh[0], old[0]);
    b.absorb(later, &[old, fresh].concat());
    assert_eq!(
        b.drain(),
        vec!["placed", "late"]
            .into_iter()
            .skip(1)
            .collect::<Vec<_>>(),
        "the frame once"
    );
}

#[test]
fn a_deletion_nobody_placed_is_issued_again() {
    let (mut a, mut b) = friends();
    a.engine.send(&b.id(), "t1", "x").unwrap();
    b.absorb(NOW, &a.outgoing(NOW));
    assert_eq!(deletions(&mut b, NOW).len(), 1);
    let later = NOW + mailbox::RESIGN_AFTER_S + 60;
    assert!(b.engine.mailbox_queue(later).unwrap().is_empty());
    assert_eq!(
        deletions(&mut b, later).len(),
        1,
        "issued again with a fresh signature"
    );
}

// ---- invitations through mailboxes ------------------------------------------------

/// Links and contact codes run on the real clock, so these tests do too.
fn real_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn pubkeys(events: &[String]) -> std::collections::HashMap<nostr::EventId, nostr::PublicKey> {
    events
        .iter()
        .map(|json| Event::from_json(json).unwrap())
        .map(|e| (e.id, e.pubkey))
        .collect()
}

#[test]
fn a_link_makes_friends_through_mailboxes_only_and_leaves_nothing_behind() {
    let (mut issuer, mut opener) = (Node::new(), Node::new());
    let now = real_now();
    let (code, expires) = issuer.engine.link_code().unwrap();
    assert!(expires > now + 6 * mailbox::DAY_S && expires <= now + 7 * mailbox::DAY_S);
    assert_eq!(opener.engine.import_contact(&code).unwrap(), issuer.id());

    // The offer: sealed to the link, under its own key, naming no one.
    let offers = opener.outgoing(now);
    assert_eq!(offers.len(), 1);
    let offer = Event::from_json(&offers[0]).unwrap();
    for identity in [issuer.id(), opener.id()] {
        assert!(!offers[0].contains(&identity), "no npub on the wire");
    }
    let exp: u64 = tags(&offer)
        .into_iter()
        .find(|t| t[0] == "expiration")
        .unwrap()[1]
        .parse()
        .unwrap();
    assert!(
        exp <= expires + mailbox::CLOCK_GRACE_S,
        "it ends with the link"
    );
    assert!(opener.outgoing(now + 60).is_empty(), "once a day");
    let again = opener.outgoing(now + mailbox::DAY_S + 1);
    assert_eq!(again.len(), 1, "published again the next day");
    assert_ne!(
        Event::from_json(&again[0]).unwrap().pubkey,
        offer.pubkey,
        "each offer has a key of its own"
    );

    // The issuer sees a request and confirms it.
    assert_eq!(issuer.absorb(now, &offers)["accepted"], 1);
    assert_eq!(
        issuer.engine.peer(&opener.id()).unwrap().unwrap().state,
        "invited"
    );
    // The link is used up: its address is no longer fetched, a copy no longer taken.
    let address = tags(&offer).into_iter().find(|t| t[0] == "z").unwrap()[1].clone();
    assert!(
        !issuer
            .engine
            .mailbox_addresses(now, 2)
            .unwrap()
            .contains(&address)
    );
    assert_eq!(issuer.absorb(now, &offers)["refused"], 1);
    issuer.engine.accept(&opener.id()).unwrap();

    // The acceptance comes back under the invitation secret of the package.
    let acceptance = issuer.outgoing(now);
    assert_eq!(acceptance.len(), 1);
    let before = opener.engine.mailbox_addresses(now, 2).unwrap();
    assert_eq!(opener.absorb(now, &acceptance)["accepted"], 1);
    assert_eq!(
        opener.engine.peer(&issuer.id()).unwrap().unwrap().state,
        "active"
    );

    // From now on both write under the link key.
    opener.engine.send(&issuer.id(), "t1", "hello").unwrap();
    let data = opener.outgoing(now + 60);
    assert_eq!(issuer.absorb(now + 60, &data)["accepted"], 1);
    assert_eq!(issuer.drain(), vec!["hello"]);
    issuer.engine.send(&opener.id(), "t2", "welcome").unwrap();
    let reply = issuer.outgoing(now + 120);
    assert_eq!(opener.absorb(now + 120, &reply)["accepted"], 1);
    assert_eq!(opener.drain(), vec!["welcome"]);
    let after = opener.engine.mailbox_addresses(now + 120, 2).unwrap();
    assert!(
        before.iter().any(|a| !after.contains(a)),
        "the introduction is no longer read once the friend answered under the link"
    );

    // Nothing stays: the issuer deletes what it took (except the offer, whose
    // key only the opener has), the opener withdraws its offers.
    let mut published = pubkeys(
        &[
            offers.clone(),
            again.clone(),
            acceptance.clone(),
            data,
            reply,
        ]
        .concat(),
    );
    opener.engine.housekeeping().unwrap();
    let mut named = 0;
    for node in [&mut issuer, &mut opener] {
        for (_, role, deletion) in deletions(node, now + 180) {
            for id in deletion.tags.event_ids() {
                let author = published
                    .remove(id)
                    .expect("a deletion names an event of this introduction");
                assert_eq!(
                    author, deletion.pubkey,
                    "{role}: signed by whoever signed the event"
                );
                named += 1;
            }
        }
    }
    assert_eq!(named, 5, "both offers, the acceptance, both data events");
    assert!(
        published.is_empty(),
        "every event is deleted: {published:?}"
    );
}

#[test]
fn a_link_is_taken_once_can_be_revoked_and_ends() {
    let mut issuer = Node::new();
    let now = real_now();
    let (code, _) = issuer.engine.link_code().unwrap();
    let (mut first, mut second) = (Node::new(), Node::new());
    first.engine.import_contact(&code).unwrap();
    second.engine.import_contact(&code).unwrap();
    let both = [first.outgoing(now), second.outgoing(now)].concat();
    let result = issuer.absorb(now, &both);
    assert_eq!(
        (result["accepted"].as_i64(), result["refused"].as_i64()),
        (Some(1), Some(1))
    );
    let invited: Vec<_> = issuer
        .engine
        .peers()
        .unwrap()
        .into_iter()
        .filter(|p| p.state == "invited")
        .collect();
    assert_eq!(invited.len(), 1, "a link is used up by the first offer");
    assert!(issuer.engine.links().unwrap().is_empty());

    // Revoked: an offer through it is no longer taken.
    let (code, _) = issuer.engine.link_code().unwrap();
    let links = issuer.engine.links().unwrap();
    assert_eq!(links.len(), 1);
    assert_eq!(links[0].code, code, "the open link can be shared again");
    assert!(issuer.engine.revoke_link(&links[0].id).unwrap());
    let mut third = Node::new();
    third.engine.import_contact(&code).unwrap();
    assert_eq!(issuer.absorb(now, &third.outgoing(now))["refused"], 1);
    assert!(issuer.engine.links().unwrap().is_empty());

    // Ended: the opener learns it at once.
    let mut ended = code.clone();
    let past = u32::try_from(now - 60).unwrap().to_be_bytes();
    ended[68..72].copy_from_slice(&past);
    let mut late = Node::new();
    assert_eq!(
        late.engine.import_contact(&ended).unwrap_err().0,
        "link_expired"
    );

    // At most eight open links.
    for _ in 0..8 {
        issuer.engine.link_code().unwrap();
    }
    assert_eq!(issuer.engine.link_code().unwrap_err().0, "link_quota");
}

#[test]
fn a_scanned_qr_code_also_reaches_its_device_through_mailboxes() {
    let (mut shower, mut scanner) = (Node::new(), Node::new());
    let now = real_now();
    let code = shower.engine.contact_code().unwrap();
    scanner.engine.import_contact(&code).unwrap();
    let offers = scanner.outgoing(now);
    assert_eq!(offers.len(), 1);
    let exp: u64 = tags(&Event::from_json(&offers[0]).unwrap())
        .into_iter()
        .find(|t| t[0] == "expiration")
        .unwrap()[1]
        .parse()
        .unwrap();
    assert!(
        exp <= now + HOUR + mailbox::CLOCK_GRACE_S + 1,
        "it ends with the code"
    );
    assert_eq!(shower.absorb(now, &offers)["accepted"], 1);
    assert_eq!(
        shower.engine.peer(&scanner.id()).unwrap().unwrap().state,
        "invited"
    );
    // A stranger who did not show the code takes nothing.
    let mut stranger = Node::new();
    assert_eq!(stranger.absorb(now, &offers)["refused"], 1);
}
