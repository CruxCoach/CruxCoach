use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

pub trait DataType {
    const TYPE: u32;
    const LEN: u32;

    fn data(&self) -> Vec<u8>;
}

impl DataType for Ipv4Addr {
    const TYPE: u32 = 7;
    const LEN: u32 = 4;

    fn data(&self) -> Vec<u8> {
        self.octets().to_vec()
    }
}

impl DataType for Ipv6Addr {
    const TYPE: u32 = 8;
    const LEN: u32 = 16;

    fn data(&self) -> Vec<u8> {
        self.octets().to_vec()
    }
}

impl<const N: usize> DataType for [u8; N] {
    const TYPE: u32 = 5;
    const LEN: u32 = N as u32;

    fn data(&self) -> Vec<u8> {
        self.to_vec()
    }
}

pub fn ip_to_vec(ip: IpAddr) -> Vec<u8> {
    match ip {
        IpAddr::V4(x) => x.octets().to_vec(),
        IpAddr::V6(x) => x.octets().to_vec(),
    }
}
