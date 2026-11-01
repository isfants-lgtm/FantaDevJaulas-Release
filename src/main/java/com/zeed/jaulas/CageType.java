package com.zeed.jaulas;

public enum CageType {
    QUADRADA("quadrada"),
    REDONDA("redonda");

    public final String key;

    CageType(String key) {
        this.key = key;
    }

    public static CageType fromKey(String s) {
        for (CageType t : values()) {
            if (t.key.equalsIgnoreCase(s)) return t;
        }
        return null;
    }
}
