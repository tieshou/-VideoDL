package com.handsiron.videodl

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** 在 App 里打开网站登录页，登录完点「完成」把 Cookie 存下来 */
class LoginActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val site = Cookies.sites.firstOrNull { it.key == intent.getStringExtra("site") } ?: run { finish(); return }
        CookieManager.getInstance().setAcceptCookie(true)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                var web by remember { mutableStateOf<WebView?>(null) }
                var loading by remember { mutableStateOf(true) }
                BackHandler { if (web?.canGoBack() == true) web?.goBack() else finish() }
                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("登录 ${site.label}") },
                        navigationIcon = { TextButton(onClick = { finish() }) { Text("关闭") } },
                        actions = {
                            Button(onClick = {
                                val n = Cookies.capture(this@LoginActivity, site)
                                if (n > 0) {
                                    Toast.makeText(this@LoginActivity, "已保存 ${site.label} 登录", Toast.LENGTH_SHORT).show()
                                    finish()
                                } else {
                                    Toast.makeText(this@LoginActivity, "还没读到登录信息，请先完成登录", Toast.LENGTH_LONG).show()
                                }
                            }, modifier = Modifier.padding(end = 8.dp)) { Text("完成") }
                        }
                    )
                }) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("登录成功、看到网站首页后，点右上角「完成」。", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                // 去掉 WebView 标记，部分网站会拦截内嵌浏览器登录
                                settings.userAgentString = settings.userAgentString.replace("; wv", "")
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webChromeClient = WebChromeClient()
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { loading = true }
                                    override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                                }
                                loadUrl(site.loginUrl)
                                web = this
                            }
                        })
                    }
                }
            }
        }
    }
}
