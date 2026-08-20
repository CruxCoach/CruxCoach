use rustables_macros::{nfnetlink_enum, nfnetlink_struct};

use crate::{
    parser_impls::NfNetlinkData,
    sys::{
        NFTA_CMP_DATA, NFTA_CMP_OP, NFTA_CMP_SREG, NFT_CMP_EQ, NFT_CMP_GT, NFT_CMP_GTE, NFT_CMP_LT,
        NFT_CMP_LTE, NFT_CMP_NEQ,
    },
};

use super::{Expression, Register};

/// Comparison operator.
#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[nfnetlink_enum(u32, nested = true)]
pub enum CmpOp {
    /// Equals.
    Eq = NFT_CMP_EQ,
    /// Not equal.
    Neq = NFT_CMP_NEQ,
    /// Less than.
    Lt = NFT_CMP_LT,
    /// Less than, or equal.
    Lte = NFT_CMP_LTE,
    /// Greater than.
    Gt = NFT_CMP_GT,
    /// Greater than, or equal.
    Gte = NFT_CMP_GTE,
}

/// Comparator expression. Allows comparing the content of the netfilter register with any value.
#[derive(Default, Debug, Clone, PartialEq, Eq)]
#[nfnetlink_struct]
pub struct Cmp {
    #[field(NFTA_CMP_SREG)]
    sreg: Register,
    #[field(NFTA_CMP_OP)]
    op: CmpOp,
    #[field(NFTA_CMP_DATA)]
    data: NfNetlinkData,
}

impl Cmp {
    /// Returns a new comparison expression comparing the value loaded in the register with the
    /// data in `data` using the comparison operator `op`.
    pub fn new(op: CmpOp, data: impl Into<Vec<u8>>) -> Self {
        Cmp {
            sreg: Some(Register::Reg1),
            op: Some(op),
            data: Some(NfNetlinkData::default().with_value(data.into())),
        }
    }
}

impl Expression for Cmp {
    fn get_name() -> &'static str {
        "cmp"
    }
}

/*
/// Can be used to compare the value loaded by [`Meta::IifName`] and [`Meta::OifName`]. Please note
/// that it is faster to check interface index than name.
///
/// [`Meta::IifName`]: enum.Meta.html#variant.IifName
/// [`Meta::OifName`]: enum.Meta.html#variant.OifName
#[derive(Debug, Clone, Eq, PartialEq, Hash)]
pub enum InterfaceName {
    /// Interface name must be exactly the value of the `CString`.
    Exact(CString),
    /// Interface name must start with the value of the `CString`.
    ///
    /// `InterfaceName::StartingWith("eth")` will look like `eth*` when printed and match against
    /// `eth0`, `eth1`, ..., `eth99` and so on.
    StartingWith(CString),
}

impl ToSlice for InterfaceName {
    fn to_slice(&self) -> Cow<'_, [u8]> {
        let bytes = match *self {
            InterfaceName::Exact(ref name) => name.as_bytes_with_nul(),
            InterfaceName::StartingWith(ref name) => name.as_bytes(),
        };
        Cow::from(bytes)
    }
}
*/
