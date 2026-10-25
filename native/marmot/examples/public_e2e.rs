//! Explicit, bounded synthetic qualification. Not run by cargo test or CI.
//! Two ephemeral identities: discovery, invitation, acceptance and one
//! encrypted application message each way over public relays.
use cruxcoach_marmot::engine::LocalProofSigner;
use cruxcoach_marmot::error::Error;
use cruxcoach_marmot::host::{Host, HostConfig};
use cruxcoach_marmot::protocol::DEFAULT_RELAYS;
use nostr::Keys;
use serde_json::{Value, json};
use std::sync::Arc;
use std::time::{Duration, Instant};
use storage_sqlite::SqlCipherKey;

fn call(host: &Host, value: Value) -> Result<Value, Error> {
    host.call(&value.to_string())
}

fn main() {
    if std::env::args().nth(1).as_deref() != Some("--synthetic-public-probe") {
        eprintln!("explicit_probe_flag_required");
        std::process::exit(2);
    }
    let six = std::env::args().any(|arg| arg == "--six-relay-compat");
    match run(six) {
        Ok(()) => println!(
            "{}",
            json!({"result":"native_encrypted_roundtrip_verified","cruxcoach_backend":false,"six_relay_compatibility":six})
        ),
        Err(error) => {
            println!("{}", json!({"result":"blocked","code":error.0}));
            std::process::exit(1);
        }
    }
}

fn run(six: bool) -> Result<(), Error> {
    let dir = tempfile::tempdir().map_err(|_| Error("temporary_directory"))?;
    // The CruxCoach endpoint is excluded unless six-relay compatibility is asked.
    let relays: Vec<String> = DEFAULT_RELAYS[..if six { 6 } else { 5 }]
        .iter()
        .map(|r| r.to_string())
        .collect();
    let mut hosts = Vec::new();
    let mut accounts = Vec::new();
    for n in 0..2 {
        let keys = Keys::generate();
        accounts.push(keys.public_key().to_hex());
        let host = Host::open(
            &dir.path().join(format!("p{n}/session.db")),
            SqlCipherKey::new(Keys::generate().public_key().to_hex()).map_err(|_| Error("key"))?,
            HostConfig {
                account: keys.public_key().to_hex(),
                relays: relays.clone(),
                local_test: false,
            },
            Arc::new(keys.clone()),
            Arc::new(LocalProofSigner(keys)),
        )?;
        call(&host, json!({"op":"set_online","online":true}))?;
        call(&host, json!({"op":"set_discovery","enabled":true}))?;
        hosts.push(host);
    }
    let wait = |check: &dyn Fn() -> bool| -> Result<(), Error> {
        let deadline = Instant::now() + Duration::from_secs(90);
        while !check() {
            if Instant::now() > deadline {
                return Err(Error("probe_timeout"));
            }
            for host in &hosts {
                let _ = call(host, json!({"op":"sync"}));
            }
            std::thread::sleep(Duration::from_secs(1));
        }
        Ok(())
    };
    wait(&|| {
        hosts.iter().all(|h| {
            call(h, json!({"op":"status"})).is_ok_and(|s| s["local"]["outbound_pending"] == 0)
        })
    })?;
    call(&hosts[0], json!({"op":"invite","peer":accounts[1]}))?;
    wait(&|| {
        call(&hosts[1], json!({"op":"status"}))
            .is_ok_and(|s| s["peers"].as_array().is_some_and(|p| !p.is_empty()))
    })?;
    call(&hosts[1], json!({"op":"accept","peer":accounts[0]}))?;
    call(
        &hosts[1],
        json!({"op":"send","peer":accounts[0],"token":"probe","content":"synthetic public probe"}),
    )?;
    let next = |host: &Host| {
        call(
            host,
            json!({"op":"next","after":0,"generation":0,"timeout_ms":0,"limit":8}),
        )
    };
    wait(&|| next(&hosts[0]).is_ok_and(|v| v["items"].as_array().is_some_and(|i| !i.is_empty())))?;
    call(
        &hosts[0],
        json!({"op":"send","peer":accounts[1],"token":"reply","content":"synthetic public reply"}),
    )?;
    wait(&|| next(&hosts[1]).is_ok_and(|v| v["items"].as_array().is_some_and(|i| !i.is_empty())))?;
    Ok(())
}
