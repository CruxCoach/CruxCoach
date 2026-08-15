use std::collections::HashMap;
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use fips::transport::ble::addr::BleAddr;
use fips::transport::ble::android_io::{AndroidBleBridge, AndroidRadio, set_android_ble_bridge};
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong};
use jni::{JNIEnv, JavaVM};

static VM: OnceLock<JavaVM> = OnceLock::new();
static BRIDGES: OnceLock<Mutex<HashMap<i64, Arc<AndroidBleBridge>>>> = OnceLock::new();
static NEXT_BRIDGE: AtomicI64 = AtomicI64::new(1);

fn bridges() -> &'static Mutex<HashMap<i64, Arc<AndroidBleBridge>>> {
    BRIDGES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn with_env<R>(default: R, f: impl FnOnce(&mut JNIEnv) -> R) -> R {
    let Some(vm) = VM.get() else { return default };
    match vm.attach_current_thread() {
        Ok(mut env) => f(&mut env),
        Err(_) => default,
    }
}

struct KotlinRadio {
    radio: GlobalRef,
}

impl AndroidRadio for KotlinRadio {
    fn listen(&self) -> u16 {
        with_env(0, |env| {
            env.call_method(&self.radio, "listen", "()I", &[])
                .and_then(|v| v.i())
                .map(|v| v as u16)
                .unwrap_or(0)
        })
    }
    fn connect(&self, connect_id: i64, addr: &BleAddr, psm: u16) {
        let address = addr.to_string_repr();
        with_env((), |env| {
            if let Ok(jaddr) = env.new_string(address) {
                let _ = env.call_method(
                    &self.radio,
                    "connect",
                    "(JLjava/lang/String;I)V",
                    &[
                        JValue::Long(connect_id),
                        JValue::Object(&jaddr),
                        JValue::Int(psm as i32),
                    ],
                );
            }
        });
    }
    fn start_advertising(&self, psm: u16) {
        with_env((), |env| {
            let _ = env.call_method(
                &self.radio,
                "startAdvertising",
                "(I)V",
                &[JValue::Int(psm as i32)],
            );
        });
    }
    fn stop_advertising(&self) {
        with_env((), |env| {
            let _ = env.call_method(&self.radio, "stopAdvertising", "()V", &[]);
        });
    }
    fn start_scanning(&self) {
        with_env((), |env| {
            let _ = env.call_method(&self.radio, "startScanning", "()V", &[]);
        });
    }
    fn stop_scanning(&self) {
        with_env((), |env| {
            let _ = env.call_method(&self.radio, "stopScanning", "()V", &[]);
        });
    }
    fn close_channel(&self, id: i64) {
        with_env((), |env| {
            let _ = env.call_method(&self.radio, "closeChannel", "(J)V", &[JValue::Long(id)]);
        });
    }
}

/// Clone a strong reference while the registry lock is held. JNI callers then
/// operate without the registry lock, and bridge_free merely prevents new
/// callers from acquiring the retired generation. This removes the raw-pointer
/// use-after-free race with late Kotlin reader/writer callbacks.
fn bridge(handle: jlong) -> Option<Arc<AndroidBleBridge>> {
    bridges().lock().unwrap().get(&handle).cloned()
}

fn addr(env: &mut JNIEnv, value: &JString) -> Option<BleAddr> {
    let text: String = env.get_string(value).ok()?.into();
    BleAddr::parse(&text).ok()
}

pub fn bridge_new(env: JNIEnv, _class: JClass, radio: JObject) -> jlong {
    if let Ok(vm) = env.get_java_vm() {
        let _ = VM.set(vm);
    }
    let Ok(global) = env.new_global_ref(radio) else {
        return 0;
    };
    let value = AndroidBleBridge::new(Arc::new(KotlinRadio { radio: global }));
    set_android_ble_bridge(Arc::clone(&value));
    let handle = NEXT_BRIDGE.fetch_add(1, Ordering::Relaxed).max(1);
    bridges().lock().unwrap().insert(handle, value);
    handle
}

pub fn bridge_free(_env: JNIEnv, _class: JClass, handle: jlong) {
    if handle != 0 {
        bridges().lock().unwrap().remove(&handle);
    }
}

pub fn deliver_inbound(
    mut env: JNIEnv,
    _class: JClass,
    h: jlong,
    value: JString,
    send: jint,
    recv: jint,
) -> jlong {
    let Some(b) = bridge(h) else {
        return 0;
    };
    let Some(a) = addr(&mut env, &value) else {
        return 0;
    };
    b.deliver_inbound(a, send.max(0) as u16, recv.max(0) as u16)
}

pub fn deliver_connect_result(
    mut env: JNIEnv,
    _class: JClass,
    h: jlong,
    id: jlong,
    ok: jboolean,
    value: JString,
    send: jint,
    recv: jint,
) -> jlong {
    let Some(b) = bridge(h) else {
        return 0;
    };
    let a = addr(&mut env, &value).unwrap_or(BleAddr {
        adapter: "ble0".into(),
        device: [0; 6],
    });
    b.deliver_connect_result(id, ok != 0, a, send.max(0) as u16, recv.max(0) as u16)
}

pub fn deliver_scan(
    mut env: JNIEnv,
    _class: JClass,
    h: jlong,
    value: JString,
    psm: jint,
    rssi: jint,
) {
    let Some(b) = bridge(h) else {
        return;
    };
    if let Some(a) = addr(&mut env, &value) {
        b.deliver_scan(a, psm.max(0) as u16, rssi);
    }
}

pub fn channel_recv(
    env: JNIEnv,
    _class: JClass,
    h: jlong,
    id: jlong,
    data: JByteArray,
    len: jint,
) -> jboolean {
    let Some(b) = bridge(h) else {
        return 0;
    };
    let Ok(mut bytes) = env.convert_byte_array(data) else {
        return 0;
    };
    bytes.truncate(len.max(0) as usize);
    jboolean::from(b.deliver_recv(id, &bytes))
}

pub fn channel_closed(_env: JNIEnv, _class: JClass, h: jlong, id: jlong) {
    if let Some(b) = bridge(h) {
        b.channel_closed(id);
    }
}

pub fn channel_next_send(
    env: JNIEnv,
    _class: JClass,
    h: jlong,
    id: jlong,
    out: JByteArray,
    timeout: jint,
) -> jint {
    let Some(b) = bridge(h) else {
        return -1;
    };
    match b.next_send(id, Duration::from_millis(timeout.max(0) as u64)) {
        Some(bytes) => {
            let cap = env.get_array_length(&out).unwrap_or(0).max(0) as usize;
            let values: Vec<i8> = bytes[..bytes.len().min(cap)]
                .iter()
                .map(|v| *v as i8)
                .collect();
            if env.set_byte_array_region(&out, 0, &values).is_ok() {
                values.len() as jint
            } else {
                -1
            }
        }
        None if b.channel_open(id) => 0,
        None => -1,
    }
}
