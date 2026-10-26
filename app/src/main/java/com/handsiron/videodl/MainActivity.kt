package com.handsiron.videodl

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private val vm: DownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen(vm) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshLogins()
    }

    fun openLogin(site: LoginSite) {
        startActivity(Intent(this, LoginActivity::class.java).putExtra("site", site.key))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            vm.onShared(i.getStringExtra(Intent.EXTRA_TEXT))
            i.action = null
        }
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun openItem(item: SavedItem) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (_: Exception) { }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(vm: DownloadViewModel) {
    val act = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val busy by vm.busy
    LaunchedEffect(busy) { act.keepScreenOn(busy) }

    Scaffold(topBar = { TopAppBar(title = { Text("视频下载") }) }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = vm.url.value,
                onValueChange = { vm.url.value = it },
                label = { Text("视频链接") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val t = cm.primaryClip?.getItemAt(0)?.coerceToText(act)?.toString()
                    DownloadViewModel.extractUrl(t)?.let { vm.url.value = it }
                }) { Text("粘贴") }
                OutlinedButton(enabled = !busy, onClick = { vm.url.value = "" }) { Text("清空") }
            }

            Text("清晰度", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.values().forEach { q ->
                    FilterChip(
                        selected = vm.quality.value == q,
                        onClick = { if (!busy) vm.quality.value = q },
                        label = { Text(q.label) }
                    )
                }
            }

            if (!busy) {
                Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("下载", fontSize = 18.sp)
                }
            } else {
                LinearProgressIndicator(progress = { vm.progress.value }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            }

            Text(vm.status.value, style = MaterialTheme.typography.bodyMedium)

            vm.renameAsk.value?.let { ask -> RenameDialog(ask) }

            HorizontalDivider()
            var showSites by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.loggedSites.isEmpty()) "未登录任何网站" else "已登录：" + vm.loggedSites.joinToString("、") { it.label },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                )
                TextButton(enabled = !busy, onClick = { showSites = !showSites }) { Text(if (showSites) "收起" else "登录网站") }
            }
            if (showSites) {
                Text("Instagram、抖音、Facebook 等要登录才能看的视频，先在这里登录一次。登录信息只存在本机。",
                    style = MaterialTheme.typography.bodySmall)
                Cookies.sites.forEach { site ->
                    val on = vm.loggedSites.any { it.key == site.key }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(site.label + if (on) "（已登录）" else "", modifier = Modifier.weight(1f))
                        TextButton(onClick = { act.openLogin(site) }) { Text(if (on) "重新登录" else "登录") }
                        if (on) TextButton(onClick = { vm.logout(site) }) { Text("退出") }
                    }
                }
            }

            if (vm.saved.isNotEmpty()) {
                HorizontalDivider()
                Text("已下载（保存在相册 Movies/VideoDL）", style = MaterialTheme.typography.labelLarge)
                vm.saved.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(item.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { act.openItem(item) }) { Text("打开") }
                    }
                }
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("App ${BuildConfig.VERSION_NAME} · yt-dlp ${vm.version.value}", modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy, onClick = { vm.diagnose() }) { Text("自检") }
                TextButton(enabled = !busy, onClick = { vm.updateEngine() }) { Text("更新引擎") }
            }
            if (vm.log.value.isNotBlank()) {
                SelectionContainer {
                    Text(vm.log.value.takeLast(1500), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun RenameDialog(ask: RenameAsk) {
    var name by remember(ask) { mutableStateOf(ask.original) }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("保存为") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    suffix = { Text("." + ask.ext) },
                    trailingIcon = { if (name.isNotEmpty()) TextButton(onClick = { name = "" }) { Text("清空") } }
                )
                Text("默认是原标题，可以直接修改。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { ask.result.complete(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { ask.result.complete(ask.original) }) { Text("用原名") } },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)
    )
}
