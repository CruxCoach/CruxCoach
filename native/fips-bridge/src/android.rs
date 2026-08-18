use std::net::Ipv6Addr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use fips::config::{BleConfig, TransportInstances};
use fips::transport::ble::android_io::BleRadioSlot;
use fips::upper::dns::DnsResolvedIdentity;
use fips::{Config, Node, PeerIdentity};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};

use crate::ble_diagnostics;
use crate::control;
use crate::peer_directory::{
    InboundHold, PeerDirectory, merge_rows, parse_peer_rows, parse_session_rows,
};
use crate::{decode_datagram, encode_datagram};

mod ble;

struct Running {
    stop_tx: Option<tokio::sync::oneshot::Sender<()>>,
    node_thread: Option<std::thread::JoinHandle<()>>,
    exited: std::sync::mpsc::Receiver<()>,
    inbound: Arc<Mutex<std::sync::mpsc::Receiver<Vec<u8>>>>,
    batch_tx: Option<std::sync::mpsc::SyncSender<OutboundBatch>>,
    batch_thread: Option<std::thread::JoinHandle<()>>,
    inbound_thread: Option<std::thread::JoinHandle<()>>,
    directory: Arc<PeerDirectory>,
    hold: Arc<InboundHold>,
    refresher: Arc<Refresher>,
    refresh_failures: Arc<AtomicU64>,
    slot: Arc<BleRadioSlot>,
    socket_path: PathBuf,
    source: Ipv6Addr,
    npub: String,
    alive: Arc<AtomicBool>,
}

struct OutboundBatch {
    identity: DnsResolvedIdentity,
    packets: Vec<Vec<u8>>,
}

static RUNNING: OnceLock<Mutex<Option<Running>>> = OnceLock::new();
static LIFECYCLE: Mutex<()> = Mutex::new(());
static STOPPING: AtomicBool = AtomicBool::new(false);
static SEND: Mutex<()> = Mutex::new(());
static NODE_GENERATION: AtomicU64 = AtomicU64::new(0);
fn running() -> &'static Mutex<Option<Running>> {
    RUNNING.get_or_init(|| Mutex::new(None))
}

const START_TIMEOUT: Duration = Duration::from_secs(30);
const STOP_TIMEOUT: Duration = Duration::from_secs(3);
/// Background cadence for the peer directory. Kotlin's own peer loop polls
/// every two seconds, so this is comfortably ahead of it while still costing
/// only two local socket round trips a second.
const DIRECTORY_REFRESH: Duration = Duration::from_secs(1);
/// How long the control socket may take to answer before the refresher gives
/// up on this round and tries again on the next tick.
const CONTROL_TIMEOUT: Duration = Duration::from_secs(2);
/// A datagram whose sender is not in the directory waits at most this long.
/// The refresher runs every second, so a peer that exists is normally named
/// well inside it; anything still unresolved after this is a peer the node
/// does not know, not a race.
const HOLD_TTL_MS: u64 = 6_000;
const HOLD_CAPACITY: usize = 32;
/// While something is held, no single blocking receive may outlast this, or a
/// quiet link would keep a resolvable frame parked for the caller's whole
/// timeout.
const HOLD_POLL: Duration = Duration::from_millis(200);
/// `sun_path` holds 108 bytes including the terminating NUL.
const MAX_UNIX_SOCKET_PATH: usize = 107;

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

/// Wakes the directory refresher out of turn.
///
/// A receive that cannot name its sender nudges this instead of issuing its
/// own `show_peers`: the query stays off the hot path, but a join does not
/// have to wait out a full tick either.
#[derive(Default)]
struct Refresher {
    state: Mutex<RefreshState>,
    signal: Condvar,
}

#[derive(Default)]
struct RefreshState {
    requested: bool,
    stopped: bool,
}

impl Refresher {
    fn request(&self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.requested = true;
        self.signal.notify_all();
    }

