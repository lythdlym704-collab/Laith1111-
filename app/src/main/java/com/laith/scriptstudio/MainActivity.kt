package com.laith.scriptstudio

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.laith.scriptstudio.databinding.ActivityMainBinding
import com.laith.scriptstudio.databinding.ItemProjectBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewProject.setOnClickListener {
            startActivity(Intent(this, NewProjectActivity::class.java))
        }
        binding.btnGithubLogin.setOnClickListener {
            val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
            if (prefs.getString("github_user", null) == null) {
                startActivity(Intent(this, LoginActivity::class.java))
            } else {
                prefs.edit().remove("github_user").remove("github_token").apply()
                refreshGithub()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGithub()
        refreshProjects()
    }

    private fun refreshGithub() {
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val user = prefs.getString("github_user", null)
        if (user == null) {
            binding.githubStatusMain.text = getString(R.string.login_out)
            binding.btnGithubLogin.text = getString(R.string.login_title)
        } else {
            binding.githubStatusMain.text = "مسجّل الدخول باسم: $user — المستودعات تُنشأ تلقائياً"
            binding.btnGithubLogin.text = getString(R.string.btn_logout)
        }
    }

    private fun refreshProjects() {
        binding.projectsContainer.removeAllViews()
        val projects = ProjectStore.list(this)
        binding.emptyText.visibility =
            if (projects.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        val inflater = LayoutInflater.from(this)
        for (project in projects) {
            val item = ItemProjectBinding.inflate(inflater, binding.projectsContainer, false)
            item.projectName.text = project.appName.ifBlank { project.name }
            item.projectSub.text =
                if (project.githubRepo.isBlank()) getString(R.string.not_linked)
                else "GitHub: ${project.githubRepo}"
            val icon = project.iconFile(this)
            if (icon.isFile) {
                item.projectIcon.setPadding(0, 0, 0, 0)
                item.projectIcon.setImageBitmap(BitmapFactory.decodeFile(icon.absolutePath))
            }
            item.root.setOnClickListener {
                startActivity(
                    Intent(this, ProjectActivity::class.java).putExtra("id", project.id)
                )
            }
            item.root.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(project.name)
                    .setMessage(R.string.delete_project_q)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        ProjectStore.delete(this, project)
                        refreshProjects()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                true
            }
            binding.projectsContainer.addView(item.root)
        }
    }
}
