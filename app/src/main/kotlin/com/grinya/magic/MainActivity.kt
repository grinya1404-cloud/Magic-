package com.grinya.magic

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.random.Random
import androidx.appcompat.app.AlertDialog

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
            onCreatureDeath = { abilityEngine.onCreatureDeath() }
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
            killCreature = { slot -> combat.killAt(slot) }   // <-- вот это
        )

        elementPanel = ElementPanel(findViewById(R.id.leftResources), ELEMENTS, 20) { element ->
            elementPanel.highlightSelected(element)
            handController.selectElement(element)
            log(if (playerTurn) "Ваш ход. Пакет: $element" else "Пакет: $element (ход противника)")
        }

        handController = HandController(
            findViewById(R.id.handScroll),
            findViewById(R.id.handPanel),
            findViewById(R.id.dragOverlay),
            field, factory,
            canDrag = { playerTurn && !gameOver },
            onDropRequest = { card, view, slotIndex -> tryPlayCard(card, view, slotIndex) }, // <-- запятая
            onCardInfo = { card -> showCardInfo(card) }
        )

        val base = CardRepository(resources, packageName).loadAll()
        handCards.addAll(base.map { it.clone() })
        aiHand.addAll(base.map { it.clone() })
        for (e in ELEMENTS) aiPoints[e] = 20

        handController.setCards(handCards)
        setupSkipButton()
        updateLifeUI()
        log("Ваш ход")
    }

    // ---------- Ходы ----------

    private fun tryPlayCard(card: CardModel, view: View, slotIndex: Int): Boolean {
        if (!playerTurn || gameOver) return false
        if (card.type == "spell") return tryCastSpellOn(card, slotIndex)

        if (!field.isFree(slotIndex)) return false

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
        return true
    }

    private fun endPlayerTurn() {
        playerTurn = false
        if (gameOver) return
        combat.resolve(true, playerJustPlayedCard) {
            playerJustPlayedCard = null
            elementPanel.regenAll()          // <-- сюда
            if (!gameOver) {
                log("Ход противника…")
                aiHandler.postDelayed({ aiTurn() }, 1200)
            }
        }
    }

    private fun aiTurn() {
        if (gameOver) return

        val affordable = aiHand.filter { (aiPoints[it.element] ?: 0) >= it.cost }
        val freeSlots = (5..9).filter { field.isFree(it) }

        if (affordable.isEmpty() || freeSlots.isEmpty()) {
            log("Противник пропустил ход")
        } else {
            val card = affordable[Random.nextInt(affordable.size)]
            val slotIndex = freeSlots[Random.nextInt(freeSlots.size)]

            aiPoints[card.element] = (aiPoints[card.element] ?: 0) - card.cost
            aiHand.remove(card)
            aiJustPlayedCard = card

            field.place(factory.createSlotCard(card), slotIndex)
            log("Противник выложил «${card.name}»")
        }

        combat.resolve(false, aiJustPlayedCard) {
            aiJustPlayedCard = null
            for (e in ELEMENTS) {            // <-- сюда
                aiPoints[e] = (aiPoints[e] ?: 0) + 1
            }
            if (!gameOver) {
                playerTurn = true
                log("Ваш ход")
            }
        }

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

    private fun log(text: String) {
        findViewById<TextView>(R.id.logLine1)?.text = text
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
