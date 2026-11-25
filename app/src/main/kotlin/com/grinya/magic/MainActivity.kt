package com.grinya.magic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val ELEMENTS = listOf("Fire", "Water", "Air", "Earth", "Death")

    private val handCards = ArrayList<CardModel>()
    private val aiHand = ArrayList<CardModel>()
    private val aiPoints = HashMap<String, Int>()

    private lateinit var factory: CardViewFactory
    private lateinit var field: Battlefield
    private lateinit var combat: CombatDirector
    private lateinit var elementPanel: ElementPanel
    private lateinit var handController: HandController
    private lateinit var abilityEngine: AbilityEngine

    private val aiHandler = Handler(Looper.getMainLooper())

    private var playerLife = 50
    private var aiLife = 50

    private var gameOver = false
    private var playerTurn = true

    private var playerJustPlayedCard: CardModel? = null
    private var aiJustPlayedCard: CardModel? = null

    // Полный журнал игры — открывается кликом по строке лога
    private val logHistory = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        factory = CardViewFactory(this)

        // 0..4 — свои слоты, 5..9 — противника; пары друг напротив друга
        val leftIds = intArrayOf(R.id.slot1, R.id.slot3, R.id.slot4, R.id.slot5, R.id.slot10)
        val rightIds = intArrayOf(R.id.slot2, R.id.slot6, R.id.slot7, R.id.slot8, R.id.slot9)
        val slotViews = ArrayList<FrameLayout>(10)
        for (id in leftIds) slotViews.add(findViewById(id))
        for (id in rightIds) slotViews.add(findViewById(id))
        field = Battlefield(slotViews)

        combat = CombatDirector(
            field, factory,
            findViewById(R.id.dragOverlay),
            powersOf = { isPlayer -> if (isPlayer) elementPanel.raw() else aiPoints },
            onLog = { log(it) },
            onPlayerHit = { damage -> takeDirectDamage(damage) },
            onEnemyHit = { damage -> dealDirectDamage(damage) },
            onHeal = { isPlayer, amt ->
                if (isPlayer) playerLife += amt else aiLife += amt
                updateLifeUI(); checkGameEnd()
            },
            isOver = { gameOver },
            onCreatureDeath = { diedName -> abilityEngine.onCreatureDeath(diedName) }
        )

        abilityEngine = AbilityEngine(
            field,
            powersOf = { isPlayer -> if (isPlayer) elementPanel.raw() else aiPoints },
            onLog = { log(it) },
            refreshUi = { elementPanel.refresh() },
            changeLife = { isPlayer, delta ->
                if (isPlayer) {
                    playerLife += delta; updateLifeUI(); checkGameEnd()
                } else {
                    aiLife += delta; updateLifeUI(); checkGameEnd()
                }
            },
            damageCreature = { slot, dmg -> combat.damageAt(slot, dmg) },
            healCreature = { slot, amt -> combat.healAt(slot, amt) },
            killCreature = { slot -> combat.killAt(slot) }
        )

        elementPanel = ElementPanel(findViewById(R.id.leftResources), ELEMENTS, 20) { element ->
            elementPanel.highlightSelected(element)
            handController.selectElement(element)
        }

        handController = HandController(
            findViewById(R.id.handScroll),
            findViewById(R.id.handPanel),
            findViewById(R.id.dragOverlay),
            field, factory,
            canDrag = { playerTurn && !gameOver },
            onDropRequest = { card, view, slotIndex -> tryPlayCard(card, view, slotIndex) },
            onCardInfo = { card -> showCardInfo(card) }
        )

        val base = CardRepository(resources, packageName).loadAll()
        handCards.addAll(base.map { it.clone() })
        aiHand.addAll(base.map { it.clone() })
        for (e in ELEMENTS) aiPoints[e] = 20

        handController.setCards(handCards)
        setupSkipButton()
        setupLogClick()
        updateLifeUI()
        log("Ваш ход")
    }

    // ---------- Ходы ----------

    private fun tryPlayCard(card: CardModel, view: View, slotIndex: Int): Boolean {
        if (!playerTurn || gameOver) return false
        if (card.type == "spell") return tryCastSpellOn(card, slotIndex)

        // Свои существа — только в свои слоты (0..4)
        if (slotIndex > 4 || !field.isFree(slotIndex)) return false

        val available = elementPanel.get(card.element)
        if (available < card.cost) {
            log("Не хватает очков стихии ${card.element}: нужно ${card.cost}, есть $available")
            return false
        }

        elementPanel.spend(card.element, card.cost)
        handController.removeFromHand(card)

        (view.parent as? ViewGroup)?.removeView(view)
        field.place(view, slotIndex)
        view.translationX = 0f
        view.translationY = 0f
        view.translationZ = 0f
        view.alpha = 1f
        view.visibility = View.VISIBLE
        view.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Со слота карту больше не таскаем; долгий тап — описание
        view.setOnTouchListener(null)
        view.setOnLongClickListener { showCardInfo(card); true }

        // Срабатывание способностей «при входе»
        abilityEngine.onEnter(slotIndex, true)

        playerJustPlayedCard = card
        log("Вы выложили «${card.name}»")
        endPlayerTurn()
        return true
    }

    private fun tryCastSpellOn(card: CardModel, slotIndex: Int): Boolean {
        val target = field.cardAt(slotIndex)
        if (target != null && !abilityEngine.canTarget(card, slotIndex)) {
            log("«${card.name}» не действует на отдельную цель")
            return false // карта вернётся в руку, сила не тратится
        }
        val available = elementPanel.get(card.element)
        if (available < card.cost) {
            log("Не хватает очков стихии ${card.element}: нужно ${card.cost}, есть $available")
            return false
        }
        elementPanel.spend(card.element, card.cost)
        handController.removeFromHand(card)
        log("Вы произносите «${card.name}»")
        if (target != null) abilityEngine.castSpellTargeted(card, true, slotIndex)
        else abilityEngine.castSpell(card, true)
        updateLifeUI()
        checkGameEnd()
        // Заклинание завершает ход так же, как существо и «Пропустить»
        playerJustPlayedCard = null
        endPlayerTurn()
        return true
    }

    private fun endPlayerTurn() {
        playerTurn = false
        if (gameOver) return
        abilityEngine.onTurnEnd(true, playerJustPlayedCard)
        combat.resolve(true, playerJustPlayedCard) {
            playerJustPlayedCard = null
            elementPanel.regenAll()
            log("Реген: +1 ко всем стихиям")
            logPowers()
            if (!gameOver) {
                log("—— Ход противника ——")
                aiHandler.postDelayed({ aiTurn() }, 1200)
            }
        }
    }

    private fun aiTurn() {
        if (gameOver) return

        val affordable = aiHand.filter { (aiPoints[it.element] ?: 0) >= it.cost }
        val freeSlots = (5..9).filter { field.isFree(it) }
        val creatures = affordable.filter { it.type == "creature" }
        val spells = affordable.filter { it.type == "spell" }

        var played = false

        // Существо — если есть куда и есть что; в одном случае из четырёх
        // ИИ отдаёт ход заклинанию, чтобы не копить магию мёртвым грузом
        val preferSpell = creatures.isEmpty() || freeSlots.isEmpty() ||
        (spells.isNotEmpty() && Random.nextInt(4) == 0)

        if (preferSpell && spells.isNotEmpty()) {
            val card = spells[Random.nextInt(spells.size)]
            if (aiCastSpell(card)) {
                // Сила и карта списываются только после успешного каста
                aiPoints[card.element] = (aiPoints[card.element] ?: 0) - card.cost
                aiHand.remove(card)
                played = true
            }
        }

        if (!played && creatures.isNotEmpty() && freeSlots.isNotEmpty()) {
            val card = creatures[Random.nextInt(creatures.size)]
            val slotIndex = freeSlots[Random.nextInt(freeSlots.size)]

            aiPoints[card.element] = (aiPoints[card.element] ?: 0) - card.cost
            aiHand.remove(card)
            aiJustPlayedCard = card

            val cardView = factory.createSlotCard(card)
            cardView.setOnLongClickListener { showCardInfo(card); true }
            field.place(cardView, slotIndex)

            abilityEngine.onEnter(slotIndex, false)

            log("Противник выложил «${card.name}»")

        } else if (!played) {
            log("Противник пропустил ход")
        }

        abilityEngine.onTurnEnd(false, aiJustPlayedCard)
        combat.resolve(false, aiJustPlayedCard) {
            aiJustPlayedCard = null
            for (e in ELEMENTS) {
                aiPoints[e] = (aiPoints[e] ?: 0) + 1
            }
            log("Реген ИИ: +1 ко всем стихиям")
            logPowers()
            if (!gameOver) {
                playerTurn = true
                log("—— Ваш ход ——")
            }
        }
    }

    // Каст заклинания ИИ. true — заклинание сработало, силу и карту можно списывать
    private fun aiCastSpell(card: CardModel): Boolean {
        val tokens = card.effect.split(";")
        .map { it.trim().split(":") }
        .filter { it.isNotEmpty() && it[0].isNotEmpty() }

        // Заклинания, которым нужна своя цель
        val targeted = tokens.any { it[0] in setOf(
                "spell_dmg_enemy_creatures", "spell_dmg_enemy_creatures_dyn",
                "spell_dmg_enemy_both_dyn", "spell_dmg_all_creatures",
                "spell_dmg_equal_attack", "spell_kill_maxhp", "spell_meteor"
        ) }
        // Заклинания, которым нужны свои существа
        val healOwn = tokens.any { it[0] == "spell_heal_owner_creatures" }
        // Заклинания, которым нужны чужие существа (иначе эффект пустой)
        val needsEnemies = targeted || tokens.any {
            it[0] == "spell_hypnosis" || it[0] == "spell_dmg_enemy_creatures"
        }

        val enemyCreatures = (0..4).filter { field.cardAt(it) != null }
        val ownCreatures = (5..9).filter { field.cardAt(it) != null }

        if (needsEnemies && enemyCreatures.isEmpty()) return false

        if (healOwn && ownCreatures.isNotEmpty()) {
            val target = ownCreatures[Random.nextInt(ownCreatures.size)]
            if (abilityEngine.castSpellTargeted(card, false, target)) {
                log("Противник применяет «${card.name}»")
                return true
            }
            return false
        }

        if (targeted) {
            // Простой «умный» выбор: цель с наибольшей жизнью
            val target = enemyCreatures.maxByOrNull { field.cardAt(it)?.health ?: 0 }
            ?: return false
            if (abilityEngine.castSpellTargeted(card, false, target)) {
                log("Противник применяет «${card.name}»")
                return true
            }
            return false
        }

        if (abilityEngine.castSpell(card, false)) {
            log("Противник применяет «${card.name}»")
            return true
        }
        return false
    }

    // ---------- Жизни ----------

    private fun takeDirectDamage(damage: Int) {
        playerLife -= damage
        updateLifeUI()
        shake(R.id.lifePlayer)
        checkGameEnd()
    }

    private fun dealDirectDamage(damage: Int) {
        aiLife -= damage
        updateLifeUI()
        shake(R.id.lifeEnemy)
        checkGameEnd()
    }

    private fun updateLifeUI() {
        findViewById<TextView>(R.id.lifePlayer)?.text = "Life: $playerLife"
        findViewById<TextView>(R.id.lifeEnemy)?.text = "Life: $aiLife"
    }

    private fun shake(viewId: Int) {
        val v = findViewById<View>(viewId) ?: return
        v.animate().translationX(-10f).setDuration(60).withEndAction {
            v.animate().translationX(10f).setDuration(60).withEndAction {
                v.animate().translationX(-6f).setDuration(50).withEndAction {
                    v.animate().translationX(6f).setDuration(50).withEndAction {
                        v.animate().translationX(0f).setDuration(50).start()
                    }.start()
                }.start()
            }.start()
        }.start()
    }

    private fun checkGameEnd() {
        if (gameOver) return
        when {
            aiLife <= 0 && playerLife <= 0 -> { gameOver = true; log("Ничья!") }
            aiLife <= 0 -> { gameOver = true; log("Вы победили!") }
            playerLife <= 0 -> { gameOver = true; log("Вы проиграли!") }
        }
    }

    // ---------- UI ----------

    private fun setupSkipButton() {
        val logPanel = findViewById<android.widget.LinearLayout>(R.id.logPanel)
        val button = Button(this).apply {
            text = "Пропустить"
            textSize = 11f
            setPadding(12, 4, 12, 4)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        button.setOnClickListener {
            if (!playerTurn || gameOver) return@setOnClickListener
            log("Вы пропустили ход")
            playerJustPlayedCard = null
            endPlayerTurn()
        }
        logPanel.addView(button)
    }

    // Клик по строке лога — полное окно журнала
    private fun setupLogClick() {
        findViewById<TextView>(R.id.logLine1)?.setOnClickListener { showFullLog() }
    }

    private fun showFullLog() {
        val scroll = ScrollView(this)
        val text = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(24, 16, 24, 16)
            text = logHistory.toString()
        }
        scroll.addView(text)

        AlertDialog.Builder(this)
        .setTitle("Журнал игры")
        .setView(scroll)
        .setPositiveButton("Ок", null)
        .setNegativeButton("Скопировать") { _, _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("log", logHistory.toString()))
        }
        .show()
    }

    private fun log(text: String) {
        logHistory.append(text).append('\n')
        findViewById<TextView>(R.id.logLine1)?.text = text
    }

    private fun ru(element: String) = when (element) {
        "Fire" -> "Огонь"; "Water" -> "Вода"; "Air" -> "Воздух"
        "Earth" -> "Земля"; else -> "Смерть"
    }

    private fun powersLine(isPlayer: Boolean): String =
    ELEMENTS.joinToString(", ") { e ->
        val v = if (isPlayer) elementPanel.get(e) else (aiPoints[e] ?: 0)
        "${ru(e)} $v"
    }

    private fun logPowers() {
        log("Сила — игрок: ${powersLine(true)} | ИИ: ${powersLine(false)}")
    }

    private fun showCardInfo(card: CardModel) {
        val elementRu = when (card.element) {
            "Fire" -> "Огонь"; "Water" -> "Вода"; "Air" -> "Воздух"
            "Earth" -> "Земля"; else -> "Смерть"
        }
        val body = buildString {
            append("Стихия: $elementRu, уровень ${card.level}\n")
            if (card.type == "spell") {
                append("Заклинание")
            } else {
                append("Существо, атака ${card.attack}, жизнь ${card.health}")
            }
            if (card.ability.isNotEmpty()) append("\n\n${card.ability}")
        }
        AlertDialog.Builder(this)
        .setTitle(card.name)
        .setMessage(body)
        .setPositiveButton("Ок", null)
        .show()
    }
}
