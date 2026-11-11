package com.laith.scriptstudio

/**
 * محلّل كود: يستنتج اللغة، ويصنّف الكود (تطبيق/سكربت أم صفحة ويب)،
 * ويشرح ماذا يفعل، ويرصد العمليات الخطيرة. كل الشرح بالعربية.
 */
object ScriptAnalyzer {

    data class Classification(val isWeb: Boolean, val label: String, val reason: String)

    private data class Rule(
        val regex: Regex,
        val explain: String,
        val danger: Boolean = false
    )

    private val rules = listOf(
        Rule(Regex("""\brm\s+-[rRfv]*f""", RegexOption.IGNORE_CASE), "حذف ملفات/مجلدات بالقوة (rm -rf) — عملية خطيرة", danger = true),
        Rule(Regex("""\b(rm|del|unlink)\b""", RegexOption.IGNORE_CASE), "حذف ملفات"),
        Rule(Regex("""\b(mv|move|rename)\b""", RegexOption.IGNORE_CASE), "نقل أو إعادة تسمية ملفات"),
        Rule(Regex("""\b(cp|copy|xcopy)\b""", RegexOption.IGNORE_CASE), "نسخ ملفات"),
        Rule(Regex("""\bmkdir\b""", RegexOption.IGNORE_CASE), "إنشاء مجلدات"),
        Rule(Regex("""\b(touch|New-Item)\b""", RegexOption.IGNORE_CASE), "إنشاء ملفات جديدة"),
        Rule(Regex("""\b(cat|type|Get-Content)\b""", RegexOption.IGNORE_CASE), "قراءة محتوى ملفات"),
        Rule(Regex("""(open\s*\(|with\s+open|writeText|FileWriter|>>?\s*\S)"""), "كتابة إلى ملفات"),
        Rule(Regex("""\b(curl|wget|Invoke-WebRequest|iwr)\b""", RegexOption.IGNORE_CASE), "تنزيل بيانات من الإنترنت"),
        Rule(Regex("""(https?://|ftp://)""", RegexOption.IGNORE_CASE), "الاتصال بعناوين إنترنت"),
        Rule(Regex("""\b(requests\.|urllib|http\.client|socket|fetch\(|axios)\b"""), "طلبات شبكة برمجية"),
        Rule(Regex("""\b(nc|netcat|ncat|telnet)\b""", RegexOption.IGNORE_CASE), "اتصال شبكي منخفض المستوى (قد يكون باب خلفي)", danger = true),
        Rule(Regex("""\bsudo\b""", RegexOption.IGNORE_CASE), "تشغيل أوامر بصلاحيات المدير (sudo)", danger = true),
        Rule(Regex("""\b(chmod|chown|icacls|attrib)\b""", RegexOption.IGNORE_CASE), "تغيير صلاحيات/ملكية الملفات"),
        Rule(Regex("""\b(kill|taskkill|pkill)\b""", RegexOption.IGNORE_CASE), "إنهاء عمليات قيد التشغيل"),
        Rule(Regex("""\b(reboot|shutdown|halt|poweroff)\b""", RegexOption.IGNORE_CASE), "إعادة تشغيل أو إيقاف النظام", danger = true),
        Rule(Regex("""\b(systemctl|service|sc\s)\b""", RegexOption.IGNORE_CASE), "التحكم في خدمات النظام"),
        Rule(Regex("""\b(crontab|schtasks|at\s)\b""", RegexOption.IGNORE_CASE), "جدولة مهام تعمل تلقائياً"),
        Rule(Regex("""\b(apt|apt-get|yum|dnf|pacman|brew)\b""", RegexOption.IGNORE_CASE), "تثبيت/إدارة حزم النظام"),
        Rule(Regex("""\b(pip|pip3)\s+install\b""", RegexOption.IGNORE_CASE), "تثبيت مكتبات بايثون"),
        Rule(Regex("""\b(npm|yarn|pnpm)\s+(install|i|add)\b""", RegexOption.IGNORE_CASE), "تثبيت حزم جافاسكربت"),
        Rule(Regex("""\beval\b""", RegexOption.IGNORE_CASE), "تنفيذ نص كبرنامج (eval) — يصعب التنبؤ بنتيجته", danger = true),
        Rule(Regex("""\b(exec|os\.system|subprocess|Runtime\.getRuntime)\b"""), "تشغيل أوامر نظام من داخل الكود"),
        Rule(Regex("""\bdd\s+if=""", RegexOption.IGNORE_CASE), "كتابة مباشرة على القرص (dd) — قد يمحو بيانات", danger = true),
        Rule(Regex("""\bmkfs""", RegexOption.IGNORE_CASE), "تهيئة قرص (formatting) — يمحو كل شيء", danger = true),
        Rule(Regex(""":\s*\(\s*\)\s*\{.*:\|:.*\}"""), "قنبلة تفرّع (fork bomb) — تعلّق الجهاز", danger = true),
        Rule(Regex("""\b(password|passwd|secret|api[_-]?key|token)\b""", RegexOption.IGNORE_CASE), "يتعامل مع كلمات مرور أو مفاتيح سرية"),
        // عناصر ويب
        Rule(Regex("""<(button|input|form)\b""", RegexOption.IGNORE_CASE), "يعرض أزراراً أو نماذج إدخال للمستخدم"),
        Rule(Regex("""\b(alert|prompt|confirm)\s*\("""), "يعرض رسائل منبثقة للمستخدم"),
        Rule(Regex("""\b(localStorage|sessionStorage)\b"""), "يحفظ بيانات داخل المتصفح"),
        Rule(Regex("""\baddEventListener\s*\(|onclick\s*=""", RegexOption.IGNORE_CASE), "يتفاعل مع ضغطات وأحداث المستخدم")
    )

