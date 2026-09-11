use futures_util::{SinkExt, StreamExt};
use nostr::{Event, Filter, JsonUtil, filter::MatchEventOptions};
use serde_json::{Value, json};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicBool, Ordering},
};
pub struct Relay {
    pub url: String,
    pub events: Arc<Mutex<Vec<Event>>>,
    pub online: Arc<AtomicBool>,
    stop: Option<tokio::sync::oneshot::Sender<()>>,
    thread: Option<std::thread::JoinHandle<()>>,
}
impl Relay {
    pub fn start() -> Self {
        let events = Arc::new(Mutex::new(Vec::<Event>::new()));
        let online = Arc::new(AtomicBool::new(true));
        let (url_tx, url_rx) = std::sync::mpsc::channel();
        let (stop_tx, mut stop_rx) = tokio::sync::oneshot::channel();
        let shared = events.clone();
        let available = online.clone();
        let thread = std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();
            rt.block_on(async move {
                let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
                url_tx.send(format!("ws://{}",listener.local_addr().unwrap())).unwrap();
                loop {
                    tokio::select! {
                        _=&mut stop_rx=>break,
                        incoming=listener.accept()=>{
                            let (stream,_)=incoming.unwrap();let events=shared.clone();let online=available.clone();
                            tokio::spawn(async move {
                                if !online.load(Ordering::SeqCst) {return;}
                                let Ok(mut socket)=tokio_tungstenite::accept_async(stream).await else{return};
                                while let Some(Ok(message))=socket.next().await {
                                    let Ok(text)=message.to_text() else{continue};
                                    let Ok(request)=serde_json::from_str::<Value>(text) else{continue};
                                    match request[0].as_str() {
                                        Some("EVENT")=>{
                                            let event=Event::from_json(request[1].to_string()).unwrap();
                                            let valid=event.verify().is_ok();let eid=event.id.to_hex();
                                            if valid {events.lock().unwrap().push(event);}
                                            let _=socket.send(tokio_tungstenite::tungstenite::Message::Text(json!(["OK",eid,valid,""]).to_string().into())).await;
                                        },
                                        Some("REQ")=>{
                                            let stored=events.lock().unwrap().clone();
                                            let mut selected = std::collections::BTreeMap::new();
                                            for raw in &request.as_array().unwrap()[2..] {
                                                let filter: Filter = serde_json::from_value(raw.clone()).unwrap();
                                                let mut matching: Vec<_> = stored.iter().filter(|e| filter.match_event(e, MatchEventOptions::default())).cloned().collect();
                                                matching.sort_by(|a,b| b.created_at.cmp(&a.created_at).then(a.id.cmp(&b.id)));
                                                matching.dedup_by_key(|e| e.id);
                                                for event in matching.into_iter().take(filter.limit.unwrap_or(500)) { selected.insert(event.id, event); }
                                            }
                                            for event in selected.into_values() {
                                                    // Every event is delivered twice, like redundant relays.
                                                    let frame=json!(["EVENT",request[1],serde_json::from_str::<Value>(&event.as_json()).unwrap()]);
                                                    for _ in 0..2 {let _=socket.send(tokio_tungstenite::tungstenite::Message::Text(frame.to_string().into())).await;}
                                            }
                                            let _=socket.send(tokio_tungstenite::tungstenite::Message::Text(json!(["EOSE",request[1]]).to_string().into())).await;
                                        },
                                        Some("CLOSE")=>break,
                                        _=>{},
                                    }
                                }
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
