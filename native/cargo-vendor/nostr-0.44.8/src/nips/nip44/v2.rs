// Copyright (c) 2022-2023 Yuki Kishimoto
// Copyright (c) 2023-2025 Rust Nostr Developers
// Distributed under the MIT software license

//! NIP44 (v2)
//!
//! <https://github.com/nostr-protocol/nips/blob/master/44.md>

use alloc::string::{FromUtf8Error, String};
use alloc::vec;
use alloc::vec::Vec;
use core::ops::{Deref, Range};
use core::{fmt, iter};

use chacha20::cipher::{KeyIvInit, StreamCipher};
use chacha20::ChaCha20;
use hashes::hmac::{Hmac, HmacEngine};
use hashes::sha256::Hash as Sha256Hash;
use hashes::{FromSliceError, Hash, HashEngine};
#[cfg(feature = "std")]
use secp256k1::rand::rngs::OsRng;
use secp256k1::rand::RngCore;

use super::Error;
use crate::util::{self, hkdf};
use crate::{PublicKey, SecretKey};

const VERSION_SIZE: usize = 1;
const NONCE_SIZE: usize = 32;
const LENGTH_PREFIX_SIZE: usize = 2;
const MIN_CIPHERTEXT_SIZE: usize = LENGTH_PREFIX_SIZE + 32;
const HMAC_SIZE: usize = 32;
const MIN_PAYLOAD_SIZE: usize = VERSION_SIZE + NONCE_SIZE + MIN_CIPHERTEXT_SIZE + HMAC_SIZE;
// This codec currently supports the original two-byte length prefix only.
const MAX_SUPPORTED_PLAINTEXT_SIZE: usize = 65_536 - 128;
const MAX_CIPHERTEXT_SIZE: usize = LENGTH_PREFIX_SIZE + calc_padding(MAX_SUPPORTED_PLAINTEXT_SIZE);
pub(super) const MAX_PAYLOAD_SIZE: usize =
    VERSION_SIZE + NONCE_SIZE + MAX_CIPHERTEXT_SIZE + HMAC_SIZE;
pub(super) const MAX_ENCODED_PAYLOAD_SIZE: usize =
    (MAX_PAYLOAD_SIZE / 3 + if MAX_PAYLOAD_SIZE % 3 == 0 { 0 } else { 1 }) * 4;

const MESSAGE_KEYS_SIZE: usize = 76;
const MESSAGES_KEYS_ENCRYPTION_SIZE: usize = 32;
const MESSAGES_KEYS_NONCE_SIZE: usize = 12;
const MESSAGES_KEYS_ENCRYPTION_RANGE: Range<usize> = 0..MESSAGES_KEYS_ENCRYPTION_SIZE;
const MESSAGES_KEYS_NONCE_RANGE: Range<usize> =
    MESSAGES_KEYS_ENCRYPTION_SIZE..MESSAGES_KEYS_ENCRYPTION_SIZE + MESSAGES_KEYS_NONCE_SIZE;
const MESSAGES_KEYS_AUTH_RANGE: Range<usize> =
    MESSAGES_KEYS_ENCRYPTION_SIZE + MESSAGES_KEYS_NONCE_SIZE..MESSAGE_KEYS_SIZE;

/// Error
#[derive(Debug, PartialEq, Eq)]
pub enum ErrorV2 {
    /// From slice error
    FromSlice(FromSliceError),
    /// Error while encoding to UTF-8
    Utf8Encode(FromUtf8Error),
    /// HKDF Length
    HkdfLength(usize),
    /// Payload is too short
    PayloadTooShort,
    /// Try from slice
    TryFromSlice,
    /// Message is empty
    MessageEmpty,
    /// Message is too long
    MessageTooLong,
    /// Invalid HMAC
    InvalidHmac,
    /// Invalid padding
    InvalidPadding,
}

#[cfg(feature = "std")]
impl std::error::Error for ErrorV2 {}

impl fmt::Display for ErrorV2 {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::FromSlice(e) => e.fmt(f),
            Self::Utf8Encode(e) => write!(f, "error while encoding to UTF-8: {e}"),
            Self::HkdfLength(size) => write!(f, "invalid Length for HKDF: {size}"),
            Self::PayloadTooShort => f.write_str("payload size is too short"),
            Self::TryFromSlice => f.write_str("could not convert slice to array"),
            Self::MessageEmpty => f.write_str("message empty"),
            Self::MessageTooLong => f.write_str("message too long"),
            Self::InvalidHmac => f.write_str("invalid HMAC"),
            Self::InvalidPadding => f.write_str("invalid padding"),
        }
    }
}

