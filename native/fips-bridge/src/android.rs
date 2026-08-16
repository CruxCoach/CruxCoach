use std::net::Ipv6Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use fips::config::{BleConfig, TransportInstances};
use fips::transport::ble::attempts::ble_attempt_log;
use fips::upper::dns::DnsResolvedIdentity;
use fips::{Config, Node, PeerIdentity};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};

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
    read: fips::control::read_handle::ControlReadHandle,
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
fn running() -> &'static Mutex<Option<Running>> {
    RUNNING.get_or_init(|| Mutex::new(None))
}

const START_TIMEOUT: Duration = Duration::from_secs(30);
const STOP_TIMEOUT: Duration = Duration::from_secs(3);

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
    config.node.tick_interval_secs = 5;
    config.node.heartbeat_interval_secs = 20;
    config.node.link_dead_timeout_secs = 60;
}

fn stop_running(mut value: Running) {
    STOPPING.store(true, Ordering::Release);
    // Stop accepting whole application messages first. The pump drains the
    // bounded queue while the node is alive, or observes the node channels
    // closing during shutdown.
    drop(value.batch_tx.take());
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
        STOPPING.store(false, Ordering::Release);
    } else {
        // Dropping JoinHandle detaches rather than blocking the Android caller.
        // The thread still owns all node state and tears it down independently.
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
) -> jboolean {
    let Some(secret) = jstring(&mut env, secret) else {
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
        let mut config = Config::new();
        apply_mobile_profile(&mut config);
        config.node.identity.nsec = Some(secret);
        config.node.identity.persistent = false; // Android owns encrypted persistence.
        config.tun.enabled = false;
        config.dns.enabled = false;
        config.node.control.enabled = false;
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
        let read = node.control_read_handle();
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
        let identities = node.enable_app_owned_dns();
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
                return Err(error);
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                STOPPING.store(true, Ordering::Release);
                let _ = stop_tx.send(());
                // The node thread owns all state and observes the already-sent
                // stop signal after a late start; never extend the JNI timeout
                // with an unbounded join.
                drop(node_thread);
                return Err("node start timed out".into());
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                let _ = node_thread.join();
                return Err("node thread died during start".into());
            }
        }
        Ok(Running {
            stop_tx: Some(stop_tx),
            node_thread: Some(node_thread),
            exited: exit_rx,
            inbound: Arc::new(Mutex::new(inbound)),
            batch_tx: Some(batch_tx),
            batch_thread: Some(batch_thread),
            inbound_thread: Some(inbound_thread),
            read,
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
    let state = running().lock().unwrap().as_ref().and_then(|value| {
        value.batch_tx.as_ref().map(|tx| (tx.clone(), value.source))
    });
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
    jboolean::from(batch_tx.try_send(OutboundBatch {
        identity: DnsResolvedIdentity {
            node_addr: *peer.node_addr(),
            pubkey: peer.pubkey_full(),
        },
        packets,
    }).is_ok())
}

/// Returns `[u16 npub length][UTF-8 npub][application bytes]`, or an empty array.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_receive(
    env: JNIEnv,
    _class: JClass,
    timeout_ms: jint,
) -> jni::sys::jbyteArray {
    let inbound = running()
        .lock()
        .unwrap()
        .as_ref()
        .map(|value| Arc::clone(&value.inbound));
    let Some(inbound) = inbound else {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    };
    let received = if timeout_ms < 0 {
        inbound.lock().unwrap().recv().ok()
    } else {
        inbound
            .lock()
            .unwrap()
            .recv_timeout(Duration::from_millis(timeout_ms as u64))
            .ok()
    };
    let Some(packet) = received else {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    };
    let Some(datagram) = decode_datagram(&packet) else {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    };
    let read = running()
        .lock()
        .unwrap()
        .as_ref()
        .map(|value| value.read.clone());
    let Some(read) = read else {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    };
    let source_npub = read
        .peer_views()
        .into_iter()
        .find_map(|p| {
            PeerIdentity::from_npub(&p.npub)
                .ok()
                .filter(|identity| identity.address().to_ipv6() == datagram.source)
                .map(|_| p.npub)
        })
        .unwrap_or_default();
    if source_npub.is_empty() || source_npub.len() > u16::MAX as usize {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    }
    let mut framed = Vec::with_capacity(2 + source_npub.len() + datagram.payload.len());
    framed.extend_from_slice(&(source_npub.len() as u16).to_be_bytes());
    framed.extend_from_slice(source_npub.as_bytes());
    framed.extend_from_slice(&datagram.payload);
    env.byte_array_from_slice(&framed).unwrap().into_raw()
}

/// One tab-separated peer per line: npub, connected, transport, lastSeenMs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_peers(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let read = running().lock().unwrap().as_ref().map(|r| r.read.clone());
    let text = read
        .map(|read| {
            read.peer_views()
                .into_iter()
                .map(|p| {
                    format!(
                        "{}\t{}\t{}\t{}",
                        p.npub, p.connected, p.transport, p.last_seen_ms
                    )
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default();
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// One tab-separated resolved BLE attempt per line:
/// timestamp, address, node, role, discovery-ms, outcome, send-failures.
/// This exposes FIPS' bounded diagnostic ring to Android logcat without
/// enabling verbose native logging or leaking any private key material.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_bleAttempts(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = ble_attempt_log()
        .snapshot()
        .into_iter()
        .flat_map(|peer| {
            let send_failures = peer.send_failures;
            peer.attempts.into_iter().map(move |attempt| {
                format!(
                    "{}\t{}\t{}\t{}\t{}\t{}\t{}",
                    attempt.at_ms,
                    attempt.ble_addr,
                    attempt.node_addr_hex,
                    attempt.role.as_str(),
                    attempt.discovery_ms,
                    attempt.outcome.as_str(),
                    send_failures,
                )
            })
        })
        .collect::<Vec<_>>()
        .join("\n");
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
