//! JSON commands are bounded DTOs, not code. No native pointer crosses JNI.
//! Four production exports: open, call, archive, close.
use crate::error::Error;
use crate::host::{Host, HostConfig};
use cgka_engine::account_identity_proof::{
    AccountIdentityProofRequest, AccountIdentityProofSigner,
};
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JByteArray, JClass, JObject, JString, JValue},
    sys::{jlong, jstring},
};
use nostr::signer::SignerBackend;
use nostr::{
    Event, JsonUtil, NostrSigner, PublicKey, SignerError, UnsignedEvent, util::BoxedFuture,
};
use serde_json::json;
use std::{
    collections::BTreeMap,
    path::Path,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicI64, Ordering},
    },
};
use storage_sqlite::SqlCipherKey;

static HOSTS: OnceLock<Mutex<BTreeMap<i64, Arc<Host>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn hosts() -> &'static Mutex<BTreeMap<i64, Arc<Host>>> {
    HOSTS.get_or_init(Default::default)
}

fn register(host: Host) -> Result<i64, Error> {
    let mut registry = hosts().lock().map_err(|_| Error("registry"))?;
    if registry.len() >= 4 {
        return Err(Error("account_limit"));
    }
    let handle = NEXT.fetch_add(1, Ordering::SeqCst);
    registry.insert(handle, Arc::new(host));
    Ok(handle)
}

struct JavaSigner {
    vm: JavaVM,
    callback: GlobalRef,
    public: PublicKey,
}
impl std::fmt::Debug for JavaSigner {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("JavaSigner(redacted)")
    }
}
impl JavaSigner {
    fn invoke(&self, operation: &str, argument: &str) -> Result<String, SignerError> {
        let result = (|| {
            let mut env = self.vm.attach_current_thread().map_err(|_| ())?;
            let op = env.new_string(operation).map_err(|_| ())?;
            let arg = env.new_string(argument).map_err(|_| ())?;
            let result = env.call_method(
                self.callback.as_obj(),
                "invoke",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                &[JValue::Object(&op), JValue::Object(&arg)],
            );
            if env.exception_check().unwrap_or(true) {
                let _ = env.exception_clear();
                return Err(());
            }
            let object = result.map_err(|_| ())?.l().map_err(|_| ())?;
            if object.is_null() {
                return Err(());
            }
            let string = JString::from(object);
            let value: String = env.get_string(&string).map_err(|_| ())?.into();
            if value.len() > 524_288 {
                return Err(());
            }
            Ok(value)
        })();
        result.map_err(|_| SignerError::backend(Error("account_signer_refused")))
    }
}
impl AccountIdentityProofSigner for JavaSigner {
    fn sign_account_identity_proof(
        &self,
        request: &AccountIdentityProofRequest,
    ) -> Result<[u8; 64], String> {
        if request.account_identity != self.public.to_bytes() {
            return Err("account_mismatch".into());
        }
        let signed = self
            .invoke("sign", &request.proof_event()?.as_json())
            .map_err(|_| "signer".to_string())?;
        request
            .signature_from_signed_event(Event::from_json(signed).map_err(|_| "event".to_string())?)
    }
}
impl NostrSigner for JavaSigner {
    fn backend(&self) -> SignerBackend<'_> {
        SignerBackend::Custom("CruxCoach account signer".into())
    }
    fn get_public_key(&self) -> BoxedFuture<'_, Result<PublicKey, SignerError>> {
        Box::pin(async { Ok(self.public) })
    }
    fn sign_event(&self, event: UnsignedEvent) -> BoxedFuture<'_, Result<Event, SignerError>> {
        Box::pin(async move {
            if event.pubkey != self.public {
                return Err(SignerError::backend(Error("account_mismatch")));
            }
            let signed = self.invoke("sign", &event.as_json())?;
            let signed = Event::from_json(signed)
                .map_err(|_| SignerError::backend(Error("signed_event")))?;
            signed
                .verify()
                .map_err(|_| SignerError::backend(Error("signature")))?;
            if Some(signed.id) != event.id || signed.pubkey != event.pubkey {
                return Err(SignerError::backend(Error("signer_mismatch")));
            }
            Ok(signed)
        })
    }
    fn nip04_encrypt<'a>(
        &'a self,
        _: &'a PublicKey,
        _: &'a str,
    ) -> BoxedFuture<'a, Result<String, SignerError>> {
        Box::pin(async { Err(SignerError::backend(Error("nip04_disabled"))) })
    }
    fn nip04_decrypt<'a>(
        &'a self,
        _: &'a PublicKey,
        _: &'a str,
    ) -> BoxedFuture<'a, Result<String, SignerError>> {
        Box::pin(async { Err(SignerError::backend(Error("nip04_disabled"))) })
    }
    fn nip44_encrypt<'a>(
        &'a self,
        public: &'a PublicKey,
        text: &'a str,
    ) -> BoxedFuture<'a, Result<String, SignerError>> {
        Box::pin(async move {
            self.invoke(
                "nip44_encrypt",
                &json!({"public":public.to_hex(),"content":text}).to_string(),
            )
        })
    }
    fn nip44_decrypt<'a>(
        &'a self,
        public: &'a PublicKey,
        text: &'a str,
    ) -> BoxedFuture<'a, Result<String, SignerError>> {
        Box::pin(async move {
            self.invoke(
                "nip44_decrypt",
                &json!({"public":public.to_hex(),"content":text}).to_string(),
            )
        })
    }
}

