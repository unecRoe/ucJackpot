package com.unecroe.ucjackpot.gui;

import java.util.List;

public record RoomGuiDefinition(
        String id,
        int slot,
        String material,
        String displayName,
        String name,
        List<String> lore,
        boolean glow
) {
}


