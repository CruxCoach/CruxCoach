#![cfg(target_arch = "wasm32")]
#![allow(unexpected_cfgs)]

use core::future::Future;
use core::pin::Pin;
use core::task::{Context, Poll};
use std::cell::Cell;
use std::rc::Rc;
use std::time::Duration;

use async_utility::time::{sleep, timeout};
use async_wsocket::{ConnectionMode, WebSocket};
use futures_util::future::{join, join_all};
use wasm_bindgen::prelude::*;
use wasm_bindgen_test::*;

wasm_bindgen_test_configure!(run_in_browser);

#[wasm_bindgen(module = "/tests/mock_websocket.js")]
extern "C" {
    #[wasm_bindgen(js_name = installMockWebSocket)]
    fn install_mock_websocket();
}

struct PendingUntilDropped {
    dropped: Rc<Cell<bool>>,
}

impl Future for PendingUntilDropped {
    type Output = ();

    fn poll(self: Pin<&mut Self>, _context: &mut Context<'_>) -> Poll<Self::Output> {
        Poll::Pending
    }
}

impl Drop for PendingUntilDropped {
    fn drop(&mut self) {
        self.dropped.set(true);
    }
}

async fn connect(path: String) -> WebSocket {
    let url = async_wsocket::Url::parse(&format!("ws://mock/{path}"))
        .expect("the mock WebSocket URL must be valid");

    async_wsocket::connect(&url, &ConnectionMode::direct())
        .await
        .expect("the mock WebSocket connection must succeed")
}

#[wasm_bindgen_test]
async fn input_completes_before_timeout() {
    let result = timeout(Some(Duration::from_millis(100)), async { 42 }).await;

    assert_eq!(result, Some(42));

    // Give the spawned timer task a turn to process its cancellation.
    sleep(Duration::ZERO).await;
}

#[wasm_bindgen_test]
async fn timeout_cancels_pending_future() {
    let dropped = Rc::new(Cell::new(false));
    let future = PendingUntilDropped {
        dropped: dropped.clone(),
    };

    let result = timeout(Some(Duration::from_millis(10)), future).await;

    assert!(result.is_none());
    assert!(dropped.get());
}

#[wasm_bindgen_test]
async fn concurrent_async_wsocket_connections_complete() {
    install_mock_websocket();

    let connections = (0..4).map(|index| async move {
        timeout(
            Some(Duration::from_millis(250)),
            connect(format!("success/{index}")),
        )
        .await
        .expect("the connection must complete before its timeout")
    });

    let sockets = join_all(connections).await;
    assert_eq!(sockets.len(), 4);
    drop(sockets);

    sleep(Duration::ZERO).await;
}

#[wasm_bindgen_test]
async fn pending_async_wsocket_connection_is_cancelled() {
    install_mock_websocket();

    let result = timeout(
        Some(Duration::from_millis(10)),
        connect("pending/connection".to_owned()),
    )
    .await;

    assert!(result.is_none());
}

#[wasm_bindgen_test]
async fn repeated_async_wsocket_cancellation_and_reconnect_does_not_reenter_executor() {
    install_mock_websocket();

    for cycle in 0..10 {
        let connections = (0..3).map(|index| async move {
            timeout(
                Some(Duration::from_millis(250)),
                connect(format!("success/{cycle}/{index}")),
            )
            .await
            .expect("the connection must complete before its timeout")
        });
        let pending = timeout(
            Some(Duration::from_millis(10)),
            connect(format!("pending/{cycle}")),
        );

        let (sockets, pending_result) = join(join_all(connections), pending).await;

        assert_eq!(sockets.len(), 3);
        assert!(pending_result.is_none());
        drop(sockets);
        sleep(Duration::ZERO).await;
    }
}
