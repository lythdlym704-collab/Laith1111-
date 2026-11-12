package com.laith.scriptstudio

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipInputStream

/** تنزيل ملف APK الناتج من تشغيل ناجح وفتح المثبّت — يُستخدم للمشاريع ولتحديث التطبيق نفسه. */
object ApkInstaller {

    fun downloadAndInstall(
        activity: Activity,
        token: String,
        owner: String,
        repo: String,
        runId: Long,
        onDone: (Boolean) -> Unit
    ) {
        Thread {
            var apk: File? = null
            val url = GitHubClient.latestArtifactUrl(token, owner, repo, runId)
            if (url != null) {
                val zip = File(activity.cacheDir, "apk/artifact.zip")
                if (GitHubClient.downloadToFile(token, url, zip)) {
                    apk = unzipApk(zip, File(activity.cacheDir, "apk"))
                }
            }
            val ok = apk != null && apk.isFile
            activity.runOnUiThread {
                if (ok) launch(activity, apk!!)
                onDone(ok)
            }
        }.start()
    }

    fun unzipApk(zip: File, outDir: File): File? {
        return try {
            outDir.mkdirs()
            ZipInputStream(zip.inputStream()).use { stream ->
                var entry = stream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                        val out = File(outDir, "app.apk")
                        out.outputStream().use { stream.copyTo(it) }
                        return out
                    }
                    entry = stream.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun launch(activity: Activity, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.install_failed, Toast.LENGTH_LONG).show()
        }
    }
}
