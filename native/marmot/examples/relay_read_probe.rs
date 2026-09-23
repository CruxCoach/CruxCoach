//! Bounded read-only compatibility checks with fresh synthetic filters, through
//! the same pinned transport and pool the host uses. Manual; never CI.
use cruxcoach_marmot::protocol::DEFAULT_RELAYS;
use cruxcoach_marmot::transport::CruxTransport;
use nostr::{Alphabet, Filter, Keys, Kind, SingleLetterTag};
use nostr_relay_builder::{LocalRelay, RelayBuilder};
use nostr_sdk::Client;
use serde_json::json;
use std::time::Duration;

fn main() {
    if std::env::args().nth(1).as_deref() != Some("--read-only-probe") {
        eprintln!("explicit_probe_flag_required");
        std::process::exit(2);
    }
    let runtime = tokio::runtime::Runtime::new().unwrap();
    runtime.block_on(async {
        let public = Keys::generate().public_key();
        let transport = CruxTransport::new(LocalRelay::new(RelayBuilder::default()), false);
        let client = Client::builder().websocket_transport(transport).build();
        for relay in DEFAULT_RELAYS {
            let _ = client.add_relay(relay).await;
        }
        let _ = client.try_connect(Duration::from_secs(8)).await;
        for kind in [10002u16, 10050, 30443, 1059, 445] {
            let filter = match kind {
                445 => Filter::new().kind(Kind::Custom(kind)).custom_tag(SingleLetterTag::lowercase(Alphabet::H), public.to_hex()),
                1059 => Filter::new().kind(Kind::Custom(kind)).pubkey(public),
                _ => Filter::new().kind(Kind::Custom(kind)).author(public),
            }
            .limit(1);
            for relay in DEFAULT_RELAYS {
                let result = client.fetch_events_from([relay], filter.clone(), Duration::from_secs(8)).await;
                println!("{}", json!({"relay":relay,"kind":kind,"ok":result.is_ok(),"matched":result.map(|e| e.len()).unwrap_or(0)}));
            }
        }
        client.shutdown().await;
    });
}
