#![allow(rustdoc::broken_intra_doc_links)]

use std::fs::File;
use std::io::Read;
use std::path::PathBuf;

use proc_macro::TokenStream;
use proc_macro2::Span;
use proc_macro2_diagnostics::{Diagnostic, Level, SpanDiagnosticExt};
use quote::{quote, quote_spanned};

use syn::parse::Parser;
use syn::punctuated::Punctuated;
use syn::spanned::Spanned;
use syn::{
    Attribute, Expr, ExprCast, ExprLit, Ident, Item, ItemEnum, ItemStruct, Lit, Meta, Path, Token,
    Type, TypePath, Visibility, parse,
};

use once_cell::sync::OnceCell;

struct GlobalState {
    declared_identifiers: Vec<String>,
}

static STATE: OnceCell<GlobalState> = OnceCell::new();

fn get_state() -> &'static GlobalState {
    STATE.get_or_init(|| {
        let sys_file = {
            // Load the header file and extract the constants defined inside.
            // This is what determines whether optional attributes (or enum variants)
            // will be supported or not in the resulting binary.
            let out_path = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("sys.rs");
            let mut sys_file = String::new();
            File::open(out_path)
                .expect("Error: could not open the output header file")
                .read_to_string(&mut sys_file)
                .expect("Could not read the header file");
            syn::parse_file(&sys_file).expect("Could not parse the header file")
        };

        let mut declared_identifiers = Vec::new();
        for item in sys_file.items {
            if let Item::Const(v) = item {
                declared_identifiers.push(v.ident.to_string());
            }
        }

        GlobalState {
            declared_identifiers,
        }
    })
}

struct Field<'a> {
    name: &'a Ident,
    ty: &'a Type,
    args: FieldArgs,
    netlink_type: Path,
    vis: &'a Visibility,
    attrs: Vec<&'a Attribute>,
}

#[derive(Default)]
struct FieldArgs {
    netlink_type: Option<Path>,
    override_function_name: Option<String>,
    optional: bool,
}

fn parse_field_args(input: proc_macro2::TokenStream) -> Result<FieldArgs, Diagnostic> {
    let mut args = FieldArgs::default();
    let parser = Punctuated::<Meta, Token![,]>::parse_terminated;
    let attribute_args = parser
        .parse2(input)
        .map_err(|e| Diagnostic::new(Level::Error, e.to_string()))?;
    for arg in attribute_args.iter() {
        match arg {
            Meta::Path(path) => {
                if args.netlink_type.is_none() {
                    args.netlink_type = Some(path.clone());
                } else {
                    return Err(arg
                        .span()
                        .error("Only a single netlink value can exist for a given field"));
                }
            }
            Meta::NameValue(namevalue) => {
                let key = namevalue
                    .path
                    .get_ident()
                    .expect("the macro parameter is not an ident?")
                    .to_string();
                match key.as_str() {
                    "name_in_functions" => {
                        if let Expr::Lit(ExprLit {
                            lit: Lit::Str(val), ..
                        }) = &namevalue.value
                        {
                            args.override_function_name = Some(val.value());
                        } else {
                            return Err(namevalue.value.span().error("Expected a string literal"));
                        }
                    }
                    "optional" => {
                        if let Expr::Lit(ExprLit {
                            lit: Lit::Bool(boolean),
                            ..
                        }) = &namevalue.value
                        {
                            args.optional = boolean.value;
                        } else {
                            return Err(namevalue.value.span().error("Expected a boolean"));
                        }
                    }
                    _ => return Err(arg.span().error("Unsupported macro parameter")),
                }
            }
            _ => return Err(arg.span().error("Unrecognized argument")),
        }
    }
    Ok(args)
}

struct StructArgs {
    nested: bool,
    derive_decoder: bool,
    derive_deserialize: bool,
}

impl Default for StructArgs {
    fn default() -> Self {
        Self {
            nested: false,
            derive_decoder: true,
            derive_deserialize: true,
        }
    }
}

