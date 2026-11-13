#!/usr/bin/env python3
"""Read-only: finds a recent public event of a kind and prints a nostr: address for it (nevent, naddr or npub)."""
import asyncio, json, struct, sys
import websockets

CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def polymod(values):
    gen = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]
    chk = 1
    for v in values:
        b = chk >> 25
        chk = (chk & 0x1ffffff) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if (b >> i) & 1 else 0
    return chk


def bech32(hrp, data):
    acc, bits, out = 0, 0, []
    for b in data:
        acc = (acc << 8) | b
        bits += 8
        while bits >= 5:
            bits -= 5
            out.append((acc >> bits) & 31)
    if bits:
        out.append((acc << (5 - bits)) & 31)
    values = [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp] + out + [0] * 6
    mod = polymod(values) ^ 1
    out += [(mod >> 5 * (5 - i)) & 31 for i in range(6)]
    return hrp + "1" + "".join(CHARSET[d] for d in out)


def tlv(t, v):
    return bytes([t, len(v)]) + v


async def scan(host, kind):
    try:
        async with websockets.connect(f"wss://{host}", open_timeout=8, max_size=2**23) as ws:
            await ws.send(json.dumps(["REQ", "k", {"kinds": [kind], "limit": 200 if kind == 0 else 40}]))
            found = []
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), 10))
                if msg[0] == "EOSE" or msg[0] == "CLOSED":
                    return found
                if msg[0] == "EVENT":
                    found.append(msg[2])
    except Exception as e:
        print("scan", host, e, file=sys.stderr)
        return []


def usable(e, kind):
    tags = e["tags"]
    if kind in (20, 21, 22, 34235, 34236):
        return any(t and t[0] == "imeta" and any(x.startswith("url https://") for x in t) for t in tags)
    if kind == 30023:
        return any(t and t[0] == "title" for t in tags) and len(e["content"]) > 400
    if kind == 10011:
        return any(t and t[0] == "i" and len(t) >= 3 for t in tags)
    if kind == 0:
        try:
            p = json.loads(e["content"])
        except Exception:
            return False
        return str(p.get("banner", "")).startswith("https://") and "@" in str(p.get("lud16", "")) and bool(p.get("website"))
    return True


async def main():
    kind = int(sys.argv[1])
    for host in ["relay.primal.net", "nos.lol", "relay.damus.io", "relay.ditto.pub"]:
        events = [e for e in await scan(host, kind) if usable(e, kind)]
        if not events:
            continue
        e = events[0]
        if kind in (0, 10011):
            print(bech32("npub", bytes.fromhex(e["pubkey"])))
        elif 30000 <= kind < 40000:
            d = next((t[1] for t in e["tags"] if t and t[0] == "d" and len(t) > 1), "")
            data = tlv(0, d.encode()) + tlv(1, f"wss://{host}".encode()) + tlv(2, bytes.fromhex(e["pubkey"])) + tlv(3, struct.pack(">I", kind))
            print(bech32("naddr", data))
        else:
            data = tlv(0, bytes.fromhex(e["id"])) + tlv(1, f"wss://{host}".encode()) + tlv(3, struct.pack(">I", kind))
            print(bech32("nevent", data))
        print(f"kind {kind} from {host}: {json.dumps(e)[:300]}", file=sys.stderr)
        return
    sys.exit(f"no usable kind {kind} event found")


asyncio.run(main())
