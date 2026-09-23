//! CruxCoach's account-device Marmot host. Cryptography is MDK/OpenMLS/Nostr;
//! transport is the nostr-sdk pool over an in-process relay and public relays.
pub mod engine;
pub mod error;
pub mod host;
mod jni_bridge;
pub mod protocol;
pub mod store;
pub mod transport;