fn parse_struct_args(input: TokenStream) -> Result<StructArgs, Diagnostic> {
    let mut args = StructArgs::default();
    let parser = Punctuated::<Meta, Token![,]>::parse_terminated;
    let attribute_args = parser
        .parse(input.clone())
        .map_err(|e| Diagnostic::new(Level::Error, e.to_string()))?;
    for arg in attribute_args.iter() {
        if let Meta::NameValue(namevalue) = arg {
            let key = namevalue
                .path
                .get_ident()
                .expect("the macro parameter is not an ident?")
                .to_string();
            if let Expr::Lit(ExprLit {
                lit: Lit::Bool(boolean),
                ..
            }) = &namevalue.value
            {
                match key.as_str() {
                    "derive_decoder" => {
                        args.derive_decoder = boolean.value;
                    }
                    "nested" => {
                        args.nested = boolean.value;
                    }
                    "derive_deserialize" => {
                        args.derive_deserialize = boolean.value;
                    }
                    _ => return Err(arg.span().error("Unsupported macro parameter")),
                }
            } else {
                return Err(namevalue.value.span().error("Expected a boolean"));
            }
        } else {
            return Err(arg.span().error("Unrecognized argument"));
        }
    }
    Ok(args)
}

fn nfnetlink_struct_inner(
    attrs: TokenStream,
    item: TokenStream,
) -> Result<TokenStream, Diagnostic> {
    let ast: ItemStruct = parse(item).unwrap();
    let name = ast.ident;

    let args = parse_struct_args(attrs)?;

    let state = get_state();

    let mut fields = Vec::with_capacity(ast.fields.len());
    let mut identical_fields = Vec::new();

    'out: for field in ast.fields.iter() {
        for attr in field.attrs.iter() {
            if let Some(id) = attr.path().get_ident()
                && id == "field"
            {
                let field_args = match &attr.meta {
                    Meta::List(l) => l,
                    _ => {
                        return Err(attr.span().error("Invalid attributes"));
                    }
                };

                let field_args = match parse_field_args(field_args.tokens.clone()) {
                    Ok(x) => x,
                    Err(_) => {
                        return Err(attr.span().error("Could not parse the field attributes"));
                    }
                };
                if let Some(netlink_type) = field_args.netlink_type.clone() {
                    // optional fields are not generated when the kernel version you have on
                    // the system does not support that field
                    if field_args.optional {
                        let netlink_type_ident = netlink_type
                            .segments
                            .last()
                            .expect("empty path?")
                            .ident
                            .to_string();
                        if !state.declared_identifiers.contains(&netlink_type_ident) {
                            // reject the optional identifier
                            continue 'out;
                        }
                    }

                    fields.push(Field {
                        name: field.ident.as_ref().expect("Should be a names struct"),
                        ty: &field.ty,
                        args: field_args,
                        netlink_type,
                        vis: &field.vis,
                        // drop the "field" attribute
                        attrs: field
                            .attrs
                            .iter()
                            .filter(|x| x.path().get_ident() != attr.path().get_ident())
                            .collect(),
                    });
                } else {
                    return Err(attr.span().error("Missing Netlink Type in field"));
                }
                continue 'out;
            }
        }
        identical_fields.push(field);
    }

    let getters_and_setters = fields.iter().map(|field| {
        let field_name = field.name;
        // use the name override if any
        let field_str = field_name.to_string();
        let field_str = field
            .args
            .override_function_name
            .as_deref()
            .unwrap_or(field_str.as_str());
        let field_type = field.ty;

        let getter_name = format!("get_{}", field_str);
        let getter_name = Ident::new(&getter_name, field.name.span());

        let muttable_getter_name = format!("get_mut_{}", field_str);
        let muttable_getter_name = Ident::new(&muttable_getter_name, field.name.span());

        let setter_name = format!("set_{}", field_str);
        let setter_name = Ident::new(&setter_name, field.name.span());

        let in_place_edit_name = format!("with_{}", field_str);
        let in_place_edit_name = Ident::new(&in_place_edit_name, field.name.span());
        quote!(
            #[allow(dead_code)]
            impl #name {
            pub fn #getter_name(&self) -> Option<&#field_type> {
                self.#field_name.as_ref()
            }

            pub fn #muttable_getter_name(&mut self) -> Option<&mut #field_type> {
                self.#field_name.as_mut()
            }

            pub fn #setter_name(&mut self, val: impl Into<#field_type>) {
                self.#field_name = Some(val.into());
            }

            pub fn #in_place_edit_name(mut self, val: impl Into<#field_type>) -> Self {
                self.#field_name = Some(val.into());
                self
            }
        })
    });

    let decoder = if args.derive_decoder {
        let match_entries = fields.iter().map(|field| {
            let field_name = field.name;
            let field_type = field.ty;
            let netlink_value = &field.netlink_type;
            quote!(
                x if x == #netlink_value => {
                    debug!("Calling {}::deserialize()", std::any::type_name::<#field_type>());
                    let (val, remaining) = <#field_type>::deserialize(buf)?;
                    if remaining.len() != 0 {
                        return Err(crate::error::DecodeError::InvalidDataSize);
                    }
                    self.#field_name = Some(val);
                    Ok(())
                }
            )
        });
        quote!(
            impl crate::nlmsg::AttributeDecoder for #name {
                #[allow(dead_code)]
                fn decode_attribute(&mut self, attr_type: u16, buf: &[u8]) -> Result<(), crate::error::DecodeError> {
                    use crate::nlmsg::NfNetlinkDeserializable;
                    debug!("Decoding attribute {} in type {}", attr_type, std::any::type_name::<#name>());
                    match attr_type {
                        #(#match_entries),*
                        _ => Err(crate::error::DecodeError::UnsupportedAttributeType(attr_type)),
                    }
                }
            }
        )
    } else {
        proc_macro2::TokenStream::new()
    };

    let nfnetlinkattribute_impl = {
        let size_entries = fields.iter().map(|field| {
            let field_name = field.name;
            quote!(
                if let Some(val) = &self.#field_name {
                    // Attribute header + attribute value
                    size += crate::nlmsg::pad_netlink_object::<crate::sys::nlattr>()
                        + crate::nlmsg::pad_netlink_object_with_variable_size(val.get_size());
                }
            )
        });
        let write_entries = fields.iter().map(|field| {
            let field_name = field.name;
            let field_str = field_name.to_string();
            let netlink_value = &field.netlink_type;
            quote!(
                if let Some(val) = &self.#field_name {
                    debug!("writing attribute {} - {:?}", #field_str, val);

                    crate::parser::write_attribute(#netlink_value, val, addr);

                    #[allow(unused)]
                    {
                        let size = crate::nlmsg::pad_netlink_object::<crate::sys::nlattr>()
                            + crate::nlmsg::pad_netlink_object_with_variable_size(val.get_size());
                        addr = &mut addr[size..];
                    }
                }
            )
        });
        let nested = args.nested;
        quote!(
            impl crate::nlmsg::NfNetlinkAttribute for #name {
                fn is_nested(&self) -> bool {
                    #nested
                }

                fn get_size(&self) -> usize {
                    use crate::nlmsg::NfNetlinkAttribute;

                    let mut size = 0;
                    #(#size_entries) *
                    size
                }

                fn write_payload(&self, mut addr: &mut [u8]) {
                    use crate::nlmsg::NfNetlinkAttribute;

                    #(#write_entries) *
                }
            }
        )
    };

    let vis = &ast.vis;
    let attrs = ast.attrs;
    let new_fields = fields.iter().map(|field| {
        let name = field.name;
        let ty = field.ty;
        let attrs = &field.attrs;
        let vis = &field.vis;
        quote_spanned!(name.span() => #(#attrs) * #vis #name: Option<#ty>, )
    });
    let nfnetlinkdeserialize_impl = if args.derive_deserialize {
        quote!(
            impl crate::nlmsg::NfNetlinkDeserializable for #name {
                fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), crate::error::DecodeError> {
                    Ok((crate::parser::read_attributes(buf)?, &[]))
                }
            }
        )
    } else {
        proc_macro2::TokenStream::new()
    };
    let res = quote! {
        #(#attrs) * #vis struct #name {
            #(#new_fields)*
            #(#identical_fields),*
        }

        #(#getters_and_setters) *

        #decoder

        #nfnetlinkattribute_impl

        #nfnetlinkdeserialize_impl
    };

    Ok(res.into())
}

