use cgka_traits::StorageProvider;
use storage_sqlite::SqliteAccountStorage;

#[test]
fn journal_capacity_failure_rolls_back_the_whole_host_transaction() {
    let db = SqliteAccountStorage::in_memory().unwrap();
    db.cruxcoach_init().unwrap();
    db.cruxcoach_put("existing", b"synthetic durable value")
        .unwrap();
    let result: Result<(), cgka_traits::storage::StorageError> = db.with_transaction(|_| {
        db.cruxcoach_put("existing", b"must roll back")?;
        db.cruxcoach_put("oversized", &vec![0; 1_048_577])?;
        Ok(())
    });
    assert!(result.is_err());
    assert_eq!(
        db.cruxcoach_get("existing").unwrap().unwrap(),
        b"synthetic durable value"
    );
    assert!(db.cruxcoach_get("oversized").unwrap().is_none());
    assert!(db.cruxcoach_put(&"x".repeat(257), b"bounded").is_err());
}

#[test]
fn journal_row_quota_can_be_reused_without_overwriting_other_records() {
    let db = SqliteAccountStorage::in_memory().unwrap();
    db.cruxcoach_init().unwrap();
    db.with_transaction::<_, cgka_traits::storage::StorageError, _>(|_| {
        for n in 0..4096 {
            db.cruxcoach_put(&format!("synthetic/{n}"), b"retained")?;
        }
        Ok(())
    })
    .unwrap();
    assert!(db.cruxcoach_put("overflow", b"refused").is_err());
    db.cruxcoach_put("synthetic/0", b"update existing").unwrap();
    db.cruxcoach_delete("synthetic/1").unwrap();
    db.cruxcoach_put("new", b"reusable slot").unwrap();
    assert_eq!(db.cruxcoach_keys("").unwrap().len(), 4096);
    assert_eq!(
        db.cruxcoach_get("synthetic/0").unwrap().unwrap(),
        b"update existing"
    );
}
