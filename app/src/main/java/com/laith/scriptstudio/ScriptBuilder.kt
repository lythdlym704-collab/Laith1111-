package com.laith.scriptstudio

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * يحزم السكربت في حزمة قابلة للتشغيل داخل مجلد التطبيق،
 * وينشئ ملف manifest يصف الحزمة. لا يمكن للهاتف تجميع APK من
 * سكربت عشوائي، لذا هذه الخطوة تُجهّز وتوثّق الحزمة بشكل احترافي.
 */
object ScriptBuilder {

    data class BuildResult(
        val success: Boolean,
        val summary: String,
        val outputDir: String
    )

    val steps = listOf(
        "تحليل السكربت",
        "التحقق من الصحة",
        "تجهيز الموارد",
        "حزم الملفات",
        "توليد البصمة والتوقيع"
    )

    fun build(context: Context, raw: String, appName: String): BuildResult {
        val script = raw.trim()
        if (script.isEmpty()) {
            return BuildResult(false, "السكربت فارغ — لا يمكن البناء.", "")
        }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val outDir = File(context.filesDir, "builds/build-$stamp").apply { mkdirs() }

        val scriptFile = File(outDir, "script.txt")
        scriptFile.writeText(script)

        val hash = sha256(script)
        val lines = script.lines().size

        val manifest = """
            {
              "app_name": "${appName.jsonSafe()}",
              "package": "com.laith.scriptstudio.pkg",
              "built_at": "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}",
              "source_lines": $lines,
              "source_bytes": ${script.toByteArray().size},
              "sha256": "$hash",
              "entry": "script.txt",
              "builder": "ScriptStudio 1.0"
            }
        """.trimIndent()
        File(outDir, "manifest.json").writeText(manifest)

        val readme = buildString {
            appendLine("# حزمة $appName")
            appendLine()
            appendLine("أُنشئت بواسطة ستوديو السكربت.")
            appendLine("- الملف الرئيسي: script.txt")
            appendLine("- الوصف: manifest.json")
            appendLine("- البصمة (SHA-256): $hash")
        }
        File(outDir, "README.md").writeText(readme)

        val summary = buildString {
            appendLine("✅ تم تجهيز الحزمة بنجاح")
            appendLine()
            appendLine("الاسم   : $appName")
            appendLine("الأسطر  : $lines")
            appendLine("البصمة  : ${hash.take(16)}…")
            appendLine("الملفات : script.txt، manifest.json، README.md")
            appendLine()
            appendLine("المسار:")
            appendLine(outDir.absolutePath)
            appendLine()
            appendLine("ملاحظة: الهاتف لا يُجمّع APK من سكربت مباشرةً. لإنتاج")
            appendLine("ملف APK قابل للتثبيت، استخدم بناء المشروع عبر GitHub Actions.")
        }

        return BuildResult(true, summary.trimEnd(), outDir.absolutePath)
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun String.jsonSafe(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
