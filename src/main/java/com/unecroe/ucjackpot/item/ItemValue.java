package com.unecroe.ucjackpot.item;

import org.bukkit.inventory.ItemStack;

public record ItemValue(boolean accepted, double value, String reason, ItemStack normalized) {
    public static ItemValue rejected(String reason) {
        return new ItemValue(false, 0.0, reason, null);
    }
}


