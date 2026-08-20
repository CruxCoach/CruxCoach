use std::net::Ipv4Addr;

use libc::NF_DROP;

use crate::{
    expr::{
        Bitwise, Cmp, CmpOp, Conntrack, ConntrackKey, Counter, ExpressionList, HeaderField,
        HighLevelPayload, IcmpCode, Immediate, Log, Lookup, Masquerade, Meta, MetaType, Nat,
        NatType, Register, Reject, RejectType, TCPHeaderField, TransportHeaderField, VerdictKind,
    },
    set::SetBuilder,
    sys::{
        NFTA_BITWISE_DREG, NFTA_BITWISE_LEN, NFTA_BITWISE_MASK, NFTA_BITWISE_SREG,
        NFTA_BITWISE_XOR, NFTA_CMP_DATA, NFTA_CMP_OP, NFTA_CMP_SREG, NFTA_COUNTER_BYTES,
        NFTA_COUNTER_PACKETS, NFTA_CT_DREG, NFTA_CT_KEY, NFTA_DATA_VALUE, NFTA_DATA_VERDICT,
        NFTA_EXPR_DATA, NFTA_EXPR_NAME, NFTA_IMMEDIATE_DATA, NFTA_IMMEDIATE_DREG, NFTA_LIST_ELEM,
        NFTA_LOG_GROUP, NFTA_LOG_PREFIX, NFTA_LOOKUP_SET, NFTA_LOOKUP_SREG, NFTA_META_DREG,
        NFTA_META_KEY, NFTA_NAT_FAMILY, NFTA_NAT_REG_ADDR_MIN, NFTA_NAT_TYPE, NFTA_PAYLOAD_BASE,
        NFTA_PAYLOAD_DREG, NFTA_PAYLOAD_LEN, NFTA_PAYLOAD_OFFSET, NFTA_REJECT_ICMP_CODE,
        NFTA_REJECT_TYPE, NFTA_RULE_CHAIN, NFTA_RULE_EXPRESSIONS, NFTA_RULE_TABLE,
        NFTA_VERDICT_CODE, NFT_CMP_EQ, NFT_CT_STATE, NFT_META_PROTOCOL, NFT_NAT_SNAT,
        NFT_PAYLOAD_TRANSPORT_HEADER, NFT_REG_1, NFT_REG_VERDICT, NFT_REJECT_ICMPX_UNREACH,
    },
    tests::{get_test_table, SET_NAME},
    ProtocolFamily,
};

use super::{get_test_nlmsg, get_test_rule, NetlinkExpr, CHAIN_NAME, TABLE_NAME};

#[test]
fn bitwise_expr_is_valid() {
    let netmask = Ipv4Addr::new(255, 255, 255, 0);
    let bitwise = Bitwise::new(netmask.octets(), [0, 0, 0, 0]).unwrap();
    let mut rule = get_test_rule().with_expressions(ExpressionList::default().with_value(bitwise));

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 124);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"bitwise".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_BITWISE_SREG,
                                    NFT_REG_1.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_BITWISE_DREG,
                                    NFT_REG_1.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(NFTA_BITWISE_LEN, 4u32.to_be_bytes().to_vec()),
                                NetlinkExpr::Nested(
                                    NFTA_BITWISE_MASK,
                                    vec![NetlinkExpr::Final(
                                        NFTA_DATA_VALUE,
                                        vec![255, 255, 255, 0]
                                    )]
                                ),
                                NetlinkExpr::Nested(
                                    NFTA_BITWISE_XOR,
                                    vec![NetlinkExpr::Final(
                                        NFTA_DATA_VALUE,
                                        0u32.to_be_bytes().to_vec()
                                    )]
                                )
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn cmp_expr_is_valid() {
    let val = [1u8, 2, 3, 4];
    let cmp = Cmp::new(CmpOp::Eq, val.clone());
    let mut rule = get_test_rule().with_expressions(vec![cmp]);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 100);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"cmp".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(NFTA_CMP_SREG, NFT_REG_1.to_be_bytes().to_vec()),
                                NetlinkExpr::Final(NFTA_CMP_OP, NFT_CMP_EQ.to_be_bytes().to_vec()),
                                NetlinkExpr::Nested(
                                    NFTA_CMP_DATA,
                                    vec![NetlinkExpr::Final(NFTA_DATA_VALUE, val.to_vec())]
                                )
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn counter_expr_is_valid() {
    let nb_bytes = 123456u64;
    let nb_packets = 987u64;
    let counter = Counter::default()
        .with_nb_bytes(nb_bytes)
        .with_nb_packets(nb_packets);

    let mut rule = get_test_rule().with_expressions(vec![counter]);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 100);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"counter".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_COUNTER_BYTES,
                                    nb_bytes.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_COUNTER_PACKETS,
                                    nb_packets.to_be_bytes().to_vec()
                                )
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn ct_expr_is_valid() {
    let ct = Conntrack::default().with_retrieve_value(ConntrackKey::State);
    let mut rule = get_test_rule().with_expressions(vec![ct]);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 88);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"ct".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_CT_KEY,
                                    NFT_CT_STATE.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(NFTA_CT_DREG, NFT_REG_1.to_be_bytes().to_vec())
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    )
}

