//! The only way the pool reaches a relay. The in-process relay is a duplex
//! stream without any socket; public relays are dialled only after DNS
//! resolution, public-address checks, TLS and frame limits. Pinned peer
//! endpoints are the plurality hook for later LAN/BLE delivery.
use crate::error::Error;
use crate::protocol::LOCAL_RELAY_URL;
use async_wsocket::Message;
use futures_util::{Sink, SinkExt, StreamExt, TryStreamExt};
use nostr::Url;
use nostr_relay_builder::LocalRelay;
use nostr_relay_pool::ConnectionMode;
use nostr_relay_pool::transport::error::TransportError;
use nostr_relay_pool::transport::websocket::{WebSocketSink, WebSocketStream, WebSocketTransport};
use std::collections::BTreeMap;
use std::pin::Pin;
use std::sync::{Arc, RwLock};
use std::task::{Context, Poll};
use std::time::Duration;
use tokio::net::{TcpStream, lookup_host};
use tokio_tungstenite::tungstenite::Message as WireMessage;
use tokio_tungstenite::tungstenite::protocol::{Role, WebSocketConfig};

pub const MAX_FRAME_BYTES: usize = 524_288;

// Test-only fault injection maps the canonical CruxCoach relay to a closed
// loopback port. This branch and its JNI setter are absent from Android.
#[cfg(feature = "local-harness")]
pub static CRUXCOACH_FAULT_PORT: std::sync::atomic::AtomicU16 =
    std::sync::atomic::AtomicU16::new(0);

fn is_loopback(host: &str) -> bool {
    host == "localhost"
        || host
            .trim_matches(['[', ']'])
            .parse::<std::net::IpAddr>()
            .is_ok_and(|ip| ip.is_loopback())
}

/// Public relay URL policy. `local_test` admits `ws://` loopback fixtures only.
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
    if host.ends_with(".invalid") {
        return Err("relay_reserved");
    }
    if url.scheme() != "wss" && !(local_test && is_loopback(host) && url.scheme() == "ws") {
        return Err("relay_tls_required");
    }
    Ok(url)
}

fn same_endpoint(a: &Url, b: &str) -> bool {
    Url::parse(b).is_ok_and(|b| {
        a.scheme() == b.scheme()
            && a.host_str().map(str::to_ascii_lowercase)
                == b.host_str().map(str::to_ascii_lowercase)
            && a.port_or_known_default() == b.port_or_known_default()
            && a.path().trim_end_matches('/') == b.path().trim_end_matches('/')
    })
}

#[derive(Clone)]
pub struct CruxTransport {
    local: LocalRelay,
    local_test: bool,
    /// account → explicitly pinned endpoints. Empty in v1; validated only.
    pinned: Arc<RwLock<BTreeMap<String, Vec<String>>>>,
}

impl std::fmt::Debug for CruxTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("CruxTransport")
    }
}

impl CruxTransport {
    pub fn new(local: LocalRelay, local_test: bool) -> Self {
        Self {
            local,
            local_test,
            pinned: Default::default(),
        }
    }

    /// The plurality hook: a peer endpoint is dialled only for URLs pinned to
    /// that peer. v1 accepts no private endpoint outside the loopback harness.
    pub fn pin_peer_endpoints(&self, account: &str, urls: Vec<String>) -> Result<(), Error> {
        for url in &urls {
            validate_url(url, self.local_test).map_err(Error)?;
        }
        if let Ok(mut pinned) = self.pinned.write() {
            if urls.is_empty() {
                pinned.remove(account);
            } else {
                pinned.insert(account.to_string(), urls);
            }
        }
        Ok(())
    }

    async fn local(&self, config: WebSocketConfig) -> (WebSocketSink, WebSocketStream) {
        let (client, server) = tokio::io::duplex(MAX_FRAME_BYTES * 2);
        let relay = self.local.clone();
        tokio::spawn(async move {
            let _ = relay
                .take_connection(server, std::net::SocketAddr::from(([127, 0, 0, 1], 0)))
                .await;
        });
        let socket =
            tokio_tungstenite::WebSocketStream::from_raw_socket(client, Role::Client, Some(config))
                .await;
        split(socket)
    }

