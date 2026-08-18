//! Minimal client for FIPS' supported control socket.
//!
//! The embedder-facing `ControlReadHandle::peer_views()` this bridge used to
//! call is `pub(crate)` upstream now; the control socket is the supported
//! boundary that replaced it. It speaks line-delimited JSON over a Unix domain
//! socket: one request line in, one response line out, connection closed.
//!
//! Everything here is platform-independent and unit tested on the host against
//! a real `UnixListener`, so the parsing and framing are covered without an
//! Android device or a running FIPS node.

use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::net::UnixStream;
use std::path::Path;
use std::time::Duration;

use serde_json::Value;

/// Upstream caps a request at 4 KiB; a `show_*` response is much larger, and
/// an unbounded read would let a wedged peer grow this process's memory.
const MAX_RESPONSE_BYTES: usize = 1_024 * 1_024;

/// Encode one control request. Only pure `show_*` queries are ever issued from
/// here: this bridge reads node state and never mutates it over the socket.
pub fn request_line(command: &str) -> String {
    format!("{{\"command\":\"{command}\"}}\n")
}

/// Decode one control response line into its `data` object.
pub fn parse_response(line: &str) -> Result<Value, String> {
    let mut value: Value = serde_json::from_str(line.trim())
        .map_err(|error| format!("malformed response: {error}"))?;
    match value.get("status").and_then(Value::as_str) {
        Some("ok") => Ok(value["data"].take()),
        Some("error") => Err(value
            .get("message")
            .and_then(Value::as_str)
            .unwrap_or("unspecified control error")
            .to_string()),
        other => Err(format!("unexpected control status: {other:?}")),
    }
}

/// Issue one `show_*` query and return its `data` object.
///
/// A fresh connection per query, which is what upstream's accept loop expects
/// (it serves one request and drops the stream). Both directions are bounded
/// by `timeout` so a wedged node can never block the caller indefinitely.
pub fn query(socket: &Path, command: &str, timeout: Duration) -> Result<Value, String> {
    let stream = UnixStream::connect(socket).map_err(|error| format!("connect: {error}"))?;
    stream
        .set_read_timeout(Some(timeout))
        .and_then(|()| stream.set_write_timeout(Some(timeout)))
        .map_err(|error| format!("timeout: {error}"))?;
    let mut writer = &stream;
    writer
        .write_all(request_line(command).as_bytes())
        .and_then(|()| writer.flush())
        .map_err(|error| format!("write: {error}"))?;

    let mut reader = BufReader::new((&stream).take(MAX_RESPONSE_BYTES as u64));
    let mut line = String::new();
    reader
        .read_line(&mut line)
        .map_err(|error| format!("read: {error}"))?;
    if line.is_empty() {
        return Err("control socket closed without a response".into());
    }
    parse_response(&line)
}

#[cfg(test)]
pub(crate) mod testing {
    use std::io::{BufRead, BufReader, Write};
    use std::os::unix::net::UnixListener;
    use std::path::{Path, PathBuf};
    use std::thread::JoinHandle;

    /// A one-shot stand-in for FIPS' control accept loop: serves `replies`
    /// in order, one per connection, then stops.
    pub struct FakeControl {
        pub path: PathBuf,
        handle: Option<JoinHandle<Vec<String>>>,
        _dir: tempdir::TempDir,
    }

    impl FakeControl {
        pub fn serve(replies: Vec<String>) -> Self {
            let dir = tempdir::TempDir::new();
            let path = dir.path().join("control.sock");
            let listener = UnixListener::bind(&path).expect("bind fake control socket");
            let handle = std::thread::spawn(move || {
                let mut requests = Vec::new();
                for reply in replies {
                    let Ok((stream, _)) = listener.accept() else {
                        break;
                    };
                    let mut line = String::new();
                    let mut reader = BufReader::new(&stream);
                    let _ = reader.read_line(&mut line);
                    requests.push(line);
                    let mut writer = &stream;
                    let _ = writer.write_all(reply.as_bytes());
                    let _ = writer.flush();
                }
                requests
            });
            Self {
                path,
                handle: Some(handle),
                _dir: dir,
            }
        }

        pub fn requests(&mut self) -> Vec<String> {
            self.handle.take().expect("joined once").join().unwrap()
        }

        pub fn socket(&self) -> &Path {
            &self.path
        }
    }

    /// A self-cleaning directory. `tempfile` is not a dependency of this
    /// crate and one is not worth adding for two tests.
    pub mod tempdir {
        use std::path::{Path, PathBuf};
        use std::sync::atomic::{AtomicU32, Ordering};

        static NEXT: AtomicU32 = AtomicU32::new(0);

        pub struct TempDir(PathBuf);

        impl TempDir {
            #[allow(clippy::new_without_default)]
            pub fn new() -> Self {
                let unique = NEXT.fetch_add(1, Ordering::Relaxed);
                let path = std::env::temp_dir().join(format!(
                    "cruxcoach-fips-test-{}-{unique}",
                    std::process::id()
                ));
                std::fs::create_dir_all(&path).expect("create temp dir");
                Self(path)
            }

            pub fn path(&self) -> &Path {
                &self.0
            }
        }

        impl Drop for TempDir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::testing::FakeControl;
    use super::*;

    #[test]
    fn a_query_sends_one_line_and_reads_one_response() {
        let mut fake = FakeControl::serve(vec![
            "{\"status\":\"ok\",\"data\":{\"peers\":[]}}\n".to_string(),
        ]);

        let data = query(fake.socket(), "show_peers", Duration::from_secs(2)).unwrap();
        assert_eq!(data, serde_json::json!({"peers": []}));
        assert_eq!(fake.requests(), vec!["{\"command\":\"show_peers\"}\n"]);
    }

    #[test]
    fn an_error_response_is_surfaced_not_silently_empty() {
        let mut fake = FakeControl::serve(vec![
            "{\"status\":\"error\",\"message\":\"unknown command: nope\"}\n".to_string(),
        ]);

        let error = query(fake.socket(), "nope", Duration::from_secs(2)).unwrap_err();
        assert!(error.contains("unknown command"), "{error}");
        let _ = fake.requests();
    }

    #[test]
    fn a_missing_socket_is_an_error_rather_than_a_hang() {
        let error = query(
            Path::new("/nonexistent/cruxcoach/control.sock"),
            "show_peers",
            Duration::from_millis(200),
        )
        .unwrap_err();
        assert!(error.starts_with("connect:"), "{error}");
    }

    #[test]
    fn malformed_json_is_rejected() {
        assert!(parse_response("not json").is_err());
        assert!(parse_response("{\"status\":\"weird\"}").is_err());
    }
}