#[test]
fn immediate_expr_is_valid() {
    let immediate = Immediate::new_data(vec![42u8], Register::Reg1);
    let mut rule =
        get_test_rule().with_expressions(ExpressionList::default().with_value(immediate));

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 100);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"immediate".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_IMMEDIATE_DREG,
                                    NFT_REG_1.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Nested(
                                    NFTA_IMMEDIATE_DATA,
                                    vec![NetlinkExpr::Final(1u16, 42u8.to_be_bytes().to_vec())]
                                )
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn log_expr_is_valid() {
    let log = Log::new(Some(1337), Some("mockprefix")).expect("Could not build a log expression");
    let mut rule = get_test_rule().with_expressions(ExpressionList::default().with_value(log));

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 96);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"log".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(NFTA_LOG_GROUP, 1337u16.to_be_bytes().to_vec()),
                                NetlinkExpr::Final(NFTA_LOG_PREFIX, b"mockprefix".to_vec()),
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn lookup_expr_is_valid() {
    let table = get_test_table();
    let mut set_builder = SetBuilder::new(SET_NAME, &table).unwrap();
    let address: Ipv4Addr = [8, 8, 8, 8].into();
    set_builder.add(&address);
    let (set, _set_elements) = set_builder.finish();
    let lookup = Lookup::new(&set).unwrap();

    let mut rule = get_test_rule().with_expressions(ExpressionList::default().with_value(lookup));

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 96);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"lookup".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(NFTA_LOOKUP_SET, b"mockset".to_vec()),
                                NetlinkExpr::Final(
                                    NFTA_LOOKUP_SREG,
                                    NFT_REG_1.to_be_bytes().to_vec()
                                ),
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn masquerade_expr_is_valid() {
    let masquerade = Masquerade::default();
    let mut rule = get_test_rule().with_expressions(vec![masquerade]);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 72);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"masq".to_vec()),
                        NetlinkExpr::Nested(NFTA_EXPR_DATA, vec![]),
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn meta_expr_is_valid() {
    let meta = Meta::default()
        .with_key(MetaType::Protocol)
        .with_dreg(Register::Reg1);
    let mut rule = get_test_rule().with_expressions(vec![meta]);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 88);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"meta".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_META_KEY,
                                    NFT_META_PROTOCOL.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_META_DREG,
                                    NFT_REG_1.to_be_bytes().to_vec()
                                )
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn nat_expr_is_valid() {
    let nat = Nat::default()
        .with_nat_type(NatType::SNat)
        .with_family(ProtocolFamily::Ipv4)
        .with_ip_register(Register::Reg1);
    let mut rule = get_test_rule().with_expressions(vec![nat]);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 96);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"nat".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_NAT_TYPE,
                                    NFT_NAT_SNAT.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_NAT_FAMILY,
                                    (ProtocolFamily::Ipv4 as u32).to_be_bytes().to_vec(),
                                ),
                                NetlinkExpr::Final(
                                    NFTA_NAT_REG_ADDR_MIN,
                                    NFT_REG_1.to_be_bytes().to_vec()
                                )
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn payload_expr_is_valid() {
    let tcp_header_field = TCPHeaderField::Sport;
    let transport_header_field = TransportHeaderField::Tcp(tcp_header_field);
    let payload = HighLevelPayload::Transport(transport_header_field);
    let mut rule = get_test_rule().with_expressions(vec![payload.build()]);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 108);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"payload".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_PAYLOAD_DREG,
                                    NFT_REG_1.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_PAYLOAD_BASE,
                                    NFT_PAYLOAD_TRANSPORT_HEADER.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_PAYLOAD_OFFSET,
                                    tcp_header_field.offset().to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_PAYLOAD_LEN,
                                    tcp_header_field.len().to_be_bytes().to_vec()
                                ),
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn reject_expr_is_valid() {
    let code = IcmpCode::NoRoute;
    let reject = Reject::default()
        .with_type(RejectType::IcmpxUnreach)
        .with_icmp_code(code);
    let mut rule = get_test_rule().with_expressions(vec![reject]);
    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 92);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"reject".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_REJECT_TYPE,
                                    NFT_REJECT_ICMPX_UNREACH.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Final(
                                    NFTA_REJECT_ICMP_CODE,
                                    (code as u8).to_be_bytes().to_vec()
                                ),
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}

#[test]
fn verdict_expr_is_valid() {
    let verdict = Immediate::new_verdict(VerdictKind::Drop);
    let mut rule = get_test_rule().with_expressions(ExpressionList::default().with_value(verdict));

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(nlmsghdr.nlmsg_len, 104);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_RULE_EXPRESSIONS,
                vec![NetlinkExpr::Nested(
                    NFTA_LIST_ELEM,
                    vec![
                        NetlinkExpr::Final(NFTA_EXPR_NAME, b"immediate".to_vec()),
                        NetlinkExpr::Nested(
                            NFTA_EXPR_DATA,
                            vec![
                                NetlinkExpr::Final(
                                    NFTA_IMMEDIATE_DREG,
                                    NFT_REG_VERDICT.to_be_bytes().to_vec()
                                ),
                                NetlinkExpr::Nested(
                                    NFTA_IMMEDIATE_DATA,
                                    vec![NetlinkExpr::Nested(
                                        NFTA_DATA_VERDICT,
                                        vec![NetlinkExpr::Final(
                                            NFTA_VERDICT_CODE,
                                            NF_DROP.to_be_bytes().to_vec()
                                        ),]
                                    )],
                                ),
                            ]
                        )
                    ]
                )]
            )
        ])
        .to_raw()
    );
}
