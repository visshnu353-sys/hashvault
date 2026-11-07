package io.hashvault.store;

/** Storage codec policy. AUTO preserves the previous compression-if-smaller behavior. */
public enum CompressionCodec {
    AUTO, NONE, GZIP;

    public static CompressionCodec parse(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "auto" -> AUTO;
            case "none", "raw" -> NONE;
            case "gzip" -> GZIP;
            default -> throw new IllegalArgumentException("Compression must be auto, none, or gzip");
        };
    }
}
