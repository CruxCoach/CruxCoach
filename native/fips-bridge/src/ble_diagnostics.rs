//! Honest BLE diagnostics after `transport::ble::attempts` was deleted.
//!
//! The old bridge surfaced FIPS' per-peer attempt ring (`ble_attempt_log`):
//! one line per dial, with address, role, discovery latency and outcome.
//! Upstream removed that module. What replaced it is a set of aggregate
//! transport counters, published through the control socket's
//! `show_transports`, plus a per-event `debug!` trace stream in the daemon.
//!
//! This module projects the aggregate half. It deliberately does **not**
//! reconstruct per-peer attempts from aggregates: a counter that went from 3
//! to 4 says a dial failed somewhere, not which peer it was for, and inventing
//! the attribution would be worse than not having it. The per-peer layer is
//! Kotlin's, which owns the radio and already traces every dial, accept,
//! channel open and close with its address and outcome.
//!
//! What is emitted is bounded (a fixed key list), non-identifying (integers
//! only — no addresses, no keys, no transport-local address) and stable enough
//! for a Kotlin parser to diff between polls.

use serde_json::Value;

/// The counters worth reporting, in a fixed order so a reader diffs a stable
/// sequence. Every one of these is a connect-, handshake- or admission-outcome
/// counter; raw byte/packet totals are left out as noise.
pub const REPORTED_COUNTERS: [&str; 12] = [
    "connections_established",
    "connections_accepted",
    "connections_rejected",
    "connect_timeouts",
    "connect_errors",
    "pubkey_exchange_failures",
    "tiebreaker_yields",
    "tiebreaker_drops",
    "duplicate_node_declines",
    "pool_evictions",
    "scan_results",
    "advertisements_sent",
];

/// Project the `data` object of a `show_transports` response into
/// `instance\tcounter\tvalue` lines for the BLE transports it describes.
///
/// A counter the daemon does not report is omitted rather than rendered as
/// zero: "not reported by this build" and "reported as zero" are different
/// facts, and flattening them is how a diagnostic starts lying.
pub fn ble_counter_lines(data: &Value) -> Vec<String> {
    let Some(transports) = data.get("transports").and_then(Value::as_array) else {
        return Vec::new();
    };
    let mut lines = Vec::new();
    for transport in transports {
        if transport.get("type").and_then(Value::as_str) != Some("ble") {
            continue;
        }
        // The instance name, never `local_addr`: on this platform that is the
        // adapter's Bluetooth address.
        let instance = transport
            .get("name")
            .and_then(Value::as_str)
            .unwrap_or("ble");
        let Some(stats) = transport.get("stats") else {
            continue;
        };
        for key in REPORTED_COUNTERS {
            if let Some(value) = stats.get(key).and_then(Value::as_u64) {
                lines.push(format!("{instance}\t{key}\t{value}"));
            }
        }
    }
    lines
}

/// Append the bridge's own counters, so a datagram this process dropped is as
/// visible as one the transport dropped — and so a peer directory that has
/// stopped refreshing (the reason senders go unresolved) shows up as a number
/// rather than as unexplained silence.
pub fn hold_counter_lines(
    evicted: u64,
    expired: u64,
    resolved: u64,
    refresh_failures: u64,
) -> Vec<String> {
    vec![
        format!("bridge\tinbound_held_resolved\t{resolved}"),
        format!("bridge\tinbound_held_evicted\t{evicted}"),
        format!("bridge\tinbound_held_expired\t{expired}"),
        format!("bridge\tpeer_directory_refresh_failures\t{refresh_failures}"),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn show_transports() -> Value {
        json!({"transports": [
            {
                "transport_id": 1,
                "type": "ble",
                "name": "ble0",
                "state": "running",
                "mtu": 2048,
                "local_addr": "ble0/AA:BB:CC:DD:EE:FF",
                "stats": {
                    "packets_sent": 91,
                    "bytes_sent": 4096,
                    "connections_established": 2,
                    "connections_accepted": 1,
                    "connections_rejected": 0,
                    "connect_timeouts": 3,
                    "connect_errors": 1,
                    "pubkey_exchange_failures": 0,
                    "tiebreaker_yields": 1,
                    "tiebreaker_drops": 1,
                    "duplicate_node_declines": 7,
                    "pool_evictions": 0,
                    "scan_results": 412,
                    "advertisements_sent": 5
                }
            },
            {
                "transport_id": 2,
                "type": "udp",
                "state": "running",
                "mtu": 1200,
                "stats": {"connect_errors": 99}
            }
        ]})
    }

    #[test]
    fn only_ble_outcome_counters_are_reported() {
        let lines = ble_counter_lines(&show_transports());
        assert_eq!(lines.len(), REPORTED_COUNTERS.len());
        assert_eq!(lines[0], "ble0\tconnections_established\t2");
        assert_eq!(lines[3], "ble0\tconnect_timeouts\t3");
        assert!(
            lines.iter().all(|line| line.starts_with("ble0\t")),
            "the UDP transport must not leak into the BLE diagnostic",
        );
    }

    #[test]
    fn nothing_identifying_is_emitted() {
        let rendered = ble_counter_lines(&show_transports()).join("\n");
        assert!(!rendered.contains("AA:BB:CC"), "no adapter address");
        assert!(!rendered.contains("npub"), "no peer identity");
        assert!(!rendered.contains("packets_sent"), "outcomes only");
    }

    #[test]
    fn a_counter_the_daemon_omits_is_omitted_not_zeroed() {
        let lines = ble_counter_lines(&json!({"transports": [
            {"type": "ble", "name": "ble0", "stats": {"connect_timeouts": 4}}
        ]}));
        assert_eq!(lines, vec!["ble0\tconnect_timeouts\t4"]);
    }

    #[test]
    fn an_empty_or_unexpected_payload_yields_no_lines() {
        assert!(ble_counter_lines(&json!({})).is_empty());
        assert!(ble_counter_lines(&json!({"transports": []})).is_empty());
        assert!(ble_counter_lines(&json!({"transports": [{"type": "ble"}]})).is_empty());
    }

    #[test]
    fn bridge_hold_losses_are_reported_alongside_transport_counters() {
        assert_eq!(
            hold_counter_lines(2, 1, 40, 3),
            vec![
                "bridge\tinbound_held_resolved\t40",
                "bridge\tinbound_held_evicted\t2",
                "bridge\tinbound_held_expired\t1",
                "bridge\tpeer_directory_refresh_failures\t3",
            ]
        );
    }
}
