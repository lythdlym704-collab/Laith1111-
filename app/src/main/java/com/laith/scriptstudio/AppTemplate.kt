package com.laith.scriptstudio

/**
 * قالب مشروع أندرويد كامل يُرفع إلى مستودع المستخدم على GitHub،
 * فيبنيه GitHub Actions وينتج ملف APK حقيقياً قابلاً للتثبيت:
 * تطبيق WebView يحمل اسم المستخدم وصورته وصلاحياته، ويعرض
 * صفحته (إن كان الكود صفحة ويب) أو السكربت بشكل منسّق.
 */
object AppTemplate {

    fun appId(repo: String): String {
        val seg = repo.filter { it.isLetterOrDigit() }.lowercase().ifBlank { "app" }
        val safe = if (seg.first().isDigit()) "a$seg" else seg
        return "com.scriptstudio.$safe"
    }

    fun settingsGradle(): String = """
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "GeneratedApp"
include ":app"
""".trimIndent() + "\n"

    fun rootBuildGradle(): String = """
plugins {
    id 'com.android.application' version '8.6.1' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.24' apply false
}
""".trimIndent() + "\n"

    fun gradleProperties(): String = """
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
""".trimIndent() + "\n"

    fun appBuildGradle(applicationId: String): String = """
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.scriptstudio.generated'
    compileSdk 35

    defaultConfig {
        applicationId "$applicationId"
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName "1.0"
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.appcompat:appcompat:1.7.0'
}
""".trimIndent() + "\n"

    private val permissionMap = mapOf(
        "الإنترنت" to listOf("android.permission.INTERNET"),
        "الكاميرا" to listOf("android.permission.CAMERA"),
        "التخزين" to listOf("android.permission.READ_EXTERNAL_STORAGE"),
        "الموقع" to listOf("android.permission.ACCESS_FINE_LOCATION"),
        "الميكروفون" to listOf("android.permission.RECORD_AUDIO"),
        "الإشعارات" to listOf("android.permission.POST_NOTIFICATIONS")
    )

    fun manifestXml(permissions: List<String>): String {
        val permLines = permissions
            .flatMap { permissionMap[it] ?: emptyList() }
            .distinct()
            .joinToString("\n") { "    <uses-permission android:name=\"$it\" />" }
        return """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

$permLines

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
""".trimIndent() + "\n"
    }

    fun stringsXml(appName: String): String {
        val safe = appName
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
        return """
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$safe</string>
</resources>
""".trimIndent() + "\n"
    }

    fun mainActivityKt(): String = """
package com.scriptstudio.generated

import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var tts: TextToSpeech? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
            }
        }
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(JsBridge(), "Android")
        webView.loadUrl("file:///android_asset/index.html")
        setContentView(webView)
    }

    inner class JsBridge {
        @JavascriptInterface
        fun speak(text: String) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "msg")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
""".trimIndent() + "\n"

    fun launcherXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/launcher_bg" />
    <foreground android:drawable="@drawable/launcher_fg" />
</adaptive-icon>
""".trimIndent() + "\n"

    fun iconBgXml(): String = """
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#4F46E5" />
</shape>
""".trimIndent() + "\n"

    fun iconFgVector(): String = """
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="0.6" android:scaleY="0.6" android:pivotX="54" android:pivotY="54">
        <path android:strokeColor="#FFFFFF" android:strokeWidth="6"
            android:strokeLineCap="round" android:strokeLineJoin="round"
            android:pathData="M40,45 L54,55 L40,65" />
        <path android:strokeColor="#34D399" android:strokeWidth="6"
            android:strokeLineCap="round"
            android:pathData="M60,66 L74,66" />
    </group>
</vector>
""".trimIndent() + "\n"

    fun indexHtml(appName: String, script: String, isWeb: Boolean): String {
        if (isWeb) return script
        val safeName = appName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val safeScript = script.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>$safeName</title>
<style>
  body { margin: 0; background: #0b1020; color: #e2e8f0; font-family: sans-serif; }
  header { background: #4f46e5; padding: 18px; font-size: 20px; font-weight: bold; color: #fff; }
  pre { direction: ltr; text-align: left; background: #0f172a; margin: 14px; padding: 14px;
        border-radius: 12px; overflow-x: auto; font-size: 14px; line-height: 1.6; }
</style>
</head>
<body>
<header>$safeName</header>
<pre>$safeScript</pre>
</body>
</html>
""".trimIndent() + "\n"
    }

    fun workflowYml(): String = """
name: Build APK
on:
  push:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - name: Checkout
        uses: actions/checkout@v4
      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"
      - name: Set up Android SDK
        uses: android-actions/setup-android@v4
        with:
          packages: "platform-tools platforms;android-35 build-tools;35.0.0"
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.7"
      - name: Build debug APK
        run: gradle --no-daemon :app:assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-apk
          path: app/build/outputs/apk/debug/app-debug.apk
          if-no-files-found: error
""".trimIndent() + "\n"
}