impl From<FromSliceError> for ErrorV2 {
    fn from(e: FromSliceError) -> Self {
        Self::FromSlice(e)
    }
}

impl From<FromUtf8Error> for ErrorV2 {
    fn from(e: FromUtf8Error) -> Self {
        Self::Utf8Encode(e)
    }
}

struct MessageKeys([u8; MESSAGE_KEYS_SIZE]);

impl MessageKeys {
    #[inline]
    pub fn from_slice(slice: &[u8]) -> Result<Self, Error> {
        Ok(Self(slice.try_into().map_err(|_| ErrorV2::TryFromSlice)?))
    }

    #[inline]
    pub fn encryption(&self) -> &[u8] {
        &self.0[MESSAGES_KEYS_ENCRYPTION_RANGE]
    }

    #[inline]
    pub fn nonce(&self) -> &[u8] {
        &self.0[MESSAGES_KEYS_NONCE_RANGE]
    }

    #[inline]
    pub fn auth(&self) -> &[u8] {
        &self.0[MESSAGES_KEYS_AUTH_RANGE]
    }
}

/// NIP44 v2 Conversation Key
#[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
pub struct ConversationKey(Hmac<Sha256Hash>);

impl fmt::Debug for ConversationKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Conversation key: <sensitive>")
    }
}

impl Deref for ConversationKey {
    type Target = Hmac<Sha256Hash>;

    fn deref(&self) -> &Self::Target {
        &self.0
    }
}

impl ConversationKey {
    /// Construct conversation key from 32-byte array
    #[inline]
    pub fn new(bytes: [u8; 32]) -> Self {
        Self(Hmac::from_byte_array(bytes))
    }

    /// Derive Conversation Key
    #[inline]
    pub fn derive(secret_key: &SecretKey, public_key: &PublicKey) -> Result<Self, Error> {
        let shared_key: [u8; 32] = util::generate_shared_key(secret_key, public_key)?;
        Ok(Self(hkdf::extract(b"nip44-v2", &shared_key)))
    }

    /// Compose Conversation Key from bytes
    #[inline]
    pub fn from_slice(slice: &[u8]) -> Result<Self, Error> {
        Ok(Self(
            Hmac::from_slice(slice).map_err(|e| Error::from(ErrorV2::from(e)))?,
        ))
    }

    /// Get Conversation Key as bytes
    #[inline]
    pub fn as_bytes(&self) -> &[u8] {
        self.deref().as_byte_array()
    }
}

/// Encrypt with NIP44 (v2)
///
/// **The result is NOT encoded in base64!**
#[inline]
#[cfg(feature = "std")]
pub fn encrypt_to_bytes(
    conversation_key: &ConversationKey,
    plaintext: &[u8],
) -> Result<Vec<u8>, Error> {
    encrypt_to_bytes_with_rng(&mut OsRng, conversation_key, plaintext)
}

/// Encrypt with NIP44 (v2) using custom Rng
///
/// **The result is NOT encoded in base64!**
#[inline]
pub fn encrypt_to_bytes_with_rng<R>(
    rng: &mut R,
    conversation_key: &ConversationKey,
    plaintext: &[u8],
) -> Result<Vec<u8>, Error>
where
    R: RngCore,
{
    internal_encrypt_to_bytes_with_rng(rng, conversation_key, plaintext, None)
}

fn internal_encrypt_to_bytes_with_rng<R>(
    rng: &mut R,
    conversation_key: &ConversationKey,
    plaintext: &[u8],
    override_random_nonce: Option<&[u8; 32]>,
) -> Result<Vec<u8>, Error>
where
    R: RngCore,
{
    // Generate nonce
    let nonce: [u8; 32] = match override_random_nonce {
        Some(nonce) => *nonce,
        None => {
            let mut nonce: [u8; 32] = [0; 32];
            rng.fill_bytes(&mut nonce);
            nonce
        }
    };

    // Get Message Keys
    let keys: MessageKeys = get_message_keys(conversation_key, &nonce)?;

    // Pad
    let mut buffer: Vec<u8> = pad(plaintext)?;

    // Compose cipher and encrypt
    let mut cipher = ChaCha20::new(keys.encryption().into(), keys.nonce().into());
    cipher.apply_keystream(&mut buffer);

    // HMAC-SHA256
    let mut engine: HmacEngine<Sha256Hash> = HmacEngine::new(keys.auth());
    engine.input(&nonce);
    engine.input(&buffer);
    let hmac: [u8; 32] = Hmac::from_engine(engine).to_byte_array();

    // Compose payload
    let mut payload: Vec<u8> = vec![2]; // Version
    payload.extend_from_slice(&nonce);
    payload.extend_from_slice(&buffer);
    payload.extend_from_slice(&hmac);

    Ok(payload)
}

