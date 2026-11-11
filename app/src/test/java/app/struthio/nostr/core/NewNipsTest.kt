package app.struthio.nostr.core

import app.struthio.nostr.data.RemoteSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewNipsTest {
    private val signer = LocalSigner(Keys.generate())

    private fun event(kind: Int, tags: Tags, content: String = "", at: Long = 1700000000) =
        signer.signNow(Draft(signer.pubkey, at, kind, tags, content))

    // NIP-68

    @Test
    fun picturePostsShowTheirImetaPictures() {
        val e = event(
            Kind.PICTURE,
            listOf(
                listOf("title", "Coast"),
                listOf("imeta", "url https://a.example/1.jpg", "m image/jpeg", "dim 3024x4032"),
                listOf("imeta", "url https://a.example/2.png", "m image/png"),
                // Not a type NIP-68 accepts.
                listOf("imeta", "url https://a.example/3.svg", "m image/svg+xml"),
            ),
            "A walk",
        )
        val pics = pictures(e)
        assertEquals(listOf("https://a.example/1.jpg", "https://a.example/2.png"), pics.map { it.url })
        assertEquals(0.75f, pics[0].aspect!!, 0.001f)
        assertEquals("Coast", titleOf(e))
    }

    // NIP-71

    @Test
    fun videoPostsPreferMp4AndKnowShortOnes() {
        val e = event(
            Kind.VIDEO,
            listOf(
                listOf("title", "Talk"),
                listOf("imeta", "url https://v.example/720.m3u8", "m application/x-mpegURL", "dim 1280x720"),
                listOf("imeta", "url https://v.example/1080.mp4", "m video/mp4", "dim 1920x1080", "image https://v.example/1080.jpg", "duration 65.5"),
                listOf("imeta", "url https://v.example/en.mp3", "m audio/mp3"),
            ),
        )
        val video = videoPost(e)!!
        assertEquals("https://v.example/1080.mp4", video.url)
        assertEquals("https://v.example/1080.jpg", video.thumbnail)
        assertEquals(65.5, video.seconds!!, 0.001)
        assertFalse(video.portrait)
        val short = event(Kind.SHORT_VIDEO, listOf(listOf("imeta", "url https://v.example/s.mp4", "m video/mp4")))
        assertTrue(videoPost(short)!!.portrait)
        // Editable videos may give the length as its own tag.
        val editable = event(Kind.EDITABLE_VIDEO, listOf(listOf("d", "x"), listOf("imeta", "url https://v.example/e.mp4", "m video/mp4"), listOf("duration", "30")))
        assertEquals(30.0, videoPost(editable)!!.seconds!!, 0.001)
        assertEquals("1:05", formatDuration(65.5))
        assertEquals("1:02:09", formatDuration(3729.0))
    }

    // NIP-23

    @Test
    fun articlesReadTheirMetadataAndAddress() {
        val e = event(
            Kind.ARTICLE,
            listOf(listOf("d", "lorem"), listOf("title", "Lorem"), listOf("summary", "Short"), listOf("image", "https://a.example/i.jpg"), listOf("published_at", "1296962229")),
            "# Heading\n\nBody",
        )
        val info = article(e)!!
        assertEquals("Lorem", info.title)
        assertEquals("Short", info.summary)
        assertEquals(1296962229L, info.publishedAt)
        assertEquals("30023:${signer.pubkey}:lorem", addressOf(e))
        // Without a title, the first line stands in.
        assertEquals("Heading", article(event(Kind.ARTICLE, listOf(listOf("d", "y")), "# Heading\n\nBody"))!!.title)
    }

    @Test
    fun commentsOnArticlesNameTheirAddress() {
        val a = event(Kind.ARTICLE, listOf(listOf("d", "lorem"), listOf("title", "Lorem")), "Body")
        val (kind, tags) = replyTags(a, "wss://r.example")
        assertEquals(Kind.COMMENT, kind)
        assertTrue(tags.any { it.getOrNull(0) == "A" && it.getOrNull(1) == addressOf(a) })
        assertTrue(tags.any { it.getOrNull(0) == "E" && it.getOrNull(1) == a.id })
        assertTrue(tags.any { it.getOrNull(0) == "K" && it.getOrNull(1) == "30023" })
        // A comment naming its root only by address still finds its thread.
        val byAddress = event(Kind.COMMENT, listOf(listOf("A", addressOf(a)), listOf("K", "30023"), listOf("a", addressOf(a)), listOf("k", "30023")), "Nice")
        assertEquals(addressOf(a), threadRef(byAddress).root)
    }

    @Test
    fun markdownCoversArticleBasics() {
        val blocks = Markdown.parse(
            """
            # Title

            Some **bold** and *italic* text, with `code` and a [link](https://a.example).
            It continues on the next line.

            > A quote

            - one
            - two
              - nested

            1. first

            ```
            let x = 1
            ```

            ![alt](https://a.example/p.png)

            ---
            """.trimIndent(),
        )
        assertTrue(blocks[0] is Markdown.Block.Heading)
        val paragraph = blocks[1] as Markdown.Block.Paragraph
        assertTrue(paragraph.text.any { it.bold && it.text == "bold" })
        assertTrue(paragraph.text.any { it.italic && it.text == "italic" })
        assertTrue(paragraph.text.any { it.code && it.text == "code" })
        assertTrue(paragraph.text.any { it.link == "https://a.example" && it.text == "link" })
        // Lines of one paragraph join up, as articles mustn't hard-wrap.
        assertTrue(paragraph.text.joinToString("") { it.text }.contains("text, with"))
        assertTrue(paragraph.text.last().text.endsWith("next line."))
        assertTrue(blocks[2] is Markdown.Block.Quote)
        assertEquals(1, (blocks[5] as Markdown.Block.Item).depth)
        assertEquals("1.", (blocks[6] as Markdown.Block.Item).marker)
        assertEquals("let x = 1", (blocks[7] as Markdown.Block.Code).text)
        assertEquals("https://a.example/p.png", (blocks[8] as Markdown.Block.Image).url)
        assertEquals(Markdown.Block.Rule, blocks[9])
    }

    @Test
    fun markdownKeepsSnakeCaseAndReadsNostrReferenceLinks() {
        val spans = Markdown.inline("snake_case_name stays and [ipsum][nostr:nevent1abc] links")
        assertTrue(spans.joinToString("") { it.text }.contains("snake_case_name"))
        assertTrue(spans.none { it.italic })
        assertEquals("nostr:nevent1abc", spans.first { it.link != null }.link)
        // HTML isn't allowed in articles: it stays visible text, never markup.
        assertEquals("<b>x</b>", Markdown.inline("<b>x</b>").joinToString("") { it.text })
    }

    // NIP-38

    @Test
    fun statusesClearWhenEmptyAndEndWhenExpired() {
        val general = event(Kind.STATUS, listOf(listOf("d", "general"), listOf("r", "https://nostr.world")), "Hiking")
        assertEquals(Status("Hiking", false, "https://nostr.world"), parseStatus(general))
        assertNull(parseStatus(event(Kind.STATUS, listOf(listOf("d", "general")), "")))
        val song = event(Kind.STATUS, listOf(listOf("d", "music"), listOf("expiration", "1700000100")), "Song")
        assertTrue(parseStatus(song, now = 1700000050)!!.music)
        assertNull(parseStatus(song, now = 1700000200))
    }

    // NIP-39

    @Test
    fun linkedAccountsParseAndPointAtTheirProofs() {
        val e = event(
            Kind.IDENTITIES,
            listOf(
                listOf("i", "github:semisol", "9721ce4ee4fceb91c9711ca2a6c9a5ab"),
                listOf("i", "mastodon:bitcoinhackers.org/@semisol", "109775066355589974"),
                listOf("i", "telegram:1087295469", "nostrdirectory/770"),
                listOf("i", "bad:name:extra"),
                listOf("i", "nocolon", "x"),
            ),
        )
        val list = parseIdentities(e)
        assertEquals(3, list.size)
        assertEquals("https://gist.github.com/semisol/9721ce4ee4fceb91c9711ca2a6c9a5ab", list[0].proofUrl)
        assertEquals("https://bitcoinhackers.org/@semisol/109775066355589974", list[1].proofUrl)
        assertEquals("https://t.me/nostrdirectory/770", list[2].proofUrl)
        assertEquals(listOf("i", "github:semisol", "9721ce4ee4fceb91c9711ca2a6c9a5ab"), list[0].tag())
    }

    // NIP-46

    @Test
    fun bunkerLinksParse() {
        val remote = Keys.publicKey(Keys.generate())
        val b = RemoteSigner.parseBunker("bunker://$remote?relay=wss%3A%2F%2Frelay.nsec.app&relay=wss://nos.lol&secret=abc123")!!
        assertEquals(remote, b.remote)
        assertEquals(listOf("wss://relay.nsec.app", "wss://nos.lol"), b.relays)
        assertEquals("abc123", b.secret)
        assertNull(RemoteSigner.parseBunker("bunker://notakey?relay=wss://nos.lol"))
        assertNull(RemoteSigner.parseBunker("bunker://$remote"))
        assertNotNull(RemoteSigner.parseBunker("bunker://$remote?relay=wss://nos.lol"))
    }

    @Test
    fun nostrConnectLinksCarryWhatSignersNeed() {
        val client = Keys.publicKey(Keys.generate())
        val uri = RemoteSigner.nostrConnectUri(client, listOf("wss://relay.nsec.app"), "s3cret")
        assertTrue(uri.startsWith("nostrconnect://$client?"))
        assertTrue("relay=wss%3A%2F%2Frelay.nsec.app" in uri)
        assertTrue("secret=s3cret" in uri)
        assertTrue("name=Struthio" in uri)
        assertTrue("perms=sign_event%2Cnip44_encrypt%2Cnip44_decrypt" in uri)
    }

    @Test
    fun feedsIncludeTheNewPostKinds() {
        listOf(Kind.PICTURE, Kind.VIDEO, Kind.SHORT_VIDEO, Kind.EDITABLE_VIDEO, Kind.EDITABLE_SHORT_VIDEO, Kind.ARTICLE).forEach {
            assertTrue("kind $it", it in Kind.POSTS)
        }
    }
}
