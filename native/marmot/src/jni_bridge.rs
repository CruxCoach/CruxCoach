//! JSON commands are bounded DTOs, not code. No native pointer crosses JNI.
use crate::node::{Config, Error, Fence, Node};
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
use serde::Deserialize;
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    path::Path,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicI64, Ordering},
    },
};
use storage_sqlite::SqlCipherKey;

static NODES: OnceLock<Mutex<BTreeMap<i64, Arc<Mutex<Node>>>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn nodes() -> &'static Mutex<BTreeMap<i64, Arc<Mutex<Node>>>> {
    NODES.get_or_init(Default::default)
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

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    Bootstrap {
        refresh: Option<bool>,
    },
    Sync,
    RelaySummary,
    RelayStates,
    DiscoveryEnabled,
    Peers,
    Inbox {
        after: Option<String>,
        kind: Option<u64>,
    },
    Outbox,
    Pending {
        after: Option<String>,
        kind: Option<u64>,
    },
    Binding {
        binding: String,
    },
    Publish {
        event_id: String,
        fence: Fence,
    },
    Invite {
        peer: String,
    },
    Accept {
        peer: String,
        group: Option<String>,
    },
    Reset {
        peer: String,
    },
    Fence {
        peer: String,
    },
    Handoff {
        fence: Fence,
        kind: u64,
        tags: Vec<Vec<String>>,
        content: String,
        expires_at: u64,
    },
    Ack {
        source: String,
        fence: Fence,
    },
    Rotate {
        fence: Fence,
    },
}
fn command(node: &mut Node, raw: &str) -> Result<Value, Error> {
    if raw.len() > 524_288 {
        return Err(Error("command_limit"));
    }
    let command: Command = serde_json::from_str(raw).map_err(|_| Error("command_format"))?;
    Ok(match command {
        Command::DiscoveryEnabled => json!(node.discovery_enabled()?),
        Command::RelaySummary => json!(node.relay_summary()?),
        Command::RelayStates => json!(node.relay_states()?),
        Command::Pending { after, kind } => {
            let mut rows = node.pending_applications()?;
            rows.retain(|r| {
                after.as_ref().is_none_or(|a| &r.event_id > a) && kind.is_none_or(|k| k == r.kind)
            });
            rows.sort_by(|a, b| a.event_id.cmp(&b.event_id));
            rows.truncate(16);
            json!(rows)
        }
        Command::Publish { event_id, fence } => {
            node.publish_handoff(&event_id, &fence)?;
            json!(true)
        }
        Command::Binding { binding } => json!(node.own_binding(&binding)?),
        Command::Bootstrap { refresh } => {
            if refresh.unwrap_or(false) {
                node.refresh_discovery()?;
            } else {
                node.bootstrap()?;
            }
            json!(true)
        }
        Command::Sync => {
            node.sync()?;
            json!(true)
        }
        Command::Peers => json!(
            node.peers()?
                .into_iter()
                .chain(node.invitations()?)
                .collect::<Vec<_>>()
        ),
        Command::Inbox { after, kind } => {
            let mut rows = node.inbox()?;
            rows.retain(|r| {
                after.as_ref().is_none_or(|a| &r.source > a) && kind.is_none_or(|k| k == r.kind)
            });
            rows.sort_by(|a, b| a.source.cmp(&b.source));
            rows.truncate(16);
            json!(rows)
        }
        Command::Outbox => json!(node.outbox()?),
        Command::Invite { peer } => json!(node.invite(&peer)?),
        Command::Accept { peer, group } => {
            json!(node.accept_group_invitation(&peer, group.as_deref())?)
        }
        Command::Reset { peer } => {
            node.reset_peer(&peer)?;
            json!(true)
        }
        Command::Fence { peer } => json!(node.fence(&peer)?),
        Command::Handoff {
            fence,
            kind,
            tags,
            content,
            expires_at,
        } => json!(node.handoff(&fence, kind, tags, content, expires_at)?),
        Command::Ack { source, fence } => {
            node.acknowledge(&source, &fence)?;
            json!(true)
        }
        Command::Rotate { fence } => {
            node.rotate(&fence)?;
            json!(true)
        }
    })
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
        let config: Config = serde_json::from_str(&raw).map_err(|_| Error("config"))?;
        if config.local_test {
            return Err(Error("loopback_disabled_in_app"));
        }
        let mut bytes = zeroize::Zeroizing::new(
            env.convert_byte_array(key)
                .map_err(|_| Error("database_key"))?,
        );
        if bytes.len() != 32 {
            return Err(Error("database_key"));
        }
        let key = SqlCipherKey::new(hex::encode(bytes.as_slice()))?;
        bytes.fill(0);
        let signer = Arc::new(JavaSigner {
            vm: env.get_java_vm().map_err(|_| Error("jni_vm"))?,
            callback: env
                .new_global_ref(callback)
                .map_err(|_| Error("jni_callback"))?,
            public: PublicKey::from_hex(&config.account).map_err(|_| Error("account"))?,
        });
        let node = Node::open(Path::new(&path), key, config, signer.clone(), signer)?;
        let handle = NEXT.fetch_add(1, Ordering::SeqCst);
        let mut registry = nodes().lock().map_err(|_| Error("registry"))?;
        if registry.len() >= 4 {
            return Err(Error("account_limit"));
        }
        registry.insert(handle, Arc::new(Mutex::new(node)));
        Ok(handle)
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
        let node = nodes()
            .lock()
            .map_err(|_| Error("registry"))?
            .get(&handle)
            .cloned()
            .ok_or(Error("closed"))?;
        let mut node = node.lock().map_err(|_| Error("session_poisoned"))?;
        command(&mut node, &raw)
    }))
    .unwrap_or(Err(Error("native_panic")));
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
            fail(&mut env, "jni_output");
            std::ptr::null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotNative_close(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
) {
    match nodes().lock() {
        Ok(mut registry) => {
            registry.remove(&handle);
        }
        Err(_) => fail(&mut env, "registry"),
    }
}