    async fn dial(
        &self,
        url: &Url,
        timeout: Duration,
        config: WebSocketConfig,
    ) -> Result<(WebSocketSink, WebSocketStream), Error> {
        let url = validate_url(url.as_str(), self.local_test).map_err(Error)?;
        let host = url
            .host_str()
            .ok_or(Error("relay_host"))?
            .trim_matches(['[', ']'])
            .to_string();
        let port = url.port_or_known_default().ok_or(Error("relay_port"))?;
        #[cfg(feature = "local-harness")]
        let fault = if self.local_test && same_endpoint(&url, crate::protocol::DEFAULT_RELAYS[5]) {
            CRUXCOACH_FAULT_PORT.load(std::sync::atomic::Ordering::SeqCst)
        } else {
            0
        };
        #[cfg(not(feature = "local-harness"))]
        let fault = 0u16;
        let addresses: Vec<std::net::SocketAddr> = if fault != 0 {
            vec![std::net::SocketAddr::from(([127, 0, 0, 1], fault))]
        } else {
            lookup_host((host.as_str(), port))
                .await
                .map_err(|_| Error("dns"))?
                .take(16)
                .collect()
        };
        if addresses.is_empty() {
            return Err(Error("dns"));
        }
        // A mixed public/private answer is refused rather than silently chosen.
        for address in &addresses {
            if !(self.local_test && address.ip().is_loopback()) {
                cgka_traits::app_components::reject_non_public_ip(address.ip(), false)
                    .map_err(|_| Error("unsafe_address"))?;
            }
        }
        let mut stream = None;
        for address in addresses {
            if let Ok(Ok(connected)) =
                tokio::time::timeout(timeout, TcpStream::connect(address)).await
            {
                stream = Some(connected);
                break;
            }
        }
        let stream = stream.ok_or(Error("connect"))?;
        let roots =
            rustls::RootCertStore::from_iter(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .map_err(|_| Error("tls_config"))?
        .with_root_certificates(roots)
        .with_no_client_auth();
        let connector = tokio_tungstenite::Connector::Rustls(Arc::new(tls));
        let (socket, _) = tokio::time::timeout(
            timeout,
            tokio_tungstenite::client_async_tls_with_config(
                url.as_str(),
                stream,
                Some(config),
                Some(connector),
            ),
        )
        .await
        .map_err(|_| Error("handshake_timeout"))?
        .map_err(|_| Error("tls_or_websocket"))?;
        Ok(split(socket))
    }
}

fn frame_config() -> WebSocketConfig {
    WebSocketConfig::default()
        .max_message_size(Some(MAX_FRAME_BYTES))
        .max_frame_size(Some(MAX_FRAME_BYTES))
}

fn incoming(message: WireMessage) -> Option<Message> {
    Some(match message {
        WireMessage::Text(text) => Message::Text(text.to_string()),
        WireMessage::Binary(data) => Message::Binary(data.to_vec()),
        WireMessage::Ping(data) => Message::Ping(data.to_vec()),
        WireMessage::Pong(data) => Message::Pong(data.to_vec()),
        WireMessage::Close(frame) => {
            Message::Close(frame.map(|f| async_wsocket::message::CloseFrame {
                code: f.code.into(),
                reason: f.reason.to_string(),
            }))
        }
        WireMessage::Frame(_) => return None,
    })
}

fn split<S>(socket: tokio_tungstenite::WebSocketStream<S>) -> (WebSocketSink, WebSocketStream)
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let (sink, stream) = socket.split();
    let sink: WebSocketSink = Box::new(OutgoingSink(sink));
    let stream: WebSocketStream = Box::pin(
        stream
            .map_err(TransportError::backend)
            .try_filter_map(|message| futures_util::future::ready(Ok(incoming(message)))),
    );
    (sink, stream)
}

struct OutgoingSink<S>(S);

impl<S> Sink<Message> for OutgoingSink<S>
where
    S: Sink<WireMessage, Error = tokio_tungstenite::tungstenite::Error> + Unpin,
{
    type Error = TransportError;

    fn poll_ready(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Pin::new(&mut self.0)
            .poll_ready_unpin(cx)
            .map_err(TransportError::backend)
    }

    fn start_send(mut self: Pin<&mut Self>, item: Message) -> Result<(), Self::Error> {
        Pin::new(&mut self.0)
            .start_send_unpin(WireMessage::from(item))
            .map_err(TransportError::backend)
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Pin::new(&mut self.0)
            .poll_flush_unpin(cx)
            .map_err(TransportError::backend)
    }

    fn poll_close(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Result<(), Self::Error>> {
        Pin::new(&mut self.0)
            .poll_close_unpin(cx)
            .map_err(TransportError::backend)
    }
}

impl WebSocketTransport for CruxTransport {
    fn support_ping(&self) -> bool {
        true
    }

    fn connect<'a>(
        &'a self,
        url: &'a Url,
        _mode: &'a ConnectionMode,
        timeout: Duration,
    ) -> nostr::util::BoxedFuture<'a, Result<(WebSocketSink, WebSocketStream), TransportError>>
    {
        Box::pin(async move {
            if url.as_str().trim_end_matches('/') == LOCAL_RELAY_URL {
                return Ok(self.local(frame_config()).await);
            }
            self.dial(url, timeout.min(Duration::from_secs(8)), frame_config())
                .await
                .map_err(TransportError::backend)
        })
    }
}

/// Configured endpoint list: only these are ever added to the pool.
pub fn normalize_relays(relays: &[String], local_test: bool) -> Result<Vec<String>, Error> {
    if relays.is_empty() || relays.len() > crate::protocol::MAX_RELAYS {
        return Err(Error("relay_count"));
    }
    let mut seen: Vec<Url> = Vec::new();
    for relay in relays {
        let url = validate_url(relay, local_test).map_err(Error)?;
        if seen.iter().any(|s| same_endpoint(s, relay)) {
            return Err(Error("relay_duplicate"));
        }
        seen.push(url);
    }
    Ok(relays.to_vec())
}

/// Advertised hints select only locally configured endpoints (URL equivalence
/// keeps the configured spelling); they never add a relay.
pub fn permitted(configured: &[String], advertised: &[String], local_test: bool) -> Vec<String> {
    let advertised: Vec<Url> = advertised
        .iter()
        .filter_map(|u| validate_url(u, local_test).ok())
        .collect();
    configured
        .iter()
        .filter(|c| advertised.iter().any(|a| same_endpoint(a, c)))
        .cloned()
        .collect()
}
