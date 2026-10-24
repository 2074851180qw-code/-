package com.fishpet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.sin

/**
 * 负责画宠物。
 * 1) 优先用 res/drawable-nodpi 里的透明 PNG 精灵帧：pet_<动画名>_<序号>.png
 *    动画名：idle / blink / walk / sleep / eat（可选 happy / drag / alert）
 *    例如 pet_walk_0.png, pet_walk_1.png, pet_walk_2.png ……
 * 2) 某个动画没有帧就退回 idle 帧；连 idle 都没有就用代码画一条蓝色占位鱼。
 * 素材约定：默认朝左，向右走时代码会自动镜像。
 */
class FishPainter(private val ctx: Context) {
    private val sprites = HashMap<String, List<Bitmap>>()
    /** 装扮前缀：pet = 毛衣款，maid = 女仆款 */
    var outfit: String = "pet"
        set(value) { if (field != value) { field = value; sprites.clear() } }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3B82F6.toInt() }
    private val bellyP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF93C5FD.toInt() }
    private val finP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
    private val whiteP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val blackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111827.toInt() }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111827.toInt(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E3A8A.toInt(); isFakeBoldText = true }
    private val path = Path()
    private val rect = RectF()

    private fun frames(anim: String): List<Bitmap> = sprites.getOrPut(anim) {
        val out = ArrayList<Bitmap>()
        var i = 0
        while (true) {
            val id = ctx.resources.getIdentifier("${outfit}_${anim}_$i", "drawable", ctx.packageName)
            if (id == 0) break
            BitmapFactory.decodeResource(ctx.resources, id)?.let { out.add(it) }
            i++
        }
        out
    }

    fun aspect(anim: String): Float {
        val list = frames(anim).ifEmpty { frames("idle") }
        val b = list.firstOrNull() ?: return 0.68f
        return if (b.height > 0) b.width.toFloat() / b.height.toFloat() else 0.68f
    }

    fun draw(c: Canvas, w: Float, h: Float, anim: String, frame: Int, blink: Boolean, flip: Boolean,
             phase: Float = 0f, walking: Boolean = false) {
        c.save()
        if (flip) c.scale(-1f, 1f, w / 2f, h / 2f)
        val blinkFrames = if (blink) frames("blink") else emptyList()
        val use = when {
            blinkFrames.isNotEmpty() -> blinkFrames
            else -> frames(anim).ifEmpty { frames("idle") }
        }
        if (use.isNotEmpty()) {
            val b = use[frame % use.size]
            // 保持原图比例，按高度缩放并底部对齐（之前是直接拉满正方形，所以比例怪）
            val scale = minOf(w / b.width.toFloat(), h / b.height.toFloat())
            val dw = b.width * scale
            val dh = b.height * scale
            val dx = (w - dw) / 2f
            val dy = h - dh
            // 直接按比例绘制。之前的 drawBitmapMesh 在大尺寸下会把贴图撕成竖条，已移除。
            c.drawBitmap(b, null, RectF(dx, dy, dx + dw, dy + dh), bmpPaint)
        } else {
            procedural(c, w, h, anim, frame, blink)
        }
        c.restore()
    }

    private fun procedural(c: Canvas, w: Float, h: Float, anim: String, frame: Int, blink: Boolean) {
        val bob = if (anim == "sleep") sin(frame * 0.4f) * h * 0.02f else sin(frame * 0.8f) * h * 0.03f
        val swing = if (anim == "walk") sin(frame * 1.6f) * h * 0.08f else sin(frame * 0.6f) * h * 0.03f
        strokeP.strokeWidth = w * 0.025f
        c.save()
        c.translate(0f, bob)

        // 尾巴
        path.reset()
        path.moveTo(w * .76f, h * .52f); path.lineTo(w * .97f, h * .30f + swing)
        path.lineTo(w * .97f, h * .74f + swing); path.close()
        c.drawPath(path, finP)
        // 背鳍
        path.reset()
        path.moveTo(w * .40f, h * .30f); path.lineTo(w * .52f, h * .13f); path.lineTo(w * .66f, h * .32f); path.close()
        c.drawPath(path, finP)
        // 身体和肚皮
        rect.set(w * .06f, h * .26f, w * .84f, h * .78f); c.drawOval(rect, bodyP)
        rect.set(w * .12f, h * .52f, w * .78f, h * .76f); c.drawOval(rect, bellyP)

        // 眼睛
        val ex = w * .27f
        val ey = h * .46f
        when {
            anim == "sleep" || blink -> c.drawLine(ex - w * .05f, ey, ex + w * .05f, ey, strokeP)
            anim == "happy" -> {
                path.reset()
                path.moveTo(ex - w * .05f, ey + h * .02f)
                path.quadTo(ex, ey - h * .06f, ex + w * .05f, ey + h * .02f)
                c.drawPath(path, strokeP)
            }
            anim == "alert" -> { c.drawCircle(ex, ey, w * .075f, whiteP); c.drawCircle(ex, ey, w * .02f, blackP) }
            else -> { c.drawCircle(ex, ey, w * .06f, whiteP); c.drawCircle(ex - w * .01f, ey, w * .03f, blackP) }
        }

        // 嘴
        if (anim == "eat" && frame % 2 == 0) {
            rect.set(w * .07f, h * .53f, w * .15f, h * .63f); c.drawOval(rect, blackP)
        } else {
            c.drawLine(w * .08f, h * .57f, w * .16f, h * .58f, strokeP)
        }

        // 小装饰
        textP.textSize = h * .16f
        if (anim == "sleep") c.drawText("z", w * .52f, h * .22f - (frame % 6) * h * .012f, textP)
        if (anim == "alert") c.drawText("!", w * .18f, h * .22f, textP)
        c.restore()
    }
}