/// Ephemeral identities exist only in an explicitly built local harness.
/// This module/symbol set is absent from the Android production library.
#[cfg(feature = "local-harness")]
mod harness {
    use super::*;
    use jni::sys::{jboolean, jbyteArray};
    use nostr::{
        Keys,
        secp256k1::{Message, SECP256K1, XOnlyPublicKey, schnorr::Signature},
    };
    static IDENTITIES: OnceLock<Mutex<BTreeMap<i64, (Keys, Keys)>>> = OnceLock::new();
    fn identities() -> &'static Mutex<BTreeMap<i64, (Keys, Keys)>> {
        IDENTITIES.get_or_init(Default::default)
    }

    // Test-only restart store: generated account/device/wrapping keys are encrypted
    // by the already pinned SQLCipher. The parent harness supplies a temporary
    // database key over stdin, never argv/logs. No key export API exists.
    struct RestartStore {
        db: storage_sqlite::SqliteAccountStorage,
        _lease: fs_private::PrivateExclusiveFileLease,
    }
    static RESTART_STORES: OnceLock<Mutex<BTreeMap<i64, RestartStore>>> = OnceLock::new();
    fn restart_stores() -> &'static Mutex<BTreeMap<i64, RestartStore>> {
        RESTART_STORES.get_or_init(Default::default)
    }
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
            let lease =
                fs_private::try_acquire_private_exclusive_file_lease(&path.with_extension("lease"))
                    .map_err(|_| Error("test_lease"))?;
            fs_private::ensure_private_db_files(path).map_err(|_| Error("test_path"))?;
            let bytes = zeroize::Zeroizing::new(
                env.convert_byte_array(key).map_err(|_| Error("test_key"))?,
            );
            if bytes.len() != 32 {
                return Err(Error("test_key"));
            }
            let db = storage_sqlite::SqliteAccountStorage::open_encrypted(
                path,
                &SqlCipherKey::new(hex::encode(bytes.as_slice()))?,
            )?;
            db.cruxcoach_init()?;
            let raw = if let Some(raw) = db.cruxcoach_get("synthetic-identity-v1")? {
                zeroize::Zeroizing::new(raw)
            } else {
                let mut raw = zeroize::Zeroizing::new(Vec::with_capacity(64));
                for _ in 0..2 {
                    let key = Keys::generate();
                    let secret = zeroize::Zeroizing::new(key.secret_key().to_secret_bytes());
                    raw.extend_from_slice(secret.as_slice());
                }
                db.cruxcoach_put("synthetic-identity-v1", raw.as_slice())?;
                raw
            };
            if raw.len() != 64 {
                return Err(Error("test_identity_format"));
            }
            let account = Keys::new(
                nostr::SecretKey::from_slice(&raw[..32]).map_err(|_| Error("test_identity"))?,
            );
            let device = Keys::new(
                nostr::SecretKey::from_slice(&raw[32..]).map_err(|_| Error("test_identity"))?,
            );
            let handle = NEXT.fetch_add(1, Ordering::SeqCst);
            let response = json!({"handle":handle,"account":account.public_key().to_hex(),"device":device.public_key().to_hex()});
            identities()
                .lock()
                .map_err(|_| Error("test_identity"))?
                .insert(handle, (account, device));
            restart_stores()
                .lock()
                .map_err(|_| Error("test_store"))?
                .insert(handle, RestartStore { db, _lease: lease });
            Ok(response)
        })();
        match result {
            Ok(value) => env.new_string(value.to_string()).unwrap().into_raw(),
            Err(e) => {
                fail(&mut env, e.0);
                std::ptr::null_mut()
            }
        }
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_wrappingKey(
        mut env: JNIEnv,
        _: JClass,
        handle: jlong,
        alias: JString,
        operation: jni::sys::jint,
    ) -> jbyteArray {
        let result: Result<Option<zeroize::Zeroizing<Vec<u8>>>, Error> = (|| {
            let alias = input(&mut env, &alias)?;
            // Hash identifiers only to fit the journal bound; not a key derivation.
            use sha2::{Digest, Sha256};
            let alias = format!("wrap/{}", hex::encode(Sha256::digest(alias.as_bytes())));
            let stores = restart_stores().lock().map_err(|_| Error("test_store"))?;
            let db = &stores.get(&handle).ok_or(Error("test_store"))?.db;
            if operation == 2 {
                db.cruxcoach_delete(&alias)?;
                return Ok(None);
            }
            let key = match db.cruxcoach_get(&alias)? {
                Some(key) => Some(key),
                None if operation == 1 => {
                    let key =
                        zeroize::Zeroizing::new(nostr::SecretKey::generate().to_secret_bytes());
                    db.cruxcoach_put(&alias, key.as_slice())?;
                    Some(key.to_vec())
                }
                None => None,
            };
            Ok(key.map(zeroize::Zeroizing::new))
        })();
        match result {
            Ok(Some(key)) => env
                .byte_array_from_slice(key.as_slice())
                .unwrap()
                .into_raw(),
            Ok(None) => std::ptr::null_mut(),
            Err(e) => {
                fail(&mut env, e.0);
                std::ptr::null_mut()
            }
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_createIdentity(
        env: JNIEnv,
        _: JClass,
    ) -> jstring {
        let account = Keys::generate();
        let device = Keys::generate();
        let handle = NEXT.fetch_add(1, Ordering::SeqCst);
        let response = json!({"handle":handle,"account":account.public_key().to_hex(),"device":device.public_key().to_hex()});
        identities()
            .lock()
            .unwrap()
            .insert(handle, (account, device));
        env.new_string(response.to_string()).unwrap().into_raw()
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_destroyIdentity(
        _env: JNIEnv,
        _: JClass,
        handle: jlong,
    ) {
        identities().lock().unwrap().remove(&handle);
        restart_stores().lock().unwrap().remove(&handle);
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_signEvent(
        mut env: JNIEnv,
        _: JClass,
        handle: jlong,
        device: jboolean,
        raw: JString,
    ) -> jstring {
        let result = (|| {
            let raw = input(&mut env, &raw)?;
            let event = UnsignedEvent::from_json(raw).map_err(|_| Error("test_event"))?;
            let identities = identities().lock().map_err(|_| Error("test_identity"))?;
            let keys = identities.get(&handle).ok_or(Error("test_identity"))?;
            let key = if device != 0 { &keys.1 } else { &keys.0 };
            if event.pubkey != key.public_key() {
                return Err(Error("test_account_mismatch"));
            }
            event.sign_with_keys(key).map_err(|_| Error("test_signer"))
        })();
        match result {
            Ok(event) => env.new_string(event.as_json()).unwrap().into_raw(),
            Err(e) => {
                fail(&mut env, e.0);
                std::ptr::null_mut()
            }
        }
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_signDigest(
        mut env: JNIEnv,
        _: JClass,
        handle: jlong,
        device: jboolean,
        bytes: JByteArray,
    ) -> jbyteArray {
        let result: Result<[u8; 64], Error> = (|| {
            let hash = env
                .convert_byte_array(bytes)
                .map_err(|_| Error("test_hash"))?;
            let message = Message::from_digest_slice(&hash).map_err(|_| Error("test_hash"))?;
            let identities = identities().lock().map_err(|_| Error("test_identity"))?;
            let keys = identities.get(&handle).ok_or(Error("test_identity"))?;
            Ok(if device != 0 { &keys.1 } else { &keys.0 }
                .sign_schnorr(&message)
                .serialize())
        })();
        match result {
            Ok(bytes) => env.byte_array_from_slice(&bytes).unwrap().into_raw(),
            Err(e) => {
                fail(&mut env, e.0);
                std::ptr::null_mut()
            }
        }
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
            let config: Config = serde_json::from_str(&input(&mut env, &config)?)
                .map_err(|_| Error("test_config"))?;
            let bytes = zeroize::Zeroizing::new(
                env.convert_byte_array(key)
                    .map_err(|_| Error("test_database_key"))?,
            );
            if bytes.len() != 32 {
                return Err(Error("test_database_key"));
            }
            let keys = identities()
                .lock()
                .map_err(|_| Error("test_identity"))?
                .get(&identity)
                .ok_or(Error("test_identity"))?
                .0
                .clone();
            let node = Node::open(
                Path::new(&path),
                SqlCipherKey::new(hex::encode(bytes.as_slice()))?,
                config,
                Arc::new(keys.clone()),
                Arc::new(crate::node::LocalProofSigner(keys)),
            )?;
            let handle = NEXT.fetch_add(1, Ordering::SeqCst);
            nodes()
                .lock()
                .map_err(|_| Error("registry"))?
                .insert(handle, Arc::new(Mutex::new(node)));
            Ok(handle)
        })();
        match result {
            Ok(h) => h,
            Err(e) => {
                fail(&mut env, e.0);
                0
            }
        }
    }
}

#[cfg(feature = "local-harness")]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_cruxcoach_android_sharing_MarmotTestNative_failCruxcoach(
    _env: JNIEnv,
    _class: JClass,
    port: jni::sys::jint,
) {
    if (0..=65535).contains(&port) {
        crate::relay::CRUXCOACH_FAULT_PORT.store(port as u16, Ordering::SeqCst);
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
        crate::node::archive_storage(Path::new(&path))
    }))
    .unwrap_or(Err(Error("native_panic")));
    if result.is_ok() { 1 } else { 0 }
}
