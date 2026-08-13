package cn.choosec.economy.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the chat-quantity pending-input registry. */
class MarketInputTest {

    @Test
    void pollRemovesAndClearDropsPending() {
        UUID uuid = UUID.randomUUID();
        MarketInput.setPending(uuid, 42, MarketInput.Action.SUPPLY);

        MarketInput.Pending pending = MarketInput.poll(uuid);
        assertNotNull(pending);
        assertEquals(42, pending.orderId());
        assertEquals(MarketInput.Action.SUPPLY, pending.action());
        assertNull(MarketInput.poll(uuid));

        MarketInput.setPending(uuid, 7, MarketInput.Action.BUY);
        MarketInput.clear(uuid);
        assertNull(MarketInput.poll(uuid));
    }
}