/// `nfnetlink_struct` is a macro wrapping structures that describe nftables objects.
/// It allows serializing and deserializing these objects to the corresponding nfnetlink
/// attributes.
///
/// It automatically generates getter and setter functions for each netlink properties.
///
/// # Parameters
/// The macro have multiple parameters:
/// - `nested` (defaults to `false`): the structure is nested (in the netlink sense)
///   inside its parent structure. This is the case of most structures outside
///   of the main nftables objects (batches, sets, rules, chains and tables), which are
///   the outermost structures, and as such cannot be nested.
/// - `derive_decoder` (defaults to `true`): derive a [`rustables::nlmsg::AttributeDecoder`]
///   implementation for the structure
/// - `derive_deserialize` (defaults to `true`): derive a [`rustables::nlmsg::NfNetlinkDeserializable`]
///   implementation for the structure
///
/// # Example use
/// ```ignore
/// #[nfnetlink_struct(derive_deserialize = false)]
/// #[derive(PartialEq, Eq, Default, Debug)]
/// pub struct Chain {
///     family: ProtocolFamily,
///     #[field(NFTA_CHAIN_TABLE)]
///     table: String,
///     #[field(NFTA_CHAIN_TYPE, name_in_functions = "type")]
///     chain_type: ChainType,
///     #[field(optional = true, crate::sys::NFTA_CHAIN_USERDATA)]
///     userdata: Vec<u8>,
///     ...
/// }
/// ```
///
/// # Type of fields
/// This contrived example show the two possible type of fields:
/// - A field that is not converted to a netlink attribute (`family`) because it is not
///   annotated in `#[field]` attribute.
///   When deserialized, this field will take the value it is given in the Default implementation
///   of the struct.
/// - A field that is annotated with the `#[field]` attribute.
///   That attribute takes parameters (there are none here), and the netlink attribute type.
///   When annotated with that attribute, the macro will generate `get_<name>`, `set_<name>` and
///   `with_<name>` methods to manipulate the attribute (e.g. `get_table`, `set_table` and
///   `with_table`).
///   It will also replace the field type (here `String`) with an Option (`Option<String>`)
///   so the struct may represent objects where that attribute is not set.
///
/// # `#[field]` parameters
/// The `#[field]` attribute can be parametrized through two options:
/// - `optional` (defaults to `false`): if the netlink attribute type (here `NFTA_CHAIN_USERDATA`)
///   does not exist, do not generate methods and ignore this attribute if encountered
///   while deserializing a nftables object.
///   This is useful for attributes added recently to the kernel, which may not be supported on
///   older kernels.
///   Support for an attribute is detected according to the existence of that attribute in the kernel
///   headers.
/// - `name_in_functions` (not defined by default): overwrite the `<name`> in the name of the methods
///   `get_<name>`, `set_<name>` and `with_<name>`.
///   Here, this means that even though the field is called `chain_type`, users can query it with
///   the method `get_type` instead of `get_chain_type`.
#[proc_macro_attribute]
pub fn nfnetlink_struct(attrs: TokenStream, item: TokenStream) -> TokenStream {
    match nfnetlink_struct_inner(attrs, item) {
        Ok(tokens) => tokens,
        Err(diag) => diag.emit_as_item_tokens().into(),
    }
}

