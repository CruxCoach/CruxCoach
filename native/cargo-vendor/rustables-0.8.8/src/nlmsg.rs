use std::{fmt::Debug, mem::size_of};

use crate::{
    MsgType, ProtocolFamily,
    error::DecodeError,
    sys::{
        NFNETLINK_V0, NFNL_MSG_BATCH_BEGIN, NFNL_MSG_BATCH_END, NFNL_SUBSYS_NFTABLES, NLM_F_ACK,
        NLM_F_CREATE, NLMSG_ALIGNTO, nfgenmsg, nlmsghdr,
    },
};
///
/// The largest nf_tables netlink message is the set element message, which contains the
/// NFTA_SET_ELEM_LIST_ELEMENTS attribute. This attribute is a nest that describes the set
/// elements. Given that the netlink attribute length (nla_len) is 16 bits, the largest message is
/// a bit larger than 64 KBytes.
pub fn nft_nlmsg_maxsize() -> u32 {
    u32::from(u16::MAX) + unsafe { libc::sysconf(libc::_SC_PAGESIZE) } as u32
}

#[inline]
pub const fn pad_netlink_object_with_variable_size(size: usize) -> usize {
    // align on a 4 bytes boundary
    (size + (NLMSG_ALIGNTO as usize - 1)) & !(NLMSG_ALIGNTO as usize - 1)
}

#[inline]
pub const fn pad_netlink_object<T>() -> usize {
    let size = size_of::<T>();
    pad_netlink_object_with_variable_size(size)
}

pub fn get_subsystem_from_nlmsghdr_type(x: u16) -> u8 {
    ((x & 0xff00) >> 8) as u8
}

pub fn get_operation_from_nlmsghdr_type(x: u16) -> u8 {
    (x & 0x00ff) as u8
}

pub struct NfNetlinkWriter<'a> {
    buf: &'a mut Vec<u8>,
    // hold the position of the nlmsghdr and nfgenmsg structures for the object currently being
    // written
    headers: Option<(usize, usize)>,
}

impl<'a> NfNetlinkWriter<'a> {
    pub fn new(buf: &'a mut Vec<u8>) -> NfNetlinkWriter<'a> {
        NfNetlinkWriter { buf, headers: None }
    }

    pub fn add_data_zeroed(&mut self, size: usize) -> &mut [u8] {
        let padded_size = pad_netlink_object_with_variable_size(size);
        let start = self.buf.len();
        self.buf.resize(start + padded_size, 0);

        // if we are *inside* an object begin written, extend the netlink object size
        if let Some((msghdr_idx, _nfgenmsg_idx)) = self.headers {
            let hdr: &mut nlmsghdr =
                unsafe { &mut *(self.buf[msghdr_idx..].as_mut_ptr() as *mut nlmsghdr) };
            hdr.nlmsg_len += padded_size as u32;
        }

        &mut self.buf[start..start + size]
    }

    // rewrite of `__nftnl_nlmsg_build_hdr`
    pub fn write_header(
        &mut self,
        msg_type: u16,
        family: ProtocolFamily,
        flags: u16,
        seq: u32,
        ressource_id: Option<u16>,
    ) {
        if self.headers.is_some() {
            error!("Calling write_header while still holding headers open!?");
        }

        let nlmsghdr_len = pad_netlink_object::<nlmsghdr>();
        let nfgenmsg_len = pad_netlink_object::<nfgenmsg>();

        // serialize the nlmsghdr
        let nlmsghdr_buf = self.add_data_zeroed(nlmsghdr_len);
        let hdr: &mut nlmsghdr = unsafe { &mut *(nlmsghdr_buf.as_mut_ptr() as *mut nlmsghdr) };
        hdr.nlmsg_len = (nlmsghdr_len + nfgenmsg_len) as u32;
        hdr.nlmsg_type = msg_type;
        // batch messages are not specific to the nftables subsystem
        if msg_type != NFNL_MSG_BATCH_BEGIN as u16 && msg_type != NFNL_MSG_BATCH_END as u16 {
            hdr.nlmsg_type |= (NFNL_SUBSYS_NFTABLES as u16) << 8;
        }
        hdr.nlmsg_flags = libc::NLM_F_REQUEST as u16 | flags;
        hdr.nlmsg_seq = seq;

        // serialize the nfgenmsg
        let nfgenmsg_buf = self.add_data_zeroed(nfgenmsg_len);
        let nfgenmsg: &mut nfgenmsg = unsafe { &mut *(nfgenmsg_buf.as_mut_ptr() as *mut nfgenmsg) };
        nfgenmsg.nfgen_family = family as u8;
        nfgenmsg.version = NFNETLINK_V0 as u8;
        nfgenmsg.res_id = ressource_id.unwrap_or(0);

        self.headers = Some((
            self.buf.len() - (nlmsghdr_len + nfgenmsg_len),
            self.buf.len() - nfgenmsg_len,
        ));
    }

    pub fn finalize_writing_object(&mut self) {
        self.headers = None;
    }
}

pub type NetlinkType = u16;

pub trait AttributeDecoder {
    fn decode_attribute(&mut self, attr_type: NetlinkType, buf: &[u8]) -> Result<(), DecodeError>;
}

pub trait NfNetlinkDeserializable: Sized {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError>;
}

pub trait NfNetlinkObject:
    Sized + AttributeDecoder + NfNetlinkDeserializable + NfNetlinkAttribute
{
    const MSG_TYPE_ADD: u32;
    const MSG_TYPE_DEL: u32;

    fn add_or_remove<'a>(&self, writer: &mut NfNetlinkWriter<'a>, msg_type: MsgType, seq: u32) {
        let raw_msg_type = match msg_type {
            MsgType::Add => Self::MSG_TYPE_ADD,
            MsgType::Del => Self::MSG_TYPE_DEL,
        } as u16;
        writer.write_header(
            raw_msg_type,
            self.get_family(),
            (if let MsgType::Add = msg_type {
                self.get_add_flags()
            } else {
                self.get_del_flags()
            } | NLM_F_ACK) as u16,
            seq,
            None,
        );
        let buf = writer.add_data_zeroed(self.get_size());
        self.write_payload(buf);
        writer.finalize_writing_object();
    }

    fn get_family(&self) -> ProtocolFamily;

    fn set_family(&mut self, _family: ProtocolFamily) {
        // the default impl do nothing, because some types are family-agnostic
    }

    fn with_family(mut self, family: ProtocolFamily) -> Self {
        self.set_family(family);
        self
    }

    fn get_add_flags(&self) -> u32 {
        NLM_F_CREATE
    }

    fn get_del_flags(&self) -> u32 {
        0
    }
}

pub trait NfNetlinkAttribute: Debug + Sized {
    // is it a nested argument that must be marked with a NLA_F_NESTED flag?
    fn is_nested(&self) -> bool {
        false
    }

    fn get_size(&self) -> usize {
        size_of::<Self>()
    }

    // example body: std::ptr::copy_nonoverlapping(self as *const Self as *const u8, addr.as_mut_ptr(), self.get_size());
    fn write_payload(&self, addr: &mut [u8]);
}
