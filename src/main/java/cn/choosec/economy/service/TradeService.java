package cn.choosec.economy.service;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.util.MessageUtil;
import com.mojang.serialization.DataResult;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Player-to-player marketplace with two order types:
 * <ul>
 *   <li>SELL order — a player lists an item (NBT preserved); a buyer pays, seller receives price - fee.</li>
 *   <li>BUY order (求购) — a player posts a wanted item and prepays price*count as escrow; a fulfiller
 *       supplies the item and receives escrow - fee; cancelling refunds the escrow in full.</li>
 * </ul>
 * The configured trade fee (default 2%) goes to the server.
 */
public final class TradeService {

    public record Listing(int id, UUID seller, String itemId, int count, BigDecimal price,
                          String itemData, long time, String type) {
    }

    private TradeService() {
    }

    /** Short-lived cache of the active listing summary (used for command suggestions / list). */
    private static volatile List<Listing> listingCache = null;
    private static volatile long listingCacheAt = 0L;
    private static final long LISTING_CACHE_TTL_MS = 5_000L;

    private static void invalidateListings() {
        listingCache = null;
    }

    /** Create a SELL order. Returns id, -1 error, or -2 too many listings. */
    public static synchronized int createListing(UUID seller, String itemId, int count,
                                                 BigDecimal price, String itemData) {
        int max = ConfigManager.get().trade.maxListingsPerPlayer;
        if (countActive(seller) >= max) {
            return -2;
        }
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO market_listings (seller, item, count, price, item_data, time, type)
                    VALUES (?, ?, ?, ?, ?, ?, 'SELL')""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, seller.toString());
                ps.setString(2, itemId);
                ps.setInt(3, count);
                ps.setBigDecimal(4, price);
                ps.setString(5, itemData);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        invalidateListings();
                        return keys.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return -1;
    }

    /**
     * Create a BUY order (求购): creator prepays count*price as escrow.
     * Returns id, -1 error, -2 too many listings, -3 insufficient balance.
     */
    public static synchronized int createBuyOrder(ServerPlayer creator, String itemId, int count, BigDecimal unitPrice) {
        int max = ConfigManager.get().trade.maxListingsPerPlayer;
        if (countActive(creator.getUUID()) >= max) {
            return -2;
        }
        BigDecimal escrow = unitPrice.multiply(BigDecimal.valueOf(count)).setScale(MoneyUtil.SCALE, RoundingMode.HALF_UP);
        if (EconomyService.remove(creator.getUUID(), creator.getName().getString(), escrow, "buy-order escrow") == null) {
            return -3;
        }
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO market_listings (seller, item, count, price, item_data, time, type)
                    VALUES (?, ?, ?, ?, NULL, ?, 'BUY')""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, creator.getUUID().toString());
                ps.setString(2, itemId);
                ps.setInt(3, count);
                ps.setBigDecimal(4, unitPrice);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        invalidateListings();
                        return keys.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            EconomyService.add(creator.getUUID(), creator.getName().getString(), escrow, "buy-order escrow refund");
        }
        return -1;
    }

    public static synchronized Listing getListing(int id) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM market_listings WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return null;
        }
    }

    public static synchronized List<Listing> listListings() {
        long now = System.currentTimeMillis();
        if (listingCache != null && now - listingCacheAt < LISTING_CACHE_TTL_MS) {
            return listingCache;
        }
        List<Listing> result = new ArrayList<>();
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM market_listings ORDER BY id DESC LIMIT 100")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(row(rs));
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        listingCache = List.copyOf(result);
        listingCacheAt = now;
        return listingCache;
    }

    /** List a single seller's listings for the management UI (newest first). */
    public static synchronized List<Listing> listBySeller(UUID seller) {
        List<Listing> result = new ArrayList<>();
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM market_listings WHERE seller = ? ORDER BY id DESC LIMIT 500")) {
                ps.setString(1, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(row(rs));
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return result;
    }

    private static int countActive(UUID seller) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM market_listings WHERE seller = ?")) {
                ps.setString(1, seller.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
    }

    /** Cancel a listing: SELL refunds items, BUY refunds the full escrow. */
    public enum CancelResult { SUCCESS, NOT_FOUND, NOT_OWNER, ERROR }

    public static synchronized CancelResult cancel(ServerPlayer owner, int id) {
        Listing l = getListing(id);
        if (l == null) {
            return CancelResult.NOT_FOUND;
        }
        if (!l.seller().equals(owner.getUUID())) {
            return CancelResult.NOT_OWNER;
        }
        if ("BUY".equalsIgnoreCase(l.type())) {
            BigDecimal escrow = l.price().multiply(BigDecimal.valueOf(l.count())).setScale(MoneyUtil.SCALE, RoundingMode.HALF_UP);
            EconomyService.add(owner.getUUID(), owner.getName().getString(), escrow, "buy-order cancel refund");
        } else {
            // 从 item_data 还原物品，保留 NBT/附魔等数据；不要用 buildFromId 重建（会丢失 NBT）
            ItemStack item = buildItem(l, owner.level().getServer().registryAccess());
            if (item.isEmpty()) {
                // 无法还原物品数据时保留订单，避免物品凭空消失
                return CancelResult.ERROR;
            }
            if (!owner.getInventory().add(item)) {
                owner.getInventory().placeItemBackInInventory(item);
            }
        }
        deleteListing(id);
        return CancelResult.SUCCESS;
    }

    /** Change a listing's unit price. BUY orders settle the escrow difference. */
    public enum RepriceResult { SUCCESS, NOT_FOUND, NOT_OWNER, INVALID_PRICE, NO_FUNDS, ERROR }

    public static synchronized RepriceResult reprice(ServerPlayer owner, int id, BigDecimal newPrice) {
        Listing l = getListing(id);
        if (l == null) {
            return RepriceResult.NOT_FOUND;
        }
        if (!l.seller().equals(owner.getUUID())) {
            return RepriceResult.NOT_OWNER;
        }
        BigDecimal price = MoneyUtil.norm(newPrice);
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return RepriceResult.INVALID_PRICE;
        }
        String name = owner.getName().getString();
        BigDecimal escrowDelta = BigDecimal.ZERO;
        if ("BUY".equalsIgnoreCase(l.type())) {
            BigDecimal oldEscrow = l.price().multiply(BigDecimal.valueOf(l.count()))
                    .setScale(MoneyUtil.SCALE, MoneyUtil.ROUNDING);
            BigDecimal newEscrow = price.multiply(BigDecimal.valueOf(l.count()))
                    .setScale(MoneyUtil.SCALE, MoneyUtil.ROUNDING);
            escrowDelta = newEscrow.subtract(oldEscrow);
            if (escrowDelta.compareTo(BigDecimal.ZERO) > 0) {
                if (EconomyService.remove(owner.getUUID(), name, escrowDelta, "reprice escrow #" + id) == null) {
                    return RepriceResult.NO_FUNDS;
                }
            } else if (escrowDelta.compareTo(BigDecimal.ZERO) < 0) {
                EconomyService.add(owner.getUUID(), name, escrowDelta.negate(), "reprice escrow refund #" + id);
            }
        }
        boolean updated = false;
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE market_listings SET price = ? WHERE id = ?")) {
                ps.setBigDecimal(1, price);
                ps.setInt(2, id);
                updated = ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        if (!updated) {
            // Compensate the escrow adjustment so money and price stay consistent.
            if (escrowDelta.compareTo(BigDecimal.ZERO) > 0) {
                EconomyService.add(owner.getUUID(), name, escrowDelta, "reprice escrow rollback #" + id);
            } else if (escrowDelta.compareTo(BigDecimal.ZERO) < 0) {
                EconomyService.remove(owner.getUUID(), name, escrowDelta.negate(), "reprice escrow rollback #" + id);
            }
            return RepriceResult.ERROR;
        }
        invalidateListings();
        return RepriceResult.SUCCESS;
    }

    /** Add more quantity to the player's own listing. SELL takes items from inventory, BUY takes extra escrow. */
    public enum RestockResult { SUCCESS, NOT_FOUND, NOT_OWNER, INVALID_COUNT, NO_ITEMS, NO_FUNDS, ERROR }

    public static synchronized RestockResult restock(ServerPlayer owner, int id, int addCount) {
        Listing l = getListing(id);
        if (l == null) {
            return RestockResult.NOT_FOUND;
        }
        if (!l.seller().equals(owner.getUUID())) {
            return RestockResult.NOT_OWNER;
        }
        if (addCount <= 0) {
            return RestockResult.INVALID_COUNT;
        }
        String name = owner.getName().getString();
        boolean buy = "BUY".equalsIgnoreCase(l.type());
        BigDecimal escrow = null;
        if (buy) {
            escrow = l.price().multiply(BigDecimal.valueOf(addCount))
                    .setScale(MoneyUtil.SCALE, MoneyUtil.ROUNDING);
            if (EconomyService.remove(owner.getUUID(), name, escrow, "restock escrow #" + id) == null) {
                return RestockResult.NO_FUNDS;
            }
        } else if (!hasItems(owner, l.itemId(), addCount)) {
            return RestockResult.NO_ITEMS;
        }
        boolean updated = false;
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE market_listings SET count = count + ? WHERE id = ?")) {
                ps.setInt(1, addCount);
                ps.setInt(2, id);
                updated = ps.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        if (!updated) {
            if (escrow != null) {
                EconomyService.add(owner.getUUID(), name, escrow, "restock escrow rollback #" + id);
            }
            return RestockResult.ERROR;
        }
        if (!buy) {
            List<ItemStack> taken = removeItems(owner, l.itemId(), addCount);
            if (taken == null) {
                // Defensive: roll the count back rather than losing player items.
                try (Connection c = DatabaseManager.open();
                     PreparedStatement ps = c.prepareStatement(
                             "UPDATE market_listings SET count = count - ? WHERE id = ?")) {
                    ps.setInt(1, addCount);
                    ps.setInt(2, id);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    DatabaseManager.log(e);
                }
                invalidateListings();
                return RestockResult.ERROR;
            }
        }
        invalidateListings();
        return RestockResult.SUCCESS;
    }

    /** Fulfill a BUY order: fulfiller supplies the item, receives escrow - fee. */
    public enum FulfillResult { SUCCESS, NOT_FOUND, NOT_BUY_ORDER, NO_ITEMS, ITEM_ERROR, INVALID_COUNT }

    public static synchronized FulfillResult fulfill(ServerPlayer fulfiller, int id) {
        return fulfill(fulfiller, id, Integer.MAX_VALUE);
    }

    public static synchronized FulfillResult fulfill(ServerPlayer fulfiller, int id, int count) {
        Listing l = getListing(id);
        if (l == null) {
            return FulfillResult.NOT_FOUND;
        }
        if (!"BUY".equalsIgnoreCase(l.type())) {
            return FulfillResult.NOT_BUY_ORDER;
        }
        if (count <= 0) {
            return FulfillResult.INVALID_COUNT;
        }
        int supplyCount = Math.min(count, l.count());
        if (supplyCount <= 0) {
            return FulfillResult.INVALID_COUNT;
        }
        if (buildFromId(l.itemId(), 1).isEmpty()) {
            return FulfillResult.ITEM_ERROR;
        }
        if (!hasItems(fulfiller, l.itemId(), supplyCount)) {
            return FulfillResult.NO_ITEMS;
        }
        List<ItemStack> removed = removeItems(fulfiller, l.itemId(), supplyCount);
        if (removed == null) {
            return FulfillResult.NO_ITEMS;
        }
        BigDecimal escrow = l.price().multiply(BigDecimal.valueOf(supplyCount)).setScale(MoneyUtil.SCALE, RoundingMode.HALF_UP);
        BigDecimal fee = ConfigManager.get().rates.tradeFeePercent;
        BigDecimal toSeller = MoneyUtil.minusPercent(escrow, fee);
        BigDecimal bankFee = MoneyUtil.percent(escrow, fee);
        EconomyService.add(fulfiller.getUUID(), fulfiller.getName().getString(), toSeller, "buy-order fulfilled");
        EconomyService.add(EconomyService.BANK_UUID, "bank", bankFee, "buy-order fee");
        // 把实际移除的物品（保留 NBT）交给求购方；离线或背包放不下则存入邮箱
        MinecraftServer server = fulfiller.level().getServer();
        RegistryAccess reg = server.registryAccess();
        ServerPlayer creator = server.getPlayerList().getPlayer(l.seller());
        List<ItemStack> leftover = new ArrayList<>();
        if (creator != null) {
            for (ItemStack st : removed) {
                ItemStack copy = st.copy();
                // Inventory.add consumes as much as fits and leaves the remainder in
                // the passed stack; anything left over must be mailed, never dropped.
                if (!creator.getInventory().add(copy) && !copy.isEmpty()) {
                    leftover.add(copy);
                }
            }
            if (leftover.isEmpty()) {
                creator.sendSystemMessage(MessageUtil.parse("&a你发布的求购单 #" + l.id() + " 已收到 &e" + supplyCount
                        + " &a个物品，已放入背包！"));
            } else {
                for (ItemStack st : leftover) {
                    MailboxService.add(l.seller(), st, reg);
                }
                creator.sendSystemMessage(MessageUtil.parse("&e你发布的求购单 #" + l.id() + " 已到货，背包空间不足，"
                        + "部分物品已存入邮箱，使用 /mails 领取！"));
            }
        } else {
            for (ItemStack st : removed) {
                MailboxService.add(l.seller(), st, reg);
            }
        }
        if (supplyCount >= l.count()) {
            deleteListing(id);
        } else {
            updateCount(id, l.count() - supplyCount);
        }
        return FulfillResult.SUCCESS;
    }

    /** Buy a SELL listing. */
    public enum BuyResult { SUCCESS, NOT_FOUND, OWN_ITEM, NO_FUNDS, NO_SPACE, ITEM_ERROR, IS_BUY_ORDER, INVALID_COUNT }

    public static synchronized BuyResult buy(ServerPlayer buyer, int id) {
        return buy(buyer, id, Integer.MAX_VALUE);
    }

    public static synchronized BuyResult buy(ServerPlayer buyer, int id, int count) {
        Listing l = getListing(id);
        if (l == null) {
            return BuyResult.NOT_FOUND;
        }
        if ("BUY".equalsIgnoreCase(l.type())) {
            return BuyResult.IS_BUY_ORDER;
        }
        if (l.seller().equals(buyer.getUUID())) {
            return BuyResult.OWN_ITEM;
        }
        if (count <= 0) {
            return BuyResult.INVALID_COUNT;
        }
        int buyCount = Math.min(count, l.count());
        if (buyCount <= 0) {
            return BuyResult.INVALID_COUNT;
        }
        RegistryAccess reg = buyer.level().getServer().registryAccess();
        ItemStack item = buildItem(l, reg);
        if (item.isEmpty()) {
            return BuyResult.ITEM_ERROR;
        }
        item.setCount(buyCount);
        if (!canFit(buyer, item)) {
            return BuyResult.NO_SPACE;
        }
        BigDecimal fee = ConfigManager.get().rates.tradeFeePercent;
        // 单价 × 购买数量
        BigDecimal total = l.price().multiply(BigDecimal.valueOf(buyCount))
                .setScale(MoneyUtil.SCALE, MoneyUtil.ROUNDING);
        String sellerName = sellerName(buyer.level().getServer(), l.seller());
        if (!EconomyService.transfer(buyer.getUUID(), buyer.getName().getString(),
                l.seller(), sellerName, total, fee)) {
            return BuyResult.NO_FUNDS;
        }
        // Inventory.add consumes what fits and leaves the remainder in the passed
        // stack. Mail the remainder so a component mismatch can never lose items.
        ItemStack leftover = item.copy();
        boolean added = buyer.getInventory().add(leftover);
        if (!added && !leftover.isEmpty()) {
            MailboxService.add(buyer.getUUID(), leftover, reg);
            buyer.sendSystemMessage(MessageUtil.parse("&e背包空间不足，&f" + leftover.getCount() + " &e个已存入邮箱，使用 /mails 领取！"));
        }
        if (buyCount >= l.count()) {
            deleteListing(id);
        } else {
            updateCount(id, l.count() - buyCount);
        }
        return BuyResult.SUCCESS;
    }

    /** Friendly seller name: online player first, then account name, then short id. */
    public static String sellerName(MinecraftServer server, java.util.UUID uuid) {
        if (server != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) {
                return p.getName().getString();
            }
        }
        return sellerName(uuid);
    }

    /** Friendly seller display id: account name if known (and not a raw uuid), else short UUID. */
    public static String sellerName(java.util.UUID uuid) {
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement("SELECT name FROM balances WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String n = rs.getString("name");
                    // a raw uuid means the account was auto-created without a real name
                    if (n != null && !n.isEmpty() && !n.equalsIgnoreCase(uuid.toString())) {
                        return n;
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        if (EconomyService.BANK_UUID.equals(uuid)) {
            return "服务器";
        }
        return uuid.toString().substring(0, 8);
    }

    /** Serialize an item stack (including NBT/enchantments) to SNBT via the item codec. */
    public static String serialize(ItemStack stack, RegistryAccess reg) {
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, reg);
            DataResult<Tag> res = ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack);
            java.util.Optional<Tag> ot = res.result();
            if (ot.isPresent() && ot.get() instanceof CompoundTag ct) {
                return NbtUtils.structureToSnbt(ct);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    /** Rebuild an item stack from a listing (NBT preserved when present). */
    public static ItemStack buildItem(Listing l, RegistryAccess reg) {
        if (l.itemData() != null && !l.itemData().isEmpty()) {
            try {
                RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, reg);
                Tag tag = NbtUtils.snbtToStructure(l.itemData());
                ItemStack stack = ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
                if (!stack.isEmpty()) {
                    stack.setCount(l.count());
                    return stack;
                }
            } catch (Exception ignored) {
                // fall through to id-only
            }
        }
        return buildFromId(l.itemId(), l.count());
    }

    /** Build a plain stack from item id + count (no NBT). */
    public static ItemStack buildFromId(String itemId, int count) {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            if (item == null) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = item.getDefaultInstance();
            stack.setCount(count);
            return stack;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /** Rebuild a stack from a serialized SNBT string (as produced by {@link #serialize}). */
    public static ItemStack buildFromData(String itemData, RegistryAccess reg) {
        if (itemData == null || itemData.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, reg);
            Tag tag = NbtUtils.snbtToStructure(itemData);
            return ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * True if the player's inventory has room for the whole stack. Uses the same
     * component-aware stacking rules as {@code Inventory.add}: an empty slot holds
     * a full stack, and a matching stack merges up to the item's max stack size.
     */
    public static boolean canFit(ServerPlayer p, ItemStack stack) {
        int need = stack.getCount();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack slot = p.getInventory().getItem(i);
            if (slot.isEmpty()) {
                need -= Math.min(need, stack.getMaxStackSize());
                if (need <= 0) {
                    return true;
                }
            } else if (ItemStack.isSameItemSameComponents(slot, stack)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                if (space > 0) {
                    need -= Math.min(need, space);
                    if (need <= 0) {
                        return true;
                    }
                }
            }
        }
        return need <= 0;
    }

    /** True if the player's inventory has at least {@code count} items matching {@code itemId} (by id). */
    private static boolean hasItems(ServerPlayer p, String itemId, int count) {
        int have = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack slot = p.getInventory().getItem(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (itemId.equals(slot.typeHolder().getRegisteredName())) {
                have += slot.getCount();
                if (have >= count) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Remove up to {@code count} matching items (matched by id) and return the actual
     * removed stacks, preserving each stack's NBT. Returns {@code null} if the full
     * count could not be gathered (nothing is removed in that case).
     */
    private static List<ItemStack> removeItems(ServerPlayer p, String itemId, int count) {
        List<ItemStack> removed = new ArrayList<>();
        int need = count;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (need <= 0) {
                break;
            }
            ItemStack slot = p.getInventory().getItem(i);
            if (slot.isEmpty()) {
                continue;
            }
            if (!itemId.equals(slot.typeHolder().getRegisteredName())) {
                continue;
            }
            int take = Math.min(need, slot.getCount());
            ItemStack copy = slot.copy();
            copy.setCount(take);
            removed.add(copy);
            slot.shrink(take);
            need -= take;
        }
        if (need > 0) {
            // Should not happen when hasItems() was checked first; restore defensively.
            for (ItemStack st : removed) {
                p.getInventory().add(st);
            }
            p.getInventory().setChanged();
            return null;
        }
        p.getInventory().setChanged();
        return removed;
    }

    static synchronized void deleteListing(int id) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM market_listings WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        invalidateListings();
    }

    /** Reduce the remaining count of a listing after a partial buy/fulfil. */
    private static synchronized void updateCount(int id, int newCount) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE market_listings SET count = ? WHERE id = ?")) {
                ps.setInt(1, Math.max(1, newCount));
                ps.setInt(2, id);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        invalidateListings();
    }

    private static Listing row(ResultSet rs) throws SQLException {
        String type = rs.getString("type");
        return new Listing(rs.getInt("id"), UUID.fromString(rs.getString("seller")),
                rs.getString("item"), rs.getInt("count"), rs.getBigDecimal("price"),
                rs.getString("item_data"), rs.getLong("time"), type == null ? "SELL" : type);
    }
}
