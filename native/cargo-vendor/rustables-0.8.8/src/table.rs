use std::fmt::Debug;

use rustables_macros::nfnetlink_struct;

use crate::error::QueryError;
use crate::nlmsg::NfNetlinkObject;
use crate::sys::{
    NFT_MSG_DELTABLE, NFT_MSG_GETTABLE, NFT_MSG_NEWTABLE, NFTA_TABLE_FLAGS, NFTA_TABLE_NAME,
};
use crate::{Batch, ProtocolFamily};

/// Abstraction of a `nftnl_table`, the top level container in netfilter. A table has a protocol
/// family and contains [`Chain`]s that in turn hold the rules.
///
/// [`Chain`]: struct.Chain.html
#[nfnetlink_struct(derive_deserialize = false)]
#[derive(Default, PartialEq, Eq, Debug)]
pub struct Table {
    family: ProtocolFamily,
    #[field(NFTA_TABLE_NAME)]
    name: String,
    #[field(NFTA_TABLE_FLAGS)]
    flags: u32,
    #[field(optional = true, crate::sys::NFTA_TABLE_USERDATA)]
    userdata: Vec<u8>,
}

impl Table {
    pub fn new(family: ProtocolFamily) -> Table {
        Self {
            family,
            ..Self::default()
        }
    }

    /// Appends this rule to `batch`
    pub fn add_to_batch(self, batch: &mut Batch) -> Self {
        batch.add(&self, crate::MsgType::Add);
        self
    }
}

impl NfNetlinkObject for Table {
    const MSG_TYPE_ADD: u32 = NFT_MSG_NEWTABLE;
    const MSG_TYPE_DEL: u32 = NFT_MSG_DELTABLE;

    fn get_family(&self) -> ProtocolFamily {
        self.family
    }

    fn set_family(&mut self, family: ProtocolFamily) {
        self.family = family;
    }
}

pub fn list_tables() -> Result<Vec<Table>, QueryError> {
    let mut result = Vec::new();
    crate::query::list_objects_with_data(
        NFT_MSG_GETTABLE as u16,
        &|table: Table, tables: &mut Vec<Table>| {
            tables.push(table);
            Ok(())
        },
        None,
        &mut result,
    )?;
    Ok(result)
}
