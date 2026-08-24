package org.black_ixx.playerpoints.util;

import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DurationParser {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("(\\d+)([smhdw])", Pattern.CASE_INSENSITIVE);

    private DurationParser() {

    }

    public static OptionalLong parseMillis(String input) {
        if (input == null || input.isEmpty()) {
            return OptionalLong.empty();
        }

        Matcher matcher = SEGMENT_PATTERN.matcher(input);
        int position = 0;
        long total = 0;
        try {
            while (matcher.find()) {
                if (matcher.start() != position) {
                    return OptionalLong.empty();
                }
                long value = Long.parseLong(matcher.group(1));
                long multiplier = getMultiplier(matcher.group(2).charAt(0));
                total = Math.addExact(total, Math.multiplyExact(value, multiplier));
                position = matcher.end();
            }
        } catch (NumberFormatException | ArithmeticException e) {
            return OptionalLong.empty();
        }
        return position == input.length() && total > 0 ? OptionalLong.of(total) : OptionalLong.empty();
    }

    public static String formatMillis(long durationMillis) {
        if (durationMillis < TimeUnit.SECONDS.toMillis(1)) {
            throw new IllegalArgumentException("Duration must contain at least one whole second");
        }

        StringBuilder result = new StringBuilder();
        long remaining = durationMillis;
        long weeks = remaining / TimeUnit.DAYS.toMillis(7);
        remaining %= TimeUnit.DAYS.toMillis(7);
        long days = remaining / TimeUnit.DAYS.toMillis(1);
        remaining %= TimeUnit.DAYS.toMillis(1);
        long hours = remaining / TimeUnit.HOURS.toMillis(1);
        remaining %= TimeUnit.HOURS.toMillis(1);
        long minutes = remaining / TimeUnit.MINUTES.toMillis(1);
        remaining %= TimeUnit.MINUTES.toMillis(1);
        long seconds = remaining / TimeUnit.SECONDS.toMillis(1);

        append(result, weeks, 'w');
        append(result, days, 'd');
        append(result, hours, 'h');
        append(result, minutes, 'm');
        append(result, seconds, 's');
        return result.toString();
    }

    private static long getMultiplier(char unit) {
        switch (Character.toLowerCase(unit)) {
            case 's':
                return TimeUnit.SECONDS.toMillis(1);
            case 'm':
                return TimeUnit.MINUTES.toMillis(1);
            case 'h':
                return TimeUnit.HOURS.toMillis(1);
            case 'd':
                return TimeUnit.DAYS.toMillis(1);
            case 'w':
                return TimeUnit.DAYS.toMillis(7);
            default:
                throw new IllegalArgumentException("Unknown duration unit: " + unit);
        }
    }

    private static void append(StringBuilder result, long value, char unit) {
        if (value > 0) {
            result.append(value).append(unit);
        }
    }

}
