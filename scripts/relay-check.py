#!/usr/bin/env python3
"""Read-only relay survey: NIP-11 document, connection time, and whether each job works."""
import asyncio, json, time, urllib.request
import websockets

KEY = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
GENERAL = ["relay.damus.io", "nos.lol", "relay.primal.net", "relay.snort.social", "nostr.mom", "offchain.pub"]
MESSAGING = ["relay.0xchat.com", "auth.nostr1.com", "inbox.nostr.wine", "nip17.com", "relay.damus.io", "nos.lol"]
SEARCH = ["relay.nostr.band", "relay.noswhere.com", "search.nos.today", "antiprimal.net", "relay.ditto.pub"]
LOOKUP = ["purplepag.es", "user.kindpag.es", "indexer.coracle.social", "directory.yabu.me"]


def info(host):
    req = urllib.request.Request(f"https://{host}/", headers={"Accept": "application/nostr+json", "User-Agent": "relay-check"})
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            doc = json.loads(r.read(200_000))
        return doc, round((time.time() - start) * 1000)
    except Exception as e:
        return {"error": str(e)[:50]}, None


async def ask(host, flt, wait=6):
    """Sends one REQ. Returns events seen, how it ended, and milliseconds to connect."""
    start = time.time()
    try:
        async with websockets.connect(f"wss://{host}", open_timeout=8, max_size=2**22, user_agent_header="relay-check") as ws:
            connect = round((time.time() - start) * 1000)
            await ws.send(json.dumps(["REQ", "check", flt]))
            events, end, auth = 0, "timeout", False
            deadline = time.time() + wait
            while time.time() < deadline:
                try:
                    msg = json.loads(await asyncio.wait_for(ws.recv(), deadline - time.time()))
                except asyncio.TimeoutError:
                    break
                if msg[0] == "EVENT":
                    events += 1
                elif msg[0] == "AUTH":
                    auth = True
                elif msg[0] == "EOSE":
                    end = "eose"
                    break
                elif msg[0] == "CLOSED":
                    end = "closed:" + str(msg[2] if len(msg) > 2 else "")[:40]
                    break
                elif msg[0] == "NOTICE":
                    end = "notice:" + str(msg[1])[:40]
            return events, end, connect, auth
    except Exception as e:
        return 0, "fail:" + type(e).__name__ + " " + str(e)[:40], None, False


async def row(host, flt, label):
    doc, ms = await asyncio.to_thread(info, host)
    events, end, connect, auth = await ask(host, flt)
    lim = doc.get("limitation") or {}
    fees = doc.get("fees") or {}
    nips = doc.get("supported_nips") or []
    print(
        f"{label:9} {host:24} ws={connect}ms nip11={ms}ms events={events} end={end} authmsg={auth} "
        f"auth_req={lim.get('auth_required')} pay_req={lim.get('payment_required')} restricted={lim.get('restricted_writes')} "
        f"fees={sorted(fees)} nip42={42 in nips} nip50={50 in nips} sw={str(doc.get('software'))[-24:]} err={doc.get('error')}"
    )


async def main():
    jobs = [row(h, {"kinds": [1], "limit": 5}, "general") for h in GENERAL]
    jobs += [row(h, {"kinds": [1059], "#p": [KEY], "limit": 5}, "messaging") for h in MESSAGING]
    jobs += [row(h, {"kinds": [1], "search": "bitcoin", "limit": 5}, "search") for h in SEARCH]
    jobs += [row(h, {"kinds": [0], "search": "fiatjaf", "limit": 5}, "people") for h in SEARCH]
    jobs += [row(h, {"kinds": [10002], "authors": [KEY], "limit": 1}, "lookup") for h in LOOKUP]
    await asyncio.gather(*jobs)


asyncio.run(main())