    fn stop(&self) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.stopped = true;
        self.signal.notify_all();
    }

    /// Sleep until the next tick, an out-of-turn request, or shutdown.
    /// Returns whether the refresher should keep running.
    fn wait(&self, tick: Duration) -> bool {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.stopped {
            return false;
        }
        if state.requested {
            state.requested = false;
            return true;
        }
        let (mut state, _) = self
            .signal
            .wait_timeout(state, tick)
            .unwrap_or_else(|e| e.into_inner());
        state.requested = false;
        !state.stopped
    }
}

/// FIPS defaults both crypto pools to available_parallelism(), which is a
/// server-friendly default but excessive for the small BoardCell frames on a
/// phone. Respect an explicit operator override, otherwise use one worker in
/// each direction just like the measured fips-android mobile profile.
fn apply_mobile_profile(config: &mut Config) {
    for key in ["FIPS_ENCRYPT_WORKERS", "FIPS_DECRYPT_WORKERS"] {
        if std::env::var_os(key).is_none() {
            // SAFETY: start/stop is serialized by LIFECYCLE and these variables
            // are read only while Node::start runs on the node thread.
            unsafe { std::env::set_var(key, "1") };
        }
    }
    // A five-second tick made a local BLE join wait for up to two complete
    // maintenance windows: once before the peer became routable and again
    // before the first BoardCell snapshot crossed the new route. Keep the
    // upstream one-second cadence; the worker limits above are the meaningful
    // mobile CPU bound, while this tick is also the join latency bound.
    config.node.tick_interval_secs = 1;
    config.node.heartbeat_interval_secs = 20;
    config.node.link_dead_timeout_secs = 60;
}

/// Choose this node generation's control socket path inside the app-private
/// directory Kotlin supplied, and sweep the previous generations' files.
///
/// A distinct name per generation is what keeps a stop that timed out from
/// blocking the next start: the detached node still owns its socket, and
/// upstream's `bind` refuses a path something is still listening on. Stale
/// files from a killed process are removed the same way upstream does it —
/// by proving nobody answers first.
fn prepare_control_socket(dir: &Path) -> Result<PathBuf, String> {
    std::fs::create_dir_all(dir).map_err(|error| format!("control dir: {error}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        // App-private. FIPS relaxes the socket itself to 0770 for its "fips"
        // group convention, which has no meaning on Android — the directory is
        // what actually keeps other UIDs out.
        let _ = std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700));
    }
    let generation = NODE_GENERATION.fetch_add(1, Ordering::Relaxed);
    let socket_path = dir.join(format!("ctl-{generation}.sock"));
    // `sun_path` is 108 bytes including its NUL. The published feature package
    // leaves ~25 bytes of headroom, but a local debug build derives its
    // applicationId from the branch name and can in principle run out. Refuse
    // loudly: without the control socket an inbound datagram cannot be
    // attributed to a sender, and starting anyway would look healthy while
    // silently dropping BoardCell state.
    if socket_path.as_os_str().len() > MAX_UNIX_SOCKET_PATH {
        return Err(format!(
            "control socket path is {} bytes, over the {MAX_UNIX_SOCKET_PATH}-byte platform limit",
            socket_path.as_os_str().len(),
        ));
    }

    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path == socket_path {
                continue;
            }
            let is_control_socket = path
                .file_name()
                .and_then(|name| name.to_str())
                .is_some_and(|name| name.starts_with("ctl-") && name.ends_with(".sock"));
            if !is_control_socket {
                continue;
            }
            if std::os::unix::net::UnixStream::connect(&path).is_err() {
                let _ = std::fs::remove_file(&path);
            }
        }
    }
    Ok(socket_path)
}

fn refresh_directory(socket: &Path, directory: &PeerDirectory) -> Result<(), String> {
    let peers = control::query(socket, "show_peers", CONTROL_TIMEOUT)?;
    // Direct peers alone would leave a multi-hop BoardCell member unnameable,
    // and an unnameable sender is a dropped delta. Sessions are end-to-end, so
    // they cover exactly that gap.
    let sessions = control::query(socket, "show_sessions", CONTROL_TIMEOUT)
        .unwrap_or_else(|_| serde_json::Value::Null);
    directory.replace(merge_rows(
        &parse_peer_rows(&peers),
        &parse_session_rows(&sessions),
        |npub| {
            PeerIdentity::from_npub(npub)
                .ok()
                .map(|peer| peer.address().to_ipv6())
        },
    ));
    Ok(())
}

