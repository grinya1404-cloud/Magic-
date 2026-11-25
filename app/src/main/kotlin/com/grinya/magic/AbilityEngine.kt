package com.grinya.magic

class AbilityEngine(
    private val field: Battlefield,
    private val powersOf: (Boolean) -> MutableMap<String, Int>, // true = игрок
    private val onLog: (String) -> Unit,
    private val refreshUi: () -> Unit,
    private val changeLife: (Boolean, Int) -> Unit,
    private val damageCreature: (Int, Int) -> Boolean,
    private val healCreature: (Int, Int) -> Unit,
    private val killCreature: (Int) -> Unit
) {
    private val ALL = listOf("Fire", "Water", "Air", "Earth", "Death")

    private var ownerCtx = true // чьи формулы считаем

    private fun tokens(effect: String): List<List<String>> =
        effect.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { it.split(":") }

    private fun values(effect: String, name: String): List<Int> =
        tokens(effect).filter { it[0] == name }.mapNotNull { it.getOrNull(1)?.toIntOrNull() }

    private fun ru(element: String) = when (element) {
        "Fire" -> "Огонь"; "Water" -> "Вода"; "Air" -> "Воздух"
        "Earth" -> "Земля"; else -> "Смерть"
    }

    private fun side(isPlayer: Boolean) = if (isPlayer) "игрока" else "ИИ"

    private fun ownSlots(isPlayer: Boolean) =
        (if (isPlayer) 0..4 else 5..9).filter { field.cardAt(it) != null }

    private fun enemySlots(isPlayer: Boolean) =
        (if (isPlayer) 5..9 else 0..4).filter { field.cardAt(it) != null }

    private fun powerOf(isPlayer: Boolean, elem: String) = powersOf(isPlayer)[elem] ?: 0

    private fun maxHpEnemySlot(isPlayer: Boolean): Int? =
        enemySlots(isPlayer).maxByOrNull { field.cardAt(it)?.health ?: 0 }

    // Централизованное изменение силы: лог со старым и новым значением
    private fun addPower(isPlayer: Boolean, element: String, delta: Int, src: String) {
        val m = powersOf(isPlayer)
        val before = m[element] ?: 0
        val after = (before + delta).coerceAtLeast(0)
        m[element] = after
        if (after != before) {
            onLog("«$src»: сила ${ru(element)} ${side(isPlayer)} $before → $after")
        }
    }

    // Ауры заклинаний: Фея (+1 плоско), Дракон (+50%)
    private fun boost(v: Int, isPlayer: Boolean): Int {
        val flat = ownSlots(isPlayer)
            .mapNotNull { field.cardAt(it) }
            .sumOf { values(it.effect, "aura_spell_dmg").sum() }
        val pct = ownSlots(isPlayer)
            .mapNotNull { field.cardAt(it) }
            .sumOf { values(it.effect, "aura_spell_pct").sum() }
        var r = v + flat
        if (pct > 0) r = r * (100 + pct) / 100
        if (r != v) onLog("Аура заклинаний: $v → $r")
        return r
    }

    // dyn: [токен, elem, b] → сила+b; [токен, elem, a, b] → сила/a+b
    private fun dyn(p: List<String>): Int {
        val pw = powerOf(ownerCtx, p[1])
        val v = if (p.size >= 4) pw / (p[2].toIntOrNull() ?: 1) + (p[3].toIntOrNull() ?: 0)
        else pw + (p[2].toIntOrNull() ?: 0)
        return v.coerceAtLeast(0)
    }

    // [токен, elem, b] → сила/2 + b
    private fun halfPlus(p: List<String>): Int =
        (powerOf(ownerCtx, p[1]) / 2) + (p.getOrNull(2)?.toIntOrNull() ?: 0)

    private fun n(s: String) = s.toInt()

    // ===== При входе существа =====
    fun onEnter(slotIndex: Int, isPlayer: Boolean) {
        val card = field.cardAt(slotIndex) ?: return
        for (t in tokens(card.effect)) {
            when (t[0]) {
                "ent_dmg_enemy" -> {
                    changeLife(!isPlayer, -n(t[1]))
                    onLog("«${card.name}» входит: ${n(t[1])} повреждений магу ${side(!isPlayer)}")
                }
                "ent_dmg_enemy_creatures" -> {
                    enemySlots(isPlayer).forEach { damageCreature(it, n(t[1])) }
                    onLog("«${card.name}» входит: ${n(t[1])} повреждений всем существам противника")
                }
                "ent_dmg_enemy_both" -> {
                    changeLife(!isPlayer, -n(t[1]))
                    enemySlots(isPlayer).forEach { damageCreature(it, n(t[1])) }
                    onLog("«${card.name}» входит: ${n(t[1])} повреждений магу и существам противника")
                }
                "ent_dmg_owner" -> {
                    changeLife(isPlayer, -n(t[1]))
                    onLog("«${card.name}» входит: ${n(t[1])} повреждений магу ${side(isPlayer)}")
                }
                "ent_dmg_owner_if_lt" -> {
                    val pw = powerOf(isPlayer, t[1])
                    if (pw < n(t[2])) {
                        changeLife(isPlayer, -n(t[3]))
                        onLog("«${card.name}» входит: ${ru(t[1])} $pw < ${n(t[2])} — ${n(t[3])} повреждений магу ${side(isPlayer)}")
                    }
                }
                "ent_dmg_enemy_if_gt" -> {
                    val pw = powerOf(isPlayer, t[1])
                    if (pw > n(t[2])) {
                        changeLife(!isPlayer, -n(t[3]))
                        onLog("«${card.name}» входит: ${ru(t[1])} $pw > ${n(t[2])} — ${n(t[3])} повреждений магу ${side(!isPlayer)}")
                    }
                }
                "ent_dmg_maxhp_enemy" -> {
                    maxHpEnemySlot(isPlayer)?.let {
                        val targetName = field.cardAt(it)?.name ?: "?"
                        damageCreature(it, n(t[1]))
                        onLog("«${card.name}» входит: ${n(t[1])} повреждений «$targetName» (самое живучее)")
                    }
                }
                "ent_heal_owner" -> {
                    changeLife(isPlayer, n(t[1]))
                    onLog("«${card.name}» входит: +${n(t[1])} жизней магу ${side(isPlayer)}")
                }
                "ent_heal_own_creatures" -> {
                    ownSlots(isPlayer).forEach { healCreature(it, 999) }
                    onLog("«${card.name}» входит: полное исцеление существ ${side(isPlayer)}")
                }
                "ent_minus_all_enemy" ->
                    ALL.forEach { addPower(!isPlayer, it, -n(t[1]), card.name) }
                "ent_minus_all_owner" ->
                    ALL.forEach { addPower(isPlayer, it, -n(t[1]), card.name) }
                "ent_minus_power_enemy" -> addPower(!isPlayer, t[1], -n(t[2]), card.name)
                "ent_plus_power_owner" -> addPower(isPlayer, t[1], n(t[2]), card.name)
            }
        }
        refreshUi()
    }

    // ===== Конец хода владельца =====
    // justPlayed — карта, выложенная в этом ходу: её «каждый ход»-способности
    // в этот ход не срабатывают, как и её атака
    fun onTurnEnd(isPlayer: Boolean, justPlayed: CardModel?) {
        for (slot in ownSlots(isPlayer).toList()) {
            val card = field.cardAt(slot) ?: continue
            if (card === justPlayed) continue
            for (t in tokens(card.effect)) {
                when (t[0]) {
                    "turn_plus" -> addPower(isPlayer, t[1], n(t[2]), card.name)
                    "turn_minus" -> addPower(isPlayer, t[1], -n(t[2]), card.name)
                    "turn_plus_all_owner" ->
                        ALL.forEach { addPower(isPlayer, it, n(t[1]), card.name) }
                    "turn_minus_all_enemy" ->
                        ALL.forEach { addPower(!isPlayer, it, -n(t[1]), card.name) }
                    "turn_minus_power_enemy" -> addPower(!isPlayer, t[1], -n(t[2]), card.name)
                    "turn_heal_owner" -> {
                        changeLife(isPlayer, n(t[1]))
                        onLog("«${card.name}»: +${n(t[1])} жизней магу ${side(isPlayer)}")
                    }
                    "turn_heal_self" -> {
                        healCreature(slot, n(t[1]))
                        onLog("«${card.name}»: +${n(t[1])} себе")
                    }
                    "turn_heal_own_creatures" -> {
                        changeLife(isPlayer, n(t[1]))
                        ownSlots(isPlayer).forEach { healCreature(it, n(t[1])) }
                        onLog("«${card.name}»: +${n(t[1])} магу и всем существам ${side(isPlayer)}")
                    }
                    "turn_dmg_owner" -> {
                        changeLife(isPlayer, -n(t[1]))
                        onLog("«${card.name}»: ${n(t[1])} повреждений магу ${side(isPlayer)}")
                    }
                    "turn_dmg_owner_if_lt_enemy" -> {
                        val mine = powerOf(isPlayer, t[1])
                        val theirs = powerOf(!isPlayer, t[1])
                        if (mine < theirs) {
                            changeLife(isPlayer, -n(t[2]))
                            onLog("«${card.name}»: ${ru(t[1])} $mine < $theirs — ${n(t[2])} повреждений магу ${side(isPlayer)}")
                        }
                    }
                }
            }
        }
        refreshUi()
    }

    // ===== Смерть любого существа =====
    fun onCreatureDeath(died: String) {
        onLog("Погибает «$died»")
        for (isPlayer in listOf(true, false)) {
            for (slot in ownSlots(isPlayer).toList()) {
                val card = field.cardAt(slot) ?: continue
                for (t in tokens(card.effect)) {
                    when (t[0]) {
                        "death_plus_power" -> addPower(isPlayer, t[1], n(t[2]), card.name)
                        "death_heal_owner" -> {
                            changeLife(isPlayer, n(t[1]))
                            onLog("«${card.name}»: +${n(t[1])} жизней магу ${side(isPlayer)}")
                        }
                    }
                }
            }
        }
        refreshUi()
    }

    // ===== Каст заклинания =====
    fun castSpell(card: CardModel, isPlayer: Boolean): Boolean {
        ownerCtx = isPlayer
        var applied = false
        var killed = 0
        for (t in tokens(card.effect)) {
            when (t[0]) {
                "spell_dmg_enemy_dyn" -> {
                    val v = boost(dyn(t), isPlayer)
                    changeLife(!isPlayer, -v)
                    onLog("«${card.name}»: $v повреждений магу ${side(!isPlayer)}")
                    applied = true
                }
                "spell_dmg_enemy_flat" -> {
                    val v = boost(n(t[1]), isPlayer)
                    changeLife(!isPlayer, -v)
                    onLog("«${card.name}»: $v повреждений магу ${side(!isPlayer)}")
                    applied = true
                }
                "spell_dmg_enemy_creatures" -> {
                    val v = boost(n(t[1]), isPlayer)
                    enemySlots(isPlayer).forEach { damageCreature(it, v) }
                    onLog("«${card.name}»: $v повреждений всем существам противника")
                    applied = true
                }
                "spell_dmg_enemy_creatures_dyn" -> {
                    val v = boost(dyn(t), isPlayer)
                    enemySlots(isPlayer).forEach { damageCreature(it, v) }
                    onLog("«${card.name}»: $v повреждений всем существам противника")
                    applied = true
                }
                "spell_dmg_enemy_both_dyn" -> {
                    val v = boost(dyn(t), isPlayer)
                    changeLife(!isPlayer, -v)
                    enemySlots(isPlayer).forEach { damageCreature(it, v) }
                    onLog("«${card.name}»: $v повреждений магу и существам противника")
                    applied = true
                }
                "spell_dmg_all_creatures" -> {
                    val v = boost(n(t[1]), isPlayer)
                    (0..9).filter { field.cardAt(it) != null }.forEach { damageCreature(it, v) }
                    onLog("«${card.name}»: $v повреждений всем существам")
                    applied = true
                }
                "spell_dmg_equal_attack" -> {
                    enemySlots(isPlayer).toList().forEach { i ->
                        field.cardAt(i)?.let { damageCreature(i, boost(it.attack, isPlayer)) }
                    }
                    onLog("«${card.name}»: каждому существу противника — урон, равный его атаке")
                    applied = true
                }
                "spell_meteor" -> {
                    val v = boost(20, isPlayer)
                    val spare = powerOf(isPlayer, "Earth") > 11
                    enemySlots(isPlayer).forEach { damageCreature(it, v) }
                    if (!spare) ownSlots(isPlayer).forEach { damageCreature(it, v) }
                    onLog(
                        "«${card.name}»: $v повреждений всем существам" +
                                if (spare) " (свои не тронуты, ${ru("Earth")} > 11)"
                                else " (и своим, ${ru("Earth")} ≤ 11)"
                    )
                    applied = true
                }
                "spell_armageddon" -> {
                    val v = boost(dyn(t), isPlayer)
                    changeLife(!isPlayer, -v)
                    (0..9).filter { field.cardAt(it) != null }.forEach { damageCreature(it, v) }
                    onLog("«${card.name}»: $v повреждений магу противника и всем существам")
                    applied = true
                }
                "spell_heal_owner_dyn" -> {
                    val v = halfPlus(t)
                    changeLife(isPlayer, v)
                    onLog("«${card.name}»: +$v жизней магу ${side(isPlayer)}")
                    applied = true
                }
                "spell_heal_owner_mult" -> {
                    val v = powerOf(isPlayer, t[1]) * n(t[2])
                    changeLife(isPlayer, v)
                    onLog("«${card.name}»: +$v жизней магу ${side(isPlayer)} (${ru(t[1])} × ${n(t[2])})")
                    applied = true
                }
                "spell_heal_owner_creatures" -> {
                    val v = n(t[1])
                    changeLife(isPlayer, v)
                    ownSlots(isPlayer).forEach { healCreature(it, v) }
                    onLog("«${card.name}»: +$v жизней магу и существам ${side(isPlayer)}")
                    applied = true
                }
                "spell_heal_owner_per_creature" -> {
                    if (killed > 0) {
                        changeLife(isPlayer, killed * n(t[1]))
                        onLog("«${card.name}»: +${killed * n(t[1])} жизней за $killed существ")
                        applied = true
                    }
                }
                "spell_kill_maxhp" -> {
                    maxHpEnemySlot(isPlayer)?.let {
                        val targetName = field.cardAt(it)?.name ?: "?"
                        killCreature(it)
                        onLog("«${card.name}»: уничтожает «$targetName» (самое живучее)")
                    }
                    applied = true
                }
                "spell_kill_all_creatures" -> {
                    (0..9).filter { field.cardAt(it) != null }.forEach {
                        killed++; killCreature(it)
                    }
                    if (killed > 0) onLog("«${card.name}»: уничтожает все существа ($killed)")
                    if (killed > 0) applied = true
                }
                "spell_drain" -> {
                    val v = boost(halfPlus(t), isPlayer)
                    changeLife(!isPlayer, -v)
                    changeLife(isPlayer, v)
                    onLog("«${card.name}»: вытягивает $v жизней у ${side(!isPlayer)}")
                    applied = true
                }
                "spell_hypnosis" -> {
                    val top = enemySlots(isPlayer).mapNotNull { field.cardAt(it) }
                        .sortedByDescending { it.attack }.take(2)
                    val dmg = top.sumOf { it.attack }
                    if (dmg > 0) {
                        changeLife(!isPlayer, -dmg)
                        onLog("«${card.name}»: ${top.joinToString(" + ") { "«${it.name}»" }} = $dmg повреждений магу ${side(!isPlayer)}")
                        applied = true
                    }
                }
                "spell_plus_power_owner" -> addPower(isPlayer, t[1], n(t[2]), card.name)
                "spell_minus_power_enemy" -> addPower(!isPlayer, t[1], -n(t[2]), card.name)
                "spell_minus_all_enemy" -> ALL.forEach { addPower(!isPlayer, it, -n(t[1]), card.name) }
            }
        }
        if (applied) onLog("Срабатывает «${card.name}»")
        refreshUi()
        return applied
    }

    private val TARGET_ENEMY = setOf(
        "spell_dmg_enemy_creatures", "spell_dmg_enemy_creatures_dyn",
        "spell_dmg_enemy_both_dyn", "spell_dmg_all_creatures",
        "spell_dmg_equal_attack", "spell_kill_maxhp",
        "spell_meteor", "spell_armageddon"
    )

    fun canTarget(card: CardModel, slotIndex: Int): Boolean {
        val names = tokens(card.effect).map { it[0] }
        return if (slotIndex >= 5) names.any { it in TARGET_ENEMY }
        else names.any { it == "spell_heal_owner_creatures" }
    }

    // Прицельный каст: эффект только на выбранное существо
    fun castSpellTargeted(card: CardModel, isPlayer: Boolean, slotIndex: Int): Boolean {
        ownerCtx = isPlayer
        val targetName = field.cardAt(slotIndex)?.name ?: "?"
        var applied = false
        for (t in tokens(card.effect)) {
            when (t[0]) {
                "spell_dmg_enemy_creatures" -> {
                    val v = boost(n(t[1]), isPlayer)
                    damageCreature(slotIndex, v)
                    onLog("«${card.name}» → «$targetName»: $v повреждений")
                    applied = true
                }
                "spell_dmg_enemy_creatures_dyn", "spell_dmg_enemy_both_dyn" -> {
                    val v = boost(dyn(t), isPlayer)
                    damageCreature(slotIndex, v)
                    onLog("«${card.name}» → «$targetName»: $v повреждений")
                    applied = true
                }
                "spell_dmg_all_creatures" -> {
                    val v = boost(n(t[1]), isPlayer)
                    damageCreature(slotIndex, v)
                    onLog("«${card.name}» → «$targetName»: $v повреждений")
                    applied = true
                }
                "spell_dmg_equal_attack" -> {
                    field.cardAt(slotIndex)?.let {
                        val v = boost(it.attack, isPlayer)
                        damageCreature(slotIndex, v)
                        onLog("«${card.name}» → «$targetName»: $v повреждений")
                    }
                    applied = true
                }
                "spell_meteor" -> {
                    val v = boost(20, isPlayer)
                    damageCreature(slotIndex, v)
                    onLog("«${card.name}» → «$targetName»: $v повреждений")
                    applied = true
                }
                "spell_armageddon" -> {
                    val v = boost(dyn(t), isPlayer)
                    damageCreature(slotIndex, v)
                    onLog("«${card.name}» → «$targetName»: $v повреждений")
                    applied = true
                }
                "spell_kill_maxhp" -> {
                    killCreature(slotIndex)
                    onLog("«${card.name}»: уничтожает «$targetName»")
                    applied = true
                }
                "spell_heal_owner_creatures" -> {
                    healCreature(slotIndex, n(t[1]))
                    onLog("«${card.name}» → «$targetName»: +${n(t[1])} жизней")
                    applied = true
                }
            }
        }
        if (applied) onLog("Срабатывает «${card.name}»")
        refreshUi()
        return applied
    }
}
