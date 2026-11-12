package com.laith.scriptstudio

/** يكشف نوع المشروع من ملفاته ليُبنى بالطريقة الصحيحة. */
object ProjectKind {
    const val ANDROID = "android"
    const val WEB = "web"
    const val OTHER = "other"

    val textExtensions = setOf(
        "html", "htm", "js", "css", "txt", "json", "md", "py", "sh", "xml", "csv",
        "kt", "kts", "java", "gradle", "properties", "yml", "yaml", "pro", "cfg", "ini",
        "svg", "ts", "tsx", "jsx", "c", "cpp", "h", "dart", "swift", "php", "rb", "go",
        "rs", "toml", "lock", "bat", "ps1", "gitignore", "txt"
    )

    fun isTextFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isEmpty() || ext in textExtensions
    }

    fun detect(paths: List<String>): String {
        val lower = paths.map { it.lowercase() }
        val hasGradle = lower.any {
            it == "settings.gradle" || it == "settings.gradle.kts" ||
                it.endsWith("/settings.gradle") || it.endsWith("/settings.gradle.kts") ||
                it == "build.gradle" || it == "build.gradle.kts" ||
                it.endsWith("/build.gradle") || it.endsWith("/build.gradle.kts")
        }
        val hasManifest = lower.any { it.endsWith("androidmanifest.xml") }
        val hasSource = lower.any { it.endsWith(".kt") || it.endsWith(".java") }
        if (hasGradle && (hasManifest || hasSource)) return ANDROID
        if (lower.any { it.endsWith(".html") || it.endsWith(".htm") }) return WEB
        return OTHER
    }

    fun label(kind: String): String = when (kind) {
        ANDROID -> "📱 مشروع أندرويد أصلي — سيُبنى كما هو بملفاته"
        WEB -> "🌐 تطبيق ويب — سيُغلَّف داخل تطبيق أندرويد"
        else -> "📄 ملفات عامة — ستُعرض داخل تطبيق أندرويد"
    }
}
