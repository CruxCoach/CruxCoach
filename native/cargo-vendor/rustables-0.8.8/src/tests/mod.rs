use crate::data_type::DataType;
use crate::nlmsg::{NfNetlinkObject, NfNetlinkWriter};
use crate::parser::{NlMsg, parse_nlmsg};
use crate::set::{Set, SetBuilder};
use crate::{Chain, MsgType, ProtocolFamily, Rule, Table, sys::*};

mod batch;
mod chain;
mod expr;
mod rule;
mod set;
mod table;

pub const TABLE_NAME: &'static str = "mocktable";
pub const CHAIN_NAME: &'static str = "mockchain";
pub const SET_NAME: &'static str = "mockset";

pub const TABLE_USERDATA: &'static str = "mocktabledata";
pub const CHAIN_USERDATA: &'static str = "mockchaindata";
pub const RULE_USERDATA: &'static str = "mockruledata";
pub const SET_USERDATA: &'static str = "mocksetdata";

type NetLinkType = u16;

#[derive(Debug, Clone, Eq, Ord)]
pub enum NetlinkExpr {
    Nested(NetLinkType, Vec<NetlinkExpr>),
    Final(NetLinkType, Vec<u8>),
    List(Vec<NetlinkExpr>),
}

impl NetlinkExpr {
    pub fn to_raw(self) -> Vec<u8> {
        match self.sort() {
            NetlinkExpr::Final(ty, val) => {
                let len = val.len() + 4;
                let mut res = Vec::with_capacity(len);

                res.extend(&(len as u16).to_le_bytes());
                res.extend(&ty.to_le_bytes());
                res.extend(val);
                // alignment
                while res.len() % 4 != 0 {
                    res.push(0);
                }

                res
            }
            NetlinkExpr::Nested(ty, exprs) => {
                // some heuristic to decrease allocations (even though this is
                // only useful for testing so performance is not an objective)
                let mut sub = Vec::with_capacity(exprs.len() * 50);

                for expr in exprs {
                    sub.append(&mut expr.to_raw());
                }

                let len = sub.len() + 4;
                let mut res = Vec::with_capacity(len);

                // set the "NESTED" flag
                res.extend(&(len as u16).to_le_bytes());
                res.extend(&(ty | NLA_F_NESTED as u16).to_le_bytes());
                res.extend(sub);

                res
            }
            NetlinkExpr::List(exprs) => {
                // some heuristic to decrease allocations (even though this is
                // only useful for testing so performance is not an objective)
                let mut list = Vec::with_capacity(exprs.len() * 50);

                for expr in exprs {
                    list.append(&mut expr.to_raw());
                }

                list
            }
        }
    }

    pub fn sort(self) -> Self {
        match self {
            NetlinkExpr::Final(_, _) => self,
            NetlinkExpr::Nested(ty, mut exprs) => {
                exprs.sort();
                NetlinkExpr::Nested(ty, exprs)
            }
            NetlinkExpr::List(mut exprs) => {
                exprs.sort();
                NetlinkExpr::List(exprs)
            }
        }
    }
}

impl PartialEq for NetlinkExpr {
    fn eq(&self, other: &Self) -> bool {
        match (self.clone().sort(), other.clone().sort()) {
            (NetlinkExpr::Nested(k1, v1), NetlinkExpr::Nested(k2, v2)) => k1 == k2 && v1 == v2,
            (NetlinkExpr::Final(k1, v1), NetlinkExpr::Final(k2, v2)) => k1 == k2 && v1 == v2,
            (NetlinkExpr::List(v1), NetlinkExpr::List(v2)) => v1 == v2,
            _ => false,
        }
    }
}

impl PartialOrd for NetlinkExpr {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        match (self, other) {
            (
                NetlinkExpr::Nested(k1, _) | NetlinkExpr::Final(k1, _),
                NetlinkExpr::Nested(k2, _) | NetlinkExpr::Final(k2, _),
            ) => k1.partial_cmp(k2),
            (NetlinkExpr::List(v1), NetlinkExpr::List(v2)) => v1.partial_cmp(v2),
            (_, NetlinkExpr::List(_)) => Some(std::cmp::Ordering::Less),
            (NetlinkExpr::List(_), _) => Some(std::cmp::Ordering::Greater),
        }
    }
}

pub fn get_test_table() -> Table {
    Table::new(ProtocolFamily::Inet)
        .with_name(TABLE_NAME)
        .with_flags(0u32)
}

pub fn get_test_table_raw_expr() -> NetlinkExpr {
    NetlinkExpr::List(vec![
        NetlinkExpr::Final(NFTA_TABLE_FLAGS, 0u32.to_be_bytes().to_vec()),
        NetlinkExpr::Final(NFTA_TABLE_NAME, TABLE_NAME.as_bytes().to_vec()),
    ])
    .sort()
}

pub fn get_test_table_with_userdata_raw_expr() -> NetlinkExpr {
    NetlinkExpr::List(vec![
        NetlinkExpr::Final(NFTA_TABLE_FLAGS, 0u32.to_be_bytes().to_vec()),
        NetlinkExpr::Final(NFTA_TABLE_NAME, TABLE_NAME.as_bytes().to_vec()),
        NetlinkExpr::Final(NFTA_TABLE_USERDATA, TABLE_USERDATA.as_bytes().to_vec()),
    ])
    .sort()
}

pub fn get_test_chain() -> Chain {
    Chain::new(&get_test_table()).with_name(CHAIN_NAME)
}

pub fn get_test_rule() -> Rule {
    Rule::new(&get_test_chain()).unwrap()
}

pub fn get_test_set<K: DataType>() -> Set {
    SetBuilder::<K>::new(SET_NAME, &get_test_table())
        .expect("Couldn't create a set")
        .finish()
        .0
        .with_userdata(SET_USERDATA)
}

pub fn get_test_nlmsg_with_msg_type<'a>(
    buf: &'a mut Vec<u8>,
    obj: &mut impl NfNetlinkObject,
    msg_type: MsgType,
) -> (nlmsghdr, nfgenmsg, &'a [u8]) {
    let mut writer = NfNetlinkWriter::new(buf);
    obj.add_or_remove(&mut writer, msg_type, 0);

    let (hdr, msg) = parse_nlmsg(buf.as_slice()).expect("Couldn't parse the message");

    let (nfgenmsg, raw_value) = match msg {
        NlMsg::NfGenMsg(nfgenmsg, raw_value) => (nfgenmsg, raw_value),
        _ => panic!("Invalid return value type, expected a valid message"),
    };

    // sanity checks on the global message (this should be very similar/factorisable for the
    // most part in other tests)
    // TODO: check the messages flags
    assert_eq!(nfgenmsg.res_id.to_be(), 0);

    (hdr, nfgenmsg, raw_value)
}

pub fn get_test_nlmsg<'a>(
    buf: &'a mut Vec<u8>,
    obj: &mut impl NfNetlinkObject,
) -> (nlmsghdr, nfgenmsg, &'a [u8]) {
    get_test_nlmsg_with_msg_type(buf, obj, MsgType::Add)
}
