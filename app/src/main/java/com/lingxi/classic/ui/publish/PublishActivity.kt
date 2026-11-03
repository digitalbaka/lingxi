package com.lingxi.classic.ui.publish

import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.reflect.TypeToken
import com.lingxi.classic.R
import com.lingxi.classic.data.api.ApiClient
import com.lingxi.classic.data.auth.AuthManager
import com.lingxi.classic.data.model.ApiResponse
import com.lingxi.classic.data.model.Partition
import com.lingxi.classic.data.model.PublishRequest
import com.lingxi.classic.data.model.UploadResult
import com.lingxi.classic.databinding.ActivityPublishBinding
import com.lingxi.classic.ui.auth.LoginActivity
import com.lingxi.classic.util.FormatUtils
import com.lingxi.classic.util.ImageLoader
import com.lingxi.classic.util.toast
import com.lingxi.classic.util.toastLong
import com.lingxi.classic.util.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** 投稿：视频/音频（带上传进度）与文章（Markdown） */
class PublishActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPublishBinding

    private var currentKind = KIND_VIDEO
    private var pickedUri: Uri? = null
    private var pickedBytes: ByteArray? = null
    private var pickedName = ""
    private var mediaDurationSec = 0L
    private var mediaWidth = 0
    private var mediaHeight = 0
    private var mediaFps = 0
    private var uploadedUrl: String? = null
    private var coverUrl: String = ""

    private var partitions: List<Partition> = emptyList()
    private var selectedPartition = ""

    /** 大文件直接读进内存：上限 150MB */
    private val maxMediaBytes = 150L * 1024 * 1024

    private val uploadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onMediaPicked(uri)
    }
    private val pickCover = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadCover(uri)
    }

    companion object {
        private const val KIND_VIDEO = "video"
        private const val KIND_AUDIO = "audio"
        private const val KIND_ARTICLE = "article"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isLoggedIn) {
            toast("请先登录后再投稿")
            startActivity(android.content.Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding = ActivityPublishBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tabVideo.setOnClickListener { switchKind(KIND_VIDEO) }
        binding.tabAudio.setOnClickListener { switchKind(KIND_AUDIO) }
        binding.tabArticle.setOnClickListener { switchKind(KIND_ARTICLE) }

        binding.btnPickFile.setOnClickListener {
            pickMedia.launch(if (currentKind == KIND_AUDIO) "audio/*" else "video/*")
        }
        binding.btnUpload.setOnClickListener { startUpload() }
        binding.btnPickCover.setOnClickListener { pickCover.launch("image/*") }
        binding.btnPublish.setOnClickListener { publish() }

        loadPartitions()
    }

    // ==================== 类型切换 ====================

    private fun switchKind(kind: String) {
        if (kind == currentKind) return
        currentKind = kind
        val tabs = listOf(binding.tabVideo to KIND_VIDEO, binding.tabAudio to KIND_AUDIO, binding.tabArticle to KIND_ARTICLE)
        tabs.forEach { (tab, k) ->
            val selected = k == kind
            tab.setTextColor(androidx.core.content.ContextCompat.getColor(this, if (selected) R.color.lx_green_dark else R.color.lx_text_sub))
            tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        val isArticle = kind == KIND_ARTICLE
        binding.groupMedia.visible = !isArticle
        binding.etContent.visible = isArticle
        // 换类型后之前的文件与上传结果不再适用
        pickedUri = null
        pickedBytes = null
        uploadedUrl = null
        binding.tvFileInfo.text = "未选择（≤150MB）"
        binding.groupUpload.visible = false
    }

    // ==================== 分区 ====================

    private fun loadPartitions() {
        lifecycleScope.launch {
            try {
                val resp = ApiClient.apiService.getPartitions()
                val data = resp.data
                if (resp.isSuccess && data != null) {
                    partitions = data.items
                    val names = mutableListOf("请选择分区")
                    names.addAll(partitions.map { it.name })
                    val spinnerAdapter = ArrayAdapter(
                        this@PublishActivity,
                        android.R.layout.simple_spinner_item,
                        names
                    ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    binding.spinnerPartition.adapter = spinnerAdapter
                    binding.spinnerPartition.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            selectedPartition = if (position in 1..partitions.size) partitions[position - 1].slug else ""
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) { selectedPartition = "" }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // ==================== 媒体文件选择 ====================

    private fun onMediaPicked(uri: Uri) {
        binding.tvFileInfo.text = "读取文件中…"
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { readUriBytes(uri, maxMediaBytes) }
            if (bytes == null) {
                binding.tvFileInfo.text = "未选择（≤150MB）"
                toastLong("文件过大或读取失败（最大支持 150MB）")
                return@launch
            }
            pickedUri = uri
            pickedBytes = bytes
            pickedName = queryDisplayName(uri) ?: if (currentKind == KIND_AUDIO) "audio.mp3" else "video.mp4"
            extractMediaMeta(uri)
            uploadedUrl = null
            binding.groupUpload.visible = true
            binding.progressUpload.progress = 0
            binding.tvProgress.text = "0%"
            binding.tvFileInfo.text = "$pickedName（${FormatUtils.formatBytes(bytes.size.toLong())}" +
                (if (mediaDurationSec > 0) "，${FormatUtils.formatDuration(mediaDurationSec)}" else "") + "）"
        }
    }

    private fun readUriBytes(uri: Uri, maxBytes: Long): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) { null }
    }

    private fun extractMediaMeta(uri: Uri) {
        mediaDurationSec = 0; mediaWidth = 0; mediaHeight = 0; mediaFps = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            mediaDurationSec = durationMs / 1000
            mediaWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            mediaHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (Build.VERSION.SDK_INT >= 23) {
                mediaFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.toInt() ?: 0
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // ==================== 上传（带进度） ====================

    private fun startUpload() {
        val bytes = pickedBytes
        if (bytes == null) { toast("请先选择文件"); return }
        val scope = if (currentKind == KIND_AUDIO) "audio" else "videos"

        binding.btnUpload.isEnabled = false
        lifecycleScope.launch {
            try {
                val fileBody = bytes.toRequestBody("application/octet-stream".toMediaType())
                val countingBody = CountingRequestBody(fileBody) { written, total ->
                    runOnUiThread {
                        val percent = if (total > 0) (written * 100 / total).toInt() else 0
                        binding.progressUpload.progress = percent
                        binding.tvProgress.text = "$percent%"
                    }
                }
                val part = MultipartBody.Part.createFormData("file", pickedName, countingBody)
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addPart(part)
                    .build()
                val request = Request.Builder()
                    .url("${ApiClient.BASE_URL}/api/v1/upload?scope=$scope")
                    .post(multipart)
                    .header("Authorization", "Bearer ${AuthManager.token}")
                    .header("X-Device-Id", AuthManager.deviceId)
                    .header("X-Device-Label", AuthManager.DEVICE_LABEL)
                    .build()

                val result = withContext(Dispatchers.IO) {
                    uploadClient.newCall(request).execute().use { response ->
                        val bodyStr = response.body?.string() ?: return@withContext null
                        val type = object : TypeToken<ApiResponse<UploadResult>>() {}.type
                        ApiClient.gson.fromJson<ApiResponse<UploadResult>>(bodyStr, type)
                    }
                }
                val url = result?.data?.url
                if (result != null && result.isSuccess && !url.isNullOrEmpty()) {
                    uploadedUrl = url
                    binding.progressUpload.progress = 100
                    binding.tvProgress.text = "上传完成"
                    toast("上传成功，可以发布了")
                } else {
                    toast(result?.message?.ifBlank { "上传失败" } ?: "上传失败")
                }
            } catch (e: Exception) {
                toast("上传失败：${e.message}")
            } finally {
                binding.btnUpload.isEnabled = true
            }
        }
    }

    // ==================== 封面 ====================

    private fun uploadCover(uri: Uri) {
        binding.tvCoverState.text = "上传中…"
        lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { readUriBytes(uri, 10L * 1024 * 1024) }
                if (bytes == null) {
                    binding.tvCoverState.text = "未选择"
                    toast("封面读取失败或超过 10MB")
                    return@launch
                }
                val name = queryDisplayName(uri) ?: "cover.jpg"
                val body = bytes.toRequestBody("application/octet-stream".toMediaType())
                val part = MultipartBody.Part.createFormData("file", name, body)
                val resp = ApiClient.apiService.upload("covers", part)
                val url = resp.data?.url
                if (resp.isSuccess && !url.isNullOrEmpty()) {
                    coverUrl = url
                    binding.tvCoverState.text = "已上传"
                    binding.ivCoverPreview.visible = true
                    ImageLoader.load(binding.ivCoverPreview, ApiClient.fullUrl(url))
                } else {
                    binding.tvCoverState.text = "上传失败"
                    toast(resp.message.ifBlank { "封面上传失败" })
                }
            } catch (e: Exception) {
                binding.tvCoverState.text = "上传失败"
                toast("封面上传失败：${e.message}")
            }
        }
    }

    // ==================== 发布 ====================

    private fun publish() {
        val title = binding.etTitle.text.toString().trim()
        if (title.isEmpty()) { toast("请填写标题"); return }
        if (selectedPartition.isEmpty()) { toast("请选择分区"); return }

        val request = if (currentKind == KIND_ARTICLE) {
            val content = binding.etContent.text.toString()
            if (content.isBlank()) { toast("请填写正文"); return }
            PublishRequest(
                kind = "article",
                title = title,
                content = content,
                partition = selectedPartition,
                cover = coverUrl,
                description = binding.etDesc.text.toString().trim(),
                visibility = 0
            )
        } else {
            val src = uploadedUrl
            if (src.isNullOrEmpty()) { toast("请先上传文件"); return }
            PublishRequest(
                kind = currentKind,
                title = title,
                src = src,
                partition = selectedPartition,
                tags = binding.etTags.text.toString()
                    .split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
                duration = mediaDurationSec,
                width = mediaWidth,
                height = mediaHeight,
                fps = mediaFps,
                description = binding.etDesc.text.toString().trim(),
                cover = coverUrl,
                contentLabels = emptyList(),
                visibility = 0
            )
        }

        binding.btnPublish.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = if (currentKind == KIND_ARTICLE)
                    ApiClient.apiService.publishArticle(request)
                else
                    ApiClient.apiService.publishVideo(request)
                if (resp.isSuccess) {
                    toastLong("已提交审核")
                    finish()
                } else {
                    toast(resp.message.ifBlank { "发布失败" })
                }
            } catch (e: Exception) {
                toast("网络错误：${e.message}")
            } finally {
                binding.btnPublish.isEnabled = true
            }
        }
    }

    // ==================== 工具 ====================

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) { null }
    }

    /** 包装 RequestBody，上报写入进度 */
    private class CountingRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (written: Long, total: Long) -> Unit
    ) : RequestBody() {

        override fun contentType() = delegate.contentType()

        override fun contentLength(): Long = try { delegate.contentLength() } catch (e: Exception) { -1L }

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            val countingSink = object : ForwardingSink(sink) {
                var written = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onProgress(written, total)
                }
            }
            val buffered = countingSink.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }
}
