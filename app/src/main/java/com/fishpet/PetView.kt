package com.fishpet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.sin

/**
 * 宠物本体。
 *
 * 相比只“换帧”，这里做了三件让动作更像活物的事：
 *  1. 网格摆动（drawBitmapMesh）：越靠下摆幅越大，头发和尾巴会跟着飘
 *  2. 地面投影：影子随跳起高度变淡变小，站住时有“踩在地上”的感觉
 *  3. 落地挤压（squash & stretch）：落下来先压扁再弹回
 */
class PetView(context: Context) : View(context) {
    var anim: String = "idle"
    var frame: Int = 0
    var blink: Boolean = false
    var facingRight: Boolean = false
    var hop: Float = 0f
    var phase: Float = 0f
    var squash: Float = 1f          // 1 = 原始；<1 压扁；>1 拉长
    var walking: Boolean = false

    private val painter = FishPainter(context)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val shadowRect = RectF()

    var outfit: String
        get() = painter.outfit
        set(value) { painter.outfit = value }

    fun aspect(): Float = painter.aspect(anim)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val breathe = when (anim) {
            "sleep" -> sin(phase * 1.1f) * h * 0.008f
            "walk" -> sin(phase * 6.0f) * h * 0.006f
            else -> sin(phase * 1.9f) * h * 0.010f
        }
        // 不做整体倾斜：整张图旋转会像“身体歪着晃”，看着很诡异
        val tilt = 0f

        // 地面影子：跳得越高越淡越小
        val lift = (-hop).coerceAtLeast(0f)
        val ratio = (1f - lift / (h * 0.6f)).coerceIn(0.35f, 1f)
        val sw = w * 0.34f * ratio
        val sh = h * 0.05f * ratio
        shadowPaint.alpha = (90 * ratio).toInt().coerceIn(20, 90)
        shadowRect.set(w / 2f - sw / 2f, h - sh * 1.6f, w / 2f + sw / 2f, h - sh * 0.4f)
        canvas.drawOval(shadowRect, shadowPaint)

        canvas.save()
        if (tilt != 0f) canvas.rotate(tilt, w / 2f, h)
        canvas.translate(0f, hop + breathe)
        if (squash != 1f) {
            val sq = squash.coerceIn(0.9f, 1.08f)
            canvas.scale(1f / sq, sq, w / 2f, h)
        }
        painter.draw(canvas, w, h, anim, frame, blink, facingRight, phase, walking)
        canvas.restore()
    }
}
