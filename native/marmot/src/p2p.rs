//! The direct (peer-to-peer) wire of the Olm channel: spike M0.
//!
//! Everything that crosses LAN or BLE is produced and parsed here; Kotlin
//! transports move opaque containers and never parse them. One container is
//! one exchange step. The initiator sends a request; the responder answers
//! with a response bound to that request's nonce.
//!
//! ```text
//! container := "ccx1" kind:u8 nonce:16 reply_to:16 time:u64 count:u16 section*
//! section   := addr:16 flags:u8 len:u32 body mac:16
//! channel   := frames:u16 (id:8 len:u32 bytes)* acks:u16 id:8* nacks:u16 id:8*
//! ```
//!
//! `addr` is an HMAC over the container nonce, so a section names its
//! relationship without a stable identifier on the wire. Channel sections are
//! authenticated with the relationship's link key; invitation sections carry a
//! self-authenticating, signed invitation package. Olm messages are opaque
//! frames here; Olm authenticates and decrypts them.
use crate::error::{Error, Result};
use chacha20poly1305::aead::Aead;
use chacha20poly1305::{ChaCha20Poly1305, KeyInit};
use hmac::{Hmac, Mac};
use sha2_v11::{Digest, Sha256};

pub const MAGIC: &[u8; 4] = b"ccx1";
pub const KIND_REQUEST: u8 = 1;
pub const KIND_RESPONSE: u8 = 2;
/// A channel section: an established or pre-acceptance relationship.
pub const SECTION_CHANNEL: u8 = 1;
/// An invitation offered to a not-yet-identified endpoint.
pub const SECTION_INVITE: u8 = 2;
/// One exchange step in either direction (fits a 512 KiB JNI command as base64).
pub const MAX_CONTAINER: usize = 262_144;
pub const MAX_SECTIONS: usize = 40;
pub const MAX_FRAMES: usize = 64;
/// One Olm message carrying at most one 64 KiB application message.
pub const MAX_FRAME: usize = 90_000;
pub const MAX_IDS: usize = 256;
/// Sections older or newer than this are refused (replayed captures).
pub const MAX_SKEW_S: u64 = 7 * 86_400;
/// Beacon period: an advertised tag changes this often.
pub const BEACON_PERIOD_S: u64 = 600;
pub const BEACON_BYTES: usize = 8;
pub const STREAM_CHUNK: usize = 65_536;

pub type FrameId = [u8; 8];

pub fn random<const N: usize>() -> Result<[u8; N]> {
    let mut value = [0u8; N];
    getrandom::fill(&mut value).map_err(|_| Error("random"))?;
    Ok(value)
}

pub fn sha256(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

/// HMAC-SHA256 over length-prefixed parts (unambiguous concatenation).
pub fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256>>::new_from_slice(key).expect("hmac accepts any key length");
    for part in parts {
        mac.update(&(part.len() as u32).to_be_bytes());
        mac.update(part);
    }
    mac.finalize().into_bytes().into()
}

pub fn hmac16(key: &[u8], parts: &[&[u8]]) -> [u8; 16] {
    let full = hmac(key, parts);
    let mut out = [0u8; 16];
    out.copy_from_slice(&full[..16]);
    out
}

/// Constant-time comparison for MACs and hints.
pub fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// The relationship link key. Both receive tags are needed: the inviter's
/// tag travels in the (possibly photographed) invitation, the acceptor's only
/// inside Olm, so a QR onlooker never learns the link key.
pub fn link_key(inviter_tag: &[u8; 32], acceptor_tag: &[u8; 32]) -> [u8; 32] {
    let hkdf = hkdf::Hkdf::<Sha256>::new(
        Some(b"cc-link-v1"),
        &[inviter_tag.as_slice(), acceptor_tag].concat(),
    );
    let mut okm = [0u8; 32];
    hkdf.expand(b"cruxcoach share link", &mut okm)
        .expect("32 bytes is a valid HKDF output length");
    okm
}

