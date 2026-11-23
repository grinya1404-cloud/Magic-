package com.grinya.magic; // <-- ОБЯЗАТЕЛЬНО, проверь, что пакет совпадает

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;

public class GameCardView extends FrameLayout {

    private ImageView cardImage;
    private TextView tvAttack, tvHealth, tvName;
    private CardView cardContainer;
    private View overlay; // Для затемнения пустых слотов

    // Данные карты
    private String cardId;
    private int attack, health;

    public GameCardView(Context context) {
        super(context);
        init(context);
    }

    public GameCardView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        // Надуваем разметку карты
        LayoutInflater.from(context).inflate(R.layout.view_game_card, this, true);

        cardContainer = findViewById(R.id.cardContainer);
        cardImage = findViewById(R.id.cardImage);
        tvAttack = findViewById(R.id.tvAttack);
        tvHealth = findViewById(R.id.tvHealth);
        tvName = findViewById(R.id.tvName);
        overlay = findViewById(R.id.overlay);

        // Настраиваем тень и скругления (стандартный вид карты)
        cardContainer.setRadius(8f);
        cardContainer.setCardElevation(4f);
        cardContainer.setCardBackgroundColor(Color.BLACK);
    }

    /**
     * Установка данных из JSON-модели
     */
    public void bindCardData(CardModel model) {
        this.cardId = model.getId();
        this.attack = model.getAttack();
        this.health = model.getHealth();

        tvName.setText(model.getName());
        
        // Загрузка картинки. Если используешь Glide/Picasso:
        // Glide.with(getContext()).load(model.getImageRes()).into(cardImage);
        // Пока просто заглушка:
        cardImage.setImageResource(model.getImageRes()); 

        tvAttack.setText(String.valueOf(attack));
        tvHealth.setText(String.valueOf(health));
        
        // Цвет текста здоровья (например, зеленый)
        tvHealth.setTextColor(Color.parseColor("#00FF00"));
        tvAttack.setTextColor(Color.WHITE);
        
        // Скрываем оверлей, если карта есть
        overlay.setVisibility(View.GONE);
    }

    /**
     * Сделать слот пустым
     */
    public void setEmptySlot() {
        cardImage.setImageDrawable(null);
        tvName.setText("");
        tvAttack.setText("");
        tvHealth.setText("");
        overlay.setVisibility(View.VISIBLE);
        // Настраиваем фон пустого слота (синий градиент)
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#1A237E"), Color.parseColor("#0D47A1")}
        );
        bg.setStroke(2, Color.parseColor("#00BFFF"));
        bg.setCornerRadius(8f);
        cardContainer.setBackground(bg);
    }

    // Геттеры для логики боя
    public int getAttack() { return attack; }
    public int getHealth() { return health; }
    public String getCardId() { return cardId; }
}