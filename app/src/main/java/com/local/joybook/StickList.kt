package com.local.joybook

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView driven by a joystick cursor instead of view focus: one click = one row,
 * minimal scrolling that always keeps a peek of the neighbouring row.
 */
class StickList(private val rv: RecyclerView) {
    private val lm = LinearLayoutManager(rv.context)
    var cursor = 0
        private set

    init {
        rv.layoutManager = lm
        rv.itemAnimator = null
        // RecyclerView makes itself focusable; keys are handled by the activity instead.
        rv.isFocusable = false
        rv.isFocusableInTouchMode = false
    }

    private val count: Int get() = rv.adapter?.itemCount ?: 0

    fun move(delta: Int, wrap: Boolean) {
        val n = count
        if (n == 0) return
        val cur = cursor.coerceIn(0, n - 1)
        setCursor(if (wrap) ((cur + delta) % n + n) % n else (cur + delta).coerceIn(0, n - 1))
    }

    fun pageSize(): Int = (lm.childCount - 1).coerceAtLeast(1)

    fun setCursor(pos: Int) {
        val old = cursor
        cursor = pos
        if (old != pos) {
            if (old in 0 until count) rv.adapter?.notifyItemChanged(old)
            rv.adapter?.notifyItemChanged(pos)
        }
        ensureVisible(pos)
    }

    /** Put the cursor on [pos] and bring that row to the upper third (list just opened). */
    fun reset(pos: Int) {
        cursor = pos
        @Suppress("NotifyDataSetChanged")
        rv.adapter?.notifyDataSetChanged()
        rv.post { lm.scrollToPositionWithOffset(pos, rv.height / 3) }
    }

    private fun ensureVisible(pos: Int) {
        rv.post(object : Runnable {
            var tries = 0
            override fun run() {
                if (rv.isLayoutRequested && tries++ < 4) {
                    rv.post(this)
                    return
                }
                val v = lm.findViewByPosition(pos)
                if (v == null) {
                    lm.scrollToPosition(pos)
                    return
                }
                val peek = (rv.resources.displayMetrics.density * 18).toInt()
                val top = rv.paddingTop + if (pos > 0) peek else 0
                val bottom = rv.height - rv.paddingBottom - if (pos < count - 1) peek else 0
                val vTop = lm.getDecoratedTop(v)
                val vBottom = lm.getDecoratedBottom(v)
                val dy = when {
                    vBottom > bottom -> minOf(vBottom - bottom, vTop - rv.paddingTop)
                    vTop < top -> vTop - top
                    else -> 0
                }
                if (dy != 0) rv.smoothScrollBy(0, dy, null, 90)
            }
        })
    }
}
