package com.unecroe.ucjackpot.config;

import java.util.Locale;

public enum JackpotMode {
    MONEY,
    ITEM,
    HYBRID;

    public static JackpotMode parse(String value) {
        try {
            return JackpotMode.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return HYBRID;
        }
    }
}


