#!/usr/bin/env python3
"""Read-only: finds a recent public note with a YouTube link and prints it as a note1 address."""
import asyncio, json, re, sys
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


async def scan(host):
    found = []
    try:
        async with websockets.connect(f"wss://{host}", open_timeout=8, max_size=2**23) as ws:
            await ws.send(json.dumps(["REQ", "v", {"kinds": [1], "limit": 500}]))
            while True:
                msg = json.loads(await asyncio.wait_for(ws.recv(), 10))
                if msg[0] == "EOSE":
                    break
                if msg[0] == "EVENT" and re.search(r"(youtube\.com/watch\?v=|youtu\.be/)[\w-]{11}", msg[2]["content"]):
                    found.append(msg[2])
    except Exception as e:
        print("scan", host, e, file=sys.stderr)
    return found


async def main():
    for host in ["relay.primal.net", "nos.lol", "relay.damus.io"]:
        notes = await scan(host)
        if notes:
            e = notes[0]
            print(re.search(r"\S*(youtube\.com/watch\?v=|youtu\.be/)\S*", e["content"]).group(0), file=sys.stderr)
            print(bech32("note", bytes.fromhex(e["id"])))
            return
    sys.exit("no note with a YouTube link found")


asyncio.run(main())
