//! Mailbox events: the Olm channel through relays that keep a frame only
//! until it is delivered.
//!
//! One event carries one sealed mailbox container (`p2p::KIND_MAILBOX`) for
//! one relationship and direction:
//!
//! ```text
//! event   := kind 2437, tags ["z", address] ["expiration", t] [nonce]
//! address := HMAC(mailbox key, direction ‖ day)[..8], 16 hex
//! signer  := HKDF(mailbox key, "sign" ‖ direction ‖ day)
//! content := base64(nonce:12 ‖ ChaCha20-Poly1305(seal key, len:u32 ‖ container ‖ zeros))
//! ```
//!
//! The mailbox key comes from the relationship's link key, so only the two
//! friends can find, sign, open or (later) delete a day's events; a relay sees
//! a pseudonym that changes daily, a key that changes with it, a size class
//! and a time to the minute, never an npub, a count of frames or their sizes.
use crate::error::{Error, Result};
use crate::p2p;
use chacha20poly1305::aead::Aead;
use chacha20poly1305::{ChaCha20Poly1305, KeyInit};
use nostr::base64::Engine as _;
use nostr::base64::engine::general_purpose::STANDARD;
use nostr::{Event, EventBuilder, JsonUtil, Keys, Kind, SecretKey, Tag, TagKind, Timestamp};
use sha2_v11::Sha256;

/// A regular event kind no NIP uses (checked against the NIPs list @ d47a2ec, 2026-10-22).
pub const KIND: u16 = 2437;
/// The one indexed tag: a single letter, so relays can filter by it.
pub const ADDRESS_TAG: char = 'z';
/// How long a relay may keep a frame (14 days; the app's return
/// status follows the same value).
pub const MAX_AGE_S: u64 = 14 * 86_400;
/// Clocks differ: expiries and creation times get this much slack.
pub const CLOCK_GRACE_S: u64 = 15 * 60;
/// `created_at` is cut to the minute and moved back by up to this much.
pub const CREATED_SPREAD_S: u64 = 120;
/// Raw (pre-base64) content sizes; the largest keeps the event JSON within
/// the 65,536 bytes standard relays accept.
pub const SIZE_CLASSES: [usize; 7] = [1024, 2048, 4096, 8192, 16_384, 32_768, 48_128];
const NONCE: usize = 12;
const TAG: usize = 16;
const LENGTH: usize = 4;
/// The largest container one event can carry.
pub const MAX_CONTAINER: usize = 48_128 - NONCE - TAG - LENGTH;
pub const DAY_S: u64 = 86_400;
/// The largest event JSON this module produces (relays accept 65,536 bytes).
pub const MAX_EVENT_JSON: usize = 65_536;

/// Which way an event travels within a relationship.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Direction {
    FromInviter = 0,
    FromAcceptor = 1,
}

impl Direction {
    /// The direction of what a device sends, by its role in the relationship.
    pub fn outgoing(inviter: bool) -> Self {
        if inviter {
            Self::FromInviter
        } else {
            Self::FromAcceptor
        }
    }
    pub fn incoming(inviter: bool) -> Self {
        Self::outgoing(!inviter)
    }
}

pub fn day(now_s: u64) -> u64 {
    now_s / DAY_S
}

/// The relationship's mailbox key, separate from the link key that
/// authenticates direct sections.
pub fn mailbox_key(link: &[u8; 32]) -> [u8; 32] {
    expand(link, b"cc-mailbox-v1", b"cruxcoach share mailbox")
}

fn expand(key: &[u8], salt: &[u8], info: &[u8]) -> [u8; 32] {
    let hkdf = hkdf::Hkdf::<Sha256>::new(Some(salt), key);
    let mut okm = [0u8; 32];
    hkdf.expand(info, &mut okm)
        .expect("32 bytes is a valid HKDF output length");
    okm
}

fn daily(key: &[u8; 32], label: &[u8], direction: Direction, day: u64) -> [u8; 32] {
    let info = [label, &[direction as u8], &day.to_be_bytes()].concat();
    expand(key, b"cc-mailbox-day-v1", &info)
}

