//! CruxCoach's account-device sharing host: the v2 MLS host (MDK/OpenMLS,
//! nostr-sdk pool, in-process relay; feature `mls`, default) and the spike-M0
//! direct Olm channel (vodozemac, no relay).
#[cfg(feature = "mls")]
pub mod engine;
pub mod error;
#[cfg(feature = "mls")]
pub mod host;
mod jni_bridge;
pub mod olm;
pub mod olm_host;
pub mod p2p;
pub mod protocol;
#[cfg(feature = "mls")]
pub mod store;
#[cfg(feature = "mls")]
pub mod transport;
