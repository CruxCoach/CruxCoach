// Copyryght (c) 2021-2022 GPL lafleur@boum.org and Simon Thoby
//
// This file is free software: you may copy, redistribute and/or modify it
// under the terms of the GNU General Public License as published by the
// Free Software Foundation, either version 3 of the License, or (at your
// option) any later version.
//
// This file is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program. If not, see the LICENSE file.
//
// This file incorporates work covered by the following copyright and
// permission notice:
//
//     Copyright 2018 Amagicom AB.
//
//     Licensed under the Apache License, Version 2.0 <LICENSE-APACHE or
//     http://www.apache.org/licenses/LICENSE-2.0> or the MIT license
//     <LICENSE-MIT or http://opensource.org/licenses/MIT>, at your
//     option. This file may not be copied, modified, or distributed
//     except according to those terms.

//! Safe abstraction for userspace access to the in-kernel nf_tables subsystem.
//! Can be used to create and remove tables, chains, sets and rules from the nftables
//! firewall, the successor to iptables.
//!
//! This library currently has quite rough edges and does not make adding and removing netfilter
//! entries super easy and elegant. That is partly because the library needs more work, but also
//! partly because nftables is super low level and extremely customizable, making it hard, and
//! probably wrong, to try and create a too simple/limited wrapper. See examples for inspiration.
//!
//! Understanding how to use the netlink subsystem and implementing this crate has mostly been done by
//! reading the source code for the [`nftables`] userspace program and its corresponding kernel code,
//! as well as attaching debuggers to the `nft` binary.
//! Since the implementation is mostly based on trial and error, there might of course be
//! a number of places where the forged netlink messages are used in an invalid or not intended way.
//! Contributions are welcome!
//!
//! [`nftables`]: https://netfilter.org/projects/nftables/

#[macro_use]
extern crate log;

use rustables_macros::nfnetlink_enum;
use std::convert::TryFrom;

mod batch;
pub use batch::{Batch, default_batch_page_size};

pub mod data_type;

mod table;
pub use table::Table;
pub use table::list_tables;

mod chain;
pub use chain::list_chains_for_table;
pub use chain::{Chain, ChainPolicy, ChainPriority, ChainType, Hook, HookClass};

pub mod error;

pub mod query;

pub(crate) mod nlmsg;
pub(crate) mod parser;
pub(crate) mod parser_impls;

mod rule;
pub use rule::Rule;
pub use rule::list_rules_for_chain;

pub mod expr;

mod rule_methods;
pub use rule_methods::{Protocol, iface_index};

pub mod set;
pub use set::Set;

pub mod sys;

#[cfg(test)]
mod tests;

/// The type of the message as it's sent to netfilter. A message consists of an object, such as a
/// [`Table`], [`Chain`] or [`Rule`] for example, and a [`MsgType`] to describe what to do with
/// that object. If a [`Table`] object is sent with `MsgType::Add` then that table will be added
/// to netfilter, if sent with `MsgType::Del` it will be removed.
///
/// [`Table`]: struct.Table.html
/// [`Chain`]: struct.Chain.html
/// [`Rule`]: struct.Rule.html
/// [`MsgType`]: enum.MsgType.html
#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum MsgType {
    /// Add the object to netfilter.
    Add,
    /// Remove the object from netfilter.
    Del,
}

/// Denotes a protocol. Used to specify which protocol a table or set belongs to.
#[derive(Default, Debug, Copy, Clone, PartialEq, Eq)]
#[nfnetlink_enum(i32)]
pub enum ProtocolFamily {
    #[default]
    Unspec = libc::NFPROTO_UNSPEC,
    /// Inet - Means both IPv4 and IPv6
    Inet = libc::NFPROTO_INET,
    Ipv4 = libc::NFPROTO_IPV4,
    Arp = libc::NFPROTO_ARP,
    NetDev = libc::NFPROTO_NETDEV,
    Bridge = libc::NFPROTO_BRIDGE,
    Ipv6 = libc::NFPROTO_IPV6,
    DecNet = libc::NFPROTO_DECNET,
}
