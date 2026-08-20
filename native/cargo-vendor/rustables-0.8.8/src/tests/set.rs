use std::net::{Ipv4Addr, Ipv6Addr};

use crate::{
    data_type::DataType,
    error::DecodeError,
    nlmsg::{get_operation_from_nlmsghdr_type, NfNetlinkDeserializable},
    set::SetBuilder,
    sys::{
        NFTA_DATA_VALUE, NFTA_LIST_ELEM, NFTA_SET_ELEM_KEY, NFTA_SET_ELEM_LIST_ELEMENTS,
        NFTA_SET_ELEM_LIST_SET, NFTA_SET_ELEM_LIST_TABLE, NFTA_SET_KEY_LEN, NFTA_SET_KEY_TYPE,
        NFTA_SET_NAME, NFTA_SET_TABLE, NFTA_SET_USERDATA, NFT_MSG_DELSET, NFT_MSG_NEWSET,
        NFT_MSG_NEWSETELEM,
    },
    MsgType, Set,
};

use super::{
    get_test_nlmsg, get_test_nlmsg_with_msg_type, get_test_set, get_test_table, NetlinkExpr,
    SET_NAME, SET_USERDATA, TABLE_NAME,
};

#[test]
fn new_empty_set() {
    let mut set = get_test_set::<Ipv4Addr>();

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut set);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWSET as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 80);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_SET_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_NAME, SET_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_KEY_TYPE, Ipv4Addr::TYPE.to_be_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_KEY_LEN, Ipv4Addr::LEN.to_be_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_USERDATA, SET_USERDATA.as_bytes().to_vec()),
        ])
        .to_raw()
    );
}

// non-regression test for https://gitlab.com/rustwall/rustables/-/issues/8
#[test]
fn set_with_empty_userdata() -> Result<(), DecodeError> {
    let mut set = get_test_set::<Ipv4Addr>().with_userdata(&[]);

    let mut buf = Vec::new();
    get_test_nlmsg(&mut buf, &mut set);

    let (orig_set, _) = Set::deserialize(&buf)?;

    assert_eq!(orig_set.userdata, Some([].to_vec()));

    Ok(())
}

#[test]
fn delete_empty_set() {
    let mut set = get_test_set::<Ipv6Addr>();

    let mut buf = Vec::new();
    let (nlmsghdr, _nfgenmsg, raw_expr) =
        get_test_nlmsg_with_msg_type(&mut buf, &mut set, MsgType::Del);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_DELSET as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 80);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_SET_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_NAME, SET_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_KEY_TYPE, Ipv6Addr::TYPE.to_be_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_KEY_LEN, Ipv6Addr::LEN.to_be_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_USERDATA, SET_USERDATA.as_bytes().to_vec()),
        ])
        .to_raw()
    );
}

#[test]
fn new_set_with_data() {
    let ip1 = Ipv4Addr::new(127, 0, 0, 1);
    let ip2 = Ipv4Addr::new(1, 1, 1, 1);
    let mut set_builder = SetBuilder::<Ipv4Addr>::new(SET_NAME.to_string(), &get_test_table())
        .expect("Couldn't create a set");

    set_builder.add(&ip1);
    set_builder.add(&ip2);
    let (_set, mut elem_list) = set_builder.finish();

    let mut buf = Vec::new();

    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut elem_list);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWSETELEM as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 84);

    assert_eq!(
        raw_expr,
        NetlinkExpr::List(vec![
            NetlinkExpr::Final(NFTA_SET_ELEM_LIST_TABLE, TABLE_NAME.as_bytes().to_vec()),
            NetlinkExpr::Final(NFTA_SET_ELEM_LIST_SET, SET_NAME.as_bytes().to_vec()),
            NetlinkExpr::Nested(
                NFTA_SET_ELEM_LIST_ELEMENTS,
                vec![
                    NetlinkExpr::Nested(
                        NFTA_LIST_ELEM,
                        vec![NetlinkExpr::Nested(
                            NFTA_DATA_VALUE,
                            vec![NetlinkExpr::Final(NFTA_SET_ELEM_KEY, ip1.data().to_vec())]
                        )]
                    ),
                    NetlinkExpr::Nested(
                        NFTA_LIST_ELEM,
                        vec![NetlinkExpr::Nested(
                            NFTA_DATA_VALUE,
                            vec![NetlinkExpr::Final(NFTA_SET_ELEM_KEY, ip2.data().to_vec())]
                        )]
                    ),
                ]
            ),
        ])
        .to_raw()
    );
}
