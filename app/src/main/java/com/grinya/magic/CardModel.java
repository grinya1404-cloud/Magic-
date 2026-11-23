package com.grinya.magic;

public class CardModel {
    private String id;
    private String name;
    private int attack;
    private int health;
    private int imageRes;

    // Пустой конструктор для JSON-парсера
    public CardModel() {}

    public CardModel(String id, String name, int attack, int health, int imageRes) {
        this.id = id;
        this.name = name;
        this.attack = attack;
        this.health = health;
        this.imageRes = imageRes;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getAttack() { return attack; }
    public int getHealth() { return health; }
    public int getImageRes() { return imageRes; }

    public void setId(String id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setAttack(int attack) { this.attack = attack; }
    public void setHealth(int health) { this.health = health; }
    public void setImageRes(int imageRes) { this.imageRes = imageRes; }
}