/// The day's address of one direction: 64 bits as 16 hex.
pub fn address(key: &[u8; 32], direction: Direction, day: u64) -> String {
    let mac = p2p::hmac(
        key,
        &[b"cc-mbx-addr-v1", &[direction as u8], &day.to_be_bytes()],
    );
    hex::encode(&mac[..8])
}

/// Which mailboxes one direction of a relationship uses: both friends derive
/// the same choice from it, nobody else can. Stable across days.
pub fn pool_seed(key: &[u8; 32], direction: Direction) -> String {
    let mac = p2p::hmac(key, &[b"cc-mbx-pool-v1", &[direction as u8]]);
    hex::encode(&mac[..8])
}

/// An event waiting for mailboxes is signed again after this long: a strict
/// mailbox refuses events created more than an hour ago.
pub const RESIGN_AFTER_S: u64 = 50 * 60;

/// The day's signing key of one direction. Both friends can derive it, so the
/// receiver may delete what it fetched; nobody else can sign under it.
pub fn signer(key: &[u8; 32], direction: Direction, day: u64) -> Keys {
    let mut seed = daily(key, b"sign", direction, day);
    loop {
        // A 32-byte value is a valid secp256k1 key with overwhelming probability.
        if let Ok(secret) = SecretKey::from_slice(&seed) {
            return Keys::new(secret);
        }
        seed = p2p::sha256(&seed);
    }
}

fn cipher(key: &[u8; 32], direction: Direction, day: u64) -> ChaCha20Poly1305 {
    ChaCha20Poly1305::new((&daily(key, b"seal", direction, day)).into())
}

/// The smallest size class that holds `container`.
pub fn class_for(container: usize) -> Result<usize> {
    SIZE_CLASSES
        .iter()
        .copied()
        .find(|class| container + NONCE + TAG + LENGTH <= *class)
        .ok_or(Error("mailbox_too_large"))
}

/// Pad and seal a container to exactly one size class.
pub fn seal(key: &[u8; 32], direction: Direction, day: u64, container: &[u8]) -> Result<Vec<u8>> {
    let class = class_for(container.len())?;
    let mut padded = Vec::with_capacity(class - NONCE - TAG);
    padded.extend((container.len() as u32).to_be_bytes());
    padded.extend(container);
    padded.resize(class - NONCE - TAG, 0);
    let nonce = p2p::random::<NONCE>()?;
    let sealed = cipher(key, direction, day)
        .encrypt(
            &nonce.into(),
            chacha20poly1305::aead::Payload {
                msg: &padded,
                aad: b"cc-mbx-v1",
            },
        )
        .map_err(|_| Error("mailbox_seal"))?;
    let mut out = nonce.to_vec();
    out.extend(sealed);
    debug_assert_eq!(out.len(), class);
    Ok(out)
}

pub fn open(key: &[u8; 32], direction: Direction, day: u64, sealed: &[u8]) -> Result<Vec<u8>> {
    if !SIZE_CLASSES.contains(&sealed.len()) {
        return Err(Error("mailbox_size"));
    }
    let (nonce, body) = sealed.split_at(NONCE);
    let nonce: [u8; NONCE] = nonce.try_into().expect("split at NONCE");
    let padded = cipher(key, direction, day)
        .decrypt(
            &nonce.into(),
            chacha20poly1305::aead::Payload {
                msg: body,
                aad: b"cc-mbx-v1",
            },
        )
        .map_err(|_| Error("mailbox_open"))?;
    let length = u32::from_be_bytes(padded[..LENGTH].try_into().expect("length prefix")) as usize;
    let end = LENGTH.checked_add(length).ok_or(Error("mailbox_format"))?;
    if end > padded.len() || padded[end..].iter().any(|b| *b != 0) {
        return Err(Error("mailbox_format"));
    }
    Ok(padded[LENGTH..end].to_vec())
}

fn minute(now_s: u64) -> u64 {
    now_s - now_s % 60
}