struct Variant<'a> {
    inner: &'a syn::Variant,
    name: &'a Ident,
    value: &'a Path,
}

#[derive(Default)]
struct EnumArgs {
    nested: bool,
    ty: Option<Path>,
}

fn parse_enum_args(input: TokenStream) -> Result<EnumArgs, Diagnostic> {
    let mut args = EnumArgs::default();
    let parser = Punctuated::<Meta, Token![,]>::parse_terminated;
    let attribute_args = parser
        .parse(input)
        .map_err(|e| Diagnostic::new(Level::Error, e.to_string()))?;
    for arg in attribute_args.iter() {
        match arg {
            Meta::Path(path) => {
                if args.ty.is_none() {
                    args.ty = Some(path.clone());
                } else {
                    return Err(arg
                        .span()
                        .error("A value can only have a single representation"));
                }
            }
            Meta::NameValue(namevalue) => {
                let key = namevalue
                    .path
                    .get_ident()
                    .expect("the macro parameter is not an ident?")
                    .to_string();
                match key.as_str() {
                    "nested" => {
                        if let Expr::Lit(ExprLit {
                            lit: Lit::Bool(boolean),
                            ..
                        }) = &namevalue.value
                        {
                            args.nested = boolean.value;
                        } else {
                            return Err(namevalue.value.span().error("Expected a boolean"));
                        }
                    }
                    _ => return Err(arg.span().error("Unsupported macro parameter")),
                }
            }
            _ => return Err(arg.span().error("Unrecognized argument")),
        }
    }
    Ok(args)
}

