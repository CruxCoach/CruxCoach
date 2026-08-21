//! BLE connection pool with priority eviction.
//!
//! BLE hardware limits concurrent connections (typically 4-10). The pool
//! enforces a configurable maximum and prioritizes static (configured)
//! peers over dynamically discovered ones.

use std::collections::HashMap;

use tokio::task::JoinHandle;

use crate::identity::NodeAddr;
use crate::transport::{TransportAddr, TransportError};

use super::addr::BleAddr;

/// A single BLE connection in the pool.
pub struct BleConnection<S> {
    /// The L2CAP stream for this connection.
    pub stream: S,
    /// Background receive task handle.
    pub recv_task: Option<JoinHandle<()>>,
    /// Negotiated L2CAP send MTU.
    pub send_mtu: u16,
    /// Negotiated L2CAP receive MTU.
    pub recv_mtu: u16,
    /// When the connection was established.
    pub established_at: tokio::time::Instant,
    /// Whether this is a static (configured) peer.
    pub is_static: bool,
    /// Parsed remote address.
    pub addr: BleAddr,
    /// The peer's node address, once the pubkey exchange has learned it.
    ///
    /// The pool is keyed by *link* address, but a BLE link address is not a
    /// stable identity: peers using resolvable private addresses rotate theirs
    /// continually, and each rotation looks like a brand-new device. This
    /// field carries the identity that does not rotate, so
    /// [`ConnectionPool::find_by_node`] can recognise a peer already connected
    /// under an address never seen before. `None` for a connection whose peer
    /// is not yet identified.
    pub node_addr: Option<NodeAddr>,
}

impl<S> BleConnection<S> {
    /// Effective MTU for this connection: min(send, recv).
    pub fn effective_mtu(&self) -> u16 {
        self.send_mtu.min(self.recv_mtu)
    }
}

impl<S> Drop for BleConnection<S> {
    fn drop(&mut self) {
        if let Some(task) = self.recv_task.take() {
            task.abort();
        }
    }
}

/// Connection pool managing BLE connections with priority eviction.
pub struct ConnectionPool<S> {
    connections: HashMap<TransportAddr, BleConnection<S>>,
    max_connections: usize,
    protected_discovered_connections: usize,
    capacity_changed: std::sync::Arc<tokio::sync::Notify>,
}

impl<S> ConnectionPool<S> {
    /// Create a new pool with the given maximum capacity.
    pub fn new(max_connections: usize) -> Self {
        Self::new_with_policy(max_connections, 0)
    }

    /// Create a pool with an explicit stable-backbone policy.
    pub fn new_with_policy(
        max_connections: usize,
        protected_discovered_connections: usize,
    ) -> Self {
        Self {
            connections: HashMap::new(),
            max_connections,
            protected_discovered_connections,
            capacity_changed: std::sync::Arc::new(tokio::sync::Notify::new()),
        }
    }

    /// Get the number of active connections.
    pub fn len(&self) -> usize {
        self.connections.len()
    }

    /// Check if the pool is empty.
    pub fn is_empty(&self) -> bool {
        self.connections.is_empty()
    }

    /// Check if the pool is at capacity.
    pub fn is_full(&self) -> bool {
        self.connections.len() >= self.max_connections
    }

    /// Whether a new connection of this class can be retained by the pool.
    ///
    /// Callers use this as an admission fast path before paying for an L2CAP
    /// dial or pubkey exchange. [`insert`](Self::insert) remains authoritative
    /// because another connection can fill the last slot concurrently.
    pub fn can_accept(&self, new_is_static: bool) -> bool {
        if !self.is_full() {
            return true;
        }
        self.find_eviction_candidate(new_is_static).is_ok()
    }

    /// Get the maximum pool capacity.
    pub fn max_connections(&self) -> usize {
        self.max_connections
    }

