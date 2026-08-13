package cn.choosec.economy.service;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Carpet fake-player detection for billing purposes.
 *
 * <p>Links to the Carpet mod without requiring a compile-time dependency:
 * fake players are detected by their runtime class name and the billing owner is
 * resolved by reflection (with a name-&gt;UUID fallback). This means the economy mod
 * bills players automatically for the fake players they have active — no manual
 * commands needed.
 *
 * <p>Because Carpet's exact fake-player class/owner fields vary by version, all
 * lookups are defensive (reflection, never throws). If an owner cannot be
 * determined, the fake player's own UUID is used as a fallback.
 */
public final class CarpetIntegration {

    private CarpetIntegration() {
    }

    private static final String LOGGER_REGISTRY_CLASS = "carpet.logging.LoggerRegistry";

    /** Cached reflective handle to Carpet's getPlayerSubscriptions (resolved lazily). */
    private static volatile Method playerSubscriptionsMethod;

    /**
     * True if the given player is subscribed to at least one Carpet log. Reads Carpet
     * 26.2's {@code carpet.logging.LoggerRegistry} via reflection (no compile-time
     * dependency, no config). The authoritative public static
     * {@code getPlayerSubscriptions(playerName)} returns the player's active
     * {@code {logger -> option}} map, or null if they have no subscriptions.
     */
    public static boolean hasAnyLogSubscription(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        try {
            Method m = playerSubscriptionsMethod;
            if (m == null) {
                Class<?> registryClass = Class.forName(LOGGER_REGISTRY_CLASS);
                m = playerSubscriptionsMethod = registryClass.getMethod("getPlayerSubscriptions", String.class);
            }
            Object v = m.invoke(null, player.getName().getString());
            return v instanceof Map<?, ?> subs && !subs.isEmpty();
        } catch (Exception ignored) {
            // Carpet not present or its internals changed -> not a Carpet-tab user.
            return false;
        }
    }

    /** True if the given online player is a Carpet fake player. */
    public static boolean isFakePlayer(ServerPlayer p) {
        if (p == null) {
            return false;
        }
        String name = p.getClass().getName();
        // Covers Carpet's EntityPlayerMPFake and similar fake-player classes.
        return name != null && name.toLowerCase().contains("fake");
    }

    /**
     * Best-effort owner UUID for a fake player. Tries common owner fields via
     * reflection, then falls back to the player name resolved against the server
     * name cache, then finally the fake player's own UUID.
     */
    public static UUID ownerOf(ServerPlayer fake, MinecraftServer server) {
        UUID reflected = reflectOwner(fake);
        if (reflected != null) {
            return reflected;
        }
        try {
            Optional<NameAndId> nid = server.services().nameToIdCache().get(fake.getName().getString());
            if (nid.isPresent()) {
                return nid.get().id();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return fake.getUUID();
    }

    /** Count active fake players grouped by their billing owner. */
    public static Map<UUID, Integer> fakeCounts(MinecraftServer server) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (server == null || server.getPlayerList() == null) {
            return counts;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isFakePlayer(p)) {
                UUID owner = ownerOf(p, server);
                counts.merge(owner, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static UUID reflectOwner(ServerPlayer fake) {
        // Try common field names across Carpet versions.
        Class<?> c = fake.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if (n.contains("owner") || n.contains("linkedplayer") || n.contains("creator")) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(fake);
                        if (v instanceof UUID u) {
                            return u;
                        }
                        if (v instanceof String s && !s.isEmpty()) {
                            try {
                                return UUID.fromString(s);
                            } catch (IllegalArgumentException ignored) {
                                // not a uuid string
                            }
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }
}
