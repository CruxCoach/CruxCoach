# T2: hostile local mailboxes with real limits

Two installations share through three real strfry relays on 127.0.0.1 (PLAN-0.2.6 §10.2, VT7–VT9, VT12, VT13).
Nothing leaves the machine. This is not part of CI.

| Mailbox | Stands for | Limits |
|---|---|---|
| strfry + `write_policy.py` | the CruxCoach mailbox | write rule PV2: mailbox events and their deletions only, ≤ 14 d + 1 h, size classes, ≤ 60 min old |
| strfry + `ratelimit_policy.py`, behind a hostile proxy | a public relay of pool A | 8 events a minute, blocked for an hour after 10 refusals, no exception for 127.0.0.1; the proxy drops, doubles, delays, reorders, closes subscriptions and connections |
| strfry with `maxFilterLimit = 100` | a public relay of pool B (relay.nostr.net limits) | 100 events per filter |
| fake relay (in the test) | a hostile reserve | keeps everything, ignores deletions, sends junk |

```sh
scripts/mailbox_t2/setup_strfry.sh ~/t2            # once, ~10 min, ~1 GB
CC_T2_STRFRY=~/t2/src/strfry CC_T2_LIB=~/t2/sysroot/usr/lib/x86_64-linux-gnu CC_T2_SEED=7 \
  ./gradlew :androidApp:testDebugUnitTest --tests 'com.cruxcoach.android.sharing.MailboxRealRelaysTest' --rerun
rm -rf ~/t2                                         # afterwards
```

`write_policy.py` is also the reference for the production write rule (PV2), which is proposed in Go next to the
existing relay policy; deploying it is the owner's step.