/// Decrypt with NIP44 (v2)
///
/// **The payload MUST be already decoded from base64**
pub fn decrypt_to_bytes(
    conversation_key: &ConversationKey,
    payload: &[u8],
) -> Result<Vec<u8>, Error> {
    let len: usize = payload.len();

    if len < MIN_PAYLOAD_SIZE {
        return Err(ErrorV2::PayloadTooShort.into());
    }
    // Reject before HMAC and ciphertext allocation using the largest payload we can emit.
    if len > MAX_PAYLOAD_SIZE {
        return Err(ErrorV2::MessageTooLong.into());
    }

    // Extract nonce, buffer and hmac from payload
    let nonce: &[u8] = payload
        .get(VERSION_SIZE..VERSION_SIZE + NONCE_SIZE)
        .ok_or_else(|| Error::NotFound(String::from("nonce")))?;
    let buffer: &[u8] = payload
        .get(VERSION_SIZE + NONCE_SIZE..len - HMAC_SIZE)
        .ok_or_else(|| Error::NotFound(String::from("buffer")))?;
    let mac: &[u8] = payload
        .get(len - HMAC_SIZE..)
        .ok_or_else(|| Error::NotFound(String::from("hmac")))?;

    // Compose Message Keys
    let keys: MessageKeys = get_message_keys(conversation_key, nonce)?;

    // Check HMAC-SHA256
    let mut engine: HmacEngine<Sha256Hash> = HmacEngine::new(keys.auth());
    engine.input(nonce);
    engine.input(buffer);
    let calculated_mac: [u8; HMAC_SIZE] = Hmac::from_engine(engine).to_byte_array();
    if mac != calculated_mac.as_slice() {
        return Err(ErrorV2::InvalidHmac.into());
    }

    // Compose cipher
    let mut cipher = ChaCha20::new(keys.encryption().into(), keys.nonce().into());
    let mut buffer: Vec<u8> = buffer.to_vec();
    cipher.apply_keystream(&mut buffer);

    let be_bytes: [u8; 2] = buffer
        .get(0..2)
        .ok_or(ErrorV2::InvalidPadding)?
        .try_into()
        .map_err(|_| ErrorV2::InvalidPadding)?;
    let unpadded_len: usize = u16::from_be_bytes(be_bytes) as usize;

    let unpadded: &[u8] = buffer
        .get(2..2 + unpadded_len)
        .ok_or(ErrorV2::InvalidPadding)?;

    if unpadded.is_empty() {
        return Err(ErrorV2::MessageEmpty.into());
    }

    if unpadded.len() != unpadded_len {
        return Err(ErrorV2::InvalidPadding.into());
    }

    if buffer.len() != 2 + calc_padding(unpadded_len) {
        return Err(ErrorV2::InvalidPadding.into());
    }

    Ok(unpadded.to_vec())
}

#[inline]
fn get_message_keys(
    conversation_key: &ConversationKey,
    nonce: &[u8],
) -> Result<MessageKeys, ErrorV2> {
    let expanded_key: Vec<u8> = hkdf::expand(conversation_key.as_bytes(), nonce, MESSAGE_KEYS_SIZE);
    MessageKeys::from_slice(&expanded_key).map_err(|_| ErrorV2::HkdfLength(expanded_key.len()))
}

fn pad(unpadded: &[u8]) -> Result<Vec<u8>, ErrorV2> {
    let len: usize = unpadded.len();

    if len < 1 {
        return Err(ErrorV2::MessageEmpty);
    }

    if len > MAX_SUPPORTED_PLAINTEXT_SIZE {
        return Err(ErrorV2::MessageTooLong);
    }

    let take: usize = calc_padding(len) - len;
    let mut padded: Vec<u8> = Vec::with_capacity(2 + len + take);
    padded.extend_from_slice(&(len as u16).to_be_bytes());
    padded.extend_from_slice(unpadded);
    padded.extend(iter::repeat(0).take(take));
    Ok(padded)
}

