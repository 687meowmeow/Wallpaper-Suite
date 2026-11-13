package com.wepwawet.discover

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
 *
 * v0.8.19 (1.0 parity round — checked line-by-line against the upstream
 * v1.0.0 tag of FranklinNexus/Nyanpasu): the remaining drift from 1.0 is
 * gone. Each alignment is marked "1.0 parity:" at the site:
 *   • Lolicon: the api.lolicon.app + api.yetal.ml MIRROR pair, num=16,
 *     excludeAI=true first (dropped on retry), aspectRatio=lt1 retried
 *     last for untagged queries, the slot budget (2 when the style band
 *     occupies a tag slot, else 3), and the niche tag scoring/soft-pick
 *     from SearchScoring + SearchNicheRegistry (a matching niche result is
 *     preferred, with a soft fallback — never a hard rejection).
 *   • Danbooru: rating:general / -rating:general / -rating:explicit (MIX
 *     excludes explicit, like 1.0 — not the later "no constraint"), OR
 *     terms with ~, pixiv_id:>0 first pass, order:random, is_deleted skip,
 *     and the niche boost ORs appended when the intent matches.
 *   • Booru pool: 1.0's per-mode site lists (PURE → konachan.net +
 *     behoimi.org SAFE_ONLY + safebooru + tbib; NSFW → konachan.com
 *     EXPLICIT_ONLY + yande.re ANY + gelbooru + xbooru; MIX → yande.re,
 *     konachan.com, konachan.net ANY + all four gel sites), the tag
 *     candidates ladder (first | alternative, then each | part, then
 *     niche fallback singles, "1girl" last resort), rating:safe /
 *     rating:explicit / -rating:explicit suffixes, and RANDOM pick from
 *     every allowed result (never "first result").
 *   • Download: the ImageUrlFallback proxy chain — i.pixiv.re,
 *     i.pixiv.nl, pixiv.yuki.sh — with the pixiv Referer for pximg/pixiv
 *     hosts, like 1.0's AppHttpClient + ImageUrlFallback.
 * ══════════════════════════════════════════════════════════════════════
 */
object DiscoverSearch {

    private const val TAG = "DiscoverSearch"
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

    /** 1.0 parity: Lolicon asks for NUM = 16 results per query. */
    private const val LOLICON_NUM = 16

    /** 1.0 parity: the Lolicon mirror pair (loliconMirrorOrder NORMAL). */
    private val LOLICON_MIRRORS = listOf(
        "https://api.lolicon.app/setu/v2",
        "https://api.yetal.ml/setu/v2",
    )

    /** 1.0 parity: the pixiv proxy chain (ImageUrlFallback.PIXIV_PROXY_HOSTS). */
    private val PIXIV_PROXY_HOSTS = listOf(
        "i.pixiv.re",
        "i.pixiv.nl",
        "pixiv.yuki.sh",
    )

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
     * the discover page") — the ids from [DiscoverPrefs.ALL_SOURCES]; only
     * the selected ones are queried at every tier.
     */
    suspend fun searchOne(
        context: Context,
        strictTags: List<String>,
        softTags: List<String>,
        styleValue: Int,
        r18Mode: Int,
        recentUrls: Set<String>,
        sources: Set<String> = DiscoverPrefs.ALL_SOURCES.toSet(),
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
        if (DiscoverPrefs.SOURCE_LOLICON in sources) {
            lolicon(strict, soft, styleValue, r18Mode, attachSoft = true, tried)?.let { return it }
        }

        // ── Tier 1b: Danbooru with tags ──
        if (DiscoverPrefs.SOURCE_DANBOORU in sources &&
            System.currentTimeMillis() < deadline
        ) {
            danbooru(strict, soft, styleValue, r18Mode, tried)?.let { return it }
        }

        // ── Tier 2: booru pool with tags ──
        if (System.currentTimeMillis() < deadline) {
            booruPool(strict, r18Mode, sources, tried)?.let { return it }
        }

        // ── R18 escalations (v1.0.0 tierEscalations) ──
        for (escalated in r18Escalations(r18Mode)) {
            if (System.currentTimeMillis() >= deadline) break
            if (DiscoverPrefs.SOURCE_LOLICON in sources) {
                lolicon(strict, soft, styleValue, escalated, attachSoft = false, tried)?.let { return it }
            }
            booruPool(strict, escalated, sources, tried)?.let { return it }
        }

        // ── Tier 3: slider-only (style band alone) ──
        if (System.currentTimeMillis() < deadline) {
            sliderTagGroup(styleValue)?.let { band ->
                if (DiscoverPrefs.SOURCE_LOLICON in sources) {
                    lolicon(listOf(band), emptyList(), styleValue, r18Mode, attachSoft = false, tried)?.let { return it }
                }
                booruPool(listOf(band), r18Mode, sources, tried)?.let { return it }
            }
        }

        // ── Tier 4: untagged fallback — ALWAYS something when the network is up ──
        // (v1.0.0's contract: this tier exists exactly so a no-match never
        // strands the user; only a truly dead network reaches the exception.)
        if (DiscoverPrefs.SOURCE_LOLICON in sources) {
            lolicon(emptyList(), emptyList(), styleValue, r18Mode, attachSoft = false, tried)?.let { return it }
        }
        if (DiscoverPrefs.SOURCE_DANBOORU in sources) {
            danbooru(emptyList(), emptyList(), styleValue, r18Mode, tried)?.let { return it }
        }
        booruPool(emptyList(), r18Mode, sources, tried)?.let { return it }
        return null
    }

