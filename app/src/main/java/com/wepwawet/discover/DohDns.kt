package com.wepwawet.discover

import com.wepwawet.log.WepWawetLogger
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * v0.8.10: DNS-over-HTTPS fallback for the image sources.
 *
 * Device reports showed every booru/API host failing with
 * UnknownHostException (api.lolicon.app, safebooru.org, konachan.net,
 * behoimi.org, tbib.org …) while other hosts resolved fine — the user's
 * network drops plain DNS answers for these domains (carrier/school DNS
 * filtering). UnknownHostException from OkHttp's system resolver now
 * retries the lookup over HTTPS JSON DNS (Cloudflare 1.1.1.1, then Google)
 * and caches the result for the session.
 *
 * The DoH lookup itself runs on a bare client WITHOUT this Dns instance
 * (no recursion), and both resolvers are by-IP so no bootstrap DNS is
 * needed to reach them.
 */
object DohDns : Dns {

    private const val TAG = "DohDns"

    /** Session cache: host → resolved addresses (empty = failed, retry later). */
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    private val dohClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private const val RESOLVER_COOLDOWN_MS = 60_000L
    private const val NEG_TTL_MS = 60_000L

    /** By-IP HTTPS JSON DoH endpoints (no bootstrap DNS needed). */
    private val RESOLVERS = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
    )

    @Volatile
    private var dohUsed = 0
    @Volatile
    private var dohOk = 0

    // v0.8.26 (the retry-storm fix): the 0.8.25 log shows a network where
    // system DNS drops the booru hosts AND both DoH endpoints time out —
    // every lookup then burned 4s × resolvers, for host after host, on
    // every search attempt (8 sources × the fill's 15-second retries).
    // Three counter-measures: a per-resolver COOLDOWN after consecutive
    // failures, STICKINESS to the resolver that last worked, and a short
    // NEGATIVE cache so a host that failed all paths fails fast for a
    // while instead of re-paying the timeouts.
    private val resolverFailures = ConcurrentHashMap<String, Int>()
    private val resolverCooldownUntil = ConcurrentHashMap<String, Long>()
    private val resolverSuccessAt = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastGoodResolver: String? = null

    private class Negative(val at: Long)
    private val negativeCache = ConcurrentHashMap<String, Negative>()

    private fun noteResolverFailure(template: String) {
        val n = (resolverFailures[template] ?: 0) + 1
        resolverFailures[template] = n
        if (n >= 3) {
            resolverCooldownUntil[template] = System.currentTimeMillis() + RESOLVER_COOLDOWN_MS
            resolverFailures[template] = 0
            WepWawetLogger.appWarn("$TAG: resolver cooling down",
                "resolver" to template.substringBefore('?'),
                "cooldown_ms" to RESOLVER_COOLDOWN_MS)
        }
    }

    private fun noteResolverSuccess(template: String) {
        resolverFailures[template] = 0
        resolverSuccessAt[template] = System.currentTimeMillis()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // Never DoH-resolve the DoH resolvers themselves (they're by-IP
        // anyway) or localhost.
        if (hostname == "1.1.1.1" || hostname == "8.8.8.8" || hostname == "localhost") {
            return Dns.SYSTEM.lookup(hostname)
        }
        cache[hostname]?.let { return it }

        // v0.8.26: the negative cache — a host that failed EVERY path
        // fails fast for NEG_TTL_MS instead of re-paying 8s of DoH
        // timeouts on every search attempt.
        negativeCache[hostname]?.let {
            if (System.currentTimeMillis() - it.at < NEG_TTL_MS) {
                throw UnknownHostException("Failed to resolve '$hostname' (recently failed all paths)")
            }
            negativeCache.remove(hostname)
        }

        // 1) system resolver first (fast path, no overhead when it works)
        val system = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            null
        }
        if (system != null && system.isNotEmpty()) {
            return system
        }

        // 2) DoH fallback — the user's network is dropping these lookups.
        // v0.8.26: try the LAST GOOD resolver first (stickiness), skip
        // cooled-down resolvers, and record failures for the cooldown.
        dohUsed++
        val now = System.currentTimeMillis()
        val ordered = RESOLVERS.sortedWith(
            compareByDescending<String> { it == lastGoodResolver }
                .thenBy { resolverCooldownUntil[it] ?: 0L })
        for (template in ordered) {
            if ((resolverCooldownUntil[template] ?: 0L) > now) {
                WepWawetLogger.appInfo("$TAG: resolver skipped — cooling down",
                    "resolver" to template.substringBefore('?'),
                    "host" to hostname)
                continue
            }
            val url = template.format(hostname)
            val answer = try {
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "WallpaperSuite")
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        WepWawetLogger.appInfo("$TAG: DoH resolver HTTP error",
                            "resolver" to url.substringBefore('?'),
                            "code" to resp.code,
                            "host" to hostname)
                        null
                    } else {
                        val body = org.json.JSONObject(resp.body?.string() ?: return@use null)
                        val addrs = ArrayList<InetAddress>()
                        val answers = body.optJSONArray("Answer") ?: org.json.JSONArray()
                        for (i in 0 until answers.length()) {
                            val a = answers.optJSONObject(i) ?: continue
                            if (a.optInt("type") == 1) { // A record
                                runCatching { addrs.add(InetAddress.getByName(a.optString("data"))) }.onFailure { WepWawetLogger.appWarn("DohDns: addrs — best-effort call failed", "exception" to it.javaClass.simpleName) }
                            }
                        }
                        addrs
                    }
                }
            } catch (e: Exception) {
                noteResolverFailure(template)
                WepWawetLogger.appInfo("$TAG: DoH resolver failed",
                    "resolver" to url.substringBefore('?'),
                    "host" to hostname,
                    "exception" to e.javaClass.simpleName)
                null
            }
            if (!answer.isNullOrEmpty()) {
                dohOk++
                noteResolverSuccess(template)
                lastGoodResolver = template
                WepWawetLogger.appInfo("$TAG: resolved via DoH ✓",
                    "host" to hostname,
                    "resolver" to url.substringBefore('?'),
                    "addresses" to answer.joinToString(",") { it.hostAddress ?: "?" })
                cache[hostname] = answer
                return answer
            }
        }
        WepWawetLogger.appWarn("$TAG: DoH fallback exhausted — host unresolvable",
            "host" to hostname,
            "doh_used" to dohUsed,
            "doh_ok" to dohOk)
        negativeCache[hostname] = Negative(System.currentTimeMillis())
        throw UnknownHostException("Failed to resolve '$hostname' (system DNS and DoH)")
    }
}
