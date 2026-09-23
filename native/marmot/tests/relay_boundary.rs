use cruxcoach_marmot::transport::{
    CruxTransport, MAX_FRAME_BYTES, normalize_relays, permitted, validate_url,
};
use futures_util::{SinkExt, StreamExt};
use nostr::Url;
use nostr_relay_builder::{LocalRelay, RelayBuilder};
use nostr_relay_pool::ConnectionMode;
use nostr_relay_pool::transport::websocket::WebSocketTransport;
use std::time::Duration;

#[test]
fn relay_policy_keeps_private_targets_credentials_and_reserved_names_out() {
    for url in [
        "ws://127.0.0.1:80",
        "https://relay.example",
        "wss://user:pass@relay.example",
        "wss://relay.example/?secret=1",
        "wss://relay.example/#fragment",
        "wss://local.cruxcoach.invalid",
    ] {
        assert!(validate_url(url, false).is_err(), "{url}");
    }
    assert!(validate_url("ws://192.168.1.1:80", true).is_err());
    assert!(validate_url("ws://10.0.0.2:80", true).is_err());
    assert!(validate_url("ws://127.0.0.1:80", true).is_ok());
    assert!(validate_url("wss://relay.example", false).is_ok());
    assert!(normalize_relays(&[], false).is_err());
    assert!(normalize_relays(&vec!["wss://relay.example".to_string(); 17], false).is_err());
    assert_eq!(
        normalize_relays(
            &["wss://relay.example".into(), "wss://RELAY.example/".into()],
            false
        )
        .unwrap_err()
        .0,
        "relay_duplicate"
    );
}

#[test]
fn advertised_hints_select_only_configured_endpoints() {
    let configured = vec![
        "wss://a.example".to_string(),
        "wss://b.example/nostr".to_string(),
    ];
    let advertised = vec![
        "wss://A.example/".to_string(),
        "wss://evil.example".to_string(),
        "ws://127.0.0.1:1".to_string(),
    ];
    assert_eq!(
        permitted(&configured, &advertised, false),
        vec!["wss://a.example".to_string()]
    );
    assert!(permitted(&configured, &[], false).is_empty());
}

#[tokio::test]
async fn private_address_resolution_is_refused_before_connecting() {
    let relay = LocalRelay::new(RelayBuilder::default());
    let transport = CruxTransport::new(relay, false);
    // `localhost` resolves to loopback, which production never dials.
    let url = Url::parse("wss://localhost:9").unwrap();
    let result = transport
        .connect(&url, &ConnectionMode::Direct, Duration::from_secs(2))
        .await;
    assert!(result.is_err());
}

#[tokio::test]
async fn oversized_frames_close_the_connection_without_panicking() {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", listener.local_addr().unwrap());
    tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
        let _ = socket
            .send(tokio_tungstenite::tungstenite::Message::Text(
                "x".repeat(MAX_FRAME_BYTES + 1).into(),
            ))
            .await;
    });
    let transport = CruxTransport::new(LocalRelay::new(RelayBuilder::default()), true);
    let (_sink, mut stream) = transport
        .connect(
            &Url::parse(&url).unwrap(),
            &ConnectionMode::Direct,
            Duration::from_secs(2),
        )
        .await
        .unwrap();
    let next = tokio::time::timeout(Duration::from_secs(3), stream.next())
        .await
        .unwrap();
    assert!(matches!(next, Some(Err(_)) | None));
}

#[tokio::test]
async fn the_local_relay_is_reached_without_any_socket() {
    let relay = LocalRelay::new(RelayBuilder::default());
    let transport = CruxTransport::new(relay, false);
    let url = Url::parse(cruxcoach_marmot::protocol::LOCAL_RELAY_URL).unwrap();
    let (mut sink, mut stream) = transport
        .connect(&url, &ConnectionMode::Direct, Duration::from_secs(2))
        .await
        .unwrap();
    sink.send(async_wsocket::Message::Text(
        r#"["REQ","s",{"kinds":[1]}]"#.into(),
    ))
    .await
    .unwrap();
    let reply = tokio::time::timeout(Duration::from_secs(3), stream.next())
        .await
        .unwrap()
        .unwrap()
        .unwrap();
    assert_eq!(reply.as_text(), Some(r#"["EOSE","s"]"#));
}