    private val webMarkers = listOf(
        Regex("""<!DOCTYPE\s+html""", RegexOption.IGNORE_CASE),
        Regex("""<html\b""", RegexOption.IGNORE_CASE),
        Regex("""<head\b""", RegexOption.IGNORE_CASE),
        Regex("""<body\b""", RegexOption.IGNORE_CASE),
        Regex("""<div\b|<span\b|<p\b|<h[1-6]\b""", RegexOption.IGNORE_CASE),
        Regex("""<style\b|</style>""", RegexOption.IGNORE_CASE),
        Regex("""<script\b|</script>""", RegexOption.IGNORE_CASE),
        Regex("""document\.(getElementById|querySelector|createElement)"""),
        Regex("""window\.(location|onload|open)"""),
        Regex("""<link\b[^>]*stylesheet""", RegexOption.IGNORE_CASE),
        Regex("""<meta\b""", RegexOption.IGNORE_CASE),
        Regex("""<img\b|<a\s+href""", RegexOption.IGNORE_CASE)
    )

    private val appMarkers = listOf(
        Regex("""^#!""", RegexOption.MULTILINE),
        Regex("""\becho\s|\bfi\b|\bdone\b"""),
        Regex("""\bdef\s+\w+|\bimport\s+\w+|print\s*\("""),
        Regex("""\bfun\s+main|\bpublic\s+static\s+void\s+main"""),
        Regex("""#include\s*<"""),
        Regex("""\b(apt|pip|npm|curl|wget|sudo|chmod)\b""", RegexOption.IGNORE_CASE),
        Regex("""Write-Host|Get-\w+"""),
        Regex("""@echo off""", RegexOption.IGNORE_CASE)
    )

    /** يصنّف الكود: صفحة ويب أم تطبيق/سكربت. */
    fun classify(script: String): Classification {
        val webScore = webMarkers.count { it.containsMatchIn(script) }
        val appScore = appMarkers.count { it.containsMatchIn(script) }
        return if (webScore >= 2 && webScore > appScore) {
            Classification(
                isWeb = true,
                label = "🌐 صفحة ويب",
                reason = "يحتوي على وسوم HTML وعناصر صفحات ويب ($webScore مؤشراً)"
            )
        } else {
            Classification(
                isWeb = false,
                label = "📱 تطبيق / سكربت",
                reason = if (appScore > 0)
                    "يحتوي على أوامر وبنية سكربت تنفيذي ($appScore مؤشراً)"
                else
                    "لا يحتوي على وسوم صفحات ويب"
            )
        }
    }

