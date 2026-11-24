package com.grinya.magic

import android.content.res.Resources
import org.json.JSONArray

class CardRepository(
    private val resources: Resources,
    private val packageName: String
) {
    fun loadAll(): List<CardModel> {
        val result = ArrayList<CardModel>()
        try {
            val text = resources.openRawResource(R.raw.cards)
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    result.add(
                        CardModel(
                            id = o.getString("id"),
                            name = o.getString("name"),
                            element = o.optString("element", "Fire"),
                            level = o.optInt("level", 1),
                            type = o.optString("type", "creature"),
                            attack = o.optInt("attack", 0),
                            health = o.optInt("health", 0),
                            imageRes = resources.getIdentifier(
                                o.optString("imageRes", ""), "drawable", packageName
                            ),
                            ability = o.optString("ability", ""),
                            effect = o.optString("effect", "")
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace() // битая карта не роняет колоду
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        android.util.Log.d("Magic", "loadCards: parsed ${result.size} cards")
        return result
    }
}
