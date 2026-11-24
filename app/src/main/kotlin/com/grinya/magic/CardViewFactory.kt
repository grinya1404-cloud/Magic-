package com.grinya.magic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class CardViewFactory(private val context: Context) {

    fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    // Цвет кружка по стихии
    private fun elementColor(element: String): Int = when (element) {
        "Fire" -> 0xFFCC3333.toInt()
        "Water" -> 0xFF3366CC.toInt()
        "Air" -> 0xFFE6C84A.toInt()
        "Earth" -> 0xFF4CAF50.toInt()
        else -> 0xFF8E44AD.toInt() // Death
    }

    fun createHandCard(card: CardModel): FrameLayout {
        val container = FrameLayout(context)
        container.layoutParams = LinearLayout.LayoutParams(dp(110), dp(150)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
        container.tag = card
        fillContent(container, card)
        return container
    }

    fun createSlotCard(card: CardModel): FrameLayout {
        val container = FrameLayout(context)
        container.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.tag = card
        fillContent(container, card)
        return container
    }

    private fun fillContent(container: FrameLayout, card: CardModel) {
        container.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(if (card.imageRes != 0) card.imageRes else R.drawable.card_bg)
        })

        container.addView(TextView(context).apply { // цена
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
            setPadding(8, 4, 8, 4)
            text = card.cost.toString()
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000080.toInt())
            }
        })

        // Шапка сверху: имя, под ним кружок стихии + статы
        val header = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            )
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xAA000000.toInt())
        }

        header.addView(TextView(context).apply { // имя — теперь наверху
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(4, 3, 4, 1)
            text = card.name
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
        })

        val statRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 1, 0, 3)
        }

        statRow.addView(View(context).apply { // кружок цвета стихии
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(dp(2), 0, dp(4), 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(elementColor(card.element))
            }
        })

        statRow.addView(TextView(context).apply { // статы; tag для поиска
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            text = if (card.type == "spell") "" else "${card.attack}/${card.health}"
            textSize = 10f
            setTextColor(0xFFFFFF00.toInt())
            tag = card
        })

        header.addView(statRow)
        container.addView(header)
    }

    fun updateStatsText(cardView: View?, card: CardModel) {
        val container = cardView as? ViewGroup ?: return
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView && child.tag == card) {
                child.text = "${card.attack}/${card.health}"
            }
        }
    }
}
