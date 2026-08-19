# CruxCoach NixOS dev shell — local, untracked.
#
# Replaces scripts/setup_dev_env.sh on NixOS (that script is Debian/Ubuntu only:
# it calls dpkg/apt and hard-codes /usr/lib/jvm paths that don't exist here).
#
# This flake pins the exact SDK / NDK / build-tools / CMake versions declared in
# scripts/setup_dev_env.sh so the build is identical to upstream CI.
#
# Usage:
#   nix develop                  # enter shell, or use direnv (see .envrc)
#   ./gradlew :androidApp:assembleDebug
#   ./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest
#
# Caveats:
#   * The Nix Android SDK lives in the read-only /nix/store, so AGP cannot
#     auto-download missing components — every needed piece is listed below.
#   * nixpkgs is floated on nixos-unstable. If a future nixos-unstable revision
#     drops NDK 27.2.12479018 (or any other pinned version), `nix develop` will
#     fail to evaluate. Fix by pinning nixpkgs to a known-good commit:
#
#       nixpkgs.url = "github:NixOS/nixpkgs/<commit-sha>";
#
#     and re-running `nix flake update`.
#   * BLE board control needs a physical Android device; the emulator is fine
#     for everything else.

{
  description = "CruxCoach Android dev shell (NixOS)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs systems;
    in {
      # Legacy single-shell output — needed by `direnv`'s `use flake`
      # (which calls `nix print-dev-env` with no installable and resolves
      # `devShell.<system>` rather than `devShells.<system>.default`).
      devShell = forAllSystems (system: self.devShells.${system}.default);

      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;              # Android SDK is unfree
              android_sdk.accept_license = true; # accept Google SDK license
            };
          };

          # Versions — single source of truth: scripts/setup_dev_env.sh
          compileSdk = "36";
          targetSdk = "35";
          buildToolsVersion = "36.0.0";
          ndkVersion = "27.2.12479018";
          cmakeVersion = "3.22.1";

          androidSdk = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ targetSdk compileSdk ];
            buildToolsVersions = [ "35.0.0" buildToolsVersion ];
            includeNDK = true;
            ndkVersion = ndkVersion;
            cmakeVersions = [ cmakeVersion ];
            includeEmulator = false;
            includeSystemImages = false;
          };

          jdk = pkgs.openjdk17;
          sdkRoot = "${androidSdk.androidsdk}/libexec/android-sdk";

        in {
          default = pkgs.mkShell {
            packages = [
              jdk
              pkgs.wget
              pkgs.unzip
              pkgs.git
            ];

            JAVA_HOME = "${jdk}/lib/openjdk";
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            ANDROID_NDK_ROOT = "${sdkRoot}/ndk/${ndkVersion}";

            shellHook = ''
              echo ""
              echo "=== CruxCoach Nix dev shell ==="
              echo "  JAVA_HOME        = $JAVA_HOME"
              echo "  ANDROID_HOME     = $ANDROID_HOME"
              echo "  NDK              = ${ndkVersion}"
              echo "  Build Tools      = ${buildToolsVersion}"
              echo "  CMake            = ${cmakeVersion}"
              echo "  Platforms        = android-${targetSdk}, android-${compileSdk}"
              echo ""

              chmod +x ./gradlew 2>/dev/null || true

              # Write local.properties if missing or sdk.dir drifted.
              # Preserves any RELEASE_* signing lines already present.
              local_props="./local.properties"
              need_write=1
              if [ -f "$local_props" ] && grep -q "^sdk.dir=$ANDROID_HOME$" "$local_props"; then
                need_write=0
              fi
              if [ "$need_write" = "1" ]; then
                signing=""
                if [ -f "$local_props" ]; then
                  signing=$(grep -E "^RELEASE_" "$local_props" 2>/dev/null || true)
                fi
                {
                  echo "sdk.dir=$ANDROID_HOME"
                  echo "cmake.dir=$ANDROID_HOME/cmake/${cmakeVersion}"
                  if [ -n "$signing" ]; then
                    echo ""
                    echo "$signing"
                  fi
                } > "$local_props"
                echo "[nix] wrote $local_props"
              fi
            '';
          };
        });
    };
}
