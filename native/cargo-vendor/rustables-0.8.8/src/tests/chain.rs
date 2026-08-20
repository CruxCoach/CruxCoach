use crate::{
    nlmsg::get_operation_from_nlmsghdr_type,
    sys::{
        NFTA_CHAIN_HOOK, NFTA_CHAIN_NAME, NFTA_CHAIN_TABLE, NFTA_CHAIN_TYPE, NFTA_CHAIN_USERDATA,
        NFTA_HOOK_HOOKNUM, NFTA_HOOK_PRIORITY, NFT_MSG_DELCHAIN, NFT_MSG_NEWCHAIN,
    },
    ChainType, Hook, HookClass, MsgType,
};

use super::{
    get_test_chain, get_test_nlmsg, get_test_nlmsg_with_msg_type, NetlinkExpr, CHAIN_NAME,
    CHAIN_USERDATA, TABLE_NAME,
};

#[test]
fn new_empty_chain() {
    let mut chain = get_test_chain();

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut chain);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWCHAIN as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 52);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_CHAIN_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_CHAIN_NAME, CHAIN_NAME.as_bytes().to_vec()),
        ])
        .to_raw()
    );
}

#[test]
fn new_empty_chain_with_hook_and_type() {
    let mut chain = get_test_chain()
        .with_hook(Hook::new(HookClass::In, 0))
        .with_type(ChainType::Filter);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut chain);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWCHAIN as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 84);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_CHAIN_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_CHAIN_NAME, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_CHAIN_TYPE, "filter".as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_CHAIN_HOOK,
                vec![
                    NetlinkExpr::List(vec![NetlinkExpr::Final(
                        NFTA_HOOK_HOOKNUM,
                        vec![0, 0, 0, 1]
                    )]),
                    NetlinkExpr::List(vec![NetlinkExpr::Final(
                        NFTA_HOOK_PRIORITY,
                        vec![0, 0, 0, 0]
                    )])
                ]
            ),
        ])
        .to_raw()
    );
}

#[test]
fn new_empty_chain_with_userdata() {
    let mut chain = get_test_chain();
    chain.set_userdata(CHAIN_USERDATA);

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut chain);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWCHAIN as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 72);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_CHAIN_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_CHAIN_NAME, CHAIN_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_CHAIN_USERDATA, CHAIN_USERDATA.as_bytes().to_vec())
        ])
        .to_raw()
    );
}

#[test]
fn delete_empty_chain() {
    let mut chain = get_test_chain();

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) =
        get_test_nlmsg_with_msg_type(&mut buf, &mut chain, MsgType::Del);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_DELCHAIN as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 52);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_CHAIN_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_CHAIN_NAME, CHAIN_NAME.as_bytes().to_vec()),
        ])
        .to_raw()
    );
}
