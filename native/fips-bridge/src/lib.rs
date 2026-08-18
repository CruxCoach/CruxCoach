//! Minimal CruxCoach application-message embedding for FIPS.
//!
//! CruxCoach does not install a VPN. Instead, each application message is one
//! IPv6/UDP datagram injected through FIPS' app-owned TUN seam. FIPS still
//! performs authenticated peer sessions, encryption and multi-hop routing.

use std::net::Ipv6Addr;

pub const APP_PORT: u16 = 42_424;
pub const MAX_APP_PAYLOAD: usize = 1_100;

#[derive(Debug, PartialEq, Eq)]
pub struct AppDatagram {
    pub source: Ipv6Addr,
    pub destination: Ipv6Addr,
    pub payload: Vec<u8>,
}

/// Build a standards-compliant IPv6 UDP packet. Application fragmentation is
/// deliberately above this layer, keeping every FIPS datagram bounded.
pub fn encode_datagram(source: Ipv6Addr, destination: Ipv6Addr, payload: &[u8]) -> Option<Vec<u8>> {
    if payload.len() > MAX_APP_PAYLOAD {
        return None;
    }
    let udp_len = 8usize.checked_add(payload.len())?;
    let mut packet = vec![0u8; 40 + udp_len];
    packet[0] = 0x60;
    packet[4..6].copy_from_slice(&(udp_len as u16).to_be_bytes());
    packet[6] = 17; // UDP
    packet[7] = 64;
    packet[8..24].copy_from_slice(&source.octets());
    packet[24..40].copy_from_slice(&destination.octets());
    packet[40..42].copy_from_slice(&APP_PORT.to_be_bytes());
    packet[42..44].copy_from_slice(&APP_PORT.to_be_bytes());
    packet[44..46].copy_from_slice(&(udp_len as u16).to_be_bytes());
    packet[48..].copy_from_slice(payload);
    let checksum = udp_checksum(source, destination, &packet[40..]);
    packet[46..48].copy_from_slice(&checksum.to_be_bytes());
    Some(packet)
}

pub fn decode_datagram(packet: &[u8]) -> Option<AppDatagram> {
    if packet.len() < 48 || packet[0] >> 4 != 6 || packet[6] != 17 {
        return None;
    }
    let payload_len = u16::from_be_bytes([packet[4], packet[5]]) as usize;
    if payload_len < 8 || packet.len() < 40 + payload_len {
        return None;
    }
    if u16::from_be_bytes([packet[42], packet[43]]) != APP_PORT {
        return None;
    }
    let source = Ipv6Addr::from(<[u8; 16]>::try_from(&packet[8..24]).ok()?);
    let destination = Ipv6Addr::from(<[u8; 16]>::try_from(&packet[24..40]).ok()?);
    let udp = &packet[40..40 + payload_len];
    if udp_checksum(source, destination, udp) != 0 {
        return None;
    }
    Some(AppDatagram {
        source,
        destination,
        payload: udp[8..].to_vec(),
    })
}

fn udp_checksum(source: Ipv6Addr, destination: Ipv6Addr, udp: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut add = |bytes: &[u8]| {
        for pair in bytes.chunks(2) {
            sum += u16::from_be_bytes([pair[0], *pair.get(1).unwrap_or(&0)]) as u32;
        }
    };
    add(&source.octets());
    add(&destination.octets());
    add(&(udp.len() as u32).to_be_bytes());
    add(&[0, 0, 0, 17]);
    add(udp);
    while sum >> 16 != 0 {
        sum = (sum & 0xffff) + (sum >> 16);
    }
    !(sum as u16)
}

#[cfg(target_os = "android")]
mod android;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn datagram_round_trip_and_checksum_rejection() {
        let src = "fd00::1".parse().unwrap();
        let dst = "fd00::2".parse().unwrap();
        let mut packet = encode_datagram(src, dst, b"board-cell").unwrap();
        assert_eq!(decode_datagram(&packet).unwrap().payload, b"board-cell");
        packet[48] ^= 1;
        assert!(decode_datagram(&packet).is_none());
    }

    #[test]
    fn payload_is_bounded() {
        assert!(
            encode_datagram(
                Ipv6Addr::LOCALHOST,
                Ipv6Addr::LOCALHOST,
                &vec![0; MAX_APP_PAYLOAD + 1]
            )
            .is_none()
        );
    }
}
