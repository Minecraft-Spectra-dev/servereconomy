package cn.choosec.economy.ui;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.service.MarketInput;
import cn.choosec.economy.service.MarketInput.Action;
import cn.choosec.economy.service.TradeService;
import cn.choosec.economy.util.MessageUtil;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla chest GUI for managing the viewer's own market listings.
 *
 * <p>The bottom control row switches between an operation mode (下架/改价/补货)
 * and flips pages. Left-clicking a listing applies the selected mode: cancel
 * refunds the items/escrow, reprice closes the GUI and asks for a new unit
 * price in chat, and restock asks for an amount to add. The listing area is
 * read-only.
 */
public class MyMarketMenu extends ChestMenu {

    private static final int ITEM_ROWS = 5;
    private static final int ITEM_SLOTS = ITEM_ROWS * 9;
    private static final int SLOTS = 54;
    private static final int PAGE_SIZE = ITEM_SLOTS;

    private static final int PREV_SLOT = 45;
    private static final int CANCEL_SLOT = 46;
    private static final int REPRICE_SLOT = 47;
    private static final int RESTOCK_SLOT = 48;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private enum Mode {
        NONE("无"),
        CANCEL("下架"),
        REPRICE("改价"),
        RESTOCK("补货");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private final ServerPlayer player;
    private final SimpleContainer container;
    private final List<TradeService.Listing> listings = new ArrayList<>();
    private Mode mode = Mode.NONE;
    private int page = 0;

    public MyMarketMenu(int id, Inventory playerInventory, ServerPlayer player) {
        super(MenuType.GENERIC_9x6, id, playerInventory, new SimpleContainer(SLOTS), 6);
        this.player = player;
        this.container = (SimpleContainer) getContainer();
        reload();
    }

    private int pageCount() {
        return Math.max(1, (listings.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void reload() {
        listings.clear();
        listings.addAll(TradeService.listBySeller(player.getUUID()));
        page = Math.max(0, Math.min(page, pageCount() - 1));
        refresh();
    }

    private void refresh() {
        container.clearContent();
        int start = page * PAGE_SIZE;
        int end = Math.min(listings.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int idx = start; idx < end; idx++) {
            TradeService.Listing l = listings.get(idx);
            ItemStack s = TradeService.buildItem(l, player.level().getServer().registryAccess());
            if (!s.isEmpty()) {
                s.setCount(1);
                s.set(DataComponents.LORE, tooltip(l));
            }
            container.setItem(slot, s);
            slot++;
        }

        container.setItem(PREV_SLOT, navButton("minecraft:arrow", "&6上一页",
                page > 0 ? "&7点击翻到上一页" : "&c已是第一页"));
        container.setItem(CANCEL_SLOT, navButton("minecraft:barrier",
                mode == Mode.CANCEL ? "&c[开启] 下架" : "&7下架（点击开启）",
                "&7开启后点击商品即下架", "&7出售退回物品，求购退回托管货款"));
        container.setItem(REPRICE_SLOT, navButton("minecraft:gold_ingot",
                mode == Mode.REPRICE ? "&6[开启] 改价" : "&7改价（点击开启）",
                "&7开启后点击商品，在聊天栏输入新单价"));
        container.setItem(RESTOCK_SLOT, navButton("minecraft:chest",
                mode == Mode.RESTOCK ? "&a[开启] 补货" : "&7补货（点击开启）",
                "&7开启后点击商品，在聊天栏输入补充数量"));
        container.setItem(INFO_SLOT, navButton("minecraft:book",
                "&e第 &a" + (page + 1) + "&e/&a" + pageCount() + " &e页",
                listings.isEmpty() ? "&e你还没有上架商品" : "&7共 &a" + listings.size() + " &7条你的订单",
                "&7当前操作：&a" + mode.label()));
        container.setItem(NEXT_SLOT, navButton("minecraft:arrow", "&6下一页",
                page + 1 < pageCount() ? "&7点击翻到下一页" : "&c已是最后一页"));
        broadcastChanges();
    }

    private ItemLore tooltip(TradeService.Listing l) {
        boolean buy = "BUY".equalsIgnoreCase(l.type());
        String cur = ConfigManager.get().currencyAbbreviation;
        List<Component> lines = new ArrayList<>();
        lines.add(MessageUtil.parse((buy ? "&b[求购] " : "&a[出售] ") + "&e#" + l.id()));
        lines.add(MessageUtil.parse((buy ? "&7求购数量 &f" : "&7库存 &f") + l.count()
                + " &7单价 &f" + MoneyUtil.format(l.price()) + " " + cur));
        lines.add(MessageUtil.parse("&7当前操作：&a" + mode.label()));
        lines.add(MessageUtil.parse("&8左键点击执行所选操作"));
        return new ItemLore(lines);
    }

    private static ItemStack navButton(String itemId, String name, String... tips) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        ItemStack s = item == null ? ItemStack.EMPTY.copy() : item.getDefaultInstance();
        s.set(DataComponents.CUSTOM_NAME, MessageUtil.parse(name));
        List<Component> lines = new ArrayList<>();
        for (String tip : tips) {
            lines.add(MessageUtil.parse(tip));
        }
        s.set(DataComponents.LORE, new ItemLore(lines));
        return s;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player p) {
        if (slotId >= 0 && slotId < SLOTS) {
            if (p instanceof ServerPlayer sp) {
                if (slotId >= ITEM_SLOTS) {
                    handleControl(sp, slotId);
                } else if (input == ContainerInput.PICKUP) {
                    int idx = page * PAGE_SIZE + slotId;
                    if (idx < listings.size()) {
                        handleListing(sp, listings.get(idx));
                    }
                }
                // Push the authoritative menu state immediately so the client's
                // click prediction can never leave stale slots behind.
                sendAllDataToRemote();
            }
            return; // swallow click so no item is moved
        }
        super.clicked(slotId, button, input, p);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int slot) {
        return ItemStack.EMPTY; // the whole menu is read-only
    }

    private void handleControl(ServerPlayer sp, int slotId) {
        if (slotId == PREV_SLOT && page > 0) {
            page--;
            refresh();
        } else if (slotId == NEXT_SLOT && page + 1 < pageCount()) {
            page++;
            refresh();
        } else if (slotId == CANCEL_SLOT) {
            mode = mode == Mode.CANCEL ? Mode.NONE : Mode.CANCEL;
            refresh();
        } else if (slotId == REPRICE_SLOT) {
            mode = mode == Mode.REPRICE ? Mode.NONE : Mode.REPRICE;
            refresh();
        } else if (slotId == RESTOCK_SLOT) {
            mode = mode == Mode.RESTOCK ? Mode.NONE : Mode.RESTOCK;
            refresh();
        }
    }

    private void handleListing(ServerPlayer sp, TradeService.Listing l) {
        switch (mode) {
            case NONE -> sp.sendSystemMessage(MessageUtil.parse("&7请先点击下方按钮选择操作：下架 / 改价 / 补货。"));
            case CANCEL -> cancelListing(sp, l);
            case REPRICE -> requestReprice(sp, l);
            case RESTOCK -> requestRestock(sp, l);
        }
    }

    private void cancelListing(ServerPlayer sp, TradeService.Listing l) {
        switch (TradeService.cancel(sp, l.id())) {
            case SUCCESS -> sp.sendSystemMessage(MessageUtil.parse("&a已下架 &e#" + l.id() + " &a，物品/货款已全额退回。"));
            case NOT_FOUND -> sp.sendSystemMessage(MessageUtil.parse("&c该订单不存在！"));
            case NOT_OWNER -> sp.sendSystemMessage(MessageUtil.parse("&c这不是你的订单！"));
            default -> sp.sendSystemMessage(MessageUtil.parse("&c下架失败！"));
        }
        reload();
    }

    private void requestReprice(ServerPlayer sp, TradeService.Listing l) {
        MarketInput.setPending(sp.getUUID(), l.id(), Action.REPRICE);
        sp.closeContainer();
        sp.sendSystemMessage(MessageUtil.parse("&e请输入 &e#" + l.id() + " &e新的&a单价&e（当前 &f"
                + MoneyUtil.format(l.price()) + " " + ConfigManager.get().currencyAbbreviation
                + "&e），直接发一条聊天消息即可，输入 &c0/c&e 取消："));
    }

    private void requestRestock(ServerPlayer sp, TradeService.Listing l) {
        boolean buy = "BUY".equalsIgnoreCase(l.type());
        MarketInput.setPending(sp.getUUID(), l.id(), Action.RESTOCK);
        sp.closeContainer();
        sp.sendSystemMessage(MessageUtil.parse(buy
                ? "&e请输入要&a增加求购&e的数量（将按当前单价&f" + MoneyUtil.format(l.price())
                + "&e预支托管金），直接发一条聊天消息即可，输入 &c0/c&e 取消："
                : "&e请输入要&a补货&e的数量（从背包扣除同 id 物品），直接发一条聊天消息即可，输入 &c0/c&e 取消："));
    }
}
