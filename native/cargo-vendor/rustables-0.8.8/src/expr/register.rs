use std::fmt::Debug;

use rustables_macros::nfnetlink_enum;

use crate::sys::{NFT_REG_1, NFT_REG_2, NFT_REG_3, NFT_REG_4, NFT_REG_VERDICT};

/// A netfilter data register. The expressions store and read data to and from these when
/// evaluating rule statements.
#[derive(Debug, Copy, Clone, Eq, PartialEq, Hash)]
#[nfnetlink_enum(u32)]
pub enum Register {
    Verdict = NFT_REG_VERDICT,
    Reg1 = NFT_REG_1,
    Reg2 = NFT_REG_2,
    Reg3 = NFT_REG_3,
    Reg4 = NFT_REG_4,
}
