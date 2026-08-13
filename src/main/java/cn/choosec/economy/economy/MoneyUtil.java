package cn.choosec.economy.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money formatting / arithmetic helpers. All money uses the configurable {@link #SCALE}. */
public final class MoneyUtil {

    /** Money scale (decimal places); set from config at startup (currencyDecimals). */
    public static volatile int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyUtil() {
    }

    /** Normalize a value to the standard money scale. */
    public static BigDecimal norm(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        }
        return value.setScale(SCALE, ROUNDING);
    }


    public static BigDecimal norm(double value) {
        return norm(BigDecimal.valueOf(value));
    }

    public static BigDecimal norm(String value) {
        try {
            return norm(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO.setScale(SCALE, ROUNDING);
        }
    }

    /** Smallest representable money unit for the configured scale, e.g. 0.01 at scale 2. */
    public static BigDecimal minUnit() {
        return BigDecimal.ONE.movePointLeft(SCALE);
    }

    /** Percentage helper: amount - amount * pct/100. */
    public static BigDecimal minusPercent(BigDecimal amount, BigDecimal percent) {
        BigDecimal fee = norm(amount.multiply(percent).divide(new BigDecimal("100"), SCALE, ROUNDING));
        return norm(amount.subtract(fee));
    }

    /** Percentage helper: amount * pct/100. */
    public static BigDecimal percent(BigDecimal amount, BigDecimal percent) {
        return norm(amount.multiply(percent).divide(new BigDecimal("100"), SCALE, ROUNDING));
    }

    public static String format(BigDecimal value) {
        return norm(value).stripTrailingZeros().toPlainString();
    }
}
