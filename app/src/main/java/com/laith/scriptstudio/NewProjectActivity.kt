package com.laith.scriptstudio

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.laith.scriptstudio.databinding.ActivityNewProjectBinding
import java.io.File

/**
 * معالج إنشاء مشروع من خطوتين:
 * ١) اسم المشروع + اسم التطبيق + صورته.
 * ٢) السكربت + متطلبات البناء (الصلاحيات)، ثم إنشاء المشروع
 *    وإنشاء مستودعه على GitHub تلقائياً إذا كان المستخدم مسجّل الدخول.
 */
class NewProjectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewProjectBinding
    private var iconTemp: File? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                val file = File(cacheDir, "new_project_icon.png")
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                iconTemp = file
                binding.iconPreview.setPadding(0, 0, 0, 0)
                binding.iconPreview.setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewProjectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPickImage.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.btnNext.setOnClickListener {
            val name = binding.inputProjectName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.step1.visibility = View.GONE
            binding.step2.visibility = View.VISIBLE
            binding.stepLabel.text = getString(R.string.step2_title)
        }

        binding.btnCreateProject.setOnClickListener { createProject() }
    }

    private fun selectedPermissions(): MutableList<String> {
        val perms = mutableListOf<String>()
        if (binding.chkInternet.isChecked) perms.add(getString(R.string.perm_internet))
        if (binding.chkCamera.isChecked) perms.add(getString(R.string.perm_camera))
        if (binding.chkStorage.isChecked) perms.add(getString(R.string.perm_storage))
        if (binding.chkLocation.isChecked) perms.add(getString(R.string.perm_location))
        if (binding.chkMic.isChecked) perms.add(getString(R.string.perm_mic))
        if (binding.chkNotif.isChecked) perms.add(getString(R.string.perm_notif))
        return perms
    }

    private fun createProject() {
        val name = binding.inputProjectName.text?.toString()?.trim().orEmpty()
        val appName = binding.inputAppName.text?.toString()?.trim().orEmpty()

        val project = ProjectStore.create(this, name)
        project.appName = appName.ifBlank { name }
        project.permissions = selectedPermissions()
        ProjectStore.save(this, project)

        iconTemp?.takeIf { it.isFile }?.copyTo(project.iconFile(this), overwrite = true)

        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val token = prefs.getString("github_token", null)
        if (token == null) {
            Toast.makeText(this, R.string.github_need_login, Toast.LENGTH_LONG).show()
            openProject(project)
            return
        }

        binding.btnCreateProject.isEnabled = false
        binding.createProgress.visibility = View.VISIBLE
        binding.createStatus.text = getString(R.string.creating_repo)
        Thread {
            // إنشاء المستودع فقط — لا رفع ولا بناء قبل إضافة الملفات
            val ok = GitHubClient.createRepoOnly(token, project)
            runOnUiThread {
                binding.createProgress.visibility = View.GONE
                binding.createStatus.text = ""
                Toast.makeText(
                    this,
                    if (ok) R.string.repo_created else R.string.repo_create_failed,
                    Toast.LENGTH_LONG
                ).show()
                openProject(project)
            }
        }.start()
    }

    private fun openProject(project: Project) {
        startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", project.id))
        finish()
    }
}
