# shellcheck shell=bash
# Shared by the migration flows; source it, do not run it.
#
# apk_signer_digest APKSIGNER APK prints the APK's one public signer certificate SHA-256 in
# lowercase. It fails for zero or several signers and for certificate lines it does not know,
# because an unknown format could hide a second signer.
#
# apksigner up to build-tools 36 prints "Signer #1 certificate SHA-256 digest: …" (with key
# rotation "Signer (minSdkVersion=…, maxSdkVersion=…) certificate …"). Build-tools 37 names the
# scheme instead: "V3.0 Signer: certificate …", "V2 Signer #1: certificate …",
# "V3.1 Signer: (minSdkVersion=…, maxSdkVersion=…) certificate …".
apk_signer_digest() {
    local apksigner="$1" apk="$2" output line digest="" found
    local signer_re='^(V[0-9]+(\.[0-9]+)? )?Signer( #[0-9]+)?:? (\(minSdkVersion=[0-9]+, maxSdkVersion=[0-9]+\) )?certificate SHA-256 digest: ([0-9a-fA-F]{64})$'
    # The source stamp records the distributing store; it is not an app signer.
    local stamp_re='^Source Stamp Signer:? certificate SHA-256 digest: [0-9a-fA-F]{64}$'
    output="$("$apksigner" verify --print-certs "$apk")" || return 1
    while IFS= read -r line; do
        [[ "$line" == *"certificate SHA-256 digest:"* ]] || continue
        [[ "$line" =~ $stamp_re ]] && continue
        [[ "$line" =~ $signer_re ]] || return 1
        found="${BASH_REMATCH[5],,}"
        [[ -z "$digest" || "$digest" == "$found" ]] || return 1
        digest="$found"
    done <<< "$output"
    [[ -n "$digest" ]] || return 1
    printf '%s' "$digest"
}
