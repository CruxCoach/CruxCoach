//! The local relay's event store and the host's small bookkeeping tables, on
//! the MDK SQLCipher connection and transaction rail. An outbound MLS event is
//! written in the same transaction that advances the ratchet.
use crate::error::{Error, Result};
use crate::protocol::{MAX_APP_CONTENT, now_ms, now_s};
use cgka_traits::storage::StorageProvider;
use nostr::filter::MatchEventOptions;
use nostr::{Event, EventId, Filter, JsonUtil, Kind, Timestamp};
use nostr_database::{
    Backend, DatabaseError, DatabaseEventStatus, Events, NostrDatabase, RejectedReason,
    SaveEventStatus,
};
use rusqlite::{OptionalExtension, params, types::Value};
use serde::Serialize;
use std::collections::BTreeSet;
use std::sync::{Arc, RwLock};
use storage_sqlite::SqliteAccountStorage;

const SCHEMA_VERSION: &str = "2";
pub const MAX_EVENT_BYTES: usize = 131_072;
pub const MAX_EVENTS: i64 = 20_000;
pub const MAX_EVENT_STORE_BYTES: i64 = 64 * 1024 * 1024;
pub const MAX_INBOX: i64 = 4_096;
const MAX_QUERY_ROWS: usize = 5_000;
const REMOTE_RETENTION_MS: u64 = 10 * 86_400_000;
const DIRECTORY_RETENTION_MS: u64 = 2 * 86_400_000;
pub const OWN_RETENTION_MS: u64 = 14 * 86_400_000;

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS cc2_meta(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS cc2_event(
  seq INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,
  pubkey TEXT NOT NULL,
  kind INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  d TEXT NOT NULL DEFAULT '',
  raw TEXT NOT NULL,
  own INTEGER NOT NULL,
  peer TEXT,
  core INTEGER NOT NULL DEFAULT 0,
  expires_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS cc2_event_kind ON cc2_event(kind, created_at);
CREATE INDEX IF NOT EXISTS cc2_event_author ON cc2_event(pubkey, kind, d);
CREATE TABLE IF NOT EXISTS cc2_event_tag(seq INTEGER NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS cc2_event_tag_value ON cc2_event_tag(name, value, seq);
CREATE INDEX IF NOT EXISTS cc2_event_tag_seq ON cc2_event_tag(seq);
CREATE TABLE IF NOT EXISTS cc2_delivery(
  event_id TEXT NOT NULL, relay TEXT NOT NULL, position INTEGER NOT NULL,
  status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0,
  confirmed INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(event_id, relay));
CREATE TABLE IF NOT EXISTS cc2_peer(
  account TEXT PRIMARY KEY NOT NULL, grp TEXT NOT NULL, state TEXT NOT NULL,
  inbox TEXT NOT NULL, inviter INTEGER NOT NULL, created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL DEFAULT 0, cancelled_at INTEGER NOT NULL DEFAULT 0,
  ended_at INTEGER NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS cc2_inbox(
  seq INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL UNIQUE, peer TEXT NOT NULL,
  content TEXT NOT NULL, received_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS cc2_token(
  peer TEXT NOT NULL, token TEXT NOT NULL, event_id TEXT NOT NULL, content_hash TEXT NOT NULL,
  created_at INTEGER NOT NULL, PRIMARY KEY(peer, token));
CREATE TABLE IF NOT EXISTS cc2_relay(
  url TEXT PRIMARY KEY NOT NULL, state TEXT NOT NULL, nip77 INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL);
";

/// What the host currently wants from public relays. Admission refuses the
/// rest before it can occupy quota or reach MDK ingest.
#[derive(Default, Debug)]
pub struct Interest {
    pub routes: BTreeSet<String>,
    pub authors: BTreeSet<String>,
}

#[derive(Clone)]
pub struct Store {
    db: SqliteAccountStorage,
    account: String,
    pub interest: Arc<RwLock<Interest>>,
    pub remote: Arc<tokio::sync::Notify>,
}

impl std::fmt::Debug for Store {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Store(redacted)")
    }
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct Peer {
    pub account: String,
    pub group: String,
    pub state: String,
    #[serde(skip)]
    pub inbox: Vec<String>,
    pub invited_by_me: bool,
    pub created_at: u64,
    pub last_seen: u64,
    pub cancelled_at: u64,
    pub ended_at: u64,
    pub reason: String,
}

#[derive(Clone, Debug, Serialize)]
pub struct InboxItem {
    pub seq: i64,
    pub peer: String,
    pub content: String,
    pub received_at: u64,
}

#[derive(Clone, Debug, Serialize)]
pub struct Delivery {
    pub relay: String,
    pub position: usize,
    pub status: String,
    pub attempts: u32,
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Origin {
    Own,
    Remote,
}

fn i64_of(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

fn tag_rows(event: &Event) -> Vec<(String, String)> {
    event
        .tags
        .iter()
        .filter_map(|tag| {
            let letter = tag.single_letter_tag()?;
            let value = tag.content()?;
            (value.len() <= 256).then(|| (letter.as_char().to_string(), value.to_string()))
        })
        .take(64)
        .collect()
}

fn identifier(event: &Event) -> String {
    if event.kind.is_addressable() {
        event.tags.identifier().unwrap_or("").to_string()
    } else {
        String::new()
    }
}

impl Store {
    pub fn open(db: SqliteAccountStorage, account: &str) -> Result<Self> {
        let store = Self {
            db,
            account: account.to_string(),
            interest: Default::default(),
            remote: Default::default(),
        };
        store.sql(|c| c.execute_batch(SCHEMA))?;
        match store.meta("schema")?.as_deref() {
            None => store.put_meta("schema", SCHEMA_VERSION)?,
            Some(SCHEMA_VERSION) => {}
            Some(_) => return Err(Error("host_schema_version")),
        }
        match store.meta("account")? {
            None => store.put_meta("account", account)?,
            Some(existing) if existing == account => {}
            Some(_) => return Err(Error("account_mismatch")),
        }
        Ok(store)
    }

    pub fn account(&self) -> &str {
        &self.account
    }

    pub fn raw(&self) -> &SqliteAccountStorage {
        &self.db
    }

    pub fn sql<R>(
        &self,
        f: impl FnOnce(&rusqlite::Connection) -> rusqlite::Result<R>,
    ) -> Result<R> {
        self.db.cruxcoach_sql(f).map_err(Error::from)
    }

    /// One SQLCipher transaction for engine state and host rows. Nested calls
    /// on the same thread join the outer transaction.
    pub fn transaction<R>(&self, f: impl FnOnce() -> Result<R>) -> Result<R> {
        self.db.with_transaction(|_| f())
    }

    pub fn meta(&self, key: &str) -> Result<Option<String>> {
        self.sql(|c| {
            c.query_row("SELECT value FROM cc2_meta WHERE key=?1", [key], |r| {
                r.get(0)
            })
            .optional()
        })
    }

    pub fn put_meta(&self, key: &str, value: &str) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "INSERT INTO cc2_meta(key,value) VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                params![key, value],
            )
        })?;
        Ok(())
    }

    // ---- events -----------------------------------------------------------

    /// Insert a verified event. Replaceable/addressable kinds keep only the
    /// newest per author (and `d`). Returns the arrival sequence when stored.
    pub fn insert_event(
        &self,
        event: &Event,
        origin: Origin,
        peer: Option<&str>,
        core: bool,
        expires_at: u64,
    ) -> Result<Option<i64>> {
        let raw = event.as_json();
        if raw.len() > MAX_EVENT_BYTES {
            return Err(Error("event_size"));
        }
        let d = identifier(event);
        let replaceable = event.kind.is_replaceable() || event.kind.is_addressable();
        self.transaction(|| {
            if replaceable {
                let newer: Option<String> = self.sql(|c| {
                    c.query_row(
                        "SELECT id FROM cc2_event WHERE pubkey=?1 AND kind=?2 AND d=?3
                         AND (created_at>?4 OR (created_at=?4 AND id<=?5)) LIMIT 1",
                        params![
                            event.pubkey.to_hex(),
                            event.kind.as_u16(),
                            d,
                            i64_of(event.created_at.as_secs()),
                            event.id.to_hex()
                        ],
                        |r| r.get(0),
                    )
                    .optional()
                })?;
                if newer.is_some() {
                    return Ok(None);
                }
                let old: Vec<String> = self.sql(|c| {
                    let mut q = c.prepare(
                        "SELECT id FROM cc2_event WHERE pubkey=?1 AND kind=?2 AND d=?3",
                    )?;
                    q.query_map(
                        params![event.pubkey.to_hex(), event.kind.as_u16(), d],
                        |r| r.get(0),
                    )?
                    .collect()
                })?;
                for id in old {
                    self.delete_event(&id)?;
                }
            }
            let inserted = self.sql(|c| {
                c.execute(
                    "INSERT OR IGNORE INTO cc2_event(id,pubkey,kind,created_at,d,raw,own,peer,core,expires_at)
                     VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)",
                    params![
                        event.id.to_hex(),
                        event.pubkey.to_hex(),
                        event.kind.as_u16(),
                        i64_of(event.created_at.as_secs()),
                        d,
                        raw,
                        origin == Origin::Own,
                        peer,
                        core,
                        i64_of(expires_at)
                    ],
                )
            })?;
            if inserted == 0 {
                return Ok(None);
            }
            let seq = self.sql(|c| Ok(c.last_insert_rowid()))?;
            for (name, value) in tag_rows(event) {
                self.sql(|c| {
                    c.execute(
                        "INSERT INTO cc2_event_tag(seq,name,value) VALUES(?1,?2,?3)",
                        params![seq, name, value],
                    )
                })?;
            }
            Ok(Some(seq))
        })
    }

    pub fn delete_event(&self, id: &str) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "DELETE FROM cc2_event_tag WHERE seq IN (SELECT seq FROM cc2_event WHERE id=?1)",
                [id],
            )?;
            c.execute("DELETE FROM cc2_delivery WHERE event_id=?1", [id])?;
            c.execute("DELETE FROM cc2_event WHERE id=?1", [id])
        })?;
        Ok(())
    }

    pub fn event(&self, id: &str) -> Result<Option<Event>> {
        let raw: Option<String> = self.sql(|c| {
            c.query_row("SELECT raw FROM cc2_event WHERE id=?1", [id], |r| r.get(0))
                .optional()
        })?;
        Ok(raw.and_then(|raw| Event::from_json(raw).ok()))
    }

    /// Remote group messages and gift wraps after the ingest cursor.
    pub fn remote_after(&self, seq: i64, limit: usize) -> Result<Vec<(i64, Event)>> {
        let rows: Vec<(i64, String)> = self.sql(|c| {
            let mut q = c.prepare(
                "SELECT seq, raw FROM cc2_event WHERE seq>?1 AND own=0 AND kind IN (445,1059)
                 ORDER BY seq LIMIT ?2",
            )?;
            q.query_map(params![seq, limit as i64], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect()
        })?;
        Ok(rows
            .into_iter()
            .filter_map(|(seq, raw)| Event::from_json(raw).ok().map(|e| (seq, e)))
            .collect())
    }

    pub fn max_seq(&self) -> Result<i64> {
        self.sql(|c| {
            c.query_row("SELECT coalesce(max(seq),0) FROM cc2_event", [], |r| {
                r.get(0)
            })
        })
    }

    fn select(&self, filter: &Filter, cap: usize) -> Result<Vec<Event>> {
        if filter.search.is_some() {
            return Ok(Vec::new());
        }
        let mut sql = String::from("SELECT raw FROM cc2_event e WHERE 1=1");
        let mut values: Vec<Value> = Vec::new();
        fn list(sql: &mut String, values: &mut Vec<Value>, column: &str, items: Vec<Value>) {
            sql.push_str(&format!(" AND {column} IN ("));
            for (index, item) in items.into_iter().enumerate() {
                if index > 0 {
                    sql.push(',');
                }
                sql.push('?');
                values.push(item);
            }
            sql.push(')');
        }
        if let Some(ids) = &filter.ids {
            let items = ids
                .iter()
                .take(512)
                .map(|i| Value::Text(i.to_hex()))
                .collect();
            list(&mut sql, &mut values, "e.id", items);
        }
        if let Some(authors) = &filter.authors {
            let items = authors
                .iter()
                .take(512)
                .map(|a| Value::Text(a.to_hex()))
                .collect();
            list(&mut sql, &mut values, "e.pubkey", items);
        }
        if let Some(kinds) = &filter.kinds {
            let items = kinds
                .iter()
                .take(64)
                .map(|k| Value::Integer(k.as_u16() as i64))
                .collect();
            list(&mut sql, &mut values, "e.kind", items);
        }
        for (letter, wanted) in filter.generic_tags.iter() {
            sql.push_str(" AND EXISTS (SELECT 1 FROM cc2_event_tag t WHERE t.seq=e.seq AND t.name=? AND t.value IN (");
            values.push(Value::Text(letter.as_char().to_string()));
            for (index, value) in wanted.iter().take(512).enumerate() {
                if index > 0 {
                    sql.push(',');
                }
                sql.push('?');
                values.push(Value::Text(value.clone()));
            }
            sql.push_str("))");
        }
        if let Some(since) = filter.since {
            sql.push_str(" AND e.created_at>=?");
            values.push(Value::Integer(i64_of(since.as_secs())));
        }
        if let Some(until) = filter.until {
            sql.push_str(" AND e.created_at<=?");
            values.push(Value::Integer(i64_of(until.as_secs())));
        }
        let limit = filter.limit.unwrap_or(cap).min(cap);
        sql.push_str(" ORDER BY e.created_at DESC, e.id ASC LIMIT ?");
        values.push(Value::Integer(limit as i64));
        let rows: Vec<String> = self.sql(|c| {
            let mut q = c.prepare(&sql)?;
            q.query_map(rusqlite::params_from_iter(values.iter()), |r| r.get(0))?
                .collect()
        })?;
        Ok(rows
            .into_iter()
            .filter_map(|raw| Event::from_json(raw).ok())
            .filter(|event| filter.match_event(event, MatchEventOptions::default()))
            .collect())
    }

    pub fn query_events(&self, filter: &Filter) -> Result<Vec<Event>> {
        self.select(filter, MAX_QUERY_ROWS)
    }

    /// Local admission for events offered by public relays or the pool.
    pub fn admissible(&self, event: &Event) -> bool {
        if event.as_json().len() > MAX_EVENT_BYTES {
            return false;
        }
        let author = event.pubkey.to_hex();
        let own = author == self.account;
        let interest = match self.interest.read() {
            Ok(interest) => interest,
            Err(_) => return false,
        };
        let tag = |name: char| {
            event.tags.iter().find_map(|t| {
                (t.single_letter_tag()?.as_char() == name)
                    .then(|| t.content().map(str::to_string))?
            })
        };
        match event.kind.as_u16() {
            445 => tag('h').is_some_and(|h| interest.routes.contains(&h)),
            1059 => tag('p').is_some_and(|p| p == self.account),
            10002 | 10050 | 30443 => own || interest.authors.contains(&author),
            _ => false,
        }
    }

    fn remote_expiry(event: &Event) -> u64 {
        match event.kind.as_u16() {
            445 | 1059 => now_ms() + REMOTE_RETENTION_MS,
            _ => now_ms() + DIRECTORY_RETENTION_MS,
        }
    }

    pub fn save_remote(&self, event: &Event) -> Result<SaveEventStatus> {
        if !self.admissible(event) {
            return Ok(SaveEventStatus::Rejected(RejectedReason::Other));
        }
        if self.exists(&event.id.to_hex())? {
            return Ok(SaveEventStatus::Rejected(RejectedReason::Duplicate));
        }
        self.enforce_capacity()?;
        let origin = if event.pubkey.to_hex() == self.account {
            Origin::Own
        } else {
            Origin::Remote
        };
        match self.insert_event(event, origin, None, false, Self::remote_expiry(event))? {
            Some(_) => {
                if matches!(event.kind.as_u16(), 445 | 1059) {
                    self.remote.notify_one();
                }
                Ok(SaveEventStatus::Success)
            }
            None => Ok(SaveEventStatus::Rejected(RejectedReason::Replaced)),
        }
    }

    pub fn exists(&self, id: &str) -> Result<bool> {
        self.sql(|c| {
            c.query_row("SELECT 1 FROM cc2_event WHERE id=?1", [id], |_| Ok(()))
                .optional()
                .map(|v| v.is_some())
        })
    }

    fn enforce_capacity(&self) -> Result<()> {
        let (count, bytes): (i64, i64) = self.sql(|c| {
            c.query_row(
                "SELECT count(*), coalesce(sum(length(raw)),0) FROM cc2_event",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
        })?;
        if count < MAX_EVENTS && bytes < MAX_EVENT_STORE_BYTES {
            return Ok(());
        }
        // Oldest remote payloads go first; own undelivered events never do.
        let victims: Vec<String> = self.sql(|c| {
            let mut q = c.prepare("SELECT id FROM cc2_event WHERE own=0 ORDER BY seq LIMIT 256")?;
            q.query_map([], |r| r.get(0))?.collect()
        })?;
        if victims.is_empty() {
            return Err(Error("native_storage_quota"));
        }
        for id in victims {
            self.delete_event(&id)?;
        }
        Ok(())
    }

    /// Expire retained payloads. Own events with outstanding deliveries are
    /// kept until their own expiry; nothing else depends on them afterwards.
    pub fn prune(&self) -> Result<usize> {
        let now = i64_of(now_ms());
        let expired: Vec<String> = self.sql(|c| {
            let mut q = c.prepare("SELECT id FROM cc2_event WHERE expires_at<=?1 LIMIT 512")?;
            q.query_map([now], |r| r.get(0))?.collect()
        })?;
        for id in &expired {
            self.delete_event(id)?;
        }
        self.sql(|c| {
            c.execute(
                "DELETE FROM cc2_token WHERE created_at<=?1",
                [now - i64_of(OWN_RETENTION_MS)],
            )
        })?;
        Ok(expired.len())
    }

    // ---- deliveries --------------------------------------------------------

    pub fn add_deliveries(&self, event_id: &str, relays: &[String]) -> Result<()> {
        for (position, relay) in relays.iter().enumerate() {
            self.sql(|c| {
                c.execute(
                    "INSERT OR IGNORE INTO cc2_delivery(event_id,relay,position,status) VALUES(?1,?2,?3,'pending')",
                    params![event_id, relay, position as i64],
                )
            })?;
        }
        Ok(())
    }

    /// Own events with at least one outstanding relay whose attempt is due.
    pub fn due(&self, relays_online: &[String], limit: usize) -> Result<Vec<(Event, Vec<String>)>> {
        let now = i64_of(now_ms());
        let rows: Vec<(String, String)> = self.sql(|c| {
            let mut q = c.prepare(
                "SELECT d.event_id, d.relay FROM cc2_delivery d JOIN cc2_event e ON e.id=d.event_id
                 WHERE d.status!='accepted' AND d.next_attempt<=?1 ORDER BY e.seq, d.position LIMIT 512",
            )?;
            q.query_map([now], |r| Ok((r.get(0)?, r.get(1)?)))?.collect()
        })?;
        let mut grouped: Vec<(String, Vec<String>)> = Vec::new();
        for (event, relay) in rows {
            if !relays_online.contains(&relay) {
                continue;
            }
            match grouped.iter().position(|(id, _)| *id == event) {
                Some(index) => grouped[index].1.push(relay),
                None if grouped.len() < limit => grouped.push((event, vec![relay])),
                None => {}
            }
        }
        let mut result = Vec::new();
        for (id, relays) in grouped {
            if let Some(event) = self.event(&id)? {
                result.push((event, relays));
            }
        }
        Ok(result)
    }

    pub fn record_delivery(&self, event_id: &str, relay: &str, status: &str) -> Result<()> {
        let now = i64_of(now_ms());
        self.sql(|c| {
            let attempts: i64 = c
                .query_row(
                    "SELECT attempts FROM cc2_delivery WHERE event_id=?1 AND relay=?2",
                    params![event_id, relay],
                    |r| r.get(0),
                )
                .optional()?
                .unwrap_or(0)
                + 1;
            let delay = 1_000i64.saturating_mul(1i64 << attempts.min(11)).min(1_800_000);
            c.execute(
                "UPDATE cc2_delivery SET status=?3, attempts=?4, next_attempt=?5 WHERE event_id=?1 AND relay=?2",
                params![event_id, relay, status, attempts, now + delay],
            )
        })?;
        Ok(())
    }

    /// Connectivity returned: retry every unavailable target now.
    pub fn wake_deliveries(&self) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "UPDATE cc2_delivery SET next_attempt=0 WHERE status IN ('pending','unavailable')",
                [],
            )
        })?;
        Ok(())
    }

    pub fn deliveries(&self, event_id: &str) -> Result<Vec<Delivery>> {
        self.sql(|c| {
            let mut q = c.prepare(
                "SELECT relay, position, status, attempts FROM cc2_delivery WHERE event_id=?1 ORDER BY position",
            )?;
            q.query_map([event_id], |r| {
                Ok(Delivery {
                    relay: r.get(0)?,
                    position: r.get::<_, i64>(1)? as usize,
                    status: r.get(2)?,
                    attempts: r.get::<_, i64>(3)? as u32,
                })
            })?
            .collect()
        })
    }

    /// Accepted core fanout deliveries MDK has not been told about yet.
    pub fn unconfirmed_core(&self) -> Result<Vec<(String, String, usize)>> {
        self.sql(|c| {
            let mut q = c.prepare(
                "SELECT d.event_id, d.relay, d.position FROM cc2_delivery d JOIN cc2_event e ON e.id=d.event_id
                 WHERE e.core=1 AND d.status='accepted' AND d.confirmed=0 LIMIT 64",
            )?;
            q.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? as usize)))?
                .collect()
        })
    }

    pub fn mark_confirmed(&self, event_id: &str, relay: &str) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "UPDATE cc2_delivery SET confirmed=1 WHERE event_id=?1 AND relay=?2",
                params![event_id, relay],
            )
        })?;
        Ok(())
    }

    /// Own events no relay has accepted yet. One accepting relay makes an
    /// event reachable; the replicator keeps copying it to the others, but an
    /// unreachable relay must not make every pass wait for it.
    pub fn outbound_pending(&self) -> Result<i64> {
        self.sql(|c| {
            c.query_row(
                "SELECT count(*) FROM cc2_event e WHERE e.own=1
                 AND EXISTS (SELECT 1 FROM cc2_delivery d WHERE d.event_id=e.id)
                 AND NOT EXISTS (SELECT 1 FROM cc2_delivery d WHERE d.event_id=e.id AND d.status='accepted')",
                [],
                |r| r.get(0),
            )
        })
    }

    /// Remove this peer's application messages no public relay accepted yet.
    pub fn cancel_unreplicated(&self, peer: &str) -> Result<usize> {
        let ids: Vec<String> = self.sql(|c| {
            let mut q = c.prepare(
                "SELECT e.id FROM cc2_event e WHERE e.own=1 AND e.core=0 AND e.peer=?1
                 AND NOT EXISTS (SELECT 1 FROM cc2_delivery d WHERE d.event_id=e.id AND d.status='accepted')",
            )?;
            q.query_map([peer], |r| r.get(0))?.collect()
        })?;
        for id in &ids {
            self.delete_event(id)?;
        }
        Ok(ids.len())
    }

    pub fn pending_for_peer(&self, peer: &str) -> Result<i64> {
        self.sql(|c| {
            c.query_row(
                "SELECT count(*) FROM cc2_event e WHERE e.own=1 AND e.peer=?1
                 AND EXISTS (SELECT 1 FROM cc2_delivery d WHERE d.event_id=e.id)
                 AND NOT EXISTS (SELECT 1 FROM cc2_delivery d WHERE d.event_id=e.id AND d.status='accepted')",
                [peer],
                |r| r.get(0),
            )
        })
    }

    // ---- relays ---------------------------------------------------------------

    pub fn set_relay_state(&self, url: &str, state: &str) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "INSERT INTO cc2_relay(url,state,updated_at) VALUES(?1,?2,?3)
                 ON CONFLICT(url) DO UPDATE SET state=excluded.state, updated_at=excluded.updated_at",
                params![url, state, i64_of(now_ms())],
            )
        })?;
        Ok(())
    }

    pub fn set_relay_nip77(&self, url: &str, supported: bool) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "INSERT INTO cc2_relay(url,state,nip77,updated_at) VALUES(?1,'unknown',?2,?3)
                 ON CONFLICT(url) DO UPDATE SET nip77=excluded.nip77",
                params![url, supported, i64_of(now_ms())],
            )
        })?;
        Ok(())
    }

    pub fn relay_rows(&self) -> Result<Vec<(String, String, bool, u64)>> {
        self.sql(|c| {
            let mut q =
                c.prepare("SELECT url, state, nip77, updated_at FROM cc2_relay ORDER BY url")?;
            q.query_map([], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? as u64))
            })?
            .collect()
        })
    }

    // ---- peers ------------------------------------------------------------------

    pub fn peers(&self) -> Result<Vec<Peer>> {
        self.sql(|c| {
            let mut q = c.prepare(
                "SELECT account, grp, state, inbox, inviter, created_at, last_seen, cancelled_at, ended_at, reason
                 FROM cc2_peer ORDER BY account",
            )?;
            q.query_map([], |r| {
                let inbox: String = r.get(3)?;
                Ok(Peer {
                    account: r.get(0)?,
                    group: r.get(1)?,
                    state: r.get(2)?,
                    inbox: serde_json::from_str(&inbox).unwrap_or_default(),
                    invited_by_me: r.get(4)?,
                    created_at: r.get::<_, i64>(5)? as u64,
                    last_seen: r.get::<_, i64>(6)? as u64,
                    cancelled_at: r.get::<_, i64>(7)? as u64,
                    ended_at: r.get::<_, i64>(8)? as u64,
                    reason: r.get(9)?,
                })
            })?
            .collect()
        })
    }

    pub fn peer(&self, account: &str) -> Result<Option<Peer>> {
        Ok(self.peers()?.into_iter().find(|p| p.account == account))
    }

    pub fn put_peer(&self, peer: &Peer) -> Result<()> {
        let inbox = serde_json::to_string(&peer.inbox).map_err(|_| Error("encode"))?;
        self.sql(|c| {
            c.execute(
                "INSERT INTO cc2_peer(account,grp,state,inbox,inviter,created_at,last_seen,cancelled_at,ended_at,reason)
                 VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)
                 ON CONFLICT(account) DO UPDATE SET grp=excluded.grp, state=excluded.state, inbox=excluded.inbox,
                 inviter=excluded.inviter, created_at=excluded.created_at, last_seen=excluded.last_seen,
                 cancelled_at=excluded.cancelled_at, ended_at=excluded.ended_at, reason=excluded.reason",
                params![
                    peer.account,
                    peer.group,
                    peer.state,
                    inbox,
                    peer.invited_by_me,
                    i64_of(peer.created_at),
                    i64_of(peer.last_seen),
                    i64_of(peer.cancelled_at),
                    i64_of(peer.ended_at),
                    peer.reason
                ],
            )
        })?;
        Ok(())
    }

    pub fn delete_peer(&self, account: &str) -> Result<()> {
        self.sql(|c| c.execute("DELETE FROM cc2_peer WHERE account=?1", [account]))?;
        Ok(())
    }

    // ---- inbox --------------------------------------------------------------------

    pub fn inbox_insert(&self, source: &str, peer: &str, content: &str) -> Result<bool> {
        if content.len() > MAX_APP_CONTENT {
            return Ok(false);
        }
        let count: i64 =
            self.sql(|c| c.query_row("SELECT count(*) FROM cc2_inbox", [], |r| r.get(0)))?;
        if count >= MAX_INBOX {
            return Err(Error("native_inbox_quota"));
        }
        let inserted = self.sql(|c| {
            c.execute(
                "INSERT OR IGNORE INTO cc2_inbox(source,peer,content,received_at) VALUES(?1,?2,?3,?4)",
                params![source, peer, content, i64_of(now_ms())],
            )
        })?;
        Ok(inserted > 0)
    }

    /// Only active peers' messages leave the native layer. Invitations are
    /// held until acceptance; ended peers were purged.
    pub fn inbox_after(&self, after: i64, limit: usize) -> Result<Vec<InboxItem>> {
        self.sql(|c| {
            let mut q = c.prepare(
                "SELECT i.seq, i.peer, i.content, i.received_at FROM cc2_inbox i JOIN cc2_peer p ON p.account=i.peer
                 WHERE i.seq>?1 AND p.state='active' ORDER BY i.seq LIMIT ?2",
            )?;
            q.query_map(params![after, limit as i64], |r| {
                Ok(InboxItem {
                    seq: r.get(0)?,
                    peer: r.get(1)?,
                    content: r.get(2)?,
                    received_at: r.get::<_, i64>(3)? as u64,
                })
            })?
            .collect()
        })
    }

    pub fn inbox_ack(&self, seqs: &[i64]) -> Result<()> {
        for seq in seqs {
            self.sql(|c| c.execute("DELETE FROM cc2_inbox WHERE seq=?1", [seq]))?;
        }
        Ok(())
    }

    pub fn inbox_purge(&self, peer: &str) -> Result<()> {
        self.sql(|c| c.execute("DELETE FROM cc2_inbox WHERE peer=?1", [peer]))?;
        Ok(())
    }

    // ---- tokens -------------------------------------------------------------------

    pub fn token(&self, peer: &str, token: &str) -> Result<Option<(String, String)>> {
        self.sql(|c| {
            c.query_row(
                "SELECT event_id, content_hash FROM cc2_token WHERE peer=?1 AND token=?2",
                params![peer, token],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
        })
    }

    pub fn put_token(&self, peer: &str, token: &str, event_id: &str, hash: &str) -> Result<()> {
        self.sql(|c| {
            c.execute(
                "INSERT INTO cc2_token(peer,token,event_id,content_hash,created_at) VALUES(?1,?2,?3,?4,?5)",
                params![peer, token, event_id, hash, i64_of(now_ms())],
            )
        })?;
        Ok(())
    }

    pub fn counts(&self) -> Result<(i64, i64)> {
        self.sql(|c| {
            c.query_row(
                "SELECT (SELECT count(*) FROM cc2_event), (SELECT count(*) FROM cc2_inbox)",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
        })
    }

    /// Harness-only plaintext search in host tables; returns counts only.
    #[cfg(feature = "local-harness")]
    pub fn plaintext_matches(&self, marker: &str) -> Result<usize> {
        let like = format!("%{marker}%");
        self.sql(|c| {
            c.query_row(
                "SELECT (SELECT count(*) FROM cc2_inbox WHERE content LIKE ?1)
                      + (SELECT count(*) FROM cc2_event WHERE raw LIKE ?1)
                      + (SELECT count(*) FROM cc2_meta WHERE value LIKE ?1)",
                [like],
                |r| r.get::<_, i64>(0),
            )
        })
        .map(|n| n as usize)
    }
}

fn database_error(error: Error) -> DatabaseError {
    DatabaseError::backend(error)
}

impl NostrDatabase for Store {
    fn backend(&self) -> Backend {
        Backend::Custom("cruxcoach-sqlcipher".to_string())
    }

    fn save_event<'a>(
        &'a self,
        event: &'a Event,
    ) -> nostr::util::BoxedFuture<'a, std::result::Result<SaveEventStatus, DatabaseError>> {
        Box::pin(async move { self.save_remote(event).map_err(database_error) })
    }

    fn check_id<'a>(
        &'a self,
        event_id: &'a EventId,
    ) -> nostr::util::BoxedFuture<'a, std::result::Result<DatabaseEventStatus, DatabaseError>> {
        Box::pin(async move {
            Ok(
                if self.exists(&event_id.to_hex()).map_err(database_error)? {
                    DatabaseEventStatus::Saved
                } else {
                    DatabaseEventStatus::NotExistent
                },
            )
        })
    }

    fn event_by_id<'a>(
        &'a self,
        event_id: &'a EventId,
    ) -> nostr::util::BoxedFuture<'a, std::result::Result<Option<Event>, DatabaseError>> {
        Box::pin(async move { self.event(&event_id.to_hex()).map_err(database_error) })
    }

    fn count(
        &self,
        filter: Filter,
    ) -> nostr::util::BoxedFuture<'_, std::result::Result<usize, DatabaseError>> {
        Box::pin(async move {
            Ok(self
                .select(&filter, MAX_QUERY_ROWS)
                .map_err(database_error)?
                .len())
        })
    }

    fn query(
        &self,
        filter: Filter,
    ) -> nostr::util::BoxedFuture<'_, std::result::Result<Events, DatabaseError>> {
        Box::pin(async move {
            let events = self
                .select(&filter, MAX_QUERY_ROWS)
                .map_err(database_error)?;
            let mut result = Events::new(&filter);
            result.extend(events);
            Ok(result)
        })
    }

    fn negentropy_items(
        &self,
        filter: Filter,
    ) -> nostr::util::BoxedFuture<'_, std::result::Result<Vec<(EventId, Timestamp)>, DatabaseError>>
    {
        Box::pin(async move {
            Ok(self
                .select(&filter, MAX_EVENTS as usize)
                .map_err(database_error)?
                .into_iter()
                .map(|e| (e.id, e.created_at))
                .collect())
        })
    }

    fn delete(
        &self,
        filter: Filter,
    ) -> nostr::util::BoxedFuture<'_, std::result::Result<(), DatabaseError>> {
        Box::pin(async move {
            for event in self
                .select(&filter, MAX_QUERY_ROWS)
                .map_err(database_error)?
            {
                // Own undelivered events belong to the MLS outbound path.
                if event.pubkey.to_hex() != self.account {
                    self.delete_event(&event.id.to_hex())
                        .map_err(database_error)?;
                }
            }
            Ok(())
        })
    }

    fn wipe(&self) -> nostr::util::BoxedFuture<'_, std::result::Result<(), DatabaseError>> {
        Box::pin(async move { Err(database_error(Error("wipe_refused"))) })
    }
}

pub fn kind_of(event: &Event) -> Kind {
    event.kind
}

pub fn now_seconds() -> u64 {
    now_s()
}
