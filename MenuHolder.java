package com.steelrework.playerprogress.menu;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Отмечает инвентарь как меню плагина и хранит, что лежит в каких слотах. */
public final class MenuHolder implements InventoryHolder {

    public enum Type { MAIN, TOPS, ADMIN_PLAYERS, ADMIN_EDIT }

    private final Type type;
    private final UUID target;
    private final int page;
    /** слот -> действие (ключ познания, "tops", "back", "admin", "player:<uuid>", "booster:<key>", "page:<n>") */
    private final Map<Integer, String> actions = new HashMap<>();
    private Inventory inventory;

    public MenuHolder(Type type, UUID target, int page) {
        this.type = type;
        this.target = target;
        this.page = page;
    }

    public Type type() {
        return type;
    }

    public UUID target() {
        return target;
    }

    public int page() {
        return page;
    }

    public Map<Integer, String> actions() {
        return actions;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
