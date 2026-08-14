use std::net::Ipv6Addr;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

use fips::config::{BleConfig, TransportInstances};
use fips::upper::dns::DnsResolvedIdentity;
use fips::{Config, Node, PeerIdentity};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use tokio::runtime::Runtime;
use tokio::sync::mpsc;

use crate::{decode_datagram, encode_datagram};

mod ble;

struct Running {
    runtime: Runtime,
    task: tokio::task::JoinHandle<()>,
    outbound: mpsc::Sender<Vec<u8>>,
    inbound: std::sync::mpsc::Receiver<Vec<u8>>,
    identities: mpsc::Sender<DnsResolvedIdentity>,
    read: fips::control::read_handle::ControlReadHandle,
    source: Ipv6Addr,
    npub: String,
}

static RUNNING: OnceLock<Mutex<Option<Running>>> = OnceLock::new();
fn running() -> &'static Mutex<Option<Running>> {
    RUNNING.get_or_init(|| Mutex::new(None))
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
    let mut guard = running().lock().unwrap();
    if guard.is_some() {
        return 1;
    }
    let result = (|| -> Result<Running, String> {
        let mut config = Config::new();
        config.node.identity.nsec = Some(secret);
        config.node.identity.persistent = false; // Android owns encrypted persistence.
        config.tun.enabled = false;
        config.dns.enabled = false;
        config.node.control.enabled = false;
        config.transports.ble = TransportInstances::Single(BleConfig {
            adapter: Some("ble0".into()),
            max_connections: Some(max_direct_connections.clamp(1, 7) as usize),
            auto_connect: Some(true),
            ..Default::default()
        });
        let mut node = Node::new(config).map_err(|e| e.to_string())?;
        let npub = node.npub();
        let source = node.identity().address().to_ipv6();
        let read = node.control_read_handle();
        let (outbound, inbound) = node.enable_app_owned_tun();
        let identities = node.enable_app_owned_dns();
        let runtime = Runtime::new().map_err(|e| e.to_string())?;
        let task = runtime.spawn(async move {
            if node.start().await.is_ok() {
                let _ = node.run_rx_loop().await;
            }
        });
        Ok(Running {
            runtime,
            task,
            outbound,
            inbound,
            identities,
            read,
            source,
            npub,
        })
    })();
    match result {
        Ok(value) => {
            *guard = Some(value);
            1
        }
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_stop(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(value) = running().lock().unwrap().take() {
        value.task.abort();
        value.runtime.shutdown_timeout(Duration::from_secs(2));
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_send(
    mut env: JNIEnv,
    _class: JClass,
    destination_npub: JString,
    bytes: JByteArray,
) -> jboolean {
    let Some(npub) = jstring(&mut env, destination_npub) else {
        return 0;
    };
    let Ok(payload) = env.convert_byte_array(bytes) else {
        return 0;
    };
    let Ok(peer) = PeerIdentity::from_npub(&npub) else {
        return 0;
    };
    let guard = running().lock().unwrap();
    let Some(value) = guard.as_ref() else {
        return 0;
    };
    if value
        .identities
        .try_send(DnsResolvedIdentity {
            node_addr: *peer.node_addr(),
            pubkey: peer.pubkey_full(),
        })
        .is_err()
    {
        return 0;
    }
    let Some(packet) = encode_datagram(value.source, peer.address().to_ipv6(), &payload) else {
        return 0;
    };
    jboolean::from(value.outbound.try_send(packet).is_ok())
}

/// Returns `[u16 npub length][UTF-8 npub][application bytes]`, or an empty array.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_fips_NativeFips_receive(
    env: JNIEnv,
    _class: JClass,
    timeout_ms: jint,
) -> jni::sys::jbyteArray {
    let guard = running().lock().unwrap();
    let Some(value) = guard.as_ref() else {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    };
    let Ok(packet) = value
        .inbound
        .recv_timeout(Duration::from_millis(timeout_ms.max(0) as u64))
    else {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    };
    let Some(datagram) = decode_datagram(&packet) else {
        return env.byte_array_from_slice(&[]).unwrap().into_raw();
    };
    let source_npub = value
        .read
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
    let text = running()
        .lock()
        .unwrap()
        .as_ref()
        .map(|r| {
            r.read
                .peer_views()
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