    // ════════════════════════════════════════════════════════════════
    // Sources
    // ════════════════════════════════════════════════════════════════

    /**
     * Lolicon /setu/v2 — returns a direct image URL (proxied pixiv).
     *
     * 1.0 parity (LoliconSearchClient.fetchTagged + buildPath):
     *  • the retry ladder — tagged: (no aspect, excludeAI) → (no aspect, no
     *    excludeAI) → (aspect lt1, excludeAI); untagged: (aspect lt1,
     *    excludeAI) → (no aspect, excludeAI) → (no aspect, no excludeAI);
     *  • the primary-slot budget — max 2 when the style band occupies a
     *    tag slot (slider active + band non-null), else 3;
     *  • num=16, and the niche tag soft-pick (score >= 1 preferred,
     *    score >= 0 soft, last-resort random — never a rejection).
     */
    private fun lolicon(
        primaryTags: List<String>,
        softTags: List<String>,
        styleValue: Int,
        r18Mode: Int,
        attachSoft: Boolean,
        tried: Set<String>,
    ): String? {
        // v1.0.0: r18=2 means "mixed" (the API returns either).
        val band = styleBand(styleValue)
        val useSlider = sliderTagGroup(styleValue) != null
        val params = StringBuilder("?r18=$r18Mode&size=regular")
        val slots = expandTags(primaryTags).take(primarySlotBudget(useSlider))
        slots.forEach { params.append("&tag=${encode(it)}") }
        // Soft tags: 38% chance to attach one hint (v1.0.0's chance logic;
        // 100% when the caller explicitly asks to attach, like 1.0's
        // searchStrictWithSoftHints tier).
        if (slots.isNotEmpty() && softTags.isNotEmpty()) {
            val chance = if (attachSoft) 100 else 38
            if (Random.nextInt(100) < chance) {
                params.append("&tag=${encode(softTags.random())}")
            }
        } else if (slots.isEmpty() && softTags.isNotEmpty() && Random.nextInt(100) < 38) {
            params.append("&tag=${encode(softTags.random())}")
        }
        params.append("&num=$LOLICON_NUM")

        // 1.0 parity: the retry ladder over (aspect, excludeAI) — for
        // tagged queries excludeAI drops out before aspect flips; for
        // untagged queries aspect lt1 (portrait, phone-shaped) leads.
        val attempts: List<Pair<Boolean, Boolean>> = if (primaryTags.isNotEmpty()) {
            listOf(false to true, false to false, true to true)
        } else {
            listOf(true to true, false to true, false to false)
        }
        for ((aspect, excludeAi) in attempts) {
            val query = buildString {
                append(params)
                if (excludeAi) append("&excludeAI=true")
                if (aspect) append("&aspectRatio=lt1")
            }
            val data = LOLICON_MIRRORS.firstNotNullOfOrNull { base ->
                httpGetJson(base + query)?.optJSONArray("data")
            } ?: continue
            // v1.0.0: iterate and pick a non-recent result (NO quality
            // filter — the later versions' chooseBest()/score() is
            // deliberately absent). The niche soft-pick applies when the
            // user's tags match a niche registry entry.
            val picked = pickLoliconEntry(data, primaryTags, tried) ?: continue
            if (picked != null) return picked
        }
        return null
    }