#[inline]
const fn calc_padding(len: usize) -> usize {
    if len <= 32 {
        return 32;
    }
    let nextpower: usize = 1 << (log2_round_down(len - 1) + 1);
    let chunk: usize = if nextpower <= 256 { 32 } else { nextpower / 8 };
    chunk * (((len - 1) / chunk) + 1)
}

/// Returns the base 2 logarithm of the number, rounded down.
#[inline]
const fn log2_round_down(x: usize) -> u32 {
    if x == 0 {
        0
    } else {
        // This is equivalent to floor(log2(x))
        (usize::BITS - 1) - x.leading_zeros()
    }
}

#[cfg(test)]
#[cfg(feature = "std")]
mod tests {
    #![allow(dead_code)]

    use core::str::FromStr;

    use base64::engine::{general_purpose, Engine};

    use super::*;
    use crate::nips::nip44;
    use crate::Keys;

    const JSON_VECTORS: &str = include_str!("nip44.vectors.json");

    fn val(c: u8, idx: usize) -> u8 {
        match c {
            b'A'..=b'F' => c - b'A' + 10,
            b'a'..=b'f' => c - b'a' + 10,
            b'0'..=b'9' => c - b'0',
            _ => panic!("Invalid character {} at position {}", c as char, idx),
        }
    }

    pub fn hex_decode<T>(hex: T) -> Vec<u8>
    where
        T: AsRef<[u8]>,
    {
        let hex = hex.as_ref();
        let len = hex.len();

        if len % 2 != 0 {
            panic!("Odd number of digits");
        }

        let mut bytes: Vec<u8> = Vec::with_capacity(len / 2);

        for i in (0..len).step_by(2) {
            let high = val(hex[i], i);
            let low = val(hex[i + 1], i + 1);
            bytes.push((high << 4) | low);
        }

        bytes
    }

    // Check if out manual implementation work in the same way as the std one.
    #[test]
    fn test_log2_round_down() {
        let f = |x: usize| -> u32 {
            let x: f64 = x as f64;
            x.log2().floor() as u32
        };

        assert_eq!(log2_round_down(0), f(0));
        assert_eq!(log2_round_down(1), f(1));
        assert_eq!(log2_round_down(2), f(2));
        assert_eq!(log2_round_down(3), f(3));
        assert_eq!(log2_round_down(4), f(4));
        assert_eq!(log2_round_down(5), f(5));
        assert_eq!(log2_round_down(6), f(6));
        assert_eq!(log2_round_down(7), f(7));
        assert_eq!(log2_round_down(8), f(8));
        assert_eq!(log2_round_down(9), f(9));
        assert_eq!(log2_round_down(10), f(10));
    }

    #[test]
    fn test_valid_get_conversation_key() {
        let json: serde_json::Value = serde_json::from_str(JSON_VECTORS).unwrap();

        for vectorobj in json
            .as_object()
            .unwrap()
            .get("v2")
            .unwrap()
            .as_object()
            .unwrap()
            .get("valid")
            .unwrap()
            .as_object()
            .unwrap()
            .get("get_conversation_key")
            .unwrap()
            .as_array()
            .unwrap()
        {
            let vector = vectorobj.as_object().unwrap();

            let sec1 = {
                let sec1hex = vector.get("sec1").unwrap().as_str().unwrap();
                SecretKey::from_str(sec1hex).unwrap()
            };
            let pub2 = {
                let pub2hex = vector.get("pub2").unwrap().as_str().unwrap();
                PublicKey::from_str(pub2hex).unwrap()
            };
            let conversation_key: [u8; 32] = {
                let ckeyhex = vector.get("conversation_key").unwrap().as_str().unwrap();
                hex_decode(ckeyhex).try_into().unwrap()
            };
            let note = vector.get("note").unwrap().as_str().unwrap();

            let computed_conversation_key = ConversationKey::derive(&sec1, &pub2).unwrap();

            assert_eq!(
                conversation_key,
                computed_conversation_key.to_byte_array(),
                "Conversation key failure on {}",
                note
            );
        }
    }

