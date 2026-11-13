package com.local.imgbedhook

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.AndroidAppHelper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "ImageToolboxImgBed"
        private const val TARGET_PKG = "ru.tech.imageresizershrinker"
        private const val BASE_URL = "https://imgbed1026.pages.dev"
        // 若后台开启了上传认证密码，改为 "$BASE_URL/upload?authCode=你的密码"
        private const val UPLOAD_URL = "$BASE_URL/upload"

        private val executor = Executors.newSingleThreadExecutor()
        private val handledUris = Collections.synchronizedSet(HashSet<String>())
        private var currentActivityRef: WeakReference<Activity>? = null
        @Volatile
        private var appContext: Context? = null

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
            Log.d(TAG, msg)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        log("Hook initialized in target process: ${lpparam.processName}")

        // 1. 通过 Application 生命周期官方接口，精准追踪前台 Activity
        XposedBridge.hookAllMethods(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    appContext = app
                    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(activity: Activity) {
                            currentActivityRef = WeakReference(activity)
                            log("Activity resumed: ${activity.javaClass.simpleName}")
                        }

                        override fun onActivityPaused(activity: Activity) {}
                        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                            currentActivityRef = WeakReference(activity)
                        }

                        override fun onActivityStarted(activity: Activity) {
                            currentActivityRef = WeakReference(activity)
                        }

                        override fun onActivityStopped(activity: Activity) {}
                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                        override fun onActivityDestroyed(activity: Activity) {
                            if (currentActivityRef?.get() === activity) {
                                currentActivityRef = null
                            }
                        }
                    })
                    log("Registered ActivityLifecycleCallbacks successfully")
                }
            }
        )

        // 兜底直接 Hook Activity.onResume
        XposedBridge.hookAllMethods(
            Activity::class.java,
            "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.packageName == TARGET_PKG) {
                        currentActivityRef = WeakReference(activity)
                        appContext = activity.applicationContext
                    }
                }
            }
        )

        // 2. 拦截方案 A：监听 openOutputStream 关闭（覆盖 SAF 自定义文件夹及相册直接流写入）
        XposedBridge.hookAllMethods(
            ContentResolver::class.java,
            "openOutputStream",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val uri = param.args.getOrNull(0) as? Uri ?: return
                    val rawOs = param.result as? OutputStream ?: return
                    val uriStr = uri.toString()

                    // 仅关注媒体/文档保存流，排除无意义的临时缓存流
                    if (uriStr.startsWith("content://media/") ||
                        uriStr.contains("document") ||
                        uriStr.contains("storage")
                    ) {
                        log("openOutputStream intercepted for: $uriStr")
                        val resolver = param.thisObject as ContentResolver
                        // 包装 OutputStream，在 close 完毕后触发保存完成弹窗
                        param.result = object : FilterOutputStream(rawOs) {
                            override fun close() {
                                try {
                                    super.close()
                                } finally {
                                    log("OutputStream closed for: $uriStr")
                                    onImageSaveCompleted(resolver, uri)
                                }
                            }
                        }
                    }
                }
            }
        )

        // 3. 拦截方案 B：监听 ContentResolver.update（针对 Scoped Storage IS_PENDING 模式）
        XposedBridge.hookAllMethods(
            ContentResolver::class.java,
            "update",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val uri = param.args.getOrNull(0) as? Uri ?: return
                    val values = param.args.getOrNull(1) as? ContentValues ?: return
                    val isPending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)

                    log("ContentResolver.update: uri=$uri, isPending=$isPending")
                    if (isPending == 0) {
                        log("IS_PENDING changed to 0 for: $uri")
                        val resolver = param.thisObject as ContentResolver
                        onImageSaveCompleted(resolver, uri)
                    }
                }
            }
        )
    }

    private fun onImageSaveCompleted(resolver: ContentResolver, uri: Uri) {
        val uriStr = uri.toString()
        // 去重防止同一张图在 update 和 close 中重复触发
        if (!handledUris.add(uriStr)) {
            log("URI already handled, skip: $uriStr")
            return
        }

        // 短暂延迟 100ms 确保媒体库索引与文件系统完全落盘
        Handler(Looper.getMainLooper()).postDelayed({
            showChoiceDialog(resolver, uri)
        }, 100)

        // 5秒后允许同一 URI 再次触发（方便覆盖保存场景）
        executor.execute {
            try {
                Thread.sleep(5000)
                handledUris.remove(uriStr)
            } catch (_: Exception) {}
        }
    }

    private fun showChoiceDialog(resolver: ContentResolver, uri: Uri) {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            log("Cannot show dialog: current Activity is null or finishing! Falling back to Toast notification.")
            val ctx = appContext ?: AndroidAppHelper.currentApplication()
            ctx?.let {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(it, "图片已保存（未捕获前台窗口，已保留本地）", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        log("Showing choice dialog on Activity: ${activity.javaClass.simpleName}")
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("图片已保存")
            .setMessage("请选择后续操作：")
            .setNegativeButton("正常保存") { dialog, _ ->
                log("User selected: 正常保存")
                dialog.dismiss()
            }
            .setPositiveButton("保存并上传图床") { dialog, _ ->
                log("User selected: 保存并上传图床")
                dialog.dismiss()
                Toast.makeText(activity, "正在上传图床...", Toast.LENGTH_SHORT).show()
                executor.execute {
                    uploadAndCopy(resolver, uri)
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun uploadAndCopy(resolver: ContentResolver, uri: Uri) {
        try {
            log("Starting upload for URI: $uri")
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                log("Failed to read bytes from URI: $uri")
                copyToClipboardAndToast(null, "图床上传失败: 读取图片文件为空")
                return
            }

            var fileName = "image_${System.currentTimeMillis()}.jpg"
            try {
                resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                }
            } catch (e: Exception) {
                log("Query display name error: ${e.message}")
            }

            // 补充合适的扩展名
            if (!fileName.contains(".")) {
                val mime = resolver.getType(uri)
                val ext = when (mime) {
                    "image/png" -> ".png"
                    "image/webp" -> ".webp"
                    "image/gif" -> ".gif"
                    "image/heif", "image/heic" -> ".heic"
                    else -> ".jpg"
                }
                fileName += ext
            }

            log("Uploading image: $fileName (${bytes.size} bytes)")
            val boundary = "----Boundary${UUID.randomUUID()}"
            val conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                useCaches = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                val lineEnd = "\r\n"
                val twoHyphens = "--"

                val header = StringBuilder()
                    .append(twoHyphens).append(boundary).append(lineEnd)
                    .append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"").append(lineEnd)
                    .append("Content-Type: application/octet-stream").append(lineEnd)
                    .append(lineEnd)
                    .toString()

                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.write(lineEnd.toByteArray(Charsets.UTF_8))
                out.write("$twoHyphens$boundary$twoHyphens$lineEnd".toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = conn.responseCode
            val respText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            log("Upload response code: $code, body: $respText")

            if (code in 200..299) {
                val src = JSONArray(respText).optJSONObject(0)?.optString("src").orEmpty()
                if (src.isNotEmpty()) {
                    val dateTitle = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val markdown = "![$dateTitle]($BASE_URL$src)"
                    copyToClipboardAndToast(markdown, "已上传并复制 Markdown")
                    return
                }
            }
            copyToClipboardAndToast(null, "图床上传失败 ($code): $respText")
        } catch (e: Exception) {
            log("Upload exception: ${e.message}")
            XposedBridge.log(e)
            copyToClipboardAndToast(null, "图床上传异常: ${e.message}")
        }
    }

    private fun copyToClipboardAndToast(text: String?, toastMsg: String) {
        val context = currentActivityRef?.get() ?: appContext ?: AndroidAppHelper.currentApplication() ?: return
        Handler(Looper.getMainLooper()).post {
            if (text != null) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Markdown", text))
            }
            Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }
}