    /** 1.0 parity: 2 primary slots when the slider band takes one, else 3. */
    private fun primarySlotBudget(sliderOccupiesSlot: Boolean): Int =
        if (sliderOccupiesSlot) 2 else 3

    /**
     * 1.0 parity (LoliconSearchClient.pickRegularUrl + SearchScoring):
     * random pick among non-recent results, with the niche soft-pref —
     * when the intent matches a niche spec, results scoring >= 1 win,
     * then score >= 0, then anything (never a rejection).
     */
    private fun pickLoliconEntry(
        data: JSONArray,
        intentTags: List<String>,
        tried: Set<String>,
    ): String? {
        val intentBlob = (intentTags).joinToString("|").lowercase()
        val spec = matchNiche(intentBlob) ?: return pickAnyLolicon(data, tried)
        val entries = ArrayList<Pair<JSONObject, Int>>(data.length())
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val urls = entry.optJSONObject("urls") ?: continue
            val reg = urls.optString("regular", "")
            if (reg.isEmpty() || reg in tried) continue
            val tagStr = entry.optJSONArray("tags")?.let { arr ->
                List(arr.length()) { arr.optString(it) }.joinToString(" ")
            } ?: ""
            entries.add(entry to nicheScore(tagStr, spec))
        }
        if (entries.isEmpty()) return null
        entries.filter { it.second >= 1 }.takeIf { it.isNotEmpty() }?.let {
            return it[Random.nextInt(it.size)].first
                .optJSONObject("urls")?.optString("regular", "")
        }
        entries.filter { it.second >= 0 }.takeIf { it.isNotEmpty() }?.let {
            return it[Random.nextInt(it.size)].first
                .optJSONObject("urls")?.optString("regular", "")
        }
        return entries[Random.nextInt(entries.size)].first
            .optJSONObject("urls")?.optString("regular", "")
    }

    private fun pickAnyLolicon(data: JSONArray, tried: Set<String>): String? {
        val urls = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val urlsObj = entry.optJSONObject("urls") ?: continue
            val reg = urlsObj.optString("regular", "")
                .ifBlank { urlsObj.optString("original", "") }
            if (reg.isNotBlank() && reg !in tried) urls.add(reg)
        }
        if (urls.isEmpty()) return null
        return urls[Random.nextInt(urls.size)]
    }

    /**
     * Danbooru posts.json — random order, rating per content tier.
     *
     * 1.0 parity (DanbooruSearchClient.fetchPixiv):
     *  • rating clauses 0 → rating:general, 1 → -rating:general, MIX →
     *    -rating:explicit (NOT unconstrained — 1.0's MIX excludes explicit
     *    on Danbooru, unlike the booru pool);
     *  • OR terms (~a ~b) for | alternatives and niche boosts;
     *  • pixiv_id:>0 on the first (require-pixiv) pass, order:random,
     *    limit=25, is_deleted skipped.
     */
    private fun danbooru(
        primaryTags: List<String>,
        softTags: List<String>,
        styleValue: Int,
        r18Mode: Int,
        tried: Set<String>,
    ): String? {
        if (primaryTags.isEmpty()) return null // untagged fallback uses the booru pool
        val intentBlob = primaryTags.joinToString("|").lowercase()

        // Pass 1: require a pixiv id (like 1.0's first fetchPixiv call),
        // Pass 2: compact tags, no pixiv requirement.
        for (requirePixiv in listOf(true, false)) {
            val parts = ArrayList<String>()
            danbooruRatingClause(r18Mode)?.let { parts.add(it) }
            if (requirePixiv) parts.add("pixiv_id:>0")
            parts.add("order:random")
            if (requirePixiv) {
                expandTags(primaryTags).forEach { parts.addAll(tokenToTerms(it)) }
                // 1.0 parity: soft hint attached 40% when no primary tags —
                // here primaries exist, so the niche boost ORs instead.
                for (spec in NICHES) {
                    if (intentBlob.containsAny(spec.markers)) {
                        spec.danbooruBoostOrs.forEach { parts.add(it) }
                    }
                }
            } else {
                // compactTags: ONE content token (first | alternative).
                expandTags(primaryTags).firstOrNull()?.let { first ->
                    val one = if ("|" in first) first.substringBefore("|") else first
                    if (one.isNotBlank()) parts.add(one)
                }
            }
            val tagQuery = parts.filter { it.isNotBlank() }.joinToString(" ")
            if (tagQuery.length > 1500) continue
            val url = "https://danbooru.donmai.us/posts.json?limit=25&tags=${encode(tagQuery)}"
            val body = httpGetJsonArray(url) ?: continue
            // v1.0.0: iterate and pick a non-recent result (NO quality
            // filter). Deleted posts and off-rating posts are skipped.
            val urls = ArrayList<String>()
            for (i in 0 until body.length()) {
                val post = body.optJSONObject(i) ?: continue
                if (post.optBoolean("is_deleted")) continue
                val rating = post.optString("rating", "")
                val ratingOk = when (r18Mode) {
                    DiscoverPrefs.R18_PURE -> rating == "g"
                    DiscoverPrefs.R18_NSFW -> true
                    else -> rating != "e" // MIX: exclude explicit (1.0 parity)
                }
                if (!ratingOk) continue
                val fileUrl = post.optString("file_url")
                if (fileUrl.isNotBlank() && fileUrl !in tried) urls.add(fileUrl)
            }
            if (urls.isNotEmpty()) return urls[Random.nextInt(urls.size)]
        }
        return null
    }

    /** 1.0 parity (DanbooruSearchClient.ratingClause). */
    private fun danbooruRatingClause(r18Mode: Int): String? = when (r18Mode) {
        DiscoverPrefs.R18_PURE -> "rating:general"
        DiscoverPrefs.R18_NSFW -> "-rating:general"
        else -> "-rating:explicit"
    }

    /** 1.0 parity (tokenToTerms): "|"-joined alternatives become ~a ~b ORs. */
    private fun tokenToTerms(token: String): List<String> {
        val t = token.trim()
        if (t.isEmpty()) return emptyList()
        if ("|" in t) {
            val ors = t.split("|")
                .map { it.trim().replace(' ', '_').lowercase() }
                .filter { it.isNotEmpty() }
            if (ors.isEmpty()) return emptyList()
            return listOf(ors.joinToString(" ") { "~$it" })
        }
        return listOf(t.replace(' ', '_').lowercase())
    }

    /**
     * The booru pool (v1.0.0 BooruPoolClient) — per-mode site lists,
     * rating-filtered per site family, RANDOM pick among allowed results.
     * v0.8.18: only sites whose source id is in `sources` are tried.
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
            val filter: Int, // 0 SAFE_ONLY, 1 EXPLICIT_ONLY, 2 ANY
        )

        // 1.0 parity: MOE_PURE / MOE_NSFW / MOE_MIX site lists.
        val moeByMode = when (r18Mode) {
            DiscoverPrefs.R18_PURE -> listOf(
                MoeSite(DiscoverPrefs.SOURCE_KONACHAN, "https://konachan.net/post.json", 0),
                MoeSite(DiscoverPrefs.SOURCE_BEHOIMI, "https://behoimi.org/post.json", 0),
            )
            DiscoverPrefs.R18_NSFW -> listOf(
                MoeSite("konachan_com", "https://konachan.com/post.json", 1),
                MoeSite(DiscoverPrefs.SOURCE_YANDE, "https://yande.re/post.json", 2),
            )
            else -> listOf( // MIX
                MoeSite(DiscoverPrefs.SOURCE_YANDE, "https://yande.re/post.json", 2),
                MoeSite("konachan_com", "https://konachan.com/post.json", 2),
                MoeSite(DiscoverPrefs.SOURCE_KONACHAN, "https://konachan.net/post.json", 2),
            )
        }
        // 1.0 parity: GEL_PURE / GEL_NSFW site lists (MIX = both).
        val gelByMode = when (r18Mode) {
            DiscoverPrefs.R18_PURE -> listOf(
                DiscoverPrefs.SOURCE_SAFEBOORU to
                    "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1",
                DiscoverPrefs.SOURCE_TBIB to
                    "https://tbib.org/index.php?page=dapi&s=post&q=index&json=1",
            )
            DiscoverPrefs.R18_NSFW -> listOf(
                DiscoverPrefs.SOURCE_GELBOORU to
                    "https://gelbooru.com/index.php?page=dapi&s=post&q=index&json=1",
                DiscoverPrefs.SOURCE_XBOORU to
                    "https://xbooru.com/index.php?page=dapi&s=post&q=index&json=1",
            )
            else -> listOf( // MIX = GEL_PURE + GEL_NSFW
                DiscoverPrefs.SOURCE_SAFEBOORU to
                    "https://safebooru.org/index.php?page=dapi&s=post&q=index&json=1",
                DiscoverPrefs.SOURCE_TBIB to
                    "https://tbib.org/index.php?page=dapi&s=post&q=index&json=1",
                DiscoverPrefs.SOURCE_GELBOORU to
                    "https://gelbooru.com/index.php?page=dapi&s=post&q=index&json=1",
                DiscoverPrefs.SOURCE_XBOORU to
                    "https://xbooru.com/index.php?page=dapi&s=post&q=index&json=1",
            )
        }

        // 1.0 parity (BooruPoolClient.buildTagCandidates): the tag
        // candidate ladder — first |-alternative, then each | part, then
        // the niche fallback singles, "1girl" last resort.
        val candidates = buildTagCandidates(tags)
        val ratingSuffix = when (r18Mode) {
            DiscoverPrefs.R18_PURE -> "rating:safe"
            DiscoverPrefs.R18_NSFW -> "rating:explicit"
            else -> "-rating:explicit"
        }

        // Moe-style sites (1.0: for each tag candidate, each site, in
        // candidate order — NOT shuffled; the pool's variety comes from
        // the random pick among results).
        for (tag in candidates) {
            for (site in moeByMode.filter { it.sourceId in sources }) {
                val query = "$tag $ratingSuffix"
                val body = httpGetJsonArray("${site.endpoint}?limit=24&tags=${encode(query)}") ?: continue
                val urls = ArrayList<String>()
                for (i in 0 until body.length()) {
                    val post = body.optJSONObject(i) ?: continue
                    val rt = post.optString("rating", "").lowercase()
                    val allowed = when (site.filter) {
                        0 -> rt == "safe" || rt == "s"
                        1 -> rt == "explicit" || rt == "e"
                        else -> when (r18Mode) {
                            DiscoverPrefs.R18_PURE -> rt == "safe" || rt == "s"
                            DiscoverPrefs.R18_NSFW -> rt != "safe" && rt != "s"
                            else -> rt != "explicit" && rt != "e"
                        }
                    }
                    if (!allowed) continue
                    val fileUrl = sequenceOf(
                        post.optString("sample_url", ""),
                        post.optString("jpeg_url", ""),
                        post.optString("file_url", ""),
                    ).firstOrNull { it.isNotEmpty() } ?: continue
                    if (fileUrl !in tried) urls.add(fileUrl)
                }
                if (urls.isNotEmpty()) return urls[Random.nextInt(urls.size)]
            }
        }
        // dapi-style (safebooru/tbib/gelbooru/xbooru).
        for (tag in candidates) {
            for ((sourceId, base) in gelByMode.filter { it.first in sources }) {
                val body = httpGetJsonArray("${base}&tags=${encode(tag)}&limit=24") ?: continue
                val urls = ArrayList<String>()
                for (i in 0 until body.length()) {
                    val post = body.optJSONObject(i) ?: continue
                    val rating = post.optString("rating", "").lowercase()
                    val allowed = when (r18Mode) {
                        DiscoverPrefs.R18_PURE -> rating !in setOf("explicit", "e", "q")
                        DiscoverPrefs.R18_NSFW -> true
                        else -> rating !in setOf("explicit", "e")
                    }
                    if (!allowed) continue
                    val fileUrl = post.optString("file_url", "")
                    if (fileUrl.startsWith("http") && fileUrl !in tried) urls.add(fileUrl)
                }
                if (urls.isNotEmpty()) return urls[Random.nextInt(urls.size)]
            }
        }
        return null
    }

    /**
     * 1.0 parity (BooruPoolClient.buildTagCandidates): first | alternative
     * of each tag, then every | part, then niche fallback singles, then
     * "1girl" as the last resort so the pool ALWAYS has a query.
     */
    private fun buildTagCandidates(tags: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in expandTags(tags)) {
            val p = raw.trim()
            if (p.isEmpty()) continue
            val one = (if ("|" in p) p.substringBefore("|") else p)
                .replace(' ', '_').lowercase()
            if (one.isNotEmpty()) out.add(one)
            p.split("|").forEach { part ->
                val t = part.trim().replace(' ', '_').lowercase()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        val intentBlob = tags.joinToString("|").lowercase()
        matchNiche(intentBlob)?.let { spec ->
            spec.fallbackSingles.forEach { s ->
                val t = s.trim().replace(' ', '_').lowercase()
                if (t.isNotEmpty()) out.add(t)
            }
        }
        if (out.isEmpty()) out.add("1girl")
        return out.toList()
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

    // ── 1.0 parity: the niche registry (SearchNicheRegistry) ──────────

    private class NicheSpec(
        val markers: List<String>,
        val orGroup: String,
        val fallbackSingles: List<String>,
        val danbooruBoostOrs: List<String>,
        val loliconPositive: List<String>,
    )

    private val NICHES = listOf(
        NicheSpec(
            markers = listOf("男娘", "伪娘", "femboy", "otokonoko", "crossdressing",
                "josou", "otoko_no_ko"),
            orGroup = "otokonoko|trap|otoko_no_ko|crossdressing|femboy|josou_seme|josou_uke",
            fallbackSingles = listOf("otokonoko", "trap", "crossdressing", "femboy", "otoko_no_ko"),
            danbooruBoostOrs = listOf("~otokonoko", "~trap", "~crossdressing",
                "~otoko_no_ko", "~femboy"),
            loliconPositive = listOf("otokonoko", "trap", "crossdressing", "femboy",
                "josou", "otoko no ko", "otoko_no_ko", "male maiden", "男の娘", "女装", "女裝", "偽娘"),
        ),
        NicheSpec(
            markers = listOf("yuri", "百合", "gl", "girls_love", "girls love",
                "shoujo_ai", "shojo_ai"),
            orGroup = "yuri|girls_love|shoujo_ai",
            fallbackSingles = listOf("yuri", "girls_love", "shoujo_ai"),
            danbooruBoostOrs = listOf("~yuri", "~girls_love", "~shoujo_ai"),
            loliconPositive = listOf("yuri", "girls_love", "girls love", "shoujo_ai", "shojo_ai"),
        ),
        NicheSpec(
            markers = listOf("yaoi", "耽美", "bl", "boys_love", "boys love", "shounen_ai"),
            orGroup = "yaoi|boys_love|shounen_ai",
            fallbackSingles = listOf("yaoi", "boys_love", "shounen_ai"),
            danbooruBoostOrs = listOf("~yaoi", "~boys_love", "~shounen_ai"),
            loliconPositive = listOf("yaoi", "boys love", "shounen_ai", "shounen-ai"),
        ),
        NicheSpec(
            markers = listOf("furry", "kemono", "兽人", "ケモノ", "福瑞"),
            orGroup = "furry|kemono|animal_ears",
            fallbackSingles = listOf("furry", "kemono", "animal_ears"),
            danbooruBoostOrs = listOf("~furry", "~kemono"),
            loliconPositive = listOf("furry", "kemono", "animal ears", "animal_ears"),
        ),
        NicheSpec(
            markers = listOf("足控", "foot_focus", "feet", "裸足", "足", "footjob"),
            orGroup = "foot_focus|feet|barefoot|soles",
            fallbackSingles = listOf("foot_focus", "feet", "barefoot"),
            danbooruBoostOrs = listOf("~foot_focus", "~feet", "~barefoot"),
            loliconPositive = listOf("foot focus", "foot_focus", "feet", "barefoot", "soles", "toes"),
        ),
        NicheSpec(
            markers = listOf("捆绑", "bdsm", "bondage", "束缚", "绳"),
            orGroup = "bondage|bdsm|shibari|rope",
            fallbackSingles = listOf("bondage", "bdsm", "shibari"),
            danbooruBoostOrs = listOf("~bondage", "~bdsm", "~shibari"),
            loliconPositive = listOf("bondage", "bdsm", "shibari", "rope"),
        ),
        NicheSpec(
            markers = listOf("ntr", "netorare", "寝取", "牛头人"),
            orGroup = "netorare|ntr|cheating",
            fallbackSingles = listOf("netorare", "ntr", "cheating"),
            danbooruBoostOrs = listOf("~netorare", "~ntr", "~cheating"),
            loliconPositive = listOf("netorare", "ntr", "cheating"),
        ),
        NicheSpec(
            markers = listOf("触手", "tentacle", "tentacles"),
            orGroup = "tentacles|tentacle_pit|tentacle",
            fallbackSingles = listOf("tentacles", "tentacle"),
            danbooruBoostOrs = listOf("~tentacles", "~tentacle"),
            loliconPositive = listOf("tentacle", "tentacles"),
        ),
        NicheSpec(
            markers = listOf("雌小鬼", "mesugaki", "小恶魔", "smug"),
            orGroup = "mesugaki|smug|brat",
            fallbackSingles = listOf("mesugaki", "smug"),
            danbooruBoostOrs = listOf("~mesugaki", "~smug"),
            loliconPositive = listOf("mesugaki", "smug"),
        ),
    )

    private fun matchNiche(intentBlobLower: String): NicheSpec? =
        NICHES.firstOrNull { spec -> spec.markers.any { it.lowercase() in intentBlobLower } }

    /** 1.0 parity (SearchScoring.scoreTagsForNiches). */
    private fun nicheScore(tagRaw: String, spec: NicheSpec): Int {
        val tagLower = tagRaw.lowercase()
        val pos = spec.loliconPositive.any { marker ->
            marker in tagRaw || marker.lowercase() in tagLower
        }
        return if (pos) 4 else 0
    }

    private fun String.containsAny(markers: List<String>): Boolean =
        markers.any { it.lowercase() in this }

    /** 1.0 parity: the style bands. */
    private fun styleBand(styleValue: Int): Int = when {
        styleValue < 35 -> -1 // SMALL
        styleValue > 65 -> 1  // LARGE
        else -> 0             // NEUTRAL
    }

    /** v1.0.0 style bands: <35 small, >65 large, else neutral (null). */
    fun sliderTagGroup(styleValue: Int): String? = when {
        styleValue < 35 -> "small_breasts|flat_chest|petite"
        styleValue > 65 -> "huge_breasts|large_breasts|gigantic_breasts"
        else -> null
    }

    /** v1.0.0 SearchR18Policy.tierEscalations. */
    private fun r18Escalations(appMode: Int): List<Int> = when (appMode) {
        DiscoverPrefs.R18_PURE -> emptyList()
        DiscoverPrefs.R18_NSFW -> listOf(DiscoverPrefs.R18_MIX)
        else -> listOf(DiscoverPrefs.R18_NSFW, DiscoverPrefs.R18_PURE)
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
     * Download an image into raw bytes.
     *
     * 1.0 parity (AppHttpClient's pixiv Referer + ImageUrlFallback): the
     * pixiv Referer rides along for pximg/pixiv hosts, and failures fall
     * back through the proxy chain i.pixiv.re → i.pixiv.nl →
     * pixiv.yuki.sh (1.0 had two proxies; the third is the same idea and
     * only ever used after the first two fail).
     */
    suspend fun downloadImage(url: String): ByteArray = withContext(Dispatchers.IO) {
        val lower = url.lowercase()
        val isPixiv = "pximg.net" in lower || "pixiv.net" in lower
        val candidates = mutableListOf(url)
        if (isPixiv) {
            val path = url.substringAfter(".net", "").ifEmpty { url.substringAfter(".com", "") }
            for (proxy in PIXIV_PROXY_HOSTS) {
                candidates += "https://$proxy$path"
            }
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                val cl = candidate.lowercase()
                val needReferer = "pximg.net" in cl || "pixiv.net" in cl ||
                    cl.endsWith(".pixiv.re") || cl == "pixiv.re" ||
                    cl.endsWith(".pixiv.nl") || cl == "pixiv.nl" ||
                    cl.endsWith(".yuki.sh") || cl == "pixiv.yuki.sh"
                val request = Request.Builder().url(candidate)
                    .header("User-Agent", USER_AGENT)
                    .apply {
                        if (needReferer) header("Referer", "https://www.pixiv.net/")
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
