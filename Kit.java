package com.kitbot;

public enum Kit {
    PVP("pvp"),
    TOTEMS("totems", "totem"),
    OBSIDIAN("obsidian", "obi", "obby", "obisidian"),
    RANDOM("random", "rand");

    private final String[] aliases;

    Kit(String... aliases) {
        this.aliases = aliases;
    }

    public String display() {
        return aliases[0];
    }

    private static String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z]", "");
    }

    /** True if the text (chat argument or sign line) refers to this kit. */
    public boolean matches(String text) {
        String n = normalize(text);
        if (n.isEmpty()) return false;
        for (String a : aliases) {
            if (n.equals(a) || n.equals(a + "kit")) return true;
        }
        return false;
    }

    public static Kit parse(String text) {
        for (Kit k : values()) if (k.matches(text)) return k;
        return null;
    }
}