    /// Notification used by capacity-sensitive services such as BLE
    /// advertising. `Notify` stores a permit, so a transition cannot be lost
    /// if it happens just before the watcher goes back to sleep.
    pub fn capacity_notifier(&self) -> std::sync::Arc<tokio::sync::Notify> {
        std::sync::Arc::clone(&self.capacity_changed)
    }

    /// Look up a connection by transport address.
    pub fn get(&self, addr: &TransportAddr) -> Option<&BleConnection<S>> {
        self.connections.get(addr)
    }

    /// Look up a mutable connection by transport address.
    pub fn get_mut(&mut self, addr: &TransportAddr) -> Option<&mut BleConnection<S>> {
        self.connections.get_mut(addr)
    }

    /// Check if a connection exists for the given address.
    pub fn contains(&self, addr: &TransportAddr) -> bool {
        self.connections.contains_key(addr)
    }

    /// Find an existing connection to `node`, whatever link address it
    /// arrived on.
    ///
    /// This is the identity check [`Self::contains`] cannot make. A peer using
    /// resolvable private addresses presents a different link address every
    /// rotation, so an address-keyed lookup reports "not connected" for a peer
    /// that is very much connected — and the caller then opens a second link
    /// to it, and a third. Callers that know the peer's node address should
    /// ask this before admitting a connection.
    ///
    /// Only connections whose pubkey exchange has completed carry a node
    /// address, so an unidentified connection is never matched.
    pub fn find_by_node(&self, node: &NodeAddr) -> Option<TransportAddr> {
        self.connections
            .iter()
            .find(|(_, c)| c.node_addr.as_ref() == Some(node))
            .map(|(addr, _)| addr.clone())
    }

    /// The live link address for `node`, if it is connected.
    ///
    /// [`Self::find_by_node`] answers with the pool key; this answers with the
    /// `BleAddr` the link is actually on, which is what a caller needs when it
    /// has to *name* the peer's current address rather than merely test for
    /// one.
    pub fn live_addr_of_node(&self, node: &NodeAddr) -> Option<BleAddr> {
        self.connections
            .values()
            .find(|c| c.node_addr.as_ref() == Some(node))
            .map(|c| c.addr.clone())
    }

    /// Try to insert a connection, evicting if necessary.
    ///
    /// Returns `Ok(evicted_addr)` on success (with optional evicted peer),
    /// or `Err` if the pool is full and the new connection cannot evict anyone.
    pub fn insert(
        &mut self,
        addr: TransportAddr,
        conn: BleConnection<S>,
    ) -> Result<Option<TransportAddr>, TransportError> {
        use std::collections::hash_map::Entry;

        // Already connected — replace
        if let Entry::Occupied(mut e) = self.connections.entry(addr.clone()) {
            e.insert(conn);
            self.capacity_changed.notify_one();
            return Ok(None);
        }

        // Room available
        if !self.is_full() {
            self.connections.insert(addr, conn);
            self.capacity_changed.notify_one();
            return Ok(None);
        }

        // Pool full — try eviction
        let evicted = self.find_eviction_candidate(conn.is_static)?;
        self.connections.remove(&evicted);
        self.connections.insert(addr, conn);
        self.capacity_changed.notify_one();
        Ok(Some(evicted))
    }

    /// Remove a connection by address.
    pub fn remove(&mut self, addr: &TransportAddr) -> Option<BleConnection<S>> {
        let removed = self.connections.remove(addr);
        if removed.is_some() {
            self.capacity_changed.notify_one();
        }
        removed
    }

    /// Get all connection addresses.
    pub fn addrs(&self) -> Vec<TransportAddr> {
        self.connections.keys().cloned().collect()
    }