    #[test]
    fn test_valid_calc_padded_len() {
        let json: serde_json::Value = serde_json::from_str(JSON_VECTORS).unwrap();

        for elem in json
            .as_object()
            .unwrap()
            .get("v2")
            .unwrap()
            .as_object()
            .unwrap()
            .get("valid")
            .unwrap()
            .as_object()
            .unwrap()
            .get("calc_padded_len")
            .unwrap()
            .as_array()
            .unwrap()
        {
            let len = elem[0].as_number().unwrap().as_u64().unwrap() as usize;
            let pad = elem[1].as_number().unwrap().as_u64().unwrap() as usize;
            assert_eq!(calc_padding(len), pad);
        }
    }

    #[test]
    fn test_valid_encrypt_decrypt() {
        let json: serde_json::Value = serde_json::from_str(JSON_VECTORS).unwrap();

        for (i, vectorobj) in json
            .as_object()
            .unwrap()
            .get("v2")
            .unwrap()
            .as_object()
            .unwrap()
            .get("valid")
            .unwrap()
            .as_object()
            .unwrap()
            .get("encrypt_decrypt")
            .unwrap()
            .as_array()
            .unwrap()
            .iter()
            .enumerate()
        {
            let vector = vectorobj.as_object().unwrap();

            let sec1 = {
                let sec1hex = vector.get("sec1").unwrap().as_str().unwrap();
                SecretKey::from_str(sec1hex).unwrap()
            };
            let pub2 = {
                let sec2hex = vector.get("sec2").unwrap().as_str().unwrap();
                let secret_key = SecretKey::from_str(sec2hex).unwrap();
                Keys::new(secret_key).public_key()
            };
            let conversation_key: ConversationKey = {
                let ckeyhex = vector.get("conversation_key").unwrap().as_str().unwrap();
                ConversationKey::from_slice(&hex_decode(ckeyhex)).unwrap()
            };
            let nonce: [u8; 32] = {
                let noncehex = vector.get("nonce").unwrap().as_str().unwrap();
                hex_decode(noncehex).try_into().unwrap()
            };
            let plaintext = vector.get("plaintext").unwrap().as_str().unwrap();
            let ciphertext = vector.get("ciphertext").unwrap().as_str().unwrap();

            // Test conversation key
            let computed_conversation_key = ConversationKey::derive(&sec1, &pub2).unwrap();
            assert_eq!(
                computed_conversation_key, conversation_key,
                "Conversation key failure on ValidSec #{}",
                i
            );

            // Test encryption with an overridden nonce
            let computed_ciphertext = internal_encrypt_to_bytes_with_rng(
                &mut OsRng,
                &conversation_key,
                plaintext.as_bytes(),
                Some(&nonce),
            )
            .unwrap();
            let computed_ciphertext = general_purpose::STANDARD.encode(computed_ciphertext);
            assert_eq!(
                computed_ciphertext, ciphertext,
                "Encryption does not match on ValidSec #{}",
                i
            );

            // Test decryption
            let computed_plaintext = nip44::decrypt(&sec1, &pub2, ciphertext).unwrap();
            assert_eq!(
                computed_plaintext, plaintext,
                "Decryption does not match on ValidSec #{}",
                i
            );
        }
    }

    #[test]
    fn test_invalid_get_conversation_key() {
        let json: serde_json::Value = serde_json::from_str(JSON_VECTORS).unwrap();

        for vectorobj in json
            .as_object()
            .unwrap()
            .get("v2")
            .unwrap()
            .as_object()
            .unwrap()
            .get("invalid")
            .unwrap()
            .as_object()
            .unwrap()
            .get("get_conversation_key")
            .unwrap()
            .as_array()
            .unwrap()
        {
            let vector = vectorobj.as_object().unwrap();

            let sec1result = {
                let sec1hex = vector.get("sec1").unwrap().as_str().unwrap();
                SecretKey::from_str(sec1hex)
            };
            let pub2result = {
                let pub2hex = vector.get("pub2").unwrap().as_str().unwrap();
                PublicKey::from_str(pub2hex).unwrap().xonly()
            };
            let note = vector.get("note").unwrap().as_str().unwrap();

            assert!(
                sec1result.is_err() || pub2result.is_err(),
                "One of the keys should have failed: {}",
                note
            );
        }
    }

