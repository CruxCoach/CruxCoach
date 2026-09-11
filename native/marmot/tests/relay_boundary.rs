use cruxcoach_marmot::relay::{MAX_EVENT_BYTES, RelayStatus, exchange, validate_url};
use futures_util::{SinkExt, StreamExt};
use nostr::{EventBuilder, JsonUtil, Keys, Kind, Timestamp};
use serde_json::{Value, json};

// A deliberately dishonest local relay. No mocked client calls or public I/O.
async fn scripted(frames: Vec<String>, request: Value) -> cruxcoach_marmot::relay::Exchange {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("ws://{}", listener.local_addr().unwrap());
    let task = tokio::spawn(async move {
        let (stream, _) = listener.accept().await.unwrap();
        let mut socket = tokio_tungstenite::accept_async(stream).await.unwrap();
        let _ = socket.next().await;
        for frame in frames {
            if socket
                .send(tokio_tungstenite::tungstenite::Message::Text(frame.into()))
                .await
                .is_err()
            {
                break;
            }
        }
        // Any authentication response would disclose an identity; only CLOSE
        // is permitted from this adapter after EOSE.
        if let Ok(Some(Ok(message))) =
            tokio::time::timeout(std::time::Duration::from_millis(100), socket.next()).await
            && let Ok(text) = message.to_text()
        {
            assert!(!text.starts_with("[\"AUTH\""));
        }
    });
    let result = exchange(&url, true, request).await;
    task.await.unwrap();
    result
}

#[tokio::test]
async fn authenticated_events_still_need_subscription_and_filter_and_are_deduplicated() {
    let keys = Keys::generate();
    let event = EventBuilder::new(Kind::Custom(445), "synthetic ciphertext placeholder")
        .custom_created_at(Timestamp::from(100))
        .sign_with_keys(&keys)
        .unwrap();
    let raw: Value = serde_json::from_str(&event.as_json()).unwrap();
    let mut altered = raw.clone();
    altered["content"] = json!("tampered");
    let result = scripted(vec![
        json!(["EVENT","wrong-sub", raw]).to_string(),
        json!(["EVENT","s", altered]).to_string(),
        json!(["EVENT","s", raw]).to_string(),
        json!(["EVENT","s", raw]).to_string(),
        json!(["EOSE","s"]).to_string(),
    ], json!(["REQ","s",{"kinds":[445],"authors":[keys.public_key().to_hex()],"since":99,"until":101}])).await;
    assert_eq!(result.status, RelayStatus::ReadComplete);
    assert_eq!(result.events.len(), 1);
    let wrong_filter = scripted(
        vec![
            json!(["EVENT", "s", raw]).to_string(),
            json!(["EOSE", "s"]).to_string(),
        ],
        json!(["REQ","s",{"kinds":[30443]}]),
    )
    .await;
    assert!(wrong_filter.events.is_empty());
}

#[tokio::test]
async fn auth_and_rejection_are_visible_without_authentication_or_false_ack() {
    for frame in [
        json!(["AUTH", "synthetic-challenge"]),
        json!(["CLOSED", "s", "auth-required: policy"]),
    ] {
        assert_eq!(
            scripted(vec![frame.to_string()], json!(["REQ","s",{"kinds":[445]}]))
                .await
                .status,
            RelayStatus::AuthRequired
        );
    }
    assert_eq!(
        scripted(
            vec![json!(["OK", "event", false, "blocked: kind"]).to_string()],
            json!(["EVENT",{"id":"event"}])
        )
        .await
        .status,
        RelayStatus::Rejected
    );
    assert_eq!(
        scripted(
            vec![
                json!(["OK", "another-event", true, ""]).to_string(),
                json!(["OK", "event", false, ""]).to_string()
            ],
            json!(["EVENT",{"id":"event"}])
        )
        .await
        .status,
        RelayStatus::Rejected
    );
}

#[tokio::test]
async fn oversized_frames_and_floods_are_limited() {
    let request = json!(["REQ","s",{"kinds":[445]}]);
    assert_eq!(
        scripted(vec!["x".repeat(MAX_EVENT_BYTES + 1)], request.clone())
            .await
            .status,
        RelayStatus::Limited
    );
    assert_eq!(
        scripted(
            vec![json!(["NOTICE", "synthetic flood"]).to_string(); 301],
            request
        )
        .await
        .status,
        RelayStatus::Limited
    );
}

#[test]
fn relay_policy_keeps_private_targets_and_credentials_out_of_production() {
    for url in [
        "ws://127.0.0.1:80",
        "https://relay.example",
        "wss://user:pass@relay.example",
        "wss://relay.example/?secret=1",
        "wss://relay.example/#fragment",
    ] {
        assert!(validate_url(url, false).is_err());
    }
    assert!(validate_url("ws://192.168.1.1:80", true).is_err());
    assert!(validate_url("ws://127.0.0.1:80", true).is_ok());
}
