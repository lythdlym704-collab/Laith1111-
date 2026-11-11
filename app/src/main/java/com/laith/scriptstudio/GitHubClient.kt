package com.laith.scriptstudio

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * عميل بسيط لواجهة GitHub API:
 * - التحقق من رمز الدخول ومعرفة اسم المستخدم
 * - إنشاء المستودع إن لم يكن موجوداً (حلقة الوصل مع GitHub)
 * - رفع/تحديث الملفات عبر Contents API
 */
object GitHubClient {

    private const val API = "https://api.github.com"

    data class Response(val code: Int, val body: String)

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

    /** يرجع اسم المستخدم إذا كان الرمز صالحاً، وإلا null. */
    fun login(token: String): String? {
        return try {
            val r = request("GET", "$API/user", token)
            if (r.code == 200) JSONObject(r.body).optString("login").ifBlank { null } else null
        } catch (e: Exception) {
            null
        }
    }

    /** يتأكد من وجود المستودع، وينشئه خاصاً إذا لم يوجد. */
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

    /** يرفع ملفاً أو يحدّثه إن كان موجوداً. */
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

    data class UploadResult(val ok: Boolean, val message: String, val repoUrl: String = "")

    /**
     * يرفع المشروع كاملاً: ينشئ المستودع إن لزم ثم يرفع
     * السكربت وملف البناء والصورة وبيانات التطبيق.
     */
    fun uploadProject(context: android.content.Context, project: Project, token: String): UploadResult {
        val user = login(token)
            ?: return UploadResult(false, "رمز الدخول غير صالح أو لا يوجد اتصال بالإنترنت")

        val repo = project.githubRepo.ifBlank { ProjectStore.sanitizeRepoName(project.name) }
        if (!ensureRepo(token, user, repo)) {
            return UploadResult(false, "تعذّر إنشاء المستودع على GitHub")
        }

        val script = project.scriptFile(context).takeIf { it.isFile }?.readText().orEmpty()
        val cls = ScriptAnalyzer.classify(script)
        val workflow = project.buildYmlFile(context).takeIf { it.isFile }?.readText()
            ?: ScriptBuilder.generateWorkflowYml(cls.isWeb)

        val files = mutableListOf<Pair<String, ByteArray>>(
            "script.txt" to script.toByteArray(Charsets.UTF_8),
            "app.json" to project.appJson().toByteArray(Charsets.UTF_8),
            ".github/workflows/build.yml" to workflow.toByteArray(Charsets.UTF_8)
        )
        if (cls.isWeb) files.add("index.html" to script.toByteArray(Charsets.UTF_8))
        val readme = java.io.File(project.dir(context), "README.md")
        if (readme.isFile) files.add("README.md" to readme.readBytes())
        val icon = project.iconFile(context)
        if (icon.isFile) files.add("app-icon.png" to icon.readBytes())

        val failed = mutableListOf<String>()
        for ((path, bytes) in files) {
            if (!putFile(token, user, repo, path, bytes, "تحديث من ستوديو السكربت")) {
                failed.add(path)
            }
        }

        val url = "https://github.com/" + user + "/" + repo
        return if (failed.isEmpty()) {
            UploadResult(true, "تم رفع المشروع بنجاح إلى:\n" + url, url)
        } else {
            UploadResult(false, "اكتمل الرفع جزئياً. فشل رفع: " + failed.joinToString("، ") + "\n" + url, url)
        }
    }
}
