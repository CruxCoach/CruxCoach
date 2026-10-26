#!/usr/bin/env python3
"""The CruxCoach mailbox write rule (NACHTRAG PV2) as a strfry write-policy plugin.

Test implementation for T2 on 127.0.0.1; the production proposal is the same
rule in Go next to the existing relay policy. strfry sends one JSON line per
incoming event on stdin and expects one answer line per event on stdout.

Accepted:
- kind 2437: exactly one `z` tag (16 lowercase hex) and one `expiration` no
  later than now + 14 days + 1 h, at most 4 tags (a `nonce` may be one),
  content that decodes to exactly one size class, created at most 60 min ago
  and at most 15 min ahead, NIP-13 work of POW_BITS if set.
- kind 5: only `e` tags (at most 50), exactly one `k` = 2437, one
  `expiration` within the same bound, empty content.
- at most 300 events per address and day, at most 50 events per second.
Everything else is refused. No limit per IP: the proxy does not pass it on.
"""
import base64
import json
import os
import re
import sys
import time
from collections import defaultdict, deque

KIND = 2437
MAX_AGE_S = 14 * 86_400 + 3_600
SIZE_CLASSES = {1024, 2048, 4096, 8192, 16_384, 32_768, 48_128}
POW_BITS = int(os.environ.get("POW_BITS", "0"))
ADDRESS = re.compile(r"^[0-9a-f]{16}$")
PER_ADDRESS_DAY = 300
PER_SECOND = 50

per_address = defaultdict(int)
recent = deque()


def leading_zero_bits(hex_id: str) -> int:
    bits = 0
    for ch in hex_id:
        nibble = int(ch, 16)
        if nibble == 0:
            bits += 4
            continue
        return bits + (4 - nibble.bit_length())
    return bits


def check(event: dict, now: int) -> str | None:
    kind = event.get("kind")
    if kind not in (KIND, 5):
        return "blocked: this mailbox takes mailbox events only"
    created = int(event.get("created_at", 0))
    if created < now - 3_600 or created > now + 900:
        return "invalid: created_at outside the window"
    tags = event.get("tags", [])
    expirations = [t for t in tags if t and t[0] == "expiration"]
    if len(expirations) != 1 or len(expirations[0]) != 2 or not expirations[0][1].isdigit():
        return "invalid: exactly one expiration"
    if int(expirations[0][1]) > now + MAX_AGE_S:
        return "invalid: expiration too far"
    if kind == KIND:
        if len(tags) > 4:
            return "invalid: too many tags"
        addresses = [t for t in tags if t and t[0] == "z"]
        if len(addresses) != 1 or len(addresses[0]) != 2 or not ADDRESS.match(addresses[0][1]):
            return "invalid: exactly one address"
        if any(t[0] not in ("z", "expiration", "nonce") for t in tags if t):
            return "invalid: unknown tag"
        try:
            raw = base64.b64decode(event.get("content", ""), validate=True)
        except Exception:
            return "invalid: content"
        if len(raw) not in SIZE_CLASSES:
            return "invalid: size class"
        if POW_BITS and leading_zero_bits(event.get("id", "")) < POW_BITS:
            return "pow: not enough work"
        key = (addresses[0][1], now // 86_400)
        if per_address[key] >= PER_ADDRESS_DAY:
            return "rate-limited: address full today"
        per_address[key] += 1
        return None
    if kind == 5:
        if event.get("content", "") != "":
            return "invalid: deletion with content"
        ids = [t for t in tags if t and t[0] == "e"]
        kinds = [t for t in tags if t and t[0] == "k"]
        if not 1 <= len(ids) <= 50 or len(kinds) != 1 or kinds[0][1:] != [str(KIND)]:
            return "invalid: deletion shape"
        if len(ids) + len(kinds) + len(expirations) != len(tags):
            return "invalid: unknown tag"
    return None


def main() -> None:
    for line in sys.stdin:
        try:
            message = json.loads(line)
        except ValueError:
            continue
        event = message.get("event", {})
        now = int(time.time())
        while recent and recent[0] < now - 1:
            recent.popleft()
        reason = "rate-limited: too many events" if len(recent) >= PER_SECOND else check(event, now)
        if reason is None:
            recent.append(now)
        answer = {"id": event.get("id", ""), "action": "accept" if reason is None else "reject"}
        if reason is not None:
            answer["msg"] = reason
        sys.stdout.write(json.dumps(answer) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
