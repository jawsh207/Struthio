package app.struthio.nostr.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import app.struthio.nostr.net.AppLog
import app.struthio.nostr.net.alternateFamilies
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
    fun tamperedEventsAreRejected() {
        val event = event(Kind.NOTE, emptyList(), "original")
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
    fun zapReceiptValidation() {
        val invoice = "lnbc10u1p3unwfusp5t9r3yymhpfqculx78u027lxspgxcr2n2987mx2j55nnfs95nxnzqpp5"
        val recipient = "a".repeat(64)
        val target = "b".repeat(64)
        val request = event(
            Kind.ZAP_REQUEST,
            listOf(listOf("relays", "wss://r"), listOf("amount", "1000000"), listOf("p", recipient), listOf("e", target)),
            "great note",
        )
        fun receipt(description: String) = event(
            Kind.ZAP_RECEIPT,
            listOf(listOf("p", recipient), listOf("e", target), listOf("bolt11", invoice), listOf("description", description)),
            "",
        )
        val zap = parseZapReceipt(receipt(request.toJson()))!!
        assertEquals(1_000_000L, zap.msats)
        assertEquals(request.pubkey, zap.sender)
        assertEquals(target, zap.target)
        assertEquals("great note", zap.comment)

        val wrongAmount = event(Kind.ZAP_REQUEST, listOf(listOf("amount", "5000"), listOf("p", recipient)), "")
        assertNull(parseZapReceipt(receipt(wrongAmount.toJson())))
        val forged = Event(request.id, request.pubkey, request.createdAt, request.kind, request.tags, "edited", request.sig)
        assertNull(parseZapReceipt(receipt(forged.toJson())))
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

    @Test
    fun reactionsShowAsPurpleHeartByDefault() {
        assertEquals(DEFAULT_REACTION, reactionGlyph("+"))
        assertEquals(DEFAULT_REACTION, reactionGlyph(""))
        assertEquals("\uD83D\uDD25", reactionGlyph("\uD83D\uDD25"))
        assertNull(reactionGlyph("-"))
        assertNull(reactionGlyph(":soapbox:"))
    }

    @Test
    fun encryptedFileMessages() {
        val key = randomBytes(32)
        val nonce = randomBytes(16)
        val plain = "photo bytes".toByteArray()
        val sealed = FileCipher.encrypt(plain, key, nonce)
        assertTrue(FileCipher.decrypt(sealed, key, nonce).contentEquals(plain))
        val tampered = sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertTrue(runCatching { FileCipher.decrypt(tampered, key, nonce) }.isFailure)

        val rumor = Draft(
            "a".repeat(64), 1, Kind.FILE_DM,
            listOf(
                listOf("p", "b".repeat(64)), listOf("file-type", "image/jpeg"),
                listOf("encryption-algorithm", "aes-gcm"), listOf("decryption-key", Hex.encode(key)),
                listOf("decryption-nonce", Hex.encode(nonce)), listOf("x", "c".repeat(64)), listOf("dim", "400x200"),
            ),
            "https://blossom.example/abc",
        )
        val file = parseFileMessage(rumor)!!
        assertEquals(2f, file.aspect)
        assertEquals(file, fileFromJson(fileToJson(file)))
        assertNull(parseFileMessage(Draft(rumor.pubkey, 1, Kind.FILE_DM, listOf(listOf("encryption-algorithm", "other")), rumor.content)))
    }

    @Test
    fun blossomTokenIsScoped() {
        val tags = blossomAuthTags("d".repeat(64), "cdn.example.com", 1000)
        assertEquals("upload", tags.value("t"))
        assertEquals("d".repeat(64), tags.value("x"))
        assertEquals("cdn.example.com", tags.value("server"))
        assertEquals("1300", tags.value("expiration"))
    }

    @Test
    fun readOnlyAccountsCannotSign() {
        val signer = ReadOnlySigner("a".repeat(64))
        val refused = runCatching { kotlinx.coroutines.runBlocking { signer.sign(Draft(signer.pubkey, 1, Kind.NOTE, emptyList(), "x")) } }
        assertTrue(refused.isFailure)
    }

    @Test
    fun trustedNetworkNeedsMoreThanOneVoucher() {
        val me = "m".repeat(64)
        val network = trustedNetwork(me, setOf("a", "b"), listOf(listOf("c", "d", "d"), listOf("c")), 2)
        assertEquals(setOf(me, "a", "b", "c"), network)
    }

    @Test
    fun videosComeFromImetaOrExtension() {
        val e = event(Kind.NOTE, listOf(listOf("imeta", "url https://a.example/clip", "m video/mp4")), "https://a.example/clip https://a.example/b.webm https://a.example/c.png")
        assertEquals(listOf("https://a.example/clip", "https://a.example/b.webm"), noteVideos(e))
        assertEquals(1, noteImages(e).size)
    }

    @Test
    fun commonVideoFormatsAreRecognized() {
        val base = "https://a.example/v"
        val urls = listOf("$base.MP4", "$base.mkv", "$base.mov?x=1", "$base.m3u8", "$base.avi", "$base.webm#t=5", "$base.3gp", "$base.mpd")
        assertEquals(urls, noteVideos(event(Kind.NOTE, emptyList(), urls.joinToString(" "))))
        assertTrue(noteVideos(event(Kind.NOTE, emptyList(), "https://a.example/watch https://a.example/photo.png")).isEmpty())
        val hls = event(Kind.NOTE, listOf(listOf("imeta", "url https://a.example/live", "m application/vnd.apple.mpegurl")), "https://a.example/live")
        assertEquals(listOf("https://a.example/live"), noteVideos(hls))
    }

    @Test
    fun relayDocumentsAreRead() {
        val info = parseRelayInfo(
            """{"name":"Paid","supported_nips":[1,50,50,"x"],"software":"https://github.com/hoytech/strfry.git","version":"1.0",
            "limitation":{"auth_required":true,"max_content_length":64000},
            "fees":{"subscription":[{"amount":5000000,"unit":"msats","period":2592000}],"admission":[{"amount":1000,"unit":"sats"}]}}""",
        )!!
        assertEquals("Paid", info.name)
        assertEquals(listOf(1, 50), info.nips)
        assertEquals("strfry", info.software)
        assertEquals(true, info.authRequired)
        assertNull(info.paymentRequired)
        assertEquals(listOf("To join: 1,000 sats", "Subscription: 5,000 sats every 30 days"), info.fees)
        assertEquals("Paid", info.tag)
        assertEquals(listOf("Longest note (characters)" to "64,000"), info.limits)
        assertEquals("Search", nipName(50))
        assertEquals("NIP-99", nipName(99))
        assertNull(parseRelayInfo("not json"))
        assertNull(parseRelayInfo("{}")!!.tag)
    }

    @Test
    fun encryptedBackupsMatchTheNip() {
        val backup = "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"
        assertEquals(
            "3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683",
            Hex.encode(KeyBackup.decrypt(backup, "nostr")!!),
        )
        assertNull(KeyBackup.decrypt(backup, "wrong"))
    }

    @Test
    fun encryptedBackupsRoundTrip() {
        val secret = ByteArray(32) { (it + 1).toByte() }
        val backup = KeyBackup.encrypt(secret, "ÅΩẛ̣ password", logN = 4)
        assertTrue(backup.startsWith("ncryptsec1"))
        assertTrue(secret.contentEquals(KeyBackup.decrypt(backup, "ÅΩẛ̣ password")))
        assertNull(KeyBackup.decrypt(backup, "other"))
    }

    @Test
    fun scryptMatchesItsPublishedVector() {
        // RFC 7914, section 12, the case with p = 1. Its first 32 bytes.
        val key = KeyBackup.scrypt("pleaseletmein".toByteArray(), "SodiumChloride".toByteArray(), 14)
        assertEquals("7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2", Hex.encode(key))
    }

    private val sampleInvoice = "lnbc1u1p4w62kmsp5h5m8d45r4ruyk8pcwepanf6jhrf90hpuqcekrdg4k0j7afm73vwqpp5d5affsylzaeds72vswa30ndaym03r2ald0dnq3t4m0c7myf9lqsqdpz2d68yat5dp5k7gzjv43k26tkv5s9getnwscqzgq9qyysgqs92lsa4zyk2je54wr48vqywrypzhmjx5hrf97jp84wahxm8x3mrjfz2vjau56j2775tdyfk4jz8mpxuy2f59jw6uwdc22a7y7mufv3gpzldp2m"

    @Test
    fun invoicesAreRead() {
        val invoice = parseInvoice(sampleInvoice)!!
        assertEquals(100_000L, invoice.msats)
        assertEquals("Struthio Receive Test", invoice.memo)
        assertEquals(1793927899L + 3600, invoice.expiresAt)
        assertEquals(invoice, parseInvoice("lightning:" + sampleInvoice.uppercase()))
        assertNull(parseInvoice(sampleInvoice.dropLast(1) + "q"))
    }

    @Test
    fun invoicesInNotesBecomeCards() {
        val parts = parseContent("Pay me: $sampleInvoice thanks")
        assertEquals(1, parts.filterIsInstance<Part.Lightning>().size)
        assertEquals("Pay me: ", (parts.first() as Part.Text).text)
        val broken = "lnbc" + "q".repeat(60)
        assertEquals(listOf<Part>(Part.Text(broken)), parseContent(broken))
    }

    @Test
    fun invoicesCanBeSummarized() {
        assertEquals("Hi Lightning invoice for 100 sats", summarizeInvoices("Hi $sampleInvoice"))
        assertEquals(listOf(100_000L), invoicesIn("Hi $sampleInvoice").map { it.msats })
        assertEquals("Hi", replaceInvoices("Hi $sampleInvoice") { "" }.trim())
        val fake = "lnbc" + "q".repeat(60)
        assertEquals(fake, summarizeInvoices(fake))
    }

    @Test
    fun videoSitesAreEmbedded() {
        fun player(url: String) = videoEmbed(url)?.player
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", player("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10"))
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", player("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", player("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("https://yewtu.be/embed/dQw4w9WgXcQ", player("https://yewtu.be/watch?v=dQw4w9WgXcQ"))
        assertEquals("Invidious · yewtu.be", videoEmbed("https://yewtu.be/watch?v=dQw4w9WgXcQ")?.site)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", videoEmbed("https://youtu.be/dQw4w9WgXcQ")?.thumbnail)
        assertEquals(null, videoEmbed("https://piped.video/watch?v=dQw4w9WgXcQ")?.thumbnail)
        assertEquals("https://piped.video/embed/dQw4w9WgXcQ", player("https://piped.video/watch?v=dQw4w9WgXcQ"))
        assertEquals("https://tilvids.com/videos/embed/9c9de5e8-0a1e-484a-b099-e80766180a6d", player("https://tilvids.com/videos/watch/9c9de5e8-0a1e-484a-b099-e80766180a6d"))
        assertEquals("https://framatube.org/videos/embed/oFAtkg8rf2TyHi3LrubsCa", player("https://framatube.org/w/oFAtkg8rf2TyHi3LrubsCa"))
        assertEquals("https://player.vimeo.com/video/76979871?dnt=1", player("https://vimeo.com/76979871"))
        assertEquals("https://odysee.com/\$/embed/@chan:1/name:2", player("https://odysee.com/@chan:1/name:2"))
        assertNull(player("https://www.youtube.com/@someone"))
        assertNull(player("http://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(player("https://example.com/article"))
    }

    @Test
    fun customEmojiAreFound() {
        val tags = listOf(listOf("emoji", "soapbox", "https://a.example/s.png"), listOf("emoji", "bad code", "https://a.example/b.png"), listOf("emoji", "http", "http://a.example/h.png"))
        val emoji = emojiMap(tags)
        assertEquals(setOf("soapbox"), emoji.keys)
        assertEquals(listOf("Hi " to false, "soapbox" to true, " and :unknown:" to false), splitEmoji("Hi :soapbox: and :unknown:", emoji))
        assertEquals("Alex", stripEmoji("Alex :soapbox:", emoji))
        assertEquals(":soapbox:", customGlyph(":soapbox:", tags))
        assertNull(customGlyph(":missing:", tags))
    }

    private fun pollEvent(vararg tags: List<String>) = Event("a".repeat(64), "b".repeat(64), 100, Kind.POLL, tags.toList(), "Pizza?", "c".repeat(128))

    private fun voteEvent(who: Char, at: Long, vararg choices: String) = Event(
        (who.toString() + at).padEnd(64, '0'), who.toString().repeat(64), at, Kind.POLL_VOTE,
        listOf(listOf("e", "a".repeat(64))) + choices.map { listOf("response", it) }, "", "c".repeat(128),
    )

    @Test
    fun pollsCountOneVotePerPerson() {
        val yes = listOf("option", "y", "Yes")
        val no = listOf("option", "n", "No")
        val poll = parsePoll(pollEvent(yes, no, listOf("endsAt", "500")))!!
        assertFalse(poll.multiple)
        val result = countVotes(
            poll,
            listOf(voteEvent('d', 10, "y"), voteEvent('d', 20, "n"), voteEvent('e', 30, "y", "n"), voteEvent('f', 600, "y"), voteEvent('1', 40, "zzz")),
            "d".repeat(64),
        )
        assertEquals(mapOf("n" to 1, "y" to 1), result.counts)
        assertEquals(2, result.voters)
        assertEquals(setOf("n"), result.mine)
    }

    @Test
    fun multipleChoicePollsCountEachOption() {
        val poll = parsePoll(pollEvent(listOf("option", "y", "Yes"), listOf("option", "n", "No"), listOf("polltype", "multiplechoice")))!!
        val result = countVotes(poll, listOf(voteEvent('d', 10, "y", "n", "y")), null)
        assertEquals(mapOf("y" to 1, "n" to 1), result.counts)
        assertEquals(1, result.voters)
        assertNull(parsePoll(pollEvent(listOf("option", "y", "Yes"))))
    }

    @Test
    fun addressFamiliesTakeTurns() {
        val six = { a: String -> a.startsWith("6") }
        assertEquals(listOf("6a", "4a", "6b", "4b", "6c"), alternateFamilies(listOf("6a", "6b", "6c", "4a", "4b"), six))
        assertEquals(listOf("4a", "6a", "4b"), alternateFamilies(listOf("4a", "4b", "6a"), six))
        assertEquals(listOf("6a", "6b"), alternateFamilies(listOf("6a", "6b"), six))
        assertEquals(emptyList<String>(), alternateFamilies(emptyList<String>(), six))
    }

    @Test
    fun logsLeaveOutThisPhonesAddress() {
        val line = "wss://nos.lol connection: failed to connect to nos.lol/2606:4700:20::1 (port 443) from /2603:7080:3001::d9f6 (port 39612) after 8000ms"
        assertEquals("wss://nos.lol connection: failed to connect to nos.lol/2606:4700:20::1 (port 443) after 8000ms", AppLog.scrub(line))
    }

    @Test
    fun logsNeverHoldKeys() {
        val hex = "ab".repeat(32)
        val out = AppLog.scrub("nsec1qqqqqq $hex nostr+walletconnect://abc?secret=1 wss://nos.lol")
        assertEquals("[hidden] [hidden] [hidden] wss://nos.lol", out)
    }

    @Test
    fun addressesAreRecognized() {
        assertTrue(isNip05Address("bob@example.com"))
        assertTrue(isNip05Address("_@Example.com"))
        assertFalse(isNip05Address("bob"))
        assertFalse(isNip05Address("bob@localhost"))
        assertFalse(isNip05Address("bob@10.0.0.1"))
        assertFalse(isNip05Address("bob@printer.local"))
    }

    @Test
    fun serversCanRevealMedia() {
        assertEquals("video", mediaKind("video/mp4"))
        assertEquals("video", mediaKind("application/vnd.apple.mpegurl"))
        assertEquals("image", mediaKind("image/webp"))
        assertNull(mediaKind("text/html"))
        assertNull(mediaKind("application/pdf"))
    }

    @Test
    fun linkCardsReadOpenGraph() {
        val html = """<html><head><title>Fallback</title><meta property="og:title" content="Real title">
            <meta content="A summary" property='og:description'><meta property="og:image" content="https://a.example/i.png"></head>"""
        val info = parseLinkInfo("https://a.example/page", html)!!
        assertEquals("Real title", info.title)
        assertEquals("A summary", info.description)
        assertEquals("https://a.example/i.png", info.image)
        assertEquals("Only", parseLinkInfo("https://a.example", "<title>Only</title>")!!.title)
        assertNull(parseLinkInfo("https://a.example", "<p>nothing</p>"))
    }

    @Test
    fun previewsStayOffPrivateAddresses() {
        assertTrue(isPreviewable("https://example.com/a"))
        assertFalse(isPreviewable("http://example.com/a"))
        assertFalse(isPreviewable("https://192.168.1.1/admin"))
        assertFalse(isPreviewable("https://router.local/"))
        assertFalse(isPreviewable("https://localhost/"))
    }

    @Test
    fun reportsNameThePersonAndTheNote() {
        val note = event(Kind.NOTE, emptyList())
        assertEquals(listOf(listOf("p", note.pubkey, "spam"), listOf("e", note.id, "spam")), reportTags(note, "spam"))
    }

    private fun event(kind: Int, tags: Tags, content: String = "x"): Event {
        val signer = LocalSigner(Keys.generate())
        return signer.signNow(Draft(signer.pubkey, 1700000000, kind, tags, content))
    }
}
