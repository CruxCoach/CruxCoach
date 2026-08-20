use std::os::{fd::AsRawFd, unix::prelude::RawFd};

use nix::sys::socket::{self, AddressFamily, MsgFlags, SockFlag, SockProtocol, SockType};

use crate::{
    ProtocolFamily,
    error::QueryError,
    nlmsg::{
        NfNetlinkAttribute, NfNetlinkObject, NfNetlinkWriter, nft_nlmsg_maxsize,
        pad_netlink_object_with_variable_size,
    },
    parser::{NlMsg, parse_nlmsg},
    sys::{NLM_F_DUMP, NLM_F_MULTI},
};

#[allow(clippy::type_complexity)]
pub(crate) fn recv_and_process<'a, T>(
    sock: RawFd,
    max_seq: Option<u32>,
    cb: Option<&dyn Fn(&[u8], &mut T) -> Result<(), QueryError>>,
    working_data: &'a mut T,
) -> Result<(), QueryError> {
    let mut msg_buffer = vec![0; 2 * nft_nlmsg_maxsize() as usize];
    let mut buf_start = 0;
    let mut end_pos = 0;

    loop {
        let nb_recv = socket::recv(sock, &mut msg_buffer[end_pos..], MsgFlags::empty())
            .map_err(QueryError::NetlinkRecvError)?;
        if nb_recv == 0 {
            return Ok(());
        }
        end_pos += nb_recv;
        loop {
            let buf = &msg_buffer.as_slice()[buf_start..end_pos];
            // exit the loop and try to receive further messages when we consumed all the buffer
            if buf.is_empty() {
                break;
            }

            debug!("Calling parse_nlmsg");
            let (nlmsghdr, msg) = parse_nlmsg(buf)?;
            debug!("Got a valid netlink message: {:?} {:?}", nlmsghdr, msg);

            match msg {
                NlMsg::Done => {
                    return Ok(());
                }
                NlMsg::Error(e) => {
                    if e.error != 0 {
                        return Err(QueryError::NetlinkError(e));
                    }
                }
                NlMsg::Noop => {}
                NlMsg::NfGenMsg(_genmsg, _data) => {
                    if let Some(cb) = cb {
                        cb(&buf[0..nlmsghdr.nlmsg_len as usize], working_data)?;
                    }
                }
            }

            // we cannot know when a sequence of messages will end if the messages do not end
            // with an NlMsg::Done marker if a maximum sequence number wasn't specified
            if max_seq.is_none() && nlmsghdr.nlmsg_flags & NLM_F_MULTI as u16 == 0 {
                return Err(QueryError::UndecidableMessageTermination);
            }

            // retrieve the next message
            if let Some(max_seq) = max_seq
                && nlmsghdr.nlmsg_seq >= max_seq
            {
                return Ok(());
            }

            // netlink messages are 4bytes aligned
            let aligned_length = pad_netlink_object_with_variable_size(nlmsghdr.nlmsg_len as usize);
            buf_start += aligned_length;
        }
        // Ensure that we always have nft_nlmsg_maxsize() free space available in the buffer.
        // We achieve this by relocating the buffer content at the beginning of the buffer
        if end_pos >= nft_nlmsg_maxsize() as usize {
            if buf_start < end_pos {
                msg_buffer.copy_within(buf_start..end_pos, 0);
            }
            end_pos -= buf_start;
            buf_start = 0;
        }
    }
}

/// Returns a buffer containing a netlink message which requests a list of all the netfilter
/// matching objects (e.g. tables, chains, rules, ...).
/// Supply the type of objects to retrieve (e.g. libc::NFT_MSG_GETTABLE), and a search filter.
pub fn get_list_of_objects<T: NfNetlinkAttribute>(
    msg_type: u16,
    seq: u32,
    filter: Option<&T>,
) -> Result<Vec<u8>, QueryError> {
    let mut buffer = Vec::new();
    let mut writer = NfNetlinkWriter::new(&mut buffer);
    writer.write_header(
        msg_type,
        ProtocolFamily::Unspec,
        NLM_F_DUMP as u16,
        seq,
        None,
    );
    if let Some(filter) = filter {
        let buf = writer.add_data_zeroed(filter.get_size());
        filter.write_payload(buf);
    }
    writer.finalize_writing_object();
    Ok(buffer)
}

/// Lists objects of a certain type (e.g. libc::NFT_MSG_GETTABLE) with the help of a helper
/// function called by mnl::cb_run2.
/// The callback expects a tuple of additional data (supplied as an argument to this function)
/// and of the output vector, to which it should append the parsed object it received.
pub fn list_objects_with_data<'a, Object, Accumulator>(
    data_type: u16,
    cb: &dyn Fn(Object, &mut Accumulator) -> Result<(), QueryError>,
    filter: Option<&Object>,
    working_data: &'a mut Accumulator,
) -> Result<(), QueryError>
where
    Object: NfNetlinkObject + NfNetlinkAttribute,
{
    debug!("Listing objects of kind {}", data_type);
    let sock = socket::socket(
        AddressFamily::Netlink,
        SockType::Raw,
        SockFlag::empty(),
        SockProtocol::NetlinkNetFilter,
    )
    .map_err(QueryError::NetlinkOpenError)?;

    let seq = 0;

    let chains_buf = get_list_of_objects(data_type, seq, filter)?;
    socket::send(sock.as_raw_fd(), &chains_buf, MsgFlags::empty())
        .map_err(QueryError::NetlinkSendError)?;

    // the kernel should return NLM_F_MULTI objects
    recv_and_process(
        sock.as_raw_fd(),
        None,
        Some(&|buf: &[u8], working_data: &mut Accumulator| {
            debug!("Calling Object::deserialize()");
            cb(Object::deserialize(buf)?.0, working_data)
        }),
        working_data,
    )
}
