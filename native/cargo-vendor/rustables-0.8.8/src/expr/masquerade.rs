use rustables_macros::nfnetlink_struct;

use super::Expression;

/// Sets the source IP to that of the output interface.
#[derive(Default, Debug, PartialEq, Eq)]
#[nfnetlink_struct(nested = true)]
pub struct Masquerade;

impl Clone for Masquerade {
    fn clone(&self) -> Self {
        Masquerade {}
    }
}

impl Expression for Masquerade {
    fn get_name() -> &'static str {
        "masq"
    }
}