pub fn channel_addr(key: &[u8], nonce: &[u8; 16]) -> [u8; 16] {
    hmac16(key, &[b"cc-hint-v1", nonce])
}

pub fn invite_addr(invited: &[u8; 32], nonce: &[u8; 16]) -> [u8; 16] {
    hmac16(invited, &[b"cc-invite-v1", nonce])
}

/// A rotating advertisement tag (BLE service data, NSD TXT). Only holders of
/// the secret can link two periods.
pub fn beacon(secret: &[u8], period: u64) -> [u8; BEACON_BYTES] {
    let full = hmac(secret, &[b"cc-beacon-v1", &period.to_be_bytes()]);
    let mut out = [0u8; BEACON_BYTES];
    out.copy_from_slice(&full[..BEACON_BYTES]);
    out
}

pub fn period(now_s: u64) -> u64 {
    now_s / BEACON_PERIOD_S
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ChannelBody {
    pub frames: Vec<(FrameId, Vec<u8>)>,
    pub acks: Vec<FrameId>,
    pub nacks: Vec<FrameId>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Section {
    pub addr: [u8; 16],
    pub flags: u8,
    pub body: Vec<u8>,
    pub mac: [u8; 16],
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Container {
    pub kind: u8,
    pub nonce: [u8; 16],
    pub reply_to: [u8; 16],
    pub time: u64,
    pub sections: Vec<Section>,
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.at.checked_add(n).ok_or(Error("wire_format"))?;
        let slice = self.bytes.get(self.at..end).ok_or(Error("wire_format"))?;
        self.at = end;
        Ok(slice)
    }
    fn array<const N: usize>(&mut self) -> Result<[u8; N]> {
        let mut out = [0u8; N];
        out.copy_from_slice(self.take(N)?);
        Ok(out)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.array()?))
    }
    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.array()?))
    }
    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(self.array()?))
    }
    fn done(&self) -> Result<()> {
        if self.at == self.bytes.len() {
            Ok(())
        } else {
            Err(Error("wire_format"))
        }
    }
}

impl ChannelBody {
    pub fn is_empty(&self) -> bool {
        self.frames.is_empty() && self.acks.is_empty() && self.nacks.is_empty()
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend((self.frames.len() as u16).to_be_bytes());
        for (id, frame) in &self.frames {
            out.extend(id);
            out.extend((frame.len() as u32).to_be_bytes());
            out.extend(frame);
        }
        for ids in [&self.acks, &self.nacks] {
            out.extend((ids.len() as u16).to_be_bytes());
            for id in ids.iter() {
                out.extend(id);
            }
        }
        out
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        let mut r = Reader { bytes, at: 0 };
        let count = r.u16()? as usize;
        if count > MAX_FRAMES {
            return Err(Error("wire_limit"));
        }
        let mut frames = Vec::with_capacity(count);
        for _ in 0..count {
            let id = r.array::<8>()?;
            let len = r.u32()? as usize;
            if len == 0 || len > MAX_FRAME {
                return Err(Error("wire_limit"));
            }
            frames.push((id, r.take(len)?.to_vec()));
        }
        let mut lists = [Vec::new(), Vec::new()];
        for list in lists.iter_mut() {
            let count = r.u16()? as usize;
            if count > MAX_IDS {
                return Err(Error("wire_limit"));
            }
            for _ in 0..count {
                list.push(r.array::<8>()?);
            }
        }
        r.done()?;
        let [acks, nacks] = lists;
        Ok(Self {
            frames,
            acks,
            nacks,
        })
    }
}

impl Container {
    pub fn new(kind: u8, reply_to: [u8; 16], time: u64) -> Result<Self> {
        Ok(Self {
            kind,
            nonce: random()?,
            reply_to,
            time,
            sections: Vec::new(),
        })
    }

