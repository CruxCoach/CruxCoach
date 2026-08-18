//! `FipsAddress` → npub directory, and the bounded hold that covers its gaps.
//!
//! An inbound application datagram carries the sender's `FipsAddress` in its
//! IPv6 source field. `FipsAddress` is a truncated hash of the node key, so
//! the npub cannot be recovered from the packet — it has to be looked up.
//!
//! The previous bridge read `ControlReadHandle::peer_views()` directly, which
//! upstream has since made `pub(crate)`. The supported replacement is the
//! control socket, but a `show_peers` round trip per received packet would put
//! a JSON query on the receive hot path. So a background refresher keeps a
//! bounded snapshot here, and a packet whose sender is not in it yet is *held*
//! briefly rather than dropped: on a fresh join the first frame regularly
//! arrives before the snapshot that names its sender.

use std::collections::HashMap;
use std::net::Ipv6Addr;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use serde_json::Value;

/// Upper bound on directory entries. A BoardCell is a handful of phones and
/// the native direct-link cap is seven; multi-hop members add a few more. The
/// bound exists so a hostile or buggy peer table cannot grow this process.
pub const MAX_DIRECTORY_ENTRIES: usize = 128;

/// One resolvable peer, in the shape the JNI `peers()` surface reports.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerEntry {
    pub npub: String,
    pub ipv6: Ipv6Addr,
    /// Send-capable, matching FIPS' own `ConnectivityState::can_send`:
    /// `connected` and `stale` both are. A peer that has merely missed a
    /// heartbeat window still carries BoardCell traffic, and reporting it as
    /// disconnected would evict a live member from the mesh UI and stop the
    /// direct-join hello it is waiting for.
    pub connected: bool,
    /// Transport type of the peer's resolved link (`"ble"`), or empty for a
    /// multi-hop peer that has a session but no direct link of ours.
    pub transport: String,
    pub last_seen_ms: u64,
}

/// One row of `show_peers`, parsed but not yet resolved to an address.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PeerRow {
    pub npub: String,
    pub ipv6_addr: Option<String>,
    pub connectivity: String,
    pub transport_type: Option<String>,
    pub last_seen_ms: u64,
}

/// One row of `show_sessions`. Sessions are end-to-end, so this is how a
/// multi-hop sender becomes resolvable at all; `show_peers` only names nodes
/// we hold a direct link to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionRow {
    pub npub: String,
    pub state: String,
    pub last_activity_ms: u64,
}

fn string_field(row: &Value, key: &str) -> Option<String> {
    row.get(key)
        .and_then(Value::as_str)
        .map(ToString::to_string)
}

