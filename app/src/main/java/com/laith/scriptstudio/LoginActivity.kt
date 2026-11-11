package com.laith.scriptstudio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.laith.scriptstudio.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenGithub.setOnClickListener {
            val url = "https://github.com/settings/tokens/new?scopes=repo,workflow,delete_repo&description=ScriptStudio"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, url, Toast.LENGTH_LONG).show()
            }
        }

        binding.btnLogin.setOnClickListener {
            val token = binding.inputToken.text?.toString()?.trim().orEmpty()
            if (token.isEmpty()) {
                Toast.makeText(this, R.string.login_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnLogin.isEnabled = false
            binding.loginStatus.text = getString(R.string.login_checking)
            Thread {
                val user = GitHubClient.login(token)
                runOnUiThread {
                    binding.btnLogin.isEnabled = true
                    if (user == null) {
                        binding.loginStatus.text = getString(R.string.login_invalid)
                    } else {
                        getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                            .putString("github_token", token)
                            .putString("github_user", user)
                            .apply()
                        Toast.makeText(this, "أهلاً $user — تم الربط بنجاح", Toast.LENGTH_LONG).show()
                        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
            }.start()
        }
    }
}