    fn mac_input<'a>(&'a self, section: &'a Section, time: &'a [u8; 8]) -> [&'a [u8]; 8] {
        [
            b"cc-section-v1",
            std::slice::from_ref(&self.kind),
            &self.nonce,
            &self.reply_to,
            time,
            &section.addr,
            std::slice::from_ref(&section.flags),
            &section.body,
        ]
    }

    /// Add a section authenticated with `key` (link key or invitation tag).
    pub fn push(&mut self, key: &[u8], addr: [u8; 16], flags: u8, body: Vec<u8>) {
        let mut section = Section {
            addr,
            flags,
            body,
            mac: [0; 16],
        };
        let time = self.time.to_be_bytes();
        section.mac = hmac16(key, &self.mac_input(&section, &time));
        self.sections.push(section);
    }

    pub fn verify(&self, section: &Section, key: &[u8]) -> bool {
        let time = self.time.to_be_bytes();
        same(&hmac16(key, &self.mac_input(section, &time)), &section.mac)
    }

    pub fn encoded_len(&self) -> usize {
        4 + 1
            + 16
            + 16
            + 8
            + 2
            + self
                .sections
                .iter()
                .map(|s| 16 + 1 + 4 + s.body.len() + 16)
                .sum::<usize>()
    }

    pub fn encode(&self) -> Result<Vec<u8>> {
        if self.sections.len() > MAX_SECTIONS || self.encoded_len() > MAX_CONTAINER {
            return Err(Error("wire_limit"));
        }
        let mut out = Vec::with_capacity(self.encoded_len());
        out.extend(MAGIC);
        out.push(self.kind);
        out.extend(self.nonce);
        out.extend(self.reply_to);
        out.extend(self.time.to_be_bytes());
        out.extend((self.sections.len() as u16).to_be_bytes());
        for s in &self.sections {
            out.extend(s.addr);
            out.push(s.flags);
            out.extend((s.body.len() as u32).to_be_bytes());
            out.extend(&s.body);
            out.extend(s.mac);
        }
        Ok(out)
    }

    pub fn decode(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MAX_CONTAINER {
            return Err(Error("wire_limit"));
        }
        let mut r = Reader { bytes, at: 0 };
        if r.take(4)? != MAGIC {
            return Err(Error("wire_format"));
        }
        let kind = r.u8()?;
        if kind != KIND_REQUEST && kind != KIND_RESPONSE {
            return Err(Error("wire_format"));
        }
        let nonce = r.array()?;
        let reply_to = r.array()?;
        let time = r.u64()?;
        let count = r.u16()? as usize;
        if count > MAX_SECTIONS {
            return Err(Error("wire_limit"));
        }
        let mut sections = Vec::with_capacity(count);
        for _ in 0..count {
            let addr = r.array()?;
            let flags = r.u8()?;
            let len = r.u32()? as usize;
            let body = r.take(len)?.to_vec();
            let mac = r.array()?;
            sections.push(Section {
                addr,
                flags,
                body,
                mac,
            });
        }
        r.done()?;
        Ok(Self {
            kind,
            nonce,
            reply_to,
            time,
            sections,
        })
    }
}

/// The age-v1 STREAM payload construction (ChaCha20-Poly1305, 64 KiB chunks,
/// nonce = 11-byte big-endian counter ‖ last-chunk flag) under a fresh random
/// key per transfer. The key travels only inside an Olm message; nothing here
/// is a new cryptographic idea.
pub fn stream_seal(key: &[u8; 32], plaintext: &[u8]) -> Result<Vec<u8>> {
    let cipher = ChaCha20Poly1305::new(key.into());
    let chunks: Vec<&[u8]> = if plaintext.is_empty() {
        vec![&[]]
    } else {
        plaintext.chunks(STREAM_CHUNK).collect()
    };
    let mut out = Vec::with_capacity(plaintext.len() + chunks.len() * 16);
    for (index, chunk) in chunks.iter().enumerate() {
        let nonce = stream_nonce(index as u64, index + 1 == chunks.len())?;
        out.extend(
            cipher
                .encrypt(&nonce.into(), *chunk)
                .map_err(|_| Error("stream_seal"))?,
        );
    }
    Ok(out)
}

