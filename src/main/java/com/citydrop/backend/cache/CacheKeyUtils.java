package com.citydrop.backend.cache;

public final class CacheKeyUtils {

    private CacheKeyUtils() {
    }

    public static String quoteSnapshotKey(int userId) {
        return "quoteSnapshot:" + userId;
    }
}
