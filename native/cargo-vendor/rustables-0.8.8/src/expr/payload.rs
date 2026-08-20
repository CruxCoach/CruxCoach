use rustables_macros::nfnetlink_struct;

use super::{Expression, Register};
use crate::{
    error::DecodeError,
    sys::{self, NFT_PAYLOAD_LL_HEADER, NFT_PAYLOAD_NETWORK_HEADER, NFT_PAYLOAD_TRANSPORT_HEADER},
};

/// Payload expressions refer to data from the packet's payload.
#[derive(Default, Debug, Copy, Clone, PartialEq, Eq)]
#[nfnetlink_struct(nested = true)]
pub struct Payload {
    #[field(sys::NFTA_PAYLOAD_DREG)]
    dreg: Register,
    #[field(sys::NFTA_PAYLOAD_BASE)]
    base: u32,
    #[field(sys::NFTA_PAYLOAD_OFFSET)]
    offset: u32,
    #[field(sys::NFTA_PAYLOAD_LEN)]
    len: u32,
    #[field(sys::NFTA_PAYLOAD_SREG)]
    sreg: Register,
}

impl Expression for Payload {
    fn get_name() -> &'static str {
        "payload"
    }
}

/// Payload expressions refer to data from the packet's payload.
#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum HighLevelPayload {
    LinkLayer(LLHeaderField),
    Network(NetworkHeaderField),
    Transport(TransportHeaderField),
}

impl HighLevelPayload {
    pub fn build(&self) -> Payload {
        match *self {
            HighLevelPayload::LinkLayer(ref f) => Payload::default()
                .with_base(NFT_PAYLOAD_LL_HEADER)
                .with_offset(f.offset())
                .with_len(f.len()),
            HighLevelPayload::Network(ref f) => Payload::default()
                .with_base(NFT_PAYLOAD_NETWORK_HEADER)
                .with_offset(f.offset())
                .with_len(f.len()),
            HighLevelPayload::Transport(ref f) => Payload::default()
                .with_base(NFT_PAYLOAD_TRANSPORT_HEADER)
                .with_offset(f.offset())
                .with_len(f.len()),
        }
        .with_dreg(Register::Reg1)
    }
}

/// Payload expressions refer to data from the packet's payload.
#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum PayloadType {
    LinkLayer(LLHeaderField),
    Network,
    Transport,
}

impl PayloadType {
    pub fn parse_from_payload(raw: &Payload) -> Result<Self, DecodeError> {
        if raw.base.is_none() {
            return Err(DecodeError::PayloadMissingBase);
        }
        if raw.len.is_none() {
            return Err(DecodeError::PayloadMissingLen);
        }
        if raw.offset.is_none() {
            return Err(DecodeError::PayloadMissingOffset);
        }
        Ok(match raw.base {
            Some(NFT_PAYLOAD_LL_HEADER) => PayloadType::LinkLayer(LLHeaderField::from_raw_data(
                raw.offset.unwrap(),
                raw.len.unwrap(),
            )?),
            Some(NFT_PAYLOAD_NETWORK_HEADER) => PayloadType::Network,
            Some(NFT_PAYLOAD_TRANSPORT_HEADER) => PayloadType::Transport,
            Some(v) => return Err(DecodeError::UnknownPayloadType(v)),
            None => return Err(DecodeError::PayloadMissingBase),
        })
    }
}

#[allow(clippy::len_without_is_empty)]
pub trait HeaderField {
    fn offset(&self) -> u32;
    fn len(&self) -> u32;
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[non_exhaustive]
pub enum LLHeaderField {
    Daddr,
    Saddr,
    EtherType,
}

impl HeaderField for LLHeaderField {
    fn offset(&self) -> u32 {
        use self::LLHeaderField::*;
        match *self {
            Daddr => 0,
            Saddr => 6,
            EtherType => 12,
        }
    }

    fn len(&self) -> u32 {
        use self::LLHeaderField::*;
        match *self {
            Daddr => 6,
            Saddr => 6,
            EtherType => 2,
        }
    }
}

impl LLHeaderField {
    pub fn from_raw_data(offset: u32, len: u32) -> Result<Self, DecodeError> {
        Ok(match (offset, len) {
            (0, 6) => Self::Daddr,
            (6, 6) => Self::Saddr,
            (12, 2) => Self::EtherType,
            _ => return Err(DecodeError::UnknownLinkLayerHeaderField(offset, len)),
        })
    }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
pub enum NetworkHeaderField {
    IPv4(IPv4HeaderField),
    IPv6(IPv6HeaderField),
}

impl HeaderField for NetworkHeaderField {
    fn offset(&self) -> u32 {
        use self::NetworkHeaderField::*;
        match *self {
            IPv4(ref f) => f.offset(),
            IPv6(ref f) => f.offset(),
        }
    }

    fn len(&self) -> u32 {
        use self::NetworkHeaderField::*;
        match *self {
            IPv4(ref f) => f.len(),
            IPv6(ref f) => f.len(),
        }
    }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[non_exhaustive]
pub enum IPv4HeaderField {
    Ttl,
    Protocol,
    Saddr,
    Daddr,
}

impl HeaderField for IPv4HeaderField {
    fn offset(&self) -> u32 {
        use self::IPv4HeaderField::*;
        match *self {
            Ttl => 8,
            Protocol => 9,
            Saddr => 12,
            Daddr => 16,
        }
    }

