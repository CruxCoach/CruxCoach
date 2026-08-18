//! Ownership rules for installing the Kotlin BLE radio into the node's slot.
//!
//! Upstream FIPS replaced the process-global `set_android_ble_bridge` with a
//! node-owned `BleRadioSlot`. That is the better shape, but it does not by
//! itself reconcile the two independent lifetimes CruxCoach has:
//!
//! * The **radio** (`FipsBleRadio` + its `AndroidBleBridge`) is created by
//!   `bleBridgeNew` *before* the node exists, and freed by `bleBridgeFree`
//!   *after* the node is gone.
//! * The **node** is rebuilt on every realm switch, permission restart,
//!   Bluetooth off/on and idle-transport recycle, and each rebuild hands out a
//!   fresh slot.
//!
//! Either side can therefore arrive, leave or be replaced first, and a stop
//! that timed out leaves a detached node thread still holding the previous
//! slot. Every mutation here is consequently guarded by an ownership check:
//! a withdrawal only ever retracts the exact installation it owns, identified
//! by the bridge's JNI handle (a monotonic token) or by slot pointer identity.
//!
//! The logic is deliberately generic over the slot so it compiles and is unit
//! tested on the host, where `fips` (and therefore `BleRadioSlot`) is not
//! available: `native/fips-bridge` only depends on `fips` for
//! `cfg(target_os = "android")`.

use std::sync::Arc;

/// The node-side slot a bridge is installed into.
///
/// Implemented for `fips::transport::ble::android_io::BleRadioSlot` on
/// Android, and by a recording fake in the tests below.
pub trait RadioSlot {
    /// The bridge type this slot carries.
    type Bridge;

    /// Install `bridge`, replacing whatever was there.
    fn install(&self, bridge: Arc<Self::Bridge>);

    /// Remove the installed bridge, if any.
    fn clear(&self);

    /// Whether this exact bridge instance is the installed one.
    fn holds(&self, bridge: &Arc<Self::Bridge>) -> bool;
}

/// The single reconciliation point between the current radio and the current
/// node's slot. Not itself synchronized: callers hold one process-wide mutex
/// around it, which is also what serializes a start racing a stop.
pub struct RadioInstall<S: RadioSlot> {
    slot: Option<Arc<S>>,
    /// The live radio and the JNI handle that owns it. The handle is the
    /// ownership token: `bleBridgeFree` presents it, and a stale one is
    /// rejected rather than allowed to retract a newer radio.
    bridge: Option<(i64, Arc<S::Bridge>)>,
}

impl<S: RadioSlot> Default for RadioInstall<S> {
    fn default() -> Self {
        Self {
            slot: None,
            bridge: None,
        }
    }
}

impl<S: RadioSlot> RadioInstall<S> {
    pub fn new() -> Self {
        Self::default()
    }

    /// Record the radio Kotlin just built and install it into the current
    /// slot, if a node has published one. Replaces any previous radio, which
    /// is what a Bluetooth off/on cycle produces.
    pub fn install_bridge(&mut self, token: i64, bridge: Arc<S::Bridge>) {
        if let Some(slot) = self.slot.as_ref() {
            slot.install(Arc::clone(&bridge));
        }
        self.bridge = Some((token, bridge));
    }

    /// Retract the radio identified by `token`.
    ///
    /// Returns whether anything was retracted. A stop that lost the race with
    /// the next start presents a stale token and is refused: withdrawing the
    /// radio a newer `bleBridgeNew` already installed would leave the node
    /// parked with no radio and nothing to wake it.
    pub fn clear_bridge(&mut self, token: i64) -> bool {
        let owned = matches!(self.bridge.as_ref(), Some((held, _)) if *held == token);
        if !owned {
            return false;
        }
        let (_, bridge) = self.bridge.take().expect("checked above");
        if let Some(slot) = self.slot.as_ref()
            && slot.holds(&bridge)
        {
            slot.clear();
        }
        true
    }

    /// Publish the slot of a node that has just been built, and hand it the
    /// live radio if there is one.
    ///
    /// A previous slot is cleared first. That case is a stop whose node thread
    /// timed out and was detached: it still owns its slot and its transport is
    /// still resolving it, so leaving the live radio installed there would let
    /// a node we have given up on keep driving the phone's radio underneath
    /// its replacement.
    pub fn attach_slot(&mut self, slot: Arc<S>) {
        if let Some(previous) = self.slot.take()
            && !Arc::ptr_eq(&previous, &slot)
        {
            previous.clear();
        }
        if let Some((_, bridge)) = self.bridge.as_ref() {
            slot.install(Arc::clone(bridge));
        }
        self.slot = Some(slot);
    }

