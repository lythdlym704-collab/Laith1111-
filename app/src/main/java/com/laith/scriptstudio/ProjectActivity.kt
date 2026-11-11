package com.laith.scriptstudio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.snackbar.Snackbar
import com.laith.scriptstudio.databinding.ActivityProjectBinding
import java.io.File
import java.util.zip.ZipInputStream

class ProjectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectBinding
    private var project: Project? = null
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var pollRunnable: Runnable? = null
    private var pollTries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSettings.setOnClickListener {
            project?.let {
                startActivity(
                    Intent(this, ProjectSettingsActivity::class.java).putExtra("id", it.id)
                )
            }
        }
        binding.btnCheck.setOnClickListener { onCheck() }
        binding.btnBuild.setOnClickListener { onBuild() }
        binding.btnClear.setOnClickListener { binding.inputScript.setText("") }
        binding.btnSample.setOnClickListener { binding.inputScript.setText(SAMPLE) }
        binding.btnCopy.setOnClickListener { copyResult() }
        binding.btnGithubUpload.setOnClickListener { onUpload() }
        binding.btnInstall.setOnClickListener { onInstall() }
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra("id") ?: return finish()
        val p = ProjectStore.load(this, id) ?: return finish()
        project = p
        binding.headerName.text = p.appName.ifBlank { p.name }
        binding.headerSub.text =
            if (p.permissions.isEmpty()) "بدون صلاحيات"
            else p.permissions.joinToString("، ")
        val icon = p.iconFile(this)
        if (icon.isFile) {
            binding.headerIcon.setPadding(0, 0, 0, 0)
            binding.headerIcon.setImageBitmap(BitmapFactory.decodeFile(icon.absolutePath))
        }
        val user = githubUser()
        binding.githubStatus.text = when {
            user == null -> getString(R.string.github_need_login)
            p.githubRepo.isBlank() -> getString(R.string.not_linked)
            else -> "المستودع: $user/${p.githubRepo}"
        }
        if (binding.inputScript.text.isNullOrEmpty()) {
            val f = p.scriptFile(this)
            if (f.isFile) binding.inputScript.setText(f.readText())
        }
        // تحقق واحد من حالة البناء إذا كان المشروع مرتبطاً
        if (user != null && p.githubRepo.isNotBlank()) {
            checkBuildOnce()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
        saveScript()
    }

    private fun githubToken(): String? =
        getSharedPreferences("app", Context.MODE_PRIVATE).getString("github_token", null)

    private fun githubUser(): String? =
        getSharedPreferences("app", Context.MODE_PRIVATE).getString("github_user", null)

    private fun saveScript() {
        val p = project ?: return
        p.scriptFile(this).writeText(binding.inputScript.text?.toString().orEmpty())
    }

    private fun currentScript(): String = binding.inputScript.text?.toString().orEmpty()

    private fun onCheck() {
        hideKeyboard()
        saveScript()
        val script = currentScript()
        if (script.isBlank()) {
            Snackbar.make(binding.root, R.string.empty_script, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.resultsTitle.text = "نتيجة الفحص"
        binding.resultsText.text = ScriptAnalyzer.analyze(script)
        binding.btnCopy.visibility = View.VISIBLE
    }

    private fun onBuild() {
        hideKeyboard()
        if (busy) return
        saveScript()
        val p = project ?: return
        val script = currentScript()
        if (script.isBlank()) {
            Snackbar.make(binding.root, R.string.empty_script, Snackbar.LENGTH_SHORT).show()
            return
        }
        busy = true
        setButtons(false)
        binding.btnCopy.visibility = View.GONE
        binding.resultsTitle.text = "جارٍ البناء…"
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = false
        binding.progress.max = ScriptBuilder.steps.size
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE

        val log = StringBuilder()
        fun step(i: Int) {
            if (i >= ScriptBuilder.steps.size) {
                val result = ScriptBuilder.build(this, p, script)
                binding.progress.visibility = View.GONE
                binding.resultsTitle.text = "نتيجة البناء"
                binding.resultsText.text = log.toString() + "\n" + result.summary
                binding.btnCopy.visibility = View.VISIBLE
                busy = false
                setButtons(true)
                Snackbar.make(binding.root, R.string.build_done, Snackbar.LENGTH_SHORT).show()
                return
            }
            log.append("▸ ${ScriptBuilder.steps[i]} … تم\n")
            binding.resultsText.text = log.toString()
            binding.progress.progress = i + 1
            handler.postDelayed({ step(i + 1) }, 380)
        }
        step(0)
    }

    private fun onUpload() {
        hideKeyboard()
        if (busy) return
        saveScript()
        val p = project ?: return
        val token = githubToken()
        if (token == null) {
            Snackbar.make(binding.root, R.string.github_need_login, Snackbar.LENGTH_LONG).show()
            return
        }
        busy = true
        setButtons(false)
        binding.btnInstall.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.resultsTitle.text = getString(R.string.github_uploading)
        Thread {
            if (currentScript().isNotBlank()) ScriptBuilder.build(this, p, currentScript())
            val result = GitHubClient.uploadProject(this, p, token)
            runOnUiThread {
                binding.progress.visibility = View.GONE
                binding.progress.isIndeterminate = false
                binding.resultsTitle.text = "نتيجة الرفع"
                binding.resultsText.text =
                    (if (result.ok) "✅ " else "⚠️ ") + result.message
                binding.btnCopy.visibility = View.VISIBLE
                busy = false
                setButtons(true)
                if (result.ok) {
                    if (p.githubRepo.isBlank()) {
                        p.githubRepo = ProjectStore.sanitizeRepoName(p.name)
                        ProjectStore.save(this, p)
                    }
                    // ابدأ متابعة حالة البناء داخل التطبيق
                    startPolling()
                }
            }
        }.start()
    }

    // ===== متابعة حالة البناء على GitHub =====

    private fun startPolling() {
        stopPolling()
        pollTries = 0
        binding.buildStatus.visibility = View.VISIBLE
        binding.buildStatus.text = getString(R.string.build_status_running)
        val r = object : Runnable {
            override fun run() {
                pollTries++
                checkBuild(repeatWhileRunning = true)
            }
        }
        pollRunnable = r
        handler.postDelayed(r, 12000)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun checkBuildOnce() = checkBuild(repeatWhileRunning = false)

    private fun checkBuild(repeatWhileRunning: Boolean) {
        val p = project ?: return
        val token = githubToken() ?: return
        val user = githubUser() ?: return
        val repo = p.githubRepo.ifBlank { return }
        Thread {
            val run = GitHubClient.latestRun(token, user, repo)
            val success = GitHubClient.latestSuccessfulRun(token, user, repo)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.buildStatus.visibility = View.VISIBLE
                // زر التثبيت يظهر متى ما وُجد أي بناء ناجح
                binding.btnInstall.visibility =
                    if (success != null) View.VISIBLE else View.GONE
                when {
                    run == null -> {
                        binding.buildStatus.text = getString(R.string.build_status_none)
                        if (repeatWhileRunning && pollTries < 40) {
                            pollRunnable?.let { handler.postDelayed(it, 12000) }
                        }
                    }
                    run.status != "completed" -> {
                        binding.buildStatus.text = getString(R.string.build_status_running)
                        if (repeatWhileRunning && pollTries < 40) {
                            pollRunnable?.let { handler.postDelayed(it, 12000) }
                        }
                    }
                    run.conclusion == "success" -> {
                        binding.buildStatus.text = getString(R.string.build_status_success)
                        stopPolling()
                    }
                    else -> {
                        binding.buildStatus.text = getString(R.string.build_status_failure)
                        stopPolling()
                    }
                }
            }
        }.start()
    }

    // ===== تنزيل APK وتثبيته من داخل التطبيق =====

    private fun onInstall() {
        if (busy) return
        val p = project ?: return
        val token = githubToken()
        val user = githubUser()
        if (token == null || user == null) {
            Snackbar.make(binding.root, R.string.github_need_login, Snackbar.LENGTH_LONG).show()
            return
        }
        val repo = p.githubRepo.ifBlank {
            Snackbar.make(binding.root, R.string.not_linked, Snackbar.LENGTH_SHORT).show()
            return
        }
        busy = true
        setButtons(false)
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.buildStatus.text = getString(R.string.downloading_apk)
        Thread {
            var apk: File? = null
            val run = GitHubClient.latestSuccessfulRun(token, user, repo)
            if (run != null) {
                val url = GitHubClient.latestArtifactUrl(token, user, repo, run.id)
                if (url != null) {
                    val zip = File(cacheDir, "apk/artifact.zip")
                    if (GitHubClient.downloadToFile(token, url, zip)) {
                        apk = unzipApk(zip, File(cacheDir, "apk"))
                    }
                }
            }
            runOnUiThread {
                busy = false
                setButtons(true)
                binding.progress.visibility = View.GONE
                binding.progress.isIndeterminate = false
                if (apk != null && apk.isFile) {
                    binding.buildStatus.text = getString(R.string.build_status_success)
                    launchInstaller(apk)
                } else {
                    binding.buildStatus.text = getString(R.string.install_failed)
                    Snackbar.make(binding.root, R.string.install_failed, Snackbar.LENGTH_LONG)
                        .show()
                }
            }
        }.start()
    }

    private fun unzipApk(zip: File, outDir: File): File? {
        return try {
            outDir.mkdirs()
            ZipInputStream(zip.inputStream()).use { stream ->
                var entry = stream.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                        val out = File(outDir, "app.apk")
                        out.outputStream().use { stream.copyTo(it) }
                        return out
                    }
                    entry = stream.nextEntry
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun launchInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setButtons(enabled: Boolean) {
        binding.btnCheck.isEnabled = enabled
        binding.btnBuild.isEnabled = enabled
        binding.btnClear.isEnabled = enabled
        binding.btnSample.isEnabled = enabled
        binding.btnGithubUpload.isEnabled = enabled
        binding.btnInstall.isEnabled = enabled
    }

    private fun copyResult() {
        val text = binding.resultsText.text?.toString().orEmpty()
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("result", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    companion object {
        private val SAMPLE = """
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
              <meta charset="utf-8">
              <title>تطبيقي</title>
              <style> body { font-family: sans-serif; text-align: center; } </style>
            </head>
            <body>
              <h1>مرحباً!</h1>
              <button onclick="alert('أهلاً بك')">اضغط هنا</button>
            </body>
            </html>
        """.trimIndent()
    }
}
