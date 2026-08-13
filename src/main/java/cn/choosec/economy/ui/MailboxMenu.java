package cn.choosec.economy.ui;

import cn.choosec.economy.service.MailboxService;
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
 * Vanilla chest GUI for the player's mailbox. Each slot shows a stored item
 * (single icon, no stack count); clicking it claims the item into the player's
 * inventory. Paged like the marketplace.
 */
public class MailboxMenu extends ChestMenu {

    private static final int ITEM_ROWS = 5;
    private static final int ITEM_SLOTS = ITEM_ROWS * 9;
    private static final int SLOTS = 54;
    private static final int PAGE_SIZE = ITEM_SLOTS;

    private static final int PREV_SLOT = 45;
    private static final int INFO_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final ServerPlayer player;
    private final List<MailboxService.Mail> mails;
    private final SimpleContainer container;
    private int page = 0;

    public MailboxMenu(int id, Inventory playerInventory, ServerPlayer player) {
        super(MenuType.GENERIC_9x6, id, playerInventory, new SimpleContainer(SLOTS), 6);
        this.player = player;
        this.container = (SimpleContainer) getContainer();
        this.mails = new ArrayList<>(MailboxService.list(player.getUUID()));
        refresh();
    }

    private int pageCount() {
        return Math.max(1, (mails.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void refresh() {
        page = Math.max(0, Math.min(page, pageCount() - 1));
        container.clearContent();
        int start = page * PAGE_SIZE;
        int end = Math.min(mails.size(), start + PAGE_SIZE);
        int slot = 0;
        for (int idx = start; idx < end; idx++) {
            MailboxService.Mail m = mails.get(idx);
            ItemStack s = MailboxService.build(m, player.level().getServer().registryAccess());
            if (!s.isEmpty()) {
                s.setCount(1); // 槽位不显示堆叠数量
                List<Component> lines = new ArrayList<>();
                lines.add(MessageUtil.parse("&7共 &f" + m.count() + " &7个"));
                lines.add(MessageUtil.parse("&a点击领取到背包"));
                s.set(DataComponents.LORE, new ItemLore(lines));
            }
            container.setItem(slot, s);
            slot++;
        }
        container.setItem(PREV_SLOT, navButton("minecraft:arrow", "&6上一页",
                page > 0 ? "&7点击翻到上一页" : "&c已是第一页"));
        container.setItem(INFO_SLOT, navButton("minecraft:book", "&e第 &a" + (page + 1) + "&e/&a" + pageCount() + " &e页",
                "&7共 &a" + mails.size() + " &7封邮件"));
        container.setItem(NEXT_SLOT, navButton("minecraft:arrow", "&6下一页",
                page + 1 < pageCount() ? "&7点击翻到下一页" : "&c已是最后一页"));
        broadcastChanges();
    }

    private static ItemStack navButton(String itemId, String name, String tip) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        ItemStack s = item == null ? ItemStack.EMPTY.copy() : item.getDefaultInstance();
        s.set(DataComponents.CUSTOM_NAME, MessageUtil.parse(name));
        List<Component> lines = new ArrayList<>();
        lines.add(MessageUtil.parse(tip));
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
                    int idx = page * PAGE_SIZE + slotId;
                    if (idx < mails.size()) {
                        claim(mails.get(idx).id(), sp);
                    }
                }
            }
            return; // swallow click so no item is moved
        }
        super.clicked(slotId, button, input, p);
    }

    @Override
    public ItemStack quickMoveStack(Player p, int slot) {
        if (slot >= 0 && slot < ITEM_SLOTS) {
            if (p instanceof ServerPlayer sp) {
                int idx = page * PAGE_SIZE + slot;
                if (idx < mails.size()) {
                    claim(mails.get(idx).id(), sp);
                }
            }
            return ItemStack.EMPTY;
        }
        if (slot >= ITEM_SLOTS && slot < SLOTS) {
            return ItemStack.EMPTY; // control row
        }
        return super.quickMoveStack(p, slot);
    }

    private void handleControl(ServerPlayer sp, int slotId) {
        if (slotId == PREV_SLOT && page > 0) {
            page--;
            refresh();
        } else if (slotId == NEXT_SLOT && page + 1 < pageCount()) {
            page++;
            refresh();
        }
    }

    private void claim(int mailId, ServerPlayer sp) {
        switch (MailboxService.claim(sp, mailId)) {
            case SUCCESS -> sp.sendSystemMessage(MessageUtil.parse("&a已领取！"));
            case NO_SPACE -> sp.sendSystemMessage(MessageUtil.parse("&c背包空间不足，无法领取！"));
            default -> sp.sendSystemMessage(MessageUtil.parse("&c该邮件不存在或不属于你！"));
        }
        mails.clear();
        mails.addAll(MailboxService.list(sp.getUUID()));
        refresh();
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        refresh();
    }
}
