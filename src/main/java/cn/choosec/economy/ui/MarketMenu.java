package cn.choosec.economy.ui;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.service.MarketInput;
import cn.choosec.economy.service.MarketInput.Action;
import cn.choosec.economy.service.TradeService;
import cn.choosec.economy.util.MessageUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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

/**
 * Vanilla chest GUI for the player marketplace, with paging, filtering and
 * sorting.
 *
 * <p>The first five rows show the current page's listings (SELL and BUY/求购).
 * The bottom row is a control bar: left-click the arrows to flip pages, the
 * filter button cycles 全部/出售/求购, the sort button cycles 最新/价格升序/价格降序
 * and the middle slot shows the current page. Clicking a listing (left, right,
 * shift — any click) selects it and closes the GUI, then the quantity is typed
 * in chat (buy for SELL orders, supply for BUY orders); only one selection is
 * allowed at a time. The listing area is read-only (no item can be taken out).
 */
public class MarketMenu extends ChestMenu {

    private static final int ITEM_ROWS = 5;
    private static final int ITEM_SLOTS = ITEM_ROWS * 9;
    private static final int SLOTS = 54;
    private static final int PAGE_SIZE = ITEM_SLOTS;

    private static final int PREV_SLOT = 45;
    private static final int FILTER_SLOT = 46;
    private static final int SORT_SLOT = 47;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private enum Filter {
        ALL("全部"),
        SELL("出售"),
        BUY("求购");

        private final String label;

        Filter(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private enum Sort {
        TIME("最新"),
        PRICE_ASC("价格从低到高"),
        PRICE_DESC("价格从高到低");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final ServerPlayer player;
    private final SimpleContainer container;
    private final List<TradeService.Listing> all = new ArrayList<>();
    private List<TradeService.Listing> view = new ArrayList<>();
    private Filter filter = Filter.ALL;
    private Sort sort = Sort.TIME;
    private int page = 0;

    public MarketMenu(int id, Inventory playerInventory, ServerPlayer player) {
        super(MenuType.GENERIC_9x6, id, playerInventory, new SimpleContainer(SLOTS), 6);
        this.player = player;
        this.container = (SimpleContainer) getContainer();
        reloadAll();
    }

    private int pageCount() {
        return Math.max(1, (view.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void reloadAll() {
        all.clear();
        all.addAll(TradeService.listListings());
        rebuildView();
        refresh();
    }

    private void rebuildView() {
        Comparator<TradeService.Listing> cmp = switch (sort) {
            case TIME -> Comparator.comparingInt(TradeService.Listing::id).reversed();
            case PRICE_ASC -> Comparator.comparing(TradeService.Listing::price)
                    .thenComparing(Comparator.comparingInt(TradeService.Listing::id).reversed());
            case PRICE_DESC -> Comparator.comparing(TradeService.Listing::price).reversed()
                    .thenComparing(Comparator.comparingInt(TradeService.Listing::id).reversed());
        };
        view = all.stream()
                .filter(l -> switch (filter) {
                    case ALL -> true;
                    case SELL -> "SELL".equalsIgnoreCase(l.type());
                    case BUY -> "BUY".equalsIgnoreCase(l.type());
                })
                .sorted(cmp)
                .collect(Collectors.toList());
        page = Math.max(0, Math.min(page, pageCount() - 1));
    }

    private void refresh() {
        container.clearContent();
        int start = page * PAGE_SIZE;
        int end = Math.min(view.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int idx = start; idx < end; idx++) {
            TradeService.Listing l = view.get(idx);
            ItemStack s = TradeService.buildItem(l, player.level().getServer().registryAccess());
            if (!s.isEmpty()) {
                s.setCount(1); // 槽位不显示堆叠数量，总量见详细信息
                s.set(DataComponents.LORE, tooltip(l));
            }
            container.setItem(slot, s);
            slot++;
        }
        // bottom control row
        container.setItem(PREV_SLOT, navButton("minecraft:arrow", "&6上一页",
                page > 0 ? "&7点击翻到上一页" : "&c已是第一页"));
        container.setItem(FILTER_SLOT, navButton("minecraft:paper",
                "&6筛选：" + filter.label(),
                "&7当前：&a" + filter.label(),
                "&7点击切换：全部 → 出售 → 求购"));
        container.setItem(SORT_SLOT, navButton("minecraft:comparator",
                "&6排序：" + sort.label(),
                "&7当前：&a" + sort.label(),
                "&7点击切换：最新 → 价格↑ → 价格↓"));
        container.setItem(INFO_SLOT, navButton("minecraft:book",
                "&e第 &a" + (page + 1) + "&e/&a" + pageCount() + " &e页",
                "&7共 &a" + view.size() + " &7条订单，每页 " + PAGE_SIZE + " 条",
                "&7筛选 &a" + filter.label() + " &7· 排序 &a" + sort.label()));
        container.setItem(NEXT_SLOT, navButton("minecraft:arrow", "&6下一页",
                page + 1 < pageCount() ? "&7点击翻到下一页" : "&c已是最后一页"));
        broadcastChanges();
    }

    private ItemLore tooltip(TradeService.Listing l) {
        boolean buy = "BUY".equalsIgnoreCase(l.type());
        String cur = ConfigManager.get().currencyAbbreviation;
        List<Component> lines = new ArrayList<>();
        lines.add(MessageUtil.parse("&8#" + l.id() + " " + (buy ? "&e收购" : "&a出售")
                + " &8· &6单价 &f" + MoneyUtil.format(l.price()) + " " + cur));
        lines.add(MessageUtil.parse("&7总量 &8· &f" + l.count()));
        lines.add(MessageUtil.parse("&7商家 &8· &f" + TradeService.sellerName(player.level().getServer(), l.seller())));
        if (buy) {
            BigDecimal escrow = l.price().multiply(BigDecimal.valueOf(l.count()));
            BigDecimal receive = MoneyUtil.minusPercent(escrow, ConfigManager.get().rates.tradeFeePercent);
            lines.add(MessageUtil.parse("&8点击选中后输入供货数量，可收到货款 &e" + MoneyUtil.format(receive) + " " + cur));
        } else {
            lines.add(MessageUtil.parse("&8点击选中后输入购买数量"));
        }
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
                } else {
                    // 左键 / Shift / 右键等所有点击一视同仁：选中该商品后，在聊天栏输入购买/供货数量
                    if (MarketInput.hasPending(sp.getUUID())) {
                        // 防止一键整理/连点等误操作选中多个商品
                        sp.sendSystemMessage(MessageUtil.parse("&c你已有待输入的操作，请先输入 &e0/c &c取消后再选择其它商品！"));
                    } else {
                        int idx = page * PAGE_SIZE + slotId;
                        if (idx < view.size()) {
                            requestQuantity(view.get(idx), sp);
                        }
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

    /** Click a listing: close the GUI and wait for a typed quantity via chat. */
    private void requestQuantity(TradeService.Listing l, ServerPlayer sp) {
        TradeService.Listing fresh = TradeService.getListing(l.id());
        if (fresh == null) {
            sp.sendSystemMessage(MessageUtil.parse("&c该订单已不存在。"));
            reloadAll();
            return;
        }
        boolean supply = "BUY".equalsIgnoreCase(fresh.type());
        MarketInput.setPending(sp.getUUID(), fresh.id(), supply ? Action.SUPPLY : Action.BUY);
        sp.closeContainer();
        // 选中提示：展示商品详细信息，再让玩家输入数量
        ItemStack item = TradeService.buildItem(fresh, sp.level().getServer().registryAccess());
        String itemName = item.isEmpty() ? fresh.itemId() : item.getHoverName().getString();
        String cur = ConfigManager.get().currencyAbbreviation;
        sp.sendSystemMessage(MessageUtil.parse("&6===== 选中" + (supply ? "供货订单" : "购买订单") + " #" + fresh.id() + " ====="));
        sp.sendSystemMessage(MessageUtil.parse("&f" + itemName + " &7x" + fresh.count()
                + " &7· 单价 &6" + MoneyUtil.format(fresh.price()) + " " + cur
                + " &7· " + (supply ? "还需" : "剩余") + " &f" + fresh.count()));
        sp.sendSystemMessage(MessageUtil.parse("&7商家 &f" + TradeService.sellerName(sp.level().getServer(), fresh.seller())));
        sp.sendSystemMessage(MessageUtil.parse(supply
                ? "&e请输入要&a供货&e的数量（最多 &a" + fresh.count() + "&e），直接发一条聊天消息即可，输入 &c0/c&e 取消："
                : "&e请输入要&a购买&e的数量（最多 &a" + fresh.count() + "&e），直接发一条聊天消息即可，输入 &c0/c&e 取消："));
    }

    private void handleControl(ServerPlayer sp, int slotId) {
        if (slotId == PREV_SLOT && page > 0) {
            page--;
            refresh();
        } else if (slotId == NEXT_SLOT && page + 1 < pageCount()) {
            page++;
            refresh();
        } else if (slotId == FILTER_SLOT) {
            filter = filter.next();
            page = 0;
            rebuildView();
            refresh();
        } else if (slotId == SORT_SLOT) {
            sort = sort.next();
            page = 0;
            rebuildView();
            refresh();
        }
    }
}
