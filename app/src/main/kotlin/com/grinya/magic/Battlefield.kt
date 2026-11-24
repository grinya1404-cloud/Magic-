package com.grinya.magic

import android.view.View
import android.widget.FrameLayout

// 0..4 — слоты игрока, 5..9 — противника; слот i стоит напротив слота i+5
class Battlefield(val slots: List<FrameLayout>) {

    private val occupied = BooleanArray(slots.size)

    fun isFree(index: Int): Boolean = !occupied[index]

    fun cardViewAt(index: Int): View? {
        val slot = slots[index]
        for (i in 0 until slot.childCount) {
            val child = slot.getChildAt(i)
            if (child.tag is CardModel) return child // фон слота не имеет тега
        }
        return null
    }

    fun cardAt(index: Int): CardModel? = cardViewAt(index)?.tag as? CardModel

    fun place(cardView: View, index: Int) {
        slots[index].addView(cardView)
        occupied[index] = true
    }

    // Снимает тег — логика сразу видит слот пустым, — затем убирает вью
    fun killCard(index: Int, onRemove: (View) -> Unit) {
        cardViewAt(index)?.let { v ->
            v.tag = null
            occupied[index] = false
            onRemove(v)
        }
    }
}
