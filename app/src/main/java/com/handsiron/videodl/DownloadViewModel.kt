package com.handsiron.videodl

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Quality(val label: String) {
    BEST("最佳"), P1080("1080p"), P720("720p"), AUDIO("仅音频")
}

data class SavedItem(val name: String, val uri: Uri, val mime: String)

/** 下载完成后请用户确认文件名；result 里返回不含扩展名的新名字 */
class RenameAsk(val original: String, val ext: String, val title: String, val result: CompletableDeferred<String>)

class DownloadViewModel(app: Application) : AndroidViewModel(app) {
    val url = mutableStateOf("")
    val quality = mutableStateOf(Quality.BEST)
    val busy = mutableStateOf(false)
    val progress = mutableStateOf(0f)
    val status = mutableStateOf("")
    val log = mutableStateOf("")
    val version = mutableStateOf("")
    val saved = mutableStateListOf<SavedItem>()
    val renameAsk = mutableStateOf<RenameAsk?>(null)
    val loggedSites = mutableStateListOf<LoginSite>()

    private val processId = "videodl-main"
    private var autoUpdateTried = false

    init {
        val err = App.initError
        status.value = if (err != null) "初始化失败：$err" else "粘贴链接，或在 YouTube / X 里点「分享」选本应用"
        refreshVersion()
        refreshLogins()
        autoUpdateOncePerDay()
    }

    private fun ctx(): Context = getApplication()

    fun refreshLogins() {
        loggedSites.clear()
        loggedSites.addAll(Cookies.loggedIn(ctx()))
    }

    fun logout(s: LoginSite) {
        Cookies.clear(ctx(), s)
        refreshLogins()
    }

    private fun refreshVersion() {
        version.value = YoutubeDL.versionName(ctx()) ?: YoutubeDL.version(ctx()) ?: "未知"
    }

