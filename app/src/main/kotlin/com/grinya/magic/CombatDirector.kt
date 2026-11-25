package com.grinya.magic

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

class CombatDirector(
    private val field: Battlefield,
    private val factory: CardViewFactory,
    private val overlay: FrameLayout,
    private val powersOf: (Boolean) -> MutableMap<String, Int>, // true = игрок
    private val onLog: (String) -> Unit,
    private val onPlayerHit: (Int) -> Unit,
    private val onEnemyHit: (Int) -> Unit,
    private val onHeal: (Boolean, Int) -> Unit, // вампиризм и прочее лечение
    private val isOver: () -> Boolean,
    private val onCreatureDeath: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    private data class AttackTask(
        val attackerIdx: Int,
        val attacker: CardModel,
        val defenderIdx: Int,
        val playerAttacking: Boolean
    )

    fun resolve(playerAttacking: Boolean, skipAttacker: CardModel?, onDone: () -> Unit) {
        val tasks = ArrayList<AttackTask>()
        for (i in 0..4) {
            val attackerIdx = if (playerAttacking) i else i + 5
            val attacker = field.cardAt(attackerIdx) ?: continue
            if (attacker === skipAttacker) continue
            tasks.add(
                AttackTask(
                    attackerIdx, attacker,
                    if (playerAttacking) i + 5 else i,
                    playerAttacking
                )
            )
        }
        if (tasks.isEmpty()) { onDone(); return }
        process(tasks, 0, onDone)
    }

    private fun process(tasks: List<AttackTask>, index: Int, onDone: () -> Unit) {
        if (isOver() || index >= tasks.size) { onDone(); return }

        val t = tasks[index]
        val attackerView = field.cardViewAt(t.attackerIdx)
        val attackerCard = field.cardAt(t.attackerIdx)
        if (attackerView == null || attackerCard == null) {
            process(tasks, index + 1, onDone)
            return
        }

        val slot = field.slots[t.attackerIdx]
        val aLoc = IntArray(2)
        attackerView.getLocationOnScreen(aLoc)
        val oLoc = IntArray(2)
        overlay.getLocationOnScreen(oLoc)

        val ghost = factory.createSlotCard(attackerCard)
        ghost.layoutParams = FrameLayout.LayoutParams(slot.width, slot.height).apply {
            leftMargin = aLoc[0] - oLoc[0]
            topMargin = aLoc[1] - oLoc[1]
        }
        overlay.addView(ghost)
        attackerView.alpha = 0f

        val dx = (if (t.playerAttacking) factory.dp(14) else -factory.dp(14)).toFloat()
        val dy = -factory.dp(6).toFloat()

        ghost.animate().translationY(dy).setDuration(250).withEndAction {
            ghost.animate().translationX(dx).setDuration(300).withEndAction {
                applyDamage(t)
                if (isOver()) {
                    finishAttack(attackerView, ghost)
                    onDone()
                    return@withEndAction
                }
                ghost.animate().translationX(0f).translationY(0f).setDuration(350)
                    .withEndAction {
                        finishAttack(attackerView, ghost)
                        handler.postDelayed({ process(tasks, index + 1, onDone) }, 400)
                    }.start()
            }.start()
        }.start()
    }

    private fun finishAttack(attackerView: View?, ghost: View) {
        overlay.removeView(ghost)
        if (attackerView != null && attackerView.parent != null) {
            attackerView.alpha = 1f
        }
    }

    // ===== Модификаторы боя =====

    private fun tokens(effect: String): List<List<String>> =
        effect.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map { it.split(":") }

    private fun hasToken(effect: String, name: String): Boolean =
        tokens(effect).any { it[0] == name }

    private fun values(effect: String, name: String): List<Int> =
        tokens(effect).filter { it[0] == name }.mapNotNull { it.getOrNull(1)?.toIntOrNull() }

    private fun creaturesOf(isPlayer: Boolean): List<CardModel> =
        (if (isPlayer) 0..4 else 5..9).mapNotNull { field.cardAt(it) }

    // Итоговая атака: элементалы бьют силой стихии, Полководец добавляет +%
    private fun effectiveAttack(attacker: CardModel, isPlayer: Boolean): Int {
        var atk = if (hasToken(attacker.effect, "dynamic_attack"))
            powersOf(isPlayer)[attacker.element] ?: 0
        else attacker.attack
        if (hasToken(attacker.effect, "dynamic_attack")) {
            onLog("«${attacker.name}»: атака = сила ${attacker.element} → $atk")
        }
        val pct = creaturesOf(isPlayer)
            .filter { it !== attacker && hasToken(it.effect, "aura_attack_pct") }
            .sumOf { values(it.effect, "aura_attack_pct").sum() }
        if (pct > 0) {
            val before = atk
            atk = atk * (100 + pct) / 100
            onLog("Аура атаки: «${attacker.name}» $before +$pct% → $atk")
        }
        return atk
    }

    // Сколько урона реально доходит: щиты защищающейся стороны.
    // Ледяной страж защищает только мага, Эльф-защитник — мага и существ
    private fun reduceIncoming(dmg: Int, defenderIsPlayer: Boolean, targetIsMage: Boolean): Int {
        var d = dmg
        if (targetIsMage) {
            val pct = creaturesOf(defenderIsPlayer)
                .sumOf { values(it.effect, "dmg_reduce_owner_pct").sum() }
            if (pct > 0) {
                val before = d
                d = (d * (100 - pct) / 100).coerceAtLeast(1)
                onLog("Защита: Ледяной страж — урон $before → $d")
            }
        }
        val flat = creaturesOf(defenderIsPlayer)
            .sumOf { values(it.effect, "dmg_reduce_owner_flat").sum() }
        if (flat > 0 && d > 1) {
            val before = d
            d = (d - flat).coerceAtLeast(1)
            onLog("Защита: Эльф-защитник — урон $before → $d")
        }
        return d
    }

    private fun applyDamage(t: AttackTask) {
        val atk = effectiveAttack(t.attacker, t.playerAttacking)
        val attackAll = hasToken(t.attacker.effect, "attack_all")
        val lifesteal = hasToken(t.attacker.effect, "lifesteal_half")
        var dealtTotal = 0

        if (attackAll) {
            val enemySlots = (if (t.playerAttacking) 5..9 else 0..4).filter { field.cardAt(it) != null }
            if (enemySlots.isEmpty()) {
                val dmg = reduceIncoming(atk, !t.playerAttacking, true)
                dealtTotal += dmg
                if (t.playerAttacking) {
                    onEnemyHit(dmg); onLog("«${t.attacker.name}» бьёт противника на $dmg")
                } else {
                    onPlayerHit(dmg); onLog("«${t.attacker.name}» бьёт вас на $dmg")
                }
            } else {
                for (s in enemySlots) {
                    val target = field.cardAt(s) ?: continue
                    dealtTotal += reduceIncoming(atk, !t.playerAttacking, false)
                    val killed = damageAt(s, atk)
                    onLog(
                        if (killed) "«${t.attacker.name}» убивает «${target.name}»"
                        else "«${t.attacker.name}» бьёт «${target.name}» на $atk"
                    )
                }
            }
        } else {
            val target = field.cardAt(t.defenderIdx)
            if (target != null) {
                dealtTotal += reduceIncoming(atk, !t.playerAttacking, false)
                val killed = damageAt(t.defenderIdx, atk)
                onLog(
                    if (killed) "«${t.attacker.name}» убивает «${target.name}»"
                    else "«${t.attacker.name}» бьёт «${target.name}» на $atk"
                )
            } else {
                val dmg = reduceIncoming(atk, !t.playerAttacking, true)
                dealtTotal += dmg
                if (t.playerAttacking) {
                    onEnemyHit(dmg); onLog("«${t.attacker.name}» бьёт противника на $dmg")
                } else {
                    onPlayerHit(dmg); onLog("«${t.attacker.name}» бьёт вас на $dmg")
                }
            }
        }

        if (lifesteal && dealtTotal > 0) {
            val heal = dealtTotal / 2
            if (heal > 0) {
                onHeal(t.playerAttacking, heal)
                onLog("«${t.attacker.name}» вытягивает $heal жизней")
            }
        }
    }

    // Урон существу в слоте. Возвращает true, если существо погибло
    fun damageAt(index: Int, amount: Int): Boolean {
        val view = field.cardViewAt(index) ?: return false
        val card = view.tag as? CardModel ?: return false
        card.health -= reduceIncoming(amount, index < 5, false)
        if (card.health > 0) {
            factory.updateStatsText(view, card)
            flash(view)
            return false
        }
        if (tryResurrect(index, card, amount)) return false
        val diedName = card.name
        field.killCard(index) { v -> die(v) }
        onCreatureDeath(diedName)
        return true
    }

    fun healAt(index: Int, amount: Int) {
        val view = field.cardViewAt(index) ?: return
        val card = view.tag as? CardModel ?: return
        card.health += amount
        factory.updateStatsText(view, card)
    }

    fun killAt(index: Int) {
        val view = field.cardViewAt(index) ?: return
        val card = view.tag as? CardModel ?: return
        if (tryResurrect(index, card, 1)) return
        val diedName = card.name
        field.killCard(index) { v -> die(v) }
        onCreatureDeath(diedName)
    }

    // Феникс: при смерти, если сила Огня владельца больше порога — выживает
    private fun tryResurrect(index: Int, card: CardModel, dmg: Int): Boolean {
        for (p in tokens(card.effect)) {
            if (p[0] == "death_resurrect_if_gt" && p.size >= 3) {
                val pw = powersOf(index < 5)[p[1]] ?: 0
                if (pw > (p[2].toIntOrNull() ?: 0)) {
                    card.health = dmg.coerceAtLeast(1)
                    val v = field.cardViewAt(index)
                    if (v != null) { factory.updateStatsText(v, card); flash(v) }
                    onLog("«${card.name}» возрождается! (сила ${p[1]} = $pw > ${p[2]})")
                    return true
                }
            }
        }
        return false
    }

    private fun flash(view: View) {
        view.animate().alpha(0.3f).setDuration(80).withEndAction {
            view.animate().alpha(1f).setDuration(120).start()
        }.start()
    }

    private fun die(view: View) {
        view.animate().scaleX(1.3f).scaleY(1.3f).alpha(0f).setDuration(300)
            .withEndAction { (view.parent as? ViewGroup)?.removeView(view) }
            .start()
    }
}
