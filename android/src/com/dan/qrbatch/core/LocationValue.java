package com.dan.qrbatch.core;

/** Optional protocol value. The timetable's sign-in module always supplies an empty value. */
public final class LocationValue {
    public final String longitude;
    public final String latitude;
    public final String address;
    public final String altitude;

    private LocationValue(String longitude, String latitude, String address, String altitude) {
        this.longitude = longitude;
        this.latitude = latitude;
        this.address = address;
        this.altitude = altitude;
    }

    public static LocationValue empty() { return new LocationValue("", "", "", ""); }

    public static LocationValue of(String longitude, String latitude, String address, String altitude) {
        String lon = value(longitude);
        String lat = value(latitude);
        if (lon.isEmpty() && lat.isEmpty()) return empty();
        if (lon.isEmpty() || lat.isEmpty()) {
            throw new IllegalArgumentException("经度和纬度必须同时提供，或同时留空。");
        }
        double longitudeNumber = number(lon, "经度");
        double latitudeNumber = number(lat, "纬度");
        if (longitudeNumber < -180 || longitudeNumber > 180) {
            throw new IllegalArgumentException("经度须在 -180 到 180 之间。");
        }
        if (latitudeNumber < -90 || latitudeNumber > 90) {
            throw new IllegalArgumentException("纬度须在 -90 到 90 之间。");
        }
        String alt = value(altitude);
        if (!alt.isEmpty()) number(alt, "海拔");
        return new LocationValue(lon, lat, value(address), alt);
    }

    public boolean isPresent() { return !longitude.isEmpty() && !latitude.isEmpty(); }

    private static String value(String value) { return value == null ? "" : value.trim(); }

    private static double number(String value, String label) {
        if (!value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")) {
            throw new IllegalArgumentException(label + "必须是有效数值。");
        }
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                throw new IllegalArgumentException(label + "必须是有限数值。");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + "必须是有效数值。", error);
        }
    }
}
