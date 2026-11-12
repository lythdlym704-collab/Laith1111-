package com.laith.scriptstudio

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL

/**
 * عميل GitHub API: تسجيل الدخول، إنشاء/حذف المستودعات، رفع المشروع كاملاً
 * في commit واحد، متابعة حالة البناء وتقدّمه، وتنزيل ملف APK الناتج.
 */
object GitHubClient {

    private const val API = "https://api.github.com"

    data class Response(val code: Int, val body: String)
    data class UploadResult(val ok: Boolean, val message: String, val repoUrl: String = "")
    data class RunInfo(val id: Long, val status: String, val conclusion: String)
    data class RunProgress(val done: Int, val total: Int, val currentStep: String)

    private fun request(
        method: String,
        url: String,
        token: String,
        body: String? = null
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            try {
                conn.requestMethod = method
            } catch (e: ProtocolException) {
                return Response(-1, "method not supported")
            }
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "ScriptStudio-App")
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
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

    /** ينشئ المستودع فقط (بدون رفع ملفات أو بدء بناء). */
    fun createRepoOnly(token: String, project: Project): Boolean {
        val user = login(token) ?: return false
        val repo = project.githubRepo.ifBlank { ProjectStore.sanitizeRepoName(project.name) }
        return ensureRepo(token, user, repo)
    }

    fun deleteRepo(token: String, owner: String, repo: String): Boolean {
        return try {
            val r = request("DELETE", "$API/repos/$owner/$repo", token)
            r.code == 204 || r.code == 404
        } catch (e: Exception) {
            false
        }
    }