fn stop_running(mut value: Running) {
    STOPPING.store(true, Ordering::Release);
    // Stop accepting whole application messages first. The pump drains the
    // bounded queue while the node is alive, or observes the node channels
    // closing during shutdown.
    drop(value.batch_tx.take());
    // Signalled, not joined: the refresher may be parked in a control-socket
    // read, and a Kotlin lifecycle callback must not wait that out. It owns
    // nothing but its own Arcs and leaves on its next wakeup.
    value.refresher.stop();
    // Retract this node's radio seat before the node goes away. A newer node
    // that already claimed it keeps it; this call then owns nothing.
    ble::detach_radio_slot(&value.slot);
    if let Some(stop) = value.stop_tx.take() {
        let _ = stop.send(());
    }
    if value.exited.recv_timeout(STOP_TIMEOUT).is_ok() {
        if let Some(thread) = value.node_thread.take() {
            let _ = thread.join();
        }
        if let Some(thread) = value.batch_thread.take() {
            let _ = thread.join();
        }
        if let Some(thread) = value.inbound_thread.take() {
            let _ = thread.join();
        }
        // Only a node that has actually exited has released its socket.
        let _ = std::fs::remove_file(&value.socket_path);
        STOPPING.store(false, Ordering::Release);
    } else {
        // Dropping JoinHandle detaches rather than blocking the Android caller.
        // The thread still owns all node state and tears it down independently
        // — including its control socket, whose per-generation name means the
        // next start does not collide with it.
        let _ = value.node_thread.take();
    }
}

