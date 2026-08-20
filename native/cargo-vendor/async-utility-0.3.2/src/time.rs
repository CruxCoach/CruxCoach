// Copyright (c) 2022-2023 Yuki Kishimoto
// Distributed under the MIT software license

//! Time module

use core::future::Future;
use core::time::Duration;
use std::pin::pin;

use futures_util::future::{self, Either};
#[cfg(target_arch = "wasm32")]
use tokio::sync::oneshot;
#[cfg(target_arch = "wasm32")]
use wasm_bindgen_futures::spawn_local;

#[cfg(not(target_arch = "wasm32"))]
use crate::runtime;

/// Sleep
pub async fn sleep(duration: Duration) {
    #[cfg(not(target_arch = "wasm32"))]
    if runtime::is_tokio_context() {
        tokio::time::sleep(duration).await;
    } else {
        // No need to propagate error
        let _ = runtime::handle()
            .spawn(async move {
                tokio::time::sleep(duration).await;
            })
            .await;
    }

    #[cfg(target_arch = "wasm32")]
    gloo_timers::future::sleep(duration).await;
}

/// Wait for a future to complete until an optional duration has elapsed.
///
/// On WASM, the browser timer runs in a separate spawned task and not in the
/// task polling `future`. Dropping a losing gloo timer directly beside a
/// browser-backed future can run timer cleanup while that task is still being
/// polled. In particular, this can reenter the `wasm-bindgen` executor when
/// cancelling an `async-wsocket` connection. The separate task confines timer
/// construction and cleanup to its own executor poll.
pub async fn timeout<F>(duration: Option<Duration>, future: F) -> Option<F::Output>
where
    F: Future,
{
    let Some(duration) = duration else {
        return Some(future.await);
    };

    #[cfg(not(target_arch = "wasm32"))]
    {
        let future = pin!(future);
        let timer = pin!(sleep(duration));

        match future::select(future, timer).await {
            Either::Left((output, _timer)) => Some(output),
            Either::Right(((), _future)) => None,
        }
    }

    #[cfg(target_arch = "wasm32")]
    {
        wasm_timeout(duration, future).await
    }
}

#[cfg(target_arch = "wasm32")]
async fn wasm_timeout<F>(duration: Duration, future: F) -> Option<F::Output>
where
    F: Future,
{
    let (elapsed_sender, elapsed_receiver) = oneshot::channel();
    let (cancel_sender, cancel_receiver) = oneshot::channel();

    spawn_local(async move {
        let timer = pin!(sleep(duration));
        let cancellation = pin!(cancel_receiver);

        if let Either::Left(((), _cancellation)) = future::select(timer, cancellation).await {
            let _ = elapsed_sender.send(());
        }
    });

    let future = pin!(future);
    let elapsed = pin!(elapsed_receiver);

    match future::select(future, elapsed).await {
        Either::Left((output, _elapsed)) => {
            let _ = cancel_sender.send(());
            Some(output)
        }
        Either::Right((_elapsed, _future)) => None,
    }
}

#[cfg(all(test, not(target_arch = "wasm32")))]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_sleep_in_tokio() {
        sleep(Duration::from_secs(5)).await;
    }

    #[async_std::test]
    async fn test_sleep_in_async_std() {
        sleep(Duration::from_secs(5)).await;
    }

    #[test]
    fn test_sleep_in_smol() {
        smol::block_on(async {
            sleep(Duration::from_secs(5)).await;
        });
    }

    #[tokio::test]
    async fn test_timeout_tokio() {
        // Timeout
        let result = timeout(Some(Duration::from_secs(1)), async {
            sleep(Duration::from_secs(2)).await;
        })
        .await;
        assert!(result.is_none());

        // Not timeout
        let result = timeout(Some(Duration::from_secs(10)), async {
            sleep(Duration::from_secs(1)).await;
        })
        .await;
        assert!(result.is_some());
    }

    #[async_std::test]
    async fn test_timeout_async_std() {
        // Timeout
        let result = timeout(Some(Duration::from_secs(1)), async {
            sleep(Duration::from_secs(2)).await;
        })
        .await;
        assert!(result.is_none());

        // Not timeout
        let result = timeout(Some(Duration::from_secs(10)), async {
            sleep(Duration::from_secs(1)).await;
        })
        .await;
        assert!(result.is_some());
    }

    #[test]
    fn test_timeout_smol() {
        smol::block_on(async {
            // Timeout
            let result = timeout(Some(Duration::from_secs(1)), async {
                sleep(Duration::from_secs(2)).await;
            })
            .await;
            assert!(result.is_none());

            // Not timeout
            let result = timeout(Some(Duration::from_secs(10)), async {
                sleep(Duration::from_secs(1)).await;
            })
            .await;
            assert!(result.is_some());
        });
    }
}
