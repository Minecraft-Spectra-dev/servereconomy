package cn.choosec.economy.service;

import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.util.MessageUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Preserved ServerRules tab-list + title features. Kept byte-for-byte compatible
 * with the original experience (same commands, same rendering, same behaviour).
 */
public final class PreservedService {

    /** Tab list header/footer (from config/serverrules.json). */
    public static String headerText = "";
    public static String footerText = "";

    /** Player UUID -> title prefix. Persisted in the economy database. */
    private static final Map<UUID, String> playerTitles = new ConcurrentHashMap<>();

    /** Per-player last-sent tab header/footer (plain text), so we only re-send the
     *  tab-list packet when the rendered content actually changes. Re-sending every
     *  tick would otherwise repeatedly overwrite whatever another mod (e.g. Carpet's
     *  log/tab display) has placed in the tab list. */
    private static final Map<UUID, String[]> lastTabSent = new ConcurrentHashMap<>();

    private PreservedService() {
    }

    public static String getTitle(UUID uuid) {
        return playerTitles.get(uuid);
    }

    public static void setTitle(UUID uuid, String title) {
        if (title == null || title.isEmpty()) {
            playerTitles.remove(uuid);
        } else {
            playerTitles.put(uuid, title);
        }
    }

    public static void removeTitle(UUID uuid) {
        playerTitles.remove(uuid);
    }

    public static boolean hasTitle(UUID uuid) {
        String t = playerTitles.get(uuid);
        return t != null && !t.isEmpty();
    }

    public static Map<UUID, String> titles() {
        return playerTitles;
    }

    /** Persist all current titles to the database (replaces the {@code titles} table content). */
    public static synchronized void saveTitles() {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM titles")) {
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO titles (uuid, title) VALUES (?, ?)")) {
                for (Map.Entry<UUID, String> e : playerTitles.entrySet()) {
                    ps.setString(1, e.getKey().toString());
                    ps.setString(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    /** Load persisted titles from the database into memory. Call at startup. */
    public static synchronized void loadTitles() {
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement("SELECT uuid, title FROM titles");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    setTitle(uuid, rs.getString("title"));
                } catch (IllegalArgumentException ignored) {
                    // skip malformed rows
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    /** Placeholders recognised in the tab header/footer (kept in sync with MessageUtil). */
    private static final String[] PLACEHOLDERS = {
            "%player%", "%ping%", "%online%", "%max%", "%health%", "%maxhealth%",
            "%x%", "%y%", "%z%", "%xp%", "%balance%", "%currency%"
    };

    /** True if the configured tab header or footer contains any live placeholder. */
    public static boolean hasPlaceholders() {
        return containsPlaceholder(headerText) || containsPlaceholder(footerText);
    }

    private static boolean containsPlaceholder(String text) {
        if (text == null) {
            return false;
        }
        for (String p : PLACEHOLDERS) {
            if (text.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** Title + player name, as used by the display-name mixin. */
    public static Component buildTitleDisplayName(ServerPlayer player) {
        String title = playerTitles.get(player.getUUID());
        if (title != null && !title.isEmpty()) {
            Component prefix = MessageUtil.parse(title);
            return prefix.copy().append(Component.literal(player.getName().getString()));
        }
        return player.getName();
    }

    /** Update custom name + push a display-name update packet to all online players. */
    public static void updatePlayerDisplayName(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        String title = playerTitles.get(player.getUUID());
        player.setCustomName(title != null && !title.isEmpty() ? buildTitleDisplayName(player) : null);
        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME),
                Collections.singletonList(player));
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            online.connection.send(packet);
        }
    }

    public static void updateTabForPlayer(ServerPlayer player) {
        // Per-player Carpet priority: if this player has any active Carpet log
        // subscription, leave the tab entirely to Carpet. The client is last-writer-wins,
        // so this is the only way to guarantee Carpet's tab rendering is never overwritten
        // for that player (including by the periodic per-second ticker). Everyone else
        // keeps this mod's tab header/footer.
        if (playerDefersToCarpet(player)) {
            // Forget what we last sent so a later Carpet unsubscribe (or a Carpet-side
            // tab reset) always gets a fresh packet from us instead of being skipped
            // by the unchanged-content guard below.
            lastTabSent.remove(player.getUUID());
            return;
        }
        boolean hasHeader = headerText != null && !headerText.isEmpty();
        boolean hasFooter = footerText != null && !footerText.isEmpty();
        if (!hasHeader && !hasFooter) {
            // Nothing configured to render: don't send an empty tab header/footer,
            // which would clobber whatever another mod (e.g. Carpet's log/state
            // display) has placed in the tab list.
            return;
        }
        Component header = MessageUtil.resolveVariables(headerText, player);
        Component footer = MessageUtil.resolveVariables(footerText, player);
        // Only send when the resolved content actually differs from the last packet
        // sent to this player. The ticker calls this every second; without this guard
        // it would re-send and repeatedly overwrite Carpet's log/tab rendering even
        // when nothing in our header/footer changed.
        String headerTextVal = header.getString();
        String footerTextVal = footer.getString();
        String[] last = lastTabSent.get(player.getUUID());
        if (last != null && headerTextVal.equals(last[0]) && footerTextVal.equals(last[1])) {
            return;
        }
        player.connection.send(new ClientboundTabListPacket(header, footer));
        lastTabSent.put(player.getUUID(), new String[]{headerTextVal, footerTextVal});
    }

    /** True if this player's tab should be left entirely to Carpet: they are actively
     *  subscribed to at least one Carpet log. Read straight from Carpet's logger
     *  registry (no config needed). */
    public static boolean playerDefersToCarpet(ServerPlayer player) {
        return CarpetIntegration.hasAnyLogSubscription(player);
    }

    /** Forget the cached tab content for a player (call on disconnect). */
    public static void clearTabCache(UUID uuid) {
        if (uuid != null) {
            lastTabSent.remove(uuid);
        }
    }
}
