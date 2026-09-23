//! A deliberately unhelpful loopback relay: every stored event is delivered
//! twice, NIP-77 is refused, and "offline" drops live connections. No public I/O.
use futures_util::{SinkExt, StreamExt};
use nostr::{Event, Filter, JsonUtil, filter::MatchEventOptions};
use serde_json::{Value, json};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, AtomicU64, Ordering},
};
use tokio::sync::mpsc::UnboundedSender;
use tokio_tungstenite::tungstenite::Message;

struct Subscription {
    connection: u64,
    id: String,
    filters: Vec<Filter>,
    sender: UnboundedSender<String>,
}

#[derive(Default)]
struct State {
    subscriptions: Vec<Subscription>,
}

pub struct Relay {
    pub url: String,
    pub events: Arc<Mutex<Vec<Event>>>,
    pub online: Arc<AtomicBool>,
    stop: Option<tokio::sync::oneshot::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}

fn frame(value: Value) -> String {
    value.to_string()
}

impl Relay {
    pub fn start() -> Self {
        let events = Arc::new(Mutex::new(Vec::<Event>::new()));
        let online = Arc::new(AtomicBool::new(true));
        let (url_tx, url_rx) = std::sync::mpsc::channel();
        let (stop_tx, mut stop_rx) = tokio::sync::oneshot::channel();
        let shared_events = events.clone();
        let available = online.clone();
        let thread = std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
                .unwrap();
            runtime.block_on(async move {
                let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
                url_tx
                    .send(format!("ws://{}", listener.local_addr().unwrap()))
                    .unwrap();
                let state = Arc::new(Mutex::new(State::default()));
                let next_connection = Arc::new(AtomicU64::new(1));
                loop {
                    tokio::select! {
                        _ = &mut stop_rx => break,
                        incoming = listener.accept() => {
                            let Ok((stream, _)) = incoming else { continue };
                            if !available.load(Ordering::SeqCst) {
                                drop(stream);
                                continue;
                            }
                            let events = shared_events.clone();
                            let online = available.clone();
                            let state = state.clone();
                            let connection = next_connection.fetch_add(1, Ordering::SeqCst);
                            tokio::spawn(async move {
                                let Ok(socket) = tokio_tungstenite::accept_async(stream).await else { return };
                                let (mut sink, mut source) = socket.split();
                                let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<String>();
                                let writer = tokio::spawn(async move {
                                    while let Some(text) = rx.recv().await {
                                        if sink.send(Message::Text(text.into())).await.is_err() {
                                            break;
                                        }
                                    }
                                });
                                let mut tick = tokio::time::interval(std::time::Duration::from_millis(100));
                                loop {
                                    let message = tokio::select! {
                                        _ = tick.tick() => {
                                            if !online.load(Ordering::SeqCst) { break; }
                                            continue;
                                        }
                                        message = source.next() => message,
                                    };
                                    let Some(Ok(message)) = message else { break };
                                    let Ok(text) = message.to_text() else { continue };
                                    let Ok(request) = serde_json::from_str::<Value>(text) else { continue };
                                    match request[0].as_str() {
                                        Some("EVENT") => {
                                            let Ok(event) = Event::from_json(request[1].to_string()) else { continue };
                                            let valid = event.verify().is_ok();
                                            let id = event.id.to_hex();
                                            if valid {
                                                let fresh = {
                                                    let mut stored = events.lock().unwrap();
                                                    let fresh = !stored.iter().any(|e| e.id == event.id);
                                                    if fresh { stored.push(event.clone()); }
                                                    fresh
                                                };
                                                if fresh {
                                                    let raw: Value = serde_json::from_str(&event.as_json()).unwrap();
                                                    for sub in state.lock().unwrap().subscriptions.iter() {
                                                        if sub.filters.iter().any(|f| f.match_event(&event, MatchEventOptions::default())) {
                                                            let _ = sub.sender.send(frame(json!(["EVENT", sub.id, raw])));
                                                        }
                                                    }
                                                }
                                            }
                                            let _ = tx.send(frame(json!(["OK", id, valid, if valid { "" } else { "invalid: signature" }])));
                                        }
                                        Some("REQ") => {
                                            let id = request[1].as_str().unwrap_or("").to_string();
                                            let filters: Vec<Filter> = request.as_array().unwrap()[2..]
                                                .iter()
                                                .filter_map(|raw| serde_json::from_value(raw.clone()).ok())
                                                .collect();
                                            let stored = events.lock().unwrap().clone();
                                            let mut selected = std::collections::BTreeMap::new();
                                            for filter in &filters {
                                                let mut matching: Vec<_> = stored.iter().filter(|e| filter.match_event(e, MatchEventOptions::default())).cloned().collect();
                                                matching.sort_by(|a, b| b.created_at.cmp(&a.created_at).then(a.id.cmp(&b.id)));
                                                for event in matching.into_iter().take(filter.limit.unwrap_or(500)) {
                                                    selected.insert(event.id, event);
                                                }
                                            }
                                            // Ordered by id, not time, and every event twice.
                                            for event in selected.into_values() {
                                                let raw: Value = serde_json::from_str(&event.as_json()).unwrap();
                                                for _ in 0..2 {
                                                    let _ = tx.send(frame(json!(["EVENT", id, raw])));
                                                }
                                            }
                                            let _ = tx.send(frame(json!(["EOSE", id])));
                                            let mut state = state.lock().unwrap();
                                            state.subscriptions.retain(|s| !(s.connection == connection && s.id == id));
                                            state.subscriptions.push(Subscription { connection, id, filters, sender: tx.clone() });
                                        }
                                        Some("CLOSE") => {
                                            let id = request[1].as_str().unwrap_or("").to_string();
                                            state.lock().unwrap().subscriptions.retain(|s| !(s.connection == connection && s.id == id));
                                        }
                                        Some("NEG-OPEN") => {
                                            let _ = tx.send(frame(json!(["NOTICE", "bad msg: unknown cmd NEG-OPEN"])));
                                        }
                                        _ => {}
                                    }
                                }
                                state.lock().unwrap().subscriptions.retain(|s| s.connection != connection);
                                writer.abort();
                            });
                        }
                    }
                }
            });
        });
        Self {
            url: url_rx.recv().unwrap(),
            events,
            online,
            stop: Some(stop_tx),
            thread: Some(thread),
        }
    }
}

impl Drop for Relay {
    fn drop(&mut self) {
        let _ = self.stop.take().unwrap().send(());
        let _ = self.thread.take().unwrap().join();
    }
}
