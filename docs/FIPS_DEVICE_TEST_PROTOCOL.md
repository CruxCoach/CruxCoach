# FIPS / BoardCell device test protocol

This is the hardware acceptance gate for FEAT-059. Use three API-29+ arm64
phones (A/B/C), two physical boards in radio range, and optionally a fourth
phone as load generator. Install the same debug APK on every phone without
clearing app data between restart cases.

## Build and evidence capture

```sh
./gradlew :androidApp:buildFipsNative :androidApp:assembleDebug
adb -s A install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
# Repeat for B/C, then capture each phone to a separate file.
adb -s A logcat -c
adb -s A logcat -v threadtime > /tmp/fips-A.log
```

Record phone model, Android build, CruxCoach version, board firmware/serial or
BLE address, wall-clock start/end, and the three log files. A pass requires no
process crash/ANR and identical visible queue/current climb on all joined
phones after every recovery step.

## Matrix

1. Connect A to board 1 and C to board 2. Start simultaneous joinable sessions;
   join B to board 1. Project different climbs and verify neither nearby list,
   last climb nor playlist crosses cells.
2. Clear app data on A/B, connect both to board 1 within two seconds and start
   discovery. Verify one Cell ID/controller wins before any board write. Repeat
   ten times with start order reversed.
3. With a multi-connect-capable board, request two projections concurrently.
   Verify the wall write order equals consecutive `PROJECT_COMMITTED` sequence
   order and all phones converge to the latter projection.
4. Put B out of range while A advances the queue twice, then return B. Verify B
   receives snapshot/history and never displays the later delta without the
   missing sequence. Repeat after force-stopping/restarting B and after toggling
   Bluetooth on B.
5. Isolate the controller A without requesting handover. Verify B/C freeze and
   cannot write. Restore A and verify convergence. Then repeat with an orderly
   handover to B and verify only B can commit.
6. Arrange A—B—C so A and C have no direct BLE reachability. Join explicitly
   while adjacent, restore the line topology, then update the queue and a local
   competition. Verify BoardCell and signed competition events traverse B, but
   C never sees a discovery/claim advertisement originating only at A.
7. During a local competition, isolate the current authority and submit an
   intent. Verify it remains pending (no alternate authority chain), then heals
   from history after reconnection.
8. Run a 40-member logical fan-out (real devices plus the JVM transport harness
   where hardware count is unavailable), update a 38-item playlist repeatedly,
   and reconnect one lagging member. Verify bounded memory, full snapshot
   recovery and no silent sequence skip.
9. Keep the session active for 30 minutes through screen-off/Doze and app
   backgrounding. Verify the connected-device foreground service remains and
   Bluetooth/process restarts recover without changing the FIPS npub.
10. Introduce a legacy unscoped advertiser beside both boards. Verify it cannot
    overwrite either selected cell; repeat with only one known board to confirm
    backwards-compatible display.
11. Record the account npub and active FIPS npub. Verify they differ; repeat a
    BLE reconnect, process restart and Bluetooth restart and verify the FIPS npub
    is stable. End/switch the realm and verify it rotates. Repeat a competition
    reconnect and verify the separate local participant credential preserves the
    participant stream within that competition.
12. Advertise two adjacent Cells/realms and inspect raw advertisements. Verify
    only short unequal tags are present, a foreign realm never forms a durable
    link, and replaying CCJ1 through phone B does not admit B's non-direct peer.
13. Enable FEAT-044 relay sharing and send one identifiable and one deliberately
    unknown LED payload from the official app. Verify ordered
    `PROJECT_COMMITTED`, then `PROJECT_UNKNOWN`; isolate/expire the controller and
    verify the external GATT write is refused and never reaches the board.

API 28 is a separate compatibility pass: repeat join/queue basics over GATT and
verify FIPS is not started. OEM L2CAP connection count, Doze behavior and BLE
address randomization are hardware gates; a JVM or emulator result cannot close
them.
