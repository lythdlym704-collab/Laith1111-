package com.laith.scriptstudio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.laith.scriptstudio.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var building = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCheck.setOnClickListener { onCheck() }
        binding.btnBuild.setOnClickListener { onBuild() }
        binding.btnClear.setOnClickListener {
            binding.inputScript.setText("")
            showPlaceholder()
        }
        binding.btnSample.setOnClickListener { binding.inputScript.setText(SAMPLE) }
        binding.btnCopy.setOnClickListener { copyResult() }
    }

    private fun currentScript(): String = binding.inputScript.text?.toString().orEmpty()

    private fun onCheck() {
        hideKeyboard()
        val script = currentScript()
        if (script.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.empty_script), Snackbar.LENGTH_SHORT).show()
            return
        }
        binding.resultsTitle.text = "نتيجة الفحص"
        val report = ScriptAnalyzer.analyze(script)
        binding.resultsText.text = report
        binding.btnCopy.visibility = android.view.View.VISIBLE
    }

    private fun onBuild() {
        hideKeyboard()
        if (building) return
        val script = currentScript()
        if (script.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.empty_script), Snackbar.LENGTH_SHORT).show()
            return
        }

        building = true
        setButtonsEnabled(false)
        binding.btnCopy.visibility = android.view.View.GONE
        binding.resultsTitle.text = "جارٍ البناء…"
        binding.progress.visibility = android.view.View.VISIBLE
        binding.progress.isIndeterminate = false
        binding.progress.max = ScriptBuilder.steps.size
        binding.progress.progress = 0

        val log = StringBuilder()
        val steps = ScriptBuilder.steps

        fun runStep(i: Int) {
            if (i >= steps.size) {
                val result = ScriptBuilder.build(this, script, getString(R.string.app_name))
                binding.progress.visibility = android.view.View.GONE
                binding.resultsTitle.text = "نتيجة البناء"
                binding.resultsText.text = log.toString() + "\n" + result.summary
                binding.btnCopy.visibility = android.view.View.VISIBLE
                building = false
                setButtonsEnabled(true)
                Snackbar.make(binding.root, getString(R.string.build_done), Snackbar.LENGTH_SHORT).show()
                return
            }
            log.append("▸ ${steps[i]} … تم\n")
            binding.resultsText.text = log.toString()
            binding.progress.progress = i + 1
            handler.postDelayed({ runStep(i + 1) }, 420)
        }
        runStep(0)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnCheck.isEnabled = enabled
        binding.btnBuild.isEnabled = enabled
        binding.btnClear.isEnabled = enabled
        binding.btnSample.isEnabled = enabled
    }

    private fun copyResult() {
        val text = binding.resultsText.text?.toString().orEmpty()
        if (text.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("result", text))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private fun showPlaceholder() {
        binding.resultsTitle.text = getString(R.string.results_title)
        binding.resultsText.text = getString(R.string.results_placeholder)
        binding.btnCopy.visibility = android.view.View.GONE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    companion object {
        private val SAMPLE = """
            #!/bin/bash
            # نموذج: ينسخ ملفات ثم يحمّل تحديثاً
            echo "بدء العملية"
            mkdir -p /tmp/backup
            cp -r ~/data /tmp/backup
            curl -s https://example.com/update.sh -o update.sh
            for f in /tmp/backup/*; do
              echo "نسخ: ${'$'}f"
            done
            echo "انتهى"
        """.trimIndent()
    }
}