fn fail(env: &mut JNIEnv, code: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", code);
}

fn input(env: &mut JNIEnv, value: &JString) -> Result<String, Error> {
    let value: String = env
        .get_string(value)
        .map_err(|_| Error("jni_string"))?
        .into();
    if value.len() > 524_288 {
        return Err(Error("jni_limit"));
    }
    Ok(value)
}

fn database_key(env: &mut JNIEnv, key: JByteArray) -> Result<SqlCipherKey, Error> {
    let mut bytes = zeroize::Zeroizing::new(
        env.convert_byte_array(key)
            .map_err(|_| Error("database_key"))?,
    );
    if bytes.len() != 32 {
        return Err(Error("database_key"));
    }
    let key = SqlCipherKey::new(hex::encode(bytes.as_slice()))?;
    bytes.fill(0);
    Ok(key)
}

fn respond(env: &mut JNIEnv, result: Result<serde_json::Value, Error>) -> jstring {
    let response = match result {
        Ok(value) => json!({"ok":true,"value":value}),
        Err(error) => json!({"ok":false,"error":error.0}),
    };
    let encoded = response.to_string();
    let encoded = if encoded.len() > 8_388_608 {
        "{\"ok\":false,\"error\":\"native_output_limit\"}".to_string()
    } else {
        encoded
    };
    match env.new_string(encoded) {
        Ok(s) => s.into_raw(),
        Err(_) => {
            fail(env, "jni_output");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotNative_open(
    mut env: JNIEnv,
    _: JClass,
    path: JString,
    key: JByteArray,
    config: JString,
    callback: JObject,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path = input(&mut env, &path)?;
        let raw = input(&mut env, &config)?;
        let config: HostConfig = serde_json::from_str(&raw).map_err(|_| Error("config"))?;
        if config.local_test {
            return Err(Error("loopback_disabled_in_app"));
        }
        let key = database_key(&mut env, key)?;
        let signer = Arc::new(JavaSigner {
            vm: env.get_java_vm().map_err(|_| Error("jni_vm"))?,
            callback: env
                .new_global_ref(callback)
                .map_err(|_| Error("jni_callback"))?,
            public: PublicKey::from_hex(&config.account).map_err(|_| Error("account"))?,
        });
        let host = Host::open(Path::new(&path), key, config, signer.clone(), signer)?;
        register(host)
    }))
    .unwrap_or(Err(Error("native_panic")));
    match result {
        Ok(handle) => handle,
        Err(error) => {
            fail(&mut env, error.0);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotNative_call(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    raw: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let raw = input(&mut env, &raw)?;
        let host = hosts()
            .lock()
            .map_err(|_| Error("registry"))?
            .get(&handle)
            .cloned()
            .ok_or(Error("closed"))?;
        // The registry lock is released: `next` may block without holding it.
        host.call(&raw)
    }))
    .unwrap_or(Err(Error("native_panic")));
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotNative_close(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    let removed = match hosts().lock() {
        Ok(mut registry) => registry.remove(&handle),
        Err(_) => {
            fail(&mut env, "registry");
            return;
        }
    };
    if let Some(mut host) = removed {
        // Blocked `next` calls hold clones; they observe `closed` and return.
        host.signal_close();
        for _ in 0..40 {
            match Arc::try_unwrap(host) {
                Ok(owned) => {
                    owned.close();
                    return;
                }
                Err(shared) => {
                    host = shared;
                    std::thread::sleep(std::time::Duration::from_millis(50));
                }
            }
        }
        // Still in use: the last clone's Drop stops the runtime.
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotNative_archive(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jni::sys::jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path = input(&mut env, &path)?;
        crate::engine::archive_storage(Path::new(&path))
    }))
    .unwrap_or(Err(Error("native_panic")));
    if result.is_ok() { 1 } else { 0 }
}

