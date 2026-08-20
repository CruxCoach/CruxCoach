use std::{
    fmt::Debug,
    mem::{size_of, transmute},
};

use crate::{
    error::DecodeError,
    nlmsg::{
        AttributeDecoder, NetlinkType, NfNetlinkAttribute, get_operation_from_nlmsghdr_type,
        get_subsystem_from_nlmsghdr_type, pad_netlink_object,
        pad_netlink_object_with_variable_size,
    },
    sys::{
        NFNETLINK_V0, NFNL_MSG_BATCH_BEGIN, NFNL_MSG_BATCH_END, NFNL_SUBSYS_NFTABLES, NLA_F_NESTED,
        NLA_TYPE_MASK, NLM_F_DUMP_INTR, NLMSG_DONE, NLMSG_ERROR, NLMSG_MIN_TYPE, NLMSG_NOOP,
        nfgenmsg, nlattr, nlmsgerr, nlmsghdr,
    },
};

pub fn get_nlmsghdr(buf: &[u8]) -> Result<nlmsghdr, DecodeError> {
    let size_of_hdr = size_of::<nlmsghdr>();

    if buf.len() < size_of_hdr {
        return Err(DecodeError::BufTooSmall);
    }

    let nlmsghdr_ptr = buf[0..size_of_hdr].as_ptr() as *const nlmsghdr;
    let nlmsghdr = unsafe { *nlmsghdr_ptr };

    if nlmsghdr.nlmsg_len as usize > buf.len() || (nlmsghdr.nlmsg_len as usize) < size_of_hdr {
        return Err(DecodeError::NlMsgTooSmall);
    }

    if nlmsghdr.nlmsg_flags & NLM_F_DUMP_INTR as u16 != 0 {
        return Err(DecodeError::ConcurrentGenerationUpdate);
    }

    Ok(nlmsghdr)
}

#[derive(Debug, Clone, PartialEq)]
pub enum NlMsg<'a> {
    Done,
    Noop,
    Error(nlmsgerr),
    NfGenMsg(nfgenmsg, &'a [u8]),
}

pub fn parse_nlmsg<'a>(buf: &'a [u8]) -> Result<(nlmsghdr, NlMsg<'a>), DecodeError> {
    // in theory the message is composed of the following parts:
    // - nlmsghdr (contains the message size and type)
    // - struct nlmsgerr OR nfgenmsg (nftables header that describes the message family)
    // - the raw value that we want to validate (if the previous part is nfgenmsg)
    let hdr = get_nlmsghdr(buf)?;

    let size_of_hdr = pad_netlink_object::<nlmsghdr>();

    if hdr.nlmsg_type < NLMSG_MIN_TYPE as u16 {
        match hdr.nlmsg_type as u32 {
            x if x == NLMSG_NOOP => return Ok((hdr, NlMsg::Noop)),
            x if x == NLMSG_ERROR => {
                if (hdr.nlmsg_len as usize) < size_of_hdr + size_of::<nlmsgerr>() {
                    return Err(DecodeError::NlMsgTooSmall);
                }
                let mut err = unsafe {
                    *(buf[size_of_hdr..size_of_hdr + size_of::<nlmsgerr>()].as_ptr()
                        as *const nlmsgerr)
                };
                // some APIs return negative values, while other return positive values
                err.error = err.error.abs();
                return Ok((hdr, NlMsg::Error(err)));
            }
            x if x == NLMSG_DONE => return Ok((hdr, NlMsg::Done)),
            x => return Err(DecodeError::UnsupportedType(x as u16)),
        }
    }

    // batch messages are not specific to the nftables subsystem
    if hdr.nlmsg_type != NFNL_MSG_BATCH_BEGIN as u16 && hdr.nlmsg_type != NFNL_MSG_BATCH_END as u16
    {
        // verify that we are decoding nftables messages
        let subsys = get_subsystem_from_nlmsghdr_type(hdr.nlmsg_type);
        if subsys != NFNL_SUBSYS_NFTABLES as u8 {
            return Err(DecodeError::InvalidSubsystem(subsys));
        }
    }

    let size_of_nfgenmsg = pad_netlink_object::<nfgenmsg>();
    if hdr.nlmsg_len as usize > buf.len()
        || (hdr.nlmsg_len as usize) < size_of_hdr + size_of_nfgenmsg
    {
        return Err(DecodeError::NlMsgTooSmall);
    }

    let nfgenmsg_ptr = buf[size_of_hdr..size_of_hdr + size_of_nfgenmsg].as_ptr() as *const nfgenmsg;
    let nfgenmsg = unsafe { *nfgenmsg_ptr };

    if nfgenmsg.version != NFNETLINK_V0 as u8 {
        return Err(DecodeError::InvalidVersion(nfgenmsg.version));
    }

    let raw_value = &buf[size_of_hdr + size_of_nfgenmsg..hdr.nlmsg_len as usize];

    Ok((hdr, NlMsg::NfGenMsg(nfgenmsg, raw_value)))
}

