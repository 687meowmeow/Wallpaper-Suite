package com.wepwawet.nyanpasu

import android.content.Context
import com.wepwawet.log.WepWawetLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Nyanpasu clone — the v1.0.0 image-choice engine.
 *
 * Based on Nyanpasu Wallpaper v1.0.0 by FranklinNexus (MIT License).
 *
 * ══════════════════════════════════════════════════════════════════════
 * WHY 1.0: the later Nyanpasu versions (1.2.3+) restricted image choice —
 *   • SearchImageQuality.score() HARD-REJECTED GIFs, files < 600 px short
 *     side and sample variants, and each source's result list collapsed to
 *     the single highest-scoring image (portrait-biased scoring);
 *   • Required tags became hard constraints — a no-match kept the OLD
 *     wallpaper instead of falling back to something fresh;
 *   • preferred-tag fallback became "bounded" and URL whitelist gating
 *     starved results further.
 * The 1.0 ladder below keeps the opposite contract: an image is ALWAYS
 * preferred over no image, soft tags are hints (38% attach chance), the
 * ladder walks strict → soft-hinted → slider-only → untaged, and NO
 * quality-based hard rejection exists. The only later-version improvement
 * folded in is TagExpander's modifier preservation (v1.2.3) — it keeps
 * user modifiers when expanding niche tags, which strictly widens choice.
 * ══════════════════════════════════════════════════════════════════════
 */
object NyanpasuSearch {

    private const val TAG = "NyanpasuSearch"
    // v0.8.18 (review B21): the OS version comes from the DEVICE now — the
    // old hardcoded "Android 14" UA went stale the moment the app ran on
    // any other release and made the client trivially fingerprintable as
    // a bot. Same fix SteamLoginActivity received in v0.7.25 (audit M-A4);
    // no longer `const` because Build.VERSION.RELEASE is runtime state.
    private val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Overall budget — mirrors v1.0.0's SearchTierBudget (single-shot usage). */
    private const val TOTAL_BUDGET_MS = 32_000L

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // v0.8.10: DNS-over-HTTPS fallback — the user's network drops
            // plain DNS for the booru/API hosts (UnknownHostException on
            // every source = the "completely black / tap refresh" report).
            // System DNS is still tried FIRST; DoH only catches its failures.
            .dns(DohDns)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    class NoImageException(message: String) : Exception(message)

