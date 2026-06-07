package com.unecroe.ucjackpot.gui;

import java.util.List;
import java.util.Map;

public record GuiDefinition(
        String id,
        String title,
        int size,
        String fillerMaterial,
        String fillerName,
        Map<Integer, GuiItemDefinition> items,
        Map<String, RoomGuiDefinition> rooms,
        Map<String, List<Integer>> dynamicSlots,
        Map<String, String> sounds,
        String historyMaterial,
        String historyName,
        List<String> historyLore
) {
}