/// Synthetic identities exist only in an explicitly built local harness.
/// This module and its symbols are absent from the Android library.
#[cfg(feature = "local-harness")]
mod harness {
    use super::*;
    use jni::sys::jboolean;
    use nostr::{
        Keys,
        secp256k1::{Message, SECP256K1, XOnlyPublicKey, schnorr::Signature},
    };
    static IDENTITIES: OnceLock<Mutex<BTreeMap<i64, Keys>>> = OnceLock::new();
    fn identities() -> &'static Mutex<BTreeMap<i64, Keys>> {
        IDENTITIES.get_or_init(Default::default)
    }

    fn remember(keys: Keys) -> serde_json::Value {
        let handle = NEXT.fetch_add(1, Ordering::SeqCst);
        let response = json!({"handle":handle,"account":keys.public_key().to_hex()});
        if let Ok(mut identities) = identities().lock() {
            identities.insert(handle, keys);
        }
        response
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_createIdentity(
        mut env: JNIEnv,
        _: JClass,
    ) -> jstring {
        let value = remember(Keys::generate());
        respond(&mut env, Ok(value))
    }

    /// Test-only restart store: the generated account key is encrypted by the
    /// pinned SQLCipher with a key the parent harness passes over stdin.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_restartIdentity(
        mut env: JNIEnv,
        _: JClass,
        path: JString,
        key: JByteArray,
    ) -> jstring {
        let result = (|| {
            let path = input(&mut env, &path)?;
            let path = Path::new(&path);
            fs_private::create_dir_all_private(path.parent().ok_or(Error("test_path"))?)
                .map_err(|_| Error("test_path"))?;
            fs_private::ensure_private_db_files(path).map_err(|_| Error("test_path"))?;
            let key = database_key(&mut env, key)?;
            let db = storage_sqlite::SqliteAccountStorage::open_encrypted(path, &key)?;
            let stored: Option<Vec<u8>> = db.cruxcoach_sql(|c| {
                c.execute_batch("CREATE TABLE IF NOT EXISTS synthetic_identity(k BLOB NOT NULL)")?;
                use rusqlite::OptionalExtension;
                c.query_row("SELECT k FROM synthetic_identity", [], |r| r.get(0))
                    .optional()
            })?;
            let secret = match stored {
                Some(bytes) => zeroize::Zeroizing::new(bytes),
                None => {
                    let generated = zeroize::Zeroizing::new(
                        Keys::generate().secret_key().to_secret_bytes().to_vec(),
                    );
                    db.cruxcoach_sql(|c| {
                        c.execute(
                            "INSERT INTO synthetic_identity(k) VALUES(?1)",
                            [generated.as_slice()],
                        )
                    })?;
                    generated
                }
            };
            let keys = Keys::new(
                nostr::SecretKey::from_slice(&secret).map_err(|_| Error("test_identity"))?,
            );
            Ok(remember(keys))
        })();
        respond(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_destroyIdentity(
        _env: JNIEnv,
        _: JClass,
        handle: jlong,
    ) {
        if let Ok(mut identities) = identities().lock() {
            identities.remove(&handle);
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_signEvent(
        mut env: JNIEnv,
        _: JClass,
        handle: jlong,
        raw: JString,
    ) -> jstring {
        let result = (|| {
            let raw = input(&mut env, &raw)?;
            let event = UnsignedEvent::from_json(raw).map_err(|_| Error("test_event"))?;
            let identities = identities().lock().map_err(|_| Error("test_identity"))?;
            let keys = identities.get(&handle).ok_or(Error("test_identity"))?;
            if event.pubkey != keys.public_key() {
                return Err(Error("test_account_mismatch"));
            }
            let signed = event
                .sign_with_keys(keys)
                .map_err(|_| Error("test_signer"))?;
            serde_json::from_str(&signed.as_json()).map_err(|_| Error("test_event"))
        })();
        respond(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_verify(
        env: JNIEnv,
        _: JClass,
        signature: JByteArray,
        hash: JByteArray,
        public: JByteArray,
    ) -> jboolean {
        let valid = (|| {
            let signature = Signature::from_slice(&env.convert_byte_array(signature).ok()?).ok()?;
            let message = Message::from_digest_slice(&env.convert_byte_array(hash).ok()?).ok()?;
            let public = XOnlyPublicKey::from_slice(&env.convert_byte_array(public).ok()?).ok()?;
            SECP256K1.verify_schnorr(&signature, &message, &public).ok()
        })()
        .is_some();
        u8::from(valid)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_open(
        mut env: JNIEnv,
        _: JClass,
        identity: jlong,
        path: JString,
        key: JByteArray,
        config: JString,
    ) -> jlong {
        let result = (|| {
            let path = input(&mut env, &path)?;
            let config: HostConfig = serde_json::from_str(&input(&mut env, &config)?)
                .map_err(|_| Error("test_config"))?;
            let key = database_key(&mut env, key)?;
            let keys = identities()
                .lock()
                .map_err(|_| Error("test_identity"))?
                .get(&identity)
                .ok_or(Error("test_identity"))?
                .clone();
            let host = Host::open(
                Path::new(&path),
                key,
                config,
                Arc::new(keys.clone()),
                Arc::new(crate::engine::LocalProofSigner(keys)),
            )?;
            register(host)
        })();
        match result {
            Ok(h) => h,
            Err(e) => {
                fail(&mut env, e.0);
                0
            }
        }
    }

    struct TestRelay {
        runtime: tokio::runtime::Runtime,
        relay: nostr_relay_builder::LocalRelay,
    }
    static RELAYS: OnceLock<Mutex<BTreeMap<i64, TestRelay>>> = OnceLock::new();
    fn relays() -> &'static Mutex<BTreeMap<i64, TestRelay>> {
        RELAYS.get_or_init(Default::default)
    }

    /// A loopback-only nostr-relay-builder relay (live subscriptions, NIP-77)
    /// for JVM integration tests. Returns {"handle","url"}.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_startRelay(
        mut env: JNIEnv,
        _: JClass,
    ) -> jstring {
        let result = (|| {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(1)
                .enable_all()
                .build()
                .map_err(|_| Error("test_relay"))?;
            let relay = runtime.block_on(async {
                let relay = nostr_relay_builder::LocalRelay::new(
                    nostr_relay_builder::RelayBuilder::default()
                        .addr(std::net::IpAddr::from([127, 0, 0, 1])),
                );
                relay.run().await.map(|_| relay)
            });
            let relay = relay.map_err(|_| Error("test_relay"))?;
            let url = runtime.block_on(relay.url()).to_string();
            let handle = NEXT.fetch_add(1, Ordering::SeqCst);
            relays()
                .lock()
                .map_err(|_| Error("test_relay"))?
                .insert(handle, TestRelay { runtime, relay });
            Ok(json!({"handle":handle,"url":url}))
        })();
        respond(&mut env, result)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_stopRelay(
        _env: JNIEnv,
        _: JClass,
        handle: jlong,
    ) {
        if let Some(relay) = relays().lock().ok().and_then(|mut r| r.remove(&handle)) {
            relay.relay.shutdown();
            relay
                .runtime
                .shutdown_timeout(std::time::Duration::from_secs(2));
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_failCruxcoach(
        _env: JNIEnv,
        _class: JClass,
        port: jni::sys::jint,
    ) {
        if (0..=65535).contains(&port) {
            crate::transport::CRUXCOACH_FAULT_PORT.store(port as u16, Ordering::SeqCst);
        }
    }
}