/// Write the attribute, preceded by a `libc::nlattr`
// rewrite of `mnl_attr_put`
pub fn write_attribute(ty: NetlinkType, obj: &impl NfNetlinkAttribute, mut buf: &mut [u8]) {
    let header_len = pad_netlink_object::<nlattr>();
    // copy the header
    let header = nlattr {
        // nla_len contains the header size + the unpadded attribute length
        nla_len: (header_len + obj.get_size()) as u16,
        nla_type: if obj.is_nested() {
            ty | NLA_F_NESTED as u16
        } else {
            ty
        },
    };

    unsafe {
        *(buf.as_mut_ptr() as *mut nlattr) = header;
    }

    buf = &mut buf[header_len..];
    // copy the attribute data itself
    obj.write_payload(buf);
}

pub(crate) fn read_attributes<T: AttributeDecoder + Debug + Default>(
    buf: &[u8],
) -> Result<T, DecodeError> {
    debug!(
        "Calling <{} as NfNetlinkDeserialize>::deserialize()",
        std::any::type_name::<T>()
    );
    let mut remaining_size = buf.len();
    let mut pos = 0;
    let mut res = T::default();
    while remaining_size >= pad_netlink_object::<nlattr>() {
        let nlattr = unsafe { *transmute::<*const u8, *const nlattr>(buf[pos..].as_ptr()) };
        // ignore the byteorder and nested attributes
        let nla_type = nlattr.nla_type & NLA_TYPE_MASK as u16;

        pos += pad_netlink_object::<nlattr>();
        let attr_remaining_size = nlattr.nla_len as usize - pad_netlink_object::<nlattr>();
        match T::decode_attribute(&mut res, nla_type, &buf[pos..pos + attr_remaining_size]) {
            Ok(()) => {}
            Err(DecodeError::UnsupportedAttributeType(t)) => info!(
                "Ignoring unsupported attribute type {} for type {}",
                t,
                std::any::type_name::<T>()
            ),
            Err(e) => return Err(e),
        }
        pos += pad_netlink_object_with_variable_size(attr_remaining_size);

        remaining_size -= pad_netlink_object_with_variable_size(nlattr.nla_len as usize);
    }

    if remaining_size != 0 {
        Err(DecodeError::InvalidDataSize)
    } else {
        Ok(res)
    }
}

pub(crate) fn parse_object<T: AttributeDecoder + Debug + Default + Sized>(
    buf: &[u8],
    add_obj: u32,
    del_obj: u32,
) -> Result<(T, nfgenmsg, &[u8]), DecodeError> {
    debug!("parse_object() started");
    let (hdr, msg) = parse_nlmsg(buf)?;

    let op = get_operation_from_nlmsghdr_type(hdr.nlmsg_type) as u32;

    if op != add_obj && op != del_obj {
        return Err(DecodeError::UnexpectedType(hdr.nlmsg_type));
    }

    let obj_size = hdr.nlmsg_len as usize
        - pad_netlink_object_with_variable_size(size_of::<nlmsghdr>() + size_of::<nfgenmsg>());

    let remaining_data_offset = pad_netlink_object_with_variable_size(hdr.nlmsg_len as usize);
    let remaining_data = &buf[remaining_data_offset..];

    let (nfgenmsg, res) = match msg {
        NlMsg::NfGenMsg(nfgenmsg, content) => (nfgenmsg, read_attributes(&content[..obj_size])?),
        _ => return Err(DecodeError::UnexpectedType(hdr.nlmsg_type)),
    };

    Ok((res, nfgenmsg, remaining_data))
}
