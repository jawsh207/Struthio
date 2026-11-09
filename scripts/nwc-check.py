#!/usr/bin/env python3
"""Read-only: how a wallet relay treats the subscriptions Struthio opens. Nothing is published."""
import asyncio, json, os, time, urllib.request
import websockets

RELAYS = ["relay.coinos.io", "relay.damus.io", "nos.lol"]
SOMEONE = os.urandom(32).hex()


def nip11(host):
    req = urllib.request.Request(f"https://{host}/", headers={"Accept": "application/nostr+json", "User-Agent": "relay-check"})
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.read(2000).decode(errors="replace")
    except Exception as e:
        return "error " + str(e)[:80]


async def watch(host, label, flt, wait=12):
    start = time.time()
    try:
        async with websockets.connect(f"wss://{host}", open_timeout=8, user_agent_header="relay-check") as ws:
            await ws.send(json.dumps(["REQ", "c", flt]))
            seen = []
            while time.time() - start < wait:
                try:
                    raw = await asyncio.wait_for(ws.recv(), wait - (time.time() - start))
                except asyncio.TimeoutError:
                    break
                msg = json.loads(raw)
                at = round(time.time() - start, 1)
                if msg[0] == "EVENT":
                    seen.append(f"{at}s EVENT kind {msg[2].get('kind')}")
                else:
                    seen.append(f"{at}s {raw[:160]}")
            print(f"{host} | {label}: " + ("; ".join(seen[:8]) or f"nothing in {wait}s"))
    except Exception as e:
        print(f"{host} | {label}: failed {type(e).__name__} {str(e)[:80]}")


async def main():
    for host in RELAYS:
        print(f"{host} | NIP-11: {nip11(host)[:400]}")
        # The answer listener, exactly as Struthio opens it, for a wallet and app that don't exist.
        await watch(host, "listener", {"authors": [SOMEONE], "kinds": [23195], "#p": [SOMEONE]})
        await watch(host, "listener without #p", {"authors": [SOMEONE], "kinds": [23195]})
        await watch(host, "wallet info, any wallet", {"kinds": [13194], "limit": 2})
        await watch(host, "any note", {"kinds": [1], "limit": 1})


asyncio.run(main())
