use rustables_macros::nfnetlink_struct;

use super::{Expression, Register, Verdict, VerdictKind, VerdictType};
use crate::{
    parser_impls::NfNetlinkData,
    sys::{NFTA_IMMEDIATE_DATA, NFTA_IMMEDIATE_DREG},
};

#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct]
pub struct Immediate {
    #[field(NFTA_IMMEDIATE_DREG)]
    dreg: Register,
    #[field(NFTA_IMMEDIATE_DATA)]
    data: NfNetlinkData,
}

impl Immediate {
    pub fn new_data(data: Vec<u8>, register: Register) -> Self {
        Immediate::default()
            .with_dreg(register)
            .with_data(NfNetlinkData::default().with_value(data))
    }

    pub fn new_verdict(kind: VerdictKind) -> Self {
        let code = match kind {
            VerdictKind::Drop => VerdictType::Drop,
            VerdictKind::Accept => VerdictType::Accept,
            VerdictKind::Queue => VerdictType::Queue,
            VerdictKind::Continue => VerdictType::Continue,
            VerdictKind::Break => VerdictType::Break,
            VerdictKind::Jump { .. } => VerdictType::Jump,
            VerdictKind::Goto { .. } => VerdictType::Goto,
            VerdictKind::Return => VerdictType::Return,
        };
        let mut data = Verdict::default().with_code(code);
        if let VerdictKind::Jump { chain } | VerdictKind::Goto { chain } = kind {
            data.set_chain(chain);
        }
        Immediate::default()
            .with_dreg(Register::Verdict)
            .with_data(NfNetlinkData::default().with_verdict(data))
    }
}

impl Expression for Immediate {
    fn get_name() -> &'static str {
        "immediate"
    }
}
