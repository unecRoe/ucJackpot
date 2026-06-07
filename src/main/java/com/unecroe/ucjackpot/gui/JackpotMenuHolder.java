package com.unecroe.ucjackpot.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;

public final class JackpotMenuHolder implements InventoryHolder {
    private final String menuId;
    private final String jackpotId;
    private final Map<Integer, String> actions;

    public JackpotMenuHolder(String menuId, Map<Integer, String> actions) {
        this(menuId, "", actions);
    }

    public JackpotMenuHolder(String menuId, String jackpotId, Map<Integer, String> actions) {
        this.menuId = menuId;
        this.jackpotId = jackpotId;
        this.actions = actions;
    }

    public String menuId() {
        return menuId;
    }

    public String jackpotId() {
        return jackpotId;
    }

    public String action(int slot) {
        return actions.getOrDefault(slot, "none");
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}


