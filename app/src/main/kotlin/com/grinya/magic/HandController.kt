package com.grinya.magic

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import kotlin.math.abs

class HandController(
    private val scroll: HorizontalScrollView?,
    private val handPanel: LinearLayout,
    private val overlay: FrameLayout,
    private val field: Battlefield,
    private val factory: CardViewFactory,
    private val canDrag: () -> Boolean,
    private val onDropRequest: (CardModel, View, Int) -> Boolean, // true = карта принята
    private val onCardInfo: (CardModel) -> Unit = {}
) {
    private val hand = ArrayList<CardModel>()
    private var selectedElement: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private val DRAG_THRESHOLD = 20f
    private val LONG_TAP_MS = 500L

    fun setCards(cards: List<CardModel>) {
        hand.clear()
        hand.addAll(cards)
        rebuild()
    }

    fun removeFromHand(card: CardModel) {
        hand.remove(card)
        rebuild()
    }

    fun selectElement(element: String) {
        selectedElement = element
        applyFilter()
        scrollTo(element)
    }

    fun applyFilter() {
        for (i in 0 until handPanel.childCount) {
            val v = handPanel.getChildAt(i)
            val card = v.tag as? CardModel ?: continue
            v.alpha = if (selectedElement == null || card.element == selectedElement) 1f else 0.5f
        }
    }

    fun scrollTo(element: String) {
        val s = scroll ?: return
        handPanel.post {
            var x = 0
            for (i in 0 until handPanel.childCount) {
                val child = handPanel.getChildAt(i)
                val card = child.tag as? CardModel
                if (card?.element == element) break
                val lp = child.layoutParams as? LinearLayout.LayoutParams
                x += child.width + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
            }
            val max = (handPanel.width - s.width).coerceAtLeast(0)
            s.smoothScrollTo(x.coerceAtMost(max), 0)
        }
    }

    private fun rebuild() {
        handPanel.removeAllViews()
        for (card in hand) {
            val v = factory.createHandCard(card)
            v.setOnTouchListener(Touch())
            handPanel.addView(v)
        }
        applyFilter()
    }

    private fun findSlotUnderFinger(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        for (i in 0..4) { // только свои слоты
            val slot = field.slots[i]
            if (!field.isFree(i)) continue
            slot.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + slot.width &&
                rawY >= loc[1] && rawY <= loc[1] + slot.height
            ) return i
        }
        return -1
    }
    
        @SuppressLint("ClickableViewAccessibility")
    inner class Touch : View.OnTouchListener {

        private var activeCard: CardModel? = null
        private var activeView: View? = null
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragStarted = false
        private var ghost: View? = null
        private var infoRunnable: Runnable? = null

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeView = v
                    activeCard = v.tag as? CardModel
                    // Перехват НЕ запрещаем: решим по направлению жеста
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = downRawX
                    lastRawY = downRawY

                    // Длинный тап — описание карты
                    val c = v.tag as? CardModel
                    if (c != null) {
                        infoRunnable = Runnable { if (!dragStarted) onCardInfo(c) }
                        handler.postDelayed(infoRunnable!!, LONG_TAP_MS)
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    infoRunnable?.let { handler.removeCallbacks(it) }

                    val card = activeCard
                    if (!dragStarted) {
                        val dx = abs(event.rawX - downRawX)
                        val dy = abs(event.rawY - downRawY)
                        if (card != null && (dx >= DRAG_THRESHOLD || dy >= DRAG_THRESHOLD)) {
                            if (dy >= dx) {
                                // Вертикальный жест — вытягиваем карту
                                (v.parent as? ViewGroup)
                                    ?.requestDisallowInterceptTouchEvent(true)
                                startDrag()
                            } else {
                                // Горизонтальный жест — это прокрутка руки.
                                // Отдаём касание ScrollView
                                return false
                            }
                        }
                    }

                    if (dragStarted) {
                        // Дельта-следование: призрак повторяет движение пальца
                        val g = ghost ?: return true
                        g.x += event.rawX - lastRawX
                        g.y += event.rawY - lastRawY
                    }
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return dragStarted // до старта драга прокрутке ничего не мешает
                }

                MotionEvent.ACTION_UP -> {
                    infoRunnable?.let { handler.removeCallbacks(it) }

                    val card = activeCard
                    val view = activeView
                    if (dragStarted && card != null && view != null) {
                        val slotIndex = slotIndexAt(event.rawX, event.rawY)
                        if (slotIndex >= 0 && onDropRequest(card, view, slotIndex)) {
                            finishDrag()
                        } else {
                            cancelDrag()
                        }
                    }
                    cleanup()
                    return dragStarted
                }

                MotionEvent.ACTION_CANCEL -> {
                    infoRunnable?.let { handler.removeCallbacks(it) }
                    cancelDrag()
                    cleanup()
                    return true
                }
            }
            return false
        }

        private fun startDrag() {
            val card = activeCard ?: return
            val view = activeView ?: return

            val oLoc = IntArray(2)
            overlay.getLocationOnScreen(oLoc)
            val vLoc = IntArray(2)
            view.getLocationOnScreen(vLoc)

            val g = factory.createHandCard(card)
            g.layoutParams = FrameLayout.LayoutParams(view.width, view.height)
            overlay.addView(g)
            g.x = (vLoc[0] - oLoc[0]).toFloat()
            g.y = (vLoc[1] - oLoc[1]).toFloat()

            view.alpha = 0f
            ghost = g
            dragStarted = true
        }

        private fun finishDrag() {
            ghost?.let { overlay.removeView(it) }
            ghost = null
        }

        private fun cancelDrag() {
            finishDrag()
            activeView?.alpha = 1f
        }

        private fun cleanup() {
            activeCard = null
            activeView = null
            dragStarted = false
            infoRunnable = null
        }

        // Слот под пальцем в экранных координатах, -1 если мимо
        private fun slotIndexAt(rawX: Float, rawY: Float): Int {
            for ((i, slot) in field.slots.withIndex()) {
                val loc = IntArray(2)
                slot.getLocationOnScreen(loc)
                if (rawX >= loc[0] && rawX <= loc[0] + slot.width &&
                    rawY >= loc[1] && rawY <= loc[1] + slot.height
                ) return i
            }
            return -1
        }
    }
    
    
}
