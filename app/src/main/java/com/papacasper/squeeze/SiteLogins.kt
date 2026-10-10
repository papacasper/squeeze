package com.papacasper.squeeze

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Logins for sites that hide some videos from logged-out viewers (X's sensitive posts, YouTube's age-restricted
 * videos). The user logs in once in [LoginActivity]; the WebView's cookies are exported to a private Netscape
 * cookies.txt that every yt-dlp run gets with --cookies. Nothing leaves the phone except to the site itself.
 */
object SiteLogins {
    /** [authCookie] is the cookie the site only sets once logged in; [hosts] are exported to yt-dlp. */
    enum class Site(val label: String, val loginUrl: String, val hosts: List<String>, val authCookie: String) {
        X("X (Twitter)", "https://x.com/i/flow/login", listOf("x.com", "twitter.com"), "auth_token"),
        YOUTUBE(
            "YouTube",
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F",
            listOf("youtube.com", "google.com"),
            "SAPISID"
        )
    }

    fun cookiesFile(context: Context) = File(context.filesDir, "cookies.txt")

    /** The file to hand yt-dlp, or null when no site is logged in. */
    fun cookiesFor(context: Context): File? = cookiesFile(context).takeIf { it.length() > 0 }

    private fun cookieHeader(host: String): String? = CookieManager.getInstance().getCookie("https://$host")

    fun isLoggedIn(site: Site): Boolean =
        site.hosts.any { host -> parse(cookieHeader(host)).any { it.first == site.authCookie } }

    /** Rewrites cookies.txt from the WebView's cookies for every site. */
    fun export(context: Context) {
        CookieManager.getInstance().flush()
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = Site.entries.flatMap { site ->
            // X's cookies live on x.com but yt-dlp may still call twitter.com, so both get the x.com set.
            val primary = parse(cookieHeader(site.hosts.first()))
            site.hosts.flatMap { host -> netscapeLines(host, parse(cookieHeader(host)).ifEmpty { primary }, expiry) }
        }
        val file = cookiesFile(context)
        if (lines.isEmpty()) file.delete() else file.writeText(NETSCAPE_HEADER + lines.joinToString("\n", postfix = "\n"))
    }

    /** Expires every cookie of [site] in the WebView, then re-exports so yt-dlp stops using them. */
    fun logOut(context: Context, site: Site) {
        val cm = CookieManager.getInstance()
        site.hosts.forEach { host ->
            parse(cookieHeader(host)).forEach { (name, _) ->
                listOf("", "; Domain=.$host").forEach { domain ->
                    cm.setCookie("https://$host", "$name=; Max-Age=0; Path=/$domain")
                }
            }
        }
        export(context)
    }

    /** "a=b; c=d" (CookieManager's format) to name/value pairs. */
    fun parse(header: String?): List<Pair<String, String>> =
        header.orEmpty().split(';').map { it.trim() }.mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }

    /** Netscape cookies.txt lines for [host] and its subdomains. CookieManager hides flags, so all are secure, path /. */
    fun netscapeLines(host: String, cookies: List<Pair<String, String>>, expiry: Long): List<String> =
        cookies.map { (name, value) -> listOf(".$host", "TRUE", "/", "TRUE", expiry.toString(), name, value).joinToString("\t") }

    private const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File\n"
}
