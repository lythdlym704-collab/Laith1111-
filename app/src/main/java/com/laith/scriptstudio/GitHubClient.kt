package com.laith.scriptstudio

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * عميل GitHub API: تسجيل الدخول، إنشاء/حذف المستودعات، رفع الملفات،
 * متابعة حالة البناء (Actions)، وتنزيل ملف APK الناتج.
 */
object GitHubClient {

    private const val API = "https://api.github.com"

    data class Response(val code: Int, val body: String)
    data class UploadResult(val ok: Boolean, val message: String, val repoUrl: String = "")
    data class RunInfo(val id: Long, val status: String, val conclusion: String)

    private fun request(
        method: String,
        url: String,
        token: String,
        body: String? = null
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "ScriptStudio-App")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Response(code, text)
        } finally {
            conn.disconnect()
        }
    }

    fun login(token: String): String? {
        return try {
            val r = request("GET", "$API/user", token)
            if (r.code == 200) JSONObject(r.body).optString("login").ifBlank { null } else null
        } catch (e: Exception) {
            null
        }
    }

    fun ensureRepo(token: String, owner: String, repo: String): Boolean {
        return try {
            val r = request("GET", "$API/repos/$owner/$repo", token)
            if (r.code == 200) return true
            val body = JSONObject()
                .put("name", repo)
                .put("private", true)
                .put("auto_init", true)
                .put("description", "مشروع من تطبيق ستوديو السكربت")
                .toString()
            val c = request("POST", "$API/user/repos", token, body)
            c.code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /** يحذف المستودع نهائياً من GitHub (يتطلب صلاحية delete_repo في الرمز). */
    fun deleteRepo(token: String, owner: String, repo: String): Boolean {
        return try {
            val r = request("DELETE", "$API/repos/$owner/$repo", token)
            r.code == 204 || r.code == 404
        } catch (e: Exception) {
            false
        }
    }

    fun putFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        bytes: ByteArray,
        message: String
    ): Boolean {
        return try {
            val url = "$API/repos/$owner/$repo/contents/$path"
            val existing = request("GET", url, token)
            val sha = if (existing.code == 200)
                JSONObject(existing.body).optString("sha").ifBlank { null } else null
            val obj = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            if (sha != null) obj.put("sha", sha)
            val r = request("PUT", url, token, obj.toString())
            r.code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    /** يحذف ملفاً من المستودع إن كان موجوداً. */
    fun deleteFileIfExists(token: String, owner: String, repo: String, path: String) {
        try {
            val url = "$API/repos/$owner/$repo/contents/$path"
            val existing = request("GET", url, token)
            if (existing.code != 200) return
            val sha = JSONObject(existing.body).optString("sha").ifBlank { return }
            val body = JSONObject()
                .put("message", "تنظيف من ستوديو السكربت")
                .put("sha", sha)
                .toString()
            request("DELETE", url, token, body)
        } catch (e: Exception) {
            // تجاهل
        }
    }

    /** آخر تشغيل لـ Actions في المستودع. */
    fun latestRun(token: String, owner: String, repo: String): RunInfo? {
        return try {
            val r = request("GET", "$API/repos/$owner/$repo/actions/runs?per_page=1", token)
            if (r.code != 200) return null
            val runs = JSONObject(r.body).optJSONArray("workflow_runs") ?: return null
            if (runs.length() == 0) return null
            val o = runs.getJSONObject(0)
            RunInfo(
                id = o.optLong("id"),
                status = o.optString("status"),
                conclusion = o.optString("conclusion")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** أحدث تشغيل ناجح (حتى لو كانت تشغيلات أحدث ما تزال تعمل). */
    fun latestSuccessfulRun(token: String, owner: String, repo: String): RunInfo? {
        return try {
            val r = request(
                "GET",
                "$API/repos/$owner/$repo/actions/runs?status=success&per_page=1",
                token
            )
            if (r.code != 200) return null
            val runs = JSONObject(r.body).optJSONArray("workflow_runs") ?: return null
            if (runs.length() == 0) return null
            val o = runs.getJSONObject(0)
            RunInfo(
                id = o.optLong("id"),
                status = o.optString("status"),
                conclusion = o.optString("conclusion")
            )
        } catch (e: Exception) {
            null
        }
    }

    data class RunProgress(val done: Int, val total: Int, val currentStep: String)

    private val stepNamesAr = mapOf(
        "Set up job" to "بدء المهمة",
        "Checkout" to "تحميل ملفات المشروع",
        "Set up Java 17" to "تجهيز جافا",
        "Set up Android SDK" to "تجهيز أدوات أندرويد",
        "Set up Gradle" to "تجهيز Gradle",
        "Build debug APK" to "بناء ملف APK",
        "Upload APK" to "رفع ملف APK",
        "Complete job" to "إنهاء المهمة"
    )

    /** تقدّم البناء: عدد الخطوات المكتملة من الإجمالي واسم الخطوة الحالية. */
    fun runProgress(token: String, owner: String, repo: String, runId: Long): RunProgress? {
        return try {
            val r = request("GET", "$API/repos/$owner/$repo/actions/runs/$runId/jobs", token)
            if (r.code != 200) return null
            val jobs = JSONObject(r.body).optJSONArray("jobs") ?: return null
            var done = 0
            var total = 0
            var current = ""
            for (j in 0 until jobs.length()) {
                val steps = jobs.getJSONObject(j).optJSONArray("steps") ?: continue
                for (i in 0 until steps.length()) {
                    val st = steps.getJSONObject(i)
                    val name = st.optString("name")
                    if (name.startsWith("Post ")) continue
                    total++
                    when (st.optString("status")) {
                        "completed" -> done++
                        "in_progress" -> if (current.isEmpty()) {
                            current = stepNamesAr[name] ?: name
                        }
                    }
                }
            }
            if (total == 0) null else RunProgress(done, total, current)
        } catch (e: Exception) {
            null
        }
    }

    /** رابط تنزيل أول ملف ناتج من تشغيل ناجح. */
    fun latestArtifactUrl(token: String, owner: String, repo: String, runId: Long): String? {
        return try {
            val r = request("GET", "$API/repos/$owner/$repo/actions/runs/$runId/artifacts", token)
            if (r.code != 200) return null
            val arts = JSONObject(r.body).optJSONArray("artifacts") ?: return null
            if (arts.length() == 0) return null
            arts.getJSONObject(0).optString("archive_download_url").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /** ينزّل ملفاً (مع معالجة إعادة التوجيه إلى مخزن GitHub دون إرسال الرمز إليه). */
    fun downloadToFile(token: String, url: String, dest: File): Boolean {
        return try {
            var current = url
            var useAuth = true
            repeat(5) {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "ScriptStudio-App")
                if (useAuth) {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                }
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                val code = conn.responseCode
                when {
                    code in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: return false
                        conn.disconnect()
                        current = loc
                        useAuth = false
                    }
                    code == 200 -> {
                        dest.parentFile?.mkdirs()
                        conn.inputStream.use { input ->
                            dest.outputStream().use { input.copyTo(it) }
                        }
                        conn.disconnect()
                        return true
                    }
                    else -> {
                        conn.disconnect()
                        return false
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * يرفع المشروع كمشروع أندرويد كامل: GitHub يبنيه وينتج APK حقيقياً
     * باسم التطبيق وصورته وصلاحياته التي اختارها المستخدم.
     */
    fun uploadProject(context: Context, project: Project, token: String): UploadResult {
        val user = login(token)
            ?: return UploadResult(false, "رمز الدخول غير صالح أو لا يوجد اتصال بالإنترنت")

        val repo = project.githubRepo.ifBlank { ProjectStore.sanitizeRepoName(project.name) }
        if (!ensureRepo(token, user, repo)) {
            return UploadResult(false, "تعذّر إنشاء المستودع على GitHub")
        }

        val appId = AppTemplate.appId(repo)
        val hasIcon = project.iconFile(context).isFile
        val userFiles = project.listUserFiles(context)
        val textExtensions = setOf(
            "html", "htm", "js", "css", "txt", "json", "md", "py", "sh", "xml", "csv"
        )

        val files = mutableListOf<Pair<String, ByteArray>>()
        fun add(path: String, text: String) {
            files.add(path to text.toByteArray(Charsets.UTF_8))
        }

        // كل ملفات المستخدم تدخل داخل التطبيق المولَّد
        var hasIndex = false
        for (f in userFiles) {
            files.add("app/src/main/assets/" + f.name to f.readBytes())
            if (f.name.equals("index.html", ignoreCase = true)) hasIndex = true
        }
        if (!hasIndex) {
            val mainText = userFiles
                .firstOrNull { it.extension.lowercase() in textExtensions }
                ?.let { runCatching { it.readText() }.getOrNull() }
            val page = if (mainText != null && ScriptAnalyzer.classify(mainText).isWeb) {
                mainText
            } else {
                AppTemplate.indexHtml(
                    project.appName,
                    mainText ?: "أضف ملفات تطبيقك من ستوديو السكربت.",
                    false
                )
            }
            add("app/src/main/assets/index.html", page)
        }

        add("app.json", project.appJson())
        add("README.md", "# " + project.appName + "\n\nمشروع من تطبيق ستوديو السكربت.\n")

        add("settings.gradle", AppTemplate.settingsGradle())
        add("build.gradle", AppTemplate.rootBuildGradle())
        add("gradle.properties", AppTemplate.gradleProperties())
        add("app/build.gradle", AppTemplate.appBuildGradle(appId))
        add("app/src/main/AndroidManifest.xml", AppTemplate.manifestXml(project.permissions))
        add(
            "app/src/main/java/com/scriptstudio/generated/MainActivity.kt",
            AppTemplate.mainActivityKt()
        )
        add("app/src/main/res/values/strings.xml", AppTemplate.stringsXml(project.appName))
        add("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", AppTemplate.launcherXml())
        add("app/src/main/res/drawable/launcher_bg.xml", AppTemplate.iconBgXml())
        if (hasIcon) {
            files.add(
                "app/src/main/res/drawable/launcher_fg.png" to project.iconFile(context).readBytes()
            )
            deleteFileIfExists(token, user, repo, "app/src/main/res/drawable/launcher_fg.xml")
        } else {
            add("app/src/main/res/drawable/launcher_fg.xml", AppTemplate.iconFgVector())
            deleteFileIfExists(token, user, repo, "app/src/main/res/drawable/launcher_fg.png")
        }

        // ملف البناء التلقائي يُرفع دائماً مع كل مشروع
        add(".github/workflows/build-apk.yml", AppTemplate.workflowYml())
        deleteFileIfExists(token, user, repo, ".github/workflows/build.yml")

        // كل الملفات تُرفع بوسم [skip ci] حتى لا يبدأ بناء لكل ملف،
        // ثم دفعة أخيرة واحدة تُطلق بناءً واحداً فقط.
        val failed = mutableListOf<String>()
        for ((path, bytes) in files) {
            if (!putFile(token, user, repo, path, bytes, "تحديث من ستوديو السكربت [skip ci]")) {
                failed.add(path)
            }
        }
        if (failed.isEmpty()) {
            val stamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date())
            if (!putFile(
                    token, user, repo, "build-trigger.txt",
                    stamp.toByteArray(Charsets.UTF_8),
                    "بدء بناء واحد من ستوديو السكربت"
                )
            ) {
                failed.add("build-trigger.txt")
            }
        }

        val url = "https://github.com/" + user + "/" + repo
        return if (failed.isEmpty()) {
            UploadResult(true, "تم رفع المشروع، وبدأ GitHub ببناء ملف APK\n" + url, url)
        } else {
            var msg = "اكتمل الرفع جزئياً. فشل رفع: " + failed.joinToString("، ") + "\n" + url
            if (failed.any { it.startsWith(".github/workflows/") }) {
                msg += "\n\nتنبيه: فشل رفع ملف البناء التلقائي. سجّل الخروج ثم سجّل الدخول" +
                    " برمز جديد يحمل صلاحية workflow."
            }
            UploadResult(false, msg, url)
        }
    }
}
