package cn.choosec.economy.util;

import cn.choosec.economy.config.ConfigManager;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.io.Reader;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Task-name resolution.
 *
 * <p>Mod-owned translation keys ({@code servereconomy.task.type.*}) are resolved
 * server-side to actual text, because a vanilla client that does not have the mod
 * installed would otherwise show the raw key. Item/block/entity names keep using
 * vanilla translation keys and are sent to the client, which has the vanilla
 * language files and renders them correctly.
 */
public final class TaskNames {

    private TaskNames() {
    }

    /** Translated label for a task type (kill/mine/use/consume/reach) — client-rendered (chat/menus). */
    public static Component type(String type) {
        return Component.translatable("servereconomy.task.type." + type);
    }

    /** Translated name for a task target (item/block/entity), raw fallback for coords — client-rendered. */
    public static Component target(String type, String target) {
        try {
            Identifier id = Identifier.parse(target);
            if ("use".equalsIgnoreCase(type) || "consume".equalsIgnoreCase(type)) {
                Item item = BuiltInRegistries.ITEM.getValue(id);
                if (item != null) return Component.translatable(item.getDescriptionId());
            } else if ("mine".equalsIgnoreCase(type)) {
                Block block = BuiltInRegistries.BLOCK.getValue(id);
                if (block != null) return Component.translatable(block.getDescriptionId());
            } else if ("kill".equalsIgnoreCase(type)) {
                EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.getValue(id);
                if (et != null) return Component.translatable(et.getDescriptionId());
            }
        } catch (Exception ignored) {
        }
        return Component.literal(target);
    }

    /** Chinese type labels (fallback when the mod language file cannot be loaded). */
    private static final Map<String, String> ZH_TYPE = Map.of(
            "kill", "击杀", "mine", "挖掘", "use", "使用", "consume", "消耗", "reach", "到达");

    // ------------------------------------------------------------------
    // Server-side resolution of MOD task-type keys (for the scoreboard).
    // ------------------------------------------------------------------

    private static final Gson GSON = new Gson();
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    private static MinecraftServer lastServer;
    private static String loadedLang = "";
    /** Task-type labels loaded from the mod's own language file. */
    private static final Map<String, String> typeLang = new HashMap<>();

    /**
     * Load the configured scoreboard language's task-type labels from the mod's
     * language file. Cheap after the first call; safe to call frequently.
     */
    public static synchronized void load(MinecraftServer server) {
        if (server == null) {
            return;
        }
        String lang = ConfigManager.get().scoreboardLanguage;
        if (lang == null) {
            lang = "en_us";
        }
        if (server == lastServer && lang.equals(loadedLang)) {
            return;
        }
        lastServer = server;
        loadedLang = lang;
        typeLang.clear();
        if ("en_us".equals(lang) || lang.isEmpty()) {
            return; // English falls back to the server's built-in English resolution
        }
        loadInto(server, "servereconomy", "lang/" + lang + ".json");
    }

    private static void loadInto(MinecraftServer server, String namespace, String path) {
        try (Reader reader = server.getResourceManager()
                .openAsReader(Identifier.fromNamespaceAndPath(namespace, path))) {
            Map<String, String> parsed = GSON.fromJson(reader, STRING_MAP_TYPE);
            if (parsed != null) {
                typeLang.putAll(parsed);
            }
        } catch (Exception ignored) {
            // Resource missing / unparsable -> fall back to the hardcoded map.
        }
    }

    /**
     * Server-resolved task-type label for the scoreboard (mod keys are rendered
     * server-side rather than sent to the client). For English, resolves against
     * the server's built-in English.
     */
    public static String typeLabel(String type, boolean zh) {
        if (zh) {
            String t = typeLang.get("servereconomy.task.type." + type.toLowerCase());
            if (t != null && !t.isEmpty()) {
                return t;
            }
            return ZH_TYPE.getOrDefault(type.toLowerCase(), type);
        }
        return typeNameEn(type);
    }

    private static String typeNameEn(String type) {
        try {
            return Component.translatable("servereconomy.task.type." + type).getString();
        } catch (Exception e) {
            return type;
        }
    }

    /**
     * Full server-side scoreboard task component: the mod task-type key is resolved
     * to a literal string server-side, while the vanilla item/block/entity name is
     * kept as a translatable key that the client renders. Used by the scoreboard,
     * which must not receive raw mod keys.
     */
    public static MutableComponent taskComponent(String type, String target, boolean zh,
                                                  net.minecraft.network.chat.Style style) {
        MutableComponent c = Component.empty();
        c.append(Component.literal(typeLabel(type, zh)).withStyle(style));
        c.append(Component.literal(" ").withStyle(style));
        if ("reach".equalsIgnoreCase(type)) {
            c.append(Component.literal(target).withStyle(style));
        } else {
            c.append(target(type, target).copy().withStyle(style));
        }
        return c;
    }
}