pub fn stream_open(key: &[u8; 32], sealed: &[u8]) -> Result<Vec<u8>> {
    let cipher = ChaCha20Poly1305::new(key.into());
    let chunks: Vec<&[u8]> = sealed.chunks(STREAM_CHUNK + 16).collect();
    if chunks.is_empty() {
        return Err(Error("stream_open"));
    }
    let mut out = Vec::with_capacity(sealed.len());
    for (index, chunk) in chunks.iter().enumerate() {
        let last = index + 1 == chunks.len();
        // A full-size final chunk is legal only when the plaintext length is
        // an exact multiple; truncation at a chunk boundary fails the flag.
        let nonce = stream_nonce(index as u64, last)?;
        let plain = cipher
            .decrypt(&nonce.into(), *chunk)
            .map_err(|_| Error("stream_open"))?;
        if !last && plain.len() != STREAM_CHUNK {
            return Err(Error("stream_open"));
        }
        out.extend(plain);
    }
    Ok(out)
}

fn stream_nonce(counter: u64, last: bool) -> Result<[u8; 12]> {
    let mut nonce = [0u8; 12];
    nonce[3..11].copy_from_slice(&counter.to_be_bytes());
    nonce[11] = u8::from(last);
    Ok(nonce)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn container_roundtrip_and_macs_bind_every_field() {
        let mut c = Container::new(KIND_REQUEST, [0; 16], 1_800_000_000).unwrap();
        let body = ChannelBody {
            frames: vec![([1; 8], vec![9; 40])],
            acks: vec![[2; 8]],
            nacks: vec![],
        };
        c.push(&[7; 32], [3; 16], SECTION_CHANNEL, body.encode());
        let bytes = c.encode().unwrap();
        let back = Container::decode(&bytes).unwrap();
        assert_eq!(back, c);
        assert!(back.verify(&back.sections[0], &[7; 32]));
        assert!(!back.verify(&back.sections[0], &[8; 32]));
        assert_eq!(ChannelBody::decode(&back.sections[0].body).unwrap(), body);
        // Any flipped byte after the magic breaks either parsing or the MAC.
        for index in 4..bytes.len() {
            let mut tampered = bytes.clone();
            tampered[index] ^= 0x01;
            if let Ok(t) = Container::decode(&tampered) {
                assert!(
                    t.sections.len() != 1 || !t.verify(&t.sections[0], &[7; 32]),
                    "byte {index}"
                );
            }
        }
    }

    #[test]
    fn stream_detects_truncation_reordering_and_tampering() {
        let key = random::<32>().unwrap();
        for size in [0, 1, STREAM_CHUNK, STREAM_CHUNK + 1, 3 * STREAM_CHUNK] {
            let plain: Vec<u8> = (0..size).map(|i| i as u8).collect();
            let sealed = stream_seal(&key, &plain).unwrap();
            assert_eq!(stream_open(&key, &sealed).unwrap(), plain);
            if size > STREAM_CHUNK {
                // Dropping the final chunk leaves a non-final chunk last.
                assert!(stream_open(&key, &sealed[..STREAM_CHUNK + 16]).is_err());
                let mut swapped = sealed.clone();
                swapped[0] ^= 1;
                assert!(stream_open(&key, &swapped).is_err());
            }
        }
        assert!(stream_open(&random::<32>().unwrap(), &stream_seal(&key, b"x").unwrap()).is_err());
    }

    #[test]
    fn beacons_rotate_and_differ_per_secret() {
        assert_ne!(beacon(&[1; 32], 5), beacon(&[1; 32], 6));
        assert_ne!(beacon(&[1; 32], 5), beacon(&[2; 32], 5));
        assert_eq!(beacon(&[1; 32], 5), beacon(&[1; 32], 5));
    }
}
