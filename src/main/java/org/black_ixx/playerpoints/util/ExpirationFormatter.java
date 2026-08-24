package org.black_ixx.playerpoints.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ExpirationFormatter {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX");

    private ExpirationFormatter() {

    }

    public static String format(long epochMillis) {
        return format(epochMillis, ZoneId.systemDefault());
    }

    public static String format(long epochMillis, ZoneId zoneId) {
        return FORMATTER.withZone(zoneId).format(Instant.ofEpochMilli(epochMillis));
    }

}
