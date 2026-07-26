#!/usr/bin/env bash
# Build three unmodified public-tag debug fixtures and one current release
# candidate in isolated git worktrees. Existing local configuration, signing,
# auth, credential, keystore, and secret material is never located or read.

set -euo pipefail
umask 077

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
readonly repo_root
readonly -a tags=(v0.1.4 v0.2.0 v0.2.1)

die() {
    echo "ERROR: $*" >&2
    exit 2
}

command -v git >/dev/null 2>&1 || die "git is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$sdk_root" && -d "$sdk_root" ]] ||
    die "ANDROID_SDK_ROOT (or ANDROID_HOME) must name the Android SDK"
sdk_root="$(realpath "$sdk_root")"

latest_build_tool() {
    local name="$1"
    find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$name" -print 2>/dev/null |
        sort -V | tail -1
}

aapt_bin="${AAPT:-$(latest_build_tool aapt)}"
apksigner_bin="${APKSIGNER:-$(latest_build_tool apksigner)}"
[[ -x "$aapt_bin" ]] || die "aapt not found in Android SDK build-tools"
[[ -x "$apksigner_bin" ]] || die "apksigner not found in Android SDK build-tools"

tmp_base="$(realpath -m "${TMPDIR:-/tmp}")"
mkdir -p "$tmp_base"
minimum_free_kib="${MATCHING_MIN_FREE_KIB:-7340032}"
[[ "$minimum_free_kib" =~ ^[1-9][0-9]*$ ]] || die "MATCHING_MIN_FREE_KIB must be positive"
free_kib_now() {
    df -Pk "$tmp_base" | awk 'NR == 2 {print $4}'
}
initial_free_kib="$(free_kib_now)"
[[ "$initial_free_kib" =~ ^[0-9]+$ ]] || die "could not determine free disk space"
((initial_free_kib >= minimum_free_kib)) ||
    die "insufficient free space before build (${initial_free_kib} KiB; require ${minimum_free_kib} KiB)"
temp_root="$(mktemp -d "$tmp_base/cruxcoach-matching-build.XXXXXX")"
case "$temp_root" in
    "$tmp_base"/cruxcoach-matching-build.*) ;;
    *) die "mktemp returned an unexpected path" ;;
esac

output_dir="${MATCHING_FIXTURE_DIR:-$tmp_base/cruxcoach-matching-fixtures-$(date -u +%Y%m%dT%H%M%SZ)}"
output_dir="$(realpath -m "$output_dir")"
repo_real="$(realpath "$repo_root")"
case "$output_dir/" in
    "$repo_real/"*) die "MATCHING_FIXTURE_DIR must be outside the repository" ;;
esac
[[ ! -e "$output_dir" ]] || die "fixture output already exists: $output_dir"
mkdir -p "$output_dir/logs"
printf 'checkpoint\tavailable_kib\tminimum_kib\ninitial\t%s\t%s\n' \
    "$initial_free_kib" "$minimum_free_kib" > "$output_dir/disk-space.tsv"

