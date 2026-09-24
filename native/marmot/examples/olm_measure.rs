//! Spike M0 measurements of the direct Olm channel (not a test, not in CI):
//! cold start, relationship setup, Olm overhead per message size, and an
//! initial state of 10,000 synthetic records sent (a) as 16-record pages
//! through the channel and (b) as one sealed STREAM transfer whose key travels
//! in one Olm message, both over a loopback TCP socket.
//!
//! `cargo run --release --example olm_measure` (debug builds are ~10x slower).
use cruxcoach_marmot::olm::{MAX_OUTBOUND_BYTES, OlmEngine};
use cruxcoach_marmot::p2p;
use nostr::Keys;
use serde_json::json;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Instant;

struct Node {
    engine: OlmEngine,
    id: String,
    _dir: tempfile::TempDir,
}

fn node() -> (Node, f64) {
    let keys = Keys::generate();
    let dir = tempfile::tempdir().unwrap();
    let started = Instant::now();
    let engine = OlmEngine::open(
        &dir.path().join("olm.db"),
        &p2p::random::<32>().unwrap(),
        &keys.public_key().to_hex(),
        Arc::new(keys.clone()),
    )
    .unwrap();
    let ms = started.elapsed().as_secs_f64() * 1000.0;
    (
        Node {
            engine,
            id: keys.public_key().to_hex(),
            _dir: dir,
        },
        ms,
    )
}

/// Length-prefixed request/response over a real loopback socket.
fn roundtrip(stream: &mut TcpStream, request: &[u8]) -> Vec<u8> {
    stream
        .write_all(&(request.len() as u32).to_be_bytes())
        .unwrap();
    stream.write_all(request).unwrap();
    read_message(stream)
}

fn read_message(stream: &mut TcpStream) -> Vec<u8> {
    let mut len = [0u8; 4];
    stream.read_exact(&mut len).unwrap();
    let mut body = vec![0u8; u32::from_be_bytes(len) as usize];
    stream.read_exact(&mut body).unwrap();
    body
}

/// Runs B's side of the socket: serve every request with B's engine.
fn serve(listener: TcpListener, mut b: Node) -> std::thread::JoinHandle<Node> {
    std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        loop {
            let mut len = [0u8; 4];
            if stream.read_exact(&mut len).is_err() {
                return b;
            }
            let mut body = vec![0u8; u32::from_be_bytes(len) as usize];
            stream.read_exact(&mut body).unwrap();
            if body == b"bye" {
                return b;
            }
            let (response, _) = b.engine.exchange_serve(&body, MAX_OUTBOUND_BYTES).unwrap();
            stream
                .write_all(&(response.len() as u32).to_be_bytes())
                .unwrap();
            stream.write_all(&response).unwrap();
        }
    })
}

/// Exchange steps until A has nothing left; returns (steps, bytes on the wire).
fn drain(a: &mut Node, peer: &str, stream: &mut TcpStream) -> (usize, usize) {
    let (mut steps, mut bytes) = (0, 0);
    loop {
        let Some(request) = a
            .engine
            .exchange_begin(Some(peer), false, MAX_OUTBOUND_BYTES)
            .unwrap()
        else {
            break;
        };
        let response = roundtrip(stream, &request);
        steps += 1;
        bytes += request.len() + response.len();
        let (more, _) = a
            .engine
            .exchange_end(&response, MAX_OUTBOUND_BYTES)
            .unwrap();
        if !more && a.engine.pending_for(peer).unwrap() == 0 {
            break;
        }
    }
    (steps, bytes)
}

fn record(i: usize) -> serde_json::Value {
    // Same shape and size class as the research estimate (~282 B per record).
    json!({"id": format!("bid:{:036}", i), "category": "TRAINING_HISTORY", "revision": 1000 + i,
           "fields": {"climbId": format!("synthetic-climb-{:08}", i % 4000), "name": format!("Synthetic route {}", i % 997),
                      "board": "kilter", "angle": (20 + i % 50).to_string(), "attempts": (1 + i % 9).to_string(),
                      "grade": "", "date": "2026-09-01T18:00:00Z", "outcome": "ATTEMPT"}})
}

