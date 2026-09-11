//! Bounded NIP-01 exchanges. Retry/fanout obligations belong to the encrypted journal.
use futures_util::{SinkExt, StreamExt};
use nostr::{Event, Filter, JsonUtil, Url, filter::MatchEventOptions};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::time::Duration;
use tokio::net::{TcpStream, lookup_host};
use tokio_tungstenite::{
    client_async_tls_with_config,
    tungstenite::{Message, protocol::WebSocketConfig},
};

pub const DEFAULT_RELAYS: [&str; 6] = [
    "wss://relay.primal.net",
    "wss://relay.damus.io",
    "wss://nostr-pub.wellorder.net",
    "wss://nos.lol",
    "wss://nostr.oxtr.dev",
    "wss://blossom.cruxcoach.org/nostr",
];
// Test-only fault injection uses a real, closed loopback TCP port. This branch
// and its JNI setter are absent from the Android library.
#[cfg(feature = "local-harness")]
pub static CRUXCOACH_FAULT_PORT: std::sync::atomic::AtomicU16 =
    std::sync::atomic::AtomicU16::new(0);

pub const MAX_EVENT_BYTES: usize = 524_288;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum RelayStatus {
    Accepted,
    Rejected,
    AuthRequired,
    Unavailable,
    ReadComplete,
    Limited,
}

pub struct Exchange {
    pub status: RelayStatus,
    pub events: Vec<Event>,
}

pub fn validate_url(endpoint: &str, local_test: bool) -> Result<Url, &'static str> {
    if endpoint.len() > 512 {
        return Err("relay_url_limit");
    }
    let url = Url::parse(endpoint).map_err(|_| "relay_url")?;
    if !url.username().is_empty()
        || url.password().is_some()
        || url.fragment().is_some()
        || url.query().is_some()
    {
        return Err("relay_url");
    }
    let host = url.host_str().ok_or("relay_host")?;
    let loopback = host == "localhost"
        || host
            .trim_matches(['[', ']'])
            .parse::<std::net::IpAddr>()
            .is_ok_and(|ip| ip.is_loopback());
    if url.scheme() != "wss" && !(local_test && loopback && url.scheme() == "ws") {
        return Err("relay_tls_required");
    }
    Ok(url)
}

