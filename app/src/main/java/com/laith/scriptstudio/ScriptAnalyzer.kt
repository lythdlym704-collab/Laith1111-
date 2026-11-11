package com.laith.scriptstudio

/**
 * محلّل سكربت بسيط: يقرأ النص ويستنتج لغته، وما يفعله،
 * ويرصد العمليات الحسّاسة أو الخطيرة. كل الشرح بالعربية.
 */
object ScriptAnalyzer {

    data class Finding(val icon: String, val text: String)

    private data class Rule(
        val regex: Regex,
        val explain: String,
        val danger: Boolean = false
    )

    private val rules = listOf(
        // --- ملفات ---
        Rule(Regex("""\brm\s+-[rRfv]*f""", RegexOption.IGNORE_CASE), "حذف ملفات/مجلدات بالقوة (rm -rf) — عملية خطيرة", danger = true),
        Rule(Regex("""\b(rm|del|unlink)\b""", RegexOption.IGNORE_CASE), "حذف ملفات"),
        Rule(Regex("""\b(mv|move|rename)\b""", RegexOption.IGNORE_CASE), "نقل أو إعادة تسمية ملفات"),
        Rule(Regex("""\b(cp|copy|xcopy)\b""", RegexOption.IGNORE_CASE), "نسخ ملفات"),
        Rule(Regex("""\bmkdir\b""", RegexOption.IGNORE_CASE), "إنشاء مجلدات"),
        Rule(Regex("""\b(touch|New-Item)\b""", RegexOption.IGNORE_CASE), "إنشاء ملفات جديدة"),
        Rule(Regex("""\b(cat|type|Get-Content)\b""", RegexOption.IGNORE_CASE), "قراءة محتوى ملفات"),
        Rule(Regex("""(open\s*\(|with\s+open|writeText|FileWriter|>>?\s*\S)"""), "كتابة إلى ملفات"),
        // --- شبكة ---
        Rule(Regex("""\b(curl|wget|Invoke-WebRequest|iwr)\b""", RegexOption.IGNORE_CASE), "تنزيل بيانات من الإنترنت"),
        Rule(Regex("""(https?://|ftp://)""", RegexOption.IGNORE_CASE), "الاتصال بعناوين إنترنت"),
        Rule(Regex("""\b(requests\.|urllib|http\.client|socket|fetch\(|axios)\b"""), "طلبات شبكة برمجية"),
        Rule(Regex("""\b(nc|netcat|ncat|telnet)\b""", RegexOption.IGNORE_CASE), "اتصال شبكي منخفض المستوى (قد يكون باب خلفي)", danger = true),
        // --- نظام ---
        Rule(Regex("""\bsudo\b""", RegexOption.IGNORE_CASE), "تشغيل أوامر بصلاحيات المدير (sudo)", danger = true),
        Rule(Regex("""\b(chmod|chown|icacls|attrib)\b""", RegexOption.IGNORE_CASE), "تغيير صلاحيات/ملكية الملفات"),
        Rule(Regex("""\b(kill|taskkill|pkill)\b""", RegexOption.IGNORE_CASE), "إنهاء عمليات قيد التشغيل"),
        Rule(Regex("""\b(reboot|shutdown|halt|poweroff)\b""", RegexOption.IGNORE_CASE), "إعادة تشغيل أو إيقاف النظام", danger = true),
        Rule(Regex("""\b(systemctl|service|sc\s)\b""", RegexOption.IGNORE_CASE), "التحكم في خدمات النظام"),
        Rule(Regex("""\b(crontab|schtasks|at\s)\b""", RegexOption.IGNORE_CASE), "جدولة مهام تعمل تلقائياً"),
        // --- حزم ---
        Rule(Regex("""\b(apt|apt-get|yum|dnf|pacman|brew)\b""", RegexOption.IGNORE_CASE), "تثبيت/إدارة حزم النظام"),
        Rule(Regex("""\b(pip|pip3)\s+install\b""", RegexOption.IGNORE_CASE), "تثبيت مكتبات بايثون"),
        Rule(Regex("""\b(npm|yarn|pnpm)\s+(install|i|add)\b""", RegexOption.IGNORE_CASE), "تثبيت حزم جافاسكربت"),
        // --- تنفيذ خطير ---
        Rule(Regex("""\beval\b""", RegexOption.IGNORE_CASE), "تنفيذ نص كبرنامج (eval) — يصعب التنبؤ بنتيجته", danger = true),
        Rule(Regex("""\b(exec|os\.system|subprocess|Runtime\.getRuntime)\b"""), "تشغيل أوامر نظام من داخل الكود"),
        Rule(Regex("""\bdd\s+if=""", RegexOption.IGNORE_CASE), "كتابة مباشرة على القرص (dd) — قد يمحو بيانات", danger = true),
        Rule(Regex("""\bmkfs""", RegexOption.IGNORE_CASE), "تهيئة قرص (formatting) — يمحو كل شيء", danger = true),
        Rule(Regex(""":\s*\(\s*\)\s*\{.*:\|:.*\}"""), "قنبلة تفرّع (fork bomb) — تعلّق الجهاز", danger = true),
        Rule(Regex("""\b(password|passwd|secret|api[_-]?key|token)\b""", RegexOption.IGNORE_CASE), "يتعامل مع كلمات مرور أو مفاتيح سرية")
    )

    fun analyze(raw: String): String {
        val script = raw.trim()
        if (script.isEmpty()) return "لا يوجد سكربت لتحليله."

        val lines = script.lines()
        val total = lines.size
        val nonEmpty = lines.count { it.isNotBlank() }
        val comments = lines.count {
            val t = it.trim()
            t.startsWith("#") || t.startsWith("//") || t.startsWith("rem ", true)
        }
        val chars = script.length

        val language = detectLanguage(script)

        val findings = LinkedHashMap<String, Finding>()
        var dangerCount = 0
        for (rule in rules) {
            if (rule.regex.containsMatchIn(script)) {
                val icon = if (rule.danger) "⛔" else "•"
                if (rule.danger) dangerCount++
                findings[rule.explain] = Finding(icon, rule.explain)
            }
        }

        val control = buildList {
            if (Regex("""\bfor\b""").containsMatchIn(script)) add("حلقات تكرار (for)")
            if (Regex("""\bwhile\b""").containsMatchIn(script)) add("حلقات شرطية (while)")
            if (Regex("""\bif\b""").containsMatchIn(script)) add("جُمل شرطية (if)")
            if (Regex("""\b(def|function|fun|void)\b""").containsMatchIn(script)) add("تعريف دوال")
        }

        val sb = StringBuilder()
        sb.appendLine("══ ملخّص الفحص ══")
        sb.appendLine("اللغة المرجّحة : $language")
        sb.appendLine("عدد الأسطر     : $total  (فعّالة: $nonEmpty، تعليقات: $comments)")
        sb.appendLine("عدد الأحرف     : $chars")
        sb.appendLine()

        sb.appendLine("══ ماذا يفعل هذا السكربت؟ ══")
        if (findings.isEmpty()) {
            sb.appendLine("• لم يتم رصد عمليات معروفة. قد يكون سكربتاً حسابياً أو نصياً بسيطاً.")
        } else {
            for (f in findings.values) sb.appendLine("${f.icon} ${f.text}")
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