    /// Retract a node's slot on stop, or after a failed start.
    ///
    /// Returns whether this call owned the slot. The radio is deliberately
    /// kept: `bleBridgeFree` owns its lifetime, and a restart that reuses the
    /// same radio must find it here.
    pub fn detach_slot(&mut self, slot: &Arc<S>) -> bool {
        if !self
            .slot
            .as_ref()
            .is_some_and(|held| Arc::ptr_eq(held, slot))
        {
            return false;
        }
        slot.clear();
        self.slot = None;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    #[derive(Debug, PartialEq, Eq)]
    struct Radio(&'static str);

    #[derive(Default)]
    struct FakeSlot {
        current: Mutex<Option<Arc<Radio>>>,
    }

    impl FakeSlot {
        fn new() -> Arc<Self> {
            Arc::new(Self::default())
        }

        fn installed(&self) -> Option<&'static str> {
            self.current.lock().unwrap().as_ref().map(|r| r.0)
        }
    }

    impl RadioSlot for FakeSlot {
        type Bridge = Radio;

        fn install(&self, bridge: Arc<Radio>) {
            *self.current.lock().unwrap() = Some(bridge);
        }

        fn clear(&self) {
            *self.current.lock().unwrap() = None;
        }

        fn holds(&self, bridge: &Arc<Radio>) -> bool {
            self.current
                .lock()
                .unwrap()
                .as_ref()
                .is_some_and(|held| Arc::ptr_eq(held, bridge))
        }
    }

    fn radio(name: &'static str) -> Arc<Radio> {
        Arc::new(Radio(name))
    }

    /// CruxCoach builds the radio before the node, every time.
    #[test]
    fn a_radio_created_before_the_node_is_installed_when_the_slot_arrives() {
        let mut install = RadioInstall::<FakeSlot>::new();
        install.install_bridge(1, radio("first"));

        let slot = FakeSlot::new();
        install.attach_slot(Arc::clone(&slot));

        assert_eq!(slot.installed(), Some("first"));
    }

    /// ...and a radio replaced under a running node reaches the live slot.
    #[test]
    fn a_radio_created_after_the_node_reaches_the_live_slot() {
        let mut install = RadioInstall::<FakeSlot>::new();
        let slot = FakeSlot::new();
        install.attach_slot(Arc::clone(&slot));
        assert_eq!(slot.installed(), None);

        install.install_bridge(1, radio("late"));
        assert_eq!(slot.installed(), Some("late"));
    }

    /// A stop racing a start: the old radio's `bleBridgeFree` arrives after
    /// the new radio was installed. It must not disarm the new one.
    #[test]
    fn a_late_clear_from_an_old_radio_never_retracts_the_new_one() {
        let mut install = RadioInstall::<FakeSlot>::new();
        let slot = FakeSlot::new();
        install.attach_slot(Arc::clone(&slot));

        install.install_bridge(1, radio("old"));
        install.install_bridge(2, radio("new"));

        assert!(!install.clear_bridge(1), "the old token owns nothing now");
        assert_eq!(slot.installed(), Some("new"));

        assert!(install.clear_bridge(2));
        assert_eq!(slot.installed(), None);
    }

    /// A failed `Node::start` must not leave the radio wired to a node that
    /// does not exist — but must also not destroy the radio, which Kotlin
    /// still owns and will reuse on the retry.
    #[test]
    fn a_failed_start_retracts_only_the_slot() {
        let mut install = RadioInstall::<FakeSlot>::new();
        install.install_bridge(1, radio("kept"));

        let failed = FakeSlot::new();
        install.attach_slot(Arc::clone(&failed));
        assert!(install.detach_slot(&failed));
        assert_eq!(failed.installed(), None);

        let retried = FakeSlot::new();
        install.attach_slot(Arc::clone(&retried));
        assert_eq!(
            retried.installed(),
            Some("kept"),
            "the retry must find the radio Kotlin still owns",
        );
    }

    /// A stop that timed out leaves the old node thread detached, still
    /// resolving its own slot. Publishing the next node's slot has to take the
    /// radio away from it, or two nodes drive one phone radio.
    #[test]
    fn a_detached_node_loses_the_radio_when_the_next_node_starts() {
        let mut install = RadioInstall::<FakeSlot>::new();
        install.install_bridge(1, radio("shared"));

        let detached = FakeSlot::new();
        install.attach_slot(Arc::clone(&detached));
        assert_eq!(detached.installed(), Some("shared"));

        let fresh = FakeSlot::new();
        install.attach_slot(Arc::clone(&fresh));

        assert_eq!(detached.installed(), None, "the detached node is disarmed");
        assert_eq!(fresh.installed(), Some("shared"));
    }

    /// The detached node's own late stop must not disturb the live one.
    #[test]
    fn a_detached_nodes_late_stop_does_not_disarm_the_live_slot() {
        let mut install = RadioInstall::<FakeSlot>::new();
        install.install_bridge(1, radio("shared"));
        let detached = FakeSlot::new();
        install.attach_slot(Arc::clone(&detached));
        let fresh = FakeSlot::new();
        install.attach_slot(Arc::clone(&fresh));

        assert!(
            !install.detach_slot(&detached),
            "it no longer owns the seat"
        );
        assert_eq!(fresh.installed(), Some("shared"));
    }

    /// Bluetooth off→on: Kotlin frees the radio, then builds a new one while
    /// the same node keeps running. Both edges must land on the live slot.
    #[test]
    fn a_bluetooth_cycle_reinstalls_under_a_running_node() {
        let mut install = RadioInstall::<FakeSlot>::new();
        let slot = FakeSlot::new();
        install.attach_slot(Arc::clone(&slot));
        install.install_bridge(1, radio("before"));

        assert!(install.clear_bridge(1));
        assert_eq!(slot.installed(), None, "an absent radio parks the backend");

        install.install_bridge(2, radio("after"));
        assert_eq!(slot.installed(), Some("after"));
    }

    /// Re-attaching the very same slot (an idempotent restart path) must not
    /// clear the radio out from underneath it.
    #[test]
    fn reattaching_the_same_slot_keeps_the_radio_installed() {
        let mut install = RadioInstall::<FakeSlot>::new();
        install.install_bridge(1, radio("stable"));
        let slot = FakeSlot::new();
        install.attach_slot(Arc::clone(&slot));
        install.attach_slot(Arc::clone(&slot));

        assert_eq!(slot.installed(), Some("stable"));
    }

    /// Freeing a radio the node never saw is a no-op rather than a panic.
    #[test]
    fn clearing_an_unknown_token_is_refused() {
        let mut install = RadioInstall::<FakeSlot>::new();
        assert!(!install.clear_bridge(7));

        install.install_bridge(1, radio("only"));
        assert!(!install.clear_bridge(7));
        assert!(install.clear_bridge(1));
    }
}
