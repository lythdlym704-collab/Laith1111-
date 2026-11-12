package com.laith.scriptstudio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.laith.scriptstudio.databinding.ActivityProjectBinding
import com.laith.scriptstudio.databinding.ItemFileBinding
import java.io.File
import java.util.zip.ZipInputStream

class ProjectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectBinding
    private var project: Project? = null
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var pollRunnable: Runnable? = null
    private var pollTries = 0

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importUri(uri)
        }

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
        binding.btnAddFile.setOnClickListener { showAddMenu() }
        binding.btnCheck.setOnClickListener { onCheck() }
        binding.btnCopy.setOnClickListener { copyResult() }
        binding.btnGithubUpload.setOnClickListener { onUpload() }
        binding.btnInstall.setOnClickListener { onInstall() }
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra("id") ?: return finish()
        val p = ProjectStore.load(this, id) ?: return finish()
        project = p
        migrateLegacyScript(p)
        binding.headerName.text = p.appName.ifBlank { p.name }
        binding.headerSub.text =
            if (p.permissions.isEmpty()) "بدون صلاحيات" else p.permissions.joinToString("، ")
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
        refreshFiles()
        if (user != null && p.githubRepo.isNotBlank()) checkBuildOnce()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    private fun migrateLegacyScript(p: Project) {
        val legacy = p.scriptFile(this)
        if (!legacy.isFile) return
        val text = try { legacy.readText() } catch (e: Exception) { "" }
        if (text.isNotBlank() && p.listUserFiles(this).isEmpty()) {
            val name = if (ScriptAnalyzer.classify(text).isWeb) "index.html" else "script.txt"
            File(p.userFilesDir(this), name).writeText(text)
        }
        legacy.delete()
    }

    private fun githubToken(): String? =
        getSharedPreferences("app", Context.MODE_PRIVATE).getString("github_token", null)

    private fun githubUser(): String? =
        getSharedPreferences("app", Context.MODE_PRIVATE).getString("github_user", null)

    // ===== الملفات =====

    private fun refreshFiles() {
        val p = project ?: return
        binding.filesContainer.removeAllViews()
        val files = p.listUserFiles(this)
        binding.emptyFiles.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        val maxRows = 150
        for (f in files.take(maxRows)) {
            val rel = p.relativePath(this, f)
            val item = ItemFileBinding.inflate(inflater, binding.filesContainer, false)
            item.fileName.text = rel
            item.fileSize.text = formatSize(f.length())
            item.root.setOnClickListener {
                if (ProjectKind.isTextFile(rel) && f.length() < 1_500_000) {
                    startActivity(
                        Intent(this, EditFileActivity::class.java)
                            .putExtra("id", p.id).putExtra("file", rel)
                    )
                } else {
                    Toast.makeText(this, R.string.binary_file, Toast.LENGTH_SHORT).show()
                }
            }
            item.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(rel)
                    .setMessage(R.string.delete_file_q)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        f.delete()
                        refreshFiles()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            binding.filesContainer.addView(item.root)
        }
        if (files.size > maxRows) {
            val more = TextView(this)
            more.text = "… و" + (files.size - maxRows) + " ملفات أخرى"
            more.setPadding(8, 16, 8, 8)
            binding.filesContainer.addView(more)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }

    private fun showAddMenu() {
        val p = project ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.files_title)
            .setItems(
                arrayOf(
                    getString(R.string.add_create),
                    getString(R.string.add_import),
                    getString(R.string.add_clear)
                )
            ) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, EditFileActivity::class.java).putExtra("id", p.id)
                    )
                    1 -> importFile.launch("*/*")
                    2 -> MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.clear_files_q)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            p.userFilesDir(this).deleteRecursively()
                            p.userFilesDir(this).mkdirs()
                            refreshFiles()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    private fun displayName(uri: Uri): String {
        var name = "file-" + System.currentTimeMillis()
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx)?.let { name = it }
            }
        } catch (e: Exception) {
            // استخدم الاسم الافتراضي
        }
        return name
    }

    /** استيراد ملف عادي أو ملف ZIP (يُفك تلقائياً مع مجلداته). */
    private fun importUri(uri: Uri) {
        val p = project ?: return
        if (busy) return
        val name = displayName(uri)
        val mime = contentResolver.getType(uri).orEmpty()
        val isZip = name.lowercase().endsWith(".zip") ||
            mime == "application/zip" || mime == "application/x-zip-compressed"
        busy = true
        setButtons(false)
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.resultsTitle.text = getString(R.string.importing)
        Thread {
            var msg: String
            try {
                if (isZip) {
                    val tmp = File(cacheDir, "import.zip")
                    contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    val count = ZipImporter.extract(tmp, p.userFilesDir(this))
                    tmp.delete()
                    msg = "✅ تم استيراد $count ملفاً من $name"
                } else {
                    val safe = name.replace(Regex("""[\\:*?"<>|]"""), "_")
                    val out = File(p.userFilesDir(this), safe)
                    out.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    msg = "✅ تم استيراد $name"
                }
                val rel = p.listUserFiles(this).map { p.relativePath(this, it) }
                msg += "\nنوع المشروع: " + ProjectKind.label(ProjectKind.detect(rel))
            } catch (e: Exception) {
                msg = getString(R.string.import_failed) + ": " + (e.message ?: "")
            }
            runOnUiThread {
                busy = false
                setButtons(true)
                binding.progress.visibility = View.GONE
                binding.progress.isIndeterminate = false
                binding.resultsTitle.text = "نتيجة الاستيراد"
                binding.resultsText.text = msg
                refreshFiles()
            }
        }.start()
    }

    private fun entryText(files: List<File>): String? {
        val p = project ?: return null
        val entry = files.firstOrNull { p.relativePath(this, it).equals("index.html", true) }
            ?: files.firstOrNull { p.relativePath(this, it).lowercase().endsWith(".html") }
            ?: files.firstOrNull { ProjectKind.isTextFile(it.name) && it.length() < 1_500_000 }
            ?: return null
        return try { entry.readText() } catch (e: Exception) { null }
    }

    private fun onCheck() {
        val p = project ?: return
        val files = p.listUserFiles(this)
        if (files.isEmpty()) {
            Snackbar.make(binding.root, R.string.no_files, Snackbar.LENGTH_SHORT).show()
            return
        }
        val rel = files.map { p.relativePath(this, it) }
        val kind = ProjectKind.detect(rel)
        val sb = StringBuilder()
        sb.appendLine("══ نوع المشروع ══")
        sb.appendLine(ProjectKind.label(kind))
        sb.appendLine("عدد الملفات: " + files.size)
        if (kind == ProjectKind.ANDROID) {
            val hasWf = rel.any { it.startsWith(".github/workflows/") }
            sb.appendLine(
                if (hasWf) "ملف البناء: ملف المشروع الخاص سيُستخدم"
                else "ملف البناء: سيُضاف build-apk.yml تلقائياً"
            )
            val hasWrapper = rel.any { it == "gradlew" } && rel.any { it.endsWith("gradle-wrapper.jar") }
            sb.appendLine("Gradle: " + if (hasWrapper) "gradlew الخاص بالمشروع" else "سيُجهَّز تلقائياً")
        }
        sb.appendLine()
        val text = entryText(files)
        if (text != null) sb.append(ScriptAnalyzer.analyze(text))
        binding.resultsTitle.text = "نتيجة الفحص"
        binding.resultsText.text = sb.toString().trimEnd()
        binding.btnCopy.visibility = View.VISIBLE
    }

    // ===== الرفع والبناء =====

    private fun onUpload() {
        if (busy) return
        val p = project ?: return
        val token = githubToken()
        if (token == null) {
            Snackbar.make(binding.root, R.string.github_need_login, Snackbar.LENGTH_LONG).show()
            return
        }
        if (p.listUserFiles(this).isEmpty()) {
            Snackbar.make(binding.root, R.string.no_files_upload, Snackbar.LENGTH_LONG).show()
            return
        }
        busy = true
        setButtons(false)
        binding.btnInstall.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.resultsTitle.text = getString(R.string.github_uploading)
        binding.resultsText.text = "تجهيز الملفات…"
        Thread {
            val result = GitHubClient.uploadProject(this, p, token) { done, total ->
                runOnUiThread {
                    binding.resultsText.text = "رفع الملف $done من $total…"
                }
            }
            runOnUiThread {
                binding.progress.visibility = View.GONE
                binding.progress.isIndeterminate = false
                binding.resultsTitle.text = "نتيجة الرفع"
                binding.resultsText.text = (if (result.ok) "✅ " else "⚠️ ") + result.message
                binding.btnCopy.visibility = View.VISIBLE
                busy = false
                setButtons(true)
                if (result.ok) {
                    if (p.githubRepo.isBlank()) {
                        p.githubRepo = ProjectStore.sanitizeRepoName(p.name)
                        ProjectStore.save(this, p)
                    }
                    startPolling()
                }
            }
        }.start()
    }

    private fun startPolling() {
        stopPolling()
        pollTries = 0
        binding.buildStatus.visibility = View.VISIBLE
        binding.buildStatus.text = getString(R.string.build_status_running)
        showBuildProgress(null, null)
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

    private fun showBuildProgress(done: Int?, total: Int?) {
        val bar = binding.buildProgress
        bar.visibility = View.GONE
        if (done == null || total == null || total <= 0) {
            bar.isIndeterminate = true
        } else {
            bar.isIndeterminate = false
            bar.max = total
            bar.setProgressCompat(done, true)
        }
        bar.visibility = View.VISIBLE
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
            val progress = if (run != null && run.status != "completed")
                GitHubClient.runProgress(token, user, repo, run.id) else null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.buildStatus.visibility = View.VISIBLE
                binding.btnInstall.visibility = if (success != null) View.VISIBLE else View.GONE
                when {
                    run == null -> {
                        binding.buildStatus.text = getString(R.string.build_status_none)
                        binding.buildProgress.visibility = View.GONE
                        if (repeatWhileRunning && pollTries < 60) {
                            pollRunnable?.let { handler.postDelayed(it, 12000) }
                        }
                    }
                    run.status != "completed" -> {
                        if (progress != null) {
                            val stepNo = minOf(progress.done + 1, progress.total)
                            val stepName =
                                if (progress.currentStep.isNotEmpty()) " — " + progress.currentStep else ""
                            binding.buildStatus.text =
                                "⏳ البناء قيد التشغيل: الخطوة " + stepNo + " من " + progress.total + stepName
                            showBuildProgress(progress.done, progress.total)
                        } else {
                            binding.buildStatus.text = getString(R.string.build_status_running)
                            showBuildProgress(null, null)
                        }
                        if (repeatWhileRunning && pollTries < 60) {
                            pollRunnable?.let { handler.postDelayed(it, 12000) }
                        }
                    }
                    run.conclusion == "success" -> {
                        binding.buildStatus.text = getString(R.string.build_status_success)
                        showBuildProgress(1, 1)
                        stopPolling()
                    }
                    else -> {
                        binding.buildStatus.text = getString(R.string.build_status_failure)
                        binding.buildProgress.visibility = View.GONE
                        stopPolling()
                    }
                }
            }
        }.start()
    }

    // ===== التنزيل والتثبيت =====

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
                    Snackbar.make(binding.root, R.string.install_failed, Snackbar.LENGTH_LONG).show()
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
        binding.btnAddFile.isEnabled = enabled
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
}
