package com.grinya.magic

import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private val handCards = ArrayList<CardModel>()
    private val fieldSlots = Array<FrameLayout?>(10) { null }
    private val slotOccupied = BooleanArray(10)

    private lateinit var dragOverlay: FrameLayout

    private var draggedCard: View? = null
    private var draggedParent: ViewGroup? = null
    private var draggedIndex = -1
    private var draggedSlot = -1

    private var startRawX = 0f
    private var startRawY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f

    private companion object {
        const val DRAG_THRESHOLD = 20f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(R.layout.activity_main)

        dragOverlay = findViewById(R.id.dragOverlay)

        // ---------- Слоты игрового поля (10 шт: 5 слева + 5 справа) ----------
        val slotIds = intArrayOf(
            R.id.slot1, R.id.slot3, R.id.slot4, R.id.slot5, R.id.slot10,
            R.id.slot2, R.id.slot6, R.id.slot7, R.id.slot8, R.id.slot9
        )
        for (i in slotIds.indices) {
            fieldSlots[i] = findViewById(slotIds[i])
            slotOccupied[i] = false
        }

        // ---------- Загружаем карты из cards.xml и кладём в руку ----------
        handCards.addAll(loadCardsFromJson())
        buildHandUI()
    }

    // ---------- Построение UI карт в руке из данных ----------
    private fun buildHandUI() {
        val handPanel = findViewById<LinearLayout>(R.id.handPanel)
        handPanel.removeAllViews()

        for (i in handCards.indices) {
            val card = handCards[i]

            val cardContainer = FrameLayout(this)
            val lp = FrameLayout.LayoutParams(dpToPx(110), dpToPx(150))
            lp.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            cardContainer.layoutParams = lp

            val cardImage = ImageView(this)
            cardImage.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            cardImage.scaleType = ImageView.ScaleType.CENTER_CROP
            val resId = card.imageRes
            cardImage.setImageResource(if (resId != 0) resId else R.drawable.card_bg)
            cardContainer.addView(cardImage)

            val nameTv = TextView(this)
            nameTv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            nameTv.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            nameTv.setPadding(4, 4, 4, 4)
            nameTv.text = card.name
            nameTv.textSize = 10f
            nameTv.setTextColor(0xFFFFFFFF.toInt())
            nameTv.setBackgroundColor(0xAA000000.toInt())
            cardContainer.addView(nameTv)

            val statsTv = TextView(this)
            statsTv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            statsTv.gravity = Gravity.BOTTOM or Gravity.END
            statsTv.setPadding(4, 4, 4, 4)
            statsTv.text = "${card.attack}/${card.health}"
            statsTv.textSize = 10f
            statsTv.setTextColor(0xFFFFFF00.toInt())
            statsTv.setBackgroundColor(0xAA000000.toInt())
            cardContainer.addView(statsTv)

            cardContainer.setOnTouchListener(CardTouchListener())
            handPanel.addView(cardContainer)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val scale = resources.displayMetrics.density
        return (dp * scale + 0.5f).toInt()
    }

    // ---------- Загрузка карт из cards.xml (raw) ----------
    private fun loadCardsFromJson(): List<CardModel> {
        val cards = ArrayList<CardModel>()
        try {
            val stream: InputStream = resources.openRawResource(R.raw.cards)
            val size = stream.available()
            val buffer = ByteArray(size)
            stream.read(buffer)
            stream.close()
            val text = String(buffer, charset("UTF-8"))

            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val card = CardModel()
                card.id = obj.getString("id")
                card.name = obj.getString("name")
                card.attack = obj.getInt("attack")
                card.health = obj.getInt("health")
                val imgName = obj.getString("imageRes")
                val resId = resources.getIdentifier(imgName, "drawable", packageName)
                card.imageRes = resId
                cards.add(card)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cards
    }

    inner class CardTouchListener : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggedParent = v.parent as? ViewGroup
                    draggedIndex = -1

                    for (i in fieldSlots.indices) {
                        if (fieldSlots[i] == draggedParent) {
                            cleanupDrag()
                            return true
                        }
                    }

                    draggedCard = v
                    startRawX = event.rawX
                    startRawY = event.rawY

                    val cardLoc = IntArray(2)
                    v.getLocationOnScreen(cardLoc)

                    val parent = draggedParent
                    parent?.removeView(v)
                    dragOverlay.addView(v)

                    val overlayLoc = IntArray(2)
                    dragOverlay.getLocationOnScreen(overlayLoc)

                    // Ставим карту на то же место на экране, но в координатах overlay
                    v.x = (cardLoc[0] - overlayLoc[0]).toFloat()
                    v.y = (cardLoc[1] - overlayLoc[1]).toFloat()
                    v.translationZ = 20f

                    // Смещение пальца — тоже в экранных координатах!
                    touchOffsetX = event.rawX - cardLoc[0]
                    touchOffsetY = event.rawY - cardLoc[1]
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (draggedCard == v) {
                        if (Math.abs(event.rawX - startRawX) < DRAG_THRESHOLD &&
                            Math.abs(event.rawY - startRawY) < DRAG_THRESHOLD) {
                            return true
                        }
                        // переводим экранные координаты пальца в координаты overlay
                        val overlayLoc = IntArray(2)
                        dragOverlay.getLocationOnScreen(overlayLoc)
                        v.x = event.rawX - overlayLoc[0] - touchOffsetX
                        v.y = event.rawY - overlayLoc[1] - touchOffsetY
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val card = draggedCard
                    if (card != null) {
                        card.translationZ = 0f

                        val targetSlot = findSlotUnderFinger(event.rawX, event.rawY)
                        val dx = Math.abs(event.rawX - startRawX)
                        val dy = Math.abs(event.rawY - startRawY)
                        val wasDragged = dx >= DRAG_THRESHOLD || dy >= DRAG_THRESHOLD

                        if (wasDragged && targetSlot >= 0 && !slotOccupied[targetSlot]) {
                            placeCardInSlot(targetSlot)
                        } else {
                            returnCardToOriginalPlace(card)
                        }
                    } else {
                        cleanupDrag()
                    }

                    draggedCard = null
                    draggedParent = null
                    draggedIndex = -1
                    draggedSlot = -1
                    return true
                }
            }
            return false
        }
    }

    private fun returnCardToOriginalPlace(card: View?) {
        if (card == null) {
            cleanupDrag()
            return
        }
        card.visibility = View.VISIBLE
        card.translationZ = 0f

        // Убираем из текущего родителя (overlay)
        val currentParent = card.parent as? ViewGroup
        currentParent?.removeView(card)

        var placed = false

        // Пытаемся вернуть в слот поля (если был из слота)
        if (draggedSlot in 0 until 10 && fieldSlots[draggedSlot] != null) {
            slotOccupied[draggedSlot] = true
            val slot = fieldSlots[draggedSlot]!!
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            card.translationX = 0f
            card.translationY = 0f
            card.translationZ = 0f
            card.layoutParams = params
            card.visibility = View.VISIBLE
            slot.addView(card) // или handPanel.addView(card)
            placed = true
        }

        // Иначе возвращаем в руку на старую позицию
        if (!placed && draggedIndex >= 0) {
            val parent = draggedParent
            if (parent is LinearLayout) {
                val params = FrameLayout.LayoutParams(dpToPx(110), dpToPx(150))
                params.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                card.layoutParams = params
                parent.addView(card, draggedIndex)
                placed = true
            }
        }

        // Иначе в конец руки
        if (!placed) {
            val handPanel = findViewById<LinearLayout>(R.id.handPanel)
            if (handPanel != null) {
                val params = FrameLayout.LayoutParams(dpToPx(110), dpToPx(150))
                params.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                card.layoutParams = params
                handPanel.addView(card)
            }
        }

        cleanupDrag()
    }

    private fun placeCardInSlot(slotIndex: Int) {
        val card = draggedCard ?: return
        if (slotIndex < 0 || slotIndex >= 10) return

        dragOverlay.removeView(card)
        val currentParent = card.parent as? ViewGroup
        currentParent?.removeView(card)

        slotOccupied[slotIndex] = true

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        card.translationX = 0f
        card.translationY = 0f
        card.translationZ = 0f
        card.layoutParams = params
        card.visibility = View.VISIBLE

        fieldSlots[slotIndex]!!.addView(card)

        cleanupDrag()
    }

    private fun findSlotUnderFinger(rawX: Float, rawY: Float): Int {
        val loc = IntArray(2)
        // Только слоты игрока 1 (слева): индексы 0-4
        for (i in 0..4) {
            if (slotOccupied[i]) continue
            fieldSlots[i]?.getLocationOnScreen(loc)
            val left = loc[0].toFloat()
            val top = loc[1].toFloat()
            val right = left + fieldSlots[i]!!.width
            val bottom = top + fieldSlots[i]!!.height
            if (rawX >= left && rawX <= right && rawY >= top && rawY <= bottom) {
                return i
            }
        }
        return -1
    }

    private fun cleanupDrag() {
        draggedCard = null
        draggedParent = null
        draggedIndex = -1
        draggedSlot = -1
    }
}
