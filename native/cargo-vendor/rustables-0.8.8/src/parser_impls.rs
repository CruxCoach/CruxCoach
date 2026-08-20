use std::{
    fmt::Debug,
    mem::{size_of, transmute},
};

use rustables_macros::nfnetlink_struct;

use crate::{
    ProtocolFamily,
    error::DecodeError,
    expr::Verdict,
    nlmsg::{
        AttributeDecoder, NfNetlinkAttribute, NfNetlinkDeserializable, NfNetlinkObject,
        pad_netlink_object, pad_netlink_object_with_variable_size,
    },
    parser::{parse_object, write_attribute},
    sys::{NFTA_DATA_VALUE, NFTA_DATA_VERDICT, NFTA_LIST_ELEM, NLA_TYPE_MASK, nlattr},
};

impl NfNetlinkAttribute for u8 {
    fn write_payload(&self, addr: &mut [u8]) {
        addr[0] = *self;
    }
}

impl NfNetlinkDeserializable for u8 {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        Ok((buf[0], &buf[1..]))
    }
}

impl NfNetlinkAttribute for u16 {
    fn write_payload(&self, addr: &mut [u8]) {
        addr[0..size_of::<Self>()].copy_from_slice(&self.to_be_bytes());
    }
}

impl NfNetlinkDeserializable for u16 {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        Ok((u16::from_be_bytes([buf[0], buf[1]]), &buf[2..]))
    }
}

impl NfNetlinkAttribute for i32 {
    fn write_payload(&self, addr: &mut [u8]) {
        addr[0..size_of::<Self>()].copy_from_slice(&self.to_be_bytes());
    }
}

impl NfNetlinkDeserializable for i32 {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        Ok((
            i32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]),
            &buf[4..],
        ))
    }
}

impl NfNetlinkAttribute for u32 {
    fn write_payload(&self, addr: &mut [u8]) {
        addr[0..size_of::<Self>()].copy_from_slice(&self.to_be_bytes());
    }
}

impl NfNetlinkDeserializable for u32 {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        Ok((
            u32::from_be_bytes([buf[0], buf[1], buf[2], buf[3]]),
            &buf[4..],
        ))
    }
}

impl NfNetlinkAttribute for u64 {
    fn write_payload(&self, addr: &mut [u8]) {
        addr[0..size_of::<Self>()].copy_from_slice(&self.to_be_bytes());
    }
}

impl NfNetlinkDeserializable for u64 {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        Ok((
            u64::from_be_bytes([
                buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], buf[7],
            ]),
            &buf[8..],
        ))
    }
}

impl NfNetlinkAttribute for String {
    fn get_size(&self) -> usize {
        self.len()
    }

    fn write_payload(&self, addr: &mut [u8]) {
        addr[0..self.len()].copy_from_slice(self.as_bytes());
    }
}

impl NfNetlinkDeserializable for String {
    fn deserialize(mut buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        // ignore the NULL byte terminator, if any
        if !buf.is_empty() && buf[buf.len() - 1] == 0 {
            buf = &buf[..buf.len() - 1];
        }
        Ok((String::from_utf8(buf.to_vec())?, &[]))
    }
}

impl NfNetlinkAttribute for Vec<u8> {
    fn get_size(&self) -> usize {
        self.len()
    }

    fn write_payload(&self, addr: &mut [u8]) {
        addr[0..self.len()].copy_from_slice(self.as_slice());
    }
}

impl NfNetlinkDeserializable for Vec<u8> {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        Ok((buf.to_vec(), &[]))
    }
}
#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct(nested = true)]
pub struct NfNetlinkData {
    #[field(NFTA_DATA_VALUE)]
    value: Vec<u8>,
    #[field(NFTA_DATA_VERDICT)]
    verdict: Verdict,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct NfNetlinkList<T>
where
    T: NfNetlinkDeserializable + NfNetlinkAttribute + Debug + Clone + Eq + Default,
{
    objs: Vec<T>,
}

impl<T> NfNetlinkList<T>
where
    T: NfNetlinkDeserializable + NfNetlinkAttribute + Clone + Eq + Default,
{
    pub fn add_value(&mut self, e: impl Into<T>) {
        self.objs.push(e.into());
    }

    pub fn with_value(mut self, e: impl Into<T>) -> Self {
        self.add_value(e);
        self
    }

    pub fn iter(&self) -> impl Iterator<Item = &T> {
        self.objs.iter()
    }

    pub fn iter_mut(&mut self) -> impl Iterator<Item = &mut T> {
        self.objs.iter_mut()
    }
}

impl<T> NfNetlinkAttribute for NfNetlinkList<T>
where
    T: NfNetlinkDeserializable + NfNetlinkAttribute + Clone + Eq + Default,
{
    fn is_nested(&self) -> bool {
        true
    }

    fn get_size(&self) -> usize {
        // one nlattr LIST_ELEM per object
        self.objs.iter().fold(0, |acc, item| {
            acc + item.get_size() + pad_netlink_object::<nlattr>()
        })
    }

    fn write_payload(&self, mut addr: &mut [u8]) {
        for item in &self.objs {
            write_attribute(NFTA_LIST_ELEM, item, addr);
            let offset = pad_netlink_object::<nlattr>() + item.get_size();
            addr = &mut addr[offset..];
        }
    }
}

impl<T> NfNetlinkDeserializable for NfNetlinkList<T>
where
    T: NfNetlinkDeserializable + NfNetlinkAttribute + Clone + Eq + Default,
{
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        let mut objs = Vec::new();

        let mut pos = 0;
        while buf.len() - pos > pad_netlink_object::<nlattr>() {
            let nlattr = unsafe { *transmute::<*const u8, *const nlattr>(buf[pos..].as_ptr()) };
            // ignore the byteorder and nested attributes
            let nla_type = nlattr.nla_type & NLA_TYPE_MASK as u16;

            if nla_type != NFTA_LIST_ELEM {
                return Err(DecodeError::UnsupportedAttributeType(nla_type));
            }

            let (obj, remaining) = T::deserialize(
                &buf[pos + pad_netlink_object::<nlattr>()..pos + nlattr.nla_len as usize],
            )?;
            if !remaining.is_empty() {
                return Err(DecodeError::InvalidDataSize);
            }
            objs.push(obj);

            pos += pad_netlink_object_with_variable_size(nlattr.nla_len as usize);
        }

        if pos != buf.len() {
            Err(DecodeError::InvalidDataSize)
        } else {
            Ok((Self { objs }, &[]))
        }
    }
}

impl<O, T> From<Vec<O>> for NfNetlinkList<T>
where
    T: From<O>,
    T: NfNetlinkDeserializable + NfNetlinkAttribute + Clone + Eq + Default,
{
    fn from(v: Vec<O>) -> Self {
        NfNetlinkList {
            objs: v.into_iter().map(T::from).collect(),
        }
    }
}

impl<T> NfNetlinkDeserializable for T
where
    T: NfNetlinkObject + AttributeDecoder + Default + Sized,
{
    fn deserialize(buf: &[u8]) -> Result<(T, &[u8]), DecodeError> {
        let (mut obj, nfgenmsg, remaining_data) = parse_object::<T>(
            buf,
            <T as NfNetlinkObject>::MSG_TYPE_ADD,
            <T as NfNetlinkObject>::MSG_TYPE_DEL,
        )?;
        obj.set_family(ProtocolFamily::try_from(nfgenmsg.nfgen_family as i32)?);

        Ok((obj, remaining_data))
    }
}