    /**
     * Search for ONE image URL that is not in `recentUrls`.
     * Throws [NoImageException] when the whole ladder fails (rare — the
     * untaged fallback tier exists exactly so this doesn't happen) or when
     * no booru source is selected.
     *
     * v0.8.18: `sources` ("add the ability to choose the booru sources for
     * the discover page") — the ids from [NyanpasuPrefs.ALL_SOURCES]; only
     * the selected ones are queried at every tier.
     */
    suspend fun searchOne(
        context: Context,
        strictTags: List<String>,
        softTags: List<String>,
        styleValue: Int,
        r18Mode: Int,
        recentUrls: Set<String>,
        sources: Set<String> = NyanpasuPrefs.ALL_SOURCES.toSet(),
    ): String = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) {
            throw NoImageException("No booru source selected")
        }
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + TOTAL_BUDGET_MS
        val recent = recentUrls.toMutableSet()

        // v1.0.0 normalization: soft-only → the soft tags act as strict.
        val strict = strictTags.filter { it.isNotBlank() }
        val soft = softTags.filter { it.isNotBlank() }
        val (effStrict, effSoft) =
            if (strict.isEmpty() && soft.isNotEmpty()) soft to emptyList()
            else strict to soft

        val tried = mutableSetOf<String>()
        var attempts = 0
        // Up to 8 draws, preferring non-recent results (soft dedup).
        while (System.currentTimeMillis() < deadline) {
            attempts++
            val url = tryLadder(effStrict, effSoft, styleValue, r18Mode, sources, tried, deadline)
            if (url.isNullOrEmpty()) break
            tried.add(url)
            if (url !in recent) {
                WepWawetLogger.appInfo("$TAG: image found",
                    "attempt" to attempts,
                    "url" to url.take(120),
                    "elapsed_ms" to (System.currentTimeMillis() - startedAt))
                return@withContext url
            }
            if (attempts >= 8) {
                // All draws recent — accept the freshest one anyway (1.0
                // NEVER refuses to hand out an image just because of dedup).
                WepWawetLogger.appInfo("$TAG: all recent — reusing last draw",
                    "attempt" to attempts)
                return@withContext url
            }
        }
        throw NoImageException("No image found across the tag ladder (attempted $attempts)")
    }

    /** The v1.0.0 tier ladder. Each tier returns null on miss. */
    private fun tryLadder(
        strict: List<String>,
        soft: List<String>,
        styleValue: Int,
        r18Mode: Int,
        sources: Set<String>,
        tried: Set<String>,
        deadline: Long,
    ): String? {
        // ── Tier 1: Lolicon with tags (the flagship source) ──
        if (NyanpasuPrefs.SOURCE_LOLICON in sources) {
            lolicon(strict, soft, styleValue, r18Mode, attachSoft = true, tried)?.let { return it }
        }

        // ── Tier 1b: Danbooru with tags ──
        if (NyanpasuPrefs.SOURCE_DANBOORU in sources &&
            System.currentTimeMillis() < deadline
        ) {
            danbooru(strict, soft, r18Mode, tried)?.let { return it }
        }

        // ── Tier 2: booru pool with tags ──
        if (System.currentTimeMillis() < deadline) {
            booruPool(strict, r18Mode, sources, tried)?.let { return it }
        }

        // ── R18 escalations (v1.0.0 tierEscalations) ──
        for (escalated in r18Escalations(r18Mode)) {
            if (System.currentTimeMillis() >= deadline) break
            if (NyanpasuPrefs.SOURCE_LOLICON in sources) {
                lolicon(strict, soft, styleValue, escalated, attachSoft = false, tried)?.let { return it }
            }
            booruPool(strict, escalated, sources, tried)?.let { return it }
        }

        // ── Tier 3: slider-only (style band alone) ──
        if (System.currentTimeMillis() < deadline) {
            sliderTagGroup(styleValue)?.let { band ->
                if (NyanpasuPrefs.SOURCE_LOLICON in sources) {
                    lolicon(listOf(band), emptyList(), styleValue, r18Mode, attachSoft = false, tried)?.let { return it }
                }
                booruPool(listOf(band), r18Mode, sources, tried)?.let { return it }
            }
        }

        // ── Tier 4: untagged fallback — ALWAYS something when the network is up ──
        // (v1.0.0's contract: this tier exists exactly so a no-match never
        // strands the user; only a truly dead network reaches the exception.)
        if (NyanpasuPrefs.SOURCE_LOLICON in sources) {
            lolicon(emptyList(), emptyList(), styleValue, r18Mode, attachSoft = false, tried)?.let { return it }
        }
        if (NyanpasuPrefs.SOURCE_DANBOORU in sources) {
            danbooru(emptyList(), emptyList(), r18Mode, tried)?.let { return it }
        }
        booruPool(emptyList(), r18Mode, sources, tried)?.let { return it }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    // Sources
    // ════════════════════════════════════════════════════════════════

    /** Lolicon /setu/v2 — returns a direct image URL (proxied pixiv). */
    private fun lolicon(
        primaryTags: List<String>,
        softTags: List<String>,
        styleValue: Int,
        r18Mode: Int,
        attachSoft: Boolean,
        tried: Set<String>,
    ): String? {
        // v1.0.0: r18=2 means "mixed" (the API returns either).
        val params = StringBuilder("?r18=$r18Mode&size=regular")
        val slots = expandTags(primaryTags)
        // Cap primary slots at 3 (v1.0.0's primaryTagSlots).
        slots.take(3).forEach { params.append("&tag=${encode(it)}") }
        // Soft tags: 38% chance to attach one hint (v1.0.0's chance logic).
        if (slots.isNotEmpty() && softTags.isNotEmpty() && Random.nextInt(100) < 38) {
            params.append("&tag=${encode(softTags.random())}")
        }
        val url = "https://api.lolicon.app/setu/v2$params"
        val body = httpGetJson(url) ?: return null
        val data = body.optJSONArray("data") ?: return null
        // v1.0.0: iterate and pick a non-recent result (NO quality filter —
        // the later versions' chooseBest()/score() is deliberately absent).
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val urls = entry.optJSONObject("urls") ?: continue
            val candidate = urls.optString("regular").ifBlank { urls.optString("original") }
            if (candidate.isNotBlank() && candidate !in tried) return candidate
        }
        return null
    }

    /** Danbooru posts.json — random order, rating per content tier. */
    private fun danbooru(
        primaryTags: List<String>,
        softTags: List<String>,
        r18Mode: Int,
        tried: Set<String>,
    ): String? {
        if (primaryTags.isEmpty()) return null // untagged fallback uses the booru pool
        // Danbooru anonymous accounts cap at 2 query tags total — use ONE
        // content tag plus the rating/meta filter (v1.0.0 behavior).
        val tags = mutableListOf<String>()
        tags += danbooruTag(expandTags(primaryTags).firstOrNull() ?: return null)
        if (softTags.isNotEmpty() && Random.nextInt(100) < 38) {
            // Replace the primary with a soft hint 38% of the time (v1.0.0's
            // attach-soft logic — a hint, never an AND-combination).
            tags[0] = danbooruTag(softTags.random())
        }
        when (r18Mode) {
            NyanpasuPrefs.R18_PURE -> tags += "rating:general"
            NyanpasuPrefs.R18_NSFW -> tags += "-rating:general"
            else -> { /* mix: no rating constraint */ }
        }
        tags += "order:random"
        val url = "https://danbooru.donmai.us/posts.json?limit=20&tags=${encode(tags.joinToString(" "))}"
        val body = httpGetJsonArray(url) ?: return null
        for (i in 0 until body.length()) {
            val post = body.optJSONObject(i) ?: continue
            val fileUrl = post.optString("file_url")
            if (fileUrl.isNotBlank() && fileUrl !in tried) return fileUrl
        }
        return null
    }

    /**
     * The booru pool (v1.0.0 BooruPoolClient) — shuffled, rating-filtered
     * per site family. Each site tries once; the first hit wins. v0.8.18:
     * only sites whose source id is in `sources` are tried.
     */
    private fun booruPool(
        tags: List<String>,
        r18Mode: Int,
        sources: Set<String>,
        tried: Set<String>,
    ): String? {
        data class MoeSite(
            val sourceId: String,
            val endpoint: String,
            val explicitAllowed: Boolean,
            val api: String,
        )

        val moe = listOf(
            MoeSite(NyanpasuPrefs.SOURCE_KONACHAN, "https://konachan.net/post.json", false, "moe"),
            MoeSite(NyanpasuPrefs.SOURCE_BEHOIMI, "https://behoimi.org/post.json", false, "moe"),
            MoeSite(NyanpasuPrefs.SOURCE_YANDE, "https://yande.re/post.json", true, "moe"),
            MoeSite("konachan_com", "https://konachan.com/post.json", true, "moe"),
        )
        val dapi = listOf(
            NyanpasuPrefs.SOURCE_SAFEBOORU to
                "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1",
            NyanpasuPrefs.SOURCE_TBIB to
                "https://tbib.org/index.php?page=dapi&s=post&q=index&json=1",
            NyanpasuPrefs.SOURCE_GELBOORU to
                "https://gelbooru.com/index.php?page=dapi&s=post&q=index&json=1",
            NyanpasuPrefs.SOURCE_XBOORU to
                "https://xbooru.com/index.php?page=dapi&s=post&q=index&json=1",
        )

        // Moe-style sites first (shuffled like v1.0.0's pool).
        for (site in moe.filter { it.sourceId in sources }.shuffled()) {
            if (r18Mode == NyanpasuPrefs.R18_PURE && site.explicitAllowed) continue
            val expanded = expandTags(tags).map { moeTag(it) }
            val url = buildString {
                append(site.endpoint)
                if (expanded.isNotEmpty()) append("?tags=${encode(expanded.joinToString(" "))}&limit=50")
                else append("?limit=50&page=${Random.nextInt(1, 20)}")
            }
            val body = httpGetJsonArray(url) ?: continue
            for (i in 0 until body.length()) {
                val post = body.optJSONObject(i) ?: continue
                if (r18Mode == NyanpasuPrefs.R18_PURE &&
                    post.optString("rating").lowercase() !in listOf("s", "safe")) continue
                val fileUrl = post.optString("file_url").ifBlank { post.optString("jpeg_url") }
                if (fileUrl.isNotBlank() && fileUrl !in tried) return fileUrl
            }
        }
        // dapi-style (safebooru/tbib/gelbooru/xbooru).
        for ((sourceId, base) in dapi.filter { it.first in sources }.shuffled()) {
            val explicitSite = "gelbooru.com" in base || "xbooru.com" in base || "konachan.com" in base
            if (r18Mode == NyanpasuPrefs.R18_PURE && explicitSite) continue
            val expanded = expandTags(tags)
            val url = buildString {
                append(base)
                append("&limit=50")
                if (expanded.isNotEmpty()) append("&tags=${encode(expanded.joinToString(" "))}")
            }
            val body = httpGetJsonArray(url) ?: continue
            for (i in 0 until body.length()) {
                val post = body.optJSONObject(i) ?: continue
                if (r18Mode == NyanpasuPrefs.R18_PURE &&
                    post.optString("rating").lowercase() !in listOf("s", "safe", "general")) continue
                val fileUrl = post.optString("file_url").ifBlank { post.optString("source") }
                if (fileUrl.startsWith("http") && fileUrl !in tried) return fileUrl
            }
        }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    // Tag expansion (v1.0.0 TagExpander + the safe v1.2.3 improvement:
    // modifiers are PRESERVED when a niche alias is expanded)
    // ════════════════════════════════════════════════════════════════

    private fun normalizeTag(value: String): String =
        value.trim().lowercase().removePrefix("#").replace(' ', '_')

    private fun modifiersExcluding(userTags: List<String>, vararg intentAliases: String): List<String> {
        val aliases = intentAliases.map { normalizeTag(it) }.toSet()
        return userTags.map { normalizeTag(it) }
            .filter { it.isNotEmpty() && it !in aliases }
    }

    fun expandTags(userTags: List<String>): List<String> {
        if (userTags.isEmpty()) return emptyList()
        val lowered = userTags.map { normalizeTag(it) }
        val has = { candidates: Array<String> -> lowered.any { it in candidates } }
        return when {
            has(arrayOf("touhou", "东方", "touhou_project", "東方")) ->
                listOf("Touhou") + modifiersExcluding(userTags, "东方", "touhou", "东方project", "touhou_project")
            has(arrayOf("genshin", "原神", "genshin_impact", "genshin impact")) ->
                listOf("Genshin_Impact") + modifiersExcluding(userTags, "原神", "genshin", "genshin impact", "genshin_impact")
            has(arrayOf("bluearchive", "blue_archive", "蔚蓝档案", "ブルーアーカイブ", "ba")) ->
                listOf("Blue_Archive") + modifiersExcluding(userTags, "蔚蓝档案", "blue archive", "blue_archive", "ba", "ブルーアーカイブ")
            has(arrayOf("starrail", "星穹铁道", "honkai_star_rail", "honkai star rail", "崩铁")) ->
                listOf("Honkai:_Star-Rail") + modifiersExcluding(userTags, "星穹铁道", "崩铁", "honkai star rail", "honkai_star_rail", "スターレイル")
            has(arrayOf("zzz", "zenless", "绝区零", "zenless_zone_zero")) ->
                listOf("Zenless_Zone_Zero") + modifiersExcluding(userTags, "绝区零", "zenless", "zzz", "ゼンゼロ")
            has(arrayOf("arknights", "明日方舟", "アークナイツ")) ->
                listOf("Arknights") + modifiersExcluding(userTags, "明日方舟", "arknights", "アークナイツ")
            has(arrayOf("fate", "fgo", "命运冠位", "fate/grand_order")) ->
                listOf("Fate/Grand_Order") + modifiersExcluding(userTags, "fate", "fgo", "命运冠位", "fate/grand_order")
            has(arrayOf("hololive", "ホロライブ")) ->
                listOf("Hololive") + modifiersExcluding(userTags, "hololive", "ホロライブ")
            else -> lowered
        }
    }

    private fun danbooruTag(tag: String): String =
        normalizeTag(tag).replace(' ', '_')

    private fun moeTag(tag: String): String =
        normalizeTag(tag).let { if (it.contains(' ')) it.replace(' ', '_') else it }

    /** v1.0.0 style bands: <35 small, >65 large, else neutral (null). */
    fun sliderTagGroup(styleValue: Int): String? = when {
        styleValue < 35 -> "small_breasts|flat_chest|petite"
        styleValue > 65 -> "huge_breasts|large_breasts|gigantic_breasts"
        else -> null
    }

    /** v1.0.0 SearchR18Policy.tierEscalations. */
    private fun r18Escalations(appMode: Int): List<Int> = when (appMode) {
        NyanpasuPrefs.R18_PURE -> emptyList()
        NyanpasuPrefs.R18_NSFW -> listOf(NyanpasuPrefs.R18_MIX)
        else -> listOf(NyanpasuPrefs.R18_NSFW, NyanpasuPrefs.R18_PURE)
    }

    // ════════════════════════════════════════════════════════════════
    // HTTP + download
    // ════════════════════════════════════════════════════════════════

    private fun httpGetJson(url: String): JSONObject? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { resp ->
                when {
                    !resp.isSuccessful -> {
                        WepWawetLogger.appInfo("$TAG: source HTTP error",
                            "host" to url.take(60),
                            "code" to resp.code)
                        null
                    }
                    else -> JSONObject(resp.body?.string() ?: return null)
                }
            }
    } catch (e: Exception) {
        WepWawetLogger.appInfo("$TAG: source failed",
            "host" to url.take(60),
            "exception" to e.javaClass.simpleName)
        null
    }

    private fun httpGetJsonArray(url: String): JSONArray? = try {
        client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { resp ->
                when {
                    !resp.isSuccessful -> {
                        WepWawetLogger.appInfo("$TAG: source HTTP error",
                            "host" to url.take(60),
                            "code" to resp.code)
                        null
                    }
                    else -> JSONArray(resp.body?.string() ?: return null)
                }
            }
    } catch (e: Exception) {
        WepWawetLogger.appInfo("$TAG: source failed",
            "host" to url.take(60),
            "exception" to e.javaClass.simpleName)
        null
    }

    /**
     * Download an image into raw bytes. pixiv-hosted URLs need the pixiv
     * Referer; failures fall back through the i.pixiv.re proxy chain
     * (v1.0.0 ImageUrlFallback).
     */
    suspend fun downloadImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        val candidates = mutableListOf(url)
        val lower = url.lowercase()
        if ("pximg.net" in lower || "pixiv.net" in lower) {
            val path = url.substringAfter(".net", "").ifEmpty { url.substringAfter(".com", "") }
            candidates += listOf(
                "https://i.pixiv.re$path",
                "https://i.pixiv.nl$path",
            )
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val request = Request.Builder().url(candidate)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if ("pximg.net" in candidate || "pixiv.net" in candidate) {
                            header("Referer", "https://www.pixiv.net/")
                        }
                    }
                    .build()
                client.newBuilder()
                    .callTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                    .newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bytes = resp.body?.bytes()
                            if (bytes != null && bytes.size > 1024) return@withContext bytes
                        }
                    }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Image download failed: $url")
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
