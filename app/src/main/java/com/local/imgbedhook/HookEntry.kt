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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
        private const val PREFS_NAME = "imgbed_hook_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_AUTH_CODE = "auth_code"

        private val executor = Executors.newSingleThreadExecutor()
        private val handledUris = Collections.synchronizedSet(HashSet<String>())
        private var currentActivityRef: WeakReference<Activity>? = null
        @Volatile
        private var appContext: Context? = null

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
            Log.d(TAG, msg)
        }

        fun getConfig(context: Context): Pair<String, String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val baseUrl = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty()
            val authCode = prefs.getString(KEY_AUTH_CODE, "")?.trim().orEmpty()
            return Pair(baseUrl, authCode)
        }

        fun saveConfig(context: Context, baseUrl: String, authCode: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_AUTH_CODE, authCode)
                .apply()
            log("Saved config: baseUrl=$baseUrl, authCode=${if (authCode.isNotEmpty()) "***" else "(empty)"}")
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

                    // 仅关注媒体/文档保存流，排除临时缓存流
                    if (uriStr.startsWith("content://media/") ||
                        uriStr.contains("document") ||
                        uriStr.contains("storage")
                    ) {
                        log("openOutputStream intercepted for: $uriStr")
                        val resolver = param.thisObject as ContentResolver
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
        if (!handledUris.add(uriStr)) {
            log("URI already handled, skip: $uriStr")
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            showChoiceDialog(resolver, uri)
        }, 100)

        executor.execute {
            try {
                Thread.sleep(5000)
                handledUris.remove(uriStr)
            } catch (_: Exception) {}
        }
    }

    private fun showConfigDialog(activity: Activity, onSaved: (() -> Unit)? = null) {
        val (currentBaseUrl, currentAuthCode) = getConfig(activity)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val dp16 = (16 * activity.resources.displayMetrics.density).toInt()
            val dp8 = (8 * activity.resources.displayMetrics.density).toInt()
            setPadding(dp16, dp8, dp16, dp8)
        }

        val tvUrl = TextView(activity).apply {
            text = "图床域名 / 基础 URL："
            textSize = 14f
        }
        val etUrl = EditText(activity).apply {
            hint = "例如: https://your-imgbed.pages.dev"
            setText(currentBaseUrl)
            setSingleLine()
        }

        val tvAuth = TextView(activity).apply {
            text = "上传认证密码 AuthCode (无密码请留空)："
            textSize = 14f
            val dp12 = (12 * activity.resources.displayMetrics.density).toInt()
            setPadding(0, dp12, 0, 0)
        }
        val etAuth = EditText(activity).apply {
            hint = "选填"
            setText(currentAuthCode)
            setSingleLine()
        }

        container.addView(tvUrl)
        container.addView(etUrl)
        container.addView(tvAuth)
        container.addView(etAuth)

        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⚙️ 图床参数配置")
            .setView(container)
            .setPositiveButton("保存") { dialog, _ ->
                var url = etUrl.text.toString().trim()
                val auth = etAuth.text.toString().trim()

                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    while (url.endsWith("/")) {
                        url = url.substring(0, url.length - 1)
                    }
                }

                saveConfig(activity, url, auth)
                Toast.makeText(activity, "图床配置已保存！", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onSaved?.invoke()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showChoiceDialog(resolver: ContentResolver, uri: Uri) {
        val activity = currentActivityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            log("Cannot show dialog: current Activity is null or finishing!")
            val ctx = appContext ?: AndroidAppHelper.currentApplication()
            ctx?.let {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(it, "图片已保存（未捕获前台窗口，已保留本地）", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        val (baseUrl, authCode) = getConfig(activity)
        val message = if (baseUrl.isNotEmpty()) {
            "当前图床: $baseUrl\n请选择后续操作："
        } else {
            "当前尚未配置图床！\n请点击【⚙️ 配置】填写你的私有图床域名。"
        }

        log("Showing choice dialog on Activity: ${activity.javaClass.simpleName}")
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("图片已保存")
            .setMessage(message)
            .setNegativeButton("正常保存") { dialog, _ ->
                log("User selected: 正常保存")
                dialog.dismiss()
            }
            .setPositiveButton("保存并上传图床") { dialog, _ ->
                if (baseUrl.isEmpty()) {
                    dialog.dismiss()
                    Toast.makeText(activity, "请先配置图床域名！", Toast.LENGTH_SHORT).show()
                    showConfigDialog(activity) {
                        showChoiceDialog(resolver, uri)
                    }
                    return@setPositiveButton
                }

                log("User selected: 保存并上传图床")
                dialog.dismiss()
                Toast.makeText(activity, "正在上传图床...", Toast.LENGTH_SHORT).show()
                executor.execute {
                    uploadAndCopy(resolver, uri, baseUrl, authCode)
                }
            }
            .setNeutralButton("⚙️ 配置") { dialog, _ ->
                dialog.dismiss()
                showConfigDialog(activity) {
                    showChoiceDialog(resolver, uri)
                }
            }
            .setCancelable(true)
            .show()
    }

    private fun uploadAndCopy(resolver: ContentResolver, uri: Uri, baseUrl: String, authCode: String) {
        try {
            log("Starting upload for URI: $uri to $baseUrl")
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
            val uploadEndpoint = if (authCode.isNotEmpty()) {
                "$baseUrl/upload?authCode=$authCode"
            } else {
                "$baseUrl/upload"
            }

            val conn = (URL(uploadEndpoint).openConnection() as HttpURLConnection).apply {
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
                    val markdown = "![$dateTitle]($baseUrl$src)"
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
