package com.papacasper.squeeze

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity

/** The site's own login page in a WebView; closes itself once the site sets its logged-in cookie. */
class LoginActivity : ComponentActivity() {
    private lateinit var site: SiteLogins.Site
    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = SiteLogins.Site.valueOf(intent.getStringExtra(EXTRA_SITE) ?: return finish())
        title = "Log in to ${site.label}"
        val web = WebView(this)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Google refuses sign-in from anything that says it's a WebView ("; wv", "Version/4.0").
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!done && SiteLogins.isLoggedIn(site)) {
                    done = true
                    Toast.makeText(this@LoginActivity, "Logged in to ${site.label}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
        setContentView(web)
        web.loadUrl(site.loginUrl)
    }

    // Also on back: a login that finished on a page we didn't catch is still saved.
    override fun onPause() {
        super.onPause()
        SiteLogins.export(applicationContext)
    }

    companion object {
        private const val EXTRA_SITE = "site"
        fun start(context: Context, site: SiteLogins.Site) =
            context.startActivity(Intent(context, LoginActivity::class.java).putExtra(EXTRA_SITE, site.name))
    }
}
