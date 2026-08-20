# rustables

[![Crates.io](https://img.shields.io/crates/v/rustables.svg)](https://crates.io/crates/rustables)
[![Documentation](https://img.shields.io/docsrs/rustables/latest)](https://docs.rs/rustables)
[![CI pipeline status](https://img.shields.io/gitlab/pipeline-status/rustwall%2Frustables?branch=master)](https://gitlab.com/rustwall/rustables/-/pipelines)
[![License](https://img.shields.io/gitlab/license/rustwall%2Frustables)](https://gitlab.com/rustwall/rustables/-/blob/master/LICENSE)


Safe abstraction for userspace access to the in-kernel nf_tables subsystem.
Can be used to create and remove tables, chains, sets and rules from the nftables
firewall, the successor to iptables.

This library is a fork of the [`nftnl-rs`] crate. Let us thank here the original project
team for their great work without which this library would probably not exist today.

This library currently has quite rough edges and does not make adding and removing netfilter
entries super easy and elegant. That is partly because the library needs more work, but also
partly because nftables is super low level and extremely customizable, making it hard, and
probably wrong, to try and create a too simple/limited wrapper. See examples for inspiration.

Understanding how to use the netlink subsystem and implementing this crate has mostly been done by
reading the source code for the [`nftables`] userspace program and its corresponding kernel code,
as well as attaching debuggers to the `nft` binary.
Since the implementation is mostly based on trial and error, there might of course be
a number of places where the forged netlink messages are used in an invalid or not intended way.
Contributions are welcome!

## Licensing

License: GNU GPLv3

Original work licensed by Amagicom AB under MIT/Apache-2.0.

[`nftnl-rs`]: https://github.com/mullvad/nftnl-rs
[`nftables`]: https://netfilter.org/projects/nftables/

