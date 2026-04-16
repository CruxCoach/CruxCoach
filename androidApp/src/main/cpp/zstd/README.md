# Vendored zstd

This directory contains a vendored subset of [Facebook/Meta's zstd](https://github.com/facebook/zstd)
compression library. Only the **decompression** sources are included — CruxCoach
uses zstd to decompress board manifests fetched via Blossom.

## Origin

| Field | Value |
|-------|-------|
| Upstream | https://github.com/facebook/zstd |
| Version | **1.5.6** (tag `v1.5.6`) |
| Imported | 2026-04-09 |
| Subset | `lib/zstd.h`, `lib/zstd_errors.h`, `lib/common/`, `lib/decompress/` |

## License Election

zstd is dual-licensed by Meta:

- BSD-3-Clause (see [`LICENSE`](LICENSE))
- GPLv2 (see [`COPYING`](COPYING))

CruxCoach **elects BSD-3-Clause** for all uses of this vendored copy. This
election is mandatory: CruxCoach as a whole is licensed under GPLv3, and bare
GPLv2 is not GPLv3-compatible — only the BSD-3-Clause arm permits the
combination.

The full BSD-3-Clause text is reproduced in [`LICENSE`](LICENSE) verbatim from
the upstream `v1.5.6` tag. The GPLv2 text is retained in [`COPYING`](COPYING)
because the source headers reference it; downstream consumers who prefer GPLv2
may exercise that option independently.

## Updating

To refresh the vendored copy to a newer zstd release:

```bash
ZSTD_VERSION=1.5.7   # or whichever
TMP=$(mktemp -d)
git clone --depth 1 --branch "v${ZSTD_VERSION}" \
    https://github.com/facebook/zstd.git "$TMP"

# Replace sources (keep our LICENSE/COPYING/README in place)
cd androidApp/src/main/cpp/zstd
rm -rf common decompress zstd.h zstd_errors.h
cp -r "$TMP/lib/common" .
cp -r "$TMP/lib/decompress" .
cp "$TMP/lib/zstd.h" "$TMP/lib/zstd_errors.h" .

# Refresh the upstream license texts as well — they may have changed
curl -sSf -o LICENSE "https://raw.githubusercontent.com/facebook/zstd/v${ZSTD_VERSION}/LICENSE"
curl -sSf -o COPYING "https://raw.githubusercontent.com/facebook/zstd/v${ZSTD_VERSION}/COPYING"

# Update version + import date in this README
```

After updating, verify the JNI binding (`../zstd_jni.c`) still compiles and the
`CMakeLists.txt` still references the correct source list.
