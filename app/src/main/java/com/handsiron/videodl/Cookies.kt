package com.handsiron.videodl

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/** 需要登录的网站。cookieUrls 用来从 WebView 里读 Cookie，domain 写进 cookies.txt */
data class LoginSite(val key: String, val label: String, val loginUrl: String, val domain: String, val cookieUrls: List<String>)

object Cookies {
    val sites = listOf(
        LoginSite("instagram", "Instagram", "https://www.instagram.com/accounts/login/", ".instagram.com",
            listOf("https://www.instagram.com", "https://i.instagram.com")),
        LoginSite("facebook", "Facebook", "https://m.facebook.com/login/", ".facebook.com",
            listOf("https://www.facebook.com", "https://m.facebook.com")),
        LoginSite("douyin", "抖音", "https://www.douyin.com/", ".douyin.com",
            listOf("https://www.douyin.com", "https://douyin.com")),
        LoginSite("x", "X", "https://x.com/i/flow/login", ".x.com",
            listOf("https://x.com", "https://twitter.com")),
        LoginSite("bilibili", "B站", "https://passport.bilibili.com/h5-app/passport/login", ".bilibili.com",
            listOf("https://www.bilibili.com", "https://passport.bilibili.com", "https://m.bilibili.com")),
    )

    private fun dir(c: Context) = File(c.filesDir, "cookies").apply { mkdirs() }
    private fun siteFile(c: Context, s: LoginSite) = File(dir(c), s.key + ".txt")

    fun loggedIn(c: Context): List<LoginSite> = sites.filter { siteFile(c, it).let { f -> f.exists() && f.length() > 0 } }

    /** 从 WebView 读出这个网站的 Cookie，存成 Netscape 格式。返回保存的条数 */
    fun capture(c: Context, s: LoginSite): Int {
        val cm = CookieManager.getInstance()
        cm.flush()
        val pairs = LinkedHashMap<String, String>()
        for (u in s.cookieUrls) {
            val raw = cm.getCookie(u) ?: continue
            raw.split(";").forEach { part ->
                val i = part.indexOf('=')
                if (i > 0) pairs[part.substring(0, i).trim()] = part.substring(i + 1).trim()
            }
        }
        if (pairs.isEmpty()) return 0
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        pairs.forEach { (k, v) -> sb.append("${s.domain}\tTRUE\t/\tTRUE\t$expiry\t$k\t$v\n") }
        siteFile(c, s).writeText(sb.toString())
        return pairs.size
    }

    fun clear(c: Context, s: LoginSite) {
        siteFile(c, s).delete()
        val cm = CookieManager.getInstance()
        for (u in s.cookieUrls) {
            cm.getCookie(u)?.split(";")?.forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isNotEmpty()) {
                    cm.setCookie(u, "$name=; Max-Age=0; Path=/")
                    cm.setCookie(u, "$name=; Max-Age=0; Path=/; Domain=${s.domain}")
                }
            }
        }
        cm.flush()
    }

    /** 把所有已登录网站的 Cookie 合成一个文件给 yt-dlp；没有登录任何网站就返回 null */
    fun combined(c: Context, workDir: File): File? {
        val files = loggedIn(c).map { siteFile(c, it) }
        if (files.isEmpty()) return null
        val out = File(workDir, "cookies.txt")
        out.writeText("# Netscape HTTP Cookie File\n" + files.joinToString("") { f ->
            f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.joinToString("\n", postfix = "\n")
        })
        return out
    }
}
