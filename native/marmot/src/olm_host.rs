//! JSON commands of the Olm engine: the v2 operation set behind `MarmotHost`
//! plus transport-neutral exchange operations for direct transports. No relay,
//! no pool, no socket: transports (LAN HTTP, BLE GATT) live on the app side and
//! move opaque containers produced and parsed here.
use crate::error::{Error, Result, checked};
use crate::olm::{Changes, MAX_OUTBOUND_BYTES, OlmEngine};
use nostr::NostrSigner;
use nostr::base64::Engine as _;
use nostr::base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use serde::Deserialize;
use serde_json::{Value, json};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::Duration;

#[derive(Deserialize)]
#[serde(tag = "op", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    Status,
    SetRelays {
        #[allow(dead_code)]
        relays: Vec<String>,
    },
    SetOnline {
        online: bool,
    },
    SetDiscovery {
        #[allow(dead_code)]
        enabled: bool,
    },
    Invite {
        peer: String,
    },
    Accept {
        peer: String,
    },
    Decline {
        peer: String,
    },
    Send {
        peer: String,
        token: String,
        content: String,
    },
    Cancel {
        peer: String,
    },
    End {
        peer: String,
    },
    Next {
        after: i64,
        generation: u64,
        timeout_ms: u64,
        limit: usize,
    },
    Ack {
        seqs: Vec<i64>,
    },
    Sync,
    // Transport-neutral direct-exchange operations.
    Hello,
    Identify {
        beacons: Vec<String>,
    },
    ExchangeBegin {
        peer: String,
        #[serde(default)]
        max_bytes: Option<usize>,
    },
    ExchangeEnd {
        container: String,
        #[serde(default)]
        max_bytes: Option<usize>,
    },
    ExchangeServe {
        container: String,
        #[serde(default)]
        max_bytes: Option<usize>,
    },
    ContactCode,
    ImportContact {
        payload: String,
    },
    // Mailbox operations: relays are reached from the app side, which moves
    // signed events it cannot open.
    MailboxOutgoing {
        #[serde(default)]
        pow: u8,
        #[serde(default)]
        max_bytes: Option<usize>,
    },
    MailboxAddresses {
        #[serde(default)]
        days: Option<u64>,
    },
    MailboxAbsorb {
        events: Vec<String>,
    },
    #[cfg(feature = "local-harness")]
    PrivateStorageMatches {
        marker: String,
    },
    #[cfg(feature = "local-harness")]
    Burn {
        peer: String,
        count: usize,
    },
    #[cfg(feature = "local-harness")]
    ForgetSessions {
        peer: String,
    },
    #[cfg(feature = "local-harness")]
    SignerOps,
}

/// The QR form of a contact code (npub and a fresh secret).
pub const CONTACT_PREFIX: &str = "cruxcoach:c1:";

pub struct OlmHost {
    pub account: String,
    engine: Mutex<OlmEngine>,
    online: AtomicBool,
    closed: AtomicBool,
    generation: Mutex<u64>,
    changed: Condvar,
}

impl OlmHost {
    pub fn open(
        path: &Path,
        key: &[u8; 32],
        account: &str,
        signer: Arc<dyn NostrSigner>,
    ) -> Result<Self> {
        let engine = OlmEngine::open(path, key, account, signer)?;
        Ok(Self {
            account: account.to_string(),
            engine: Mutex::new(engine),
            online: AtomicBool::new(false),
            closed: AtomicBool::new(false),
            generation: Mutex::new(1),
            changed: Condvar::new(),
        })
    }

