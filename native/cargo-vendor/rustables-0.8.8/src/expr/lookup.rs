use rustables_macros::nfnetlink_struct;

use super::{Expression, Register};
use crate::error::BuilderError;
use crate::sys::{NFTA_LOOKUP_DREG, NFTA_LOOKUP_SET, NFTA_LOOKUP_SET_ID, NFTA_LOOKUP_SREG};
use crate::Set;

#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct]
pub struct Lookup {
    #[field(NFTA_LOOKUP_SET)]
    set: String,
    #[field(NFTA_LOOKUP_SREG)]
    sreg: Register,
    #[field(NFTA_LOOKUP_DREG)]
    dreg: Register,
    #[field(NFTA_LOOKUP_SET_ID)]
    set_id: u32,
}

impl Lookup {
    /// Creates a new lookup entry. May return BuilderError::MissingSetName if the set has no name.
    pub fn new(set: &Set) -> Result<Self, BuilderError> {
        let mut res = Lookup::default()
            .with_set(set.get_name().ok_or(BuilderError::MissingSetName)?)
            .with_sreg(Register::Reg1);

        if let Some(id) = set.get_id() {
            res.set_set_id(*id);
        }

        Ok(res)
    }
}

impl Expression for Lookup {
    fn get_name() -> &'static str {
        "lookup"
    }
}
