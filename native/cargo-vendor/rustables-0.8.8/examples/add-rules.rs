//! Adds a table, two chains and some rules to netfilter.
//!
//! This example uses `verdict accept` everywhere. So even after running this the firewall won't
//! block anything. This is so anyone trying to run this does not end up in a strange state
//! where they don't understand why their network is broken. Try changing to `verdict drop` if
//! you want to see the block working.
//!
//! Run the following to print out current active tables, chains and rules in netfilter. Must be
//! executed as root:
//! ```bash
//! # nft list ruleset
//! ```
//! After running this example, the output should be the following:
//! ```ignore
//! table inet example-table {
//!         chain chain-for-outgoing-packets {
//!                 type filter hook output priority 0; policy accept;
//!                 ip daddr 10.1.0.0/24 counter packets 0 bytes 0 accept
//!         }
//!
//!         chain chain-for-incoming-packets {
//!                 type filter hook input priority 0; policy accept;
//!                 iif "lo" accept
//!         }
//! }
//! ```
//!
//! Try pinging any IP in the network range denoted by the outgoing rule and see the counter
//! increment:
//! ```bash
//! $ ping 10.1.0.7
//! ```
//!
//! Everything created by this example can be removed by running
//! ```bash
//! # nft delete table inet example-table
//! ```

use ipnetwork::{IpNetwork, Ipv4Network};
use rustables::{
    data_type::ip_to_vec,
    expr::{
        Bitwise, Cmp, CmpOp, Counter, HighLevelPayload, ICMPv6HeaderField, IPv4HeaderField,
        IcmpCode, Immediate, Meta, MetaType, NetworkHeaderField, TransportHeaderField, VerdictKind,
    },
    iface_index, Batch, Chain, ChainPolicy, Hook, HookClass, MsgType, ProtocolFamily, Rule, Table,
};
use std::net::Ipv4Addr;

const TABLE_NAME: &str = "example-table";
const OUT_CHAIN_NAME: &str = "chain-for-outgoing-packets";
const IN_CHAIN_NAME: &str = "chain-for-incoming-packets";