    private fun autoUpdateOncePerDay() {
        if (autoUpdateTried || App.initError != null) return
        autoUpdateTried = true
        val prefs = ctx().getSharedPreferences("videodl", Context.MODE_PRIVATE)
        val last = prefs.getLong("last_update", 0L)
        if (System.currentTimeMillis() - last < 24L * 3600 * 1000) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.updateYoutubeDL(ctx(), YoutubeDL.UpdateChannel.STABLE)
                prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                withContext(Dispatchers.Main) { refreshVersion() }
            } catch (_: Throwable) { }
        }
    }

    fun updateEngine() {
        if (busy.value) return
        busy.value = true
        status.value = "正在更新下载引擎 yt-dlp…"
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                when (YoutubeDL.updateYoutubeDL(ctx(), YoutubeDL.UpdateChannel.STABLE)) {
                    YoutubeDL.UpdateStatus.DONE -> "更新完成"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "已经是最新版"
                    else -> "更新结束"
                }
            } catch (e: Throwable) {
                "更新失败：${e.message}"
            }
            withContext(Dispatchers.Main) {
                refreshVersion()
                status.value = msg
                busy.value = false
            }
        }
    }

    fun onShared(text: String?) {
        val link = extractUrl(text) ?: return
        url.value = link
        if (!busy.value) start()
    }

    fun start() {
        val link = extractUrl(url.value)
        if (link == null) { status.value = "没找到有效链接"; return }
        if (busy.value) return
        if (App.initError != null) { status.value = "初始化失败，无法下载"; return }
        url.value = link
        busy.value = true
        progress.value = 0f
        log.value = ""
        status.value = "正在解析…"

        viewModelScope.launch(Dispatchers.IO) {
            val pm = ctx().getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoDL:download")
            wl.acquire(2 * 60 * 60 * 1000L)
            val workDir = File(ctx().cacheDir, "dl/" + System.currentTimeMillis()).apply { mkdirs() }
            try {
                val req = buildRequest(link, workDir, quality.value)
                val resp = YoutubeDL.execute(req, processId) { p, eta, line ->
                    viewModelScope.launch(Dispatchers.Main) {
                        if (p >= 0) progress.value = p / 100f
                        status.value = if (p > 0) "下载中 ${"%.1f".format(p)}%  剩余约 ${eta}s" else "处理中…"
                        if (line.isNotBlank()) log.value = (log.value + "\n" + line).takeLast(4000)
                    }
                }
                val dbg = resp.err.lines().filter {
                    it.contains("ffmpeg", true) || it.contains("exe versions") || it.contains("WARNING") || it.contains("Merg")
                }.joinToString("\n")
                withContext(Dispatchers.Main) {
                    if (dbg.isNotBlank()) log.value = (log.value + "\n" + dbg).takeLast(4000)
                    status.value = "正在保存到相册…"
                }
                val files = workDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }.toList()
                if (files.isEmpty()) throw IllegalStateException("没有生成文件")
                val finalFiles = mergeIfSplit(files, workDir)
                val items = finalFiles.mapIndexed { i, f -> saveToMediaStore(f, askName(f, i, finalFiles.size)) }
                withContext(Dispatchers.Main) {
                    saved.addAll(0, items)
                    progress.value = 1f
                    status.value = "完成：" + items.joinToString("、") { it.name }
                }
            } catch (e: YoutubeDL.CanceledException) {
                withContext(Dispatchers.Main) { status.value = "已取消" }
            } catch (e: Throwable) {
                val m = (e.message ?: e.toString()).lines().lastOrNull { it.contains("ERROR") } ?: (e.message ?: e.toString())
                withContext(Dispatchers.Main) {
                    val needLogin = listOf("login", "cookies", "sign in", "private", "Requested content is not available")
                        .any { m.contains(it, true) }
                    status.value = "失败：" + m.take(300) +
                        if (needLogin) "\n（这个视频可能要登录才能下载，可以先点「登录网站」）" else "\n（可以先点「更新引擎」再试）"
                    log.value = (log.value + "\n" + (e.message ?: "")).takeLast(4000)
                }
            } finally {
                workDir.deleteRecursively()
                if (wl.isHeld) wl.release()
                withContext(Dispatchers.Main) { busy.value = false }
            }
        }
    }

    fun diagnose() {
        if (busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val (code, out) = runFfmpeg(listOf("-hide_banner", "-version"))
            withContext(Dispatchers.Main) {
                log.value = "[ffmpeg 自检 code=$code]\n" + out.take(1500)
            }
        }
    }

    fun cancel() {
        YoutubeDL.destroyProcessById(processId)
    }

    private fun buildRequest(link: String, dir: File, q: Quality): YoutubeDLRequest {
        val r = YoutubeDLRequest(link)
        r.addOption("-o", dir.absolutePath + "/%(title).80B [%(id)s].%(ext)s")
        r.addOption("--no-mtime")
        r.addOption("--no-playlist")
        r.addOption("--verbose")
        Cookies.combined(ctx(), dir)?.let { r.addOption("--cookies", it.absolutePath) }
        when (q) {
            Quality.AUDIO -> {
                r.addOption("-f", "ba[ext=m4a]/ba/b")
                r.addOption("-x")
                r.addOption("--audio-format", "m4a")
            }
            else -> {
                r.addOption("-f", "bv*+ba/b")
                val sort = when (q) {
                    Quality.P1080 -> "res:1080,vcodec:h264,ext:mp4:m4a"
                    Quality.P720 -> "res:720,vcodec:h264,ext:mp4:m4a"
                    else -> "res,ext:mp4:m4a"
                }
                r.addOption("-S", sort)
                r.addOption("--merge-output-format", "mp4")
            }
        }
        return r
    }

    private val fmtRegex = Regex("^(.*)\\.f[0-9A-Za-z_-]+\\.([A-Za-z0-9]+)$")
    private val audioExts = setOf("m4a", "mp3", "opus", "ogg", "aac", "weba")

    /** yt-dlp 没能合并时，自己用内置 ffmpeg 把视频轨和音频轨合成一个 mp4 */
    private suspend fun mergeIfSplit(files: List<File>, dir: File): List<File> {
        // 一条推文可能有多个视频，每个视频各自是「视频轨 + 音频轨」，按文件名分组分别合并
        val groups = files.filter { fmtRegex.matches(it.name) }
            .groupBy { fmtRegex.find(it.name)!!.groupValues[1] }
            .filter { it.value.size >= 2 }
        var result = files
        for ((_, split) in groups) result = mergePair(result, split, dir)
        return result
    }

    private suspend fun mergePair(files: List<File>, split: List<File>, dir: File): List<File> {
        val audio = split.firstOrNull { it.extension.lowercase() in audioExts }
            ?: split.minByOrNull { it.length() }!!
        val video = split.filter { it != audio }.maxByOrNull { it.length() } ?: return files
        val base = fmtRegex.find(video.name)!!.groupValues[1]
        val out = File(dir, "$base.mp4")
        withContext(Dispatchers.Main) { status.value = "正在合并视频和音频…" }
        val (code, output) = runFfmpeg(listOf(
            "-y", "-i", video.absolutePath, "-i", audio.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
            "-movflags", "+faststart", out.absolutePath
        ))
        if (code == 0 && out.exists() && out.length() > 0) {
            return files.filter { it !in split } + out
        }
        withContext(Dispatchers.Main) {
            log.value = (log.value + "\n[ffmpeg 合并失败 code=$code，改用系统合并]\n" + output.takeLast(800)).takeLast(4000)
        }
        out.delete()
        return try {
            muxMerge(video, audio, out)
            withContext(Dispatchers.Main) { log.value = (log.value + "\n[系统合并成功]").takeLast(4000) }
            files.filter { it !in split } + out
        } catch (e: Throwable) {
            out.delete()
            withContext(Dispatchers.Main) {
                log.value = (log.value + "\n[系统合并也失败] " + (e.message ?: e.toString())).takeLast(4000)
            }
            files
        }
    }

    /** 用 Android 自带的 MediaExtractor + MediaMuxer 合并，不依赖 ffmpeg */
    private fun muxMerge(video: File, audio: File, out: File) {
        val vEx = android.media.MediaExtractor().apply { setDataSource(video.absolutePath) }
        val aEx = android.media.MediaExtractor().apply { setDataSource(audio.absolutePath) }
        fun findTrack(ex: android.media.MediaExtractor, prefix: String): Int {
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith(prefix)) return i
            }
            return -1
        }
        val vt = findTrack(vEx, "video/")
        val at = findTrack(aEx, "audio/")
        if (vt < 0 || at < 0) { vEx.release(); aEx.release(); throw IllegalStateException("找不到视频轨或音频轨 v=$vt a=$at") }
        vEx.selectTrack(vt); aEx.selectTrack(at)
        val muxer = android.media.MediaMuxer(out.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vIdx = muxer.addTrack(vEx.getTrackFormat(vt))
            val aIdx = muxer.addTrack(aEx.getTrackFormat(at))
            muxer.start()
            val buf = java.nio.ByteBuffer.allocate(4 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            fun copy(ex: android.media.MediaExtractor, idx: Int) {
                while (true) {
                    buf.clear()
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) break
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if ((ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(idx, buf, info)
                    ex.advance()
                }
            }
            copy(vEx, vIdx)
            copy(aEx, aIdx)
            muxer.stop()
        } finally {
            try { muxer.release() } catch (_: Throwable) {}
            vEx.release(); aEx.release()
        }
    }

    private fun runFfmpeg(args: List<String>): Pair<Int, String> {
        val c = ctx()
        val bin = File(c.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val pkgs = File(c.noBackupFilesDir, "youtubedl-android/packages")
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] =
            File(pkgs, "ffmpeg/usr/lib").absolutePath + ":" + File(pkgs, "python/usr/lib").absolutePath
        pb.environment()["TMPDIR"] = c.cacheDir.absolutePath
        return try {
            val p = pb.start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor() to text
        } catch (e: Exception) {
            -1 to (e.message ?: e.toString())
        }
    }

    private val idSuffix = Regex("\\s*\\[[^\\]]+\\]$")

    /** 弹框让用户改名，默认是视频原标题 */
    private suspend fun askName(f: File, index: Int, total: Int): String {
        val title = f.nameWithoutExtension.replace(idSuffix, "").ifBlank { f.nameWithoutExtension }
        val original = if (total > 1) "$title (${index + 1})" else title
        val label = if (total > 1) "保存为（第 ${index + 1} 个，共 $total 个）" else "保存为"
        val ask = RenameAsk(original, f.extension, label, CompletableDeferred())
        withContext(Dispatchers.Main) {
            status.value = "下载完成，请确认文件名"
            renameAsk.value = ask
        }
        val name = ask.result.await().trim().ifBlank { original }
        withContext(Dispatchers.Main) {
            renameAsk.value = null
            status.value = "正在保存到相册…"
        }
        return name
    }

    private fun saveToMediaStore(f: File, baseName: String): SavedItem {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in listOf("m4a", "mp3", "opus", "ogg", "aac")) "audio/$ext" else "video/$ext"
        val isAudio = mime.startsWith("audio")
        val collection = if (isAudio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val rel = (if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES) + "/VideoDL"
        val displayName = (baseName.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(150) + "." + f.extension)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val cr = ctx().contentResolver
        val uri = cr.insert(collection, values) ?: throw IllegalStateException("无法写入相册")
        cr.openOutputStream(uri).use { out -> f.inputStream().use { it.copyTo(out!!) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return SavedItem(displayName, uri, mime)
    }

    companion object {
        private val urlRegex = Regex("https?://[^\\s<>\"']+")
        fun extractUrl(text: String?): String? = text?.let { urlRegex.find(it)?.value?.trimEnd('.', ',', ')', '，', '。') }
    }
}
