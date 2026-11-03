package app.struthio.nostr.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val vectors by lazy {
        JSONObject(javaClass.classLoader!!.getResource("nip44.vectors.json")!!.readText()).getJSONObject("v2")
    }

    @Test
    fun nip44ConversationKeys() {
        val list = vectors.getJSONObject("valid").getJSONArray("get_conversation_key")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            assertEquals(
                v.getString("conversation_key"),
                Hex.encode(Nip44.conversationKey(Hex.decode(v.getString("sec1")), v.getString("pub2"))),
            )
        }
    }

    @Test
    fun nip44EncryptDecrypt() {
        val list = vectors.getJSONObject("valid").getJSONArray("encrypt_decrypt")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val key = Hex.decode(v.getString("conversation_key"))
            val plaintext = v.getString("plaintext")
            val payload = v.getString("payload")
            assertEquals(payload, Nip44.encrypt(plaintext, key, Hex.decode(v.getString("nonce"))))
            assertEquals(plaintext, Nip44.decrypt(payload, key))
        }
    }

    @Test
    fun nip44PaddedLengths() {
        val list = vectors.getJSONObject("valid").getJSONArray("calc_padded_len")
        for (i in 0 until list.length()) {
            val pair = list.getJSONArray(i)
            assertEquals(pair.getLong(1), Nip44.paddedLength(pair.getLong(0)))
        }
    }

    @Test
    fun nip44RejectsInvalidPayloads() {
        val list = vectors.getJSONObject("invalid").getJSONArray("decrypt")
        for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val rejected = try {
                Nip44.decrypt(v.getString("payload"), Hex.decode(v.getString("conversation_key")))
                false
            } catch (_: Exception) {
                true
            }
            assertTrue(v.optString("note"), rejected)
        }
    }

    @Test
    fun nip19FromSpec() {
        val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", Nip19.npub(hex))
        assertEquals(Entity.Profile(hex), Nip19.decode("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"))
        val nprofile = Nip19.decode(
            "nprofile1qqsrhuxx8l9ex335q7he0f09aej04zpazpl0ne2cgukyawd24mayt8gpp4mhxue69uhhytnc9e3k7mgpz4mhxue69uhkg6nzv9ejuumpv34kytnrdaksjlyr9p",
        ) as Entity.Profile
        assertEquals(hex, nprofile.pubkey)
        assertEquals(listOf("wss://r.x.com", "wss://djbas.sadkb.com"), nprofile.relays)
    }

    @Test
    fun neventRoundTrip() {
        val id = "9ae37aa68f48645127299e9453eb5d908a0cbb6058ff340d528ed4d37c8994fb"
        val author = "97c70a44366a6535c145b333f973ea86dfdc2d7a99da618c40c64705ad98e322"
        val decoded = Nip19.decode(Nip19.nevent(id, listOf("wss://relay.example.com"), author)) as Entity.Note
        assertEquals(id, decoded.id)
        assertEquals(author, decoded.author)
        assertEquals(listOf("wss://relay.example.com"), decoded.relays)
    }

    @Test
    fun zapRequestIdAndSignatureFromSpec() {
        val event = Event.parse(
            """{"pubkey":"97c70a44366a6535c145b333f973ea86dfdc2d7a99da618c40c64705ad98e322","content":"","id":"d9cc14d50fcb8c27539aacf776882942c1a11ea4472f8cdec1dea82fab66279d","created_at":1674164539,"sig":"77127f636577e9029276be060332ea565deaf89ff215a494ccff16ae3f757065e2bc59b2e8c113dd407917a010b3abd36c8d7ad84c0e3ab7dab3a0b0caa9835d","kind":9734,"tags":[["e","3624762a1274dd9636e0c552b53086d70bc88c165bc4dc0f9e836a1eaf86c3b8"],["p","32e1827635450ebb3c5a7d12c1f8e7b2b514439ac10a67eef3d9fd9c5c68e245"],["relays","wss://relay.damus.io","wss://nostr-relay.wlvs.space","wss://nostr.fmt.wiz.biz","wss://relay.nostr.bg","wss://nostr.oxtr.dev","wss://nostr.v0l.io","wss://brb.io","wss://nostr.bitcoiner.social","ws://monad.jb55.com:8080","wss://relay.snort.social"]]}""",
        )!!
        assertTrue(event.hasValidId())
        assertTrue(event.isValid())
        assertFalse(Event(event.id, event.pubkey, event.createdAt, event.kind, event.tags, "tampered", event.sig).isValid())
    }

    @Test
    fun signAndVerify() {
        val signer = LocalSigner(Keys.generate())
        val event = signer.signNow(Draft(signer.pubkey, 1700000000, Kind.NOTE, listOf(listOf("t", "x\"y\n")), "héllo\n\"🦤\""))
        assertTrue(event.isValid())
        assertEquals(event.id, Event.parse(event.toJson())!!.id)
        assertTrue(Event.parse(event.toJson())!!.isValid())
    }

    @Test
    fun giftWrapExampleFromNip17() {
        val recipient = (Nip19.decode("nsec12ywtkplvyq5t6twdqwwygavp5lm4fhuang89c943nf2z92eez43szvn4dt") as Entity.Secret).key
        val wrap = Event.parse(
            javaClass.classLoader!!.getResource("nip17-wrap.json")!!.readText(),
        )!!
        assertTrue(wrap.isValid())
        val sealJson = Nip44.decrypt(wrap.content, Nip44.conversationKey(recipient, wrap.pubkey))
        val seal = Event.parse(sealJson)!!
        assertEquals(Kind.SEAL, seal.kind)
        assertTrue(seal.isValid())
        val rumor = Event.parseRumor(Nip44.decrypt(seal.content, Nip44.conversationKey(recipient, seal.pubkey)))!!
        assertEquals(seal.pubkey, rumor.pubkey)
        assertEquals(Kind.DM, rumor.kind)
        assertEquals("Hola, que tal?", rumor.content)
    }

    @Test
    fun bolt11Amounts() {
        assertEquals(50_000L, bolt11Msats("lnbc500n1abc"))
        assertEquals(1_000_000_000L, bolt11Msats("lnbc10m1abc"))
        assertEquals(1_000_000L, bolt11Msats("lnbc10u1p3unwfusp5"))
        assertEquals(100_000_000_000L, bolt11Msats("lnbc11abc"))
        assertEquals(1L, bolt11Msats("lnbc10p1abc"))
        assertNull(bolt11Msats("lnbc1abc"))
        assertNull(bolt11Msats("notaninvoice"))
    }

    @Test
    fun zapReceiptFromSpec() {
        val request = """{"pubkey":"97c70a44366a6535c145b333f973ea86dfdc2d7a99da618c40c64705ad98e322","content":"","id":"d9cc14d50fcb8c27539aacf776882942c1a11ea4472f8cdec1dea82fab66279d","created_at":1674164539,"sig":"77127f636577e9029276be060332ea565deaf89ff215a494ccff16ae3f757065e2bc59b2e8c113dd407917a010b3abd36c8d7ad84c0e3ab7dab3a0b0caa9835d","kind":9734,"tags":[["e","3624762a1274dd9636e0c552b53086d70bc88c165bc4dc0f9e836a1eaf86c3b8"],["p","32e1827635450ebb3c5a7d12c1f8e7b2b514439ac10a67eef3d9fd9c5c68e245"],["relays","wss://relay.damus.io","wss://nostr-relay.wlvs.space","wss://nostr.fmt.wiz.biz","wss://relay.nostr.bg","wss://nostr.oxtr.dev","wss://nostr.v0l.io","wss://brb.io","wss://nostr.bitcoiner.social","ws://monad.jb55.com:8080","wss://relay.snort.social"]]}"""
        val receipt = Event(
            "67b48a14fb66c60c8f9070bdeb37afdfcc3d08ad01989460448e4081eddda446",
            "9630f464cca6a5147aa8a35f0bcdd3ce485324e732fd39e09233b1d848238f31",
            1674164545, Kind.ZAP_RECEIPT,
            listOf(
                listOf("p", "32e1827635450ebb3c5a7d12c1f8e7b2b514439ac10a67eef3d9fd9c5c68e245"),
                listOf("e", "3624762a1274dd9636e0c552b53086d70bc88c165bc4dc0f9e836a1eaf86c3b8"),
                listOf("bolt11", "lnbc10u1p3unwfusp5t9r3yymhpfqculx78u027lxspgxcr2n2987mx2j55nnfs95nxnzqpp5"),
                listOf("description", request),
            ),
            "", "",
        )
        val zap = parseZapReceipt(receipt)!!
        assertEquals(1_000_000L, zap.msats)
        assertEquals("97c70a44366a6535c145b333f973ea86dfdc2d7a99da618c40c64705ad98e322", zap.sender)
    }

    @Test
    fun threadMarkersAndReplies() {
        val root = event(Kind.NOTE, emptyList())
        val (kind, firstTags) = replyTags(root, "wss://r")
        assertEquals(Kind.NOTE, kind)
        assertEquals(listOf("e", root.id, "wss://r", "root", root.pubkey), firstTags[0])
        val reply = event(Kind.NOTE, firstTags)
        assertEquals(ThreadRef(root.id, root.id, root.pubkey, root.pubkey), threadRef(reply))
        val (_, nested) = replyTags(reply, "wss://r")
        val second = event(Kind.NOTE, nested)
        val ref = threadRef(second)
        assertEquals(root.id, ref.root)
        assertEquals(reply.id, ref.parent)
        assertTrue(nested.none { it[0] == "e" && it.size < 4 })
    }

    @Test
    fun commentsUseNip22ForOtherKinds() {
        val article = event(30023, emptyList())
        val (kind, tags) = replyTags(article, "wss://r")
        assertEquals(Kind.COMMENT, kind)
        assertEquals(article.id, tags.value("E"))
        assertEquals("30023", tags.value("K"))
        assertEquals("30023", tags.value("k"))
        val comment = event(Kind.COMMENT, tags)
        val (nextKind, nextTags) = replyTags(comment, "wss://r")
        assertEquals(Kind.COMMENT, nextKind)
        assertEquals(article.id, nextTags.value("E"))
        assertEquals(comment.id, nextTags.value("e"))
        assertEquals("1111", nextTags.value("k"))
    }

    @Test
    fun contentParsingAndTags() {
        val npub = Nip19.npub("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d")
        val (content, tags) = prepareContent("hi $npub, see https://a.example/x.png. #Nostr")
        assertTrue(content.contains("nostr:$npub"))
        assertTrue(tags.contains(listOf("p", "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d")))
        assertTrue(tags.contains(listOf("t", "nostr")))
        val parts = parseContent(content)
        assertTrue(parts.any { it is Part.Link && it.url == "https://a.example/x.png" })
        val quote = Nip19.note("9ae37aa68f48645127299e9453eb5d908a0cbb6058ff340d528ed4d37c8994fb")
        assertEquals("q", prepareContent("look nostr:$quote").second.first()[0])
    }

    @Test
    fun imetaDecidesMedia() {
        val e = event(Kind.NOTE, listOf(listOf("imeta", "url https://a.example/p", "m image/jpeg", "dim 400x200")), "x https://a.example/p y https://a.example/v.png")
        val media = noteImages(e)
        assertEquals(2, media.size)
        assertEquals(2f, media[0].aspect)
    }

    @Test
    fun nwcUriFromSpec() {
        val uri = NwcUri.parse("nostr+walletconnect://b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4?relay=wss%3A%2F%2Frelay.damus.io&secret=71a8c14c1407c113601079c4302dab36460f0ccd0ad506f1f2dc73b5100e4f3c")
        assertNotNull(uri)
        assertEquals("b889ff5b1513b641e2a139f661a661364979c5beee91842f8f0ef42ab558e9d4", uri!!.wallet)
        assertEquals(listOf("wss://relay.damus.io"), uri.relays)
        assertNull(NwcUri.parse("https://example.com"))
    }

    @Test
    fun relayListAndCover() {
        val list = parseRelayList(event(Kind.RELAYS, listOf(listOf("r", "wss://A.example/"), listOf("r", "wss://b.example", "write"), listOf("r", "wss://c.example", "read"), listOf("r", "http://bad"))))
        assertEquals(listOf("wss://a.example", "wss://b.example"), list.write)
        assertEquals(listOf("wss://a.example", "wss://c.example"), list.read)
        val cover = chooseRelays(listOf("a", "b", "c"), { if (it == "c") listOf("r2") else listOf("r1") }, 8, 1)
        assertEquals(setOf("r1", "r2"), cover.keys)
    }

    @Test
    fun zapSplitWeights() {
        val a = "a".repeat(64)
        val b = "b".repeat(64)
        val c = "c".repeat(64)
        val e = event(Kind.NOTE, listOf(listOf("zap", a, "wss://r", "1"), listOf("zap", b, "wss://r", "3"), listOf("zap", c, "wss://r")))
        assertEquals(listOf(a to 0.25, b to 0.75), zapSplits(e))
    }

    private fun event(kind: Int, tags: Tags, content: String = "x"): Event {
        val signer = LocalSigner(Keys.generate())
        return signer.signNow(Draft(signer.pubkey, 1700000000, kind, tags, content))
    }
}