    /// Find the best eviction candidate.
    ///
    /// Static peers requesting a slot can evict the oldest unprotected,
    /// non-static peer. Dynamic peers follow the same protection boundary.
    fn find_eviction_candidate(
        &self,
        new_is_static: bool,
    ) -> Result<TransportAddr, TransportError> {
        if new_is_static {
            // Static peers preserve their legacy priority, but never pierce
            // the explicitly protected discovered backbone.
            let mut discovered: Vec<_> = self
                .connections
                .iter()
                .filter(|(_, c)| !c.is_static)
                .collect();
            discovered.sort_by_key(|(_, connection)| connection.established_at);
            discovered
                .into_iter()
                .skip(self.protected_discovered_connections)
                .min_by_key(|(_, connection)| connection.established_at)
                .map(|(addr, _)| addr.clone())
                .ok_or_else(|| {
                    TransportError::NotSupported(
                        "BLE pool full: all connections are static or protected".into(),
                    )
                })
        } else {
            let mut discovered: Vec<_> = self
                .connections
                .iter()
                .filter(|(_, c)| !c.is_static)
                .collect();
            discovered.sort_by_key(|(_, connection)| connection.established_at);

            // The oldest N links are the stable backbone. Only explicitly
            // unprotected slots retain the legacy oldest-first replacement.
            discovered
                .into_iter()
                .skip(self.protected_discovered_connections)
                .min_by_key(|(_, connection)| connection.established_at)
                .map(|(addr, _)| addr.clone())
                .ok_or_else(|| {
                    TransportError::NotSupported("BLE pool full: stable backbone protected".into())
                })
        }
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn test_addr(n: u8) -> TransportAddr {
        TransportAddr::from_string(&format!("hci0/AA:BB:CC:DD:EE:{n:02X}"))
    }

    fn test_ble_addr(n: u8) -> BleAddr {
        BleAddr {
            adapter: "hci0".to_string(),
            device: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, n],
        }
    }

    /// A distinct node identity per `n` — the identity that does NOT rotate.
    fn test_node(n: u8) -> NodeAddr {
        let mut bytes = [0u8; 16];
        bytes[0] = n;
        NodeAddr::from_bytes(bytes)
    }

    fn test_conn(n: u8, is_static: bool) -> BleConnection<()> {
        BleConnection {
            stream: (),
            recv_task: None,
            send_mtu: 2048,
            recv_mtu: 2048,
            established_at: tokio::time::Instant::now(),
            is_static,
            addr: test_ble_addr(n),
            node_addr: None,
        }
    }

