use crate::{
    nlmsg::{get_operation_from_nlmsghdr_type, nft_nlmsg_maxsize, NfNetlinkDeserializable},
    sys::{NFT_MSG_DELTABLE, NFT_MSG_NEWTABLE},
    MsgType, Table,
};

use super::{
    get_test_nlmsg, get_test_nlmsg_with_msg_type, get_test_table, get_test_table_raw_expr,
    get_test_table_with_userdata_raw_expr, TABLE_USERDATA,
};

#[test]
fn new_empty_table() {
    let mut table = get_test_table();
    let mut buf = Vec::with_capacity(nft_nlmsg_maxsize() as usize);
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut table);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWTABLE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 44);

    assert_eq!(raw_expr, get_test_table_raw_expr().to_raw());
}

#[test]
fn new_empty_table_with_userdata() {
    let mut table = get_test_table();
    table.set_userdata(TABLE_USERDATA.as_bytes().to_vec());
    let mut buf = Vec::with_capacity(nft_nlmsg_maxsize() as usize);
    let (nlmsghdr, _nfgenmsg, raw_expr) = get_test_nlmsg(&mut buf, &mut table);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_NEWTABLE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 64);

    assert_eq!(raw_expr, get_test_table_with_userdata_raw_expr().to_raw());
}

#[test]
fn delete_empty_table() {
    let mut table = get_test_table();
    let mut buf = Vec::with_capacity(nft_nlmsg_maxsize() as usize);
    let (nlmsghdr, _nfgenmsg, raw_expr) =
        get_test_nlmsg_with_msg_type(&mut buf, &mut table, MsgType::Del);
    assert_eq!(
        get_operation_from_nlmsghdr_type(nlmsghdr.nlmsg_type),
        NFT_MSG_DELTABLE as u8
    );
    assert_eq!(nlmsghdr.nlmsg_len, 44);

    assert_eq!(raw_expr, get_test_table_raw_expr().to_raw());
}

#[test]
fn parse_table() {
    let mut table = get_test_table();
    table.set_userdata(TABLE_USERDATA.as_bytes().to_vec());
    let mut buf = Vec::with_capacity(nft_nlmsg_maxsize() as usize);
    let (_nlmsghdr, _nfgenmsg, _raw_expr) = get_test_nlmsg(&mut buf, &mut table);

    let (deserialized_table, remaining) =
        Table::deserialize(&buf).expect("Couldn't deserialize the object");
    assert_eq!(table, deserialized_table);
    assert_eq!(remaining.len(), 0);
}
