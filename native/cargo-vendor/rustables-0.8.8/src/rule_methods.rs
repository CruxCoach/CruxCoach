use std::ffi::CString;
use std::net::IpAddr;

use ipnetwork::IpNetwork;

use crate::data_type::ip_to_vec;
use crate::error::BuilderError;
use crate::expr::ct::{ConnTrackState, Conntrack, ConntrackKey};
use crate::expr::{
    Bitwise, Cmp, CmpOp, HighLevelPayload, IPv4HeaderField, IPv6HeaderField, Immediate, Masquerade,
    Meta, MetaType, NetworkHeaderField, TCPHeaderField, TransportHeaderField, UDPHeaderField,
    VerdictKind,
};
use crate::Rule;

/// Simple protocol description. Note that it does not implement other layer 4 protocols as
/// IGMP et al. See [`Rule::igmp`] for a workaround.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum Protocol {
    TCP,
    UDP,
}

impl Rule {
    fn match_port(mut self, port: u16, protocol: Protocol, source: bool) -> Self {
        self = self.protocol(protocol);
        self.add_expr(
            HighLevelPayload::Transport(match protocol {
                Protocol::TCP => TransportHeaderField::Tcp(if source {
                    TCPHeaderField::Sport
                } else {
                    TCPHeaderField::Dport
                }),
                Protocol::UDP => TransportHeaderField::Udp(if source {
                    UDPHeaderField::Sport
                } else {
                    UDPHeaderField::Dport
                }),
            })
            .build(),
        );
        self.add_expr(Cmp::new(CmpOp::Eq, port.to_be_bytes()));
        self
    }

    pub fn match_ip(mut self, ip: IpAddr, source: bool) -> Self {
        self.add_expr(Meta::new(MetaType::NfProto));
        match ip {
            IpAddr::V4(addr) => {
                self.add_expr(Cmp::new(CmpOp::Eq, [libc::NFPROTO_IPV4 as u8]));
                self.add_expr(
                    HighLevelPayload::Network(NetworkHeaderField::IPv4(if source {
                        IPv4HeaderField::Saddr
                    } else {
                        IPv4HeaderField::Daddr
                    }))
                    .build(),
                );
                self.add_expr(Cmp::new(CmpOp::Eq, addr.octets()));
            }
            IpAddr::V6(addr) => {
                self.add_expr(Cmp::new(CmpOp::Eq, [libc::NFPROTO_IPV6 as u8]));
                self.add_expr(
                    HighLevelPayload::Network(NetworkHeaderField::IPv6(if source {
                        IPv6HeaderField::Saddr
                    } else {
                        IPv6HeaderField::Daddr
                    }))
                    .build(),
                );
                self.add_expr(Cmp::new(CmpOp::Eq, addr.octets()));
            }
        }
        self
    }

    pub fn match_network(mut self, net: IpNetwork, source: bool) -> Result<Self, BuilderError> {
        self.add_expr(Meta::new(MetaType::NfProto));
        match net {
            IpNetwork::V4(_) => {
                self.add_expr(Cmp::new(CmpOp::Eq, [libc::NFPROTO_IPV4 as u8]));
                self.add_expr(
                    HighLevelPayload::Network(NetworkHeaderField::IPv4(if source {
                        IPv4HeaderField::Saddr
                    } else {
                        IPv4HeaderField::Daddr
                    }))
                    .build(),
                );
                self.add_expr(Bitwise::new(ip_to_vec(net.mask()), 0u32.to_be_bytes())?);
            }
            IpNetwork::V6(_) => {
                self.add_expr(Cmp::new(CmpOp::Eq, [libc::NFPROTO_IPV6 as u8]));
                self.add_expr(
                    HighLevelPayload::Network(NetworkHeaderField::IPv6(if source {
                        IPv6HeaderField::Saddr
                    } else {
                        IPv6HeaderField::Daddr
                    }))
                    .build(),
                );
                self.add_expr(Bitwise::new(ip_to_vec(net.mask()), 0u128.to_be_bytes())?);
            }
        }
        self.add_expr(Cmp::new(CmpOp::Eq, ip_to_vec(net.network())));
        Ok(self)
    }
}

