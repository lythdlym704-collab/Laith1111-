package com.laith.scriptstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * إصلاح تلقائي لأخطاء البناء الشائعة داخل ملفات المشروع:
 * صور تالفة، إصدار Gradle المطلوب، namespace ناقص، إصدار Java، إعدادات محلية.
 */
object AutoFix {

    data class Result(val fixes: List<String>, val unfixable: List<String>)

    fun apply(context: Context, project: Project, errorText: String): Result {
        val fixes = mutableListOf<String>()
        val unfixable = mutableListOf<String>()
        val dir = project.userFilesDir(context)
        val files = project.listUserFiles(context)
        val rel = files.map { project.relativePath(context, it) }

        // 1) صور ترفضها أداة الموارد
        val imgRe = Regex(
            """ERROR: (\S+?\.(?:png|jpg|jpeg|webp|gif)): AAPT: error: file failed to compile""",
            RegexOption.IGNORE_CASE
        )
        for (m in imgRe.findAll(errorText)) {
            val full = m.groupValues[1]
            val name = full.substringAfterLast('/')
            val relPath = Regex("""/work/[^/]+/[^/]+/(.*)""").find(full)?.groupValues?.get(1) ?: name
            if (name.startsWith("scriptstudio_icon")) {
                val icon = project.iconFile(context)
                val png = IconUtil.pngBytes(icon)
                if (png != null) {
                    icon.writeBytes(png)
                    fixes.add("أُعيد ترميز صورة التطبيق إلى PNG صالح")
                } else {
                    icon.delete()
                    fixes.add("حُذفت صورة التطبيق لأنها تالفة — اختر صورة أخرى من الإعدادات")
                }
                continue
            }
            val idx = rel.indexOfFirst { it == relPath || it.endsWith("/$name") || it == name }
            if (idx < 0) {
                unfixable.add("الصورة $name ليست ضمن ملفاتك")
                continue
            }
            val f = files[idx]
            if (repairImage(f)) fixes.add("أُصلحت الصورة: " + rel[idx])
            else {
                f.delete()
                fixes.add("حُذفت صورة تالفة لا يمكن قراءتها: " + rel[idx])
            }
        }

        // 2) إصدار Gradle المطلوب
        val gv = Regex("""Minimum supported Gradle version is (\d+(?:\.\d+)+)""").find(errorText)
            ?: Regex("""requires Gradle (\d+(?:\.\d+)+)""").find(errorText)
        gv?.let {
            if (setGradleVersion(files, rel, dir, it.groupValues[1])) {
                fixes.add("ضُبط إصدار Gradle على " + it.groupValues[1])
            }
        }

        // 3) namespace ناقص في مشاريع قديمة مع AGP 8
        if (errorText.contains("Namespace not specified")) {
            var n = 0
            for ((i, f) in files.withIndex()) {
                val r = rel[i]
                if (!(r.endsWith("build.gradle") || r.endsWith("build.gradle.kts"))) continue
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                if (!text.contains("android {") && !text.contains("android{")) continue
                if (Regex("""\bnamespace\b""").containsMatchIn(text)) continue
                val moduleDir = r.substringBeforeLast('/', "")
                val manifestRel = (if (moduleDir.isEmpty()) "" else "$moduleDir/") + "src/main/AndroidManifest.xml"
                val mi = rel.indexOf(manifestRel)
                if (mi < 0) continue
                val pkg = Regex("""package\s*=\s*"([^"]+)"""")
                    .find(runCatching { files[mi].readText() }.getOrNull().orEmpty())?.groupValues?.get(1) ?: continue
                val line = if (r.endsWith(".kts")) "    namespace = \"$pkg\"\n" else "    namespace '$pkg'\n"
                val fixed = text.replaceFirst(Regex("""android\s*\{\s*\n"""), "android {\n$line")
                if (fixed != text) {
                    f.writeText(fixed)
                    n++
                }
            }
            if (n > 0) fixes.add("أُضيف namespace إلى $n ملف بناء")
        }

        // 4) إصدار Java المطلوب
        Regex("""requires Java (\d+)""").find(errorText)?.let {
            val v = it.groupValues[1]
            if (v in listOf("8", "11", "17", "21")) {
                File(dir, ".scriptstudio/java").apply { parentFile?.mkdirs() }.writeText(v)
                fixes.add("ضُبط إصدار Java على $v")
            }
        }

        // 5) إعدادات محلية تُفشل البناء
        if (errorText.contains("SDK location not found") || errorText.contains("local.properties")) {
            var n = 0
            for ((i, f) in files.withIndex()) if (rel[i].endsWith("local.properties")) { f.delete(); n++ }
            if (n > 0) fixes.add("حُذف ملف local.properties ($n)")
        }

        // 6) أمور لا يمكن إصلاحها تلقائياً
        if (errorText.contains("google-services.json")) {
            unfixable.add("المشروع يحتاج ملف google-services.json من Firebase — أضفه إلى ملفات المشروع")
        }
        if (Regex("""(?m)^e: |error: cannot find symbol|Unresolved reference|error: '|expected""").containsMatchIn(errorText)) {
            unfixable.add("توجد أخطاء في كود المصدر نفسه تحتاج تعديلاً يدوياً (الأسطر الحمراء أعلاه)")
        }
        return Result(fixes, unfixable)
    }

    /** يعيد ترميز الصورة بصيغة صحيحة؛ يرجع false إن تعذّرت قراءتها. */
    fun repairImage(f: File): Boolean {
        if (f.name.endsWith(".9.png")) return false
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return false
        val out = ByteArrayOutputStream()
        val jpg = f.name.lowercase().endsWith(".jpg") || f.name.lowercase().endsWith(".jpeg")
        bmp.compress(if (jpg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, out)
        f.writeBytes(out.toByteArray())
        return true
    }

    private fun setGradleVersion(files: List<File>, rel: List<String>, root: File, version: String): Boolean {
        val settings = rel.filter { it == "settings.gradle" || it == "settings.gradle.kts" ||
            it.endsWith("/settings.gradle") || it.endsWith("/settings.gradle.kts") }
            .minByOrNull { it.count { c -> c == '/' } }
        val base = if (settings == null || !settings.contains('/')) root
            else File(root, settings.substringBeforeLast('/'))
        val props = File(base, "gradle/wrapper/gradle-wrapper.properties")
        props.parentFile?.mkdirs()
        props.writeText(
            "distributionBase=GRADLE_USER_HOME\n" +
                "distributionPath=wrapper/dists\n" +
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-$version-bin.zip\n" +
                "zipStoreBase=GRADLE_USER_HOME\n" +
                "zipStorePath=wrapper/dists\n"
        )
        return true
    }
}
