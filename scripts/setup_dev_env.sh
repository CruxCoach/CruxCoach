#!/bin/bash
# CruxCoach Development Environment Setup
# Idempotent - safe to run multiple times
# Tested on: Debian 12, Ubuntu 22.04/24.04 (amd64 + arm64)
# Usage: bash scripts/setup_dev_env.sh   (from repo root)
set -euo pipefail

# ── Pinned versions (single source of truth) ────────────────────────
COMPILE_SDK=36
TARGET_SDK=35
BUILD_TOOLS_VERSION="36.0.0"
NDK_VERSION="27.2.12479018"
CMAKE_VERSION="3.22.1"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip"
CMDLINE_TOOLS_SHA256="04453066b540409d975c676d781da1477479dde3761310f1a7eb92a1dfb15af7"

# ── Helpers ──────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1" >&2; exit 1; }

# ── Pre-flight checks ───────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ ! -f "$REPO_ROOT/gradlew" ]; then
    fail "Cannot find gradlew. Run this script from the repo root: bash scripts/setup_dev_env.sh"
fi

if [ "$(uname -s)" != "Linux" ]; then
    fail "This script supports Linux (Debian/Ubuntu) only. On macOS, use Android Studio."
fi

ARCH="$(dpkg --print-architecture 2>/dev/null || true)"
if [ -z "$ARCH" ]; then
    # Fallback for systems without dpkg
    case "$(uname -m)" in
        x86_64)  ARCH="amd64" ;;
        aarch64) ARCH="arm64" ;;
        *)       fail "Unsupported architecture: $(uname -m)" ;;
    esac
fi

echo ""
echo "=== CruxCoach Dev Environment Setup ==="
echo "    Arch: $ARCH"
echo "    Repo: $REPO_ROOT"
echo ""

# ── System packages ─────────────────────────────────────────────────
REQUIRED_PKGS=(wget unzip openjdk-17-jdk)
MISSING_PKGS=()

for pkg in "${REQUIRED_PKGS[@]}"; do
    if ! dpkg -s "$pkg" &>/dev/null; then
        MISSING_PKGS+=("$pkg")
    fi
done

if [ ${#MISSING_PKGS[@]} -gt 0 ]; then
    info "Installing missing packages: ${MISSING_PKGS[*]}"
    if command -v sudo &>/dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y -qq "${MISSING_PKGS[@]}"
    else
        # Running as root (e.g. in Docker)
        apt-get update -qq && apt-get install -y -qq "${MISSING_PKGS[@]}"
    fi
else
    ok "System packages present (wget, unzip, openjdk-17-jdk)"
fi

# ── Java 17 ─────────────────────────────────────────────────────────
JAVA_HOME="/usr/lib/jvm/java-17-openjdk-${ARCH}"

if [ ! -d "$JAVA_HOME" ]; then
    fail "JAVA_HOME not found at $JAVA_HOME — openjdk-17-jdk may not have installed correctly"
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if "$JAVA_HOME/bin/java" -version 2>&1 | grep -q "17\."; then
    ok "Java 17 at $JAVA_HOME"
else
    fail "Java 17 not working at $JAVA_HOME"
fi

# ── Android SDK ──────────────────────────────────────────────────────
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

if [ -f "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    ok "Android SDK CLI Tools already installed"
else
    info "Downloading Android SDK CLI Tools..."
    TMPDIR="$(mktemp -d)"
    trap 'rm -rf "$TMPDIR"' EXIT

    wget -q --show-progress -O "$TMPDIR/cmdline-tools.zip" "$CMDLINE_TOOLS_URL" || \
        fail "Failed to download cmdline-tools from $CMDLINE_TOOLS_URL"

    # Verify checksum if sha256sum is available
    if command -v sha256sum &>/dev/null && [ -n "$CMDLINE_TOOLS_SHA256" ]; then
        ACTUAL_SHA=$(sha256sum "$TMPDIR/cmdline-tools.zip" | cut -d' ' -f1)
        if [ "$ACTUAL_SHA" != "$CMDLINE_TOOLS_SHA256" ]; then
            fail "Checksum mismatch for cmdline-tools.zip (expected $CMDLINE_TOOLS_SHA256, got $ACTUAL_SHA). The download URL may be outdated."
        fi
        ok "Checksum verified"
    fi

    mkdir -p "$ANDROID_HOME/cmdline-tools"
    unzip -q "$TMPDIR/cmdline-tools.zip" -d "$TMPDIR"
    mv "$TMPDIR/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -rf "$TMPDIR"
    trap - EXIT
    ok "Android SDK CLI Tools installed"
fi

# Ensure sdkmanager uses the correct Java
export JAVA_HOME

# ── SDK Licenses ─────────────────────────────────────────────────────
info "Accepting SDK licenses..."
if ! (yes 2>/dev/null || true) | sdkmanager --licenses > /dev/null 2>&1; then
    info "License acceptance returned non-zero (may be OK if already accepted)"
fi

# ── SDK Components ───────────────────────────────────────────────────
install_sdk_component() {
    local component="$1"
    local check_path="$2"

    if [ -d "$check_path" ] || [ -f "$check_path" ]; then
        ok "$component already installed"
    else
        info "Installing $component..."
        # (yes||true) prevents SIGPIPE exit code from breaking pipefail
        # when sdkmanager closes stdin after accepting all prompts.
        (yes 2>/dev/null || true) | sdkmanager "$component" > /dev/null 2>&1 || \
            fail "Failed to install $component. Run 'sdkmanager \"$component\"' manually to see the error."
        if [ ! -d "$check_path" ] && [ ! -f "$check_path" ]; then
            fail "$component installed but $check_path not found — installation may be corrupted"
        fi
        ok "$component installed"
    fi
}

install_sdk_component "platforms;android-${COMPILE_SDK}" \
    "$ANDROID_HOME/platforms/android-${COMPILE_SDK}"

install_sdk_component "platforms;android-${TARGET_SDK}" \
    "$ANDROID_HOME/platforms/android-${TARGET_SDK}"

install_sdk_component "build-tools;${BUILD_TOOLS_VERSION}" \
    "$ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION}"

install_sdk_component "platform-tools" \
    "$ANDROID_HOME/platform-tools/adb"

install_sdk_component "ndk;${NDK_VERSION}" \
    "$ANDROID_HOME/ndk/${NDK_VERSION}"

install_sdk_component "cmake;${CMAKE_VERSION}" \
    "$ANDROID_HOME/cmake/${CMAKE_VERSION}"

# ── local.properties ─────────────────────────────────────────────────
LOCAL_PROPS="$REPO_ROOT/local.properties"
NEEDS_UPDATE=false

if [ ! -f "$LOCAL_PROPS" ]; then
    NEEDS_UPDATE=true
elif ! grep -q "sdk.dir=" "$LOCAL_PROPS"; then
    NEEDS_UPDATE=true
fi

if [ "$NEEDS_UPDATE" = true ]; then
    info "Writing local.properties..."
    # Preserve existing signing config if present
    SIGNING_LINES=""
    if [ -f "$LOCAL_PROPS" ]; then
        SIGNING_LINES=$(grep -E "^RELEASE_" "$LOCAL_PROPS" 2>/dev/null || true)
    fi
    cat > "$LOCAL_PROPS" <<EOF
sdk.dir=$ANDROID_HOME
cmake.dir=$ANDROID_HOME/cmake/$CMAKE_VERSION
EOF
    if [ -n "$SIGNING_LINES" ]; then
        echo "" >> "$LOCAL_PROPS"
        echo "$SIGNING_LINES" >> "$LOCAL_PROPS"
    fi
    ok "local.properties written"
else
    ok "local.properties already configured"
fi

# ── Gradle Wrapper ───────────────────────────────────────────────────
if [ -x "$REPO_ROOT/gradlew" ]; then
    ok "gradlew executable"
else
    chmod +x "$REPO_ROOT/gradlew"
    ok "Made gradlew executable"
fi

# ── Shell environment ────────────────────────────────────────────────
# Detect user's shell rc file
SHELL_RC=""
case "$(basename "${SHELL:-/bin/bash}")" in
    zsh)  SHELL_RC="$HOME/.zshrc" ;;
    bash) SHELL_RC="$HOME/.bashrc" ;;
    fish) SHELL_RC="" ;;  # fish uses a different syntax; skip and advise
    *)    SHELL_RC="$HOME/.bashrc" ;;
