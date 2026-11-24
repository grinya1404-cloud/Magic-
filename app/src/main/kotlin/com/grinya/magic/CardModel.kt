package com.grinya.magic

class CardModel(
    val id: String = "",
    val name: String = "",
    val element: String = "Fire",
    val level: Int = 1,
    val type: String = "creature",
    val attack: Int = 0,
    var health: Int = 0,
    val imageRes: Int = 0,
    val ability: String = "",
    val effect: String = ""
) {
    val cost: Int get() = level

    fun clone(): CardModel =
        CardModel(id, name, element, level, type, attack, health, imageRes, ability, effect)
}
