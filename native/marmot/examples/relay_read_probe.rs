//! Bounded read-only compatibility checks with fresh synthetic filters.
use cruxcoach_marmot::relay::{DEFAULT_RELAYS, exchange};
use nostr::Keys;
use serde_json::json;
fn main() {
    let public = Keys::generate().public_key().to_hex();
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    for kind in [10002, 10050, 30443, 1059, 445] {
        let filter = if kind == 445 {
            json!({"kinds":[kind],"#h":[public],"limit":1})
        } else if kind == 1059 {
            json!({"kinds":[kind],"#p":[public],"limit":1})
        } else {
            json!({"kinds":[kind],"authors":[public],"limit":1})
        };
        let results = rt.block_on(futures_util::future::join_all(DEFAULT_RELAYS.iter().map(
            |endpoint| {
                exchange(
                    endpoint,
                    false,
                    json!(["REQ", "cc-qualification", filter.clone()]),
                )
            },
        )));
        for (endpoint, result) in DEFAULT_RELAYS.iter().zip(results) {
            println!(
                "{}",
                json!({"relay":endpoint,"kind":kind,"status":result.status,"matched":result.events.len()})
            );
        }
    }
}
