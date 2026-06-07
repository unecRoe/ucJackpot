package com.unecroe.ucjackpot.text;

import net.md_5.bungee.api.ChatColor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TextFormatter {
    private static final Pattern HEX_TAG = Pattern.compile("<#([A-Fa-f0-9]{6})>");
    private static final Pattern HEX_CLOSE_TAG = Pattern.compile("</#[A-Fa-f0-9]{6}>");
    private static final Pattern GRADIENT_TAG = Pattern.compile("</?gradient(?::#[A-Fa-f0-9]{6}){0,2}>");
    private static final Map<String, String> TAGS = new LinkedHashMap<>();

    static {
        TAGS.put("black", "0");
        TAGS.put("dark_blue", "1");
        TAGS.put("dark_green", "2");
        TAGS.put("dark_aqua", "3");
        TAGS.put("dark_red", "4");
        TAGS.put("dark_purple", "5");
        TAGS.put("gold", "6");
        TAGS.put("gray", "7");
        TAGS.put("dark_gray", "8");
        TAGS.put("blue", "9");
        TAGS.put("green", "a");
        TAGS.put("aqua", "b");
        TAGS.put("red", "c");
        TAGS.put("light_purple", "d");
        TAGS.put("yellow", "e");
        TAGS.put("white", "f");
        TAGS.put("bold", "l");
        TAGS.put("italic", "o");
        TAGS.put("underlined", "n");
        TAGS.put("strikethrough", "m");
        TAGS.put("obfuscated", "k");
        TAGS.put("reset", "r");
    }

    private TextFormatter() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String text = GRADIENT_TAG.matcher(input).replaceAll("");
        text = HEX_CLOSE_TAG.matcher(text).replaceAll("\u00a7r");
        Matcher matcher = HEX_TAG.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(ChatColor.of("#" + matcher.group(1)).toString()));
        }
        matcher.appendTail(buffer);
        text = buffer.toString();
        for (Map.Entry<String, String> entry : TAGS.entrySet()) {
            text = text.replace("<" + entry.getKey() + ">", "\u00a7" + entry.getValue());
            text = text.replace("</" + entry.getKey() + ">", "\u00a7r");
        }
        text = text.replace("<newline>", "\n");
        text = text.replace("<br>", "\n");
        text = ChatColor.translateAlternateColorCodes('&', text);
        return text;
    }

    public static String strip(String input) {
        return ChatColor.stripColor(color(input)).toLowerCase(Locale.ROOT);
    }
}


