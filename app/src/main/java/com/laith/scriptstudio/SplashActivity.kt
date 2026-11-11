package com.laith.scriptstudio

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.laith.scriptstudio.databinding.ActivitySplashBinding

/**
 * شاشة البداية: اسم التطبيق يدور ثلاثي الأبعاد، ثم يتوقف
 * ويتحول إلى لون ذهبي لامع، ويظهر زر «تم».
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var shimmer: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val title = binding.splashTitle
        // عمق المنظور لجعل الدوران يبدو ثلاثي الأبعاد
        title.cameraDistance = 8000f * resources.displayMetrics.density

        val spin = ObjectAnimator.ofFloat(title, View.ROTATION_Y, 0f, 1080f).apply {
            duration = 2600
            interpolator = DecelerateInterpolator(1.6f)
        }
        spin.doOnEnd { showGold() }
        spin.start()

        binding.btnDone.setOnClickListener { goNext() }
    }

    private fun ObjectAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }

    private fun showGold() {
        val title = binding.splashTitle
        title.rotationY = 0f

        // تدرّج ذهبي + توهّج
        val width = title.paint.measureText(title.text.toString()).coerceAtLeast(1f)
        val colors = intArrayOf(0xFFB8860B.toInt(), 0xFFFFD700.toInt(), 0xFFFFF4B0.toInt(),
            0xFFFFD700.toInt(), 0xFFB8860B.toInt())
        val gradient = LinearGradient(0f, 0f, width, 0f, colors, null, Shader.TileMode.CLAMP)
        title.paint.shader = gradient
        title.setShadowLayer(24f, 0f, 0f, 0xAAFFD700.toInt())

        // نبضة تكبير عند التوقف
        title.scaleX = 0.9f
        title.scaleY = 0.9f
        title.animate().scaleX(1.08f).scaleY(1.08f).setDuration(450)
            .setInterpolator(OvershootInterpolator()).withEndAction {
                title.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
            }.start()

        // لمعان متحرك عبر الحروف
        val matrix = Matrix()
        shimmer = ValueAnimator.ofFloat(-width, width * 2).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                matrix.setTranslate(it.animatedValue as Float, 0f)
                gradient.setLocalMatrix(matrix)
                title.invalidate()
            }
            start()
        }

        binding.splashTagline.animate().alpha(1f).setDuration(600).start()
        binding.btnDone.visibility = View.VISIBLE
        binding.btnDone.animate().alpha(1f).setStartDelay(300).setDuration(600).start()
    }

    private fun goNext() {
        val loggedIn = getSharedPreferences("app", Context.MODE_PRIVATE)
            .getString("github_user", null) != null
        val target = if (loggedIn) MainActivity::class.java else LoginActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }

    override fun onDestroy() {
        shimmer?.cancel()
        super.onDestroy()
    }
}
