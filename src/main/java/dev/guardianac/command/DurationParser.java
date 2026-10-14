package dev.guardianac.command;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses a single positive duration without rounding, saturation, or overflow. */
public final class DurationParser {
    /** The ban service uses zero milliseconds to represent a permanent ban. */
    public static final long PERMANENT = 0L;

    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)([smhdw])");
    private static final String HELP = "Use permanent or a positive duration such as 30m, 24h, or 7d.";

    private DurationParser() {
    }

    public static long parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException(HELP);
        }
        String value = input.toLowerCase(Locale.ROOT);
        if (value.equals("permanent")) {
            return PERMANENT;
        }
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(HELP);
        }

        long unitMillis = switch (matcher.group(2)) {
            case "s" -> 1_000L;
            case "m" -> 60_000L;
            case "h" -> 3_600_000L;
            case "d" -> 86_400_000L;
            case "w" -> 604_800_000L;
            default -> throw new IllegalArgumentException(HELP);
        };
        try {
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), unitMillis);
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("That duration is too large.", exception);
        }
    }
}
