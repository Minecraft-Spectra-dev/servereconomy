package cn.choosec.economy.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the BigDecimal money helpers. */
class MoneyUtilTest {

    private static final int ORIGINAL_SCALE = MoneyUtil.SCALE;

    @AfterEach
    void restoreScale() {
        MoneyUtil.SCALE = ORIGINAL_SCALE;
    }

    @Test
    void normNullIsZero() {
        MoneyUtil.SCALE = 2;
        assertEquals(new BigDecimal("0.00"), MoneyUtil.norm((BigDecimal) null));
    }

    @Test
    void normRoundsHalfUp() {
        MoneyUtil.SCALE = 2;
        assertEquals(new BigDecimal("1.24"), MoneyUtil.norm(new BigDecimal("1.235")));
        assertEquals(new BigDecimal("1.23"), MoneyUtil.norm(new BigDecimal("1.234")));
        assertEquals(new BigDecimal("1.01"), MoneyUtil.norm(1.005));
    }

    @Test
    void normInvalidStringIsZero() {
        MoneyUtil.SCALE = 2;
        assertEquals(new BigDecimal("0.00"), MoneyUtil.norm("not-a-number"));
        assertEquals(new BigDecimal("3.50"), MoneyUtil.norm("3.5"));
    }

    @Test
    void percentageHelpers() {
        MoneyUtil.SCALE = 2;
        assertEquals(new BigDecimal("2.00"), MoneyUtil.percent(new BigDecimal("100"), new BigDecimal("2")));
        assertEquals(new BigDecimal("98.00"), MoneyUtil.minusPercent(new BigDecimal("100"), new BigDecimal("2")));
    }

    @Test
    void minUnitFollowsScale() {
        MoneyUtil.SCALE = 2;
        assertEquals(new BigDecimal("0.01"), MoneyUtil.minUnit());
        MoneyUtil.SCALE = 0;
        assertEquals(new BigDecimal("1"), MoneyUtil.minUnit());
    }

    @Test
    void formatStripsTrailingZeros() {
        MoneyUtil.SCALE = 2;
        assertEquals("1.5", MoneyUtil.format(new BigDecimal("1.50")));
        assertEquals("2", MoneyUtil.format(new BigDecimal("2.00")));
        assertEquals("0", MoneyUtil.format(BigDecimal.ZERO));
    }
}
