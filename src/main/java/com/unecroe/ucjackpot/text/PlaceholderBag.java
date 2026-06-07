package com.unecroe.ucjackpot.text;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PlaceholderBag {
    private final Map<String, String> values = new LinkedHashMap<>();

    public PlaceholderBag put(String key, Object value) {
        values.put("%" + key + "%", String.valueOf(value));
        return this;
    }

    public String apply(String input) {
        String output = input == null ? "" : input;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            output = output.replace(entry.getKey(), entry.getValue());
        }
        return output;
    }
}


