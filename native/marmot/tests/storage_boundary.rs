use cruxcoach_marmot::error::Error;
use cruxcoach_marmot::store::{MAX_INBOX, Origin, Store};
use nostr::{EventBuilder, Filter, Keys, Kind, Tag};
use nostr_database::{NostrDatabase, SaveEventStatus};
use storage_sqlite::SqliteAccountStorage;

fn store(account: &Keys) -> Store {
    Store::open(
        SqliteAccountStorage::in_memory().unwrap(),
        &account.public_key().to_hex(),
    )
    .unwrap()
}

#[test]
fn a_failed_host_transaction_rolls_back_every_row() {
    let keys = Keys::generate();
    let store = store(&keys);
    let event = EventBuilder::new(Kind::Custom(445), "synthetic ciphertext")
        .tags([Tag::parse(["h", "route"]).unwrap()])
        .sign_with_keys(&keys)
        .unwrap();
    let result: Result<(), Error> = store.transaction(|| {
        store.insert_event(&event, Origin::Own, Some("peer"), false, u64::MAX)?;
        store.add_deliveries(&event.id.to_hex(), &["ws://127.0.0.1:1".into()])?;
        store.put_token("peer", "token", &event.id.to_hex(), "hash")?;
        Err(Error("synthetic_failure"))
    });
    assert!(result.is_err());
    assert!(!store.exists(&event.id.to_hex()).unwrap());
    assert!(store.token("peer", "token").unwrap().is_none());
    assert_eq!(store.outbound_pending().unwrap(), 0);
}

#[test]
fn replaceable_events_keep_only_the_newest() {
    let keys = Keys::generate();
    let store = store(&keys);
    let old = EventBuilder::new(Kind::Custom(10002), "")
        .custom_created_at(nostr::Timestamp::from(100))
        .sign_with_keys(&keys)
        .unwrap();
    let new = EventBuilder::new(Kind::Custom(10002), "")
        .custom_created_at(nostr::Timestamp::from(200))
        .sign_with_keys(&keys)
        .unwrap();
    assert!(
        store
            .insert_event(&new, Origin::Own, None, false, u64::MAX)
            .unwrap()
            .is_some()
    );
    assert!(
        store
            .insert_event(&old, Origin::Own, None, false, u64::MAX)
            .unwrap()
            .is_none()
    );
    let found = store
        .query_events(
            &Filter::new()
                .author(keys.public_key())
                .kind(Kind::Custom(10002)),
        )
        .unwrap();
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].id, new.id);
}

#[tokio::test]
async fn admission_refuses_unrequested_kinds_routes_and_recipients() {
    let keys = Keys::generate();
    let store = store(&keys);
    let stranger = Keys::generate();
    let route = "b".repeat(64);
    let group = |h: &str| {
        EventBuilder::new(Kind::Custom(445), "ciphertext")
            .tags([Tag::parse(["h", h]).unwrap()])
            .sign_with_keys(&stranger)
            .unwrap()
    };
    let chat = EventBuilder::text_note("public note")
        .sign_with_keys(&stranger)
        .unwrap();
    let wrap_other = EventBuilder::new(Kind::GiftWrap, "x")
        .tags([Tag::public_key(Keys::generate().public_key())])
        .sign_with_keys(&stranger)
        .unwrap();
    let wrap_self = EventBuilder::new(Kind::GiftWrap, "x")
        .tags([Tag::public_key(keys.public_key())])
        .sign_with_keys(&stranger)
        .unwrap();
    let directory = EventBuilder::new(Kind::Custom(10050), "")
        .sign_with_keys(&stranger)
        .unwrap();
    for event in [&group(&route), &chat, &wrap_other, &directory] {
        assert!(!store.save_event(event).await.unwrap().is_success());
    }
    assert_eq!(
        store.save_event(&wrap_self).await.unwrap(),
        SaveEventStatus::Success
    );
    store.interest.write().unwrap().routes.insert(route.clone());
    store
        .interest
        .write()
        .unwrap()
        .authors
        .insert(stranger.public_key().to_hex());
    assert!(store.save_event(&group(&route)).await.unwrap().is_success());
    assert!(store.save_event(&directory).await.unwrap().is_success());
    assert!(!store.save_event(&group(&route)).await.unwrap().is_success());
    // The relay-facing query path returns exact tag matches only.
    let hits = store
        .query(
            Filter::new()
                .kind(Kind::Custom(445))
                .custom_tag(nostr::SingleLetterTag::lowercase(nostr::Alphabet::H), route),
        )
        .await
        .unwrap();
    assert_eq!(hits.len(), 1);
    assert!(store.wipe().await.is_err());
}

#[test]
fn inbox_is_bounded_idempotent_and_acknowledged_by_sequence() {
    let keys = Keys::generate();
    let store = store(&keys);
    assert!(store.inbox_insert("source-1", "peer", "one").unwrap());
    assert!(!store.inbox_insert("source-1", "peer", "one again").unwrap());
    assert!(
        !store
            .inbox_insert("source-2", "peer", &"x".repeat(65_537))
            .unwrap()
    );
    store
        .sql(|c| {
            for n in 0..(MAX_INBOX - 1) {
                c.execute(
                    "INSERT INTO cc2_inbox(source,peer,content,received_at) VALUES(?1,'peer','x',0)",
                    [format!("filler-{n}")],
                )?;
            }
            Ok(())
        })
        .unwrap();
    assert_eq!(
        store.inbox_insert("overflow", "peer", "x").unwrap_err().0,
        "native_inbox_quota"
    );
    store.inbox_purge("peer").unwrap();
    assert!(store.inbox_insert("after-purge", "peer", "x").unwrap());
}
