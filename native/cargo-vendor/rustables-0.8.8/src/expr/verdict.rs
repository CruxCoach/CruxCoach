use std::fmt::Debug;

use libc::{NF_ACCEPT, NF_DROP, NF_QUEUE};
use rustables_macros::{nfnetlink_enum, nfnetlink_struct};

use crate::sys::{
    NFTA_VERDICT_CHAIN, NFTA_VERDICT_CODE, NFT_BREAK, NFT_CONTINUE, NFT_GOTO, NFT_JUMP, NFT_RETURN,
};

#[derive(Debug, Copy, Clone, Eq, PartialEq, Hash)]
#[nfnetlink_enum(i32)]
pub enum VerdictType {
    Drop = NF_DROP,
    Accept = NF_ACCEPT,
    Queue = NF_QUEUE,
    Continue = NFT_CONTINUE,
    Break = NFT_BREAK,
    Jump = NFT_JUMP,
    Goto = NFT_GOTO,
    Return = NFT_RETURN,
}

#[nfnetlink_struct(nested = true)]
#[derive(Clone, PartialEq, Eq, Default, Debug)]
pub struct Verdict {
    #[field(NFTA_VERDICT_CODE)]
    code: VerdictType,
    #[field(NFTA_VERDICT_CHAIN)]
    chain: String,
    #[field(optional = true, crate::sys::NFTA_VERDICT_CHAIN_ID)]
    chain_id: u32,
}

#[derive(Debug, Clone, Eq, PartialEq, Hash)]
pub enum VerdictKind {
    /// Silently drop the packet.
    Drop,
    /// Accept the packet and let it pass.
    Accept,
    Queue,
    Continue,
    Break,
    Jump {
        chain: String,
    },
    Goto {
        chain: String,
    },
    Return,
}
