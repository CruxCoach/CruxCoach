use rustables_macros::{nfnetlink_enum, nfnetlink_struct};

use crate::sys::{
    NFTA_CT_DIRECTION, NFTA_CT_DREG, NFTA_CT_KEY, NFTA_CT_SREG, NFT_CT_MARK, NFT_CT_STATE,
};

use super::{Expression, Register};

bitflags::bitflags! {
    pub struct ConnTrackState: u32 {
        const INVALID = 1;
        const ESTABLISHED = 2;
        const RELATED = 4;
        const NEW = 8;
        const UNTRACKED = 64;
    }
}

#[derive(Copy, Clone, Debug, PartialEq, Eq)]
#[nfnetlink_enum(u32, nested = true)]
pub enum ConntrackKey {
    State = NFT_CT_STATE,
    Mark = NFT_CT_MARK,
}

#[derive(Default, Clone, Debug, PartialEq, Eq)]
#[nfnetlink_struct(nested = true)]
pub struct Conntrack {
    #[field(NFTA_CT_DREG)]
    pub dreg: Register,
    #[field(NFTA_CT_KEY)]
    pub key: ConntrackKey,
    #[field(NFTA_CT_DIRECTION)]
    pub direction: u8,
    #[field(NFTA_CT_SREG)]
    pub sreg: Register,
}

impl Expression for Conntrack {
    fn get_name() -> &'static str {
        "ct"
    }
}

impl Conntrack {
    pub fn new(key: ConntrackKey) -> Self {
        Self::default().with_dreg(Register::Reg1).with_key(key)
    }

    pub fn set_mark_value(&mut self, reg: Register) {
        self.set_sreg(reg);
        self.set_key(ConntrackKey::Mark);
    }

    pub fn with_mark_value(mut self, reg: Register) -> Self {
        self.set_mark_value(reg);
        self
    }

    pub fn retrieve_value(&mut self, key: ConntrackKey) {
        self.set_key(key);
        self.set_dreg(Register::Reg1);
    }

    pub fn with_retrieve_value(mut self, key: ConntrackKey) -> Self {
        self.retrieve_value(key);
        self
    }
}