    fn len(&self) -> u32 {
        use self::IPv4HeaderField::*;
        match *self {
            Ttl => 1,
            Protocol => 1,
            Saddr => 4,
            Daddr => 4,
        }
    }
}

impl IPv4HeaderField {
    pub fn from_raw_data(offset: u32, len: u32) -> Result<Self, DecodeError> {
        Ok(match (offset, len) {
            (8, 1) => Self::Ttl,
            (9, 1) => Self::Protocol,
            (12, 4) => Self::Saddr,
            (16, 4) => Self::Daddr,
            _ => return Err(DecodeError::UnknownIPv4HeaderField(offset, len)),
        })
    }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[non_exhaustive]
pub enum IPv6HeaderField {
    NextHeader,
    HopLimit,
    Saddr,
    Daddr,
}

impl HeaderField for IPv6HeaderField {
    fn offset(&self) -> u32 {
        use self::IPv6HeaderField::*;
        match *self {
            NextHeader => 6,
            HopLimit => 7,
            Saddr => 8,
            Daddr => 24,
        }
    }

    fn len(&self) -> u32 {
        use self::IPv6HeaderField::*;
        match *self {
            NextHeader => 1,
            HopLimit => 1,
            Saddr => 16,
            Daddr => 16,
        }
    }
}

impl IPv6HeaderField {
    pub fn from_raw_data(offset: u32, len: u32) -> Result<Self, DecodeError> {
        Ok(match (offset, len) {
            (6, 1) => Self::NextHeader,
            (7, 1) => Self::HopLimit,
            (8, 16) => Self::Saddr,
            (24, 16) => Self::Daddr,
            _ => return Err(DecodeError::UnknownIPv6HeaderField(offset, len)),
        })
    }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[non_exhaustive]
pub enum TransportHeaderField {
    Tcp(TCPHeaderField),
    Udp(UDPHeaderField),
    ICMPv6(ICMPv6HeaderField),
}

impl HeaderField for TransportHeaderField {
    fn offset(&self) -> u32 {
        use self::TransportHeaderField::*;
        match *self {
            Tcp(ref f) => f.offset(),
            Udp(ref f) => f.offset(),
            ICMPv6(ref f) => f.offset(),
        }
    }

    fn len(&self) -> u32 {
        use self::TransportHeaderField::*;
        match *self {
            Tcp(ref f) => f.len(),
            Udp(ref f) => f.len(),
            ICMPv6(ref f) => f.len(),
        }
    }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[non_exhaustive]
pub enum TCPHeaderField {
    Sport,
    Dport,
}

impl HeaderField for TCPHeaderField {
    fn offset(&self) -> u32 {
        use self::TCPHeaderField::*;
        match *self {
            Sport => 0,
            Dport => 2,
        }
    }

    fn len(&self) -> u32 {
        use self::TCPHeaderField::*;
        match *self {
            Sport => 2,
            Dport => 2,
        }
    }
}

impl TCPHeaderField {
    pub fn from_raw_data(offset: u32, len: u32) -> Result<Self, DecodeError> {
        Ok(match (offset, len) {
            (0, 2) => Self::Sport,
            (2, 2) => Self::Dport,
            _ => return Err(DecodeError::UnknownTCPHeaderField(offset, len)),
        })
    }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[non_exhaustive]
pub enum UDPHeaderField {
    Sport,
    Dport,
    Len,
}

impl HeaderField for UDPHeaderField {
    fn offset(&self) -> u32 {
        use self::UDPHeaderField::*;
        match *self {
            Sport => 0,
            Dport => 2,
            Len => 4,
        }
    }

    fn len(&self) -> u32 {
        use self::UDPHeaderField::*;
        match *self {
            Sport => 2,
            Dport => 2,
            Len => 2,
        }
    }
}

impl UDPHeaderField {
    pub fn from_raw_data(offset: u32, len: u32) -> Result<Self, DecodeError> {
        Ok(match (offset, len) {
            (0, 2) => Self::Sport,
            (2, 2) => Self::Dport,
            (4, 2) => Self::Len,
            _ => return Err(DecodeError::UnknownUDPHeaderField(offset, len)),
        })
    }
}

#[derive(Debug, Copy, Clone, Eq, PartialEq)]
#[non_exhaustive]
pub enum ICMPv6HeaderField {
    Type,
    Code,
    Checksum,
}

impl HeaderField for ICMPv6HeaderField {
    fn offset(&self) -> u32 {
        use self::ICMPv6HeaderField::*;
        match *self {
            Type => 0,
            Code => 1,
            Checksum => 2,
        }
    }

    fn len(&self) -> u32 {
        use self::ICMPv6HeaderField::*;
        match *self {
            Type => 1,
            Code => 1,
            Checksum => 2,
        }
    }
}

impl ICMPv6HeaderField {
    pub fn from_raw_data(offset: u32, len: u32) -> Result<Self, DecodeError> {
        Ok(match (offset, len) {
            (0, 1) => Self::Type,
            (1, 1) => Self::Code,
            (2, 2) => Self::Checksum,
            _ => return Err(DecodeError::UnknownICMPv6HeaderField(offset, len)),
        })
    }
}