    #[test]
    fn test_invalid_decrypt() {
        let json: serde_json::Value = serde_json::from_str(JSON_VECTORS).unwrap();

        let known_errors = [
            Error::V2(ErrorV2::InvalidHmac),
            Error::V2(ErrorV2::InvalidHmac),
            Error::V2(ErrorV2::InvalidPadding),
            Error::V2(ErrorV2::MessageEmpty),
            Error::V2(ErrorV2::InvalidPadding),
            Error::V2(ErrorV2::InvalidPadding),
        ];

        for (i, vectorobj) in json
            .as_object()
            .unwrap()
            .get("v2")
            .unwrap()
            .as_object()
            .unwrap()
            .get("invalid")
            .unwrap()
            .as_object()
            .unwrap()
            .get("decrypt")
            .unwrap()
            .as_array()
            .unwrap()
            .iter()
            .enumerate()
        {
            let vector = vectorobj.as_object().unwrap();
            let conversation_key: ConversationKey = {
                let ckeyhex = vector.get("conversation_key").unwrap().as_str().unwrap();
                ConversationKey::from_slice(&hex_decode(ckeyhex)).unwrap()
            };
            let ciphertext = vector.get("ciphertext").unwrap().as_str().unwrap();
            let note = vector.get("note").unwrap().as_str().unwrap();

            let payload: Vec<u8> = general_purpose::STANDARD.decode(ciphertext).unwrap();
            let result = decrypt_to_bytes(&conversation_key, &payload);
            assert!(result.is_err(), "Should not have decrypted: {}", note);

            let err = result.unwrap_err();
            assert_eq!(
                err, known_errors[i],
                "Unexpected error in invalid decrypt #{}",
                i
            );
        }
    }

    fn make_authenticated_short_v2_payload(
        conversation_key: &ConversationKey,
        ciphertext_len: usize,
    ) -> Vec<u8> {
        assert!(
            ciphertext_len <= 1,
            "this helper is intended for the 65/66-byte regression cases"
        );

        let nonce: [u8; 32] = [0x42; 32];

        let keys: MessageKeys = get_message_keys(conversation_key, &nonce).unwrap();

        // Zero or one encrypted byte. ChaCha20 preserves the buffer length,
        // therefore the decrypted buffer will also contain zero or one byte.
        let ciphertext: Vec<u8> = vec![0u8; ciphertext_len];

        // Produce a valid MAC, as a legitimate conversation participant can do.
        let mut engine: HmacEngine<Sha256Hash> = HmacEngine::new(keys.auth());
        engine.input(&nonce);
        engine.input(&ciphertext);
        let mac: [u8; 32] = Hmac::from_engine(engine).to_byte_array();

        let mut payload: Vec<u8> = Vec::with_capacity(65 + ciphertext_len);
        payload.push(2); // NIP-44 v2
        payload.extend_from_slice(&nonce);
        payload.extend_from_slice(&ciphertext);
        payload.extend_from_slice(&mac);

        payload
    }

    #[test]
    fn test_short_authenticated_payloads_return_error_instead_of_panicking() {
        // Alice is the sender; Bob is the recipient.
        let alice_sk =
            SecretKey::from_str("5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a")
                .unwrap();
        let alice_keys = Keys::new(alice_sk);
        let alice_pk = alice_keys.public_key();

        let bob_sk =
            SecretKey::from_str("4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d")
                .unwrap();
        let bob_keys = Keys::new(bob_sk);

        let conversation_key = ConversationKey::derive(bob_keys.secret_key(), &alice_pk).unwrap();

        for ciphertext_len in [0, 1] {
            let payload = make_authenticated_short_v2_payload(&conversation_key, ciphertext_len);

            // 1 version + 32 nonce + N ciphertext + 32 MAC.
            assert_eq!(payload.len(), 65 + ciphertext_len);

            let encoded = general_purpose::STANDARD.encode(&payload);

            let err = nip44::decrypt_to_bytes(bob_keys.secret_key(), &alice_pk, encoded.as_bytes())
                .unwrap_err();

            assert_eq!(err, Error::V2(ErrorV2::PayloadTooShort));
        }
    }

    #[test]
    fn test_oversized_binary_payload_is_rejected() {
        let conversation_key = ConversationKey::new([0x42; 32]);
        let payload = vec![0u8; MAX_PAYLOAD_SIZE + 1];

        let err = decrypt_to_bytes(&conversation_key, &payload).unwrap_err();
        assert_eq!(err, Error::V2(ErrorV2::MessageTooLong));
    }
}