/// A signed mailbox event for one direction of one relationship. `pow` adds a
/// NIP-13 proof of work of that many bits (0: none).
pub fn event(
    key: &[u8; 32],
    direction: Direction,
    now_s: u64,
    container: &[u8],
    pow: u8,
) -> Result<Event> {
    let today = day(now_s);
    let content = STANDARD.encode(seal(key, direction, today, container)?);
    // Whole minutes only: 0, 1 or 2 back.
    let spread = u64::from(p2p::random::<1>()?[0]) % (CREATED_SPREAD_S / 60 + 1) * 60;
    // Never before the day the address and key belong to.
    let created = minute(now_s).saturating_sub(spread).max(today * DAY_S);
    let tags = [
        Tag::custom(
            TagKind::single_letter(nostr::Alphabet::Z, false),
            [address(key, direction, today)],
        ),
        Tag::expiration(Timestamp::from(now_s + MAX_AGE_S)),
    ];
    let mut builder = EventBuilder::new(Kind::Custom(KIND), content)
        .tags(tags)
        .custom_created_at(Timestamp::from(created));
    if pow > 0 {
        builder = builder.pow(pow);
    }
    builder
        .sign_with_keys(&signer(key, direction, today))
        .map_err(|_| Error("mailbox_sign"))
}

/// At most this many events one deletion names, so relays take it as one small event.
pub const MAX_DELETE_IDS: usize = 50;

/// A NIP-09 deletion of `ids`, all published under the same day's key of one
/// direction and so signed with it. It expires with the latest of them.
pub fn deletion(
    key: &[u8; 32],
    direction: Direction,
    day: u64,
    now_s: u64,
    ids: &[nostr::EventId],
    expiration: u64,
) -> Result<Event> {
    if ids.is_empty() || ids.len() > MAX_DELETE_IDS {
        return Err(Error("mailbox_delete"));
    }
    let spread = u64::from(p2p::random::<1>()?[0]) % (CREATED_SPREAD_S / 60 + 1) * 60;
    let created = minute(now_s).saturating_sub(spread);
    let mut tags: Vec<Tag> = ids.iter().map(|id| Tag::event(*id)).collect();
    tags.push(Tag::custom(
        TagKind::single_letter(nostr::Alphabet::K, false),
        [KIND.to_string()],
    ));
    tags.push(Tag::expiration(Timestamp::from(
        expiration.max(now_s + CLOCK_GRACE_S),
    )));
    EventBuilder::new(Kind::EventDeletion, "")
        .tags(tags)
        .custom_created_at(Timestamp::from(created))
        .sign_with_keys(&signer(key, direction, day))
        .map_err(|_| Error("mailbox_sign"))
}

/// What a checked event says about itself, before it is opened.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Envelope {
    pub event: Event,
    pub address: String,
    pub expiration: u64,
}

/// Parse and check an event from a relay: kind, exact tags, size, time window
/// and signature. Whether its address belongs to a relationship is the
/// caller's question; the signer must then match ([`matches`]).
pub fn envelope(json: &str, now_s: u64) -> Result<Envelope> {
    if json.len() > 70_000 {
        return Err(Error("mailbox_size"));
    }
    let event = Event::from_json(json).map_err(|_| Error("mailbox_format"))?;
    if event.kind != Kind::Custom(KIND) {
        return Err(Error("mailbox_kind"));
    }
    let mut address = None;
    let mut expiration = None;
    for tag in event.tags.iter() {
        let values = tag.as_slice();
        match values.first().map(String::as_str) {
            Some("z") if values.len() == 2 && address.is_none() => {
                address = Some(values[1].clone())
            }
            Some("expiration") if values.len() == 2 && expiration.is_none() => {
                expiration = Some(
                    values[1]
                        .parse::<u64>()
                        .map_err(|_| Error("mailbox_format"))?,
                )
            }
            Some("nonce") => {}
            _ => return Err(Error("mailbox_format")),
        }
    }
    let address = address
        .filter(|a| {
            a.len() == 16
                && a.bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        })
        .ok_or(Error("mailbox_format"))?;
    let expiration = expiration.ok_or(Error("mailbox_format"))?;
    let created = event.created_at.as_secs();
    // Expired (also where a relay ignores NIP-40) or not from a plausible time.
    if expiration + CLOCK_GRACE_S <= now_s
        || expiration > created + MAX_AGE_S + CREATED_SPREAD_S + 3_600
        || created > now_s + CLOCK_GRACE_S
        || created + MAX_AGE_S + CLOCK_GRACE_S < now_s
    {
        return Err(Error("mailbox_expired"));
    }
    let raw = STANDARD
        .decode(event.content.as_bytes())
        .map_err(|_| Error("mailbox_format"))?;
    if !SIZE_CLASSES.contains(&raw.len()) {
        return Err(Error("mailbox_size"));
    }
    event.verify().map_err(|_| Error("mailbox_signature"))?;
    Ok(Envelope {
        event,
        address,
        expiration,
    })
}

