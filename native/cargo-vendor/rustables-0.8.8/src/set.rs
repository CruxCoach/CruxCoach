use rustables_macros::nfnetlink_struct;

use crate::data_type::DataType;
use crate::error::BuilderError;
use crate::nlmsg::NfNetlinkObject;
use crate::parser_impls::{NfNetlinkData, NfNetlinkList};
use crate::sys::{
    NFTA_SET_ELEM_KEY, NFTA_SET_ELEM_LIST_ELEMENTS, NFTA_SET_ELEM_LIST_SET,
    NFTA_SET_ELEM_LIST_TABLE, NFTA_SET_FLAGS, NFTA_SET_ID, NFTA_SET_KEY_LEN, NFTA_SET_KEY_TYPE,
    NFTA_SET_NAME, NFTA_SET_TABLE, NFTA_SET_USERDATA, NFT_MSG_DELSET, NFT_MSG_DELSETELEM,
    NFT_MSG_NEWSET, NFT_MSG_NEWSETELEM,
};
use crate::table::Table;
use crate::ProtocolFamily;
use std::fmt::Debug;
use std::marker::PhantomData;

#[derive(Default, Debug, Clone, PartialEq, Eq)]
#[nfnetlink_struct(derive_deserialize = false)]
pub struct Set {
    pub family: ProtocolFamily,
    #[field(NFTA_SET_TABLE)]
    pub table: String,
    #[field(NFTA_SET_NAME)]
    pub name: String,
    #[field(NFTA_SET_FLAGS)]
    pub flags: u32,
    #[field(NFTA_SET_KEY_TYPE)]
    pub key_type: u32,
    #[field(NFTA_SET_KEY_LEN)]
    pub key_len: u32,
    #[field(NFTA_SET_ID)]
    pub id: u32,
    #[field(NFTA_SET_USERDATA)]
    pub userdata: Vec<u8>,
}

impl NfNetlinkObject for Set {
    const MSG_TYPE_ADD: u32 = NFT_MSG_NEWSET;
    const MSG_TYPE_DEL: u32 = NFT_MSG_DELSET;

    fn get_family(&self) -> ProtocolFamily {
        self.family
    }

    fn set_family(&mut self, family: ProtocolFamily) {
        self.family = family;
    }
}

pub struct SetBuilder<K: DataType> {
    inner: Set,
    list: SetElementList,
    _phantom: PhantomData<K>,
}

impl<K: DataType> SetBuilder<K> {
    pub fn new(name: impl Into<String>, table: &Table) -> Result<Self, BuilderError> {
        let table_name = table.get_name().ok_or(BuilderError::MissingTableName)?;
        let set_name = name.into();
        let set = Set::default()
            .with_key_type(K::TYPE)
            .with_key_len(K::LEN)
            .with_table(table_name)
            .with_name(&set_name);

        Ok(SetBuilder {
            inner: set,
            list: SetElementList {
                table: Some(table_name.clone()),
                family: table.get_family(),
                set: Some(set_name),
                elements: Some(SetElementListElements::default()),
            },
            _phantom: PhantomData,
        })
    }

    pub fn add(&mut self, key: &K) {
        self.list.elements.as_mut().unwrap().add_value(SetElement {
            key: Some(NfNetlinkData::default().with_value(key.data())),
        });
    }

    pub fn finish(self) -> (Set, SetElementList) {
        (self.inner, self.list)
    }
}

#[derive(Default, Debug, Clone, PartialEq, Eq)]
#[nfnetlink_struct(nested = true, derive_deserialize = false)]
pub struct SetElementList {
    pub family: ProtocolFamily,
    #[field(NFTA_SET_ELEM_LIST_TABLE)]
    pub table: String,
    #[field(NFTA_SET_ELEM_LIST_SET)]
    pub set: String,
    #[field(NFTA_SET_ELEM_LIST_ELEMENTS)]
    pub elements: SetElementListElements,
}

impl NfNetlinkObject for SetElementList {
    const MSG_TYPE_ADD: u32 = NFT_MSG_NEWSETELEM;
    const MSG_TYPE_DEL: u32 = NFT_MSG_DELSETELEM;

    fn get_family(&self) -> ProtocolFamily {
        self.family
    }

    fn set_family(&mut self, family: ProtocolFamily) {
        self.family = family;
    }
}

#[derive(Default, Debug, Clone, PartialEq, Eq)]
#[nfnetlink_struct(nested = true)]
pub struct SetElement {
    #[field(NFTA_SET_ELEM_KEY)]
    pub key: NfNetlinkData,
}

type SetElementListElements = NfNetlinkList<SetElement>;
