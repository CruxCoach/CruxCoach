#!/usr/bin/env python3
"""A strfry write-policy plugin with the rate limit public relays use (noteguard
"ratelimit" with the damus values): 8 posts a minute per source, and after 10
refusals the source is blocked for an hour. No exception for 127.0.0.1.

Writes what it decided to STATS (if set) so a test can check that a client
was never blocked. Test use on 127.0.0.1 only.
"""
import json
import os
import sys
import time
from collections import defaultdict, deque

POSTS_PER_MINUTE = int(os.environ.get("POSTS_PER_MINUTE", "8"))
BAN_AFTER = int(os.environ.get("BAN_AFTER", "10"))
BAN_S = int(os.environ.get("BAN_S", "3600"))
STATS = os.environ.get("STATS")

posts = defaultdict(deque)
refusals = defaultdict(int)
banned_until = {}
counts = {"accepted": 0, "rate_limited": 0, "blocked": 0, "bans": 0}


def decide(source: str, now: float) -> str | None:
    if banned_until.get(source, 0) > now:
        counts["blocked"] += 1
        return "blocked: too many rejected notes"
    window = posts[source]
    while window and window[0] < now - 60:
        window.popleft()
    if len(window) >= POSTS_PER_MINUTE:
        refusals[source] += 1
        counts["rate_limited"] += 1
        if refusals[source] >= BAN_AFTER:
            banned_until[source] = now + BAN_S
            refusals[source] = 0
            counts["bans"] += 1
        return "rate-limited: you are noting too much"
    window.append(now)
    counts["accepted"] += 1
    return None


def main() -> None:
    for line in sys.stdin:
        try:
            message = json.loads(line)
        except ValueError:
            continue
        event = message.get("event", {})
        reason = decide(message.get("sourceInfo", ""), time.time())
        answer = {"id": event.get("id", ""), "action": "accept" if reason is None else "reject"}
        if reason is not None:
            answer["msg"] = reason
        sys.stdout.write(json.dumps(answer) + "\n")
        sys.stdout.flush()
        if STATS:
            with open(STATS, "w", encoding="utf-8") as out:
                json.dump(counts, out)


if __name__ == "__main__":
    main()
