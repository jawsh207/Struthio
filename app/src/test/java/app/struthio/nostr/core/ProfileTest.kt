package app.struthio.nostr.core

import app.struthio.nostr.net.Bech32Lnurl
import app.struthio.nostr.net.lightningLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    private val signer = LocalSigner(Keys.generate())
    private fun event(kind: Int, tags: Tags = emptyList(), content: String = "") =
        signer.signNow(Draft(signer.pubkey, 1700000000, kind, tags, content))

    @Test
    fun tabsSplitWhatSomeonePosted() {
        val other = "a".repeat(64)
        val post = event(Kind.NOTE, content = "Hello")
        val reply = event(Kind.NOTE, listOf(listOf("e", other, "", "root"), listOf("p", other)), "Hi")
        val comment = event(Kind.COMMENT, listOf(listOf("E", other), listOf("K", "1"), listOf("e", other), listOf("k", "1")), "Nice")
        val boost = event(Kind.REPOST, listOf(listOf("e", other)))
        val genericBoost = event(Kind.GENERIC_REPOST, listOf(listOf("e", other), listOf("k", "20")))
        val picture = event(Kind.PICTURE, listOf(listOf("imeta", "url https://a.example/p.jpg", "m image/jpeg")))
        val withImage = event(Kind.NOTE, content = "Look https://a.example/x.png")
        val withVideo = event(Kind.NOTE, content = "Watch https://a.example/v.mp4")

        assertTrue(ProfileTab.POSTS.shows(post))
        assertTrue(ProfileTab.POSTS.shows(picture))
        assertFalse(ProfileTab.POSTS.shows(reply))
        assertFalse(ProfileTab.POSTS.shows(boost))
        assertTrue(ProfileTab.REPLIES.shows(reply))
        assertTrue(ProfileTab.REPLIES.shows(comment))
        assertFalse(ProfileTab.REPLIES.shows(post))
        assertTrue(ProfileTab.BOOSTS.shows(boost))
        assertTrue(ProfileTab.BOOSTS.shows(genericBoost))
        assertTrue(ProfileTab.GALLERY.shows(picture))
        assertTrue(ProfileTab.GALLERY.shows(withImage))
        assertTrue(galleryMedia(withVideo).single().video)
        assertFalse(ProfileTab.GALLERY.shows(post))
    }

    @Test
    fun websitesOpenOnTheWeb() {
        assertEquals("https://example.com", websiteUrl("example.com"))
        assertEquals("http://example.com/me", websiteUrl(" http://example.com/me "))
        assertNull(websiteUrl("javascript:alert(1)"))
        assertNull(websiteUrl("not a site"))
        assertNull(websiteUrl("localhost"))
        assertEquals("example.com/me", websiteLabel("https://www.example.com/me/"))
    }

    @Test
    fun lightningAddressesReadAsNameAtDomain() {
        assertEquals("jawsh@rizful.com", lightningLabel("Jawsh@Rizful.com", null))
        val lnurl = Bech32Lnurl.encode("https://getalby.com/.well-known/lnurlp/bob")
        assertEquals("bob@getalby.com", lightningLabel(null, lnurl))
        assertEquals("bob@getalby.com", lightningLabel(null, "lightning:" + lnurl.uppercase()))
        assertEquals("pay.example.com", lightningLabel(null, Bech32Lnurl.encode("https://pay.example.com/api/v1/lnurl/abc")))
        assertNull(lightningLabel("@bad", null))
        assertNull(lightningLabel(null, null))
    }
}
