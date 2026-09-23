//! Fixed error codes. Nothing from a peer, relay or provider crosses FFI/logs.

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Error(pub &'static str);

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.0)
    }
}

impl std::error::Error for Error {}

impl From<cgka_traits::storage::StorageError> for Error {
    fn from(_: cgka_traits::storage::StorageError) -> Self {
        Self("storage")
    }
}

impl From<rusqlite::Error> for Error {
    fn from(_: rusqlite::Error) -> Self {
        Self("storage")
    }
}

pub fn checked<T, E>(result: std::result::Result<T, E>, code: &'static str) -> Result<T> {
    result.map_err(|_| Error(code))
}
