package com.laith.scriptstudio

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.laith.scriptstudio.databinding.ActivityEditFileBinding
import java.io.File

/** إنشاء ملف جديد (اسم + كود) أو تعديل ملف موجود. */
class EditFileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditFileBinding
    private var project: Project? = null
    private var originalName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditFileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("id") ?: return finish()
        val p = ProjectStore.load(this, id) ?: return finish()
        project = p
        originalName = intent.getStringExtra("file")

        originalName?.let { name ->
            binding.editTitle.text = name
            binding.inputFileName.setText(name)
            val f = File(p.userFilesDir(this), name)
            if (f.isFile) {
                try {
                    binding.inputFileCode.setText(f.readText())
                } catch (e: Exception) {
                    binding.inputFileCode.setText("")
                }
            }
        }

        binding.btnSaveFile.setOnClickListener { save() }
    }

    private fun save() {
        val p = project ?: return
        val rawName = binding.inputFileName.text?.toString()?.trim().orEmpty()
        if (rawName.isEmpty()) {
            Toast.makeText(this, R.string.file_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val name = rawName.replace(Regex("""[/\\:*?"<>|]"""), "_")
        val dir = p.userFilesDir(this)
        val old = originalName
        if (old != null && old != name) {
            File(dir, old).delete()
        }
        File(dir, name).writeText(binding.inputFileCode.text?.toString().orEmpty())
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