declare -a worktrees=()
cleanup() {
    local status=$? tree
    trap - EXIT
    for tree in "${worktrees[@]}"; do
        [[ -n "$tree" ]] || continue
        git -C "$repo_root" worktree remove --force "$tree" >/dev/null 2>&1 || true
    done
    git -C "$repo_root" worktree prune >/dev/null 2>&1 || true
    if ((status != 0)) && [[ -d "$output_dir" ]]; then
        {
            echo "completed=false"
            echo "exit_code=$status"
            echo "partial_outputs_retained=true"
            echo "resume_by_mixing_apks_allowed=false"
            echo "reason=a new invocation creates a new ephemeral debug signer; rebuild all four together"
            echo "ended_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        } > "$output_dir/incomplete-build.properties"
    fi
    case "$temp_root" in
        "$tmp_base"/cruxcoach-matching-build.*) rm -rf -- "$temp_root" ;;
    esac
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

require_free_space() {
    local checkpoint="$1" available
    available="$(free_kib_now)"
    [[ "$available" =~ ^[0-9]+$ ]] || die "could not determine free disk space at $checkpoint"
    printf '%s\t%s\t%s\n' "$checkpoint" "$available" "$minimum_free_kib" \
        >> "$output_dir/disk-space.tsv"
    ((available >= minimum_free_kib)) ||
        die "insufficient free space at $checkpoint (${available} KiB; require ${minimum_free_kib} KiB)"
}

remove_worktree_now() {
    local target="$1" label="$2" index
    git -C "$repo_root" worktree remove --force "$target" \
        > "$output_dir/logs/remove-worktree-${label}.log" 2>&1 ||
        die "could not remove completed build worktree: $label"
    for index in "${!worktrees[@]}"; do
        if [[ "${worktrees[$index]}" == "$target" ]]; then
            unset "worktrees[$index]"
            break
        fi
    done
}

untracked_production="$({
    git -C "$repo_root" ls-files --others --exclude-standard -- \
        androidApp/src/main shared/src/main androidApp/build.gradle.kts \
        build.gradle.kts settings.gradle.kts gradle gradle.properties
} || true)"
[[ -z "$untracked_production" ]] ||
    die "untracked production/build files would be omitted from the current release snapshot"

selector_audit="$output_dir/predecessor-selector-audit.json"
python3 "$repo_root/flows/lib/audit_migration_predecessor.py" \
    --repo "$repo_root" \
    --flow "$repo_root/flows/migration-predecessor-setup.yaml" > "$selector_audit" ||
    die "historical selector audit failed"

isolated_user_home="$temp_root/isolated-user"
isolated_gradle_home="$temp_root/isolated-gradle"
mkdir -p "$isolated_user_home" "$isolated_gradle_home"

build_tree() {
    local tree="$1" task="$2" log="$3"
    (
        cd "$tree"
        env \
            ANDROID_SDK_ROOT="$sdk_root" \
            ANDROID_HOME="$sdk_root" \
            bash ./gradlew \
                -Duser.home="$isolated_user_home" \
                --gradle-user-home "$isolated_gradle_home" \
                --no-daemon \
                --max-workers=1 \
                "$task"
    ) > "$log" 2>&1
}

printf 'source\tcommit\tfixture_class\tselector_mode\n' > "$output_dir/fixture-source.tsv"
for tag in "${tags[@]}"; do
    require_free_space "before-${tag}-debug"
    commit="$(git -C "$repo_root" rev-parse "$tag^{commit}")"
    tree="$temp_root/tree-${tag#v}"
    git -C "$repo_root" worktree add --detach "$tree" "$commit" \
        > "$output_dir/logs/worktree-${tag}.log" 2>&1
    worktrees+=("$tree")
    build_tree "$tree" :androidApp:assembleDebug "$output_dir/logs/build-${tag}-debug.log"
    source_apk="$tree/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
    [[ -f "$source_apk" ]] || die "$tag assembleDebug did not produce its APK"
    install -m 600 "$source_apk" "$output_dir/cruxcoach-${tag}-debug.apk"
    printf '%s\t%s\tunmodified-public-tag-assembleDebug\tvisible-text-and-content-description-only\n' \
        "$tag" "$commit" >> "$output_dir/fixture-source.tsv"
    remove_worktree_now "$tree" "$tag"
done

require_free_space "before-current-release"
current_tree="$temp_root/tree-current"
current_commit="$(git -C "$repo_root" rev-parse HEAD)"
git -C "$repo_root" worktree add --detach "$current_tree" "$current_commit" \
    > "$output_dir/logs/worktree-current.log" 2>&1
