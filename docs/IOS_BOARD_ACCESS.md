# iOS Board Access Without a CruxCoach App Store Release

Status: **architecture note and feasibility plan, not an implemented iOS
client.** Platform facts and distribution terms were last checked on
2026-08-15 and must be revalidated before committing to a release channel.

## 1. Non-negotiable product requirement

In normal personal use, the phone running CruxCoach must communicate directly
with the MoonBoard or Kilter Board over BLE. A remote Android controller or a
local web server that alone owns the board connection is therefore not a
substitute for iOS support.

Competition safety is a separate concern. Every participant device may be
technically capable of board BLE, but an active BoardCell still permits only
the canonical controller to write. Direct BLE capability must not bypass the
controller term, write-ahead log, or projection commit described in
[`specs/0.2.3/OFFLINE-BOARDCELL-FIPS-ARCHITECTURE.md`](specs/0.2.3/OFFLINE-BOARDCELL-FIPS-ARCHITECTURE.md).

## 2. The browser boundary

Stock Safari and an ordinary home-screen web app do not expose the Web
Bluetooth API. Consequently, HTML, JavaScript, a service worker, an nsite,
Nostr, or FIPS cannot by themselves open a board's GATT services on iOS. Some
native component with CoreBluetooth access must exist on the phone.

This creates three distinct product questions that must not be conflated:

1. Can CruxCoach avoid having its **own** App Store listing? Potentially yes.
2. Can the user avoid installing **any** native BLE host? No, not while Safari
   lacks Web Bluetooth.
3. Can a web-delivered CruxCoach UI still talk directly from that iPhone to the
   board? Yes, if an installed native host provides the Web Bluetooth bridge.

