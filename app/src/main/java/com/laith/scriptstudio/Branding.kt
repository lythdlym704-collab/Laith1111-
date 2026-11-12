package com.laith.scriptstudio

/**
 * يطبّق اسم التطبيق وصورته التي اختارها المستخدم على مشروع أندرويد أصلي:
 * يعدّل AndroidManifest (label/icon) ويضيف الأيقونة بكل المقاسات باسم فريد
 * لا يتعارض مع موارد المشروع.
 */
object Branding {

    private const val ICON_NAME = "scriptstudio_icon"
    private val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** يعدّل قائمة الملفات في مكانها. يرجع عدد ملفات Manifest التي عُدّلت. */
    fun apply(
        files: MutableList<Pair<String, ByteArray>>,
        appName: String,
        iconBytes: ByteArray?
    ): Int {
        var applied = 0
        val manifestIdx = files.indices.filter { i ->
            val p = files[i].first
            p.endsWith("AndroidManifest.xml") &&
                !p.contains("/build/") && !p.contains("/test/") && !p.contains("/androidTest/")
        }
        for (i in manifestIdx) {
            val (path, bytes) = files[i]
            val xml = try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { continue }
            if (!xml.contains("<application") || !xml.contains("android.intent.category.LAUNCHER")) continue

            val iconRes = if (iconBytes != null) "@mipmap/$ICON_NAME" else null
            val rewritten = rewriteManifest(xml, appName, iconRes)
            files[i] = path to rewritten.toByteArray(Charsets.UTF_8)
            applied++

            if (iconBytes != null) {
                val resDir = if (path.contains("/src/main/AndroidManifest.xml")) {
                    path.substringBeforeLast("/src/main/AndroidManifest.xml") + "/src/main/res/"
                } else if (path == "AndroidManifest.xml") {
                    "res/"
                } else {
                    path.substringBeforeLast("/") + "/res/"
                }
                fun put(p: String, b: ByteArray) {
                    val idx = files.indexOfFirst { it.first == p }
                    if (idx >= 0) files[idx] = p to b else files.add(p to b)
                }
                for (d in densities) put(resDir + "mipmap-" + d + "/" + ICON_NAME + ".png", iconBytes)
                put(resDir + "drawable/" + ICON_NAME + "_fg.png", iconBytes)
                put(resDir + "drawable/" + ICON_NAME + "_bg.xml", bgXml().toByteArray())
                put(resDir + "mipmap-anydpi-v26/" + ICON_NAME + ".xml", adaptiveXml().toByteArray())
            }
        }
        return applied
    }

    private fun bgXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#FFFFFF" />
</shape>
""".trimIndent() + "\n"

    private fun adaptiveXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/scriptstudio_icon_bg" />
    <foreground android:drawable="@drawable/scriptstudio_icon_fg" />
</adaptive-icon>
""".trimIndent() + "\n"

    private val labelAttr = Regex("\\s+android:label\\s*=\\s*\"[^\"]*\"")
    private val iconAttr = Regex("\\s+android:icon\\s*=\\s*\"[^\"]*\"")
    private val roundIconAttr = Regex("\\s+android:roundIcon\\s*=\\s*\"[^\"]*\"")

    fun rewriteManifest(xml: String, label: String, iconRes: String?): String {
        var out = xml

        // وسم <application ...>
        val appTag = Regex("<application(?=[\\s>])[^>]*>")
        out = appTag.replace(out) { m ->
            var tag = m.value
            tag = tag.replace(labelAttr, "")
            if (iconRes != null) {
                tag = tag.replace(iconAttr, "").replace(roundIconAttr, "")
            }
            val insert = StringBuilder()
            insert.append(" android:label=\"").append(xmlEscape(label)).append("\"")
            if (iconRes != null) {
                insert.append(" android:icon=\"").append(iconRes).append("\"")
                insert.append(" android:roundIcon=\"").append(iconRes).append("\"")
            }
            tag.replaceFirst("<application", "<application" + insert)
        }

        // نشاط الإطلاق: إزالة label الخاص به ليُستخدم اسم التطبيق
        val activityBlock = Regex("<activity(?=[\\s>]).*?</activity>", RegexOption.DOT_MATCHES_ALL)
        out = activityBlock.replace(out) { m ->
            if (!m.value.contains("android.intent.category.LAUNCHER")) return@replace m.value
            val open = Regex("<activity(?=[\\s>])[^>]*>").find(m.value)?.value
                ?: return@replace m.value
            val newOpen = open.replace(labelAttr, "")
            m.value.replaceFirst(open, newOpen)
        }
        return out
    }
}
