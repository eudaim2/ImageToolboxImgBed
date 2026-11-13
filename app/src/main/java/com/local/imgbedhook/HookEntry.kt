package com.local.imgbedhook

import android.app.Activity
import android.app.AlertDialog
import android.app.AndroidAppHelper
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import java.io.DataOutputStream
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
        private const val TARGET_PKG = "ru.tech.imageresizershrinker"
        private const val BASE_URL = "https://imgbed1026.pages.dev"
        // 若后台开启了上传认证密码，改为 "$BASE_URL/upload?authCode=你的密码"
        private const val UPLOAD_URL = "$BASE_URL/upload"

        private val executor = Executors.newSingleThreadExecutor()
        private val handledUris = Collections.synchronizedSet(HashSet<String>())
        private var currentActivityRef: WeakReference<Activity>? = null
        @Volatile
        private var appContext: Context? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        // 1. 追踪 Image Toolbox 当前处于前台的 Activity，供弹窗使用
        XposedBridge.hookAllMethods(
            Instrumentation::class.java,
            "callActivityOnResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.args.getOrNull(0) as? Activity ?: return
                    if (activity.packageName == TARGET_PKG) {
                        currentActivityRef = WeakReference(activity)
                        appContext = activity.applicationContext
                    }
                }
            }
        )

        XposedBridge.hookAllMethods(
            Instrumentation::class.java,
            "callActivityOnPause",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.args.getOrNull(0) as? Activity ?: return
                    if (currentActivityRef?.get() === activity) {
                        currentActivityRef = null
                    }
                }
            }
        )

        // 2. 监听媒体库文件写入完成（IS_PENDING -> 0）
        XposedBridge.hookAllMethods(
            ContentResolver::class.java,
            "update",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val uri = param.args.getOrNull(0) as? Uri ?: return
                    val values = param.args.getOrNull(1) as? ContentValues ?: return

                    if (!uri.toString().startsWith("content://media/")) return
                    val isPending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING)
                    if (isPending != 0) return

                    if (!handledUris.add(uri.toString())) return

                    val resolver = param.thisObject as ContentResolver
                    showChoiceDialog(resolver, uri)
                }
            }
        )
    }

    private fun showChoiceDialog(resolver: ContentResolver, uri: Uri) {
        Handler(Looper.getMainLooper()).post {
            val activity = currentActivityRef?.get()
            // 若当前无可用前台 Activity，默认保持仅本地保存，不执行上传
            if (activity == null || activity.isFinishing || activity.isDestroyed) return@post

            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("图片已保存")
                .setMessage("请选择后续操作：")
                .setNegativeButton("正常保存") { dialog, _ ->
                    dialog.dismiss()
                }
                .setPositiveButton("保存并上传图床") { dialog, _ ->
                    dialog.dismiss()
                    Toast.makeText(activity, "正在上传图床...", Toast.LENGTH_SHORT).show()
                    executor.execute {
                        uploadAndCopy(resolver, uri)
                    }
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun uploadAndCopy(resolver: ContentResolver, uri: Uri) {
        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            if (bytes.isEmpty()) return

            var fileName = "image_${System.currentTimeMillis()}.jpg"
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

            // 确保文件名有合适扩展名，以便图床识别 MIME 类型
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
