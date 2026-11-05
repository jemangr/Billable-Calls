package gr.wsol.billablecalls.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The update from inside the app: the APK of the release is downloaded to the app's cache and
 * Android's installer opens (the user confirms). The first time, Android asks to allow
 * «install unknown apps» for Billable Calls.
 */
object Installer {
    /** Downloads [url]; [progress] 0–100. Null if it failed. */
    suspend fun download(context: Context, url0: String, progress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, "BillableCalls.apk")
            var url = URL(url0)
            var c: HttpURLConnection
            var hops = 0
            while (true) {
                c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "BillableCalls-app")
                val code = c.responseCode
                if (code in 300..399 && hops++ < 6) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
                break
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); done += n
                            if (total > 0) progress((done * 100 / total).toInt())
                        }
                    }
                }
                f
            } finally { c.disconnect() }
        }.getOrNull()
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowInstalls(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
