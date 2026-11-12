package com.laith.scriptstudio

import java.io.File
import java.util.zip.ZipFile

/** يفك ملف ZIP داخل مجلد المشروع مع الحفاظ على المجلدات، وإزالة المجلد الجذري الزائد. */
object ZipImporter {

    fun extract(zip: File, dest: File): Int {
        ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
                .filter { !it.isDirectory }
                .filter {
                    val n = it.name
                    !n.startsWith("__MACOSX/") && !n.endsWith(".DS_Store") && !n.startsWith(".git/")
                }
            if (entries.isEmpty()) return 0

            // إذا كانت كل الملفات داخل مجلد واحد (مثل MyApp/...) نزيل هذا المجلد
            val roots = entries.map { it.name.substringBefore('/', "") }.toSet()
            val strip = if (roots.size == 1 && roots.first().isNotEmpty() &&
                entries.all { it.name.contains('/') }
            ) roots.first() + "/" else ""

            val destCanonical = dest.canonicalPath
            var count = 0
            for (e in entries) {
                val rel = e.name.removePrefix(strip).replace('\\', '/')
                if (rel.isBlank()) continue
                val parts = rel.split('/')
                if (parts.any { it == ".." || it.isBlank() }) continue
                val out = File(dest, rel)
                if (!out.canonicalPath.startsWith(destCanonical + File.separator)) continue
                out.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
                count++
            }
            return count
        }
    }
}
