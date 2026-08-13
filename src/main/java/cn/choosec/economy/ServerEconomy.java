package cn.choosec.economy;

import cn.choosec.economy.command.CommandRegistry;
import cn.choosec.economy.command.LegacyCommands;
import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.service.DailyTaskService;
import cn.choosec.economy.service.MailboxService;
import cn.choosec.economy.service.MarketInput;
import cn.choosec.economy.service.PreservedService;
import cn.choosec.economy.service.TaskService;
import cn.choosec.economy.service.TradeService;
import cn.choosec.economy.ticker.EconomyTicker;
import cn.choosec.economy.util.MessageUtil;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

public class ServerEconomy implements ModInitializer {
    public static final String MOD_ID = "servereconomy";
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

    /** Current server instance, used to resolve online players from balance events. */
    private static volatile MinecraftServer server;

    @Override
    public void onInitialize() {
        LOGGER.info("ServerEconomy initializing...");

        CommandRegistry.init();

        // --- event-driven balance gating: disable paid abilities the moment a
        // balance reaches zero, instead of waiting for the next periodic billing tick.
        EconomyService.addBalanceListener(ServerEconomy::onBalanceChanged);

        // --- preserved ServerRules behaviour ---
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // JOIN fires on the network thread; everything below is shared with the
            // main-thread ticker, so defer all mutations to the server thread.
            server.execute(() -> {
                ServerPlayer player = handler.player;
                PreservedService.updateTabForPlayer(player);
                PreservedService.updatePlayerDisplayName(player);
                DailyTaskService.onPlayerQuit(player.getUUID()); // fresh client -> send objective ADD
                DailyTaskService.ensureDaily(player, DailyTaskService.today());
                DailyTaskService.updateScoreboard(player, DailyTaskService.today());
                int mails = MailboxService.count(player.getUUID());
                if (mails > 0) {
                    player.sendSystemMessage(MessageUtil.parse("&e你有 &a" + mails + " &e封邮件待领取，使用 /mails 打开邮箱！"));
                }
            });
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            server.execute(() -> {
                DailyTaskService.onPlayerQuit(handler.player.getUUID());
                MarketInput.clear(handler.player.getUUID());
                PreservedService.clearTabCache(handler.player.getUUID());
                EconomyTicker.flushFlight(handler.player.getUUID());
            });
        });

        ServerLivingEntityEvents.AFTER_DEATH.register(ServerEconomy::onDeath);
        PlayerBlockBreakEvents.AFTER.register(ServerEconomy::onBlockBreak);

        // "use" daily tasks are tracked from the vanilla "used" (ITEM_USED)
        // statistic in ServerPlayerStatsMixin. That statistic is awarded whenever an
        // item is actually used/consumed — right-click use, eating/drinking, throwing,
        // and firework ground launches (which never fire UseItemCallback) — so this is
        // the single, vanilla-native source of truth for "use" progress.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            // 市场数量输入：玩家在等待输入购买/供货数量时，拦截下一条聊天消息
            MarketInput.Pending pending = MarketInput.poll(sender.getUUID());
            if (pending != null) {
                handleMarketInput(sender, pending, message.signedContent());
                return false;
            }
            String title = PreservedService.getTitle(sender.getUUID());
            if (title != null && !title.isEmpty()) {
                MinecraftServer server = sender.level().getServer();
                if (server != null) {
                    Component prefix = MessageUtil.parse(title);
                    MutableComponent chat = prefix.copy().append(Component.literal("<" + sender.getName().getString() + "> "))
                            .append(message.signedContent());
                    server.getPlayerList().broadcastSystemMessage(chat, false);
                }
                return false;
            }
            return true;
        });

        // --- lifecycle: init config/db at start, close db at stop ---
        ServerLifecycleEvents.SERVER_STARTING.register(ServerEconomy::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EconomyTicker.flushAllFlight(server);
            ServerEconomy.server = null;
            DatabaseManager.close();
        });

        // --- periodic billing ---
        ServerTickEvents.END_SERVER_TICK.register(EconomyTicker::onTick);

        LOGGER.info("ServerEconomy initialized!");
    }

    private static void onServerStarting(MinecraftServer server) {
        ServerEconomy.server = server;
        try {
            Path configDir = FabricLoader.getInstance().getConfigDir();
            ConfigManager.init(configDir);
            DatabaseManager.init(configDir, ConfigManager.get().database);
            PreservedService.loadTitles();
            int purged = EconomyService.purgeOldTransactions(ConfigManager.get().database.transactionRetentionDays);
            if (purged > 0) {
                LOGGER.info("[ServerEconomy] Purged {} stale transaction rows", purged);
            }
            LOGGER.info("ServerEconomy ready (config={}, db={})", configDir.resolve("servereconomy.json"),
                    ConfigManager.get().database.type);
        } catch (Exception e) {
            LOGGER.error("ServerEconomy failed to initialize storage", e);
        }
    }

    /** Disable flight (and future zero-balance gates) as soon as a balance hits zero. */
    private static void onBalanceChanged(UUID uuid, BigDecimal newBalance) {
        if (newBalance == null || newBalance.compareTo(BigDecimal.ZERO) > 0) {
            return;
        }
        MinecraftServer srv = server;
        if (srv == null) {
            return;
        }
        BigDecimal rate = ConfigManager.get().rates.flightPerSecond;
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Runnable disable = () -> {
            ServerPlayer p = srv.getPlayerList().getPlayer(uuid);
            if (p == null || p.isSpectator() || p.getAbilities().instabuild) {
                return;
            }
            if (!(p.getAbilities().mayfly || p.getAbilities().flying)) {
                return;
            }
            p.getAbilities().mayfly = false;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.sendSystemMessage(MessageUtil.parse("&c余额为0，已自动禁用飞行！"));
        };
        if (srv.isSameThread()) {
            disable.run();
        } else {
            try {
                srv.execute(disable);
            } catch (RuntimeException e) {
                // Server is shutting down; the player is already disconnecting.
            }
        }
    }

    /** Parse a market number typed in chat and execute the requested action. */
    private static void handleMarketInput(ServerPlayer sp, MarketInput.Pending pending, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty() || text.equals("0") || text.equalsIgnoreCase("c") || text.equalsIgnoreCase("cancel")) {
            sp.sendSystemMessage(MessageUtil.parse("&c已取消操作。"));
            return;
        }
        MarketInput.Action action = pending.action();
        if (action == MarketInput.Action.REPRICE) {
            BigDecimal price;
            try {
                price = new BigDecimal(text);
            } catch (NumberFormatException e) {
                MarketInput.setPending(sp.getUUID(), pending.orderId(), action);
                sp.sendSystemMessage(MessageUtil.parse("&c价格无效，请输入正数（如 12.5），或输入 0/c 取消："));
                return;
            }
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                MarketInput.setPending(sp.getUUID(), pending.orderId(), action);
                sp.sendSystemMessage(MessageUtil.parse("&c价格必须大于 0，请重新输入，或输入 0/c 取消："));
                return;
            }
            switch (TradeService.reprice(sp, pending.orderId(), price)) {
                case SUCCESS -> sp.sendSystemMessage(MessageUtil.parse("&a已改价 &e#" + pending.orderId()
                        + " &a为单价 &e" + MoneyUtil.format(price) + " " + ConfigManager.get().currencyAbbreviation));
                case NOT_FOUND -> sp.sendSystemMessage(MessageUtil.parse("&c该订单不存在！"));
                case NOT_OWNER -> sp.sendSystemMessage(MessageUtil.parse("&c这不是你的订单！"));
                case INVALID_PRICE -> sp.sendSystemMessage(MessageUtil.parse("&c价格无效！"));
                case NO_FUNDS -> sp.sendSystemMessage(MessageUtil.parse("&c余额不足！改价需要补充托管金！"));
                default -> sp.sendSystemMessage(MessageUtil.parse("&c改价失败！"));
            }
            return;
        }
        int count;
        try {
            count = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            MarketInput.setPending(sp.getUUID(), pending.orderId(), action);
            sp.sendSystemMessage(MessageUtil.parse("&c数量无效，请输入正整数（如 32），或输入 0/c 取消："));
            return;
        }
        if (count <= 0) {
            MarketInput.setPending(sp.getUUID(), pending.orderId(), action);
            sp.sendSystemMessage(MessageUtil.parse("&c数量必须大于 0，请重新输入，或输入 0/c 取消："));
            return;
        }
        switch (action) {
            case SUPPLY -> {
                switch (TradeService.fulfill(sp, pending.orderId(), count)) {
                    case SUCCESS -> sp.sendSystemMessage(MessageUtil.parse("&a已供货 &e" + count + " &a个并收到货款！"));
                    case NOT_FOUND -> sp.sendSystemMessage(MessageUtil.parse("&c该求购单不存在！"));
                    case NOT_BUY_ORDER -> sp.sendSystemMessage(MessageUtil.parse("&c该订单不是求购单！"));
                    case NO_ITEMS -> sp.sendSystemMessage(MessageUtil.parse("&c背包中没有足够的物品！"));
                    case INVALID_COUNT -> sp.sendSystemMessage(MessageUtil.parse("&c数量无效！"));
                    default -> sp.sendSystemMessage(MessageUtil.parse("&c供货失败！"));
                }
            }
            case BUY -> {
                switch (TradeService.buy(sp, pending.orderId(), count)) {
                    case SUCCESS -> sp.sendSystemMessage(MessageUtil.parse("&a已购买 &e" + count + " &a个！"));
                    case NOT_FOUND -> sp.sendSystemMessage(MessageUtil.parse("&c该出售单不存在！"));
                    case IS_BUY_ORDER -> sp.sendSystemMessage(MessageUtil.parse("&c该订单不是出售单！"));
                    case OWN_ITEM -> sp.sendSystemMessage(MessageUtil.parse("&c不能购买自己的物品！"));
                    case NO_FUNDS -> sp.sendSystemMessage(MessageUtil.parse("&c余额不足！"));
                    case NO_SPACE -> sp.sendSystemMessage(MessageUtil.parse("&c背包空间不足！"));
                    case INVALID_COUNT -> sp.sendSystemMessage(MessageUtil.parse("&c数量无效！"));
                    default -> sp.sendSystemMessage(MessageUtil.parse("&c购买失败！"));
                }
            }
            case RESTOCK -> {
                switch (TradeService.restock(sp, pending.orderId(), count)) {
                    case SUCCESS -> sp.sendSystemMessage(MessageUtil.parse("&a已补充 &e" + count + " &a个！"));
                    case NOT_FOUND -> sp.sendSystemMessage(MessageUtil.parse("&c该订单不存在！"));
                    case NOT_OWNER -> sp.sendSystemMessage(MessageUtil.parse("&c这不是你的订单！"));
                    case INVALID_COUNT -> sp.sendSystemMessage(MessageUtil.parse("&c数量无效！"));
                    case NO_ITEMS -> sp.sendSystemMessage(MessageUtil.parse("&c背包中没有足够的物品！"));
                    case NO_FUNDS -> sp.sendSystemMessage(MessageUtil.parse("&c余额不足！补货需预支托管金！"));
                    default -> sp.sendSystemMessage(MessageUtil.parse("&c补货失败！"));
                }
            }
            case REPRICE -> {
                // unreachable: handled above
            }
        }
    }

    private static void onBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (player instanceof ServerPlayer sp) {
            DailyTaskService.addProgress(sp, "mine", state.getBlock().builtInRegistryHolder().getRegisteredName());
        }
    }

    private static void onDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof ServerPlayer player) {
            LegacyCommands.saveBackOnDeath(player);
        }
        // credit the killer's daily tasks (kill type)
        if (source.getEntity() instanceof ServerPlayer killer) {
            DailyTaskService.addProgress(killer, "kill", EntityType.getKey(entity.getType()).toString());
        }
    }
}
