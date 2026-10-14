package dev.guardianac.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationParserTest {
    @ParameterizedTest
    @CsvSource({"1s, 1000", "30m, 1800000", "24h, 86400000", "7d, 604800000", "2w, 1209600000",
            "1H, 3600000", "permanent, 0", "PERMANENT, 0"})
    void parsesExactDurations(String value, long expected) {
        assertEquals(expected, DurationParser.parse(value));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "0s", "0m", "-1h", "+1d", "01d", "1.5h", "30", "1ms", "1y", "1h30m",
            "forever", "perm", " 1h", "1h ", "-permanent", "1e3s"})
    void rejectsInvalidOrAmbiguousDurations(String value) {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(value));
    }

    @Test
    void acceptsLargestWholeSecondDurationWithoutRounding() {
        long seconds = Long.MAX_VALUE / 1_000L;
        assertEquals(seconds * 1_000L, DurationParser.parse(seconds + "s"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9223372036854776s", "9223372036854775807s", "9223372036854775808s",
            "999999999999999999999999999999999999999999w"})
    void rejectsBothNumericAndMultiplicationOverflow(String value) {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(value));
    }
}
