package cn.choosec.economy.command;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.service.LandmarkService;
import cn.choosec.economy.service.TradeService;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Central suggestion providers, wired into commands to reduce manual typing for
 * admins/players wherever we have known IDs/names.
 */
public final class EcoSuggestions {

    private EcoSuggestions() {
    }

    private static CompletableFuture<Suggestions> of(List<String> values, SuggestionsBuilder b) {
        return SharedSuggestionProvider.suggest(values, b);
    }

    /**
     * Suggest landmark names without Brigadier's automatic quoting, so typed CJK
     * prefixes still match. Names containing whitespace are quoted instead.
     */
    public static CompletableFuture<Suggestions> landmarkNames(Collection<String> names, SuggestionsBuilder b) {
        for (String name : names) {
            boolean hasWhitespace = name.codePoints().anyMatch(Character::isWhitespace);
            b.suggest(hasWhitespace
                    ? com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(name)
                    : name);
        }
        return b.buildFuture();
    }

    /** Currently configured sellable (recyclable) item ids. */
    public static CompletableFuture<Suggestions> sellableItems(SuggestionsBuilder b) {
        return of(ConfigManager.get().sellableItems.stream().map(s -> s.id).collect(Collectors.toList()), b);
    }

    /** Online players + all known account names (for offline-capable admin /eco targeting). */
    public static CompletableFuture<Suggestions> playerNames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        if (server != null) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                names.add(p.getName().getString());
            }
        }
        names.addAll(EconomyService.accountNames());
        return of(new ArrayList<>(names), b);
    }

    /** The entire item registry, for /eco recycle add (candidate = whole item set). */
    public static CompletableFuture<Suggestions> itemSet(SuggestionsBuilder b) {
        List<String> ids = new ArrayList<>();
        for (Object key : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
            if (key instanceof net.minecraft.resources.Identifier id) {
                ids.add(id.toString());
            }
        }
        java.util.Collections.sort(ids);
        return of(ids, b);
    }

    /** Existing public landmark names. */
    public static CompletableFuture<Suggestions> publicLandmarks(SuggestionsBuilder b) {
        return landmarkNames(LandmarkService.listPublic().stream().map(lm -> lm.name()).toList(), b);
    }

    /** Active market listing ids. */
    public static CompletableFuture<Suggestions> listingIds(SuggestionsBuilder b) {
        return of(TradeService.listListings().stream().map(l -> String.valueOf(l.id())).collect(Collectors.toList()), b);
    }

    /** Active market listing ids owned by the player executing the command. */
    public static CompletableFuture<Suggestions> myListingIds(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            return of(List.of(), b);
        }
        return of(TradeService.listBySeller(p.getUUID()).stream()
                .map(l -> String.valueOf(l.id())).collect(Collectors.toList()), b);
    }

    /** Existing sellable/task targets + curated target list by task type. */
    public static CompletableFuture<Suggestions> taskTargets(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        String type = "";
        try {
            type = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "type").toLowerCase();
        } catch (Exception ignored) {
        }
        List<String> targets = new ArrayList<>();
        switch (type) {
            // All candidates are read dynamically from the registries so nothing is
            // hardcoded (bosses like ender_dragon included, new content auto-appears).
            case "kill" -> {
                for (Object key : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.keySet()) {
                    if (key instanceof net.minecraft.resources.Identifier id) {
                        targets.add(id.toString());
                    }
                }
                java.util.Collections.sort(targets);
            }
            case "mine" -> {
                for (Object key : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) {
                    if (key instanceof net.minecraft.resources.Identifier id) {
                        targets.add(id.toString());
                    }
                }
                java.util.Collections.sort(targets);
            }
            case "use", "consume" -> {
                // any item can be "used" (bow, fishing rod, food, tools, ...), so the whole item set is a candidate
                for (Object key : net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()) {
                    if (key instanceof net.minecraft.resources.Identifier id) {
                        targets.add(id.toString());
                    }
                }
                java.util.Collections.sort(targets);
            }
            default -> {
            }
        }
        for (cn.choosec.economy.service.TaskService.Task t : cn.choosec.economy.service.TaskService.listTasks()) {
            if (t.target() != null && !targets.contains(t.target())) {
                targets.add(t.target());
            }
        }
        return of(targets, b);
    }
}
