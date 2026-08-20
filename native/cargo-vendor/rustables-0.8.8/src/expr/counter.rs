use rustables_macros::nfnetlink_struct;

use super::Expression;
use crate::sys;

/// A counter expression adds a counter to the rule that is incremented to count number of packets
/// and number of bytes for all packets that have matched the rule.
#[derive(Default, Clone, Debug, PartialEq, Eq)]
#[nfnetlink_struct]
pub struct Counter {
    #[field(sys::NFTA_COUNTER_BYTES)]
    pub nb_bytes: u64,
    #[field(sys::NFTA_COUNTER_PACKETS)]
    pub nb_packets: u64,
}

impl Expression for Counter {
    fn get_name() -> &'static str {
        "counter"
    }
}
