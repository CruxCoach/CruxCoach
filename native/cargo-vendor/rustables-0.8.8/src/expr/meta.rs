use rustables_macros::{nfnetlink_enum, nfnetlink_struct};

use super::{Expression, Register};
use crate::sys;

/// A meta expression refers to meta data associated with a packet.
#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[nfnetlink_enum(u32)]
#[non_exhaustive]
pub enum MetaType {
    /// Packet ethertype protocol (skb->protocol), invalid in OUTPUT.
    Protocol = sys::NFT_META_PROTOCOL,
    /// Packet mark.
    Mark = sys::NFT_META_MARK,
    /// Packet input interface index (dev->ifindex).
    Iif = sys::NFT_META_IIF,
    /// Packet output interface index (dev->ifindex).
    Oif = sys::NFT_META_OIF,
    /// Packet input interface name (dev->name).
    IifName = sys::NFT_META_IIFNAME,
    /// Packet output interface name (dev->name).
    OifName = sys::NFT_META_OIFNAME,
    /// Packet input interface type (dev->type).
    IifType = libc::NFT_META_IIFTYPE,
    /// Packet output interface type (dev->type).
    OifType = sys::NFT_META_OIFTYPE,
    /// Originating socket UID (fsuid).
    SkUid = sys::NFT_META_SKUID,
    /// Originating socket GID (fsgid).
    SkGid = sys::NFT_META_SKGID,
    /// Netfilter protocol (Transport layer protocol).
    NfProto = sys::NFT_META_NFPROTO,
    /// Layer 4 protocol number.
    L4Proto = sys::NFT_META_L4PROTO,
    /// Socket control group (skb->sk->sk_classid).
    Cgroup = sys::NFT_META_CGROUP,
    /// A 32bit pseudo-random number.
    PRandom = sys::NFT_META_PRANDOM,
}

#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct]
pub struct Meta {
    #[field(sys::NFTA_META_DREG)]
    dreg: Register,
    #[field(sys::NFTA_META_KEY)]
    key: MetaType,
    #[field(sys::NFTA_META_SREG)]
    sreg: Register,
}

impl Meta {
    pub fn new(ty: MetaType) -> Self {
        Meta::default().with_dreg(Register::Reg1).with_key(ty)
    }
}

impl Expression for Meta {
    fn get_name() -> &'static str {
        "meta"
    }
}
