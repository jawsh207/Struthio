#!/usr/bin/env python3
"""Read-only relay survey: NIP-11 document, connection time, and whether each job works."""
import asyncio, hashlib, json, os, time, urllib.request
import websockets

KEY = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
GENERAL = ["relay.damus.io", "nos.lol", "relay.primal.net", "relay.snort.social", "nostr.mom", "offchain.pub"]
MESSAGING = ["relay.damus.io", "nos.lol", "relay.primal.net", "nostr.mom", "offchain.pub", "relay.snort.social"]
SEARCH = ["search.nos.today", "relay.ditto.pub", "relay.noswhere.com", "nostr.wine", "relay.azzamo.net", "nostrja-kari-nip50.heguro.com", "nfrelay.app", "relay.nostr.watch", "nostr.polyserv.xyz"]
SIGN_IN = ["relay.damus.io", "auth.nostr1.com", "nip17.com", "nos.lol", "relay.primal.net"]
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


P = 2**256 - 2**32 - 977
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def add(a, b):
    if a is None or b is None:
        return a or b
    if a[0] == b[0] and a[1] != b[1]:
        return None
    m = (3 * a[0] * a[0] * pow(2 * a[1], -1, P) if a == b else (b[1] - a[1]) * pow(b[0] - a[0], -1, P)) % P
    x = (m * m - a[0] - b[0]) % P
    return x, (m * (a[0] - x) - a[1]) % P


def mul(k, point=G):
    out = None
    while k:
        if k & 1:
            out = add(out, point)
        point, k = add(point, point), k >> 1
    return out


def tagged(tag, data):
    h = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(h + h + data).digest()


def schnorr(secret, msg):
    """BIP-340, enough for a throwaway key in this check."""
    d = int.from_bytes(secret, "big") % N
    p = mul(d)
    d = d if p[1] % 2 == 0 else N - d
    px = p[0].to_bytes(32, "big")
    t = bytes(a ^ b for a, b in zip(d.to_bytes(32, "big"), tagged("BIP0340/aux", bytes(32))))
    k = int.from_bytes(tagged("BIP0340/nonce", t + px + msg), "big") % N
    r = mul(k)
    k = k if r[1] % 2 == 0 else N - k
    rx = r[0].to_bytes(32, "big")
    e = int.from_bytes(tagged("BIP0340/challenge", rx + px + msg), "big") % N
    return px, rx + ((k + e * d) % N).to_bytes(32, "big")


def signed_auth(secret, relay, challenge):
    """NIP-42 sign-in from a throwaway key. Relays check it and never store it."""
    pubkey = schnorr(secret, bytes(32))[0].hex()
    created, tags = int(time.time()), [["relay", f"wss://{relay}"], ["challenge", challenge]]
    body = json.dumps([0, pubkey, created, 22242, tags, ""], separators=(",", ":"), ensure_ascii=False)
    digest = hashlib.sha256(body.encode()).digest()
    return {"id": digest.hex(), "pubkey": pubkey, "created_at": created, "kind": 22242, "tags": tags, "content": "", "sig": schnorr(secret, digest)[1].hex()}


async def sign_in(host):
    secret = os.urandom(32)
    flt = {"kinds": [1059], "#p": [KEY], "limit": 1}
    try:
        async with websockets.connect(f"wss://{host}", open_timeout=8, max_size=2**22, user_agent_header="relay-check") as ws:
            await ws.send(json.dumps(["REQ", "check", flt]))
            challenge, steps = None, []
            deadline = time.time() + 12
            while time.time() < deadline:
                try:
                    msg = json.loads(await asyncio.wait_for(ws.recv(), deadline - time.time()))
                except asyncio.TimeoutError:
                    break
                if msg[0] == "AUTH":
                    challenge = msg[1]
                    await ws.send(json.dumps(["AUTH", signed_auth(secret, host, challenge)]))
                    steps.append("sent-auth")
                elif msg[0] == "OK":
                    steps.append(f"OK {msg[2]} {str(msg[3])[:70]}")
                    if msg[2]:
                        await ws.send(json.dumps(["REQ", "again", flt]))
                    else:
                        break
                elif msg[0] in ("CLOSED", "EOSE", "NOTICE"):
                    steps.append(f"{msg[0]} {str(msg[-1])[:60]}")
                    if msg[0] == "EOSE":
                        break
            print(f"signin    {host:24} {' | '.join(steps)}")
    except Exception as e:
        print(f"signin    {host:24} fail {type(e).__name__} {str(e)[:60]}")


async def main():
    await asyncio.gather(*[sign_in(h) for h in SIGN_IN])
    jobs = [row(h, {"kinds": [1], "limit": 5}, "general") for h in GENERAL]
    jobs += [row(h, {"kinds": [1059], "#p": [KEY], "limit": 5}, "messaging") for h in MESSAGING]
    jobs += [row(h, {"kinds": [1], "search": "bitcoin", "limit": 5}, "search") for h in SEARCH]
    jobs += [row(h, {"kinds": [0], "search": "fiatjaf", "limit": 5}, "people") for h in SEARCH]
    jobs += [row(h, {"kinds": [10002], "authors": [KEY], "limit": 1}, "lookup") for h in LOOKUP]
    jobs += [row(h, {"kinds": [0], "limit": 1}, "plain") for h in SEARCH + ["auth.nostr1.com", "purplepag.es"]]
    await asyncio.gather(*jobs)


asyncio.run(main())