    fun analyze(raw: String): String {
        val script = raw.trim()
        if (script.isEmpty()) return "لا يوجد كود لتحليله."

        val lines = script.lines()
        val total = lines.size
        val nonEmpty = lines.count { it.isNotBlank() }
        val comments = lines.count {
            val t = it.trim()
            t.startsWith("#") || t.startsWith("//") || t.startsWith("<!--") || t.startsWith("rem ", true)
        }

        val language = detectLanguage(script)
        val cls = classify(script)

        val findings = LinkedHashMap<String, Rule>()
        var dangerCount = 0
        for (rule in rules) {
            if (rule.regex.containsMatchIn(script)) {
                if (rule.danger) dangerCount++
                findings[rule.explain] = rule
            }
        }

        val control = buildList {
            if (Regex("""\bfor\b""").containsMatchIn(script)) add("حلقات تكرار (for)")
            if (Regex("""\bwhile\b""").containsMatchIn(script)) add("حلقات شرطية (while)")
            if (Regex("""\bif\b""").containsMatchIn(script)) add("جُمل شرطية (if)")
            if (Regex("""\b(def|function|fun|void)\b""").containsMatchIn(script)) add("تعريف دوال")
        }

        val sb = StringBuilder()
        sb.appendLine("══ نوع الكود ══")
        sb.appendLine(cls.label)
        sb.appendLine("السبب: ${cls.reason}")
        sb.appendLine()
        sb.appendLine("══ ملخّص الفحص ══")
        sb.appendLine("اللغة المرجّحة : $language")
        sb.appendLine("عدد الأسطر     : $total  (فعّالة: $nonEmpty، تعليقات: $comments)")
        sb.appendLine("عدد الأحرف     : ${script.length}")
        sb.appendLine()

        sb.appendLine("══ ماذا يفعل هذا الكود؟ ══")
        if (findings.isEmpty()) {
            sb.appendLine("• لم تُرصد عمليات معروفة. قد يكون كوداً نصياً أو حسابياً بسيطاً.")
        } else {
            for ((text, rule) in findings) {
                sb.appendLine("${if (rule.danger) "⛔" else "•"} $text")
            }
        }
        sb.appendLine()

        if (control.isNotEmpty()) {
            sb.appendLine("══ البنية البرمجية ══")
            control.forEach { sb.appendLine("• $it") }
            sb.appendLine()
        }

        sb.appendLine("══ تقييم الأمان ══")
        when {
            dangerCount == 0 -> sb.appendLine("✅ آمن مبدئياً — لم تُرصد عمليات خطيرة.")
            dangerCount == 1 -> sb.appendLine("⚠️ تحذير — عملية خطيرة واحدة. راجع السطور المعلّمة بـ ⛔.")
            else -> sb.appendLine("⛔ خطر — $dangerCount عمليات حسّاسة. لا تشغّله قبل مراجعته جيداً.")
        }

        return sb.toString().trimEnd()
    }

    private fun detectLanguage(script: String): String {
        val head = script.lines().firstOrNull()?.trim().orEmpty()
        return when {
            classify(script).isWeb -> "HTML / CSS / JavaScript"
            head.startsWith("#!") && head.contains("python") -> "Python"
            head.startsWith("#!") && (head.contains("bash") || head.contains("sh")) -> "Bash / Shell"
            Regex("""\b(def |import |print\()""").containsMatchIn(script) -> "Python"
            Regex("""\b(echo |fi\b|then\b|\$\{)""").containsMatchIn(script) -> "Bash / Shell"
            Regex("""\b(function |const |let |=>)""").containsMatchIn(script) -> "JavaScript"
            Regex("""(Write-Host|Get-\w+|\.ps1)""").containsMatchIn(script) -> "PowerShell"
            Regex("""@echo off|\bgoto\b|%\w+%""", RegexOption.IGNORE_CASE).containsMatchIn(script) -> "Batch (CMD)"
            else -> "غير محدّد"
        }
    }
}
