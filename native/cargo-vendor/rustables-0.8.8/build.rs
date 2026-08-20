//! This build script leverages `bindgen` to generate rust sys files.

use regex::{Captures, Regex};
use std::borrow::Cow;
use std::env;
use std::fs::File;
use std::io::Write;
use std::path::PathBuf;

const SYS_HEADER_FILE: &str = "include/wrapper.h";

fn main() {
    generate_sys();
}

/// `bindgen`erate a rust sys file from the C kernel headers of the nf_tables capabilities.
fn generate_sys() {
    // Tell cargo to invalidate the built crate whenever the headers change.
    println!("cargo:rerun-if-changed={}", SYS_HEADER_FILE);

    let bindings = bindgen::Builder::default()
        .header(SYS_HEADER_FILE)
        .generate_comments(false)
        .prepend_enum_name(false)
        .layout_tests(false)
        .derive_partialeq(true)
        .translate_enum_integer_types(true)
        // Tell cargo to invalidate the built crate whenever any of the
        // included header files changed.
        .parse_callbacks(Box::new(bindgen::CargoCallbacks::new()))
        // Finish the builder and generate the bindings.
        .generate()
        // Unwrap the Result and panic on failure.
        .expect("Error: unable to generate bindings needed for tests.");

    // Add newlines because in alpine bindgen doesn't add them after statements.
    let s = bindings.to_string().replace(" ; ", ";\n");
    let s = reformat_units(&s);

    // Write the bindings to the rust header file.
    let out_path = PathBuf::from(env::var("OUT_DIR").unwrap()).join("sys.rs");
    File::create(out_path)
        .expect("Error: could not create rust header file.")
        .write_all(s.as_bytes())
        .expect("Error: could not write to the rust header file.");
}

/// Recast nft_*_attributes from u32 to u16 in header string `header`.
fn reformat_units<'a>(header: &'a str) -> Cow<'a, str> {
    let re = Regex::new(r"(pub type nft[a-zA-Z_]*_attributes) = u32;").unwrap();
    re.replace_all(header, |captures: &Captures| {
        format!("{} = u16;", &captures[1])
    })
}
