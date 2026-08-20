use std::mem::size_of;

use libc::{AF_UNSPEC, NFNL_MSG_BATCH_BEGIN, NLM_F_REQUEST};
use nix::libc::NFNL_MSG_BATCH_END;

use crate::nlmsg::{pad_netlink_object_with_variable_size, NfNetlinkDeserializable};
use crate::parser::{parse_nlmsg, NlMsg};
use crate::sys::{nfgenmsg, nlmsghdr, NFNETLINK_V0, NFNL_SUBSYS_NFTABLES, NLM_F_ACK};
use crate::{Batch, MsgType, Table};

use super::get_test_table;

const HEADER_SIZE: u32 =
    pad_netlink_object_with_variable_size(size_of::<nlmsghdr>() + size_of::<nfgenmsg>()) as u32;

const DEFAULT_BATCH_BEGIN_HDR: nlmsghdr = nlmsghdr {
    nlmsg_len: HEADER_SIZE,
    nlmsg_flags: NLM_F_REQUEST as u16 | NLM_F_ACK as u16,
    nlmsg_type: NFNL_MSG_BATCH_BEGIN as u16,
    nlmsg_seq: 0,
    nlmsg_pid: 0,
};
const DEFAULT_BATCH_MSG: NlMsg = NlMsg::NfGenMsg(
    nfgenmsg {
        nfgen_family: AF_UNSPEC as u8,
        version: NFNETLINK_V0 as u8,
        res_id: NFNL_SUBSYS_NFTABLES as u16,
    },
    &[],
);

const DEFAULT_BATCH_END_HDR: nlmsghdr = nlmsghdr {
    nlmsg_len: HEADER_SIZE,
    nlmsg_flags: NLM_F_REQUEST as u16,
    nlmsg_type: NFNL_MSG_BATCH_END as u16,
    nlmsg_seq: 1,
    nlmsg_pid: 0,
};

#[test]
fn batch_empty() {
    let batch = Batch::new();
    let buf = batch.finalize();

    let (hdr, msg) = parse_nlmsg(&buf).expect("Invalid nlmsg message");
    assert_eq!(hdr, DEFAULT_BATCH_BEGIN_HDR);
    assert_eq!(msg, DEFAULT_BATCH_MSG);

    let remaining_data_offset = pad_netlink_object_with_variable_size(hdr.nlmsg_len as usize);

    let (hdr, msg) = parse_nlmsg(&buf[remaining_data_offset..]).expect("Invalid nlmsg message");
    assert_eq!(hdr, DEFAULT_BATCH_END_HDR);
    assert_eq!(msg, DEFAULT_BATCH_MSG);
}

#[test]
fn batch_with_objects() {
    let mut original_tables = vec![];
    for i in 0..10 {
        let mut table = get_test_table();
        table.set_userdata(vec![i as u8]);
        original_tables.push(table);
    }

    let mut batch = Batch::new();
    for i in 0..10 {
        batch.add(
            &original_tables[i],
            if i % 2 == 0 {
                MsgType::Add
            } else {
                MsgType::Del
            },
        );
    }
    let buf = batch.finalize();

    let (hdr, msg) = parse_nlmsg(&buf).expect("Invalid nlmsg message");
    assert_eq!(hdr, DEFAULT_BATCH_BEGIN_HDR);
    assert_eq!(msg, DEFAULT_BATCH_MSG);
    let mut remaining_data = &buf[pad_netlink_object_with_variable_size(hdr.nlmsg_len as usize)..];

    for i in 0..10 {
        let (deserialized_table, rest) =
            Table::deserialize(&remaining_data).expect("could not deserialize a table");
        remaining_data = rest;

        assert_eq!(deserialized_table, original_tables[i]);
    }

    let (hdr, msg) = parse_nlmsg(&remaining_data).expect("Invalid nlmsg message");
    let mut end_hdr = DEFAULT_BATCH_END_HDR;
    end_hdr.nlmsg_seq = 11;
    assert_eq!(hdr, end_hdr);
    assert_eq!(msg, DEFAULT_BATCH_MSG);
}