/// Whether `envelope` was signed with the day's key of this direction.
pub fn matches(envelope: &Envelope, key: &[u8; 32], direction: Direction, day: u64) -> bool {
    envelope.address == address(key, direction, day)
        && envelope.event.pubkey == signer(key, direction, day).public_key()
}

/// The container inside a matched envelope.
pub fn content(
    envelope: &Envelope,
    key: &[u8; 32],
    direction: Direction,
    day: u64,
) -> Result<Vec<u8>> {
    let raw = STANDARD
        .decode(envelope.event.content.as_bytes())
        .map_err(|_| Error("mailbox_format"))?;
    open(key, direction, day, &raw)
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: u64 = 1_793_000_000;

    fn key() -> [u8; 32] {
        mailbox_key(&[7; 32])
    }

    #[test]
    fn addresses_and_keys_change_every_day_and_direction_and_belong_to_one_relationship() {
        let k = key();
        let today = day(NOW);
        let a = address(&k, Direction::FromInviter, today);
        assert_eq!(a.len(), 16);
        assert_ne!(a, address(&k, Direction::FromInviter, today + 1));
        assert_ne!(a, address(&k, Direction::FromAcceptor, today));
        assert_ne!(
            a,
            address(&mailbox_key(&[8; 32]), Direction::FromInviter, today)
        );
        let s = signer(&k, Direction::FromInviter, today).public_key();
        assert_ne!(
            s,
            signer(&k, Direction::FromInviter, today + 1).public_key()
        );
        assert_ne!(s, signer(&k, Direction::FromAcceptor, today).public_key());
        // Midnight UTC is the boundary.
        assert_eq!(day(today * DAY_S + DAY_S - 1), today);
        assert_eq!(day(today * DAY_S + DAY_S), today + 1);
        assert_ne!(mailbox_key(&[7; 32]), [7; 32]);
    }

    #[test]
    fn sealed_content_has_exactly_one_size_class_and_opens_only_with_its_key() {
        let k = key();
        for size in [0, 1, 900, 1_000, 5_000, 40_000, MAX_CONTAINER] {
            let container: Vec<u8> = (0..size).map(|i| (i % 251) as u8).collect();
            let sealed = seal(&k, Direction::FromInviter, 9, &container).unwrap();
            assert!(
                SIZE_CLASSES.contains(&sealed.len()),
                "{size} -> {}",
                sealed.len()
            );
            assert_eq!(
                open(&k, Direction::FromInviter, 9, &sealed).unwrap(),
                container
            );
            assert!(open(&k, Direction::FromAcceptor, 9, &sealed).is_err());
            assert!(open(&k, Direction::FromInviter, 10, &sealed).is_err());
            assert!(open(&mailbox_key(&[8; 32]), Direction::FromInviter, 9, &sealed).is_err());
        }
        assert_eq!(
            seal(&k, Direction::FromInviter, 9, &[]).unwrap().len(),
            1024
        );
        assert!(
            seal(&k, Direction::FromInviter, 9, &vec![0; MAX_CONTAINER + 1]).is_err(),
            "split before, never larger"
        );
    }

    #[test]
    fn an_event_shows_no_identity_no_structure_and_stays_within_relay_limits() {
        let k = key();
        let container = vec![0x5a; MAX_CONTAINER];
        let event = event(&k, Direction::FromAcceptor, NOW, &container, 0).unwrap();
        let json = event.as_json();
        assert!(json.len() <= 65_536, "{} bytes", json.len());
        assert_eq!(event.kind, Kind::Custom(KIND));
        assert_eq!(event.tags.len(), 2, "only the address and the expiry");
        let created = event.created_at.as_secs();
        assert_eq!(created % 60, 0, "minutes, never seconds");
        assert!(created <= NOW && NOW - created <= CREATED_SPREAD_S + 60);
        let checked = envelope(&json, NOW).unwrap();
        assert!(matches(&checked, &k, Direction::FromAcceptor, day(NOW)));
        assert!(!matches(&checked, &k, Direction::FromInviter, day(NOW)));
        assert_eq!(
            content(&checked, &k, Direction::FromAcceptor, day(NOW)).unwrap(),
            container
        );
        // The padding and the frame bytes are not visible: no run of the plain bytes in the content.
        assert!(!event.content.contains("WlpaWlpa"));
    }

    #[test]
    fn relays_get_nothing_through_that_is_expired_foreign_resized_or_signed_by_someone_else() {
        let k = key();
        let good = event(&k, Direction::FromInviter, NOW, b"frames", 0).unwrap();
        assert!(envelope(&good.as_json(), NOW).is_ok());
        assert_eq!(
            envelope(&good.as_json(), NOW + MAX_AGE_S + CLOCK_GRACE_S).unwrap_err(),
            Error("mailbox_expired")
        );
        // Signed by a stranger with the right address: the envelope is valid, the signer does not match.
        let stranger = EventBuilder::new(Kind::Custom(KIND), good.content.clone())
            .tags(good.tags.clone().to_vec())
            .custom_created_at(good.created_at)
            .sign_with_keys(&Keys::generate())
            .unwrap();
        let foreign = envelope(&stranger.as_json(), NOW).unwrap();
        assert!(!matches(&foreign, &k, Direction::FromInviter, day(NOW)));
        // Other kinds, extra tags and odd sizes are refused outright.
        let other = EventBuilder::new(Kind::Custom(1059), good.content.clone())
            .tags(good.tags.clone().to_vec())
            .sign_with_keys(&Keys::generate())
            .unwrap();
        assert_eq!(
            envelope(&other.as_json(), NOW).unwrap_err(),
            Error("mailbox_kind")
        );
        let tagged = EventBuilder::new(Kind::Custom(KIND), good.content.clone())
            .tags(
                good.tags
                    .clone()
                    .to_vec()
                    .into_iter()
                    .chain([Tag::custom(TagKind::Custom("client".into()), ["x"])]),
            )
            .custom_created_at(good.created_at)
            .sign_with_keys(&signer(&k, Direction::FromInviter, day(NOW)))
            .unwrap();
        assert_eq!(
            envelope(&tagged.as_json(), NOW).unwrap_err(),
            Error("mailbox_format")
        );
        let resized = EventBuilder::new(Kind::Custom(KIND), STANDARD.encode([0u8; 1000]))
            .tags(good.tags.clone().to_vec())
            .custom_created_at(good.created_at)
            .sign_with_keys(&signer(&k, Direction::FromInviter, day(NOW)))
            .unwrap();
        assert_eq!(
            envelope(&resized.as_json(), NOW).unwrap_err(),
            Error("mailbox_size")
        );
        // A longer expiry than the mailbox keeps anything is refused.
        let long = EventBuilder::new(Kind::Custom(KIND), good.content.clone())
            .tags([
                good.tags.clone().to_vec()[0].clone(),
                Tag::expiration(Timestamp::from(NOW + 30 * DAY_S)),
            ])
            .custom_created_at(good.created_at)
            .sign_with_keys(&signer(&k, Direction::FromInviter, day(NOW)))
            .unwrap();
        assert_eq!(
            envelope(&long.as_json(), NOW).unwrap_err(),
            Error("mailbox_expired")
        );
        // A tampered signature fails.
        let tampered = good.as_json().replace(&good.content[..20], &"A".repeat(20));
        assert!(envelope(&tampered, NOW).is_err());
    }

    #[test]
    fn proof_of_work_keeps_the_minute_and_the_tags() {
        let k = key();
        let event = event(&k, Direction::FromInviter, NOW, b"frames", 8).unwrap();
        assert!(event.id.check_pow(8));
        assert_eq!(event.created_at.as_secs() % 60, 0);
        let checked = envelope(&event.as_json(), NOW).unwrap();
        assert!(matches(&checked, &k, Direction::FromInviter, day(NOW)));
    }
}
