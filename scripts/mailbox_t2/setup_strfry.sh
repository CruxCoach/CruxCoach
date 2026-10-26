#!/usr/bin/env bash
# T2 environment without root: strfry 4cd3cf6 for 127.0.0.1 only, built from
# source with its build dependencies unpacked into a private sysroot.
# Usage: setup_strfry.sh <empty folder outside the repository> (~1 GB, ~10 min).
set -euo pipefail
T2="$(mkdir -p "${1:?folder}" && cd "$1" && pwd)"
cd "$T2"
mkdir -p debs sysroot src
cd debs
apt-get download liblmdb-dev libflatbuffers-dev flatbuffers-compiler flatbuffers-compiler-dev libflatbuffers2 \
  libsecp256k1-dev libsecp256k1-1 libzstd-dev libssl-dev \
  libyaml-perl libtemplate-perl libregexp-grammars-perl libappconfig-perl 2>&1 | tail -3 || true
for d in *.deb; do dpkg -x "$d" ../sysroot; done
cd "$T2/sysroot/usr/lib/x86_64-linux-gnu"
for lib in libssl.so libcrypto.so libzstd.so liblmdb.so; do
  if [ -L "$lib" ] && [ ! -e "$lib" ]; then
    target=$(ls /usr/lib/x86_64-linux-gnu/${lib}.* 2>/dev/null | sort | tail -1)
    [ -n "$target" ] && ln -sf "$target" "$lib"
  fi
done
cd "$T2/src"
[ -d strfry ] || git clone -q https://github.com/hoytech/strfry
cd strfry
git checkout -q 4cd3cf6
git rev-parse --short HEAD > "$T2/strfry.rev"
git submodule update -q --init --depth 1 golpe external/negentropy
export PERL5LIB="$T2/sysroot/usr/share/perl5:$T2/sysroot/usr/lib/x86_64-linux-gnu/perl5/5.38"
export CPATH="$T2/sysroot/usr/include:$T2/sysroot/usr/include/x86_64-linux-gnu"
export LIBRARY_PATH="$T2/sysroot/usr/lib/x86_64-linux-gnu"
export PATH="$T2/sysroot/usr/bin:$PATH"
make setup-golpe
nice -n 10 make -j3
ls -la strfry
echo "strfry built at rev $(cat "$T2/strfry.rev")"
