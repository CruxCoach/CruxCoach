use rustables_macros::nfnetlink_struct;

use super::Expression;
use crate::{
    error::BuilderError,
    sys::{NFTA_LOG_GROUP, NFTA_LOG_PREFIX},
};

#[derive(Clone, PartialEq, Eq, Default, Debug)]
#[nfnetlink_struct]
/// A Log expression will log all packets that match the rule.
pub struct Log {
    #[field(NFTA_LOG_GROUP)]
    group: u16,
    #[field(NFTA_LOG_PREFIX)]
    prefix: String,
}

impl Log {
    pub fn new(group: Option<u16>, prefix: Option<impl Into<String>>) -> Result<Log, BuilderError> {
        let mut res = Log::default();
        if let Some(group) = group {
            res.set_group(group);
        }
        if let Some(prefix) = prefix {
            let prefix = prefix.into();

            if prefix.len() > 127 {
                return Err(BuilderError::TooLongLogPrefix);
            }
            res.set_prefix(prefix);
        }
        Ok(res)
    }
}

impl Expression for Log {
    fn get_name() -> &'static str {
        "log"
    }
}