fn jstring(env: &mut JNIEnv, input: JString) -> Option<String> {
    env.get_string(&input).ok().map(Into::into)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_start(
    mut env: JNIEnv,
    _class: JClass,
    secret: JString,
    max_direct_connections: jint,
    control_dir: JString,
) -> jboolean {
    let Some(secret) = jstring(&mut env, secret) else {
        return 0;
    };
    let Some(control_dir) = jstring(&mut env, control_dir) else {
        return 0;
    };
    let _lifecycle = LIFECYCLE.lock().unwrap();
    // A timed-out stop deliberately leaves the old node thread detached. Do
    // not overlap a second FIPS node with it; the exiting thread clears this
    // flag and the Kotlin liveness reconciler retries on its next pass.
    if STOPPING.load(Ordering::Acquire) {
        return 0;
    }
    if running().lock().unwrap().is_some() {
        return 1;
    }
    let result = (|| -> Result<Running, String> {
        let socket_path = prepare_control_socket(Path::new(&control_dir))?;
        let mut config = Config::new();
        apply_mobile_profile(&mut config);
        config.node.identity.nsec = Some(secret);
        config.node.identity.persistent = false; // Android owns encrypted persistence.
        config.tun.enabled = false;
        config.dns.enabled = false;
        // The control socket is now load-bearing rather than an operator
        // convenience: `ControlReadHandle` is `pub(crate)` upstream, so this is
        // the supported way to read the peer table an inbound datagram has to
        // be attributed to. It lives in app-private storage.
        config.node.control.enabled = true;
        config.node.control.socket_path = socket_path.to_string_lossy().into_owned();
        config.transports.ble = TransportInstances::Single(BleConfig {
            adapter: Some("ble0".into()),
            max_connections: Some(max_direct_connections.clamp(1, 7) as usize),
            // Kotlin cancels an OEM BluetoothSocket connect after 10 seconds.
            // Keep the native waiter slightly longer so it observes that
            // explicit failure instead of leaving an orphaned 30-second dial.
            connect_timeout_ms: Some(12_000),
            auto_connect: Some(true),
            ..Default::default()
        });
        let mut node = Node::new(config).map_err(|e| e.to_string())?;
        let npub = node.npub();
        let source = node.identity().address().to_ipv6();
        // Node-owned radio slot: upstream deleted the process-global
        // `set_android_ble_bridge`. Publishing it here hands the radio Kotlin
        // already built to this node, and takes it away from any predecessor.
        let slot = node.enable_app_owned_ble_radio();
        ble::attach_radio_slot(Arc::clone(&slot));
        let (outbound, native_inbound) = node.enable_app_owned_tun();
        // FIPS' app-owned inbound seam is an unbounded std channel. Drain it
        // continuously into a bounded queue so a paused Kotlin consumer cannot
        // grow native memory without limit. Overflow drops datagrams; the
        // BoardCell outbox/anti-entropy layer repairs missed state.
        let (bounded_inbound_tx, inbound) = std::sync::mpsc::sync_channel(256);
        let inbound_thread = std::thread::Builder::new()
            .name("crux-fips-app-rx".into())
            .spawn(move || {
                while let Ok(packet) = native_inbound.recv() {
                    match bounded_inbound_tx.try_send(packet) {
                        Ok(()) | Err(std::sync::mpsc::TrySendError::Full(_)) => {}
                        Err(std::sync::mpsc::TrySendError::Disconnected(_)) => break,
                    }
                }
            })
            .map_err(|error| format!("spawn application receiver: {error}"))?;
        // Restores the seam `Node::enable_app_owned_dns` used to provide. An
        // outbound packet is routed by an identity-cache lookup on the
        // destination's truncated address hash, so a peer whose key the node
        // has never seen gets ICMPv6 unreachable on the first send. CruxCoach
        // knows the npub already and announces it here instead of resolving a
        // name for the cache side effect.
        let identities = node.enable_app_owned_identities();
        let (batch_tx, batch_rx) = std::sync::mpsc::sync_channel::<OutboundBatch>(8);
        let pump_outbound = outbound.clone();
        let pump_identities = identities.clone();
        let batch_thread = std::thread::Builder::new()
            .name("crux-fips-app-tx".into())
            .spawn(move || {
                'messages: while let Ok(batch) = batch_rx.recv() {
                    if pump_identities.blocking_send(batch.identity).is_err() {
                        break;
                    }
                    for packet in batch.packets {
                        if pump_outbound.blocking_send(packet).is_err() {
                            break 'messages;
                        }
                    }
                }
            })
            .map_err(|error| format!("spawn application sender: {error}"))?;
        let (ready_tx, ready_rx) = std::sync::mpsc::channel::<Result<(), String>>();
        let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
        let (exit_tx, exit_rx) = std::sync::mpsc::channel();
        let alive = Arc::new(AtomicBool::new(false));
        let thread_alive = Arc::clone(&alive);
        let node_thread = std::thread::Builder::new()
            .name("crux-fips-node".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(runtime) => runtime,
                    Err(error) => {
                        let _ = ready_tx.send(Err(format!("tokio runtime: {error}")));
                        let _ = exit_tx.send(());
                        return;
                    }
                };
                let running_alive = Arc::clone(&thread_alive);
                runtime.block_on(async move {
                    if let Err(error) = node.start().await {
                        let _ = ready_tx.send(Err(format!("node start: {error}")));
                        return;
                    }
                    running_alive.store(true, Ordering::Release);
                    let _ = ready_tx.send(Ok(()));
                    tokio::select! {
                        _ = stop_rx => {}
                        _ = node.run_rx_loop() => {}
                    }
                });
                runtime.shutdown_timeout(Duration::from_secs(2));
                thread_alive.store(false, Ordering::Release);
                STOPPING.store(false, Ordering::Release);
                let _ = exit_tx.send(());
            })
            .map_err(|error| format!("spawn node thread: {error}"))?;
        match ready_rx.recv_timeout(START_TIMEOUT) {
            Ok(Ok(())) => {}
            Ok(Err(error)) => {
                let _ = node_thread.join();
                // The node never came up, so its slot must not keep the radio.
                ble::detach_radio_slot(&slot);
                let _ = std::fs::remove_file(&socket_path);
                return Err(error);
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                STOPPING.store(true, Ordering::Release);
                let _ = stop_tx.send(());
                ble::detach_radio_slot(&slot);
                // The node thread owns all state and observes the already-sent
                // stop signal after a late start; never extend the JNI timeout
                // with an unbounded join.
                drop(node_thread);
                return Err("node start timed out".into());
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                let _ = node_thread.join();
                ble::detach_radio_slot(&slot);
                let _ = std::fs::remove_file(&socket_path);
                return Err("node thread died during start".into());
            }
        }
        let directory = Arc::new(PeerDirectory::new());
        let refresher = Arc::new(Refresher::default());
        let refresh_failures = Arc::new(AtomicU64::new(0));
        {
            let directory = Arc::clone(&directory);
            let refresher = Arc::clone(&refresher);
            let refresh_failures = Arc::clone(&refresh_failures);
            let socket_path = socket_path.clone();
            let directory_thread = std::thread::Builder::new()
                .name("crux-fips-peers".into())
                .spawn(move || {
                    while refresher.wait(DIRECTORY_REFRESH) {
                        if refresh_directory(&socket_path, &directory).is_err() {
                            // Counted rather than logged: a refresh that keeps
                            // failing is why senders go unresolved, and the
                            // diagnostics surface has to be able to say so.
                            refresh_failures.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                })
                .map_err(|error| format!("spawn peer directory: {error}"))?;
            drop(directory_thread);
        };
        Ok(Running {
            stop_tx: Some(stop_tx),
            node_thread: Some(node_thread),
            exited: exit_rx,
            inbound: Arc::new(Mutex::new(inbound)),
            batch_tx: Some(batch_tx),
            batch_thread: Some(batch_thread),
            inbound_thread: Some(inbound_thread),
            directory,
            hold: Arc::new(InboundHold::new(HOLD_CAPACITY, HOLD_TTL_MS)),
            refresher,
            refresh_failures,
            slot,
            socket_path,
            source,
            npub,
            alive,
        })
    })();
    match result {
        Ok(value) => {
            *running().lock().unwrap() = Some(value);
            1
        }
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_isAlive(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    jboolean::from(
        running()
            .lock()
            .unwrap()
            .as_ref()
            .is_some_and(|value| value.alive.load(Ordering::Acquire)),
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_stop(
    _env: JNIEnv,
    _class: JClass,
) {
    let _lifecycle = LIFECYCLE.lock().unwrap();
    let value = running().lock().unwrap().take();
    if let Some(value) = value {
        stop_running(value);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_npub(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = running()
        .lock()
        .unwrap()
        .as_ref()
        .map(|r| r.npub.clone())
        .unwrap_or_default();
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Atomically admits a complete fragmented application message. `packed`
/// contains repeated big-endian `[u32 length][frame]` records. A bounded queue
/// holds whole batches; its worker may then await TUN capacity without ever
/// exposing a partially-admitted message to the caller.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_sendBatch(
    mut env: JNIEnv,
    _class: JClass,
    destination_npub: JString,
    packed: JByteArray,
) -> jboolean {
    let Some(npub) = jstring(&mut env, destination_npub) else {
        return 0;
    };
    let Ok(bytes) = env.convert_byte_array(packed) else {
        return 0;
    };
    let Ok(peer) = PeerIdentity::from_npub(&npub) else {
        return 0;
    };
    let mut cursor = 0usize;
    let mut frames = Vec::new();
    while cursor < bytes.len() {
        if bytes.len() - cursor < 4 {
            return 0;
        }
        let length = u32::from_be_bytes(bytes[cursor..cursor + 4].try_into().unwrap()) as usize;
        cursor += 4;
        if length == 0 || length > crate::MAX_APP_PAYLOAD || bytes.len() - cursor < length {
            return 0;
        }
        frames.push(&bytes[cursor..cursor + length]);
        cursor += length;
    }
    if frames.is_empty() {
        return 0;
    }
    let state = running()
        .lock()
        .unwrap()
        .as_ref()
        .and_then(|value| value.batch_tx.as_ref().map(|tx| (tx.clone(), value.source)));
    let Some((batch_tx, source)) = state else {
        return 0;
    };
    let Some(packets) = frames
        .iter()
        .map(|frame| encode_datagram(source, peer.address().to_ipv6(), frame))
        .collect::<Option<Vec<_>>>()
    else {
        return 0;
    };
    let _send = SEND.lock().unwrap();
    jboolean::from(
        batch_tx
            .try_send(OutboundBatch {
                identity: DnsResolvedIdentity {
                    node_addr: *peer.node_addr(),
                    pubkey: peer.pubkey_full(),
                },
                packets,
            })
            .is_ok(),
    )
}

fn frame_received(npub: &str, payload: &[u8]) -> Option<Vec<u8>> {
    if npub.is_empty() || npub.len() > u16::MAX as usize {
        return None;
    }
    let mut framed = Vec::with_capacity(2 + npub.len() + payload.len());
    framed.extend_from_slice(&(npub.len() as u16).to_be_bytes());
    framed.extend_from_slice(npub.as_bytes());
    framed.extend_from_slice(payload);
    Some(framed)
}

/// Pull one attributable application datagram, or `None` on timeout.
///
/// Kept out of the JNI shim so the control flow is readable: hold-first, then
/// the wire, and never a `show_peers` round trip on this path.
fn next_framed_message(
    inbound: &Mutex<std::sync::mpsc::Receiver<Vec<u8>>>,
    directory: &PeerDirectory,
    hold: &InboundHold,
    refresher: &Refresher,
    overall: Option<Duration>,
) -> Option<Vec<u8>> {
    let started = std::time::Instant::now();
    loop {
        // Anything the directory can now name goes out first: it has already
        // waited longer than whatever is still on the wire.
        if let Some((npub, payload)) = hold.take_resolved(now_ms(), |s| directory.npub_for(s))
            && let Some(framed) = frame_received(&npub, &payload)
        {
            return Some(framed);
        }

        let remaining = match overall {
            Some(total) => match total.checked_sub(started.elapsed()) {
                Some(left) if !left.is_zero() => Some(left),
                _ => return None,
            },
            None => None,
        };
        // Never block past the hold's re-check cadence while something is
        // parked waiting for a refresh that is already in flight.
        let slice = match (remaining, hold.is_empty()) {
            (Some(left), false) => Some(left.min(HOLD_POLL)),
            (Some(left), true) => Some(left),
            (None, false) => Some(HOLD_POLL),
            (None, true) => None,
        };
        let received = {
            let queue = inbound.lock().unwrap_or_else(|e| e.into_inner());
            match slice {
                Some(slice) => queue.recv_timeout(slice),
                None => queue
                    .recv()
                    .map_err(|_| std::sync::mpsc::RecvTimeoutError::Disconnected),
            }
        };
        let packet = match received {
            Ok(packet) => packet,
            // The node is gone. Returning at once matters: a disconnected
            // channel answers instantly, so treating it as an expired slice
            // would spin this loop for the caller's whole timeout.
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => return None,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue,
        };
        let Some(datagram) = decode_datagram(&packet) else {
            continue;
        };
        match directory.npub_for(&datagram.source) {
            Some(npub) => {
                if let Some(framed) = frame_received(&npub, &datagram.payload) {
                    return Some(framed);
                }
            }
            None => {
                // Do not drop: on a fresh join the first BoardCell frame
                // regularly beats the peer table that names its sender.
                hold.push(datagram.source, datagram.payload, now_ms());
                refresher.request();
            }
        }
    }
}

/// Returns `[u16 npub length][UTF-8 npub][application bytes]`, or an empty array.
///
/// The sender is named from the background peer directory rather than a
/// `show_peers` query per packet. A datagram that arrives before its sender is
/// in the directory — the ordinary case on a fresh join — is held and retried
/// while this call's own timeout runs, instead of being dropped.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_receive(
    env: JNIEnv,
    _class: JClass,
    timeout_ms: jint,
) -> jni::sys::jbyteArray {
    let state = running().lock().unwrap().as_ref().map(|value| {
        (
            Arc::clone(&value.inbound),
            Arc::clone(&value.directory),
            Arc::clone(&value.hold),
            Arc::clone(&value.refresher),
        )
    });
    let framed = state.and_then(|(inbound, directory, hold, refresher)| {
        let overall = (timeout_ms >= 0).then(|| Duration::from_millis(timeout_ms as u64));
        next_framed_message(&inbound, &directory, &hold, &refresher, overall)
    });
    env.byte_array_from_slice(framed.as_deref().unwrap_or(&[]))
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// One tab-separated peer per line: npub, connected, transport, lastSeenMs.
///
/// Rendered from the background directory, so this is a lock-and-copy rather
/// than a control round trip.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_peers(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = running()
        .lock()
        .unwrap()
        .as_ref()
        .map(|value| value.directory.render_lines())
        .unwrap_or_default();
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// One tab-separated aggregate counter per line: instance, counter, value.
///
/// This replaces the per-peer `ble_attempt_log` upstream deleted. It is
/// deliberately *only* the aggregate layer — per-peer attempts, with their
/// addresses and outcomes, are traced by the Kotlin radio that owns them.
/// Reconstructing per-peer history from these counters would be a fabrication.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleTransportCounters(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let state = running().lock().unwrap().as_ref().map(|value| {
        (
            value.socket_path.clone(),
            Arc::clone(&value.hold),
            Arc::clone(&value.refresh_failures),
        )
    });
    let text = match state {
        Some((socket_path, hold, refresh_failures)) => {
            let mut lines = control::query(&socket_path, "show_transports", CONTROL_TIMEOUT)
                .map(|data| ble_diagnostics::ble_counter_lines(&data))
                .unwrap_or_default();
            let (evicted, expired, resolved) = hold.counters();
            lines.extend(ble_diagnostics::hold_counter_lines(
                evicted,
                expired,
                resolved,
                refresh_failures.load(Ordering::Relaxed),
            ));
            lines.join("\n")
        }
        None => String::new(),
    };
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleBridgeNew(
    env: JNIEnv,
    class: JClass,
    radio: JObject,
) -> jlong {
    ble::bridge_new(env, class, radio)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleBridgeFree(
    env: JNIEnv,
    class: JClass,
    handle: jlong,
) {
    ble::bridge_free(env, class, handle)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleDeliverInbound(
    env: JNIEnv,
    class: JClass,
    h: jlong,
    addr: JString,
    send: jint,
    recv: jint,
) -> jlong {
    ble::deliver_inbound(env, class, h, addr, send, recv)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleDeliverConnectResult(
    env: JNIEnv,
    class: JClass,
    h: jlong,
    id: jlong,
    ok: jboolean,
    addr: JString,
    send: jint,
    recv: jint,
) -> jlong {
    ble::deliver_connect_result(env, class, h, id, ok, addr, send, recv)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleDeliverScan(
    env: JNIEnv,
    class: JClass,
    h: jlong,
    addr: JString,
    psm: jint,
    rssi: jint,
) {
    ble::deliver_scan(env, class, h, addr, psm, rssi)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleChannelDeliverRecv(
    env: JNIEnv,
    class: JClass,
    h: jlong,
    id: jlong,
    data: JByteArray,
    len: jint,
) -> jboolean {
    ble::channel_recv(env, class, h, id, data, len)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleChannelClosed(
    env: JNIEnv,
    class: JClass,
    h: jlong,
    id: jlong,
) {
    ble::channel_closed(env, class, h, id)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleChannelNextSend(
    env: JNIEnv,
    class: JClass,
    h: jlong,
    id: jlong,
    out: JByteArray,
    timeout: jint,
) -> jint {
    ble::channel_next_send(env, class, h, id, out, timeout)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mobile_profile_keeps_join_maintenance_responsive() {
        let mut config = Config::default();
        apply_mobile_profile(&mut config);

        assert_eq!(config.node.tick_interval_secs, 1);
        assert_eq!(config.node.heartbeat_interval_secs, 20);
        assert_eq!(config.node.link_dead_timeout_secs, 60);
    }
}
