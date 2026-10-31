package ru.egger.audit

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileOutputStream

/**
 * Оболочка для HTML-приложения протокола (assets/index.html).
 * Даёт странице: камеру (через <input capture>), GPS, сохранение PDF в «Загрузки» и отправку.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    private var pdfFile: File? = null
    private var pdfOut: FileOutputStream? = null

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val cb = fileCallback
        fileCallback = null
        val uri = photoUri
        cb?.onReceiveValue(if (ok && uri != null) arrayOf(uri) else null)
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else {
            fileCallback?.onReceiveValue(null); fileCallback = null
            alert("Нет доступа к камере", "Разрешите приложению использовать камеру в настройках телефона.")
        }
    }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingGeo?.let { (origin, cb) -> cb.invoke(origin, hasLocation(), false) }
        pendingGeo = null
        if (hasLocation()) checkGpsEnabled()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                if (granted(Manifest.permission.CAMERA)) launchCamera()
                else cameraPermission.launch(Manifest.permission.CAMERA)
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (hasLocation()) callback.invoke(origin, true, false)
                else {
                    pendingGeo = origin to callback
                    locationPermission.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.onAndroidBack ? onAndroidBack() : false") { r ->
                    if (r != "true") finish()
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        if (hasLocation()) checkGpsEnabled()
        else locationPermission.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
    private fun hasLocation() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun checkGpsEnabled() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(this)
                .setTitle("GPS выключен")
                .setMessage("Для нанесения координат на фотографии включите определение местоположения.")
                .setPositiveButton("Включить") { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                .setNegativeButton("Позже", null)
                .show()
        }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "photos").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            photoUri = uri
            takePicture.launch(uri)
        } catch (e: Exception) {
            fileCallback?.onReceiveValue(null); fileCallback = null
            alert("Камера недоступна", e.message ?: "Не удалось открыть камеру")
        }
    }

    private fun alert(title: String, msg: String) = runOnUiThread {
        AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()
    }

    private fun closePdf() { try { pdfOut?.flush(); pdfOut?.close() } catch (e: Exception) { } ; pdfOut = null }

    /** Методы, доступные странице как window.AndroidBridge */
    inner class Bridge {
        @JavascriptInterface
        fun beginFile(name: String) {
            closePdf()
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "protocol.pdf" }
            val dir = File(cacheDir, "pdf").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            pdfFile = File(dir, safe)
            pdfOut = FileOutputStream(pdfFile)
        }

        @JavascriptInterface
        fun appendChunk(b64: String) { pdfOut?.write(Base64.decode(b64, Base64.DEFAULT)) }

        /** Копирует PDF в «Загрузки/Проверки контрагентов». Возвращает текст для пользователя. */
        @JavascriptInterface
        fun finishFile(): String {
            closePdf()
            val src = pdfFile ?: return "Файл не сформирован"
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Проверки контрагентов")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return "Не удалось создать файл в «Загрузках»"
                    contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                    "Сохранено: Загрузки/Проверки контрагентов/${src.name}"
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
                    val dst = File(dir, src.name); src.copyTo(dst, overwrite = true)
                    "Сохранено: ${dst.absolutePath}"
                }
            } catch (e: Exception) { "Ошибка сохранения: ${e.message}" }
        }

        /** Открывает системное окно «Поделиться» (почта, мессенджеры, диск). */
        @JavascriptInterface
        fun shareFile() {
            closePdf()
            val f = pdfFile ?: return
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension.replace('_', ' '))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runOnUiThread { startActivity(Intent.createChooser(send, "Отправить протокол")) }
        }
    }
}
