package com.unecroe.ucjackpot.item;

import com.unecroe.ucjackpot.config.JackpotDefinition;
import com.unecroe.ucjackpot.config.PluginSettings;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;

public final class ItemValuator {
    public ItemValue evaluate(PluginSettings settings, JackpotDefinition jackpot, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) {
            return ItemValue.rejected("empty");
        }
        Material material = item.getType();
        String materialName = material.name().toUpperCase(Locale.ROOT);
        if (settings.blockedMaterials().contains(materialName)) {
            return ItemValue.rejected("blocked-material");
        }
        if (item.getAmount() < jackpot.minItemsPerEntry()) {
            return ItemValue.rejected("below-min-items");
        }
        if (!jackpot.acceptCustomModelData() && item.hasItemMeta() && item.getItemMeta().hasCustomModelData()) {
            return ItemValue.rejected("custom-model-data");
        }
        if (!jackpot.acceptEnchantedItems() && !item.getEnchantments().isEmpty()) {
            return ItemValue.rejected("enchanted");
        }
        Double baseValue = jackpot.materialValues().get(materialName);
        if (baseValue == null) {
            if (settings.requireItemValueRule()) {
                return ItemValue.rejected("no-value-rule");
            }
            baseValue = fallbackValue(material);
        }
        ItemStack normalized = item.clone();
        normalized.setAmount(Math.min(item.getAmount(), jackpot.maxItemsPerEntry()));
        double value = baseValue * normalized.getAmount();
        value += enchantmentBonus(item, baseValue);
        value += displayBonus(item, baseValue);
        if (value < jackpot.minItemValue()) {
            return ItemValue.rejected("below-min-value");
        }
        return new ItemValue(true, value, "accepted", normalized);
    }

    private double fallbackValue(Material material) {
        String name = material.name();
        if (name.contains("NETHERITE")) {
            return 1000.0;
        }
        if (name.contains("DIAMOND")) {
            return 250.0;
        }
        if (name.contains("EMERALD")) {
            return 200.0;
        }
        if (name.contains("GOLD")) {
            return 75.0;
        }
        if (name.contains("IRON")) {
            return 25.0;
        }
        return Math.max(1.0, material.getMaxDurability() > 0 ? 15.0 : 5.0);
    }

    private double enchantmentBonus(ItemStack item, double baseValue) {
        double bonus = 0.0;
        for (Map.Entry<Enchantment, Integer> enchantment : item.getEnchantments().entrySet()) {
            bonus += baseValue * 0.05 * Math.max(1, enchantment.getValue());
        }
        return bonus;
    }

    private double displayBonus(ItemStack item, double baseValue) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0.0;
        }
        double bonus = 0.0;
        if (meta.hasDisplayName()) {
            bonus += baseValue * 0.02;
        }
        if (meta.hasLore()) {
            bonus += baseValue * 0.02;
        }
        if (meta.hasCustomModelData()) {
            bonus += baseValue * 0.10;
        }
        return bonus;
    }
}


