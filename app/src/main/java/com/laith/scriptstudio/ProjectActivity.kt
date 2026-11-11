package com.laith.scriptstudio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
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

    private val textExtensions = setOf(
        "html", "htm", "js", "css", "txt", "json", "md", "py", "sh", "xml", "csv"
    )

    private val importFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val p = project ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            try {
                var name = "file-" + System.currentTimeMillis()
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) {
                        cursor.getString(idx)?.let { name = it }
                    }
                }
                name = name.replace(Regex("""[/\\:*?"<>|]"""), "_")
                contentResolver.openInputStream(uri)?.use { input ->
                    File(p.userFilesDir(this), name).outputStream().use { input.copyTo(it) }
                }
                refreshFiles()
            } catch (e: Exception) {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
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
        refreshFiles()
        if (user != null && p.githubRepo.isNotBlank()) {
            checkBuildOnce()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    /** نقل مشروع قديم (سكربت واحد) إلى نظام الملفات. */
    private fun migrateLegacyScript(p: Project) {
        val legacy = p.scriptFile(this)
        if (!legacy.isFile) return
        val text = try { legacy.readText() } catch (e: Exception) { "" }
        if (text.isNotBlank() && p.listUserFiles(this).isEmpty()) {
            val name =
                if (ScriptAnalyzer.classify(text).isWeb) "index.html" else "script.txt"
            File(p.userFilesDir(this), name).writeText(text)
        }
        legacy.delete()
    }

    private fun githubToken(): String? =
        getSharedPreferences("app", Context.MODE_PRIVATE).getString("github_token", null)

    private fun githubUser(): String? =
        getSharedPreferences("app", Context.MODE_PRIVATE).getString("github_user", null)

    // ===== إدارة الملفات =====

    private fun refreshFiles() {
        val p = project ?: return
        binding.filesContainer.removeAllViews()
        val files = p.listUserFiles(this)
        binding.emptyFiles.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (f in files) {
            val item = ItemFileBinding.inflate(inflater, binding.filesContainer, false)
            item.fileName.text = f.name
            item.fileSize.text = formatSize(f.length())
            item.root.setOnClickListener {
                startActivity(
                    Intent(this, EditFileActivity::class.java)
                        .putExtra("id", p.id)
                        .putExtra("file", f.name)
                )
            }
            item.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(f.name)
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
                arrayOf(getString(R.string.add_create), getString(R.string.add_import))
            ) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, EditFileActivity::class.java).putExtra("id", p.id)
                    )
                    1 -> importFile.launch("*/*")
                }
            }
            .show()
    }

    /** نص الملف الرئيسي للفحص: index.html أو أول ملف نصي. */
    private fun entryText(): String? {
        val p = project ?: return null
        val files = p.listUserFiles(this)
        val entry = files.firstOrNull { it.name.equals("index.html", true) }
            ?: files.firstOrNull { it.extension.lowercase() in textExtensions }
            ?: return null
        return try { entry.readText() } catch (e: Exception) { null }
    }

    private fun onCheck() {
        val text = entryText()
        if (text.isNullOrBlank()) {
            Snackbar.make(binding.root, R.string.no_files, Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.resultsTitle.text = "نتيجة الفحص"
        binding.resultsText.text = ScriptAnalyzer.analyze(text)
        binding.btnCopy.visibility = View.VISIBLE
    }

    // ===== الرفع والبناء على GitHub =====

    private fun onUpload() {
        if (busy) return
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
        binding.resultsText.text = "يُرفع كل ملفاتك مع ملف البناء التلقائي…"
        Thread {
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