    fn engine(&self) -> Result<std::sync::MutexGuard<'_, OlmEngine>> {
        self.engine.lock().map_err(|_| Error("engine_poisoned"))
    }

    fn bump(&self) {
        if let Ok(mut generation) = self.generation.lock() {
            *generation += 1;
        }
        self.changed.notify_all();
    }

    fn after(&self, changes: Changes) {
        if changes.inbox || changes.peers {
            self.bump();
        }
    }

    pub fn signal_close(&self) {
        self.closed.store(true, Ordering::SeqCst);
        self.bump();
    }

    fn next(&self, after: i64, known: u64, timeout_ms: u64, limit: usize) -> Result<Value> {
        let limit = limit.clamp(1, 64);
        let deadline = std::time::Instant::now() + Duration::from_millis(timeout_ms.min(30_000));
        loop {
            let items = self.engine()?.inbox_after(after, limit)?;
            let generation = *self.generation.lock().map_err(|_| Error("host_poisoned"))?;
            let now = std::time::Instant::now();
            if !items.is_empty()
                || generation != known
                || self.closed.load(Ordering::SeqCst)
                || now >= deadline
            {
                return Ok(json!({"items": items, "generation": generation}));
            }
            let guard = self.generation.lock().map_err(|_| Error("host_poisoned"))?;
            if *guard != known {
                continue;
            }
            let _ = self
                .changed
                .wait_timeout(guard, (deadline - now).min(Duration::from_millis(500)));
        }
    }

    fn container(value: &str) -> Result<Vec<u8>> {
        if value.len() > 360_000 {
            return Err(Error("wire_limit"));
        }
        checked(STANDARD.decode(value), "wire_format")
    }

    pub fn call(&self, raw: &str) -> Result<Value> {
        if raw.len() > 524_288 {
            return Err(Error("command_limit"));
        }
        let command: Command = serde_json::from_str(raw).map_err(|_| Error("command_format"))?;
        Ok(match command {
            Command::Status => {
                let mut status = self.engine()?.status()?;
                status["online"] = json!(self.online.load(Ordering::SeqCst));
                status["generation"] =
                    json!(*self.generation.lock().map_err(|_| Error("host_poisoned"))?);
                status
            }
            // Direct transports have no relay list; accepted for interface
            // compatibility and ignored.
            Command::SetRelays { .. } => json!(true),
            Command::SetOnline { online } => {
                self.online.store(online, Ordering::SeqCst);
                json!(true)
            }
            // Invitations are never offered to or taken from unknown devices,
            // so there is nothing to switch; kept for the shared host API.
            Command::SetDiscovery { .. } => json!(false),
            Command::Invite { peer } => {
                let rel = self.engine()?.invite(&peer)?;
                self.bump();
                json!({"group": rel})
            }
            Command::Accept { peer } => {
                self.engine()?.accept(&peer)?;
                self.bump();
                json!(true)
            }
            Command::Decline { peer } => {
                self.engine()?.decline(&peer)?;
                self.bump();
                json!(true)
            }
            Command::Send {
                peer,
                token,
                content,
            } => {
                let (event, duplicate) = self.engine()?.send(&peer, &token, &content)?;
                json!({"event": event, "duplicate": duplicate})
            }
            Command::Cancel { peer } => json!({"cancelled": self.engine()?.cancel(&peer)?}),
            Command::End { peer } => {
                self.engine()?.end(&peer, "local")?;
                self.bump();
                json!(true)
            }
            Command::Next {
                after,
                generation,
                timeout_ms,
                limit,
            } => self.next(after, generation, timeout_ms, limit)?,
            Command::Ack { seqs } => {
                if seqs.len() > 64 {
                    return Err(Error("ack_limit"));
                }
                self.engine()?.inbox_ack(&seqs)?;
                json!(true)
            }
            Command::Sync => {
                let mut engine = self.engine()?;
                let changes = engine.housekeeping()?;
                let pending = engine.status()?["local"]["outbound_pending"].clone();
                drop(engine);
                self.after(changes);
                json!({"outbound_pending": pending, "online": self.online.load(Ordering::SeqCst)})
            }
            Command::Hello => self.engine()?.hello()?,
            Command::Identify { beacons } => json!({"peer": self.engine()?.identify(&beacons)?}),
            Command::ExchangeBegin { peer, max_bytes } => {
                let budget = max_bytes.unwrap_or(MAX_OUTBOUND_BYTES);
                let container = self.engine()?.exchange_begin(&peer, budget)?;
                json!({"container": container.map(|c| STANDARD.encode(c))})
            }
            Command::ExchangeEnd {
                container,
                max_bytes,
            } => {
                let budget = max_bytes.unwrap_or(MAX_OUTBOUND_BYTES);
                let (more, served) = self
                    .engine()?
                    .exchange_end(&Self::container(&container)?, budget)?;
                self.after(served.changes);
                json!({"more": more, "peers": served.peers})
            }
            Command::ExchangeServe {
                container,
                max_bytes,
            } => {
                let budget = max_bytes.unwrap_or(MAX_OUTBOUND_BYTES);
                let (response, served) = self
                    .engine()?
                    .exchange_serve(&Self::container(&container)?, budget)?;
                self.after(served.changes);
                json!({"container": STANDARD.encode(response), "peers": served.peers})
            }
            Command::MailboxOutgoing { pow, max_bytes } => {
                if pow > 24 {
                    return Err(Error("mailbox_pow"));
                }
                // Well below the 8 MiB a JNI answer may carry.
                let max = max_bytes.unwrap_or(4 << 20).min(6 << 20);
                json!({"events": self.engine()?.mailbox_outgoing(crate::protocol::now_s(), pow, max)?})
            }
            Command::MailboxAddresses { days } => {
                json!({"addresses": self.engine()?.mailbox_addresses(crate::protocol::now_s(), days.unwrap_or(2))?,
                       "max_age_s": crate::mailbox::MAX_AGE_S})
            }
            Command::MailboxAbsorb { events } => {
                if events.len() > 512 {
                    return Err(Error("mailbox_batch"));
                }
                let result = self
                    .engine()?
                    .mailbox_absorb(crate::protocol::now_s(), &events)?;
                self.after(crate::olm::Changes {
                    inbox: result["inbox"].as_bool().unwrap_or(false),
                    peers: result["peers"].as_bool().unwrap_or(false),
                });
                result
            }
            Command::ContactCode => {
                let code = self.engine()?.contact_code()?;
                self.bump();
                json!({"payload": format!("{CONTACT_PREFIX}{}", URL_SAFE_NO_PAD.encode(code))})
            }
            Command::ImportContact { payload } => {
                let encoded = payload
                    .strip_prefix(CONTACT_PREFIX)
                    .ok_or(Error("contact_format"))?;
                if encoded.len() > 128 {
                    return Err(Error("contact_format"));
                }
                let code = checked(URL_SAFE_NO_PAD.decode(encoded), "contact_format")?;
                let peer = self.engine()?.import_contact(&code)?;
                self.bump();
                json!({"peer": peer})
            }
            #[cfg(feature = "local-harness")]
            Command::PrivateStorageMatches { marker } => {
                json!(self.engine()?.plaintext_matches(&marker)?)
            }
            #[cfg(feature = "local-harness")]
            Command::Burn { peer, count } => {
                self.engine()?.burn(&peer, count.min(10_000))?;
                json!(true)
            }
            #[cfg(feature = "local-harness")]
            Command::ForgetSessions { peer } => {
                self.engine()?.forget_sessions(&peer)?;
                json!(true)
            }
            #[cfg(feature = "local-harness")]
            Command::SignerOps => json!(self.engine()?.signer_ops()),
        })
    }
}