esac

ENV_MARKER="# CruxCoach Android Dev"

if [ -n "$SHELL_RC" ]; then
    if grep -qF "$ENV_MARKER" "$SHELL_RC" 2>/dev/null; then
        # Remove old block and rewrite with current values
        info "Updating environment in $SHELL_RC..."
        # Delete from marker to end-marker
        sed -i "/$ENV_MARKER/,/$ENV_MARKER END/d" "$SHELL_RC"
    fi
    info "Adding environment to $SHELL_RC..."
    cat >> "$SHELL_RC" <<EOF

$ENV_MARKER
export JAVA_HOME=$JAVA_HOME
export ANDROID_HOME=$ANDROID_HOME
export PATH=\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH
$ENV_MARKER END
EOF
    ok "Environment added to $SHELL_RC"
else
    info "Fish shell detected. Add these to your config manually:"
    echo "  set -gx JAVA_HOME $JAVA_HOME"
    echo "  set -gx ANDROID_HOME $ANDROID_HOME"
    echo "  fish_add_path $ANDROID_HOME/cmdline-tools/latest/bin $ANDROID_HOME/platform-tools"
fi

# ── Verification ─────────────────────────────────────────────────────
echo ""
echo "=== Verification ==="
echo "Java:         $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "JAVA_HOME:    $JAVA_HOME"
echo "ANDROID_HOME: $ANDROID_HOME"
echo "Build Tools:  $BUILD_TOOLS_VERSION"
echo "NDK:          $NDK_VERSION"
echo "CMake:        $CMAKE_VERSION"

if [ -f "$ANDROID_HOME/platform-tools/adb" ]; then
    echo "ADB:          $("$ANDROID_HOME/platform-tools/adb" version 2>&1 | head -1)"
fi

echo ""
echo "=== Setup complete! ==="
echo ""
echo "Next steps:"
if [ -n "$SHELL_RC" ]; then
    echo "  1. Reload shell:  source $SHELL_RC"
else
    echo "  1. Add env vars to your fish config (see above)"
fi
echo "  2. Build the app:     cd $REPO_ROOT && ./gradlew :androidApp:assembleDebug"
echo "  3. Install on device: adb install androidApp/build/outputs/apk/debug/androidApp-debug.apk"
