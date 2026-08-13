package cn.choosec.economy.service;

import cn.choosec.economy.economy.MoneyUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the red-packet split math (kept pure via grabAmount). */
class RedPacketServiceTest {

    @Test
    void lastGrabTakesTheRemainder() {
        MoneyUtil.SCALE = 2;
        BigDecimal remaining = new BigDecimal("3.33");
        assertEquals(remaining, RedPacketService.grabAmount(remaining, 1, false));
        assertEquals(remaining, RedPacketService.grabAmount(remaining, 1, true));
    }

    @Test
    void normalSplitNeverPaysZeroAndSumsToTotal() {
        MoneyUtil.SCALE = 2;
        BigDecimal remaining = new BigDecimal("10.00");
        BigDecimal paid = BigDecimal.ZERO;
        for (int left = 3; left >= 1; left--) {
            BigDecimal grab = RedPacketService.grabAmount(remaining, left, false);
            assertTrue(grab.compareTo(MoneyUtil.minUnit()) >= 0, "grab must be >= min unit");
            assertTrue(grab.compareTo(remaining) <= 0, "grab must not exceed remainder");
            paid = paid.add(grab);
            remaining = remaining.subtract(grab);
        }
        assertEquals(new BigDecimal("0.00"), remaining);
        assertEquals(new BigDecimal("10.00"), paid);
    }

    @Test
    void luckySplitStaysWithinRemainderAndConsumesPacket() {
        MoneyUtil.SCALE = 2;
        for (int trial = 0; trial < 200; trial++) {
            BigDecimal remaining = new BigDecimal("50.00");
            for (int left = 5; left > 1; left--) {
                BigDecimal grab = RedPacketService.grabAmount(remaining, left, true);
                assertTrue(grab.compareTo(BigDecimal.ZERO) >= 0, "grab must not be negative");
                assertTrue(grab.compareTo(remaining) <= 0, "grab must not exceed remainder");
                remaining = remaining.subtract(grab);
            }
            assertEquals(remaining, RedPacketService.grabAmount(remaining, 1, true));
        }
    }
}
