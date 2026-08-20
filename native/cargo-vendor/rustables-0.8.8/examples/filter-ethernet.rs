///! Adds a table, chain and a rule that blocks all traffic to a given MAC address
///!
///! Run the following to print out current active tables, chains and rules in netfilter. Must be
///! executed as root:
///! ```bash
///! # nft list ruleset
///! ```
///! After running this example, the output should be the following:
///! ```ignore
///! table inet example-filter-ethernet {
///!         chain chain-for-outgoing-packets {
///!                 type filter hook output priority 3; policy accept;
///!                 ether daddr 01:02:03:04:05:06 drop
///!                 counter packets 0 bytes 0 meta random > 2147483647 counter packets 0 bytes 0
///!         }
///! }
///! ```
///!
///!
///! Everything created by this example can be removed by running
///! ```bash
///! # nft delete table inet example-filter-ethernet
///! ```
use rustables::{
    expr::{
        Cmp, CmpOp, Counter, ExpressionList, HighLevelPayload, Immediate, LLHeaderField, Meta,
        MetaType, VerdictKind,
    },
    Batch, Chain, ChainPolicy, Hook, HookClass, ProtocolFamily, Rule, Table,
};

const TABLE_NAME: &str = "example-filter-ethernet";
const OUT_CHAIN_NAME: &str = "chain-for-outgoing-packets";

const BLOCK_THIS_MAC: &[u8] = &[1, 2, 3, 4, 5, 6];

fn main() {
    // For verbose explanations of what all these lines up until the rule creation does, see the
    // `add-rules` example.
    let mut batch = Batch::new();
    let table = Table::new(ProtocolFamily::Inet).with_name(TABLE_NAME);
    batch.add(&table, rustables::MsgType::Add);

    let mut out_chain = Chain::new(&table).with_name(OUT_CHAIN_NAME);
    out_chain.set_hook(Hook::new(HookClass::Out, 3));
    out_chain.set_policy(ChainPolicy::Accept);
    batch.add(&out_chain, rustables::MsgType::Add);

    // === ADD RULE DROPPING ALL TRAFFIC TO THE MAC ADDRESS IN `BLOCK_THIS_MAC` ===

    let mut block_ethernet_rule = Rule::new(&out_chain).unwrap();

    block_ethernet_rule.set_expressions(
        ExpressionList::default()
        // Check that the interface type is an ethernet interface. Must be done before we can check
        // payload values in the ethernet header.
        .with_value(Meta::new(MetaType::IifType))
        .with_value(Cmp::new(CmpOp::Eq, (libc::ARPHRD_ETHER as u16).to_le_bytes()))

        // Compare the ethernet destination address against the MAC address we want to drop
        .with_value(HighLevelPayload::LinkLayer(LLHeaderField::Daddr).build())
        .with_value(Cmp::new(CmpOp::Eq, BLOCK_THIS_MAC))

        // Drop the matching packets.
        .with_value(Immediate::new_verdict(VerdictKind::Drop)),
    );

    batch.add(&block_ethernet_rule, rustables::MsgType::Add);

    // === FOR FUN, ADD A PACKET THAT MATCHES 50% OF ALL PACKETS ===

    // This packet has a counter before and after the check that has 50% chance of matching.
    // So after a number of packets has passed through this rule, the first counter should have a
    // value approximately double that of the second counter. This rule has no verdict, so it never
    // does anything with the matching packets.
    let mut random_rule = Rule::new(&out_chain).unwrap();

    random_rule.set_expressions(
        ExpressionList::default()
        // This counter expression will be evaluated (and increment the counter) for all packets coming
        // through.
        .with_value(Counter::default())

        // Load a pseudo-random 32 bit unsigned integer into the netfilter register.
        .with_value(Meta::new(MetaType::PRandom))
        // Check if the random integer is larger than `u32::MAX/2`, thus having 50% chance of success.
        .with_value(Cmp::new(CmpOp::Gt, (::std::u32::MAX / 2).to_be_bytes()))

        // Add a second counter. This will only be incremented for the packets passing the random check.
        .with_value(Counter::default()),
    );

    batch.add(&random_rule, rustables::MsgType::Add);

    // === FINALIZE THE TRANSACTION AND SEND THE DATA TO NETFILTER ===

    batch.send().unwrap();
}