impl Rule {
    /// Matches ICMP packets.
    pub fn icmp(mut self) -> Self {
        // quid of icmpv6?
        self.add_expr(Meta::new(MetaType::L4Proto));
        self.add_expr(Cmp::new(CmpOp::Eq, [libc::IPPROTO_ICMP as u8]));
        self
    }
    /// Matches IGMP packets.
    pub fn igmp(mut self) -> Self {
        self.add_expr(Meta::new(MetaType::L4Proto));
        self.add_expr(Cmp::new(CmpOp::Eq, [libc::IPPROTO_IGMP as u8]));
        self
    }
    /// Matches packets from source `port` and `protocol`.
    pub fn sport(self, port: u16, protocol: Protocol) -> Self {
        self.match_port(port, protocol, true)
    }
    /// Matches packets to destination `port` and `protocol`.
    pub fn dport(self, port: u16, protocol: Protocol) -> Self {
        self.match_port(port, protocol, false)
    }
    /// Matches packets on `protocol`.
    pub fn protocol(mut self, protocol: Protocol) -> Self {
        self.add_expr(Meta::new(MetaType::L4Proto));
        self.add_expr(Cmp::new(
            CmpOp::Eq,
            [match protocol {
                Protocol::TCP => libc::IPPROTO_TCP,
                Protocol::UDP => libc::IPPROTO_UDP,
            } as u8],
        ));
        self
    }
    /// Matches packets in an already established connection.
    pub fn established(mut self) -> Result<Self, BuilderError> {
        let allowed_states = ConnTrackState::ESTABLISHED.bits();
        self.add_expr(Conntrack::new(ConntrackKey::State));
        self.add_expr(Bitwise::new(
            allowed_states.to_le_bytes(),
            0u32.to_be_bytes(),
        )?);
        self.add_expr(Cmp::new(CmpOp::Neq, 0u32.to_be_bytes()));
        Ok(self)
    }
    /// Deprecated. Please use [Rule::iiface_id] instead, which has the same interface.
    #[deprecated = "Replaced by `iiface_id`"]
    pub fn iface_id(self, iface_index: libc::c_uint) -> Self {
        self.iiface_id(iface_index)
    }
    /// Matches packets received through `iface_index`. Interface indexes can be queried with
    /// `iface_index()`.
    pub fn iiface_id(mut self, iface_index: libc::c_uint) -> Self {
        self.add_expr(Meta::new(MetaType::Iif));
        self.add_expr(Cmp::new(CmpOp::Eq, iface_index.to_be_bytes()));
        self
    }
    /// Deprecated. Please use [Rule::iiface] instead, which has the same interface.
    #[deprecated = "Replaced by `iiface`"]
    pub fn iface(self, iface_name: &str) -> Result<Self, BuilderError> {
        self.iiface(iface_name)
    }
    /// Matches packets received through `iface_name` (an interface name, as in "wlan0" or "lo").
    pub fn iiface(mut self, iface_name: &str) -> Result<Self, BuilderError> {
        if iface_name.len() >= libc::IFNAMSIZ {
            return Err(BuilderError::InterfaceNameTooLong);
        }
        let mut iface_vec = iface_name.as_bytes().to_vec();
        // null terminator
        iface_vec.push(0u8);

        self.add_expr(Meta::new(MetaType::IifName));
        self.add_expr(Cmp::new(CmpOp::Eq, iface_vec));
        Ok(self)
    }
    /// Matches packets sent through `iface_index`. Interface indexes can be queried with
    /// `iface_index()`.
    pub fn oiface_id(mut self, iface_index: libc::c_uint) -> Self {
        self.add_expr(Meta::new(MetaType::Oif));
        self.add_expr(Cmp::new(CmpOp::Eq, iface_index.to_be_bytes()));
        self
    }
    /// Matches packets sent through `iface_name` (an interface name, as in "wlan0" or "lo").
    pub fn oiface(mut self, iface_name: &str) -> Result<Self, BuilderError> {
        if iface_name.len() >= libc::IFNAMSIZ {
            return Err(BuilderError::InterfaceNameTooLong);
        }
        let mut iface_vec = iface_name.as_bytes().to_vec();
        // null terminator
        iface_vec.push(0u8);

        self.add_expr(Meta::new(MetaType::OifName));
        self.add_expr(Cmp::new(CmpOp::Eq, iface_vec));
        Ok(self)
    }
    /// Matches packets whose source IP address is `saddr`.
    pub fn saddr(self, ip: IpAddr) -> Self {
        self.match_ip(ip, true)
    }
    /// Matches packets whose destination IP address is `saddr`.
    pub fn daddr(self, ip: IpAddr) -> Self {
        self.match_ip(ip, false)
    }
    /// Matches packets whose source network is `net`.
    pub fn snetwork(self, net: IpNetwork) -> Result<Self, BuilderError> {
        self.match_network(net, true)
    }
    /// Matches packets whose destination network is `net`.
    pub fn dnetwork(self, net: IpNetwork) -> Result<Self, BuilderError> {
        self.match_network(net, false)
    }
    /// Adds the `Accept` verdict to the rule. The packet will be sent to destination.
    pub fn accept(mut self) -> Self {
        self.add_expr(Immediate::new_verdict(VerdictKind::Accept));
        self
    }
    /// Adds the `Drop` verdict to the rule. The packet will be dropped.
    pub fn drop(mut self) -> Self {
        self.add_expr(Immediate::new_verdict(VerdictKind::Drop));
        self
    }
    /// Forwards the packet to its destination by replacing its source IP address
    /// with that of the output interface and creating a NAT binding.
    /// Note that masquerade operations only make sense in the `postrouting` chain
    /// of a NAT table. See more information on masquerading at
    /// [https://wiki.nftables.org/wiki-nftables/index.php/Performing_Network_Address_Translation_(NAT)](https://wiki.nftables.org/wiki-nftables/index.php/Performing_Network_Address_Translation_(NAT))
    pub fn masquerade(mut self) -> Self {
        self.add_expr(Masquerade {});
        self
    }
}

/// Looks up the interface index for a given interface name.
pub fn iface_index(name: &str) -> Result<libc::c_uint, std::io::Error> {
    let c_name = CString::new(name)?;
    let index = unsafe { libc::if_nametoindex(c_name.as_ptr()) };
    match index {
        0 => Err(std::io::Error::last_os_error()),
        _ => Ok(index),
    }
}
