package cn.choosec.economy.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks players who clicked a market action and are now waiting to type a
 * number via chat. The next chat message from such a player is intercepted
 * (not broadcast) and parsed as a quantity or a price, depending on the action.
 */
public final class MarketInput {

    /** What the typed number means. */
    public enum Action {
        /** Buy {@code count} items from a SELL order. */
        BUY,
        /** Supply {@code count} items to fulfil a BUY order. */
        SUPPLY,
        /** Set a new unit price for the player's own listing. */
        REPRICE,
        /** Add {@code count} more items/quantity to the player's own listing. */
        RESTOCK
    }

    /** A pending typed-number request for a listing. */
    public record Pending(int orderId, Action action) {
    }

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private MarketInput() {
    }

    public static void setPending(UUID uuid, int orderId, Action action) {
        pending.put(uuid, new Pending(orderId, action));
    }

    /** Remove and return the pending request for a player, or null. */
    public static Pending poll(UUID uuid) {
        return pending.remove(uuid);
    }

    public static void clear(UUID uuid) {
        pending.remove(uuid);
    }
}
