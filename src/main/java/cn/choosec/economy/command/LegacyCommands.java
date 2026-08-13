package cn.choosec.economy.command;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.model.HomeLocation;
import cn.choosec.economy.service.LandmarkService;
import cn.choosec.economy.service.PreservedService;
import cn.choosec.economy.util.MessageUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Preserved ServerRules commands. Behaviour and messages match the original mod
 * exactly so the existing player experience is unchanged.
 */
public final class LegacyCommands {

    private static final long TPA_TIMEOUT_MS = 120_000L;
    private static final Map<UUID, TpaRequest> pendingTpa = new ConcurrentHashMap<>();
    private static final Map<UUID, HomeLocation> backLocations = new ConcurrentHashMap<>();

    private LegacyCommands() {
    }

    private record TpaRequest(UUID from, UUID to, boolean senderGoesToTarget, long expiry) {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        // /serverrules tab|title
        d.register(Commands.literal("serverrules")
                .then(Commands.literal("tab")
                        .then(Commands.argument("line", IntegerArgumentType.integer(1, 2))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> executeTab(ctx)))))
                .then(Commands.literal("title")
                        .then(Commands.argument("target", EntityArgument.players())
                                .then(Commands.literal("set")
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(ctx -> executeTitleSet(ctx))))
                                .then(Commands.literal("clear").executes(ctx -> executeTitleClear(ctx))))));

        // /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel
        d.register(Commands.literal("tpa")
                .then(Commands.argument("target", EntityArgument.player()).executes(ctx -> executeTpa(ctx))
                        .then(Commands.literal("@s").executes(ctx -> executeTpaInvite(ctx)))));
        d.register(Commands.literal("tpahere")
                .then(Commands.argument("target", EntityArgument.player()).executes(ctx -> executeTpaInvite(ctx))));
        d.register(Commands.literal("tpaccept").executes(ctx -> executeTpaAccept(ctx)));
        d.register(Commands.literal("tpdeny").executes(ctx -> executeTpaDeny(ctx)));
        d.register(Commands.literal("tpacancel").executes(ctx -> executeTpaCancel(ctx)));

        // /home, /sethome, /delhome, /homes, /renamehome
        d.register(Commands.literal("home")
                .executes(ctx -> executeHomeTp(ctx))
                .then(Commands.argument("name", LandmarkNameArgumentType.name()).suggests(homeSuggestions())
                        .executes(ctx -> executeHomeTp(ctx))));
        d.register(Commands.literal("sethome")
                .executes(ctx -> executeHomeAdd(ctx))
                .then(Commands.argument("name", LandmarkNameArgumentType.name()).executes(ctx -> executeHomeAdd(ctx))));
        d.register(Commands.literal("delhome")
                .executes(ctx -> executeHomeDel(ctx))
                .then(Commands.argument("name", LandmarkNameArgumentType.name()).suggests(homeSuggestions())
                        .executes(ctx -> executeHomeDel(ctx))));
        d.register(Commands.literal("homes").executes(ctx -> executeHomeList(ctx)));
        d.register(Commands.literal("renamehome")
                .then(Commands.argument("old", LandmarkNameArgumentType.name()).suggests(homeSuggestions())
                        .then(Commands.argument("new", LandmarkNameArgumentType.name())
                                .executes(ctx -> executeHomeRename(ctx)))));

        // /back, /hat
        d.register(Commands.literal("back").executes(ctx -> executeBack(ctx)));
        d.register(Commands.literal("hat").executes(ctx -> executeHat(ctx)));
    }

    /* ---------------- tab ---------------- */

    private static int executeTab(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c需要管理员权限！");
            return 0;
        }
        int line = IntegerArgumentType.getInteger(ctx, "line");
        String text = StringArgumentType.getString(ctx, "text");
        if (line == 1) {
            PreservedService.headerText = text;
        } else {
            PreservedService.footerText = text;
        }
        ConfigManager.save();
        MinecraftServer server = src.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                PreservedService.updateTabForPlayer(player);
            }
        }
        String lineLabel = line == 1 ? "header" : "footer";
        CommandUtil.success(src, "&aServerRules: Tab " + lineLabel + " set successfully!");
        return 1;
    }

    /* ---------------- title ---------------- */

    private static int executeTitleSet(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c需要管理员权限！");
            return 0;
        }
        String text = StringArgumentType.getString(ctx, "text");
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "target");
        int count = 0;
        for (ServerPlayer target : targets) {
            PreservedService.setTitle(target.getUUID(), text);
            PreservedService.updatePlayerDisplayName(target);
            count++;
        }
        saveTitles();
        int finalCount = count;
        src.sendSuccess(() -> Component.literal("ServerRules: Set title for " + finalCount + " player(s)!"), true);
        return finalCount;
    }

    private static int executeTitleClear(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c需要管理员权限！");
            return 0;
        }
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "target");
        int count = 0;
        for (ServerPlayer target : targets) {
            PreservedService.removeTitle(target.getUUID());
            PreservedService.updatePlayerDisplayName(target);
            count++;
        }
        saveTitles();
        int finalCount = count;
        src.sendSuccess(() -> Component.literal("ServerRules: Cleared title for " + finalCount + " player(s)!"), true);
        return finalCount;
    }

    /* ---------------- tpa ---------------- */

    private static int executeTpa(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer sender = src.getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        if (sender.getUUID().equals(target.getUUID())) {
            CommandUtil.failure(src, "&c不能向自己发送传送请求！");
            return 0;
        }
        sendTpaRequest(sender, target, true);
        return 1;
    }

    private static int executeTpaInvite(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer sender = src.getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        if (sender.getUUID().equals(target.getUUID())) {
            CommandUtil.failure(src, "&c不能向自己发送传送请求！");
            return 0;
        }
        sendTpaRequest(sender, target, false);
        return 1;
    }

    private static int executeTpaAccept(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        UUID key = player.getUUID();
        TpaRequest req = pendingTpa.remove(key);
        if (req == null || isExpired(req)) {
            CommandUtil.failure(src, "&c你没有待处理的传送请求！");
            return 0;
        }
        MinecraftServer server = src.getServer();
        ServerPlayer from = server.getPlayerList().getPlayer(req.from);
        ServerPlayer to = server.getPlayerList().getPlayer(req.to);
        if (from == null) {
            CommandUtil.failure(src, "&c请求者已离线！");
            return 0;
        }
        if (req.senderGoesToTarget) {
            saveBackLocation(from);
            from.teleportTo(to.level(), to.getX(), to.getY(), to.getZ(), Set.of(), to.getYRot(), to.getXRot(), false);
            from.sendSystemMessage(MessageUtil.parse("&a传送请求已接受！你已被传送到 &e" + to.getName().getString()));
            CommandUtil.successQuiet(src, "&a已接受 " + from.getName().getString() + " 的传送请求！");
        } else {
            saveBackLocation(to);
            to.teleportTo(from.level(), from.getX(), from.getY(), from.getZ(), Set.of(), from.getYRot(), from.getXRot(), false);
            to.sendSystemMessage(MessageUtil.parse("&a传送请求已接受！你已被传送到 &e" + from.getName().getString()));
            CommandUtil.successQuiet(src, "&a已接受传送请求，" + to.getName().getString() + " 已传送到 " + from.getName().getString());
        }
        return 1;
    }

    private static int executeTpaDeny(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        UUID key = player.getUUID();
        TpaRequest req = pendingTpa.remove(key);
        if (req == null || isExpired(req)) {
            CommandUtil.failure(src, "&c你没有待处理的传送请求！");
            return 0;
        }
        ServerPlayer from = src.getServer().getPlayerList().getPlayer(req.from);
        if (from != null) {
            from.sendSystemMessage(MessageUtil.parse("&c" + player.getName().getString() + " 拒绝了你的传送请求！"));
        }
        CommandUtil.successQuiet(src, "&c已拒绝" + player.getName().getString() + " 的传送请求！");
        return 1;
    }

    private static int executeTpaCancel(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        TpaRequest found = null;
        UUID removeKey = null;
        for (Map.Entry<UUID, TpaRequest> entry : pendingTpa.entrySet()) {
            TpaRequest req = entry.getValue();
            if (req.from.equals(player.getUUID()) && !isExpired(req)) {
                found = req;
                removeKey = entry.getKey();
                break;
            }
        }
        if (found == null) {
            CommandUtil.failure(src, "&c你没有待取消的传送请求！");
            return 0;
        }
        pendingTpa.remove(removeKey);
        ServerPlayer target = src.getServer().getPlayerList().getPlayer(found.to);
        if (target != null) {
            target.sendSystemMessage(MessageUtil.parse("&c" + player.getName().getString() + " 取消了传送请求！"));
        }
        CommandUtil.successQuiet(src, "&c已取消传送请求！");
        return 1;
    }

    private static void sendTpaRequest(ServerPlayer sender, ServerPlayer target, boolean senderGoesToTarget) {
        UUID key = target.getUUID();
        TpaRequest old = pendingTpa.get(key);
        if (old != null && !isExpired(old)) {
            sender.sendSystemMessage(MessageUtil.parse("&6" + target.getName().getString() + " 当前有一个待处理的传送请求，请稍后再试！"));
            return;
        }
        TpaRequest req = new TpaRequest(sender.getUUID(), target.getUUID(), senderGoesToTarget,
                System.currentTimeMillis() + TPA_TIMEOUT_MS);
        pendingTpa.put(key, req);
        String desc = senderGoesToTarget
                ? sender.getName().getString() + " 请求传送到你这里"
                : sender.getName().getString() + " 邀请你传送到他那里";
        MutableComponent acceptBtn = Component.literal("    [同意]")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpaccept"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击同意传送"))));
        MutableComponent denyBtn = Component.literal(" [拒绝]")
                .withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true)
                        .withClickEvent(new ClickEvent.RunCommand("/tpdeny"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击拒绝传送"))));
        target.sendSystemMessage(MessageUtil.parse("&e=============================="));
        target.sendSystemMessage(MessageUtil.parse("&6" + desc));
        target.sendSystemMessage(Component.literal(""));
        target.sendSystemMessage(acceptBtn.copy().append(denyBtn));
        target.sendSystemMessage(MessageUtil.parse("&7（请求将在2分钟后过期）"));
        target.sendSystemMessage(MessageUtil.parse("&e=============================="));
        sender.sendSystemMessage(MessageUtil.parse("&a已向 &e" + target.getName().getString() + " &a发送传送请求！"));
    }

    private static boolean isExpired(TpaRequest req) {
        return System.currentTimeMillis() > req.expiry;
    }

    /* ---------------- homes ---------------- */

    private static SuggestionProvider<CommandSourceStack> homeSuggestions() {
        return (ctx, builder) -> {
            ServerPlayer player = ctx.getSource().getPlayer();
            if (player == null) {
                return builder.buildFuture();
            }
            Map<String, HomeLocation> homes = LandmarkService.listHomes(player.getUUID());
            return EcoSuggestions.landmarkNames(homes.keySet(), builder);
        };
    }

    private static int executeHomeAdd(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        String name = "name".equals(lastNode(ctx)) ? LandmarkNameArgumentType.getName(ctx, "name") : "home";
        if (name.isEmpty()) {
            CommandUtil.failure(src, "&c名称不能为空！");
            return 0;
        }
        int max = LandmarkService.personalLimit(player.getUUID());
        Map<String, HomeLocation> homes = LandmarkService.listHomes(player.getUUID());
        if (!homes.containsKey(name) && homes.size() >= max) {
            CommandUtil.failure(src, "&c已达到最大传送点数量（" + max + "个）！用 /delhome <名称> 删除旧传送点。");
            return 0;
        }
        HomeLocation loc = new HomeLocation(player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        if (!LandmarkService.addHome(player.getUUID(), name, loc)) {
            CommandUtil.failure(src, "&c传送点 &e" + name + " &c已存在或已达上限！");
            return 0;
        }
        CommandUtil.success(src, "&a传送点 &e" + name + " &a已保存！ (&7" + (int) loc.x() + ", " + (int) loc.y() + ", " + (int) loc.z() + "&a)");
        return 1;
    }

    private static int executeHomeTp(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        MinecraftServer server = src.getServer();
        String name = "name".equals(lastNode(ctx)) ? LandmarkNameArgumentType.getName(ctx, "name") : "home";
        HomeLocation loc = LandmarkService.getHome(player.getUUID(), name);
        if (loc == null) {
            CommandUtil.failure(src, "&c传送点 &e" + name + " &c不存在！用 /homes 查看所有传送点。");
            return 0;
        }
        ServerLevel targetLevel = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(loc.world())));
        if (targetLevel == null) {
            CommandUtil.failure(src, "&c目标世界不存在或未加载！");
            return 0;
        }
        player.teleportTo(targetLevel, loc.x(), loc.y(), loc.z(), Set.of(), loc.yaw(), loc.pitch(), false);
        CommandUtil.success(src, "&a已传送到传送点 &e" + name);
        return 1;
    }

    private static int executeHomeList(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        Map<String, HomeLocation> homes = LandmarkService.listHomes(player.getUUID());
        int max = LandmarkService.personalLimit(player.getUUID());
        if (homes.isEmpty()) {
            CommandUtil.successQuiet(src, "&e你还没有保存任何传送点！用 /sethome <名称> 来保存。");
            return 1;
        }
        player.sendSystemMessage(MessageUtil.parse("&6===== 你的传送点 &7(" + homes.size() + "/" + max + ") &6====="));
        int index = 1;
        for (Map.Entry<String, HomeLocation> entry : homes.entrySet()) {
            HomeLocation l = entry.getValue();
            String dimName = l.world().contains(":") ? l.world().substring(l.world().indexOf(':') + 1) : l.world();
            player.sendSystemMessage(MessageUtil.parse("&e" + index + ". &f" + entry.getKey() + " &7" + dimName
                    + " (" + (int) l.x() + ", " + (int) l.y() + ", " + (int) l.z() + ")"));
            index++;
        }
        player.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }

    private static int executeHomeDel(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        String name = "name".equals(lastNode(ctx)) ? LandmarkNameArgumentType.getName(ctx, "name") : "home";
        if (!LandmarkService.removeHome(player.getUUID(), name)) {
            CommandUtil.failure(src, "&c传送点 &e" + name + " &c不存在！");
            return 0;
        }
        CommandUtil.success(src, "&c传送点 &e" + name + " &c已删除！");
        return 1;
    }

    private static int executeHomeRename(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        String oldName = LandmarkNameArgumentType.getName(ctx, "old");
        String newName = LandmarkNameArgumentType.getName(ctx, "new");
        if (oldName.isEmpty() || newName.isEmpty()) {
            CommandUtil.failure(src, "&c名称不能为空！");
            return 0;
        }
        if (oldName.equals(newName)) {
            CommandUtil.failure(src, "&c新旧名称相同！");
            return 0;
        }
        if (!LandmarkService.renameHome(player.getUUID(), oldName, newName)) {
            CommandUtil.failure(src, "&c传送点 &e" + oldName + " &c不存在或 &e" + newName + " &c已存在！");
            return 0;
        }
        CommandUtil.success(src, "&a传送点 &e" + oldName + " &a已重命名为 &e" + newName);
        return 1;
    }


    /* ---------------- back ---------------- */

    private static int executeBack(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        HomeLocation loc = backLocations.remove(player.getUUID());
        if (loc == null) {
            CommandUtil.failure(src, "&c没有可返回的位置！");
            return 0;
        }
        ServerLevel targetLevel = src.getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(loc.world())));
        if (targetLevel == null) {
            CommandUtil.failure(src, "&c目标世界不存在或未加载！");
            return 0;
        }
        player.teleportTo(targetLevel, loc.x(), loc.y(), loc.z(), Set.of(), loc.yaw(), loc.pitch(), false);
        CommandUtil.success(src, "&a已返回！ (&7" + (int) loc.x() + ", " + (int) loc.y() + ", " + (int) loc.z() + "&a)");
        return 1;
    }

    private static void saveBackLocation(ServerPlayer player) {
        backLocations.put(player.getUUID(), new HomeLocation(
                player.level().dimension().identifier().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    /* ---------------- hat ---------------- */

    private static int executeHat(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer player = src.getPlayerOrException();
        ItemStack handItem = player.getMainHandItem();
        if (handItem.isEmpty()) {
            CommandUtil.failure(src, "&c你手上没有物品！");
            return 0;
        }
        ItemStack headItem = player.getItemBySlot(EquipmentSlot.HEAD);
        player.setItemSlot(EquipmentSlot.HEAD, handItem.copy());
        player.setItemInHand(InteractionHand.MAIN_HAND, headItem);
        CommandUtil.success(src, "&a已将 &e" + handItem.getDisplayName().getString() + " &a戴在头上！");
        return 1;
    }

    /* ---------------- internal ---------------- */

    public static void saveBackOnDeath(ServerPlayer player) {
        saveBackLocation(player);
    }

    private static String lastNode(CommandContext<CommandSourceStack> ctx) {
        return ctx.getNodes().get(ctx.getNodes().size() - 1).getNode().getName();
    }

    private static void saveTitles() {
        PreservedService.saveTitles();
    }
}
