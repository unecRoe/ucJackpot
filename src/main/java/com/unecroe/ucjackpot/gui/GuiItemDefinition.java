package com.unecroe.ucjackpot.gui;

import java.util.List;

public record GuiItemDefinition(
        String id,
        int slot,
        String material,
        String head,
        String name,
        List<String> lore,
        String action,
        boolean glow,
        int customModelData
) {
}


