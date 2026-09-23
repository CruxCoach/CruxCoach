//! CruxCoach conventions above Marmot. One inner kind and label; everything
//! else inside a group is refused in both directions.
use std::time::{SystemTime, UNIX_EPOCH};

/// Inner MLS application event kind for personal-data sharing v2.
pub const APP_KIND: u64 = 1230;
pub const APP_LABEL: &str = "cc.share.v2";
pub const APP_NAMESPACE: &str = "cruxcoach.private";
/// Upper bound for one inner application message (the app pages at 48 KiB).
pub const MAX_APP_CONTENT: usize = 65_536;
/// Reserved URL of the in-process relay. `.invalid` can never resolve.
pub const LOCAL_RELAY_URL: &str = "wss://local.cruxcoach.invalid";
pub const MAX_RELAYS: usize = 16;
pub const MAX_PEERS: usize = 32;
pub const MAX_LIVE_GROUPS: usize = 64;

/// The owner's configurable default pool, applied only to a never-configured
/// account. It is data, not a trust decision; the local relay is always first.
pub const DEFAULT_RELAYS: [&str; 6] = [
    "wss://relay.primal.net",
    "wss://relay.damus.io",
    "wss://nostr-pub.wellorder.net",
    "wss://nos.lol",
    "wss://nostr.oxtr.dev",
    "wss://blossom.cruxcoach.org/nostr",
];

pub fn app_tags() -> Vec<Vec<String>> {
    vec![vec![
        "l".to_string(),
        APP_LABEL.to_string(),
        APP_NAMESPACE.to_string(),
    ]]
}

pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

pub fn now_s() -> u64 {
    now_ms() / 1000
}

pub fn is_hex64(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}
