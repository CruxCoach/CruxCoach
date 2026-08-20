//! A module with all the nftables expressions that can be added to [`Rule`]s to build up how
//! they match against packets.
//!
//! [`Rule`]: struct.Rule.html

use std::fmt::Debug;

use rustables_macros::nfnetlink_struct;

use crate::error::DecodeError;
use crate::nlmsg::{NfNetlinkAttribute, NfNetlinkDeserializable};
use crate::parser_impls::NfNetlinkList;
use crate::sys::{self, NFTA_EXPR_DATA, NFTA_EXPR_NAME};

mod bitwise;
pub use self::bitwise::*;

mod cmp;
pub use self::cmp::*;

mod counter;
pub use self::counter::*;

pub mod ct;
pub use self::ct::*;

mod immediate;
pub use self::immediate::*;

mod log;
pub use self::log::*;

mod lookup;
pub use self::lookup::*;

mod masquerade;
pub use self::masquerade::*;

mod meta;
pub use self::meta::*;

mod nat;
pub use self::nat::*;

mod payload;
pub use self::payload::*;

mod reject;
pub use self::reject::{IcmpCode, Reject, RejectType};

mod register;
pub use self::register::Register;

mod verdict;
pub use self::verdict::*;

pub trait Expression {
    fn get_name() -> &'static str;
}

#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct(nested = true, derive_decoder = false)]
pub struct RawExpression {
    #[field(NFTA_EXPR_NAME)]
    name: String,
    #[field(NFTA_EXPR_DATA)]
    data: ExpressionVariant,
}

impl<T> From<T> for RawExpression
where
    T: Expression,
    ExpressionVariant: From<T>,
{
    fn from(val: T) -> Self {
        RawExpression::default()
            .with_name(T::get_name())
            .with_data(ExpressionVariant::from(val))
    }
}

macro_rules! create_expr_variant {
    ($enum:ident $(, [$name:ident, $type:ty])+) => {
        #[derive(Debug, Clone, PartialEq, Eq)]
        pub enum $enum {
            $(
                $name($type),
            )+
        }

        impl $crate::nlmsg::NfNetlinkAttribute for $enum {
            fn is_nested(&self) -> bool {
                true
            }

            fn get_size(&self) -> usize {
                match self {
                    $(
                        $enum::$name(val) => val.get_size(),
                    )+
                }
            }

            fn write_payload(&self, addr: &mut [u8]) {
                match self {
                    $(
                        $enum::$name(val) => val.write_payload(addr),
                    )+
                }
            }
        }

        $(
            impl From<$type> for $enum {
                fn from(val: $type) -> Self {
                    $enum::$name(val)
                }
            }
        )+

        impl $crate::nlmsg::AttributeDecoder for RawExpression {
            fn decode_attribute(
                &mut self,
                attr_type: u16,
                buf: &[u8],
            ) -> Result<(), $crate::error::DecodeError> {
                debug!("Decoding attribute {} in an expression", attr_type);
                match attr_type {
                    x if x == sys::NFTA_EXPR_NAME => {
                        debug!("Calling {}::deserialize()", std::any::type_name::<String>());
                        let (val, remaining) = String::deserialize(buf)?;
                        if remaining.len() != 0 {
                            return Err($crate::error::DecodeError::InvalidDataSize);
                        }
                        self.name = Some(val);
                        Ok(())
                    },
                    x if x == sys::NFTA_EXPR_DATA => {
                        // we can assume we have already the name parsed, as that's how we identify the
                        // type of expression
                        let name = self.name.as_ref()
                            .ok_or($crate::error::DecodeError::MissingExpressionName)?;
                        match name {
                            $(
                                x if x == <$type>::get_name() => {
                                    debug!("Calling {}::deserialize()", std::any::type_name::<$type>());
                                    let (res, remaining) =  <$type>::deserialize(buf)?;
                                    if remaining.len() != 0 {
                                            return Err($crate::error::DecodeError::InvalidDataSize);
                                    }
                                    self.data = Some(ExpressionVariant::from(res));
                                    Ok(())
                                },
                            )+
                            name => {
                                info!("Unrecognized expression '{}', generating an ExpressionRaw", name);
                                self.data = Some(ExpressionVariant::ExpressionRaw(ExpressionRaw::deserialize(buf)?.0));
                                Ok(())
                            }
                        }
                    },
                    _ => Err(DecodeError::UnsupportedAttributeType(attr_type)),
                }
            }
        }
    };
}

create_expr_variant!(
    ExpressionVariant,
    [Bitwise, Bitwise],
    [Cmp, Cmp],
    [Conntrack, Conntrack],
    [Counter, Counter],
    [ExpressionRaw, ExpressionRaw],
    [Immediate, Immediate],
    [Log, Log],
    [Lookup, Lookup],
    [Masquerade, Masquerade],
    [Meta, Meta],
    [Nat, Nat],
    [Payload, Payload],
    [Reject, Reject]
);

pub type ExpressionList = NfNetlinkList<RawExpression>;

// default type for expressions that we do not handle yet
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExpressionRaw(Vec<u8>);

impl NfNetlinkAttribute for ExpressionRaw {
    fn get_size(&self) -> usize {
        self.0.get_size()
    }

    fn write_payload(&self, addr: &mut [u8]) {
        self.0.write_payload(addr);
    }
}

impl NfNetlinkDeserializable for ExpressionRaw {
    fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), DecodeError> {
        Ok((ExpressionRaw(buf.to_vec()), &[]))
    }
}

// Because we loose the name of the expression when parsing, this is the only expression
// where deserializing a message and then reserializing it is invalid
impl Expression for ExpressionRaw {
    fn get_name() -> &'static str {
        "unknown_expression"
    }
}