fn main() {
    let (mut a, cold_a) = node();
    let (mut b, cold_b) = node();
    let reopen = {
        let dir = tempfile::tempdir().unwrap();
        let keys = Keys::generate();
        let key = p2p::random::<32>().unwrap();
        let path = dir.path().join("olm.db");
        drop(
            OlmEngine::open(
                &path,
                &key,
                &keys.public_key().to_hex(),
                Arc::new(keys.clone()),
            )
            .unwrap(),
        );
        let started = Instant::now();
        drop(OlmEngine::open(&path, &key, &keys.public_key().to_hex(), Arc::new(keys)).unwrap());
        started.elapsed().as_secs_f64() * 1000.0
    };
    println!(
        "{}",
        json!({"measure": "cold_start_ms", "new_account": [cold_a, cold_b], "reopen": reopen})
    );

    // Relationship setup over the socket (QR hand-over is in memory).
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let started = Instant::now();
    a.engine.invite(&b.id.clone()).unwrap();
    let package = a.engine.invitation(&b.id.clone()).unwrap();
    b.engine.import_invitation(&package).unwrap();
    let a_id = a.id.clone();
    b.engine.accept(&a_id).unwrap();
    // B delivers its acceptance first (B initiates once), then A drives.
    let request = b
        .engine
        .exchange_begin(Some(&a_id), false, MAX_OUTBOUND_BYTES)
        .unwrap()
        .unwrap();
    let (response, _) = a
        .engine
        .exchange_serve(&request, MAX_OUTBOUND_BYTES)
        .unwrap();
    b.engine
        .exchange_end(&response, MAX_OUTBOUND_BYTES)
        .unwrap();
    let setup_ms = started.elapsed().as_secs_f64() * 1000.0;
    let b_id = b.id.clone();
    let server = serve(listener, b);
    let mut stream = TcpStream::connect(address).unwrap();
    stream.set_nodelay(true).unwrap();
    drain(&mut a, &b_id, &mut stream);
    println!(
        "{}",
        json!({"measure": "relationship_setup_ms", "value": setup_ms, "invitation_bytes": package.len()})
    );

    // Olm overhead per message size (frame = type byte + Olm message).
    let mut overhead = Vec::new();
    for size in [150usize, 2_000, 48_000] {
        let content = "x".repeat(size);
        a.engine.send(&b_id, &format!("o{size}"), &content).unwrap();
        let queued = a.engine.pending_for(&b_id).unwrap();
        assert_eq!(queued, 1);
        let request = a
            .engine
            .exchange_begin(Some(&b_id), false, MAX_OUTBOUND_BYTES)
            .unwrap()
            .unwrap();
        let container = p2p::Container::decode(&request).unwrap();
        let body = p2p::ChannelBody::decode(&container.sections[0].body).unwrap();
        overhead.push(
            json!({"plaintext": size, "frame": body.frames[0].1.len(), "container": request.len()}),
        );
        let response = roundtrip(&mut stream, &request);
        a.engine
            .exchange_end(&response, MAX_OUTBOUND_BYTES)
            .unwrap();
    }
    drain(&mut a, &b_id, &mut stream);
    println!("{}", json!({"measure": "olm_overhead", "values": overhead}));

    // (a) 10,000 records as 16-record pages through the channel.
    let records: Vec<serde_json::Value> = (0..10_000).map(record).collect();
    let json_bytes = serde_json::to_vec(&records).unwrap().len();
    let pages: Vec<String> = records
        .chunks(16)
        .map(|page| json!({"v": 2, "type": "full", "records": page}).to_string())
        .collect();
    let started = Instant::now();
    for (i, page) in pages.iter().enumerate() {
        a.engine.send(&b_id, &format!("p{i}"), page).unwrap();
    }
    let encrypt_ms = started.elapsed().as_secs_f64() * 1000.0;
    let (steps, wire) = drain(&mut a, &b_id, &mut stream);
    let paged_ms = started.elapsed().as_secs_f64() * 1000.0;
    println!(
        "{}",
        json!({"measure": "initial_10000_paged", "records": records.len(), "json_bytes": json_bytes,
        "messages": pages.len(), "encrypt_and_queue_ms": encrypt_ms, "total_ms": paged_ms, "exchange_steps": steps, "wire_bytes": wire})
    );

    // (b) 10,000 records as one sealed transfer: random key K, STREAM (age
    // construction), K inside one Olm message, blob over the socket.
    let started = Instant::now();
    let plain = serde_json::to_vec(&records).unwrap();
    let key = p2p::random::<32>().unwrap();
    let sealed = p2p::stream_seal(&key, &plain).unwrap();
    let digest = hex::encode(p2p::sha256(&plain));
    let pointer = json!({"v": 2, "type": "snapshot", "key": hex::encode(key), "size": sealed.len(), "sha256": digest}).to_string();
    a.engine.send(&b_id, "snapshot", &pointer).unwrap();
    drain(&mut a, &b_id, &mut stream);
    let seal_ms = started.elapsed().as_secs_f64() * 1000.0;
    stream.write_all(&3u32.to_be_bytes()).unwrap();
    stream.write_all(b"bye").unwrap();
    let mut b = server.join().unwrap();
    // A second socket carries the blob (the LAN path streams it).
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let blob_address = listener.local_addr().unwrap();
    let receiver = std::thread::spawn(move || {
        let (mut s, _) = listener.accept().unwrap();
        read_message(&mut s)
    });
    let mut blob_stream = TcpStream::connect(blob_address).unwrap();
    blob_stream
        .write_all(&(sealed.len() as u32).to_be_bytes())
        .unwrap();
    blob_stream.write_all(&sealed).unwrap();
    let received = receiver.join().unwrap();
    let pointer = b
        .engine
        .inbox_after(0, 64)
        .unwrap()
        .into_iter()
        .rev()
        .find(|v| v["content"].as_str().unwrap().contains("snapshot"))
        .unwrap();
    let pointer: serde_json::Value =
        serde_json::from_str(pointer["content"].as_str().unwrap()).unwrap();
    let key: [u8; 32] = hex::decode(pointer["key"].as_str().unwrap())
        .unwrap()
        .try_into()
        .unwrap();
    let opened = p2p::stream_open(&key, &received).unwrap();
    assert_eq!(
        hex::encode(p2p::sha256(&opened)),
        pointer["sha256"].as_str().unwrap()
    );
    let bulk_ms = started.elapsed().as_secs_f64() * 1000.0;
    println!(
        "{}",
        json!({"measure": "initial_10000_bulk", "records": records.len(), "json_bytes": plain.len(),
        "sealed_bytes": sealed.len(), "seal_and_key_message_ms": seal_ms, "total_ms": bulk_ms})
    );
}
