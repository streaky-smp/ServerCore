package com.streakysmp.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Money parsing is the plugin's outermost defence against a hostile amount
 * reaching the economy, so the rejection cases matter more here than the happy
 * path.
 */
class NumbersTest {

    private static final int CENTS = 2;

    @Nested
    @DisplayName("parseMoney accepts")
    class Accepts {

        @Test
        void wholeAmounts() {
            assertEquals(Optional.of(500_00L), Numbers.parseMoney("500", CENTS));
        }

        @Test
        void decimalAmounts() {
            assertEquals(Optional.of(4_50L), Numbers.parseMoney("4.50", CENTS));
            assertEquals(Optional.of(4_05L), Numbers.parseMoney("4.05", CENTS));
        }

        @Test
        void zero() {
            assertEquals(Optional.of(0L), Numbers.parseMoney("0", CENTS));
        }

        @Test
        void groupingSeparatorsAndCurrencySymbol() {
            // Players type back what the GUI showed them.
            assertEquals(Optional.of(25_000_00L), Numbers.parseMoney("25,000", CENTS));
            assertEquals(Optional.of(25_000_00L), Numbers.parseMoney("$25,000", CENTS));
            assertEquals(Optional.of(5_000_00L), Numbers.parseMoney("  5000  ", CENTS));
        }

        @Test
        void zeroDecimalCurrency() {
            assertEquals(Optional.of(500L), Numbers.parseMoney("500", 0));
        }
    }

    @Nested
    @DisplayName("parseMoney rejects")
    class Rejects {

        @Test
        void negatives() {
            // A negative "payment" is a withdrawal from the recipient.
            assertTrue(Numbers.parseMoney("-1", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("-0.01", CENTS).isEmpty());
        }

        @Test
        void scientificNotation() {
            // BigDecimal would happily accept these and produce an astronomical
            // balance.
            assertTrue(Numbers.parseMoney("1e9", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("1E999999", CENTS).isEmpty());
        }

        @Test
        void moreDecimalPlacesThanTheCurrencyHas() {
            // Rejected rather than rounded: the player must be charged the amount
            // they typed or told why not.
            assertTrue(Numbers.parseMoney("1.005", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("1.5", 0).isEmpty());
        }

        @Test
        void nonNumericAndEmptyInput() {
            assertTrue(Numbers.parseMoney("abc", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("   ", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney(null, CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("1.2.3", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("NaN", CENTS).isEmpty());
            assertTrue(Numbers.parseMoney("Infinity", CENTS).isEmpty());
        }

        @Test
        void absurdlyLongDigitStrings() {
            // Guards against a pasted 10KB number pinning a thread in BigDecimal.
            assertTrue(Numbers.parseMoney("9".repeat(100), CENTS).isEmpty());
        }

        @Test
        void valuesBeyondTheSupportedMagnitude() {
            // 15 digits is the accepted ceiling; converting that to minor units
            // still fits comfortably in a long. Anything longer is refused
            // outright rather than being allowed to approach the overflow edge.
            assertTrue(Numbers.parseMoney("9".repeat(16), CENTS).isEmpty());
            assertEquals(Optional.of(99_999_999_999_999_900L),
                    Numbers.parseMoney("9".repeat(15), CENTS),
                    "the documented ceiling must remain parseable and exact");
        }
    }

    @Nested
    @DisplayName("exact arithmetic")
    class ExactArithmetic {

        @Test
        void addingNormally() {
            assertEquals(300L, Numbers.addExact(100L, 200L));
        }

        @Test
        void overflowThrowsRatherThanWrapping() {
            // Wrapping is how a balance silently becomes negative.
            assertThrows(ArithmeticException.class, () -> Numbers.addExact(Long.MAX_VALUE, 1L));
            assertThrows(ArithmeticException.class, () -> Numbers.subtractExact(Long.MIN_VALUE, 1L));
            assertThrows(ArithmeticException.class, () -> Numbers.multiplyExact(Long.MAX_VALUE, 2L));
        }

        @Test
        void multiplyingQuantityByUnitPrice() {
            // The shape every shop purchase uses.
            assertEquals(64 * 12_00L, Numbers.multiplyExact(64, 12_00L));
        }

        @Test
        void clampKeepsValuesInRange() {
            assertEquals(10L, Numbers.clamp(5L, 10L, 20L));
            assertEquals(20L, Numbers.clamp(50L, 10L, 20L));
            assertEquals(15L, Numbers.clamp(15L, 10L, 20L));
        }
    }
}
