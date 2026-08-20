use crate::{
    nlmsg::get_operation_from_nlmsghdr_type,
    sys::{
        NFTA_RULE_CHAIN, NFTA_RULE_HANDLE, NFTA_RULE_POSITION, NFTA_RULE_TABLE, NFTA_RULE_USERDATA,
        NFT_MSG_DELRULE, NFT_MSG_NEWRULE,
    },
    MsgType,
};

use super::{
    get_test_nlmsg, get_test_nlmsg_with_msg_type, get_test_rule, NetlinkExpr, CHAIN_NAME,
    RULE_USERDATA, TABLE_NAME,
};

#[test]
fn new_empty_rule() {
    let mut rule = get_test_rule();

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWRULE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 52);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
        ])
        .to_raw()
    );
}

#[test]
fn new_empty_rule_with_userdata() {
    let mut rule = get_test_rule().with_userdata(RULE_USERDATA);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWRULE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 68);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_USERDATA, RULE_USERDATA.as_bytes().to_vec())
        ])
        .to_raw()
    );
}

#[test]
fn new_empty_rule_with_position_and_handle() {
    let handle: u64 = 1337;
    let position: u64 = 42;
    let mut rule = get_test_rule().with_handle(handle).with_position(position);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut rule);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWRULE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 76);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_HANDLE, handle.to_be_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_POSITION, position.to_be_bytes().to_vec()),
        ])
        .to_raw()
    );
}

#[test]
fn delete_empty_rule() {
    let mut rule = get_test_rule();

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) =
        get_test_nlmsg_with_msg_type(&mut buf, &mut rule, MsgType::Del);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_DELRULE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 52);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
        ])
        .to_raw()
    );
}

#[test]
fn delete_empty_rule_with_handle() {
    let handle: u64 = 42;
    let mut rule = get_test_rule().with_handle(handle);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) =
        get_test_nlmsg_with_msg_type(&mut buf, &mut rule, MsgType::Del);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_DELRULE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 64);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_RULE_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_CHAIN, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_RULE_HANDLE, handle.to_be_bytes().to_vec()),
        ])
        .to_raw()
    );
}