    fun putFile(
        token: String, owner: String, repo: String,
        path: String, bytes: ByteArray, message: String
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

    private fun defaultBranch(token: String, owner: String, repo: String): String {
        return try {
            val r = request("GET", "$API/repos/$owner/$repo", token)
            if (r.code == 200) JSONObject(r.body).optString("default_branch").ifBlank { "main" }
            else "main"
        } catch (e: Exception) {
            "main"
        }
    }

    /**
     * يرفع كل الملفات ويحذف المطلوب في commit واحد (Git Data API)،
     * فيبدأ بناء واحد فقط. يرجع false إن فشل ليُستخدم الأسلوب البديل.
     */
    fun commitAll(
        token: String, owner: String, repo: String,
        files: List<Pair<String, ByteArray>>,
        deletions: List<String>,
        syncPrefixes: List<String>,
        message: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean {
        return try {
            val branch = defaultBranch(token, owner, repo)
            var baseSha: String? = null
            for (i in 0 until 4) {
                val r = request("GET", "$API/repos/$owner/$repo/git/ref/heads/$branch", token)
                if (r.code == 200) {
                    baseSha = JSONObject(r.body).getJSONObject("object").optString("sha")
                    break
                }
                Thread.sleep(1500)
            }
            val base = baseSha ?: return false

            val cr = request("GET", "$API/repos/$owner/$repo/git/commits/$base", token)
            if (cr.code != 200) return false
            val baseTree = JSONObject(cr.body).getJSONObject("tree").optString("sha")

            // الملفات الموجودة حالياً في المستودع
            val existing = HashSet<String>()
            val tr = request("GET", "$API/repos/$owner/$repo/git/trees/$baseTree?recursive=1", token)
            if (tr.code == 200) {
                val arr = JSONObject(tr.body).optJSONArray("tree") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("type") == "blob") existing.add(o.optString("path"))
                }
            }

            val entries = JSONArray()
            val newPaths = files.map { it.first }.toSet()
            val blobCache = HashMap<String, String>()
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            for ((i, pair) in files.withIndex()) {
                val (path, bytes) = pair
                onProgress?.invoke(i + 1, files.size)
                val key = digest.digest(bytes).joinToString("") { "%02x".format(it) }
                val cached = blobCache[key]
                val blobSha = if (cached != null) cached else {
                    val blobBody = JSONObject()
                        .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        .put("encoding", "base64")
                        .toString()
                    val br = request("POST", "$API/repos/$owner/$repo/git/blobs", token, blobBody)
                    if (br.code !in 200..299) return false
                    JSONObject(br.body).optString("sha").also { blobCache[key] = it }
                }
                entries.put(
                    JSONObject().put("path", path).put("mode", "100644")
                        .put("type", "blob").put("sha", blobSha)
                )
            }

            val toDelete = LinkedHashSet<String>()
            deletions.filter { it in existing && it !in newPaths }.forEach { toDelete.add(it) }
            existing.filter { p -> syncPrefixes.any { p.startsWith(it) } && p !in newPaths }
                .forEach { toDelete.add(it) }
            for (path in toDelete) {
                entries.put(
                    JSONObject().put("path", path).put("mode", "100644")
                        .put("type", "blob").put("sha", JSONObject.NULL)
                )
            }

            val treeReq = JSONObject().put("base_tree", baseTree).put("tree", entries).toString()
            val tcr = request("POST", "$API/repos/$owner/$repo/git/trees", token, treeReq)
            if (tcr.code !in 200..299) return false
            val newTree = JSONObject(tcr.body).optString("sha")

            val commitReq = JSONObject()
                .put("message", message)
                .put("tree", newTree)
                .put("parents", JSONArray().put(base))
                .toString()
            val ccr = request("POST", "$API/repos/$owner/$repo/git/commits", token, commitReq)
            if (ccr.code !in 200..299) return false
            val newSha = JSONObject(ccr.body).optString("sha")

            val up = request(
                "PATCH", "$API/repos/$owner/$repo/git/refs/heads/$branch", token,
                JSONObject().put("sha", newSha).put("force", false).toString()
            )
            up.code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    fun latestRun(token: String, owner: String, repo: String): RunInfo? =
        fetchRun("$API/repos/$owner/$repo/actions/runs?per_page=1", token)

    fun latestSuccessfulRun(token: String, owner: String, repo: String): RunInfo? =
        fetchRun("$API/repos/$owner/$repo/actions/runs?status=success&per_page=1", token)

    private fun fetchRun(url: String, token: String): RunInfo? {
        return try {
            val r = request("GET", url, token)
            if (r.code != 200) return null
            val runs = JSONObject(r.body).optJSONArray("workflow_runs") ?: return null
            if (runs.length() == 0) return null
            val o = runs.getJSONObject(0)
            RunInfo(o.optLong("id"), o.optString("status"), o.optString("conclusion"))
        } catch (e: Exception) {
            null
        }
    }

    private val stepNamesAr = mapOf(
        "Set up job" to "بدء المهمة",
        "Checkout" to "تحميل ملفات المشروع",
        "Checkout Repository" to "تحميل ملفات المشروع",
        "Set up Java 17" to "تجهيز جافا",
        "Set up JDK 17" to "تجهيز جافا",
        "Set up Android SDK" to "تجهيز أدوات أندرويد",
        "Set up Gradle" to "تجهيز Gradle",
        "Build debug APK" to "بناء ملف APK",
        "Build APK" to "بناء ملف APK",
        "Build with Gradle" to "بناء ملف APK",
        "Collect APK files" to "تجميع ملفات APK",
        "Upload APK" to "رفع ملف APK",
        "Complete job" to "إنهاء المهمة"
    )

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
                        "in_progress" -> if (current.isEmpty()) current = stepNamesAr[name] ?: name
                    }
                }
            }
            if (total == 0) null else RunProgress(done, total, current)
        } catch (e: Exception) {
            null
        }
    }

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
     * يرفع المشروع ويبنيه حسب نوعه:
     * - أندرويد أصلي: الملفات كما هي + ملف بناء إن لم يكن له ملفه الخاص.
     * - ويب/عام: يغلَّف داخل تطبيق WebView.
     * كله في commit واحد → بناء واحد.
     */
    fun uploadProject(
        context: Context,
        project: Project,
        token: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): UploadResult {
        val user = login(token)
            ?: return UploadResult(false, "رمز الدخول غير صالح أو لا يوجد اتصال بالإنترنت")
        val repo = project.githubRepo.ifBlank { ProjectStore.sanitizeRepoName(project.name) }
        if (!ensureRepo(token, user, repo)) {
            return UploadResult(false, "تعذّر إنشاء المستودع على GitHub")
        }

        val userFiles = project.listUserFiles(context)
        if (userFiles.isEmpty()) {
            return UploadResult(false, "لا توجد ملفات للرفع. أضف ملفات التطبيق أولاً.")
        }
        val rel = userFiles.map { project.relativePath(context, it) }
        val kind = ProjectKind.detect(rel) { path ->
            val idx = rel.indexOf(path)
            if (idx < 0) null else runCatching { userFiles[idx].readText() }.getOrNull()
        }

        val files = mutableListOf<Pair<String, ByteArray>>()
        val deletions = mutableListOf<String>()
        val syncPrefixes = mutableListOf<String>()
        fun add(path: String, text: String) {
            files.add(path to text.toByteArray(Charsets.UTF_8))
        }

        var brandedManifests = 0
        if (ProjectKind.isNative(kind)) {
            for ((f, r) in userFiles.zip(rel)) files.add(r to f.readBytes())
            // تطبيق اسم التطبيق وصورته على المشروع نفسه
            val iconBytes = project.iconFile(context).takeIf { it.isFile }?.readBytes()
            brandedManifests = Branding.apply(files, project.appName.ifBlank { project.name }, iconBytes)
            val hasOwnWorkflow = rel.any {
                it.startsWith(".github/workflows/") && (it.endsWith(".yml") || it.endsWith(".yaml"))
            }
            if (hasOwnWorkflow) {
                deletions.add(".github/workflows/build-apk.yml")
            } else {
                add(".github/workflows/build-apk.yml", AppTemplate.workflowFor(kind))
            }
            val nowPaths = files.map { it.first }.toSet()
            deletions.addAll(AppTemplate.templatePaths.filter { it !in nowPaths })
        } else {
            val appId = AppTemplate.appId(repo)
            val hasIcon = project.iconFile(context).isFile
            var hasIndex = false
            for ((f, r) in userFiles.zip(rel)) {
                files.add("app/src/main/assets/$r" to f.readBytes())
                if (r.equals("index.html", ignoreCase = true)) hasIndex = true
            }
            if (!hasIndex) {
                val htmlFile = userFiles.zip(rel)
                    .firstOrNull { it.second.lowercase().endsWith(".html") || it.second.lowercase().endsWith(".htm") }
                val mainText = htmlFile?.first?.let { runCatching { it.readText() }.getOrNull() }
                    ?: userFiles.zip(rel).firstOrNull { ProjectKind.isTextFile(it.second) }
                        ?.first?.let { runCatching { it.readText() }.getOrNull() }
                val page = if (htmlFile != null && mainText != null) mainText
                else AppTemplate.indexHtml(project.appName, mainText ?: "", false)
                add("app/src/main/assets/index.html", page)
            }
            syncPrefixes.add("app/src/main/assets/")

            add("app.json", project.appJson())
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
                deletions.add("app/src/main/res/drawable/launcher_fg.xml")
            } else {
                add("app/src/main/res/drawable/launcher_fg.xml", AppTemplate.iconFgVector())
                deletions.add("app/src/main/res/drawable/launcher_fg.png")
            }
            add(".github/workflows/build-apk.yml", AppTemplate.workflowYml())
        }
        deletions.add(".github/workflows/build.yml")

        val message = "رفع وبناء من ستوديو السكربت"
        var ok = commitAll(token, user, repo, files, deletions, syncPrefixes, message, onProgress)
        val failed = mutableListOf<String>()
        if (!ok) {
            // أسلوب بديل: ملف ملف مع [skip ci] ثم دفعة واحدة تطلق البناء
            for ((i, pair) in files.withIndex()) {
                onProgress?.invoke(i + 1, files.size)
                if (!putFile(token, user, repo, pair.first, pair.second, "$message [skip ci]")) {
                    failed.add(pair.first)
                }
            }
            for (d in deletions) deleteFileIfExists(token, user, repo, d)
            if (failed.isEmpty()) {
                val stamp = java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
                ).format(java.util.Date())
                if (!putFile(token, user, repo, "build-trigger.txt",
                        stamp.toByteArray(Charsets.UTF_8), "بدء البناء من ستوديو السكربت")
                ) failed.add("build-trigger.txt")
            }
            ok = failed.isEmpty()
        }

        val url = "https://github.com/" + user + "/" + repo
        val kindLabel = ProjectKind.label(kind)
        return if (ok) {
            val brandNote = if (ProjectKind.isNative(kind)) {
                if (brandedManifests > 0) "\nتم تطبيق اسم التطبيق وصورته على المشروع"
                else "\nتنبيه: لم يُعثر على AndroidManifest بنشاط إطلاق لتطبيق الاسم والصورة"
            } else ""
            UploadResult(
                true,
                "تم رفع " + files.size + " ملفاً في عملية واحدة وبدأ GitHub ببناء ملف APK\n" +
                    "النوع: " + kindLabel + brandNote + "\n" + url,
                url
            )
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
