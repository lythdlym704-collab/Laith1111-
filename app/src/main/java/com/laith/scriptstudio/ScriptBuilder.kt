package com.laith.scriptstudio

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * يولّد ملف البناء (GitHub Actions workflow) ويجهّز حزمة المشروع
 * داخل مجلده: script.txt + app.json + build.yml + README.md.
 */
object ScriptBuilder {

    val steps = listOf(
        "تحليل الكود",
        "التحقق من الصحة",
        "توليد ملف البناء",
        "كتابة بيانات التطبيق",
        "حزم الملفات وتوليد البصمة"
    )

    data class BuildResult(val success: Boolean, val summary: String)

    /** ملف بناء GitHub Actions: يبني مشروع الأندرويد المولَّد وينتج APK. */
    fun generateWorkflowYml(isWeb: Boolean): String = AppTemplate.workflowYml()

    fun build(context: Context, project: Project, raw: String): BuildResult {
        val script = raw.trim()
        if (script.isEmpty()) {
            return BuildResult(false, "الكود فارغ — لا يمكن البناء.")
        }

        val cls = ScriptAnalyzer.classify(script)
        val dir = project.dir(context).apply { mkdirs() }

        project.scriptFile(context).writeText(script)
        project.buildYmlFile(context).writeText(generateWorkflowYml(cls.isWeb))
        File(dir, "app.json").writeText(project.appJson())

        val hash = sha256(script)
        File(dir, "README.md").writeText(buildString {
            appendLine("# ${project.appName}")
            appendLine()
            appendLine("مشروع من تطبيق ستوديو السكربت.")
            appendLine()
            appendLine("- النوع: ${cls.label}")
            appendLine("- الصلاحيات: ${if (project.permissions.isEmpty()) "لا شيء" else project.permissions.joinToString("، ")}")
            appendLine("- البصمة (SHA-256): $hash")
            appendLine("- آخر بناء: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
        })

        val summary = buildString {
            appendLine("✅ تم البناء بنجاح")
            appendLine()
            appendLine("النوع     : ${cls.label}")
            appendLine("الاسم     : ${project.appName}")
            appendLine("الأسطر    : ${script.lines().size}")
            appendLine("الصلاحيات : ${if (project.permissions.isEmpty()) "لا شيء" else project.permissions.joinToString("، ")}")
            appendLine("البصمة    : ${hash.take(16)}…")
            appendLine()
            appendLine("الملفات المولّدة:")
            appendLine("• script.txt — الكود")
            appendLine("• build.yml — ملف بناء APK (GitHub Actions)")
            appendLine("• app.json — الاسم والصلاحيات")
            appendLine("• README.md — وصف المشروع")
            appendLine()
            appendLine("ارفع المشروع إلى GitHub ليبنيه وينتج ملف APK قابلاً للتثبيت.")
        }
        return BuildResult(true, summary.trimEnd())
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
