//! Explicit, bounded synthetic qualification. Not run by cargo test or CI.
//! Two ephemeral identities, one welcome, one application and one response.
use cruxcoach_marmot::{
    node::{Config, Error, LocalProofSigner, Node, now_ms},
    relay::{self, DEFAULT_RELAYS},
};
use nostr::Keys;
use serde_json::json;
use std::sync::Arc;
use storage_sqlite::SqlCipherKey;
fn main() {
    if std::env::args().nth(1).as_deref() != Some("--synthetic-public-probe") {
        eprintln!("explicit_probe_flag_required");
        std::process::exit(2);
    }
    let six = std::env::args().any(|arg| arg == "--six-relay-compat");
    let outcome = run(six);
    match outcome {
        Ok(()) => println!(
            "{}",
            json!({"result":"native_encrypted_roundtrip_verified","cruxcoach_backend":false,"cruxcoach_relay_required":false,"six_relay_compatibility":six})
        ),
        Err(error) => {
            println!("{}", json!({"result":"blocked","code":error.0}));
            std::process::exit(1);
        }
    }
}
fn run(six: bool) -> Result<(), Error> {
    let dir = tempfile::tempdir().map_err(|_| Error("temporary_directory"))?;
    let mut participants = Vec::new();
    // Deliberately exclude CruxCoach from BOTH discovery and transport. Six-
    // relay compatibility is reported separately, with the same native events.
    let relays: Vec<String> = DEFAULT_RELAYS[..if six { 6 } else { 5 }]
        .iter()
        .map(|r| r.to_string())
        .collect();
    for n in 0..2 {
        let key = Keys::generate();
        let account = key.public_key().to_hex();
        let db_key = SqlCipherKey::new(Keys::generate().public_key().to_hex())
            .map_err(|_| Error("test_db_key"))?;
        participants.push(Node::open(
            &dir.path().join(format!("participant{n}.db")),
            db_key,
            Config {
                account,
                relays: relays.clone(),
                local_test: false,
            },
            Arc::new(key.clone()),
            Arc::new(LocalProofSigner(key)),
        )?);
    }
    let mut a = participants.remove(0);
    let mut b = participants.remove(0);
    a.bootstrap()?;
    b.bootstrap()?;
    a.sync()?;
    b.sync()?;
    println!("{}", json!({"phase":"bootstrap","participants":2}));
    let af = a.invite(&b.config.account)?;
    a.sync()?;
    b.sync()?;
    let bf = b.accept_invitation(&a.config.account)?;
    if af.binding != bf.binding || af.local_leaf != bf.peer_leaf {
        return Err(Error("native_binding"));
    }
    let tags = vec![vec![
        "l".into(),
        "cc.native.qualification.v1".into(),
        "cruxcoach.private".into(),
    ]];
    let first = a.handoff(
        &af,
        1220,
        tags.clone(),
        "synthetic native qualification payload".into(),
        now_ms() + 600_000,
    )?;
    a.publish_handoff(&first, &af)?;
    b.sync()?;
    let received = b
        .inbox()?
        .into_iter()
        .find(|r| r.content == "synthetic native qualification payload")
        .ok_or(Error("recipient_decrypt"))?;
    b.acknowledge(&received.source, &bf)?;
    let response = b.handoff(
        &bf,
        1220,
        tags,
        "synthetic native qualification response".into(),
        now_ms() + 600_000,
    )?;
    b.publish_handoff(&response, &bf)?;
    a.sync()?;
    if !a
        .inbox()?
        .iter()
        .any(|r| r.content == "synthetic native qualification response")
    {
        return Err(Error("response_decrypt"));
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|_| Error("runtime"))?;
    // Per-kind readback of the exact authenticated synthetic events. No account
    // identifiers, signatures or ciphertext appear in the evidence output.
    let mut kinds = std::collections::BTreeSet::new();
    for out in a.outbox()?.into_iter().chain(b.outbox()?) {
        if !kinds.insert(out.event.kind) {
            continue;
        }
        for target in &out.targets {
            let result = runtime.block_on(relay::exchange(
                &target.relay,
                false,
                json!(["REQ","cc-readback",{"ids":[out.event.id],"limit":1}]),
            ));
            println!(
                "{}",
                json!({"relay":target.relay,"kind":out.event.kind,"publish":target.status,"read":result.status,"exact_readback":result.events.iter().any(|e|e.id.to_hex()==out.event.id)})
            );
        }
    }
    Ok(())
}