fn nfnetlink_enum_inner(attrs: TokenStream, item: TokenStream) -> Result<TokenStream, Diagnostic> {
    let ast: ItemEnum = parse(item).unwrap();
    let name = ast.ident;

    let args = match parse_enum_args(attrs) {
        Ok(x) => x,
        Err(_) => return Err(Span::call_site().error("Could not parse the macro arguments")),
    };

    if args.ty.is_none() {
        return Err(Span::call_site().error("The target type representation is unspecified"));
    }

    let mut variants = Vec::with_capacity(ast.variants.len());

    for variant in ast.variants.iter() {
        if variant.discriminant.is_none() {
            return Err(variant.ident.span().error("Missing value"));
        }
        let discriminant = variant.discriminant.as_ref().unwrap();
        if let syn::Expr::Path(path) = &discriminant.1 {
            variants.push(Variant {
                inner: variant,
                name: &variant.ident,
                value: &path.path,
            });
        } else {
            return Err(discriminant.1.span().error("Expected a path"));
        }
    }

    let repr_type = args.ty.unwrap();
    let match_entries = variants.iter().map(|variant| {
        let variant_name = variant.name;
        let variant_value = &variant.value;
        quote!( x if x == (#variant_value as #repr_type) => Ok(Self::#variant_name), )
    });
    #[allow(clippy::to_string_in_format_args)]
    let unknown_type_ident = Ident::new(&format!("Unknown{}", name.to_string()), name.span());
    let tryfrom_impl = quote!(
        impl ::core::convert::TryFrom<#repr_type> for #name {
            type Error = crate::error::DecodeError;

            #[allow(clippy::unnecessary_cast)]
            fn try_from(val: #repr_type) -> Result<Self, Self::Error> {
                    match val {
                        #(#match_entries) *
                        value => Err(crate::error::DecodeError::#unknown_type_ident(value))
                    }
            }
        }
    );
    let nfnetlinkdeserialize_impl = quote!(
        impl crate::nlmsg::NfNetlinkDeserializable for #name {
            fn deserialize(buf: &[u8]) -> Result<(Self, &[u8]), crate::error::DecodeError> {
                let (v, remaining_data) = #repr_type::deserialize(buf)?;
                <#name>::try_from(v).map(|x| (x, remaining_data))
            }
        }
    );
    let vis = &ast.vis;
    let attrs = ast.attrs;
    let original_variants = variants.into_iter().map(|x| {
        let mut inner = x.inner.clone();
        let discriminant = inner.discriminant.as_mut().unwrap();
        let cur_value = discriminant.1.clone();
        let cast_value = Expr::Cast(ExprCast {
            attrs: vec![],
            expr: Box::new(cur_value),
            as_token: Token![as](name.span()),
            ty: Box::new(Type::Path(TypePath {
                qself: None,
                path: repr_type.clone(),
            })),
        });
        discriminant.1 = cast_value;
        inner
    });
    let res = quote! {
        #[repr(#repr_type)]
        #[allow(clippy::unnecessary_cast)]
        #(#attrs) * #vis enum #name {
            #(#original_variants),*
        }

        impl crate::nlmsg::NfNetlinkAttribute for #name {
            fn get_size(&self) -> usize {
                (*self as #repr_type).get_size()
            }

            fn write_payload(&self, addr: &mut [u8]) {
                (*self as #repr_type).write_payload(addr);
            }
        }

        #tryfrom_impl

        #nfnetlinkdeserialize_impl
    };

    Ok(res.into())
}

#[proc_macro_attribute]
pub fn nfnetlink_enum(attrs: TokenStream, item: TokenStream) -> TokenStream {
    match nfnetlink_enum_inner(attrs, item) {
        Ok(tokens) => tokens,
        Err(diag) => diag.emit_as_item_tokens().into(),
    }
}
