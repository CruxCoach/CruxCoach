use libc::{NF_ACCEPT, NF_DROP};
use rustables_macros::nfnetlink_struct;

use crate::error::{DecodeError, QueryError};
use crate::nlmsg::{NfNetlinkAttribute, NfNetlinkDeserializable, NfNetlinkObject};
use crate::sys::{
    NFT_MSG_DELCHAIN, NFT_MSG_NEWCHAIN, NFTA_CHAIN_FLAGS, NFTA_CHAIN_HOOK, NFTA_CHAIN_NAME,
    NFTA_CHAIN_POLICY, NFTA_CHAIN_TABLE, NFTA_CHAIN_TYPE, NFTA_HOOK_HOOKNUM, NFTA_HOOK_PRIORITY,
};
use crate::{Batch, ProtocolFamily, Table};
use std::fmt::Debug;

pub type ChainPriority = i32;

/// The netfilter event hooks a chain can register for.
#[derive(Debug, Copy, Clone, Eq, PartialEq, PartialOrd, Ord, Hash)]
#[repr(i32)]
pub enum HookClass {
    /// Hook into the pre-routing stage of netfilter. Corresponds to `NF_INET_PRE_ROUTING`.
    PreRouting = libc::NF_INET_PRE_ROUTING,
    /// Hook into the input stage of netfilter. Corresponds to `NF_INET_LOCAL_IN`.
    In = libc::NF_INET_LOCAL_IN,
    /// Hook into the forward stage of netfilter. Corresponds to `NF_INET_FORWARD`.
    Forward = libc::NF_INET_FORWARD,
    /// Hook into the output stage of netfilter. Corresponds to `NF_INET_LOCAL_OUT`.
    Out = libc::NF_INET_LOCAL_OUT,
    /// Hook into the post-routing stage of netfilter. Corresponds to `NF_INET_POST_ROUTING`.
    PostRouting = libc::NF_INET_POST_ROUTING,
}

#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct(nested = true)]
pub struct Hook {
    /// Define the action netfilter will apply to packets processed by this chain, but that did not match any rules in it.
    #[field(NFTA_HOOK_HOOKNUM)]
    class: u32,
    #[field(NFTA_HOOK_PRIORITY)]
    priority: u32,
}

impl Hook {
    pub fn new(class: HookClass, priority: ChainPriority) -> Self {
        Hook::default()
            .with_class(class as u32)
            .with_priority(priority as u32)
    }
}

/// A chain policy. Decides what to do with a packet that was processed by the chain but did not
/// match any rules.
#[derive(Debug, Copy, Clone, Eq, PartialEq, PartialOrd, Ord, Hash)]
#[repr(i32)]
pub enum ChainPolicy {
    /// Accept the packet.
    Accept = NF_ACCEPT,
    /// Drop the packet.
    Drop = NF_DROP,
}

impl NfNetlinkAttribute for ChainPolicy {
    fn get_size(&self) -> usize {
        (*self as i32).get_size()
    }

    fn write_payload(&self, addr: &mut [u8]) {
        (*self as i32).write_payload(addr);
    }
}

impl NfNetlinkDeserializable for ChainPolicy {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        let (v, remaining_data) = i32::deserialize(buf)?;
        Ok((
            match v {
                NF_ACCEPT => ChainPolicy::Accept,
                NF_DROP => ChainPolicy::Accept,
                _ => return Err(DecodeError::UnknownChainPolicy),
            },
            remaining_data,
        ))
    }
}

/// Base chain type.
#[derive(Debug, Copy, Clone, Eq, PartialEq, Hash)]
pub enum ChainType {
    /// Used to filter packets.
    /// Supported protocols: ip, ip6, inet, arp, and bridge tables.
    Filter,
    /// Used to reroute packets if IP headers or packet marks are modified.
    /// Supported protocols: ip, and ip6 tables.
    Route,
    /// Used to perform NAT.
    /// Supported protocols: ip, and ip6 tables.
    Nat,
}

impl ChainType {
    fn as_str(&self) -> &'static str {
        match *self {
            ChainType::Filter => "filter",
            ChainType::Route => "route",
            ChainType::Nat => "nat",
        }
    }
}

impl NfNetlinkAttribute for ChainType {
    fn get_size(&self) -> usize {
        self.as_str().len()
    }

    fn write_payload(&self, addr: &mut [u8]) {
        self.as_str().to_string().write_payload(addr);
    }
}

impl NfNetlinkDeserializable for ChainType {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        let (s, remaining_data) = String::deserialize(buf)?;
        Ok((
            match s.as_str() {
                "filter" => ChainType::Filter,
                "route" => ChainType::Route,
                "nat" => ChainType::Nat,
                _ => return Err(DecodeError::UnknownChainType),
            },
            remaining_data,
        ))
    }
}

/// Abstraction over an nftable chain. Chains reside inside [`Table`]s and they hold [`Rule`]s.
///
/// [`Table`]: struct.Table.html
/// [`Rule`]: struct.Rule.html
#[nfnetlink_struct(derive_deserialize = false)]
#[derive(PartialEq, Eq, Default, Debug)]
pub struct Chain {
    family: ProtocolFamily,
    #[field(NFTA_CHAIN_TABLE)]
    table: String,
    #[field(NFTA_CHAIN_NAME)]
    name: String,
    #[field(NFTA_CHAIN_HOOK)]
    hook: Hook,
    #[field(NFTA_CHAIN_POLICY)]
    policy: ChainPolicy,
    #[field(NFTA_CHAIN_TYPE, name_in_functions = "type")]
    chain_type: ChainType,
    #[field(NFTA_CHAIN_FLAGS)]
    flags: u32,
    #[field(optional = true, crate::sys::NFTA_CHAIN_USERDATA)]
    userdata: Vec<u8>,
}

impl Chain {
    /// Creates a new chain instance inside the given [`Table`].
    ///
    /// [`Table`]: struct.Table.html
    pub fn new(table: &Table) -> Chain {
        let mut chain = Chain {
            family: table.get_family(),
            ..Chain::default()
        };

        if let Some(table_name) = table.get_name() {
            chain.set_table(table_name);
        }

        chain
    }

    /// Appends this chain to `batch`
    pub fn add_to_batch(self, batch: &mut Batch) -> Self {
        batch.add(&self, crate::MsgType::Add);
        self
    }
}

impl NfNetlinkObject for Chain {
    const MSG_TYPE_ADD: u32 = NFT_MSG_NEWCHAIN;
    const MSG_TYPE_DEL: u32 = NFT_MSG_DELCHAIN;

    fn get_family(&self) -> ProtocolFamily {
        self.family
    }

    fn set_family(&mut self, family: ProtocolFamily) {
        self.family = family;
    }
}

pub fn list_chains_for_table(table: &Table) -> Result<Vec<Chain>, QueryError> {
    let mut result = Vec::new();
    crate::query::list_objects_with_data(
        libc::NFT_MSG_GETCHAIN as u16,
        &|chain: Chain, (table, chains): &mut (&Table, &mut Vec<Chain>)| {
            if chain.get_table() == table.get_name() {
                chains.push(chain);
            } else {
                info!(
                    "Ignoring chain {:?} because it doesn't map the table {:?}",
                    chain.get_name(),
                    table.get_name()
                );
            }
            Ok(())
        },
        None,
        &mut (table, &mut result),
    )?;
    Ok(result)
}
