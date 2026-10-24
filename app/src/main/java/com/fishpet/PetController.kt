package com.fishpet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

enum class PetState(val anim: String) {
    IDLE("idle"), WALK("walk"), SLEEP("sleep"), HAPPY("happy"), EAT("eat"), DRAG("drag"), ALERT("alert"),
    AIR("drag") // 被扔出去/跳起来时在空中
}

/** 菜单/聊天面板用的容器：能收到“点了窗口外面”和返回键。 */
class OverlayPanel(
    ctx: Context,
    private val onOutside: () -> Unit,
    private val onBack: (() -> Unit)? = null,
) : LinearLayout(ctx) {
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_OUTSIDE) { onOutside(); return true }
        return super.onTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (onBack != null && e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) onBack.invoke()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}

/**
 * 桌宠核心：管理悬浮窗（宠物 / 气泡 / 菜单 / 聊天面板）、状态机和互动。
 *
 * 点击穿透的做法：宠物窗口的大小就等于宠物的大小，窗口之外的区域天然点得到桌面；
 * 再加 FLAG_NOT_TOUCH_MODAL，窗口外的触摸不会被这个窗口吞掉。
 * 气泡窗口设为 NOT_TOUCHABLE，完全不挡触摸。
 */
class PetController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val onQuit: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun px(v: Int): Int = (v * density).toInt()
    private fun screenW(): Int = ctx.resources.displayMetrics.widthPixels
    private fun screenH(): Int = ctx.resources.displayMetrics.heightPixels

    private var petSize = px(150 * PetPrefs.sizePercent(ctx) / 100)
    private var petW = petSize
    private val view = PetView(ctx)
    private val params = overlayParams(petSize, petSize, focusable = false).apply {
        gravity = Gravity.TOP or Gravity.START
    }
    private var attached = false

    // 状态
    private var state = PetState.IDLE
    private var stateToken = 0
    private var lastAct = SystemClock.elapsedRealtime()
    private var tickN = 0
    private var walkDir = -1
    private var walkLeft = 0f
    private var tapTimes = ArrayList<Long>()

    private fun overlayParams(w: Int, hgt: Int, focusable: Boolean, touchable: Boolean = true): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            w, hgt, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        )
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != 0) setStroke(px(1), stroke)
        }

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(v, p) } catch (_: IllegalArgumentException) {}
    }

    // ---------------------------------------------------------------- 属性 / 物理 / 物品

    private val GRAV = 2400f * density           // 重力，像素/秒²
    private var posX = 0f                        // 浮点位置，物理计算用
    private var posY = 0f
    private var vx = 0f
    private var vy = 0f
    private var airBounces = 0
    private var lastFrameMs = 0L
    private var walkSpeed = 0f
    private var hopWalk = false
    private var seekItem: FloatItem? = null
    private var seekUntil = 0L
    private var gravityOn = true
    private var gapHours = 0f

    /** 场上的小物件：食物（落地后等宠物来吃）和皮球（会弹，宠物会追着踢）。窗口完全不挡触摸。 */
    private class FloatItem(val kind: String, val view: TextView, val lp: WindowManager.LayoutParams, val size: Int) {
        var tag = ""
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var born = 0L
        var rest = false
    }

    private val items = ArrayList<FloatItem>()

    private fun hour(): Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    private fun isNight(): Boolean = hour().let { it >= 23 || it < 6 }

    private fun movePet(x: Float, y: Float) {
        posX = x; posY = y
        params.x = x.toInt(); params.y = y.toInt()
        safeUpdate(view, params)
        placeBubble()
    }

    private fun gainXp(n: Int) {
        val before = PetPrefs.level(ctx)
        PetPrefs.setXp(ctx, PetPrefs.xp(ctx) + n)
        val after = PetPrefs.level(ctx)
        if (after > before) {
            PetPrefs.setCoins(ctx, PetPrefs.coins(ctx) + 20)
            setState(PetState.HAPPY, 1500)
            say("升级啦！Lv.$after，奖励 20 金币～", 4000)
        }
    }

    private fun spawnItem(kind: String, emoji: String): FloatItem? {
        val size = px(if (kind == "ball") 44 else 40)
        val tv = TextView(ctx)
        tv.text = emoji
        tv.textSize = if (kind == "ball") 30f else 26f
        tv.gravity = Gravity.CENTER
        tv.includeFontPadding = false
        val lp = overlayParams(size, size, focusable = false, touchable = false)
        lp.gravity = Gravity.TOP or Gravity.START
        val item = FloatItem(kind, tv, lp, size)
        item.x = px(20) + (Math.random() * (screenW() - size - px(40))).toFloat()
        item.y = 0f
        item.born = SystemClock.elapsedRealtime()
        lp.x = item.x.toInt(); lp.y = 0
        try { wm.addView(tv, lp) } catch (_: Exception) { return null }
        items.add(item)
        return item
    }

    private fun removeItem(item: FloatItem) {
        try { wm.removeView(item.view) } catch (_: Exception) {}
        items.remove(item)
    }

    private fun hasItem(kind: String): Boolean = items.any { it.kind == kind }

    private fun nearestItem(kind: String): FloatItem? =
        items.filter { it.kind == kind }.minByOrNull { abs(it.x - posX) }

    private fun updateItems(dt: Float) {
        val now = SystemClock.elapsedRealtime()
        val iter = items.iterator()
        while (iter.hasNext()) {
            val item = iter.next()
            if (item.kind == "food" && now - item.born > 120_000) {
                try { wm.removeView(item.view) } catch (_: Exception) {}
                iter.remove()
                continue
            }
            val floor = (screenH() - px(36) - item.size).toFloat()
            if (!item.rest) {
                item.vy += GRAV * dt
                item.x += item.vx * dt
                item.y += item.vy * dt
                val maxX = (screenW() - item.size).toFloat()
                if (item.x < 0f) { item.x = 0f; item.vx = -item.vx * 0.7f }
                if (item.x > maxX) { item.x = maxX; item.vx = -item.vx * 0.7f }
                if (item.y >= floor) {
                    item.y = floor
                    if (item.kind == "ball") {
                        if (abs(item.vy) > px(250)) {
                            item.vy = -item.vy * 0.62f
                            item.vx *= 0.92f
                        } else {
                            item.vy = 0f
                            item.vx *= (1f - 1.6f * dt)
                            if (abs(item.vx) < px(10)) item.vx = 0f
                        }
                    } else {
                        item.vy = 0f; item.vx = 0f; item.rest = true
                    }
                }
            }
            item.lp.x = item.x.toInt(); item.lp.y = item.y.toInt()
            safeUpdate(item.view, item.lp)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 让悬浮窗宽度贴合精灵本身的比例（不再用正方形窗口），
     * 这样宠物可以一直走到屏幕边缘，不会提前被看不见的窗口边界拦住。
     */
    private fun syncWindowSize() {
        val a = view.aspect().coerceIn(0.35f, 1.6f)
        petW = (petSize * a).toInt().coerceAtLeast(px(40))
        params.width = petW
        params.height = petSize
        val nx = posX.coerceIn(0f, (screenW() - petW).coerceAtLeast(0).toFloat())
        val ny = posY.coerceIn(0f, (screenH() - petSize).coerceAtLeast(0).toFloat())
        movePet(nx, ny)
    }

    /** 宠物站立的地面线：屏幕底边往上留一点。 */
    private fun groundY(): Int = screenH() - petSize - px(36)

    /** 离开期间宠物会变饿、变困；久别重逢会撒娇。 */
    private fun applyOfflineDecay() {
        val last = PetPrefs.lastSeen(ctx)
        val now = System.currentTimeMillis()
        if (last > 0) {
            val hrs = ((now - last) / 3_600_000f).coerceIn(0f, 24f)
            val hu = PetPrefs.hunger(ctx)
            PetPrefs.setHunger(ctx, (hu - (hrs * 2).toInt()).coerceAtLeast(minOf(hu, 10)))
            val mo = PetPrefs.mood(ctx)
            PetPrefs.setMood(ctx, (mo - hrs.toInt()).coerceAtLeast(minOf(mo, 20)))
            PetPrefs.setEnergy(ctx, PetPrefs.energy(ctx) + (hrs * 5).toInt())
            gapHours = hrs
        }
        PetPrefs.setLastSeen(ctx, now)
    }

    private fun greeting(): String {
        if (gapHours >= 8f) return "你终于回来啦，想死我了！"
        return when (hour()) {
            in 5..10 -> "早上好～"
            in 11..13 -> "该吃午饭啦～"
            in 14..17 -> "下午好，摸会儿鱼？"
            in 18..22 -> "晚上好～"
            else -> "这么晚了还不睡呀？"
        }
    }

    fun show() {
        if (attached) return
        gravityOn = PetPrefs.gravity(ctx)
        posX = PetPrefs.lastX(ctx, screenW() - petSize - px(8)).toFloat()
        posY = PetPrefs.lastY(ctx, groundY()).toFloat()
        params.x = posX.toInt(); params.y = posY.toInt()
        val saved = PetPrefs.outfit(ctx)
        view.outfit = if (outfits.any { it.first == saved }) saved else "maid"
        wm.addView(view, params)
        attached = true
        syncWindowSize()
        view.setOnTouchListener(touchListener)
        applyOfflineDecay()
        h.post(ticker)
        h.postDelayed(aiTick, 5000)
        h.postDelayed(statTick, 60_000)
        say(greeting(), 4500)
    }

    fun destroy() {
        h.removeCallbacksAndMessages(null)
        chatJob?.cancel()
        PetPrefs.setLastSeen(ctx, System.currentTimeMillis())
        hideMenu(); hideBubble(); closeChat()
        items.toList().forEach { removeItem(it) }
        if (attached) { try { wm.removeView(view) } catch (_: Exception) {}; attached = false }
    }

    // ---------------------------------------------------------------- 状态机

    private fun setState(s: PetState, revertMs: Long = 0) {
        state = s
        val tk = ++stateToken
        if (s != PetState.WALK) seekItem = null
        view.anim = s.anim
        view.frame = 0
        view.invalidate()
        if (revertMs > 0) h.postDelayed({ if (stateToken == tk) setState(PetState.IDLE) }, revertMs)
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0.001f, 0.05f)
            lastFrameMs = now
            tickN++
            view.phase += dt
            // 帧图每 ~200ms 换一张；平滑感主要来自 phase 驱动的连续变换
            if (tickN % 12 == 0) view.frame++
            view.blink = state == PetState.IDLE && tickN % 220 in 0..3
            view.walking = state == PetState.WALK
            view.hop = when {
                state == PetState.HAPPY -> -abs(sin(view.phase * 7f)) * petSize * 0.15f
                state == PetState.WALK && hopWalk -> -abs(sin(view.phase * 9f)) * petSize * 0.08f
                else -> 0f
            }
            when (state) {
                PetState.WALK -> stepWalk(dt)
                PetState.AIR -> stepAir(dt)
                else -> {}
            }
            // 重力模式：悬在半空（比如刚改了大小）就掉下来
            if (gravityOn && !dragging && state != PetState.AIR && state != PetState.DRAG &&
                posY < groundY() - px(3)
            ) {
                vx = 0f; vy = 0f
                startAir("drag")
            }
            if (items.isNotEmpty()) updateItems(dt)
            // 有球的话，闲着就去追
            if (tickN % 40 == 0 && state == PetState.IDLE && !dragging) {
                val ball = nearestItem("ball")
                if (ball != null && Math.random() < 0.6) seek(ball)
            }
            view.invalidate()
            h.postDelayed(this, 16)
        }
    }

    private fun startAir(anim: String) {
        airBounces = 0
        setState(PetState.AIR)
        view.anim = anim
    }

    private fun stepAir(dt: Float) {
        vy += GRAV * dt
        var nx = posX + vx * dt
        var ny = posY + vy * dt
        val maxX = (screenW() - petW).toFloat()
        if (nx < 0f) { nx = 0f; vx = -vx * 0.6f } else if (nx > maxX) { nx = maxX; vx = -vx * 0.6f }
        if (ny < 0f) { ny = 0f; vy = -vy * 0.4f }
        val floor = groundY().toFloat()
        if (ny >= floor) {
            ny = floor
            val impact = abs(vy)
            if (impact > px(700) && airBounces < 2) {
                vy = -vy * 0.35f
                vx *= 0.7f
                airBounces++
                bounce()
            } else {
                vy = 0f; vx = 0f
                movePet(nx, ny)
                land(impact)
                return
            }
        }
        movePet(nx, ny)
    }

    private fun land(impact: Float) {
        PetPrefs.setLastX(ctx, posX.toInt())
        PetPrefs.setLastY(ctx, posY.toInt())
        bounce()
        if (impact > px(1700)) {
            PetPrefs.setMood(ctx, PetPrefs.mood(ctx) - 2)
            setState(PetState.ALERT, 1200)
            say(Lines.pick(Lines.ouch))
        } else {
            setState(PetState.HAPPY, 900)
        }
    }

    private fun stepWalk(dt: Float) {
        val tgt = seekItem
        if (tgt != null) {
            if (!items.contains(tgt) || SystemClock.elapsedRealtime() > seekUntil) { setState(PetState.IDLE); return }
            val dx = (tgt.x + tgt.size / 2f) - (posX + petW / 2f)
            walkDir = if (dx < 0f) -1 else 1
            view.facingRight = walkDir > 0
            val feet = posY + petSize
            val bottom = tgt.y + tgt.size
            val closeV = !gravityOn || abs(feet - bottom) < petSize * 0.5f
            if (abs(dx) <= petW * 0.3f && closeV) {
                if (tgt.kind == "food") eat(tgt) else kick(tgt)
                return
            }
            if (abs(dx) > petW * 0.12f) {
                val nx = (posX + walkDir * walkSpeed * dt).coerceIn(0f, (screenW() - petW).toFloat())
                movePet(nx, posY)
            }
            return
        }
        val step = walkDir * walkSpeed * dt
        val nx = posX + step
        if (walkLeft <= 0f || nx < 0f || nx > screenW() - petW) { setState(PetState.IDLE); return }
        walkLeft -= abs(step)
        view.facingRight = walkDir > 0
        movePet(nx, posY)
    }

    private fun startWalk(run: Boolean, hop: Boolean) {
        walkDir = if (posX + petW / 2f < screenW() / 2f) 1 else -1
        if (Math.random() < 0.3) walkDir = -walkDir
        walkLeft = px(80 + (Math.random() * 220).toInt()).toFloat()
        walkSpeed = px(if (run) 190 else 60).toFloat()
        hopWalk = hop
        setState(PetState.WALK)
    }

    private fun seek(item: FloatItem) {
        walkSpeed = px(200).toFloat()
        hopWalk = false
        setState(PetState.WALK)
        seekItem = item
        seekUntil = SystemClock.elapsedRealtime() + 12_000
    }

    private fun wake(msg: String) { setState(PetState.IDLE); say(msg) }

    private fun goSleep() { setState(PetState.SLEEP); say("晚安……呼噜。", 2500) }

    private fun yawn() { say(Lines.pick(Lines.yawn)); bounce() }

    private fun jump() {
        if (gravityOn) {
            vx = 0f
            vy = -px(950).toFloat()
            startAir("happy")
        } else {
            setState(PetState.HAPPY, 1000)
        }
    }

    private fun spin() {
        setState(PetState.HAPPY, 1100)
        val my = stateToken
        for (i in 1..6) h.postDelayed({ if (stateToken == my) view.facingRight = !view.facingRight }, i * 150L)
    }

    // ---------------------------------------------------------------- 吃东西 / 玩球

    private fun dropFood(kind: String) {
        val (emoji, cost) = when (kind) {
            "fish" -> "🐟" to 5
            "cake" -> "🍰" to 12
            else -> "🍚" to 0
        }
        if (items.count { it.kind == "food" } >= 3) { say("盘子满啦，让我先吃完～", 2500); return }
        if (cost > 0) {
            val coins = PetPrefs.coins(ctx)
            if (coins < cost) { say("金币不够啦（还差 ${cost - coins}）", 2500); return }
            PetPrefs.setCoins(ctx, coins - cost)
        }
        val item = spawnItem("food", emoji) ?: return
        item.tag = kind
        lastAct = SystemClock.elapsedRealtime()
        if (state == PetState.SLEEP) wake("闻到饭味了！")
    }

    private fun eat(item: FloatItem) {
        removeItem(item)
        val (hu, mo) = when (item.tag) {
            "fish" -> 30 to 8
            "cake" -> 45 to 15
            else -> 25 to 3
        }
        PetPrefs.setHunger(ctx, PetPrefs.hunger(ctx) + hu)
        PetPrefs.setMood(ctx, PetPrefs.mood(ctx) + mo)
        lastAct = SystemClock.elapsedRealtime()
        setState(PetState.EAT, 1800)
        say(Lines.pick(Lines.feed))
        gainXp(3)
    }

    private fun toggleBall() {
        val ball = nearestItem("ball")
        if (ball != null) { removeItem(ball); say("球收起来啦～", 1800); return }
        val b = spawnItem("ball", "🏀") ?: return
        b.vx = ((Math.random() - 0.5) * px(700)).toFloat()
        lastAct = SystemClock.elapsedRealtime()
        if (state == PetState.SLEEP) wake("有球！")
        else say(Lines.pick(Lines.play), 2500)
    }

    private fun kick(ball: FloatItem) {
        val dirTo = if (posX + petW / 2f < screenW() / 2f) 1 else -1
        ball.rest = false
        ball.vx = dirTo * (px(450) + Math.random() * px(450)).toFloat()
        ball.vy = -(px(900) + Math.random() * px(700)).toFloat()
        view.facingRight = dirTo > 0
        setState(PetState.HAPPY, 600)
        gainXp(1)
        if (Math.random() < 0.3) say(Lines.pick(Lines.play), 1800)
    }

    // ---------------------------------------------------------------- 自主行为（像真宠物一样有自己的想法）

    private val aiTick = object : Runnable {
        override fun run() {
            think()
            h.postDelayed(this, 4000)
        }
    }

    private fun chatterLine(): String = when {
        isNight() -> Lines.pick(Lines.night)
        PetPrefs.hunger(ctx) < 35 -> Lines.pick(Lines.hungry)
        PetPrefs.energy(ctx) < 30 -> Lines.pick(Lines.sleepy)
        else -> Lines.pick(Lines.idle)
    }

    private fun think() {
        if (dragging) return
        val idleSec = (SystemClock.elapsedRealtime() - lastAct) / 1000
        val hu = PetPrefs.hunger(ctx)
        val en = PetPrefs.energy(ctx)
        val mo = PetPrefs.mood(ctx)

        if (state == PetState.SLEEP) {
            if (en >= 95 && !isNight()) wake("睡饱啦！")
            else if (hasItem("food") && hu < 70) wake("好香……")
            else if (Math.random() < 0.15) say(Lines.pick(Lines.dream), 2200)
            return
        }
        if (state != PetState.IDLE) return

        val food = nearestItem("food")
        if (food != null) { seek(food); return }
        if (en < 15 || (isNight() && idleSec > 40) || idleSec > 300) { goSleep(); return }
        if (hu < 25 && Math.random() < 0.5) {
            setState(PetState.ALERT, 1500); say(Lines.pick(Lines.hungry)); return
        }

        val acts = ArrayList<Pair<Int, () -> Unit>>()
        fun add(w: Int, a: () -> Unit) { acts.add(w to a) }
        add(25) { startWalk(false, false) }
        add(8) { startWalk(true, false) }
        add(10) { startWalk(false, true) }
        if (mo > 55) add(8) { jump() }
        if (mo > 65) add(5) { spin() }
        add(8) { yawn() }
        add(12) { say(chatterLine()) }
        add(14) { }
        val total = acts.sumOf { it.first }
        var r = (Math.random() * total).toInt()
        for ((w, act) in acts) {
            if (r < w) { act(); return }
            r -= w
        }
    }

    /** 每分钟：饿一点、困一点；睡着时回精力。 */
    private var statMinutes = 0
    private val statTick = object : Runnable {
        override fun run() {
            statMinutes++
            val sleeping = state == PetState.SLEEP
            val hu = PetPrefs.hunger(ctx) - 1
            PetPrefs.setHunger(ctx, hu)
            val en = PetPrefs.energy(ctx) + if (sleeping) 4 else -1
            PetPrefs.setEnergy(ctx, en)
            if (hu < 25 || en < 20) PetPrefs.setMood(ctx, PetPrefs.mood(ctx) - 1)
            if (!sleeping && statMinutes % 5 == 0) PetPrefs.setCoins(ctx, PetPrefs.coins(ctx) + 1)
            PetPrefs.setLastSeen(ctx, System.currentTimeMillis())
            h.postDelayed(this, 60_000)
        }
    }

    private fun onTap() {
        val now = SystemClock.elapsedRealtime()
        lastAct = now
        tapTimes = ArrayList(tapTimes.filter { now - it < 3000 })
        tapTimes.add(now)
        if (state == PetState.SLEEP) { setState(PetState.IDLE); say(Lines.pick(Lines.wake)); return }
        if (tapTimes.size >= 6) {
            tapTimes.clear()
            setState(PetState.ALERT, 2000); say(Lines.pick(Lines.angry)); return
        }
        PetPrefs.setMood(ctx, PetPrefs.mood(ctx) + 2)
        if (Math.random() < 0.25) PetPrefs.setCoins(ctx, PetPrefs.coins(ctx) + 1)
        gainXp(2)
        if (state != PetState.AIR) setState(PetState.HAPPY, 1200)
        say(Lines.pick(Lines.click))
    }

    private fun toggleGravity() {
        gravityOn = !gravityOn
        PetPrefs.setGravity(ctx, gravityOn)
        say(if (gravityOn) "重力开啦，扔我试试～" else "自由摆放：放哪我就待哪。", 2500)
    }

    private fun showStatus() {
        val lv = PetPrefs.level(ctx)
        val xp = PetPrefs.xp(ctx) % 60
        say(
            "Lv.$lv（经验 $xp/60）  金币 ${PetPrefs.coins(ctx)}\n" +
                "饱食 ${PetPrefs.hunger(ctx)}  心情 ${PetPrefs.mood(ctx)}  精力 ${PetPrefs.energy(ctx)}",
            6500,
        )
    }

    // ---------------------------------------------------------------- 触摸：单击 / 双击 / 长按 / 拖拽 / 抛出

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var dragVx = 0f
    private var dragVy = 0f
    private var lastMoveT = 0L

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { dropFood("rice"); return true }
        override fun onLongPress(e: MotionEvent) { if (!dragging) showMenu() }
    })

    private val touchListener = View.OnTouchListener { _, e ->
        detector.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = posX; startY = posY
                dragging = false
                lastAct = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > slop * 2) {
                    dragging = true
                    hideMenu()
                    setState(PetState.DRAG)
                    say(Lines.pick(Lines.drag), 1800)
                    dragVx = 0f; dragVy = 0f
                    lastMoveT = e.eventTime
                }
                if (dragging) {
                    val nx = (startX + dx).coerceIn(0f, (screenW() - petW).toFloat())
                    val ny = (startY + dy).coerceIn(0f, groundY().toFloat())
                    val dtMs = e.eventTime - lastMoveT
                    if (dtMs > 0) {
                        dragVx = dragVx * 0.5f + (nx - posX) / dtMs * 1000f * 0.5f
                        dragVy = dragVy * 0.5f + (ny - posY) / dtMs * 1000f * 0.5f
                        lastMoveT = e.eventTime
                    }
                    movePet(nx, ny)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                releasePet(e.eventTime)
            }
        }
        true
    }

    /** 松手：重力模式下按手指甩出的速度飞出去、弹几下再落地；关掉重力就停在手放的位置。 */
    private fun releasePet(upT: Long) {
        PetPrefs.setLastX(ctx, posX.toInt())
        PetPrefs.setLastY(ctx, posY.toInt())
        if (gravityOn) {
            val stale = upT - lastMoveT > 90
            val cap = px(2600).toFloat()
            vx = if (stale) 0f else dragVx.coerceIn(-cap, cap)
            vy = if (stale) 0f else dragVy.coerceIn(-cap, cap)
            startAir("drag")
        } else {
            snapToEdge()
        }
    }

    /** 自由摆放：落在哪就停在哪，只有非常靠近边缘（28dp 内）时才轻轻靠一下。 */
    private fun snapToEdge() {
        val edge = px(28)
        val targetX = when {
            posX < edge -> 0f
            posX > screenW() - petW - edge -> (screenW() - petW).toFloat()
            else -> posX
        }
        PetPrefs.setLastX(ctx, targetX.toInt())
        PetPrefs.setLastY(ctx, posY.toInt())
        if (targetX != posX) {
            view.facingRight = targetX == 0f
            ValueAnimator.ofFloat(posX, targetX).apply {
                duration = 140
                addUpdateListener { movePet(it.animatedValue as Float, posY) }
                start()
            }
        }
        say(Lines.pick(Lines.drop))
        bounce()
        setState(PetState.HAPPY, 1000)
    }

    /** 落地时的挤压—回弹，让它有重量感。 */
    private fun bounce() {
        ValueAnimator.ofFloat(0.84f, 1.10f, 0.96f, 1f).apply {
            duration = 380
            addUpdateListener {
                view.squash = it.animatedValue as Float
                view.invalidate()
            }
            start()
        }
    }


    // ---------------------------------------------------------------- 对话气泡（完全不挡触摸）

    private var bubble: TextView? = null
    private var bubbleShown = false
    private val bubbleParams = overlayParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        focusable = false, touchable = false,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private val hideBubbleRun = Runnable { hideBubble() }

    fun say(text: String, ms: Long = 3500) {
        val b = bubble ?: TextView(ctx).also {
            it.textSize = 14f
            it.setTextColor(0xFF16324F.toInt())
            it.maxWidth = px(220)
            it.setPadding(px(12), px(8), px(12), px(8))
            it.background = rounded(0xFFFFFFFF.toInt(), px(14), 0x33000000)
            bubble = it
        }
        b.text = text
        b.measure(0, 0)
        placeBubble()
        if (!bubbleShown) {
            try { wm.addView(b, bubbleParams); bubbleShown = true } catch (_: Exception) {}
        }
        h.removeCallbacks(hideBubbleRun)
        h.postDelayed(hideBubbleRun, ms)
    }

    private fun placeBubble() {
        val b = bubble ?: return
        val bw = b.measuredWidth
        val bh = b.measuredHeight
        bubbleParams.x = (params.x + petW / 2 - bw / 2).coerceIn(0, (screenW() - bw).coerceAtLeast(0))
        val above = params.y - bh - px(4)
        bubbleParams.y = if (above >= 0) above else params.y + petSize
        if (bubbleShown) safeUpdate(b, bubbleParams)
    }

    private fun hideBubble() {
        h.removeCallbacks(hideBubbleRun)
        val b = bubble
        if (bubbleShown && b != null) { try { wm.removeView(b) } catch (_: Exception) {}; bubbleShown = false }
    }

    // ---------------------------------------------------------------- 长按菜单（分页）

    private var menu: OverlayPanel? = null

    private fun showMenu(page: String = "main") {
        hideMenu(); hideBubble()
        val box = OverlayPanel(ctx, { hideMenu() })
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(0xFFFFFFFF.toInt(), px(14))
        box.setPadding(px(6), px(6), px(6), px(6))

        fun entry(label: String, a: () -> Unit): Pair<String, () -> Unit> = label to a

        val entries: List<Pair<String, () -> Unit>> = when (page) {
            "feed" -> listOf(
                entry("🍚 白米饭（免费）") { dropFood("rice") },
                entry("🐟 小鱼干（5 币）") { dropFood("fish") },
                entry("🍰 小蛋糕（12 币）") { dropFood("cake") },
                entry("⬅ 返回") { showMenu("main") },
            )
            "play" -> listOf(
                entry(if (hasItem("ball")) "🏀 收起球" else "🏀 扔个球") { toggleBall() },
                entry("🤸 跳一下") { jump() },
                entry("🌀 转圈圈") { spin() },
                entry("😴 睡觉") { goSleep() },
                entry("⬅ 返回") { showMenu("main") },
            )
            "more" -> listOf(
                entry(if (outfits.size > 1) "👗 换个装扮" else "👗 女仆装") {
                    if (outfits.size > 1) cycleOutfit() else say("现在只有女仆装一套哦", 2000)
                },
                entry("🔍 放大 10%") { changeSize(10) },
                entry("🔎 缩小 10%") { changeSize(-10) },
                entry(if (gravityOn) "⬇ 重力：开" else "⬇ 重力：关（自由摆放）") { toggleGravity() },
                entry("⏰ 10 分钟后提醒我") { remindIn(10 * 60_000L) },
                entry("💰 查看余额") { showBalance() },
                entry("👋 退出桌宠") { onQuit() },
                entry("⬅ 返回") { showMenu("main") },
            )
            else -> listOf(
                entry("💬 聊天") { openChat() },
                entry("🍽 喂食 ▸") { showMenu("feed") },
                entry("🎮 玩耍 ▸") { showMenu("play") },
                entry("📊 状态") { showStatus() },
                entry("⚙ 更多 ▸") { showMenu("more") },
            )
        }
        for ((label, action) in entries) {
            val t = TextView(ctx)
            t.text = label
            t.textSize = 15f
            t.setTextColor(0xFF16324F.toInt())
            t.setPadding(px(16), px(10), px(16), px(10))
            t.setOnClickListener { hideMenu(); action() }
            box.addView(t)
        }

        box.measure(0, 0)
        val mw = box.measuredWidth
        val mh = box.measuredHeight
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable = false)
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        p.gravity = Gravity.TOP or Gravity.START
        p.x = if (params.x + petW + mw < screenW()) params.x + petW else (params.x - mw).coerceAtLeast(0)
        p.y = params.y.coerceAtMost((screenH() - mh).coerceAtLeast(0))
        try { wm.addView(box, p); menu = box } catch (_: Exception) {}
    }

    private fun hideMenu() {
        menu?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        menu = null
    }

    // ---------------------------------------------------------------- 装扮

    /** 装扮顺序；新增素材时在这里登记即可 */
    // 只保留女仆装这一套
    private val outfits = listOf("maid" to "女仆")

    private fun cycleOutfit() {
        val cur = PetPrefs.outfit(ctx)
        val idx = outfits.indexOfFirst { it.first == cur }
        val safeIdx = if (idx < 0) 0 else idx
        val next = outfits[(safeIdx + 1) % outfits.size]
        PetPrefs.setOutfit(ctx, next.first)
        view.outfit = next.first
        view.invalidate()
        syncWindowSize()
        say("换好啦：${next.second}", 3000)
    }

    // ---------------------------------------------------------------- 大小

    /** 按百分比调整桌宠大小，并即时应用到悬浮窗。 */
    private fun changeSize(delta: Int) {
        val pct = (PetPrefs.sizePercent(ctx) + delta).coerceIn(60, 220)
        PetPrefs.setSizePercent(ctx, pct)
        petSize = px(150 * pct / 100)
        syncWindowSize()
        say("大小 $pct%", 1600)
    }

    // ---------------------------------------------------------------- 提醒

    private fun remindIn(ms: Long) {
        say("好，${ms / 60000} 分钟后叫你～", 2500)
        h.postDelayed({
            setState(PetState.ALERT, 4000)
            say("到点啦！说好的提醒～", 6000)
        }, ms)
    }

    // ---------------------------------------------------------------- 余额

    fun showBalance() {
        say("我去看看……", 1500)
        scope.launch {
            val b = DeepSeekApi.balance(SecureStore.apiKeyAsync(ctx))
            say(DeepSeekApi.describe(b), 5000)
        }
    }

    fun onLowBalance(b: DeepSeekApi.Balance) {
        setState(PetState.ALERT, 2500)
        say("${DeepSeekApi.describe(b)}，该充值啦！", 6000)
    }

    // ---------------------------------------------------------------- 聊天（流式）

    private var chatPanel: OverlayPanel? = null
    private var chatText: TextView? = null
    private var chatScroll: ScrollView? = null
    private var chatJob: Job? = null
    private val chatLog = ArrayList<String>()
    private val history = ArrayList<Pair<String, String>>()

    private fun persona(): String {
        val mem = PetPrefs.memories(ctx)
        val memText = if (mem.isEmpty()) "（暂时还没记住关于用户的长期信息）" else mem.joinToString("；")
        return PetPrefs.persona(ctx) +
            "\n你已经记住的关于用户的事：$memText" +
            "\n当前饥饿度${PetPrefs.hunger(ctx)}/100，心情${PetPrefs.mood(ctx)}/100，饿了可以撒娇要饭吃。"
    }

    private fun errText(code: String): String = when (code) {
        "NO_KEY" -> "还没设置 API Key，先去主界面填一下"
        "HTTP_401" -> "API Key 不对"
        "HTTP_402" -> "余额不足"
        "NETWORK" -> "网络不通"
        else -> "出错了：$code"
    }

    private fun renderChat() {
        chatText?.text = chatLog.joinToString("\n\n")
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openChat() {
        if (chatPanel != null) return
        hideBubble()
        val panel = OverlayPanel(ctx, onOutside = {}, onBack = { closeChat() })
        panel.orientation = LinearLayout.VERTICAL
        panel.background = rounded(0xF5FFFFFF.toInt(), px(16))
        panel.setPadding(px(12), px(12), px(12), px(12))

        val tv = TextView(ctx).apply {
            textSize = 15f
            setTextColor(0xFF16324F.toInt())
            text = if (chatLog.isEmpty()) "对大肥鱼说点什么吧～" else chatLog.joinToString("\n\n")
        }
        val sv = ScrollView(ctx).apply { addView(tv) }
        panel.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(200)))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(ctx).apply {
            hint = "说点什么…"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            setTextColor(0xFF16324F.toInt())
            setHintTextColor(0xFF8899AA.toInt())
        }
        val send = TextView(ctx).apply {
            text = "发送"; textSize = 15f; setTextColor(0xFF3B82F6.toInt()); setPadding(px(12), px(8), px(12), px(8))
        }
        val close = TextView(ctx).apply {
            text = "关闭"; textSize = 15f; setTextColor(0xFF64748B.toInt()); setPadding(px(8), px(8), px(4), px(8))
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send); row.addView(close)
        panel.addView(row)

        // 快捷回复（参考 Coopanion：它主动给几个选项，点一下就能回）
        val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (label in listOf("摸摸头", "饿了吗", "今天好累", "讲个笑话")) {
            val chip = TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(0xFF3B82F6.toInt())
                setPadding(px(10), px(6), px(10), px(6))
                background = rounded(0xFFEFF6FF.toInt(), px(12))
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = px(6)
            chip.setOnClickListener { sendText(label) }
            chips.addView(chip, lp)
        }
        val chipScroll = android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        panel.addView(chipScroll)

        send.setOnClickListener { sendChat(input) }
        close.setOnClickListener { closeChat() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { sendChat(input); true } else false
        }

        // 聊天窗口要能弹出输入法：去掉 NOT_FOCUSABLE 和 LAYOUT_IN_SCREEN，让它跟着输入法上移
        val p = WindowManager.LayoutParams(
            screenW() - px(24), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = px(24)
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        try {
            wm.addView(panel, p)
            chatPanel = panel; chatText = tv; chatScroll = sv
        } catch (_: Exception) {}
    }

    private fun closeChat() {
        chatJob?.cancel()
        chatPanel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        chatPanel = null; chatText = null; chatScroll = null
        if (state == PetState.EAT) setState(PetState.IDLE)
    }

    private var turnsSinceExtract = 0

    /** 每隔几轮，让模型从最近的对话里提炼“关于用户的长期事实”，存进记忆。 */
    private fun maybeExtractMemories() {
        turnsSinceExtract++
        if (turnsSinceExtract < 6) return
        turnsSinceExtract = 0
        val snapshot = history.takeLast(12)
        if (snapshot.size < 4) return
        scope.launch {
            val key = SecureStore.apiKeyAsync(ctx)
            val msgs = listOf(
                "system" to ("你是记忆提取器。从对话里提取关于用户的、长期有效的事实（称呼、喜好、习惯、重要计划）。" +
                    "每行一条，最多 3 条，不要解释，不要编号，没有就输出空。"),
                "user" to snapshot.joinToString("\n") { (r, c) -> (if (r == "user") "用户：" else "宠物：") + c },
            )
            val (text, err) = DeepSeekApi.chatOnce(key, msgs, 200)
            if (err == null && !text.isNullOrBlank()) {
                text.lines()
                    .map { it.trim().trim('-', '·', '*', ' ') }
                    .filter { it.length in 4..60 && !it.startsWith("没有") }
                    .forEach { PetPrefs.addMemory(ctx, it) }
            }
        }
    }

    private fun sendChat(input: EditText) {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        sendText(t)
    }

    private fun sendText(text: String) {
        if (text.isEmpty() || chatJob?.isActive == true) return
        lastAct = SystemClock.elapsedRealtime()

        // 说“记住……”就直接存成长期记忆，不必消耗模型调用
        val rememberPrefix = when {
            text.startsWith("记住") -> "记住"
            text.startsWith("记一下") -> "记一下"
            else -> null
        }
        if (rememberPrefix != null) {
            val fact = text.removePrefix(rememberPrefix).trim().trim('，', ',', '。', '：', ':')
            if (fact.isNotEmpty()) {
                val isNew = PetPrefs.addMemory(ctx, fact)
                chatLog.add("你：$text")
                chatLog.add("鱼：" + if (isNew) "记住啦：$fact" else "这个我早就记住咯～")
                renderChat()
                say(if (isNew) "记住啦～" else "早就记住啦～", 2500)
                return
            }
        }
        history.add("user" to text)
        chatLog.add("你：$text")
        chatLog.add("鱼：")
        renderChat()
        setState(PetState.EAT) // 嘴巴一张一合，当作在说话
        chatJob = scope.launch {
            val key = SecureStore.apiKeyAsync(ctx)
            val msgs = ArrayList<Pair<String, String>>()
            msgs.add("system" to persona())
            msgs.addAll(history.takeLast(20))
            val reply = StringBuilder()
            val err = DeepSeekApi.chatStream(key, msgs) { delta ->
                withContext(Dispatchers.Main) {
                    reply.append(delta)
                    chatLog[chatLog.size - 1] = "鱼：$reply"
                    renderChat()
                }
            }
            if (err != null) {
                chatLog[chatLog.size - 1] = "鱼：（${errText(err)}）"
                history.removeAt(history.size - 1)
            } else {
                history.add("assistant" to reply.toString())
                gainXp(2)
                maybeExtractMemories()
            }
            renderChat()
            if (state == PetState.EAT) setState(PetState.IDLE)
        }
    }
}
