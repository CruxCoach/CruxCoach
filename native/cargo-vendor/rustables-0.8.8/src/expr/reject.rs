use rustables_macros::{nfnetlink_enum, nfnetlink_struct};

use crate::sys;

use super::Expression;

impl Expression for Reject {
    fn get_name() -> &'static str {
        "reject"
    }
}

#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct]
/// A reject expression that defines the type of rejection message sent when discarding a packet.
pub struct Reject {
    #[field(sys::NFTA_REJECT_TYPE, name_in_functions = "type")]
    reject_type: RejectType,
    #[field(sys::NFTA_REJECT_ICMP_CODE)]
    icmp_code: IcmpCode,
}

/// An ICMP reject code.
#[derive(Debug, Clone, Copy, Eq, PartialEq, Hash)]
#[nfnetlink_enum(u32)]
pub enum RejectType {
    IcmpUnreach = sys::NFT_REJECT_ICMP_UNREACH,
    TcpRst = sys::NFT_REJECT_TCP_RST,
    IcmpxUnreach = sys::NFT_REJECT_ICMPX_UNREACH,
}

/// An ICMP reject code.
#[derive(Debug, Clone, Copy, Eq, PartialEq, Hash)]
#[nfnetlink_enum(u8)]
pub enum IcmpCode {
    NoRoute = sys::NFT_REJECT_ICMPX_NO_ROUTE,
    PortUnreach = sys::NFT_REJECT_ICMPX_PORT_UNREACH,
    HostUnreach = sys::NFT_REJECT_ICMPX_HOST_UNREACH,
    AdminProhibited = sys::NFT_REJECT_ICMPX_ADMIN_PROHIBITED,
}
