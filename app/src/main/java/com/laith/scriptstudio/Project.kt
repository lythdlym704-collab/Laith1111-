package com.laith.scriptstudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** مشروع واحد: اسم، اسم تطبيق، صلاحيات، مستودع GitHub، وملفاته داخل مجلد خاص. */
data class Project(
    val id: String,
    var name: String,
    var appName: String,
    var permissions: MutableList<String> = mutableListOf(),
    var githubRepo: String = ""
) {
    fun dir(context: Context): File = File(context.filesDir, "projects/$id")
    fun scriptFile(context: Context): File = File(dir(context), "script.txt")
    fun iconFile(context: Context): File = File(dir(context), "icon.png")
    fun buildYmlFile(context: Context): File = File(dir(context), "build.yml")

    /** مجلد ملفات التطبيق التي ينشئها أو يستوردها المستخدم. */
    fun userFilesDir(context: Context): File =
        File(dir(context), "files").apply { mkdirs() }

    fun listUserFiles(context: Context): List<File> =
        userFilesDir(context).listFiles { f -> f.isFile }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("appName", appName)
        .put("permissions", JSONArray(permissions))
        .put("githubRepo", githubRepo)

    fun appJson(): String {
        return JSONObject()
            .put("name", appName)
            .put("permissions", JSONArray(permissions))
            .put("generator", "ScriptStudio 2.0")
            .toString(2)
    }

    companion object {
        fun fromJson(o: JSONObject): Project {
            val perms = mutableListOf<String>()
            val arr = o.optJSONArray("permissions") ?: JSONArray()
            for (i in 0 until arr.length()) perms.add(arr.getString(i))
            return Project(
                id = o.getString("id"),
                name = o.optString("name", "مشروع"),
                appName = o.optString("appName", o.optString("name", "تطبيقي")),
                permissions = perms,
                githubRepo = o.optString("githubRepo", "")
            )
        }
    }
}

object ProjectStore {

    private fun root(context: Context): File =
        File(context.filesDir, "projects").apply { mkdirs() }

    fun list(context: Context): List<Project> =
        root(context).listFiles { f -> f.isDirectory }
            ?.mapNotNull { load(context, it.name) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun create(context: Context, name: String): Project {
        val project = Project(
            id = UUID.randomUUID().toString().take(8),
            name = name.trim(),
            appName = name.trim(),
            githubRepo = sanitizeRepoName(name)
        )
        save(context, project)
        project.scriptFile(context).writeText("")
        return project
    }

    fun load(context: Context, id: String): Project? {
        val file = File(root(context), "$id/project.json")
        if (!file.isFile) return null
        return try {
            Project.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, project: Project) {
        project.dir(context).mkdirs()
        File(project.dir(context), "project.json").writeText(project.toJson().toString(2))
    }

    fun delete(context: Context, project: Project) {
        project.dir(context).deleteRecursively()
    }

    fun sanitizeRepoName(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("""[^A-Za-z0-9\-_.]"""), "-")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
        return if (cleaned.isBlank()) "script-project" else cleaned.lowercase()
    }
}