WebKit's public issue remains the primary browser reference:
[Add Support for Web Bluetooth in iOS WebKit](https://bugs.webkit.org/show_bug.cgi?id=238049).

## 3. Preferred feasibility path: a third-party Web Bluetooth host

The smallest experiment is to run a CruxCoach web client inside an existing
iOS browser that implements `navigator.bluetooth` using CoreBluetooth. Bluefy
currently advertises that central-role GATT bridge:
[Bluefy -- Web BLE Browser](https://apps.apple.com/us/app/bluefy-web-ble-browser/id1492822055).

The data path is direct even though the JavaScript API is bridged:

```text
CruxCoach web client on the iPhone
        -> navigator.bluetooth
        -> Bluefy CoreBluetooth bridge
        -> physical MoonBoard or Kilter Board
```

CruxCoach itself would not need an App Store release. Users would, however,
need to install and trust Bluefy from the App Store. This is a dependency on a
third-party runtime, not an App-Store-free solution.

The web implementation should isolate board I/O behind a narrow transport
contract rather than mix it into UI code:

```text
BoardTransport
  |- AndroidNativeBoardTransport
  |- WebBluetoothBoardTransport
  `- future IOSNativeBoardTransport
```

The Web Bluetooth adapter must reproduce the existing protocol exactly:
discovery filters, GATT service and characteristic selection, frame encoding,
20-byte chunking where required, write ordering, notifications, disconnect
handling, and board-specific pacing. Golden byte-vector tests should run
against both the Kotlin implementation and the web implementation.

### Limits of this path

- Web Bluetooth requires an explicit user gesture and device selection.
- Background execution, screen lock, reconnect, cached permissions, and
  browser lifecycle behavior are controlled by the host and must be measured.
- The app must be delivered from a context accepted as secure by the host.
  HTTPS and actual offline-cache behavior must be tested rather than assumed.
- A Web Bluetooth central can talk to the board, but it does not provide the
  phone-to-phone BLE/L2CAP surface required by the current embedded FIPS
  BoardCell mesh.
- Therefore this route may provide personal direct board control before it can
  provide full offline BoardCell mesh parity.

## 4. Full-control path: a thin native iOS host

If third-party browser dependency is unacceptable, CruxCoach needs a native
iOS host. It can remain deliberately thin:

```text
CruxCoach iOS host
  |- Swift/CoreBluetooth board transport
  |- WKWebView or native UI shell
  |- local durable state and key custody
  |- optional Rust FIPS core
  `- narrow, origin-checked capability bridge
```

The UI and much application logic may still be web-delivered, but the host
must own permissions, CoreBluetooth objects, lifecycle restoration, and any
FIPS transport. The bridge must expose domain operations such as
`connectBoard`, `sendClimb`, and `observeBoardState`, not unrestricted native
method invocation. Every board write must still enter the same BoardCell
serializer and authority checks.

Distribution outside CruxCoach's public App Store listing remains possible but
has operational costs:

- development or ad-hoc signing is suitable only for bounded test cohorts;
- AltStore, SideStore, and similar sideloading approaches add installation,
  trust, signing, and sometimes refresh burden;
- TestFlight builds expire after 90 days and external testing may require
  review ([Apple TestFlight overview](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview));
- alternative marketplace and direct web distribution are region-specific,
  Apple-signed/notarized, and governed by changing eligibility rules. Apple's
  current EU web-distribution requirements are documented at
  [Getting started with Web Distribution in the EU](https://developer.apple.com/support/web-distribution-eu/).

None of these channels is equivalent to unrestricted installation of an APK.
The project must select a supported audience and distribution commitment
before treating a native iOS host as a product release.

## 5. What Myco and nsites can and cannot contribute

An nsite is web content, not a converted APK or an independently installed
native package. A future Myco iOS runtime could provide an origin-scoped BLE
capability to a CruxCoach nsite and could reuse the Rust content/FIPS layers.
That would allow CruxCoach UI and application updates to remain outside the
App Store after the runtime is installed.

It does not remove the bootstrap requirement: Myco itself would still be a
native iOS app requiring an accepted installation channel. Myco currently has
no iOS client and its documented nsite v1 has no privileged capability API.
Consequently, "publish CruxCoach as an nsite" is not a current solution for
iPhone board access.

Even with a future capability host, permissions must be narrow and explicit:

- only the active nsite origin may access its granted board session;
- the user must select or confirm the physical board;
- raw CoreBluetooth access should not be exposed when typed board operations
  suffice;
- competition writes must pass through BoardCell authority and fencing;
- capability grants and realm-scoped FIPS identities must remain separate from
  the user's CruxCoach account identity.

## 6. Required feasibility spike

Before choosing Bluefy or funding a native host, run the same experiment for
at least one supported Kilter Board and each materially different MoonBoard
controller:

1. Load a minimal HTTPS client in the candidate iOS host.
2. Discover the correct board among two identically named nearby boards.
3. Connect and enumerate the expected GATT services and characteristics.
4. Send golden protocol frames and confirm the intended LEDs physically.
5. Verify pacing, chunking, writes with/without response, and notifications.
6. Disconnect, reconnect, switch apps, lock the screen, and recover after the
   host process is killed.
7. Load the client once online, disable internet, restart it, and prove that
   both UI and board communication remain available.
8. Test concurrent board connections and a second nearby board without
   allowing identity or state to cross.
9. Confirm that an active competition rejects writes from every non-controller
   path even when that phone has a valid direct board connection.

The spike succeeds only when the physical board result matches the encoded
climb, not merely when a GATT write reports success.

## 7. Current decision

- Direct phone-to-board BLE remains mandatory for normal iOS use.
- A stock Safari/PWA implementation is not viable while Web Bluetooth is
  unavailable.
- Bluefy is the preferred low-cost feasibility path when avoiding a
  **CruxCoach** App Store release is sufficient.
- A thin native host is the path to controlled BLE behavior and full FIPS
  BoardCell parity, but it requires an explicit non-App-Store distribution
  strategy.
- Myco/nsite packaging is a possible future delivery layer after an iOS native
  capability host exists; it is not the mechanism that grants BLE access.
- No iOS path is production-approved until the hardware spike and offline
  lifecycle tests pass.