worktrees+=("$current_tree")
current_patch="$output_dir/current-workspace.patch"
git -C "$repo_root" diff --binary --no-ext-diff HEAD -- . > "$current_patch"
sha256sum "$current_patch" > "$output_dir/current-workspace.patch.sha256"
if [[ -s "$current_patch" ]]; then
    git -C "$current_tree" apply --whitespace=nowarn "$current_patch" \
        > "$output_dir/logs/apply-current-workspace.log" 2>&1
else
    echo "current workspace has no tracked diff" > "$output_dir/logs/apply-current-workspace.log"
fi
git -C "$current_tree" status --short > "$output_dir/current-snapshot-status.txt"
build_tree "$current_tree" :androidApp:assembleRelease "$output_dir/logs/build-current-release.log"
current_source_apk="$current_tree/androidApp/build/outputs/apk/release/androidApp-release.apk"
[[ -f "$current_source_apk" ]] || die "current assembleRelease did not produce its APK"
install -m 600 "$current_source_apk" "$output_dir/cruxcoach-v0.2.2-release-candidate.apk"
printf 'current-workspace\t%s\tassembleRelease-candidate\ttestTagsAsResourceId-current-release\n' \
    "$current_commit" >> "$output_dir/fixture-source.tsv"
remove_worktree_now "$current_tree" current

printf 'artifact\tversion_code\tversion_name\tdebuggable\tapk_sha256\tpublic_signer_sha256\n' \
    > "$output_dir/apk-metadata.tsv"
metadata_row() {
    local apk="$1" label="$2" badging identity debuggable digest hash
    badging="$("$aapt_bin" dump badging "$apk")"
    identity="$(sed -n "s/^package: name='com.cruxcoach.android' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1\t\2/p" <<< "$badging")"
    [[ -n "$identity" ]] || die "cannot parse APK identity for $label"
    if grep -qx 'application-debuggable' <<< "$badging"; then debuggable=true; else debuggable=false; fi
    "$apksigner_bin" verify --verbose "$apk" >/dev/null || die "signature verification failed for $label"
    digest="$("$apksigner_bin" verify --print-certs "$apk" |
        sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
    [[ "$digest" =~ ^[0-9a-fA-F]{64}$ ]] || die "cannot parse public signer for $label"
    hash="$(sha256sum "$apk" | awk '{print $1}')"
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$label" "${identity%%$'\t'*}" "${identity#*$'\t'}" "$debuggable" "$hash" "${digest,,}" \
        >> "$output_dir/apk-metadata.tsv"
}

new_apk="$output_dir/cruxcoach-v0.2.2-release-candidate.apk"
for tag in "${tags[@]}"; do
    old_apk="$output_dir/cruxcoach-${tag}-debug.apk"
    AAPT="$aapt_bin" APKSIGNER="$apksigner_bin" \
        "$repo_root/flows/run-matching-debug-migration.sh" \
        --preflight-only --old-apk "$old_apk" --new-apk "$new_apk" \
        > "$output_dir/logs/preflight-${tag}-to-v0.2.2.log"
    metadata_row "$old_apk" "$tag-debug"
done
metadata_row "$new_apk" "v0.2.2-release-candidate"

shared_signer_count="$(tail -n +2 "$output_dir/apk-metadata.tsv" | cut -f6 | sort -u | wc -l | tr -d ' ')"
[[ "$shared_signer_count" == "1" ]] || die "built APK signer digests are not identical"
{
    echo "fixture_count=3"
    echo "candidate_count=1"
    echo "historical_source_mutation=false"
    echo "historical_selector_mode=visible-text-and-content-description-only"
    echo "current_build_task=assembleRelease"
    echo "ephemeral_signer_scope=single-complete-builder-invocation"
    echo "interrupted_runs_must_rebuild_all_four=true"
    echo "existing_local_or_signing_material_read=false"
    echo "public_artifact_upgrade_proved=false"
    echo "completed_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
} > "$output_dir/build.properties"

echo "PASS: built three unmodified public-tag debug fixtures and one non-debuggable vc8 release candidate"
echo "Fixtures: $output_dir"
