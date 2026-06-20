package com.example.hfpdetector

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class SlideToAnswerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var onAnswered: (() -> Unit)? = null

    private val track = FrameLayout(context)
    private val label = TextView(context)
    private val hint = TextView(context)
    private val shine = View(context)

    private val thumb = FrameLayout(context)
    private val thumbIcon = ImageView(context)

    private var maxX = 0f
    private var downRawX = 0f
    private var startTx = 0f
    private var answered = false

    private var shineAnimator: ObjectAnimator? = null

    init {
        // 外层高度
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(66))

        // 轨道（渐变）
        track.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.parseColor("#1A1A1A"),
                Color.parseColor("#2A2A2A"),
                Color.parseColor("#1A1A1A")
            )
        ).apply { cornerRadius = dp(999).toFloat() }
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 轨道上的“流光”
        shine.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#22FFFFFF"),
                Color.TRANSPARENT
            )
        ).apply { cornerRadius = dp(999).toFloat() }
        track.addView(shine, LayoutParams(dp(120), LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })

        // 主文案
        label.text = "滑动接听"
        label.setTextColor(Color.parseColor("#EDEDED"))
        label.textSize = 16f
        label.typeface = Typeface.DEFAULT_BOLD
        label.gravity = Gravity.CENTER
        track.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 说明
        hint.text = "接听后开始两机免提对讲"
        hint.setTextColor(Color.parseColor("#A8A8A8"))
        hint.textSize = 12f
        hint.gravity = Gravity.CENTER
        track.addView(hint, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(26)
        })

        // 滑块（圆形）
        thumb.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#2E7D32"))
        }
        thumb.elevation = dp(6).toFloat()
        track.addView(thumb, LayoutParams(dp(54), dp(54)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(6)
        })

        // 电话图标（系统自带）
        thumbIcon.setImageResource(android.R.drawable.sym_action_call)
        thumbIcon.setColorFilter(Color.WHITE)
        thumb.addView(thumbIcon, LayoutParams(dp(26), dp(26)).apply {
            gravity = Gravity.CENTER
        })

        // 只允许拖动滑块
        thumb.setOnTouchListener { _, ev ->
            if (answered) return@setOnTouchListener true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    startTx = thumb.translationX
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val target = startTx + dx
                    setThumbX(target)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val progress = if (maxX <= 0f) 0f else (thumb.translationX / maxX)
                    if (progress >= 0.88f) {
                        animateToEndThenAnswer()
                    } else {
                        animateBack()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShine()
    }

    override fun onDetachedFromWindow() {
        stopShine()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 轨道宽度 - 左右边距 - 滑块宽度
        // thumb 初始左边距 = 6dp，所以 maxX 需要扣掉这个 margin
        val trackW = track.width
        val thumbW = dp(54)
        val leftMargin = dp(6)
        maxX = max(0f, (trackW - thumbW - leftMargin * 2).toFloat())
        setThumbX(thumb.translationX)
    }

    private fun setThumbX(x: Float) {
        val nx = min(max(0f, x), maxX)
        thumb.translationX = nx

        val p = if (maxX <= 0f) 0f else nx / maxX
        // 文案随滑动淡出
        label.alpha = 1f - min(1f, p * 1.2f)
        hint.alpha = 1f - min(1f, p * 1.2f)
    }

    private fun animateBack() {
        val anim = ValueAnimator.ofFloat(thumb.translationX, 0f)
        anim.duration = 260
        anim.interpolator = OvershootInterpolator(1.1f) // 回弹
        anim.addUpdateListener { setThumbX(it.animatedValue as Float) }
        anim.start()
    }

    private fun animateToEndThenAnswer() {
        val anim = ValueAnimator.ofFloat(thumb.translationX, maxX)
        anim.duration = 120
        anim.addUpdateListener { setThumbX(it.animatedValue as Float) }
        anim.start()
        anim.doOnEnd {
            answered = true
            onAnswered?.invoke()
        }
    }

    private fun startShine() {
        if (shineAnimator != null) return
        shineAnimator = ObjectAnimator.ofFloat(shine, "translationX", -dp(120).toFloat(), track.width.toFloat()).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }
    }

    private fun stopShine() {
        shineAnimator?.cancel()
        shineAnimator = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

// 小工具：给 ValueAnimator 一个 onEnd 回调（避免引入 androidx）
private fun ValueAnimator.doOnEnd(block: () -> Unit) {
    addListener(object : android.animation.Animator.AnimatorListener {
        override fun onAnimationStart(animation: android.animation.Animator) {}
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
        override fun onAnimationCancel(animation: android.animation.Animator) {}
        override fun onAnimationRepeat(animation: android.animation.Animator) {}
    })
}