/// Parse the `data` object of a `show_peers` response.
///
/// Unknown and future keys are ignored, and a row missing the fields this
/// bridge needs is skipped rather than failing the whole snapshot: a partial
/// directory still resolves the peers it did parse.
pub fn parse_peer_rows(data: &Value) -> Vec<PeerRow> {
    data.get("peers")
        .and_then(Value::as_array)
        .map(|rows| {
            rows.iter()
                .filter_map(|row| {
                    let npub = string_field(row, "npub").filter(|npub| !npub.is_empty())?;
                    Some(PeerRow {
                        npub,
                        ipv6_addr: string_field(row, "ipv6_addr"),
                        // Upstream renders this as a plain string
                        // (`ConnectivityState`'s `Display`), not an enum.
                        connectivity: string_field(row, "connectivity").unwrap_or_default(),
                        transport_type: string_field(row, "transport_type"),
                        last_seen_ms: row.get("last_seen_ms").and_then(Value::as_u64).unwrap_or(0),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Parse the `data` object of a `show_sessions` response.
pub fn parse_session_rows(data: &Value) -> Vec<SessionRow> {
    data.get("sessions")
        .and_then(Value::as_array)
        .map(|rows| {
            rows.iter()
                .filter_map(|row| {
                    let npub = string_field(row, "npub").filter(|npub| !npub.is_empty())?;
                    Some(SessionRow {
                        npub,
                        state: string_field(row, "state").unwrap_or_default(),
                        last_activity_ms: row
                            .get("last_activity_ms")
                            .and_then(Value::as_u64)
                            .unwrap_or(0),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Whether a `show_peers` `connectivity` string means the peer can carry
/// traffic. Mirrors `fips::ConnectivityState::can_send`.
pub fn connectivity_can_send(connectivity: &str) -> bool {
    matches!(connectivity, "connected" | "stale")
}

/// Join both tables into directory entries.
///
/// `address_of` derives a peer's `FipsAddress` from its npub; on Android that
/// is `PeerIdentity::from_npub(..).address().to_ipv6()`. It is also the
/// authority: the `ipv6_addr` string a row carries is only used to cross-check
/// that the derivation and the daemon agree, never as the key on its own, so a
/// malformed or unexpected rendering cannot install a wrong mapping.
///
/// Direct peers win over sessions for the same address, because only they
/// carry a transport type — and `transport == "ble"` is what gates CruxCoach's
/// direct-join admission.
pub fn merge_rows(
    peers: &[PeerRow],
    sessions: &[SessionRow],
    address_of: impl Fn(&str) -> Option<Ipv6Addr>,
) -> Vec<PeerEntry> {
    let mut merged: Vec<PeerEntry> = Vec::new();
    let mut seen: HashMap<Ipv6Addr, usize> = HashMap::new();

    for row in peers {
        let Some(ipv6) = address_of(&row.npub) else {
            continue;
        };
        if row
            .ipv6_addr
            .as_deref()
            .and_then(|text| text.parse::<Ipv6Addr>().ok())
            .is_some_and(|reported| reported != ipv6)
        {
            // The daemon and the local derivation disagree about which address
            // this key hashes to. Refusing the row is the safe direction: a
            // mismatch would attribute inbound BoardCell frames to the wrong
            // member.
            continue;
        }
        let entry = PeerEntry {
            npub: row.npub.clone(),
            ipv6,
            connected: connectivity_can_send(&row.connectivity),
            transport: row.transport_type.clone().unwrap_or_default(),
            last_seen_ms: row.last_seen_ms,
        };
        match seen.get(&ipv6) {
            Some(&index) => merged[index] = entry,
            None => {
                seen.insert(ipv6, merged.len());
                merged.push(entry);
            }
        }
    }

    for row in sessions {
        let Some(ipv6) = address_of(&row.npub) else {
            continue;
        };
        if seen.contains_key(&ipv6) {
            continue;
        }
        seen.insert(ipv6, merged.len());
        merged.push(PeerEntry {
            npub: row.npub.clone(),
            ipv6,
            connected: row.state == "established",
            transport: String::new(),
            last_seen_ms: row.last_activity_ms,
        });
    }

    merged.truncate(MAX_DIRECTORY_ENTRIES);
    merged
}

/// The published snapshot, replaced wholesale by the refresher.
#[derive(Default)]
pub struct PeerDirectory {
    entries: Mutex<Vec<PeerEntry>>,
    /// Bumped whenever a snapshot is installed. A receiver waiting on an
    /// unresolved sender watches this to know a *new* answer exists, rather
    /// than re-deriving the same miss.
    generation: AtomicU64,
}

impl PeerDirectory {
    pub fn new() -> Self {
        Self::default()
    }

    /// Install a new snapshot. Replacement, not merge: a peer that left the
    /// node's tables must leave this directory too, or a stale npub keeps
    /// being reported as a live BoardCell member.
    pub fn replace(&self, entries: Vec<PeerEntry>) {
        *self.entries.lock().unwrap_or_else(|e| e.into_inner()) = entries;
        self.generation.fetch_add(1, Ordering::Release);
    }

    pub fn generation(&self) -> u64 {
        self.generation.load(Ordering::Acquire)
    }

    pub fn npub_for(&self, ipv6: &Ipv6Addr) -> Option<String> {
        self.entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .find(|entry| &entry.ipv6 == ipv6)
            .map(|entry| entry.npub.clone())
    }

    pub fn snapshot(&self) -> Vec<PeerEntry> {
        self.entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
    }

    /// The tab-separated `npub, connected, transport, lastSeenMs` rendering
    /// the Kotlin runtime has always parsed. Unchanged on purpose: the
    /// migration replaced where this data comes from, not what it means.
    pub fn render_lines(&self) -> String {
        self.snapshot()
            .iter()
            .map(|entry| {
                format!(
                    "{}\t{}\t{}\t{}",
                    entry.npub, entry.connected, entry.transport, entry.last_seen_ms
                )
            })
            .collect::<Vec<_>>()
            .join("\n")
    }
}

/// Datagrams whose sender the directory could not name yet.
///
/// Bounded in both size and time, and every eviction is counted: an inbound
/// BoardCell delta that vanishes with no trace is exactly the failure this
/// bridge must not introduce. The counters are reported through the
/// diagnostics surface, so a drop is visible rather than silent.
pub struct InboundHold {
    held: Mutex<Vec<HeldDatagram>>,
    capacity: usize,
    ttl_ms: u64,
    evicted: AtomicU64,
    expired: AtomicU64,
    resolved: AtomicU64,
}

struct HeldDatagram {
    source: Ipv6Addr,
    payload: Vec<u8>,
    held_since_ms: u64,
}

impl InboundHold {
    pub fn new(capacity: usize, ttl_ms: u64) -> Self {
        Self {
            held: Mutex::new(Vec::new()),
            capacity,
            ttl_ms,
            evicted: AtomicU64::new(0),
            expired: AtomicU64::new(0),
            resolved: AtomicU64::new(0),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.held
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_empty()
    }

    /// Hold one datagram. Oldest-first eviction when full: a newer frame is
    /// the more likely one to still matter, and the BoardCell outbox and
    /// anti-entropy pass repair whatever is lost either way.
    pub fn push(&self, source: Ipv6Addr, payload: Vec<u8>, now_ms: u64) {
        let mut held = self.held.lock().unwrap_or_else(|e| e.into_inner());
        while held.len() >= self.capacity {
            held.remove(0);
            self.evicted.fetch_add(1, Ordering::Relaxed);
        }
        held.push(HeldDatagram {
            source,
            payload,
            held_since_ms: now_ms,
        });
    }

    /// Take the oldest held datagram whose sender is now resolvable, expiring
    /// anything that has waited longer than the TTL on the way past.
    pub fn take_resolved(
        &self,
        now_ms: u64,
        resolve: impl Fn(&Ipv6Addr) -> Option<String>,
    ) -> Option<(String, Vec<u8>)> {
        let mut held = self.held.lock().unwrap_or_else(|e| e.into_inner());
        let before = held.len();
        held.retain(|entry| now_ms.saturating_sub(entry.held_since_ms) <= self.ttl_ms);
        self.expired
            .fetch_add((before - held.len()) as u64, Ordering::Relaxed);

        let index = held
            .iter()
            .position(|entry| resolve(&entry.source).is_some())?;
        let entry = held.remove(index);
        let npub = resolve(&entry.source)?;
        self.resolved.fetch_add(1, Ordering::Relaxed);
        Some((npub, entry.payload))
    }

    /// `(evicted, expired, resolved)` — reported as counters, never inferred
    /// into per-peer history.
    pub fn counters(&self) -> (u64, u64, u64) {
        (
            self.evicted.load(Ordering::Relaxed),
            self.expired.load(Ordering::Relaxed),
            self.resolved.load(Ordering::Relaxed),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn addr(last: u16) -> Ipv6Addr {
        Ipv6Addr::new(0xfd00, 0, 0, 0, 0, 0, 0, last)
    }

    /// Maps the npubs used below onto stable addresses, standing in for
    /// `PeerIdentity::from_npub(..).address().to_ipv6()`.
    fn fake_addresses(npub: &str) -> Option<Ipv6Addr> {
        match npub {
            "npub1direct" => Some(addr(1)),
            "npub1stale" => Some(addr(2)),
            "npub1multihop" => Some(addr(3)),
            "npub1gone" => Some(addr(4)),
            _ => None,
        }
    }

    fn show_peers() -> Value {
        json!({"peers": [
            {
                "node_addr": "aa",
                "npub": "npub1direct",
                "display_name": "phone-a",
                "ipv6_addr": "fd00::1",
                "connectivity": "connected",
                "link_id": 3,
                "last_seen_ms": 1_700_000_000_000u64,
                "direction": "outbound",
                "transport_type": "ble",
                "stats": {"packets_sent": 4}
            },
            {
                "node_addr": "bb",
                "npub": "npub1stale",
                "ipv6_addr": "fd00::2",
                "connectivity": "stale",
                "last_seen_ms": 1_700_000_000_100u64,
                "transport_type": "ble"
            },
            {
                "node_addr": "cc",
                "npub": "npub1gone",
                "ipv6_addr": "fd00::4",
                "connectivity": "disconnected",
                "last_seen_ms": 5
            }
        ]})
    }

    #[test]
    fn the_current_show_peers_schema_parses() {
        let rows = parse_peer_rows(&show_peers());
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0].npub, "npub1direct");
        assert_eq!(rows[0].transport_type.as_deref(), Some("ble"));
        assert_eq!(rows[0].connectivity, "connected");
        assert_eq!(rows[1].connectivity, "stale");
        // A row with no link has no transport_type key at all.
        assert_eq!(rows[2].transport_type, None);
    }

    #[test]
    fn stale_is_send_capable_but_disconnected_is_not() {
        assert!(connectivity_can_send("connected"));
        assert!(connectivity_can_send("stale"));
        assert!(!connectivity_can_send("reconnecting"));
        assert!(!connectivity_can_send("disconnected"));
    }

    #[test]
    fn peers_and_sessions_merge_with_direct_links_winning() {
        let peers = parse_peer_rows(&show_peers());
        let sessions = parse_session_rows(&json!({"sessions": [
            {"remote_addr": "aa", "npub": "npub1direct", "state": "established",
             "last_activity_ms": 9},
            {"remote_addr": "dd", "npub": "npub1multihop", "state": "established",
             "last_activity_ms": 1_700_000_000_200u64},
            {"remote_addr": "ee", "npub": "npub1unknown", "state": "handshaking",
             "last_activity_ms": 1}
        ]}));

        let merged = merge_rows(&peers, &sessions, fake_addresses);
        let by_npub = |npub: &str| {
            merged
                .iter()
                .find(|entry| entry.npub == npub)
                .unwrap_or_else(|| panic!("{npub} missing"))
        };

        assert_eq!(merged.len(), 4);
        assert_eq!(by_npub("npub1direct").transport, "ble");
        assert!(by_npub("npub1direct").connected);
        assert!(
            by_npub("npub1stale").connected,
            "stale still carries traffic"
        );
        assert!(!by_npub("npub1gone").connected);
        // A multi-hop sender is resolvable, but must never look like a direct
        // BLE edge: that is what CruxCoach's direct-join admission gates on.
        assert_eq!(by_npub("npub1multihop").transport, "");
        assert!(by_npub("npub1multihop").connected);
        assert!(
            !merged.iter().any(|entry| entry.npub == "npub1unknown"),
            "an npub with no derivable address is skipped, not guessed",
        );
    }

    #[test]
    fn a_row_whose_reported_address_contradicts_its_npub_is_refused() {
        let peers = parse_peer_rows(&json!({"peers": [
            {"npub": "npub1direct", "ipv6_addr": "fd00::999", "connectivity": "connected"}
        ]}));
        assert!(merge_rows(&peers, &[], fake_addresses).is_empty());
    }

    #[test]
    fn the_directory_renders_the_line_format_kotlin_parses() {
        let directory = PeerDirectory::new();
        directory.replace(merge_rows(
            &parse_peer_rows(&show_peers()),
            &[],
            fake_addresses,
        ));

        let rendered = directory.render_lines();
        let lines: Vec<&str> = rendered.lines().collect();
        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0], "npub1direct\ttrue\tble\t1700000000000");
        assert_eq!(lines[2], "npub1gone\tfalse\t\t5");
        assert_eq!(directory.npub_for(&addr(2)).as_deref(), Some("npub1stale"));
        assert_eq!(directory.npub_for(&addr(9)), None);
    }

    #[test]
    fn replacing_a_snapshot_retires_departed_peers() {
        let directory = PeerDirectory::new();
        directory.replace(merge_rows(
            &parse_peer_rows(&show_peers()),
            &[],
            fake_addresses,
        ));
        assert!(directory.npub_for(&addr(1)).is_some());

        directory.replace(Vec::new());
        assert!(directory.npub_for(&addr(1)).is_none());
        assert_eq!(directory.generation(), 2);
    }

    #[test]
    fn the_directory_is_bounded() {
        let peers: Vec<PeerRow> = (0..MAX_DIRECTORY_ENTRIES + 20)
            .map(|i| PeerRow {
                npub: format!("npub{i}"),
                ipv6_addr: None,
                connectivity: "connected".into(),
                transport_type: Some("ble".into()),
                last_seen_ms: 0,
            })
            .collect();
        let merged = merge_rows(&peers, &[], |npub| {
            npub.strip_prefix("npub")
                .and_then(|i| i.parse::<u16>().ok())
                .map(addr)
        });
        assert_eq!(merged.len(), MAX_DIRECTORY_ENTRIES);
    }

    /// The join case: the first frame from a new member arrives before the
    /// snapshot that names it. Holding it is what keeps first-contact
    /// BoardCell state from being lost.
    #[test]
    fn a_frame_that_beats_its_peer_snapshot_is_held_then_delivered() {
        let hold = InboundHold::new(4, 5_000);
        hold.push(addr(1), b"snapshot".to_vec(), 1_000);

        assert!(hold.take_resolved(1_100, |_| None).is_none());
        assert!(!hold.is_empty());

        let (npub, payload) = hold
            .take_resolved(1_200, |ipv6| {
                (ipv6 == &addr(1)).then(|| "npub1direct".to_string())
            })
            .expect("resolvable once the directory catches up");
        assert_eq!(npub, "npub1direct");
        assert_eq!(payload, b"snapshot");
        assert!(hold.is_empty());
        assert_eq!(hold.counters(), (0, 0, 1));
    }

    #[test]
    fn the_hold_is_bounded_and_every_loss_is_counted() {
        let hold = InboundHold::new(2, 5_000);
        for i in 0..5u8 {
            hold.push(addr(1), vec![i], 1_000);
        }
        let (evicted, expired, _) = hold.counters();
        assert_eq!((evicted, expired), (3, 0));

        // Oldest-first eviction keeps the two newest frames.
        let (_, payload) = hold
            .take_resolved(1_000, |_| Some("npub1direct".into()))
            .unwrap();
        assert_eq!(payload, vec![3]);
    }

    #[test]
    fn a_sender_that_never_resolves_expires_rather_than_accumulating() {
        let hold = InboundHold::new(4, 5_000);
        hold.push(addr(7), b"orphan".to_vec(), 1_000);

        assert!(hold.take_resolved(6_500, |_| None).is_none());
        assert!(hold.is_empty(), "expired entries leave the hold");
        assert_eq!(hold.counters(), (0, 1, 0));
    }
}