fn main() -> Result<(), Error> {
    env_logger::init();

    // Create a batch. This is used to store all the netlink messages we will later send.
    // Creating a new batch also automatically writes the initial batch begin message needed
    // to tell netlink this is a single transaction that might arrive over multiple netlink packets.
    let mut batch = Batch::new();

    // Create a netfilter table operating on both IPv4 and IPv6 (ProtoFamily::Inet)
    let table = Table::new(ProtocolFamily::Inet).with_name(TABLE_NAME);
    // Add the table to the batch with the `MsgType::Add` type, thus instructing netfilter to add
    // this table under its `ProtocolFamily::Inet` ruleset.
    batch.add(&table, MsgType::Add);

    // Create input and output chains under the table we created above.
    // Hook the chains to the input and output event hooks, with highest priority (priority zero).
    let mut out_chain = Chain::new(&table).with_name(OUT_CHAIN_NAME);
    let mut in_chain = Chain::new(&table).with_name(IN_CHAIN_NAME);

    out_chain.set_hook(Hook::new(HookClass::Out, 0));
    in_chain.set_hook(Hook::new(HookClass::In, 0));

    // Set the default policies on the chains. If no rule matches a packet processed by the
    // `out_chain` or the `in_chain` it will accept the packet.
    out_chain.set_policy(ChainPolicy::Accept);
    in_chain.set_policy(ChainPolicy::Accept);

    // Add the two chains to the batch with the `MsgType` to tell netfilter to create the chains
    // under the table.
    batch.add(&out_chain, MsgType::Add);
    batch.add(&in_chain, MsgType::Add);

    // === ADD RULE ALLOWING ALL TRAFFIC TO THE LOOPBACK DEVICE ===

    // Lookup the interface index of the loopback interface.
    let lo_iface_index = iface_index("lo")?;

    // Create a new rule object under the input chain.
    let allow_loopback_in_rule = Rule::new(&in_chain)?
        // First expression to be evaluated in this rule is load the meta information "iif"
        // (incoming interface index) into the comparison register of netfilter.
        // When an incoming network packet is processed by this rule it will first be processed by this
        // expression, which will load the interface index of the interface the packet came from into
        // a special "register" in netfilter.
        .with_expr(Meta::new(MetaType::Iif))

        // Next expression in the rule is to compare the value loaded into the register with our desired
        // interface index, and succeed only if it's equal. For any packet processed where the equality
        // does not hold the packet is said to not match this rule, and the packet moves on to be
        // processed by the next rule in the chain instead.
        .with_expr(Cmp::new(CmpOp::Eq, lo_iface_index.to_le_bytes()))

        // Add a verdict expression to the rule. Any packet getting this far in the expression
        // processing without failing any expression will be given the verdict added here.
        .with_expr(Immediate::new_verdict(VerdictKind::Accept));

    // Add the rule to the batch.
    batch.add(&allow_loopback_in_rule, rustables::MsgType::Add);

    // === ADD A RULE ALLOWING (AND COUNTING) ALL PACKETS TO THE 10.1.0.0/24 NETWORK ===

    let private_net_ip = Ipv4Addr::new(10, 1, 0, 0);
    let private_net_prefix = 24;
    let private_net = IpNetwork::V4(Ipv4Network::new(private_net_ip, private_net_prefix)?);

    let block_out_to_private_net_rule = Rule::new(&out_chain)?
        // Load the `nfproto` metadata into the netfilter register. This metadata denotes which layer3
        // protocol the packet being processed is using.
        .with_expr(Meta::new(MetaType::NfProto))

        // Check if the currently processed packet is an IPv4 packet. This must be done before payload
        // data assuming the packet uses IPv4 can be loaded in the next expression.
        .with_expr(Cmp::new(CmpOp::Eq, [libc::NFPROTO_IPV4 as u8]))

        // Load the IPv4 destination address into the netfilter register.
        .with_expr(HighLevelPayload::Network(NetworkHeaderField::IPv4(IPv4HeaderField::Daddr)).build())

        // Mask out the part of the destination address that is not part of the network bits. The result
        // of this bitwise masking is stored back into the same netfilter register.
        .with_expr(Bitwise::new(ip_to_vec(private_net.mask()), [0u8; 4])?)

        // Compare the result of the masking with the IP of the network we are interested in.
        .with_expr(Cmp::new(CmpOp::Eq, ip_to_vec(private_net.ip())))

        // Add a packet counter to the rule. Shows how many packets have been evaluated against this
        // expression. Since expressions are evaluated from first to last, putting this counter before
        // the above IP net check would make the counter increment on all packets also *not* matching
        // those expressions. Because the counter would then be evaluated before it fails a check.
        // Similarly, if the counter was added after the verdict it would always remain at zero. Since
        // when the packet hits the verdict expression any further processing of expressions stop.
        .with_expr(Counter::default())

        // Accept all the packets matching the rule so far.
        .with_expr(Immediate::new_verdict(VerdictKind::Accept));

    // Add the rule to the batch. Without this nothing would be sent over netlink and netfilter,
    // and all the work on `block_out_to_private_net_rule` so far would go to waste.
    batch.add(&block_out_to_private_net_rule, rustables::MsgType::Add);

    // === ADD A RULE ALLOWING ALL OUTGOING ICMPv6 PACKETS WITH TYPE 133 AND CODE 0 ===

    let allow_router_solicitation = Rule::new(&out_chain)?
        // Check that the packet is IPv6 and ICMPv6
        .with_expr(Meta::new(MetaType::NfProto))
        .with_expr(Cmp::new(CmpOp::Eq, [libc::NFPROTO_IPV6 as u8]))
        .with_expr(Meta::new(MetaType::L4Proto))
        .with_expr(Cmp::new(CmpOp::Eq, [libc::IPPROTO_ICMPV6 as u8]))
        .with_expr(
            HighLevelPayload::Transport(TransportHeaderField::ICMPv6(ICMPv6HeaderField::Type))
                .build(),
        )
        .with_expr(Cmp::new(CmpOp::Eq, [133u8]))
        .with_expr(
            HighLevelPayload::Transport(TransportHeaderField::ICMPv6(ICMPv6HeaderField::Code))
                .build(),
        )
        .with_expr(Cmp::new(CmpOp::Eq, [IcmpCode::NoRoute as u8]))
        .with_expr(Immediate::new_verdict(VerdictKind::Accept));

    batch.add(&allow_router_solicitation, rustables::MsgType::Add);

    // === FINALIZE THE TRANSACTION AND SEND THE DATA TO NETFILTER ===

    // Finalize the batch and send it. This means the batch end message is written into the batch, telling
    // netfilter the we reached the end of the transaction message. It's also converted to a
    // Vec<u8>, containing the raw netlink data so it can be sent over a netlink socket to netfilter.
    // Finally, the batch is sent over to the kernel.
    Ok(batch.send()?)
}

#[allow(dead_code)]
#[derive(Debug)]
struct Error(String);

impl<T: std::error::Error> From<T> for Error {
    fn from(error: T) -> Self {
        Error(error.to_string())
    }
}
