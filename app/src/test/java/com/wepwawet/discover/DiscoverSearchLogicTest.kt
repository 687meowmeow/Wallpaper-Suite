package com.wepwawet.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.25: the Discover search merge's pure logic — canonical image ids
 * (DiscoverDedup), host-health ranking (DiscoverSourceHealth), and the
 * Nyanpasu quality scoring (scoreImageVariant via DiscoverSearchScore).
 *
 * DiscoverSearch itself needs Android (OkHttp/Context); the quality scorer
 * was extracted to a pure object for exactly this test.
 */
class DiscoverSearchLogicTest {

    // ── Canonical ids ───────────────────────────────────────────────────

    @Test
    fun `pixiv size variants and proxies collapse to one canonical id`() {
        val a = "https://i.pximg.net/img-master/img/2020/01/01/00/00/00/12345678_p0_master1200.jpg"
        val b = "https://i.pximg.net/img-original/img/2020/01/01/00/00/00/12345678_p0.png"
        val c = "https://i.pixiv.re/img-master/img/2020/01/01/00/00/00/12345678_p0_square1200.jpg"
        val d = "https://pixiv.yuki.sh/img-master/img/2020/01/01/00/00/00/12345678_p0.jpg?size=x"
        assertEquals(DiscoverDedup.canonicalId(a), DiscoverDedup.canonicalId(b))
        assertEquals(DiscoverDedup.canonicalId(a), DiscoverDedup.canonicalId(c))
        assertEquals(DiscoverDedup.canonicalId(a), DiscoverDedup.canonicalId(d))
    }

    @Test
    fun `different pages are different ids`() {
        val p0 = "https://i.pximg.net/img-master/img/2020/01/01/00/00/00/12345678_p0_master1200.jpg"
        val p1 = "https://i.pximg.net/img-master/img/2020/01/01/00/00/00/12345678_p1_master1200.jpg"
        assertNotEquals(DiscoverDedup.canonicalId(p0), DiscoverDedup.canonicalId(p1))
    }

    @Test
    fun `booru md5 filenames collapse across cdn paths and query strings`() {
        val a = "https://cdn1.donmai.us/abc/abcdef0123456789abcdef0123456789.jpg"
        val b = "https://cdn2.yande.re/re/abcdef0123456789abcdef0123456789.png?download=1"
        assertEquals(DiscoverDedup.canonicalId(a), DiscoverDedup.canonicalId(b))
    }

    @Test
    fun `post id urls collapse to the post id`() {
        val a = "https://safebooru.org/index.php?page=post&s=view&id=123456"
        val b = "https://safebooru.org/index.php?page=dapi&s=post&q=index&id=123456"
        assertEquals(DiscoverDedup.canonicalId(a), DiscoverDedup.canonicalId(b))
    }

    // ── The queue lifecycle (THE 0.8.25 POP BUG regression) ────────────

    @Test
    fun `a queued image pops — reserve and append do not make it a duplicate`() {
        DiscoverDedupTestAccess.reset()
        val url = "https://i.pixiv.re/img-master/img/2020/01/01/00/00/00/12345678_p0_master1200.jpg"
        // The search path: found → reserved (session id + pending handoff).
        DiscoverDedupTestAccess.reserve(url)
        // The append path: the handoff is consumed by the append gate.
        org.junit.Assert.assertTrue(DiscoverDedupTestAccess.claim(url))
        // THE POP: must still succeed — the old code found the id in the
        // session set and deleted the slot ("queue pop skipped a
        // duplicate"), draining every queue in 0.8.25.
        org.junit.Assert.assertTrue(DiscoverDedupTestAccess.claimShownFromQueue(url))
        // A SECOND presentation of the same image IS a duplicate.
        org.junit.Assert.assertFalse(DiscoverDedupTestAccess.claimShownFromQueue(url))
    }

    @Test
    fun `an image shown through another path is skipped at pop`() {
        DiscoverDedupTestAccess.reset()
        val url = "https://safebooru.org/images/3928/aaaabbbbccccddddeeee000011112222.jpg"
        DiscoverDedupTestAccess.reserve(url)
        DiscoverDedupTestAccess.claim(url)          // queued
        DiscoverDedupTestAccess.markShown(url)       // also shown inline meanwhile
        org.junit.Assert.assertFalse(DiscoverDedupTestAccess.claimShownFromQueue(url))
    }

    // ── Host-health ranking ─────────────────────────────────────────────

    @Test
    fun `rank keeps order for unknown hosts`() {
        val urls = listOf("https://a.example/1", "https://b.example/2")
        // No samples recorded — the incoming order is preserved.
        val ranked = DiscoverSourceHealthTestAccess.rank(urls)
        assertEquals(urls, ranked)
    }

    @Test
    fun `rank puts the reliable host first`() {
        val good = "https://good.example/1"
        val bad = "https://bad.example/2"
        DiscoverSourceHealthTestAccess.reset()
        repeat(4) { DiscoverSourceHealthTestAccess.record(bad, false, 200) }
        repeat(4) { DiscoverSourceHealthTestAccess.record(good, true, 50) }
        val ranked = DiscoverSourceHealthTestAccess.rank(listOf(bad, good))
        assertEquals(good, ranked.first())
    }

    @Test
    fun `rank blocks a failing host while a healthy one exists`() {
        val good = "https://good.example/1"
        val dead = "https://dead.example/2"
        DiscoverSourceHealthTestAccess.reset()
        repeat(5) { DiscoverSourceHealthTestAccess.record(dead, false, 5000) }
        val ranked = DiscoverSourceHealthTestAccess.rank(listOf(dead, good))
        assertFalse(ranked.contains(dead))
        assertTrue(ranked.contains(good))
    }

    // ── The Nyanpasu quality scoring ────────────────────────────────────

    @Test
    fun `quality floor rejects gifs and tiny images`() {
        assertNull(DiscoverSearchScore.scoreImageVariant(
            "https://x.example/a.gif", 1080, 1920, "gif", isOriginal = false))
        assertNull(DiscoverSearchScore.scoreImageVariant(
            "https://x.example/a.mp4", 1080, 1920, "mp4", isOriginal = true))
        assertNull(DiscoverSearchScore.scoreImageVariant(
            "https://x.example/a.jpg", 400, 700, "jpg", isOriginal = false))
    }

    @Test
    fun `phone-shaped portrait originals outscore tiny landscape`() {
        val portrait = DiscoverSearchScore.scoreImageVariant(
            "https://x.example/a.jpg", 1080, 2340, "jpg", isOriginal = true)
        val tiny = DiscoverSearchScore.scoreImageVariant(
            "https://x.example/b.jpg", 1200, 700, "jpg", isOriginal = false)
        assertTrue(portrait != null && tiny != null && portrait > tiny)
    }

    @Test
    fun `unknown dimensions score low but are not rejected`() {
        val s = DiscoverSearchScore.scoreImageVariant(
            "https://x.example/a.jpg", 0, 0, "", isOriginal = false)
        assertTrue(s != null && s < 0)
    }
}
