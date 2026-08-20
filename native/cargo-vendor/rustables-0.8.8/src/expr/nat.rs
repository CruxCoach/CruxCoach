use rustables_macros::{nfnetlink_enum, nfnetlink_struct};

use super::{Expression, Register};
use crate::{
    sys::{self, NFT_NAT_DNAT, NFT_NAT_SNAT},
    ProtocolFamily,
};

#[derive(Debug, Copy, Clone, Eq, PartialEq, Hash)]
#[nfnetlink_enum(i32)]
pub enum NatType {
    /// Source NAT. Changes the source address of a packet.
    SNat = NFT_NAT_SNAT,
    /// Destination NAT. Changes the destination address of a packet.
    DNat = NFT_NAT_DNAT,
}

/// A source or destination NAT statement. Modifies the source or destination address (and possibly
/// port) of packets.
#[derive(Default, Debug, Clone, PartialEq, Eq)]
#[nfnetlink_struct(nested = true)]
pub struct Nat {
    #[field(sys::NFTA_NAT_TYPE)]
    pub nat_type: NatType,
    #[field(sys::NFTA_NAT_FAMILY)]
    pub family: ProtocolFamily,
    #[field(sys::NFTA_NAT_REG_ADDR_MIN)]
    pub ip_register: Register,
    #[field(sys::NFTA_NAT_REG_PROTO_MIN)]
    pub port_register: Register,
}

impl Expression for Nat {
    fn get_name() -> &'static str {
        "nat"
    }
}
