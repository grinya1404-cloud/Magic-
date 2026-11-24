package com.grinya.magic

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class ElementPanel(
    panel: LinearLayout,
    private val elements: List<String>,
    initialPoints: Int,
    private val onElementSelected: (String) -> Unit
) {
    private val points = HashMap<String, Int>()
    private val valueViews = ArrayList<TextView>(elements.size)
    private val cells = ArrayList<LinearLayout>(elements.size)

    init {
        for (e in elements) points[e] = initialPoints
        build(panel)
    }

    fun raw(): MutableMap<String, Int> = points

    fun get(element: String): Int = points[element] ?: 0

    fun spend(element: String, amount: Int) {
        points[element] = get(element) - amount
        refresh()
    }

    fun refresh() {
        for (i in elements.indices) {
            valueViews[i].text = (points[elements[i]] ?: 0).toString()
        }
    }

    fun highlightSelected(element: String) {
        for (i in elements.indices) {
            cells[i].setBackgroundColor(
                if (elements[i] == element) 0x33FFFFFF.toInt() else 0
            )
        }
    }

    private fun build(panel: LinearLayout) {
        for (i in panel.childCount - 1 downTo 0) panel.removeViewAt(i)
        for (element in elements) {
            val cell = LinearLayout(panel.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(6, 6, 6, 6)
                isClickable = true

                addView(TextView(panel.context).apply {
                        text = element
                        textSize = 12f
                        setTextColor(0xFFFFFFFF.toInt())
                })
                addView(TextView(panel.context).apply {
                        text = "0"
                        textSize = 14f
                        setTextColor(0xFF00FF00.toInt())
                        valueViews.add(this)
                })

                setOnClickListener { onElementSelected(element) }
            }
            panel.addView(cell)
            cells.add(cell)
        }
        refresh()
    }
    // После каждого хода сила в каждой стихии растёт на 1
    fun regenAll() {
        for (e in elements) points[e] = (points[e] ?: 0) + 1
        refresh()
    }

}
