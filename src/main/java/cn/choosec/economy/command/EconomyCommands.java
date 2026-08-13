package cn.choosec.economy.command;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.service.BuildRequestService;
import cn.choosec.economy.service.DailyTaskService;
import cn.choosec.economy.service.LandmarkService;
import cn.choosec.economy.service.MailboxService;
import cn.choosec.economy.service.PlayerService;
import cn.choosec.economy.service.SellService;
import cn.choosec.economy.service.TaskService;
import cn.choosec.economy.service.TradeService;
import cn.choosec.economy.ui.MailboxMenu;
import cn.choosec.economy.ui.MarketMenu;
import cn.choosec.economy.ui.MyMarketMenu;
import cn.choosec.economy.util.MessageUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** New economy commands: balances, pay, sell, eco admin, landmarks, homes expansion, slots, fly, dummies, tasks, marketplace. */
public final class EconomyCommands {

    private EconomyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        // balance
        d.register(Commands.literal("balance")
                .executes(ctx -> balanceSelf(ctx))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> balanceOther(ctx))));
        d.register(Commands.literal("baltop").executes(ctx -> baltop(ctx)));

        // red packets (红包)
        d.register(Commands.literal("redpacket")
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                java.util.List.of("lucky", "normal"), b))
                        .then(Commands.argument("total", DoubleArgumentType.doubleArg(0.01))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> redPacketCreate(ctx)))))
                .then(Commands.literal("grab").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> redPacketGrab(ctx)))));
        d.register(Commands.literal("bal")
                .executes(ctx -> balanceSelf(ctx))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> balanceOther(ctx))));
        d.register(Commands.literal("money").redirect(d.getRoot().getChild("balance")));

        // pay (peer currency transfer, no fee)
        d.register(Commands.literal("pay")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> pay(ctx)))));

        // sell held item
        d.register(Commands.literal("sell")
                .executes(ctx -> sell(ctx, 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> sell(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));

        // fly toggle
        d.register(Commands.literal("fly").executes(ctx -> toggleFly(ctx)));

        // buy extra home slot (个人地标槽位)
        d.register(Commands.literal("buyhome")
                .executes(ctx -> buyHomeSlot(ctx, 1))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                        .executes(ctx -> buyHomeSlot(ctx, IntegerArgumentType.getInteger(ctx, "amount")))));

        // manage my market listings (vanilla GUI)
        d.register(Commands.literal("mymarket").executes(ctx -> openMyMarket(ctx)));

        // extra account slot
        d.register(Commands.literal("accountslot")
                .then(Commands.literal("buy").executes(ctx -> buyAccountSlot(ctx))));

        // tasks
        d.register(Commands.literal("task").executes(ctx -> taskDaily(ctx)));

        // engineering-acceptance / contribution request (players submit a coordinate)
        d.register(Commands.literal("build")
                .then(Commands.literal("submit")
                        .then(Commands.argument("location", Vec3Argument.vec3())
                                .executes(ctx -> buildSubmit(ctx))
                                .then(Commands.argument("note", StringArgumentType.greedyString())
                                        .executes(ctx -> buildSubmit(ctx))))));

        // mailbox (求购到货领取)
        d.register(Commands.literal("mails").executes(ctx -> openMail(ctx)));

        // marketplace (vanilla GUI)
        d.register(Commands.literal("market")
                .executes(ctx -> openMarket(ctx))
                .then(Commands.literal("sell").then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> marketSell(ctx)))
                        .executes(ctx -> marketSell(ctx))))
                .then(Commands.literal("list").executes(ctx -> marketList(ctx)))
                .then(Commands.literal("buy").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .suggests((ctx, b) -> EcoSuggestions.listingIds(b))
                        .executes(ctx -> marketBuy(ctx))))
                .then(Commands.literal("buylist")
                        .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> marketBuyOrder(ctx))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(ctx -> marketBuyOrder(ctx))
                                        .then(Commands.argument("item", IdentifierArgument.id())
                                                .suggests((ctx, b) -> EcoSuggestions.itemSet(b))
                                                .executes(ctx -> marketBuyOrder(ctx))))))
                .then(Commands.literal("fulfill").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .suggests((ctx, b) -> EcoSuggestions.listingIds(b))
                        .executes(ctx -> marketFulfill(ctx))))
                .then(Commands.literal("restock").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .suggests((ctx, b) -> EcoSuggestions.myListingIds(ctx, b))
                        .executes(ctx -> marketRestock(ctx))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                .executes(ctx -> marketRestock(ctx)))))
                .then(Commands.literal("cancel").then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .suggests((ctx, b) -> EcoSuggestions.myListingIds(ctx, b))
                        .executes(ctx -> marketCancel(ctx)))));

        // public landmarks (warp)
        d.register(Commands.literal("warp")
                .executes(ctx -> warpHelp(ctx))
                .then(Commands.literal("tp").then(Commands.argument("name", LandmarkNameArgumentType.name())
                        .suggests((ctx, b) -> EcoSuggestions.publicLandmarks(b))
                        .executes(ctx -> landmarkTp(ctx))))
                .then(Commands.literal("add").then(Commands.argument("name", LandmarkNameArgumentType.name())
                        .executes(ctx -> landmarkAdd(ctx))
                        .then(Commands.argument("cost", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> landmarkAdd(ctx)))))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", LandmarkNameArgumentType.name())
                                .suggests((ctx, b) -> EcoSuggestions.publicLandmarks(b))
                                .then(Commands.argument("newname", LandmarkNameArgumentType.name())
                                        .executes(ctx -> landmarkRename(ctx)))))
                .then(Commands.literal("list").executes(ctx -> landmarkList(ctx)))
                .then(Commands.literal("del").then(Commands.argument("name", LandmarkNameArgumentType.name())
                        .suggests((ctx, b) -> EcoSuggestions.publicLandmarks(b))
                        .executes(ctx -> landmarkDel(ctx)))));

        // eco admin
        d.register(Commands.literal("eco")
                .then(Commands.literal("balance").then(Commands.argument("player", StringArgumentType.word()).suggests(EcoSuggestions::playerNames)
                        .executes(ctx -> ecoBalance(ctx))))
                .then(Commands.literal("give").then(Commands.argument("player", StringArgumentType.word()).suggests(EcoSuggestions::playerNames)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> ecoGive(ctx)))))
                .then(Commands.literal("take").then(Commands.argument("player", StringArgumentType.word()).suggests(EcoSuggestions::playerNames)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> ecoTake(ctx)))))
                .then(Commands.literal("set").then(Commands.argument("player", StringArgumentType.word()).suggests(EcoSuggestions::playerNames)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> ecoSet(ctx)))))
                .then(Commands.literal("reload").executes(ctx -> ecoReload(ctx)))
                .then(Commands.literal("stats").executes(ctx -> ecoStats(ctx)))
                .then(Commands.literal("homelimit").then(Commands.argument("player", StringArgumentType.word()).suggests(EcoSuggestions::playerNames)
                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> ecoHomeLimit(ctx)))))
                .then(Commands.literal("log").then(Commands.argument("player", StringArgumentType.word()).suggests(EcoSuggestions::playerNames)
                        .executes(ctx -> ecoLog(ctx))))
                .then(Commands.literal("buildreward").then(Commands.argument("player", StringArgumentType.word()).suggests(EcoSuggestions::playerNames)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> ecoBuildReward(ctx)))))
                .then(Commands.literal("buildlist").executes(ctx -> ecoBuildList(ctx)))
                .then(Commands.literal("buildapprove")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.0))
                                        .executes(ctx -> ecoBuildApprove(ctx)))))
                .then(Commands.literal("buildreject")
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> ecoBuildReject(ctx))))
                        .then(Commands.literal("task")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                java.util.List.of("kill", "mine", "use", "consume"), b))
                                                .then(Commands.argument("target", IdentifierArgument.id())
                                                        .suggests(EcoSuggestions::taskTargets)
                                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                                .then(Commands.argument("reward", DoubleArgumentType.doubleArg(0.0))
                                                                        .executes(ctx -> ecoTaskAdd(ctx))))))
                                        .then(Commands.literal("reach")
                                                .then(Commands.argument("location", Vec3Argument.vec3())
                                                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                                        .then(Commands.argument("reward", DoubleArgumentType.doubleArg(0.0))
                                                                                .executes(ctx -> ecoTaskReach(ctx))))))))
                                .then(Commands.literal("del").then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                TaskService.listTasks().stream().map(t -> String.valueOf(t.id())).toList(), b))
                                        .executes(ctx -> ecoTaskDel(ctx))))
                                .then(Commands.literal("list").executes(ctx -> taskList(ctx))))
                        .then(Commands.literal("dailytask")
                                .then(Commands.literal("refresh")
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(ctx -> dailyTaskRefresh(ctx))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(0))
                                                        .executes(ctx -> dailyTaskRefresh(ctx)))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .then(Commands.argument("task", IntegerArgumentType.integer(1))
                                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                                TaskService.listTasks().stream().map(t -> String.valueOf(t.id())).toList(), b))
                                                        .executes(ctx -> dailyTaskAdd(ctx)))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .then(Commands.argument("task", IntegerArgumentType.integer(1))
                                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                                TaskService.listTasks().stream().map(t -> String.valueOf(t.id())).toList(), b))
                                                        .executes(ctx -> dailyTaskRemove(ctx)))))
                                .then(Commands.literal("progress")
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .then(Commands.argument("task", IntegerArgumentType.integer(1))
                                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                                TaskService.listTasks().stream().map(t -> String.valueOf(t.id())).toList(), b))
                                                        .then(Commands.argument("delta", IntegerArgumentType.integer())
                                                                .executes(ctx -> dailyTaskProgress(ctx))))))
                                .then(Commands.literal("view")
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(ctx -> dailyTaskView(ctx)))))
                .then(Commands.literal("price").then(Commands.argument("item", IdentifierArgument.id())
                        .suggests((ctx, b) -> EcoSuggestions.sellableItems(b))
                        .then(Commands.argument("base", DoubleArgumentType.doubleArg(0.0))
                                .executes(ctx -> ecoPrice(ctx)))))
                .then(Commands.literal("recycle")
                        .then(Commands.literal("list").executes(ctx -> recycleList(ctx)))
                        .then(Commands.literal("add")
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .suggests((ctx, b) -> EcoSuggestions.itemSet(b))
                                        .then(Commands.argument("base", DoubleArgumentType.doubleArg(0.0))
                                                .then(Commands.argument("floor", DoubleArgumentType.doubleArg(0.0))
                                                        .then(Commands.argument("decay", DoubleArgumentType.doubleArg(0.0))
                                                                .executes(ctx -> recycleAdd(ctx)))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .suggests((ctx, b) -> EcoSuggestions.sellableItems(b))
                                        .executes(ctx -> recycleRemove(ctx))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .suggests((ctx, b) -> EcoSuggestions.sellableItems(b))
                                        .then(Commands.argument("field", StringArgumentType.word())
                                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                        java.util.List.of("base", "floor", "decay"), b))
                                                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0))
                                                        .executes(ctx -> recycleSet(ctx))))))));
    }

    /* ---------------- balance ---------------- */

    private static int balanceSelf(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        BigDecimal bal = EconomyService.getBalance(p.getUUID(), p.getName().getString());
        CommandUtil.successQuiet(src, "&a你的余额：&e" + MoneyUtil.format(bal) + " " + ConfigManager.get().currencyAbbreviation);
        return 1;
    }

    private static int balanceOther(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c仅管理员可查看其他玩家余额！");
            return 0;
        }

        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        BigDecimal bal = EconomyService.getBalance(target.getUUID(), target.getName().getString());
        CommandUtil.successQuiet(src, "&a" + target.getName().getString() + " 的余额：&e"
                + MoneyUtil.format(bal) + " " + ConfigManager.get().currencyAbbreviation);
        return 1;
    }

    /* ---------------- pay ---------------- */

    private static int pay(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer from = src.getPlayerOrException();
        ServerPlayer to = EntityArgument.getPlayer(ctx, "player");
        BigDecimal amount = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "amount"));
        if (from.getUUID().equals(to.getUUID())) {
            CommandUtil.failure(src, "&c不能给自己转账！");
            return 0;
        }
        if (EconomyService.transfer(from.getUUID(), from.getName().getString(),
                to.getUUID(), to.getName().getString(), amount, BigDecimal.ZERO)) {
            CommandUtil.successQuiet(src, "&a已向 &e" + to.getName().getString() + " &a转账 &e"
                    + MoneyUtil.format(amount) + " " + ConfigManager.get().currencyAbbreviation);
            to.sendSystemMessage(MessageUtil.parse("&a收到 &e" + from.getName().getString() + " &a转账 &e"
                    + MoneyUtil.format(amount) + " " + ConfigManager.get().currencyAbbreviation));
        } else {
            CommandUtil.failure(src, "&c余额不足或金额无效！");
        }
        return 1;
    }

    /* ---------------- sell ---------------- */

    private static int sell(CommandContext<CommandSourceStack> ctx, int requested)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer p = src.getPlayerOrException();
        ItemStack stack = p.getMainHandItem();
        if (stack.isEmpty()) {
            CommandUtil.failure(src, "&c你手上没有可出售的物品！");
            return 0;
        }
        String itemId = stack.typeHolder().getRegisteredName();
        if (!SellService.isSellable(itemId)) {
            CommandUtil.failure(src, "&c该物品不符合回收条件：" + itemId);
            return 0;
        }
        int remaining = SellService.remainingSupply(itemId);
        int count = Math.min(Math.min(requested, stack.getCount()), remaining);
        if (count <= 0) {
            CommandUtil.failure(src, "&c服务器今日回收额度已用完或数量不足！");
            return 0;
        }
        BigDecimal unit = SellService.unitPrice(itemId);
        stack.shrink(count);
        p.getInventory().setItem(p.getInventory().getSelectedSlot(), stack);
        BigDecimal paid = SellService.recordSale(itemId, count);
        EconomyService.add(p.getUUID(), p.getName().getString(), paid, "sell:" + itemId + "x" + count);
        CommandUtil.successQuiet(src, "&a已回收 &e" + count + "x " + itemId + " &a，获得 &e"
                + MoneyUtil.format(paid) + " " + ConfigManager.get().currencyAbbreviation
                + " &7(单价 " + MoneyUtil.format(unit) + ")");
        return 1;
    }

    /* ---------------- fly ---------------- */

    private static int toggleFly(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        if (p.isSpectator() || p.getAbilities().instabuild) {
            CommandUtil.failure(src, "&c当前为创造/旁观模式，本就可自由飞行，无需使用 /fly！");
            return 0;
        }
        boolean flying = p.getAbilities().mayfly || p.getAbilities().flying;
        BigDecimal rate = ConfigManager.get().rates.flightPerSecond;
        boolean paid = rate != null && rate.compareTo(BigDecimal.ZERO) > 0;
        if (!flying && paid
                && EconomyService.getBalance(p.getUUID(), p.getName().getString())
                .compareTo(BigDecimal.ZERO) <= 0) {
            CommandUtil.failure(src, "&c余额为0，无法开启飞行！");
            return 0;
        }
        p.getAbilities().mayfly = !flying;
        p.getAbilities().flying = !flying;
        p.onUpdateAbilities();
        if (flying) {
            CommandUtil.successQuiet(src, "&c已关闭飞行。");
        } else {
            CommandUtil.successQuiet(src, "&a已开启飞行（&e" + MoneyUtil.format(rate) + " " + ConfigManager.get().currencyAbbreviation + "/秒&a，按秒扣费）");
        }
        return 1;
    }

    /* ---------------- home slot ---------------- */

    private static int buyHomeSlot(CommandContext<CommandSourceStack> ctx, int count)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        int amount = Math.max(1, count);
        BigDecimal cost = MoneyUtil.norm(ConfigManager.get().rates.landmarkSlotPrice.multiply(BigDecimal.valueOf(amount)));
        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);
            try {
                if (EconomyService.remove(p.getUUID(), p.getName().getString(), cost, "buy home slot x" + amount) == null) {
                    c.rollback();
                    CommandUtil.failure(src, "&c余额不足！购买 &e" + amount + " &c个个人地标槽位需要 &e" + MoneyUtil.format(cost));
                    return 0;
                }
                PlayerService.addPurchasedHomeSlots(p.getUUID(), amount);
                c.commit();
                CommandUtil.successQuiet(src, "&a已购买 &e" + amount + " &a个个人地标槽位，花费 &e"
                        + MoneyUtil.format(cost) + " &a。当前上限 &e" + LandmarkService.personalLimit(p.getUUID()));
            } catch (SQLException e) {
                c.rollback();
                EconomyService.invalidateCache(p.getUUID());
                DatabaseManager.log(e);
                CommandUtil.failure(src, "&c购买失败，请重试！");
                return 0;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            CommandUtil.failure(src, "&c购买失败，请重试！");
            return 0;
        }
        return 1;
    }

    /* ---------------- account slot ---------------- */

    private static int buyAccountSlot(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        BigDecimal cost = ConfigManager.get().rates.accountSlotPrice;
        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);
            try {
                if (EconomyService.remove(p.getUUID(), p.getName().getString(), cost, "buy account slot") == null) {
                    c.rollback();
                    CommandUtil.failure(src, "&c余额不足！购买额外账号槽位需要 &e" + MoneyUtil.format(cost));
                    return 0;
                }
                PlayerService.addPurchasedAccountSlots(p.getUUID(), 1);
                c.commit();
                CommandUtil.successQuiet(src, "&a已购买 1 个额外账号槽位，花费 &e"
                        + MoneyUtil.format(cost) + " &a。已购买 &e" + PlayerService.purchasedAccountSlots(p.getUUID()));
            } catch (SQLException e) {
                c.rollback();
                EconomyService.invalidateCache(p.getUUID());
                DatabaseManager.log(e);
                CommandUtil.failure(src, "&c购买失败，请重试！");
                return 0;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            CommandUtil.failure(src, "&c购买失败，请重试！");
            return 0;
        }
        return 1;
    }








    /* ---------------- tasks ---------------- */

    private static int taskList(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        cn.choosec.economy.util.TaskNames.load(src.getServer());
        boolean zh = "zh_cn".equalsIgnoreCase(ConfigManager.get().scoreboardLanguage);

        List<TaskService.Task> tasks = TaskService.listTasks();
        if (tasks.isEmpty()) {
            CommandUtil.successQuiet(src, "&e当前没有可用的服务器任务。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= 服务器任务 &6======="));
        for (TaskService.Task t : tasks) {
            net.minecraft.network.chat.MutableComponent line = MessageUtil.parse(
                    "&e#" + t.id() + " &f" + t.name() + " &7").copy();
            line.append(Component.literal(cn.choosec.economy.util.TaskNames.typeLabel(t.type(), zh)));
            line.append(MessageUtil.parse(" &7"));
            line.append(cn.choosec.economy.util.TaskNames.target(t.type(), t.target()));
            line.append(MessageUtil.parse(" &7x" + t.amount() + " &a奖励 "
                    + MoneyUtil.format(t.reward()) + " " + ConfigManager.get().currencyAbbreviation));
            src.sendSystemMessage(line);
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }

    /* ---------------- marketplace ---------------- */

    private static int marketSell(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        BigDecimal price = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "price"));
        int count = lastNode(ctx).equals("count") ? IntegerArgumentType.getInteger(ctx, "count") : 1;
        ItemStack stack = p.getMainHandItem();
        if (stack.isEmpty()) {
            CommandUtil.failure(src, "&c请手持要出售的物品！");
            return 0;
        }
        if (count <= 0) {
            CommandUtil.failure(src, "&c数量无效！");
            return 0;
        }
        String itemId = stack.typeHolder().getRegisteredName();
        if (itemId == null) {
            CommandUtil.failure(src, "&c无法识别该物品，上架失败！");
            return 0;
        }
        String itemData = TradeService.serialize(stack, ctx.getSource().getServer().registryAccess());
        if (itemData == null) {
            // 无法序列化物品数据时拒绝上架，避免生成无法还原 NBT 的订单
            CommandUtil.failure(src, "&c无法保存该物品数据，上架失败！");
            return 0;
        }
        // 数量可超过一组：手持不足时，从背包补足完全相同的物品
        int available = TradeService.countExactItems(p, stack);
        if (available < count) {
            CommandUtil.failure(src, "&c完全相同的物品不足！需要 &e" + count + " &c个，背包中只有 &e" + available + " &c个。");
            return 0;
        }
        List<ItemStack> removed = TradeService.removeExactItems(p, stack, count);
        if (removed == null) {
            CommandUtil.failure(src, "&c上架失败！");
            return 0;
        }
        int id = TradeService.createListing(p.getUUID(), itemId, count, price, itemData);
        if (id == -2) {
            TradeService.returnItems(p, removed);
            CommandUtil.failure(src, "&c你的上架数量已达上限！");
            return 0;
        }
        if (id < 0) {
            TradeService.returnItems(p, removed);
            CommandUtil.failure(src, "&c上架失败！");
            return 0;
        }
        BigDecimal fee = ConfigManager.get().rates.tradeFeePercent;
        String sellerShare = MoneyUtil.format(new BigDecimal("100").subtract(fee));
        CommandUtil.successQuiet(src, "&a已上架 #" + id + " &e" + count + "x " + itemId
                + " &a单价 &e" + MoneyUtil.format(price) + " " + ConfigManager.get().currencyAbbreviation
                + " &7(成交后卖家收取 " + sellerShare + "%，" + MoneyUtil.format(fee) + "% 为手续费)");
        return 1;
    }

    private static int marketList(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        List<TradeService.Listing> listings = TradeService.listListings();
        if (listings.isEmpty()) {
            CommandUtil.successQuiet(src, "&e当前没有上架物品。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= 交易市场 &6======="));
        for (TradeService.Listing l : listings) {
            boolean buy = "BUY".equalsIgnoreCase(l.type());
            src.sendSystemMessage(MessageUtil.parse((buy ? "&b[求购] " : "&a[出售] ") + "&e#" + l.id() + " &f" + l.count() + "x "
                    + l.itemId() + " &7单价 &a" + MoneyUtil.format(l.price()) + " "
                    + ConfigManager.get().currencyAbbreviation + " &7商家 "
                    + TradeService.sellerName(ctx.getSource().getServer(), l.seller())
                    + " &7" + (buy ? "/market fulfill " : "/market buy ") + l.id()));
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }

    private static int marketBuy(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer buyer = src.getPlayerOrException();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        TradeService.BuyResult r = TradeService.buy(buyer, id);
        switch (r) {
            case SUCCESS -> CommandUtil.successQuiet(src, "&a购买成功！");
            case NOT_FOUND -> CommandUtil.failure(src, "&c该上架已售出或不存在！");
            case OWN_ITEM -> CommandUtil.failure(src, "&c不能购买自己上架的物品！");
            case NO_FUNDS -> CommandUtil.failure(src, "&c余额不足！");
            case NO_SPACE -> CommandUtil.failure(src, "&c背包空间不足！");
            case ITEM_ERROR -> CommandUtil.failure(src, "&c无法读取该物品数据！");
            case IS_BUY_ORDER -> CommandUtil.failure(src, "&c这是求购单，请用 /market fulfill 供货！");
        }
        return r == TradeService.BuyResult.SUCCESS ? 1 : 0;
    }

    private static int openMarket(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new MarketMenu(id, inv, p),
                Component.literal("交易市场")));
        return 1;
    }

    private static int openMyMarket(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new MyMarketMenu(id, inv, p),
                Component.literal("我的商品")));
        return 1;
    }

    private static int openMail(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        int unread = MailboxService.count(p.getUUID());
        if (unread == 0) {
            CommandUtil.successQuiet(src, "&e你的邮箱是空的。");
            return 1;
        }
        p.openMenu(new SimpleMenuProvider(
                (id, inv, pl) -> new MailboxMenu(id, inv, p),
                Component.literal("邮箱")));
        return 1;
    }

    private static int marketCancel(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        TradeService.CancelResult r = TradeService.cancel(p, id);
        switch (r) {
            case SUCCESS -> CommandUtil.successQuiet(src, "&a已取消 #" + id + "，相关已全额退回。");
            case NOT_FOUND -> CommandUtil.failure(src, "&c该订单不存在！");
            case NOT_OWNER -> CommandUtil.failure(src, "&c这不是你的订单！");
            case ERROR -> CommandUtil.failure(src, "&c取消失败！");
        }
        return r == TradeService.CancelResult.SUCCESS ? 1 : 0;
    }

    private static int marketBuyOrder(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        String node = lastNode(ctx);
        BigDecimal price = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "price"));
        int count = node.equals("count") || node.equals("item") ? IntegerArgumentType.getInteger(ctx, "count") : 1;
        count = Math.max(1, count);
        String item = node.equals("item") ? IdentifierArgument.getId(ctx, "item").toString() : "minecraft:hand";
        String itemId;
        if (item.equals("minecraft:hand")) {
            ItemStack held = p.getMainHandItem();
            if (held.isEmpty()) {
                CommandUtil.failure(src, "&c请手持要收购的物品，或用物品 id！");
                return 0;
            }
            itemId = held.typeHolder().getRegisteredName();
        } else {
            itemId = item;
            if (TradeService.buildFromId(itemId, 1).isEmpty()) {
                CommandUtil.failure(src, "&c物品 id 无效：" + itemId);
                return 0;
            }
        }
        BigDecimal total = MoneyUtil.norm(price.multiply(BigDecimal.valueOf(count)));
        int id = TradeService.createBuyOrder(p, itemId, count, price);
        if (id == -3) {
            CommandUtil.failure(src, "&c余额不足！求购需预支 " + MoneyUtil.format(total));
            return 0;
        }
        if (id == -2) {
            CommandUtil.failure(src, "&c订单数量已达上限！");
            return 0;
        }
        if (id < 0) {
            CommandUtil.failure(src, "&c发布求购失败！");
            return 0;
        }
        CommandUtil.successQuiet(src, "&a已发布求购 #" + id + " &e" + count + "x " + itemId
                + " &a单价 " + MoneyUtil.format(price) + "（已预支 " + MoneyUtil.format(total) + "）");
        return 1;
    }

    private static int marketFulfill(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        TradeService.FulfillResult r = TradeService.fulfill(p, id);
        switch (r) {
            case SUCCESS -> CommandUtil.successQuiet(src, "&a已供货并收到货款！");
            case NOT_FOUND -> CommandUtil.failure(src, "&c该求购不存在！");
            case NOT_BUY_ORDER -> CommandUtil.failure(src, "&c该订单不是求购单！");
            case NO_ITEMS -> CommandUtil.failure(src, "&c背包中没有足够的物品！");
            case ITEM_ERROR -> CommandUtil.failure(src, "&c物品数据错误！");
        }
        return r == TradeService.FulfillResult.SUCCESS ? 1 : 0;
    }

    private static int marketRestock(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        int count = lastNode(ctx).equals("count") ? IntegerArgumentType.getInteger(ctx, "count") : 1;
        TradeService.RestockResult r = TradeService.restock(p, id, count);
        switch (r) {
            case SUCCESS -> CommandUtil.successQuiet(src, "&a已向订单 &e#" + id + " &a补充 &e" + count + " &a个！");
            case NOT_FOUND -> CommandUtil.failure(src, "&c该订单不存在！");
            case NOT_OWNER -> CommandUtil.failure(src, "&c这不是你的订单！");
            case INVALID_COUNT -> CommandUtil.failure(src, "&c数量无效！");
            case NO_ITEMS -> CommandUtil.failure(src, "&c背包中没有足够的完全相同的物品！");
            case NO_FUNDS -> CommandUtil.failure(src, "&c余额不足！补货需预支托管金！");
            default -> CommandUtil.failure(src, "&c补货失败！");
        }
        return r == TradeService.RestockResult.SUCCESS ? 1 : 0;
    }

    /* ---------------- public landmarks ---------------- */

    private static int landmarkList(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        List<LandmarkService.Landmark> list = LandmarkService.listPublic();
        if (list.isEmpty()) {
            CommandUtil.successQuiet(src, "&e当前没有公共地标。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= 公共地标 &6======="));
        for (LandmarkService.Landmark lm : list) {
            src.sendSystemMessage(MessageUtil.parse("&e" + lm.name() + " &7(" + lm.world() + " "
                    + (int) lm.x() + ", " + (int) lm.y() + ", " + (int) lm.z() + ") &a传送费 "
                    + MoneyUtil.format(lm.cost()) + " " + ConfigManager.get().currencyAbbreviation
                    + " &7/warp tp " + lm.name()));
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }

    private static int landmarkTp(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        ServerPlayer p = src.getPlayerOrException();
        String name = LandmarkNameArgumentType.getName(ctx, "name");
        LandmarkService.Landmark lm = LandmarkService.getPublic(name);
        if (lm == null) {
            CommandUtil.failure(src, "&c公共地标 &e" + name + " &c不存在！");
            return 0;
        }
        ServerLevel level = src.getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(lm.world())));
        if (level == null) {
            CommandUtil.failure(src, "&c目标世界不存在或未加载！");
            return 0;
        }
        BigDecimal cost = lm.cost();
        if (cost.compareTo(BigDecimal.ZERO) > 0) {
            if (EconomyService.remove(p.getUUID(), p.getName().getString(), cost, "landmark tp:" + name) == null) {
                CommandUtil.failure(src, "&c余额不足！传送至 &e" + name + " &c需要 &e"
                        + MoneyUtil.format(cost) + " " + ConfigManager.get().currencyAbbreviation);
                return 0;
            }
        }
        p.teleportTo(level, lm.x(), lm.y(), lm.z(), java.util.Set.of(), lm.yaw(), lm.pitch(), false);
        CommandUtil.successQuiet(src, "&a已传送到公共地标 &e" + name);
        return 1;
    }

    private static int landmarkAdd(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c需要管理员权限！");
            return 0;
        }
        ServerPlayer p = src.getPlayerOrException();
        String name = LandmarkNameArgumentType.getName(ctx, "name");
        if (name.isEmpty()) {
            CommandUtil.failure(src, "&c名称不能为空！");
            return 0;
        }
        BigDecimal cost = lastNode(ctx).equals("cost")
                ? MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "cost"))
                : MoneyUtil.norm(ConfigManager.get().rates.publicLandmarkCost);
        if (LandmarkService.addPublic(name, p.level().dimension().identifier().toString(),
                p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), cost)) {
            CommandUtil.success(src, "&a已添加公共地标 &e" + name + " &a（传送费 " + MoneyUtil.format(cost) + "）");
        } else {
            CommandUtil.failure(src, "&c添加失败（名称可能已存在）！");
        }
        return 1;
    }

    private static int landmarkDel(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c需要管理员权限！");
            return 0;
        }
        String name = LandmarkNameArgumentType.getName(ctx, "name");
        if (LandmarkService.removePublic(name)) {
            CommandUtil.success(src, "&a已删除公共地标 &e" + name);
        } else {
            CommandUtil.failure(src, "&c公共地标 &e" + name + " &c不存在！");
        }
        return 1;
    }

    private static int landmarkRename(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c需要管理员权限！");
            return 0;
        }
        String oldName = LandmarkNameArgumentType.getName(ctx, "name");
        String newName = LandmarkNameArgumentType.getName(ctx, "newname");
        if (oldName.isEmpty() || newName.isEmpty()) {
            CommandUtil.failure(src, "&c名称不能为空！");
            return 0;
        }
        if (oldName.equals(newName)) {
            CommandUtil.failure(src, "&c新旧名称相同！");
            return 0;
        }
        if (LandmarkService.renamePublic(oldName, newName)) {
            CommandUtil.success(src, "&a公共地标 &e" + oldName + " &a已重命名为 &e" + newName);
        } else {
            CommandUtil.failure(src, "&c公共地标 &e" + oldName + " &c不存在或名称 &e" + newName + " &c已被占用！");
        }
        return 1;
    }

    private static int warpHelp(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        src.sendSystemMessage(MessageUtil.parse("&6======= 公共地标 /warp &6======="));
        src.sendSystemMessage(MessageUtil.parse("&e/warp list &7查看所有公共地标"));
        src.sendSystemMessage(MessageUtil.parse("&e/warp tp <名称> &7传送到公共地标（可能收费）"));
        src.sendSystemMessage(MessageUtil.parse("&e/warp add <名称> [费用] &7添加公共地标（管理员）"));
        src.sendSystemMessage(MessageUtil.parse("&e/warp rename <旧名称> <新名称> &7重命名公共地标（管理员）"));
        src.sendSystemMessage(MessageUtil.parse("&e/warp del <名称> &7删除公共地标（管理员）"));
        src.sendSystemMessage(MessageUtil.parse("&7名称支持中文（名称含空格时请加引号）"));
        src.sendSystemMessage(MessageUtil.parse("&6=================================="));
        return 1;
    }

    /* ---------------- eco admin ---------------- */

    private static boolean opOrFail(CommandSourceStack src) {
        if (!CommandUtil.isOp(src)) {
            CommandUtil.failure(src, "&c需要管理员权限！");
            return false;
        }
        return true;
    }

    private static int ecoBalance(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String pname = StringArgumentType.getString(ctx, "player");
        Target t = resolveTarget(src, pname);
        if (t == null) { CommandUtil.failure(src, "&c找不到玩家 &e" + pname + " &c！"); return 0; }
        BigDecimal bal = EconomyService.getBalance(t.uuid(), t.name());
        CommandUtil.success(src, "&a" + t.name() + " 的余额：&e"
                + MoneyUtil.format(bal) + " " + ConfigManager.get().currencyAbbreviation);
        return 1;
    }

    private static int ecoGive(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String pname = StringArgumentType.getString(ctx, "player");
        Target t = resolveTarget(src, pname);
        if (t == null) { CommandUtil.failure(src, "&c找不到玩家 &e" + pname + " &c！"); return 0; }
        BigDecimal amount = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "amount"));
        BigDecimal bal = EconomyService.add(t.uuid(), t.name(), amount, "eco give");
        if (bal == null) {
            CommandUtil.failure(src, "&c操作失败！");
            return 0;
        }
        CommandUtil.success(src, "&a已给 &e" + t.name() + " &a发放 &e"
                + MoneyUtil.format(amount) + " &a，当前余额 &e" + MoneyUtil.format(bal));
        return 1;
    }

    private static int ecoTake(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String pname = StringArgumentType.getString(ctx, "player");
        Target t = resolveTarget(src, pname);
        if (t == null) { CommandUtil.failure(src, "&c找不到玩家 &e" + pname + " &c！"); return 0; }
        BigDecimal amount = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "amount"));
        BigDecimal bal = EconomyService.remove(t.uuid(), t.name(), amount, "eco take");
        if (bal == null) {
            CommandUtil.failure(src, "&c扣除失败（余额不足）！");
            return 0;
        }
        CommandUtil.success(src, "&a已扣除 &e" + t.name() + " &a" + MoneyUtil.format(amount)
                + " &a，当前余额 &e" + MoneyUtil.format(bal));
        return 1;
    }

    private static int ecoSet(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String pname = StringArgumentType.getString(ctx, "player");
        Target t = resolveTarget(src, pname);
        if (t == null) { CommandUtil.failure(src, "&c找不到玩家 &e" + pname + " &c！"); return 0; }
        BigDecimal amount = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "amount"));
        if (EconomyService.set(t.uuid(), t.name(), amount, "eco set")) {
            CommandUtil.success(src, "&a已将 &e" + t.name() + " &a余额设为 &e" + MoneyUtil.format(amount));
        } else {
            CommandUtil.failure(src, "&c操作失败！");
        }
        return 1;
    }

    private static int ecoReload(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        if (ConfigManager.reload()) {
            CommandUtil.success(src, "&a配置已重新加载。");
        } else {
            CommandUtil.failure(src, "&c配置加载失败！");
        }
        return 1;
    }

    private static int ecoStats(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        src.sendSystemMessage(MessageUtil.parse("&6======= 经济概况 &6======="));
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(balance),0) FROM balances");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    src.sendSystemMessage(MessageUtil.parse("&a账户数 &e" + rs.getInt(1)
                            + " &a· 总发行量 &e" + MoneyUtil.format(rs.getBigDecimal(2))));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT item, sold_count, total_value FROM supply ORDER BY item");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String item = rs.getString("item");
                    src.sendSystemMessage(MessageUtil.parse("&7回收 &f" + item + " &a已收 "
                            + rs.getInt("sold_count") + " &7· 总支出 &a" + MoneyUtil.format(rs.getBigDecimal("total_value"))
                            + " &7· 单价 " + MoneyUtil.format(SellService.unitPrice(item))));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM market_listings");
                 ResultSet rs = ps.executeQuery()) {
                src.sendSystemMessage(MessageUtil.parse("&a在售上架 &e" + (rs.next() ? rs.getInt(1) : 0)));
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM tasks WHERE enabled = 1");
                 ResultSet rs = ps.executeQuery()) {
                src.sendSystemMessage(MessageUtil.parse("&a启用任务 &e" + (rs.next() ? rs.getInt(1) : 0)));
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            CommandUtil.failure(src, "&c读取统计失败！");
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }

    private static int ecoHomeLimit(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String pname = StringArgumentType.getString(ctx, "player");
        Target t = resolveTarget(src, pname);
        if (t == null) { CommandUtil.failure(src, "&c找不到玩家 &e" + pname + " &c！"); return 0; }
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        if (amount == ConfigManager.get().landmarks.defaultPersonalLimit) {
            LandmarkService.clearPersonalLimitOverride(t.uuid());
        } else {
            LandmarkService.setPersonalLimitOverride(t.uuid(), amount);
        }
        CommandUtil.success(src, "&a已将 &e" + t.name() + " &a的个人地标上限设为 &e" + amount);
        return 1;
    }

    private static int ecoBuildReward(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String pname = StringArgumentType.getString(ctx, "player");
        Target t = resolveTarget(src, pname);
        if (t == null) { CommandUtil.failure(src, "&c找不到玩家 &e" + pname + " &c！"); return 0; }
        BigDecimal amount = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "amount"));
        BigDecimal bal = EconomyService.add(t.uuid(), t.name(), amount, "building reward");
        if (bal == null) {
            CommandUtil.failure(src, "&c操作失败！");
            return 0;
        }
        CommandUtil.success(src, "&a已向 &e" + t.name() + " &a发放工程验收奖励 &e"
                + MoneyUtil.format(amount) + " &a，当前余额 &e" + MoneyUtil.format(bal));
        return 1;
    }

    /* ---------------- build / acceptance requests ---------------- */

    private static int buildSubmit(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayerOrException();
        Vec3 pos = Vec3Argument.getVec3(ctx, "location");
        String note = lastNode(ctx).equals("note")
                ? StringArgumentType.getString(ctx, "note") : null;
        if (note != null && note.isBlank()) {
            note = null;
        }
        String world = player.level().dimension().identifier().toString();
        long id = BuildRequestService.submit(player.getUUID(), player.getName().getString(),
                world, pos.x, pos.y, pos.z, note);
        if (id < 0) {
            CommandUtil.failure(src, "&c提交验收申请失败！");
            return 0;
        }
        src.sendSystemMessage(MessageUtil.parse("&a已提交工程验收申请 #" + id + " &7(" + world + " "
                + (int) pos.x + ", " + (int) pos.y + ", " + (int) pos.z + ")"
                + (note != null ? " &f" + note : "")
                + " &7等待管理员验收。"));
        return 1;
    }

    private static int ecoBuildList(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        List<BuildRequestService.BuildRequest> pending = BuildRequestService.list(BuildRequestService.PENDING);
        if (pending.isEmpty()) {
            CommandUtil.successQuiet(src, "&e当前没有待验收的工程申请。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= 待验收工程申请 (" + pending.size() + ") &6======="));
        for (BuildRequestService.BuildRequest r : pending) {
            String line = "&e#" + r.id() + " &f" + r.playerName()
                    + " &7" + r.world() + " (" + (int) r.x() + ", " + (int) r.y() + ", " + (int) r.z() + ")";
            if (r.note() != null && !r.note().isBlank()) {
                line += " &7[" + r.note() + "]";
            }
            src.sendSystemMessage(MessageUtil.parse(line));
        }
        src.sendSystemMessage(MessageUtil.parse("&6使用 &e/eco buildapprove <id> <金额> &6验收并发放奖励，&e/eco buildreject <id> &6驳回。"));
        src.sendSystemMessage(MessageUtil.parse("&6=================================================="));
        return 1;
    }

    private static int ecoBuildApprove(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        long id = IntegerArgumentType.getInteger(ctx, "id");
        BigDecimal amount = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "amount"));
        BuildRequestService.ApproveResult result = BuildRequestService.approve(id, amount);
        switch (result) {
            case SUCCESS -> {
                BuildRequestService.BuildRequest r = BuildRequestService.get(id);
                CommandUtil.success(src, "&a已验收申请 #" + id + " &e(" + r.playerName()
                        + ") &a并发放工程验收奖励 &e" + MoneyUtil.format(amount));
                return 1;
            }
            case NOT_FOUND -> {
                CommandUtil.failure(src, "&c验收申请 #" + id + " 不存在！");
                return 0;
            }
            case NOT_PENDING -> {
                CommandUtil.failure(src, "&c验收申请 #" + id + " 已处理，无法再次验收！");
                return 0;
            }
            default -> {
                CommandUtil.failure(src, "&c验收失败！");
                return 0;
            }
        }
    }

    private static int ecoBuildReject(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        long id = IntegerArgumentType.getInteger(ctx, "id");
        if (BuildRequestService.reject(id)) {
            CommandUtil.success(src, "&a已驳回验收申请 #" + id);
            return 1;
        }
        CommandUtil.failure(src, "&c驳回失败：申请 #" + id + " 不存在或已处理！");
        return 0;
    }

    private static int ecoTaskAdd(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String type = StringArgumentType.getString(ctx, "type").toLowerCase();
        if (!(type.equals("kill") || type.equals("mine") || type.equals("use") || type.equals("consume"))) {
            CommandUtil.failure(src, "&c任务类型必须是 kill / mine / use / consume 之一（到达任务用 /eco task reach）！");
            return 0;
        }
        String target = IdentifierArgument.getId(ctx, "target").toString();
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        BigDecimal reward = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "reward"));
        int id = TaskService.addTask(type, type + " " + target, type, target, amount, reward);
        if (id < 0) {
            CommandUtil.failure(src, "&c创建任务失败！");
            return 0;
        }
        CommandUtil.success(src, "&a已加入任务池 #" + id + " &e[" + type + "] " + target
                + " &a需完成 " + amount + " 次，奖励 " + MoneyUtil.format(reward));
        return 1;
    }

    private static int ecoTaskReach(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        Vec3 pos = Vec3Argument.getVec3(ctx, "location");
        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        BigDecimal reward = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "reward"));
        String dim = level.dimension().identifier().toString();
        String target = dim + ":" + (int) pos.x + "," + (int) pos.y + "," + (int) pos.z;
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        int id = TaskService.addTask("reach", "reach " + target, "reach", target, amount, reward);
        if (id < 0) {
            CommandUtil.failure(src, "&c创建任务失败！");
            return 0;
        }
        CommandUtil.success(src, "&a已加入任务池 #" + id + " &e[到达 " + dim + " "
                + (int) pos.x + "," + (int) pos.y + "," + (int) pos.z + "] &a需到达 " + amount + " 次，奖励 " + MoneyUtil.format(reward));
        return 1;
    }

    private static int ecoTaskDel(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (TaskService.deleteTask(id)) {
            CommandUtil.success(src, "&a已删除任务 #" + id);
            return 1;
        } else {
            CommandUtil.failure(src, "&c任务 #" + id + " 不存在！");
            return 0;
        }
    }

    private static int ecoPrice(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();

        if (!opOrFail(src)) return 0;
        String item = IdentifierArgument.getId(ctx, "item").toString();
        double base = DoubleArgumentType.getDouble(ctx, "base");
        for (cn.choosec.economy.config.EconomyConfig.SellableItem s : ConfigManager.get().sellableItems) {
            if (s.id.equalsIgnoreCase(item)) {
                s.basePrice = MoneyUtil.norm(BigDecimal.valueOf(base));
                ConfigManager.save();
                CommandUtil.success(src, "&a已将 &e" + item + " &a回收基价调整为 &e" + MoneyUtil.format(s.basePrice));
                return 1;
            }
        }
        CommandUtil.failure(src, "&c未找到可回收物品 &e" + item + " &c（请先在配置 sellableItems 中添加）");
        return 0;
    }


    private static int baltop(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        List<String[]> top = EconomyService.topBalances(10);
        if (top.isEmpty()) {
            CommandUtil.successQuiet(src, "&e排行榜暂无数据。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= " + ConfigManager.get().currencyAbbreviation + " 排行榜 &6======="));
        int rank = 1;
        for (String[] row : top) {
            src.sendSystemMessage(MessageUtil.parse("&e" + rank + ". &f" + row[0]
                    + " &a" + row[1] + " " + ConfigManager.get().currencyAbbreviation));
            rank++;
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }


    /* ---------------- recycle (admin manages sellable items) ---------------- */

    private static int recycleList(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        List<cn.choosec.economy.config.EconomyConfig.SellableItem> items = ConfigManager.get().sellableItems;
        if (items.isEmpty()) {
            CommandUtil.successQuiet(src, "&e当前没有可回收物品。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= 可回收物品 &6======="));
        for (cn.choosec.economy.config.EconomyConfig.SellableItem it : items) {
            src.sendSystemMessage(MessageUtil.parse("&e" + it.id + " &7基价 " + MoneyUtil.format(it.basePrice)
                    + " &7地板 " + MoneyUtil.format(it.priceFloor)
                    + " &7降幅 " + it.decayPercentPerUnit + "%/个"
                    + " &7现价 " + MoneyUtil.format(SellService.unitPrice(it.id))));
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }

    private static int recycleAdd(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        String item = IdentifierArgument.getId(ctx, "item").toString();
        BigDecimal base = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "base"));
        BigDecimal floor = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "floor"));
        double decay = DoubleArgumentType.getDouble(ctx, "decay");
        for (cn.choosec.economy.config.EconomyConfig.SellableItem it : ConfigManager.get().sellableItems) {
            if (it.id.equals(item)) {
                CommandUtil.failure(src, "&c物品 " + item + " 已存在，请用 /eco recycle set 修改。");
                return 0;
            }
        }
        cn.choosec.economy.config.EconomyConfig.SellableItem s = new cn.choosec.economy.config.EconomyConfig.SellableItem();
        s.id = item; s.basePrice = base; s.priceFloor = floor; s.decayPercentPerUnit = decay;
        ConfigManager.get().sellableItems.add(s);
        ConfigManager.save();
        CommandUtil.success(src, "&a已添加可回收物品 &e" + item + " &a（基价 " + MoneyUtil.format(base)
                + "，地板 " + MoneyUtil.format(floor) + "，降幅 " + decay + "%/个）");
        return 1;
    }

    private static int recycleRemove(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        String item = IdentifierArgument.getId(ctx, "item").toString();
        boolean removed = ConfigManager.get().sellableItems.removeIf(it -> it.id.equals(item));
        if (removed) {
            ConfigManager.save();
            CommandUtil.success(src, "&a已移除可回收物品 &e" + item);
        } else {
            CommandUtil.failure(src, "&c未找到可回收物品 &e" + item);
        }
        return removed ? 1 : 0;
    }

    private static int recycleSet(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        String item = IdentifierArgument.getId(ctx, "item").toString();
        String field = StringArgumentType.getString(ctx, "field");
        double value = DoubleArgumentType.getDouble(ctx, "value");
        for (cn.choosec.economy.config.EconomyConfig.SellableItem it : ConfigManager.get().sellableItems) {
            if (!it.id.equals(item)) continue;
            switch (field) {
                case "base" -> it.basePrice = MoneyUtil.norm(BigDecimal.valueOf(value));
                case "floor" -> it.priceFloor = MoneyUtil.norm(BigDecimal.valueOf(value));
                case "decay" -> it.decayPercentPerUnit = value;
                default -> { CommandUtil.failure(src, "&c字段必须是 base/floor/decay"); return 0; }
            }
            ConfigManager.save();
            CommandUtil.success(src, "&a已更新 &e" + item + " &a的 " + field + " = " + value);
            return 1;
        }
        CommandUtil.failure(src, "&c未找到可回收物品 &e" + item);
        return 0;
    }



    /** Show the player's own daily tasks (also updates the scoreboard). */
    private static int taskDaily(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer p = src.getPlayerOrException();
        cn.choosec.economy.util.TaskNames.load(src.getServer());
        boolean zh = "zh_cn".equalsIgnoreCase(ConfigManager.get().scoreboardLanguage);
        String date = cn.choosec.economy.service.DailyTaskService.today();
        cn.choosec.economy.service.DailyTaskService.ensureDaily(p, date);
        cn.choosec.economy.service.DailyTaskService.updateScoreboard(p, date);
        List<cn.choosec.economy.service.DailyTaskService.Daily> list =
                cn.choosec.economy.service.DailyTaskService.getDaily(p.getUUID(), date);
        if (list.isEmpty()) {
            CommandUtil.successQuiet(src, "&e今日没有任务。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= 今日任务 (" + list.size() + ") &6======="));
        for (cn.choosec.economy.service.DailyTaskService.Daily d : list) {
            net.minecraft.network.chat.MutableComponent line = MessageUtil.parse("&e").copy();
            line.append(Component.literal(cn.choosec.economy.util.TaskNames.typeLabel(d.type(), zh)));
            line.append(MessageUtil.parse(" "));
            line.append(cn.choosec.economy.util.TaskNames.target(d.type(), d.target()));
            line.append(MessageUtil.parse(d.completed() ? " &a[完成]" : " &7(" + d.progress() + "/" + d.amount() + ")"));
            line.append(MessageUtil.parse(" &a" + MoneyUtil.format(d.reward()) + " " + ConfigManager.get().currencyAbbreviation));
            src.sendSystemMessage(line);
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }


    /* ---------------- per-player daily task admin management ---------------- */

    private static int dailyTaskRefresh(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        cn.choosec.economy.util.TaskNames.load(src.getServer());
        boolean zh = "zh_cn".equalsIgnoreCase(ConfigManager.get().scoreboardLanguage);
        String date = DailyTaskService.today();
        int count = lastNode(ctx).equals("count")
                ? IntegerArgumentType.getInteger(ctx, "count")
                : ConfigManager.get().tasks.dailyCount;
        int done = 0;
        for (ServerPlayer p : targets) {
            int n = DailyTaskService.refresh(p.getUUID(), date, count);
            DailyTaskService.updateScoreboard(p, date);
            CommandUtil.success(src, "&a已刷新 &e" + p.getName().getString() + " &a的每日任务，当前 &e" + n + " &a个");
            done++;
        }
        if (done == 0) {
            CommandUtil.failure(src, "&c目标选择器未匹配到任何在线玩家！");
            return 0;
        }
        return 1;
    }

    private static int dailyTaskAdd(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        int taskId = IntegerArgumentType.getInteger(ctx, "task");
        TaskService.Task t = TaskService.getTask(taskId);
        if (t == null) {
            CommandUtil.failure(src, "&c任务池中不存在任务 #" + taskId + "！");
            return 0;
        }
        String date = DailyTaskService.today();
        int added = 0, dup = 0;
        for (ServerPlayer p : targets) {
            if (DailyTaskService.addTask(p.getUUID(), date, taskId)) {
                DailyTaskService.updateScoreboard(p, date);
                added++;
            } else {
                dup++;
            }
        }
        CommandUtil.success(src, "&a已为 &e" + added + " &a名玩家添加每日任务 #" + taskId
                + (dup > 0 ? " &7（" + dup + " 名玩家今日已拥有该任务）" : ""));
        return 1;
    }

    private static int dailyTaskRemove(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        int taskId = IntegerArgumentType.getInteger(ctx, "task");
        String date = DailyTaskService.today();
        int removed = 0;
        for (ServerPlayer p : targets) {
            if (DailyTaskService.removeTask(p.getUUID(), date, taskId)) {
                DailyTaskService.updateScoreboard(p, date);
                removed++;
            }
        }
        if (removed == 0) {
            CommandUtil.failure(src, "&c目标玩家今日都没有任务 #" + taskId + "！");
            return 0;
        }
        CommandUtil.success(src, "&a已为 &e" + removed + " &a名玩家移除每日任务 #" + taskId);
        return 1;
    }

    private static int dailyTaskProgress(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        int taskId = IntegerArgumentType.getInteger(ctx, "task");
        int delta = IntegerArgumentType.getInteger(ctx, "delta");
        String date = DailyTaskService.today();
        int done = 0, missing = 0, completedNow = 0;
        for (ServerPlayer p : targets) {
            DailyTaskService.ProgressResult r = DailyTaskService.adjustProgress(p.getUUID(), date, taskId, delta);
            if (!r.found()) {
                missing++;
                continue;
            }
            DailyTaskService.updateScoreboard(p, date);
            done++;
            if (r.paidNow()) {
                completedNow++;
            }
        }
        if (done == 0) {
            CommandUtil.failure(src, "&c目标玩家今日均没有任务 #" + taskId + "！");
            return 0;
        }
        CommandUtil.success(src, "&a已" + (delta > 0 ? "增加" : "减少") + " &e" + done
                + " &a名玩家的任务 #" + taskId + " 进度 &e" + Math.abs(delta)
                + (completedNow > 0 ? " &7（" + completedNow + " 名玩家因此完成并获得奖励）" : ""));
        if (missing > 0) {
            CommandUtil.failure(src, "&c另有 &e" + missing + " &c名玩家今日没有任务 #" + taskId + "，已跳过");
        }
        return 1;
    }

    private static int dailyTaskView(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
        cn.choosec.economy.util.TaskNames.load(src.getServer());
        boolean zh = "zh_cn".equalsIgnoreCase(ConfigManager.get().scoreboardLanguage);
        String date = DailyTaskService.today();
        for (ServerPlayer p : targets) {
            List<DailyTaskService.Daily> list = DailyTaskService.getDaily(p.getUUID(), date);
            src.sendSystemMessage(MessageUtil.parse("&6======= " + p.getName().getString() + " 今日任务 (" + list.size() + ") ======="));
            if (list.isEmpty()) {
                src.sendSystemMessage(MessageUtil.parse("&e（今日未分配任务）"));
            }
            for (DailyTaskService.Daily d : list) {
                net.minecraft.network.chat.MutableComponent line = MessageUtil.parse("&e").copy();
                line.append(Component.literal(cn.choosec.economy.util.TaskNames.typeLabel(d.type(), zh)));
                line.append(MessageUtil.parse(" "));
                line.append(cn.choosec.economy.util.TaskNames.target(d.type(), d.target()));
                line.append(MessageUtil.parse(d.completed() ? " &a[完成]" : " &7(" + d.progress() + "/" + d.amount() + ")"));
                line.append(MessageUtil.parse(" &a" + MoneyUtil.format(d.reward()) + " " + ConfigManager.get().currencyAbbreviation));
                src.sendSystemMessage(line);
            }
            src.sendSystemMessage(MessageUtil.parse("&6================================================"));
        }
        return 1;
    }

    private static int redPacketCreate(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer p = src.getPlayerOrException();
        String type = StringArgumentType.getString(ctx, "type").toLowerCase();
        if (!type.equals("lucky") && !type.equals("normal")) {
            CommandUtil.failure(src, "&c红包类型必须是 lucky / normal！");
            return 0;
        }
        BigDecimal total = MoneyUtil.norm(DoubleArgumentType.getDouble(ctx, "total"));
        int count = IntegerArgumentType.getInteger(ctx, "count");
        BigDecimal minPerGrab = MoneyUtil.minUnit();
        if (total.compareTo(minPerGrab.multiply(BigDecimal.valueOf(count))) < 0) {
            CommandUtil.failure(src, "&c红包总额不足！每份至少 &e" + MoneyUtil.format(minPerGrab)
                    + " " + ConfigManager.get().currencyAbbreviation);
            return 0;
        }
        boolean lucky = type.equals("lucky");
        int id = cn.choosec.economy.service.RedPacketService.create(p, lucky, total, count);
        if (id == -2) { CommandUtil.failure(src, "&c余额不足！"); return 0; }
        if (id < 0) { CommandUtil.failure(src, "&c发红包失败！"); return 0; }
        cn.choosec.economy.service.RedPacketService.broadcast(id, p, lucky, total, count);
        return 1;
    }

    private static int redPacketGrab(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer p = src.getPlayerOrException();
        int id = IntegerArgumentType.getInteger(ctx, "id");
        cn.choosec.economy.service.RedPacketService.GrabResult g = cn.choosec.economy.service.RedPacketService.grab(p, id);
        switch (g.result()) {
            case SUCCESS -> CommandUtil.successQuiet(src, "&a抢到红包 &e" + MoneyUtil.format(g.amount())
                    + " " + ConfigManager.get().currencyAbbreviation + "！");
            case NOT_FOUND -> CommandUtil.failure(src, "&c红包不存在！");
            case ALREADY_TAKEN -> CommandUtil.failure(src, "&c你已经抢过这个红包了！");
            case EXHAUSTED -> CommandUtil.failure(src, "&c红包已被抢完！");
            case NO_FUNDS, ERROR -> CommandUtil.failure(src, "&c抢红包失败！");
        }
        return g.result() == cn.choosec.economy.service.RedPacketService.Result.SUCCESS ? 1 : 0;
    }

    private static int ecoLog(CommandContext<CommandSourceStack> ctx)  throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        if (!opOrFail(src)) return 0;
        String pname = StringArgumentType.getString(ctx, "player");
        Target t = resolveTarget(src, pname);
        if (t == null) { CommandUtil.failure(src, "&c找不到玩家 &e" + pname + " &c！"); return 0; }
        List<String[]> log = EconomyService.getLog(t.uuid(), 20);
        if (log.isEmpty()) {
            CommandUtil.successQuiet(src, "&e" + t.name() + " 暂无流水。");
            return 1;
        }
        src.sendSystemMessage(MessageUtil.parse("&6======= " + t.name() + " 流水 &6======="));
        for (String[] row : log) {
            src.sendSystemMessage(MessageUtil.parse("&7" + row[0] + " &e" + row[1] + " " + ConfigManager.get().currencyAbbreviation + " &7" + row[2]));
        }
        src.sendSystemMessage(MessageUtil.parse("&6================================"));
        return 1;
    }


    /** Resolved target for offline-capable admin commands: online -> existing account -> profile cache. */
    private record Target(UUID uuid, String name) {
    }

    private static Target resolveTarget(CommandSourceStack src, String name) {
        MinecraftServer server = src.getServer();
        if (server != null) {
            // 1) online player
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (p.getName().getString().equalsIgnoreCase(name)) {
                    return new Target(p.getUUID(), p.getName().getString());
                }
            }
        }
        // 2) existing account in the balances table
        UUID byName = EconomyService.uuidByName(name);
        if (byName != null) {
            return new Target(byName, name);
        }
        // 3) server profile cache (players who have played before)
        if (server != null) {
            Optional<NameAndId> nid = server.services().nameToIdCache().get(name);
            if (nid.isPresent()) {
                return new Target(nid.get().id(), name);
            }
        }
        return null;
    }

    private static String lastNode(CommandContext<CommandSourceStack> ctx) {
        return ctx.getNodes().get(ctx.getNodes().size() - 1).getNode().getName();
    }
}
