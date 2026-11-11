package com.laith.scriptstudio

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.laith.scriptstudio.databinding.ActivitySettingsBinding

/** إعدادات المشروع (الترس): صورة التطبيق، اسمه، صلاحياته، واسم مستودعه. */
class ProjectSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var project: Project? = null

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val p = project ?: return@registerForActivityResult
            if (uri != null) {
                contentResolver.openInputStream(uri)?.use { input ->
                    p.iconFile(this).outputStream().use { input.copyTo(it) }
                }
                showIcon()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("id") ?: return finish()
        val p = ProjectStore.load(this, id) ?: return finish()
        project = p

        binding.inputAppName.setText(p.appName)
        binding.inputRepo.setText(p.githubRepo)
        binding.chkInternet.isChecked = getString(R.string.perm_internet) in p.permissions
        binding.chkCamera.isChecked = getString(R.string.perm_camera) in p.permissions
        binding.chkStorage.isChecked = getString(R.string.perm_storage) in p.permissions
        binding.chkLocation.isChecked = getString(R.string.perm_location) in p.permissions
        binding.chkMic.isChecked = getString(R.string.perm_mic) in p.permissions
        binding.chkNotif.isChecked = getString(R.string.perm_notif) in p.permissions
        showIcon()

        binding.btnPickImage.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        binding.btnSave.setOnClickListener {
            p.appName = binding.inputAppName.text?.toString()?.trim()
                .orEmpty().ifBlank { p.name }
            p.githubRepo = ProjectStore.sanitizeRepoName(
                binding.inputRepo.text?.toString().orEmpty().ifBlank { p.name }
            )
            val perms = mutableListOf<String>()
            if (binding.chkInternet.isChecked) perms.add(getString(R.string.perm_internet))
            if (binding.chkCamera.isChecked) perms.add(getString(R.string.perm_camera))
            if (binding.chkStorage.isChecked) perms.add(getString(R.string.perm_storage))
            if (binding.chkLocation.isChecked) perms.add(getString(R.string.perm_location))
            if (binding.chkMic.isChecked) perms.add(getString(R.string.perm_mic))
            if (binding.chkNotif.isChecked) perms.add(getString(R.string.perm_notif))
            p.permissions = perms
            ProjectStore.save(this, p)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showIcon() {
        val p = project ?: return
        val icon = p.iconFile(this)
        if (icon.isFile) {
            binding.iconPreview.setPadding(0, 0, 0, 0)
            binding.iconPreview.setImageBitmap(BitmapFactory.decodeFile(icon.absolutePath))
        }
    }
}
