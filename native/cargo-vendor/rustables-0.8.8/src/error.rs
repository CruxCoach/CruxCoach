use std::string::FromUtf8Error;

use nix::errno::Errno;
use thiserror::Error;

use crate::sys::nlmsgerr;

#[derive(Error, Debug)]
pub enum DecodeError {
    #[error("The buffer is too small to hold a valid message")]
    BufTooSmall,

    #[error("The message is too small")]
    NlMsgTooSmall,

    #[error("The message holds unexpected data")]
    InvalidDataSize,

    #[error("Invalid subsystem, expected NFTABLES")]
    InvalidSubsystem(u8),

    #[error("Invalid version, expected NFNETLINK_V0")]
    InvalidVersion(u8),

    #[error("Invalid port ID")]
    InvalidPortId(u32),

    #[error("Invalid sequence number")]
    InvalidSeq(u32),

    #[error("The generation number was bumped in the kernel while the operation was running, interrupting it")]
    ConcurrentGenerationUpdate,

    #[error("Unsupported message type")]
    UnsupportedType(u16),

    #[error("Invalid attribute type")]
    InvalidAttributeType,

    #[error("Invalid type for a chain")]
    UnknownChainType,

    #[error("Invalid policy for a chain")]
    UnknownChainPolicy,

    #[error("Unknown type for a Meta expression")]
    UnknownMetaType(u32),

    #[error("Unsupported value for an icmp reject type")]
    UnknownRejectType(u32),

    #[error("Unsupported value for an icmp code in a reject expression")]
    UnknownIcmpCode(u8),

    #[error("Invalid value for a register")]
    UnknownRegister(u32),

    #[error("Invalid type for a verdict expression")]
    UnknownVerdictType(i32),

    #[error("Invalid type for a nat expression")]
    UnknownNatType(i32),

    #[error("Invalid type for a payload expression")]
    UnknownPayloadType(u32),

    #[error("Invalid type for a compare expression")]
    UnknownCmpOp(u32),

    #[error("Invalid type for a conntrack key")]
    UnknownConntrackKey(u32),

    #[error("Unsupported value for a link layer header field")]
    UnknownLinkLayerHeaderField(u32, u32),

    #[error("Unsupported value for an IPv4 header field")]
    UnknownIPv4HeaderField(u32, u32),

    #[error("Unsupported value for an IPv6 header field")]
    UnknownIPv6HeaderField(u32, u32),

    #[error("Unsupported value for a TCP header field")]
    UnknownTCPHeaderField(u32, u32),

    #[error("Unsupported value for an UDP header field")]
    UnknownUDPHeaderField(u32, u32),

    #[error("Unsupported value for an ICMPv6 header field")]
    UnknownICMPv6HeaderField(u32, u32),

    #[error("Missing the 'base' attribute to deserialize the payload object")]
    PayloadMissingBase,

    #[error("Missing the 'offset' attribute to deserialize the payload object")]
    PayloadMissingOffset,

    #[error("Missing the 'len' attribute to deserialize the payload object")]
    PayloadMissingLen,

    #[error("The object does not contain a name for the expression being parsed")]
    MissingExpressionName,

    #[error("Unsupported attribute type")]
    UnsupportedAttributeType(u16),

    #[error("Unexpected message type")]
    UnexpectedType(u16),

    #[error("The decoded String is not UTF8 compliant")]
    StringDecodeFailure(#[from] FromUtf8Error),

    #[error("Invalid value for a protocol family")]
    UnknownProtocolFamily(i32),
}

#[derive(thiserror::Error, Debug)]
pub enum BuilderError {
    #[error("The length of the arguments are not compatible with each other")]
    IncompatibleLength,

    #[error("The table does not have a name")]
    MissingTableName,

    #[error("Missing information in the chain to create a rule")]
    MissingChainInformationError,

    #[error("Missing name for the set")]
    MissingSetName,

    #[error("The interface name is too long to be written")]
    InterfaceNameTooLong,

    #[error("The log prefix string is more than 127 characters long")]
    TooLongLogPrefix,
}

#[derive(thiserror::Error, Debug)]
pub enum QueryError {
    #[error("Unable to open netlink socket to netfilter")]
    NetlinkOpenError(#[source] nix::Error),

    #[error("Unable to send netlink command to netfilter")]
    NetlinkSendError(#[source] nix::Error),

    #[error("Error while reading from netlink socket")]
    NetlinkRecvError(#[source] nix::Error),

    #[error("Error while processing an incoming netlink message")]
    ProcessNetlinkError(#[from] DecodeError),

    #[error("Error while building netlink objects in Rust")]
    BuilderError(#[from] BuilderError),

    #[error("Error received from the kernel")]
    NetlinkError(nlmsgerr),

    #[error("Couldn't allocate a netlink object, out of memory ?")]
    NetlinkAllocationFailed,

    #[error("This socket is not a netlink socket")]
    NotNetlinkSocket,

    #[error("Couldn't retrieve information on a socket")]
    RetrievingSocketInfoFailed,

    #[error("Only a part of the message was sent")]
    TruncatedSend,

    #[error("Got a message without the NLM_F_MULTI flag, but a maximum sequence number was not specified")]
    UndecidableMessageTermination,

    #[error("Couldn't close the socket")]
    CloseFailed(#[source] Errno),

    #[error("Couldn't bind the socket")]
    BindFailed,
}
