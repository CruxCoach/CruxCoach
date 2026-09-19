# CruxCoach for iOS

Native SwiftUI app on top of the Kotlin core in [`appcore/`](../appcore/README.md).
Status and feature coverage are tracked honestly in the pull request description;
this is **not** a released product and has not been validated on a physical board.

## Build (macOS, Xcode 16 or newer, JDK 17, Android SDK for Gradle configuration)

```sh
iosApp/scripts/build-core.sh Release      # Gradle → iosApp/Frameworks/CruxCoachCore.xcframework
brew install xcodegen
cd iosApp && xcodegen generate            # → CruxCoach.xcodeproj (not committed)
open CruxCoach.xcodeproj
```

Swift packages: [SQLCipher.swift](https://github.com/sqlcipher/SQLCipher.swift) (official, Zetetic) and
[zstd](https://github.com/facebook/zstd) (official). Do **not** add `-lsqlite3` or `import SQLite3`:
the personal database must go through SQLCipher. The core refuses to open it when
`PRAGMA cipher_version` is empty, and `StorageTests` checks the file header on the simulator.

Strings: `python3 scripts/android_strings_to_ios.py` regenerates `Localizable.strings` (en, de)
from the Android resources; CI fails when they are stale. iOS-only strings go in `IOS.strings`.

## CI

`.github/workflows/ios-build.yml` runs on standard (free for public repositories) runners without
any secret: Android unit tests + debug build, Kotlin/Native simulator tests, XCTest on a simulator,
and an **unsigned** device build packaged as `CruxCoach-<version>-<commit>-UNSIGNED.ipa` with a
SHA-256 file. Never change the runner labels to large/xlarge runners; those always bill.

An unsigned `.ipa` proves that the device target compiles and links. It does not prove that the
app installs, launches, or talks to a board.

## Installing a test build without a Mac or a paid developer account

iOS only runs signed apps. With a free Apple Account the signature is valid for 7 days, at most
3 sideloaded apps per device and 10 App IDs per 7 days
([Apple](https://developer.apple.com/help/account/basics/about-your-developer-account)).
Signing must happen on **your own computer with your own Apple Account**. Nobody from the
project needs, or may ask for, your password, a 2FA code, a pairing file or a certificate.

Route documented by SideStore for Linux/Windows/macOS: [iloader](https://github.com/nab138/iloader)
(needs `usbmuxd` on Linux) → **Import IPA**. Then on the phone: Settings → General → VPN & Device
Management → trust your Apple Account; Settings → Privacy & Security → Developer Mode → on.
Re-import the same IPA before day 7 to keep the app and its data. These third-party tools speak
Apple's private developer-services protocol and can break when Apple changes it; the only
Apple-supported free path is Xcode on a Mac with a Personal Team.

Not available with free provisioning: Associated Domains (universal links — use the
`cruxcoach://` scheme or paste a link), push notifications, iCloud.