    #[test]
    fn test_pool_basic_insert() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        assert!(pool.is_empty());

        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        assert_eq!(pool.len(), 1);
        assert!(!pool.is_empty());
        assert!(pool.contains(&test_addr(1)));
    }

    #[test]
    fn test_pool_remove() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        assert!(pool.remove(&test_addr(1)).is_some());
        assert!(pool.is_empty());
    }

    #[test]
    fn test_pool_full_eviction() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(3);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        pool.insert(test_addr(2), test_conn(2, false)).unwrap();
        pool.insert(test_addr(3), test_conn(3, false)).unwrap();
        assert!(pool.is_full());

        // Inserting a 4th should evict the oldest non-static
        let result = pool.insert(test_addr(4), test_conn(4, false));
        assert!(result.is_ok());
        assert!(result.unwrap().is_some()); // something was evicted
        assert_eq!(pool.len(), 3);
        assert!(pool.contains(&test_addr(4)));
    }

    #[test]
    fn protected_discovered_links_reject_a_burst_instead_of_churning() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new_with_policy(3, 3);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        pool.insert(test_addr(2), test_conn(2, false)).unwrap();
        pool.insert(test_addr(3), test_conn(3, false)).unwrap();

        let result = pool.insert(test_addr(4), test_conn(4, false));

        assert!(result.is_err());
        assert_eq!(pool.len(), 3);
        assert!(pool.contains(&test_addr(1)));
        assert!(pool.contains(&test_addr(2)));
        assert!(pool.contains(&test_addr(3)));
        assert!(!pool.contains(&test_addr(4)));
    }

    #[test]
    fn hard_limit_six_seven_eight_preserves_all_seven_then_admits_after_disconnect() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new_with_policy(7, 7);
        for peer in 1..=6 {
            assert!(
                pool.insert(test_addr(peer), test_conn(peer, false))
                    .unwrap()
                    .is_none()
            );
        }
        assert!(
            !pool.is_full(),
            "six direct peers still leave one honest join slot"
        );
        assert!(pool.can_accept(false));

        pool.insert(test_addr(7), test_conn(7, false)).unwrap();
        assert!(pool.is_full(), "seven is the hard direct-peer boundary");
        assert!(!pool.can_accept(false));

        for attempt in 0..100 {
            let candidate = if attempt % 2 == 0 { 8 } else { 9 };
            assert!(
                pool.insert(test_addr(candidate), test_conn(candidate, false))
                    .is_err()
            );
        }
        assert_eq!(7, pool.len());
        for incumbent in 1..=7 {
            assert!(
                pool.contains(&test_addr(incumbent)),
                "incumbent {incumbent} was displaced"
            );
        }

        pool.remove(&test_addr(7));
        assert!(
            pool.can_accept(false),
            "a disconnect must self-heal capacity"
        );
        assert!(
            pool.insert(test_addr(8), test_conn(8, false))
                .unwrap()
                .is_none()
        );
        assert_eq!(7, pool.len());
        for backbone in 1..=6 {
            assert!(pool.contains(&test_addr(backbone)));
        }
        assert!(pool.contains(&test_addr(8)));
    }

    #[test]
    fn protected_pool_rejects_static_replacement_of_the_stable_backbone() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new_with_policy(2, 2);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        pool.insert(test_addr(2), test_conn(2, false)).unwrap();

        let result = pool.insert(test_addr(3), test_conn(3, true));

        assert!(result.is_err());
        assert_eq!(pool.len(), 2);
        assert!(pool.contains(&test_addr(1)));
        assert!(pool.contains(&test_addr(2)));
        assert!(!pool.contains(&test_addr(3)));
    }

    #[test]
    fn protected_full_pool_declines_dynamic_admission_work() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new_with_policy(2, 2);
        assert!(pool.can_accept(false));
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        pool.insert(test_addr(2), test_conn(2, false)).unwrap();

        assert!(!pool.can_accept(false));
        assert!(!pool.can_accept(true));
    }

    #[test]
    fn an_explicitly_unprotected_slot_rotates_without_touching_the_backbone() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new_with_policy(3, 2);
        let mut first = test_conn(1, false);
        first.established_at -= std::time::Duration::from_secs(30);
        let mut second = test_conn(2, false);
        second.established_at -= std::time::Duration::from_secs(20);
        let mut rotating = test_conn(3, false);
        rotating.established_at -= std::time::Duration::from_secs(10);
        pool.insert(test_addr(1), first).unwrap();
        pool.insert(test_addr(2), second).unwrap();
        pool.insert(test_addr(3), rotating).unwrap();

        let evicted = pool.insert(test_addr(4), test_conn(4, false)).unwrap();

        assert_eq!(Some(test_addr(3)), evicted);
        assert!(pool.contains(&test_addr(1)));
        assert!(pool.contains(&test_addr(2)));
        assert!(pool.contains(&test_addr(4)));
    }

    #[test]
    fn nineteen_simultaneous_joiners_redirect_until_all_twenty_nodes_converge() {
        use std::collections::BTreeSet;

        const PARTICIPANTS: usize = 20;
        const DEGREE: usize = 7;
        let mut pools: Vec<ConnectionPool<()>> = (0..PARTICIPANTS)
            .map(|_| ConnectionPool::new_with_policy(DEGREE, DEGREE))
            .collect();
        let mut edges = vec![BTreeSet::new(); PARTICIPANTS];
        let mut joined = BTreeSet::from([0usize]);
        // Every joiner initially saw only the controller's advertisement.
        let mut pending: Vec<(usize, Vec<usize>)> =
            (1..PARTICIPANTS).map(|node| (node, vec![0])).collect();
        let mut capacity_redirects = 0;

        for _round in 0..PARTICIPANTS {
            if pending.is_empty() {
                break;
            }
            let advertisers: Vec<_> = joined
                .iter()
                .copied()
                .filter(|node| !pools[*node].is_full())
                .collect();
            let mut retry = Vec::new();

            for (node, candidates) in pending {
                let target = candidates
                    .into_iter()
                    .find(|candidate| {
                        advertisers.contains(candidate) && !pools[*candidate].is_full()
                    })
                    .or_else(|| {
                        advertisers
                            .iter()
                            .copied()
                            .find(|candidate| *candidate != node && !pools[*candidate].is_full())
                    });
                let Some(target) = target else {
                    retry.push((node, advertisers.clone()));
                    capacity_redirects += 1;
                    continue;
                };

                let to_target = test_addr(target as u8);
                let to_joiner = test_addr(node as u8);
                assert!(
                    pools[node]
                        .insert(to_target, test_conn(target as u8, false))
                        .unwrap()
                        .is_none()
                );
                assert!(
                    pools[target]
                        .insert(to_joiner, test_conn(node as u8, false))
                        .unwrap()
                        .is_none()
                );
                edges[node].insert(target);
                edges[target].insert(node);
                joined.insert(node);
            }
            pending = retry;
        }

        assert_eq!(
            PARTICIPANTS,
            joined.len(),
            "every simultaneous joiner must find fallback capacity"
        );
        assert!(
            capacity_redirects > 0,
            "the chaos wave must exercise redirect/backoff"
        );
        assert!(pools.iter().all(|pool| pool.len() <= DEGREE));
        assert!(edges.iter().all(|neighbors| neighbors.len() <= DEGREE));

        let mut reached = BTreeSet::from([0usize]);
        let mut frontier = vec![0usize];
        while let Some(node) = frontier.pop() {
            for peer in &edges[node] {
                if reached.insert(*peer) {
                    frontier.push(*peer);
                }
            }
        }
        assert_eq!(
            PARTICIPANTS,
            reached.len(),
            "the resulting bounded-degree mesh must be connected"
        );
    }

    #[test]
    fn test_pool_static_evicts_nonstatic() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(2);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        pool.insert(test_addr(2), test_conn(2, false)).unwrap();

        // Static peer should evict a non-static
        let result = pool.insert(test_addr(3), test_conn(3, true));
        assert!(result.is_ok());
        assert_eq!(pool.len(), 2);
        assert!(pool.contains(&test_addr(3)));
    }

    #[test]
    fn test_pool_all_static_rejects() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(2);
        pool.insert(test_addr(1), test_conn(1, true)).unwrap();
        pool.insert(test_addr(2), test_conn(2, true)).unwrap();

        // Non-static peer cannot evict static peers
        let result = pool.insert(test_addr(3), test_conn(3, false));
        assert!(result.is_err());
    }

    #[test]
    fn test_pool_replace_existing() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(2);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();

        // Re-inserting same address should replace, not grow
        let result = pool.insert(test_addr(1), test_conn(1, true));
        assert!(result.is_ok());
        assert_eq!(pool.len(), 1);
        assert!(pool.get(&test_addr(1)).unwrap().is_static);
    }

    #[test]
    fn test_pool_effective_mtu() {
        let mut conn = test_conn(1, false);
        conn.send_mtu = 1024;
        conn.recv_mtu = 2048;
        assert_eq!(conn.effective_mtu(), 1024);
    }

    #[test]
    fn test_pool_addrs() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        pool.insert(test_addr(2), test_conn(2, false)).unwrap();

        let mut addrs = pool.addrs();
        addrs.sort_by(|a, b| a.as_str().cmp(&b.as_str()));
        assert_eq!(addrs.len(), 2);
    }

    /// A node address is found regardless of which link address it arrived on
    /// — the whole point of the lookup, since the link address rotates.
    #[test]
    fn test_find_by_node_matches_across_a_rotated_link_address() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        let node = test_node(1);
        let mut conn = test_conn(1, false);
        conn.node_addr = Some(node);
        pool.insert(test_addr(1), conn).unwrap();

        // Found under the address it was inserted with...
        assert_eq!(pool.find_by_node(&node), Some(test_addr(1)));
        assert!(pool.contains(&test_addr(1)));
        // ...and a rotated address for the same peer is NOT found by
        // `contains`, which is exactly the gap `find_by_node` closes.
        assert!(!pool.contains(&test_addr(99)));
        assert_eq!(pool.find_by_node(&node), Some(test_addr(1)));
    }

    #[test]
    fn test_find_by_node_ignores_unidentified_connections() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        // No pubkey exchange yet, so no node address.
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        assert_eq!(pool.find_by_node(&test_node(1)), None);
    }

    #[test]
    fn test_find_by_node_returns_none_for_an_unconnected_node() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        let mut conn = test_conn(1, false);
        conn.node_addr = Some(test_node(1));
        pool.insert(test_addr(1), conn).unwrap();
        assert_eq!(pool.find_by_node(&test_node(2)), None);
    }

    /// Distinct nodes do not alias: each resolves to its own link address.
    #[test]
    fn test_find_by_node_distinguishes_two_nodes() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        let mut a = test_conn(1, false);
        a.node_addr = Some(test_node(1));
        let mut b = test_conn(2, false);
        b.node_addr = Some(test_node(2));
        pool.insert(test_addr(1), a).unwrap();
        pool.insert(test_addr(2), b).unwrap();

        assert_eq!(pool.find_by_node(&test_node(1)), Some(test_addr(1)));
        assert_eq!(pool.find_by_node(&test_node(2)), Some(test_addr(2)));
    }

    /// The live link address is reported for a peer found under any of its
    /// rotated aliases — what a caller needs when it has to name the peer's
    /// current address rather than merely test for one.
    #[test]
    fn test_live_addr_of_node_reports_the_incumbent_not_the_alias() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        let node = test_node(1);
        let mut conn = test_conn(1, false);
        conn.node_addr = Some(node);
        pool.insert(test_addr(1), conn).unwrap();

        assert_eq!(pool.live_addr_of_node(&node), Some(test_ble_addr(1)));
        assert!(!pool.contains(&test_addr(99)));
        // An unconnected node has no incumbent, so the caller keeps whatever
        // address it observed.
        assert_eq!(pool.live_addr_of_node(&test_node(2)), None);
    }

    #[test]
    fn test_live_addr_of_node_ignores_unidentified_connections() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        pool.insert(test_addr(1), test_conn(1, false)).unwrap();
        assert_eq!(pool.live_addr_of_node(&test_node(1)), None);
    }

    /// The regression this guards: without a node-identity check, N rotated
    /// addresses for ONE peer become N pool entries and evict real peers.
    /// With it, the caller sees the peer is already present and declines.
    #[test]
    fn test_rotated_addresses_would_otherwise_fill_the_pool() {
        let mut pool: ConnectionPool<()> = ConnectionPool::new(7);
        let node = test_node(1);

        let mut first = test_conn(1, false);
        first.node_addr = Some(node);
        pool.insert(test_addr(1), first).unwrap();

        // Ten rotations arrive. Each is a distinct link address, so `contains`
        // says "new" every time — but `find_by_node` recognises all of them.
        for n in 2..12u8 {
            assert!(
                !pool.contains(&test_addr(n)),
                "rotation {n} looks new by address"
            );
            assert_eq!(
                pool.find_by_node(&node),
                Some(test_addr(1)),
                "rotation {n} is recognised as the peer already connected",
            );
        }
        // Nothing was admitted, so the pool still holds exactly one link, and
        // it is the incumbent — the first one, not the newest.
        assert_eq!(pool.len(), 1);
        assert!(pool.contains(&test_addr(1)));
    }
}
