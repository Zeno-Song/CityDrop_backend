package com.citydrop.backend.cache;

public final class CacheKeyUtils {

    private static final int COORD_ROUND_SCALE = 5;

    private CacheKeyUtils() {
    }

    public static String quoteSnapshotKey(int userId) {
        return "quoteSnapshot:" + userId;
    }

    public static String geocodeKey(String address) {
        return "geocode:" + address.trim().toLowerCase();
    }

    public static String travelTimeKey(int stationId, double destCoordX, double destCoordY, String vehicle) {
        return "travelTime:" + stationId
                + ":" + roundCoord(destCoordX)
                + ":" + roundCoord(destCoordY)
                + ":" + vehicle;
    }

    private static String roundCoord(double value) {
        return String.format("%." + COORD_ROUND_SCALE + "f", value);
    }
}