/// Resolve, validate and pin the actual dial address. Relay hints never bypass
/// this check. Local tests admit loopback only, never private/link-local hosts.
pub async fn exchange(endpoint: &str, local_test: bool, request: Value) -> Exchange {
    let result = tokio::time::timeout(Duration::from_secs(8), async {
        let url = validate_url(endpoint, local_test)?;
        let host = url.host_str().ok_or("relay_host")?.trim_matches(['[', ']']);
        let port = url.port_or_known_default().ok_or("relay_port")?;
        #[cfg(feature = "local-harness")]
        let fault = if local_test && endpoint == DEFAULT_RELAYS[5] {
            CRUXCOACH_FAULT_PORT.load(std::sync::atomic::Ordering::SeqCst)
        } else {
            0
        };
        #[cfg(not(feature = "local-harness"))]
        let fault = 0u16;
        let addresses: Vec<_> = if fault != 0 {
            vec![std::net::SocketAddr::from(([127, 0, 0, 1], fault))]
        } else {
            lookup_host((host, port))
                .await
                .map_err(|_| "dns")?
                .take(16)
                .collect()
        };
        if addresses.is_empty() {
            return Err("dns");
        }
        // Reject a mixed public/private DNS answer rather than silently choose it.
        for address in &addresses {
            if !(local_test && address.ip().is_loopback()) {
                cgka_traits::app_components::reject_non_public_ip(address.ip(), false)
                    .map_err(|_| "unsafe_address")?;
            }
        }
        let mut stream = None;
        for address in addresses {
            if let Ok(Ok(connected)) =
                tokio::time::timeout(Duration::from_secs(2), TcpStream::connect(address)).await
            {
                stream = Some(connected);
                break;
            }
        }
        let stream = stream.ok_or("connect")?;
        let config = WebSocketConfig::default()
            .max_message_size(Some(MAX_EVENT_BYTES))
            .max_frame_size(Some(MAX_EVENT_BYTES));
        let roots =
            rustls::RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls = rustls::ClientConfig::builder_with_provider(std::sync::Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|_| "tls_config")?
        .with_root_certificates(roots)
        .with_no_client_auth();
        let connector = tokio_tungstenite::Connector::Rustls(std::sync::Arc::new(tls));
        let (mut socket, _) =
            client_async_tls_with_config(endpoint, stream, Some(config), Some(connector))
                .await
                .map_err(|_| "tls_or_websocket")?;
        socket
            .send(Message::Text(request.to_string().into()))
            .await
            .map_err(|_| "send")?;
        let publishing_id = request
            .get(1)
            .and_then(|e| e.get("id"))
            .and_then(Value::as_str);
        let subscription_id = request.get(1).and_then(Value::as_str);
        let filters: Vec<Filter> = if request.get(0).and_then(Value::as_str) == Some("REQ") {
            request
                .as_array()
                .ok_or("request")?
                .iter()
                .skip(2)
                .map(|filter| serde_json::from_value(filter.clone()).map_err(|_| "filter"))
                .collect::<Result<_, _>>()?
        } else {
            Vec::new()
        };
        let mut events = Vec::new();
        let mut total_bytes = 0;
        for _ in 0..300 {
            let message = match socket.next().await {
                Some(Ok(message)) => message,
                Some(Err(tokio_tungstenite::tungstenite::Error::Capacity(_))) => {
                    return Ok(Exchange {
                        status: RelayStatus::Limited,
                        events,
                    });
                }
                _ => return Err("closed"),
            };
            let Message::Text(text) = message else {
                continue;
            };
            total_bytes += text.len();
            if total_bytes > 8_388_608 {
                return Ok(Exchange {
                    status: RelayStatus::Limited,
                    events,
                });
            }
            let Ok(value) = serde_json::from_str::<Value>(&text) else {
                continue;
            };
            match value.get(0).and_then(Value::as_str) {
                Some("AUTH") => {
                    return Ok(Exchange {
                        status: RelayStatus::AuthRequired,
                        events,
                    });
                }
                Some("OK") if publishing_id == value.get(1).and_then(Value::as_str) => {
                    let status = if value.get(2).and_then(Value::as_bool) == Some(true) {
                        RelayStatus::Accepted
                    } else if value
                        .get(3)
                        .and_then(Value::as_str)
                        .is_some_and(|s| s.starts_with("auth-required:"))
                    {
                        RelayStatus::AuthRequired
                    } else {
                        RelayStatus::Rejected
                    };
                    return Ok(Exchange { status, events });
                }
                Some("CLOSED") if subscription_id == value.get(1).and_then(Value::as_str) => {
                    let status = if value
                        .get(2)
                        .and_then(Value::as_str)
                        .is_some_and(|s| s.starts_with("auth-required:"))
                    {
                        RelayStatus::AuthRequired
                    } else {
                        RelayStatus::Rejected
                    };
                    return Ok(Exchange { status, events });
                }
                Some("EOSE") if subscription_id == value.get(1).and_then(Value::as_str) => {
                    let _ = socket
                        .send(Message::Text(
                            json!(["CLOSE", subscription_id]).to_string().into(),
                        ))
                        .await;
                    return Ok(Exchange {
                        status: RelayStatus::ReadComplete,
                        events,
                    });
                }
                Some("EVENT") if subscription_id == value.get(1).and_then(Value::as_str) => {
                    if let Some(raw) = value.get(2)
                        && let Ok(event) = Event::from_json(raw.to_string())
                        && event.verify().is_ok()
                        && filters
                            .iter()
                            .any(|filter| filter.match_event(&event, MatchEventOptions::default()))
                        && !events.iter().any(|e: &Event| e.id == event.id)
                    {
                        events.push(event);
                    }
                    if events.len() >= 256 {
                        return Ok(Exchange {
                            status: RelayStatus::Limited,
                            events,
                        });
                    }
                }
                _ => {}
            }
        }
        Ok(Exchange {
            status: RelayStatus::Limited,
            events,
        })
    })
    .await;
    result
        .ok()
        .and_then(Result::<_, &str>::ok)
        .unwrap_or(Exchange {
            status: RelayStatus::Unavailable,
            events: vec![],
        })
}
