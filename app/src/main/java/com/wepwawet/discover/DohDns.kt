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

    /** By-IP HTTPS JSON DoH endpoints (no bootstrap DNS needed). */
    private val RESOLVERS = listOf(
        "https://1.1.1.1/dns-query?name=%s&type=A",
        "https://8.8.8.8/resolve?name=%s&type=A",
    )

    @Volatile
    private var dohUsed = 0
    @Volatile
    private var dohOk = 0

    override fun lookup(hostname: String): List<InetAddress> {
        // Never DoH-resolve the DoH resolvers themselves (they're by-IP
        // anyway) or localhost.
        if (hostname == "1.1.1.1" || hostname == "8.8.8.8" || hostname == "localhost") {
            return Dns.SYSTEM.lookup(hostname)
        }
        cache[hostname]?.let { return it }

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
        dohUsed++
        for (template in RESOLVERS) {
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
                                runCatching { addrs.add(InetAddress.getByName(a.optString("data"))) }
                            }
                        }
                        addrs
                    }
                }
            } catch (e: Exception) {
                WepWawetLogger.appInfo("$TAG: DoH resolver failed",
                    "resolver" to url.substringBefore('?'),
                    "host" to hostname,
                    "exception" to e.javaClass.simpleName)
                null
            }
            if (!answer.isNullOrEmpty()) {
                dohOk++
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
        // Cache the failure briefly? No — throw the original failure so
        // OkHttp's normal error path (and our source ladder) applies.
        throw UnknownHostException("Failed to resolve '$hostname' (system DNS and DoH)")
    }
}
