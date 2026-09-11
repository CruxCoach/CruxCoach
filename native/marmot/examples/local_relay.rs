//! Synthetic local network fixture. Never binds outside loopback.
#[path = "../tests/support/relay.rs"]
mod support;
use std::io::BufRead;
use std::sync::atomic::Ordering;
fn main() {
    if std::env::args().nth(1).as_deref() != Some("--loopback-test-relay") {
        std::process::exit(2);
    }
    let relay = support::Relay::start();
    println!("{}", relay.url);
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        match line.as_str() {
            "offline" => relay.online.store(false, Ordering::SeqCst),
            "online" => relay.online.store(true, Ordering::SeqCst),
            "stats" => println!(
                "{}",
                serde_json::json!({"stored":relay.events.lock().unwrap().len()})
            ),
            "stop" => break,
            _ => {}
        }
    }
}
