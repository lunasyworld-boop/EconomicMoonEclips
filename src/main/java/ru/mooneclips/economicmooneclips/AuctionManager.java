package ru.mooneclips.economicmooneclips;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.sql.*;
import java.util.*;

public final class AuctionManager implements Listener {
    private static final String[] CATEGORIES = {
            "all", "weapons_tools", "armor", "ores_resources",
            "building_blocks", "food", "potions", "rare_items", "miscellaneous"
    };
    private static final Material[] CATEGORY_ICONS = {
            Material.NETHER_STAR, Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND, Material.BRICKS, Material.COOKED_BEEF,
            Material.POTION, Material.NETHERITE_INGOT, Material.CHEST
    };

    private final EconomicMoonEclips plugin;
    private final Economy economy;
    private final NamespacedKey listingKey;
    private Connection connection;

    public AuctionManager(EconomicMoonEclips plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.listingKey = new NamespacedKey(plugin, "auction_listing");
        openDatabase();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireListings, 1200L, 1200L);
    }

    private void openDatabase() {
        try {
            File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("auction.database-file", "auction.db"));
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) throw new SQLException("Cannot create plugin data folder");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS listings (id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL, item BLOB NOT NULL, material TEXT NOT NULL, price REAL NOT NULL, category TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS item_mail (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, item BLOB NOT NULL)");
            }
        } catch (SQLException ex) {
            plugin.getLogger().severe("Auction database could not be opened: " + ex.getMessage());
        }
    }

    public void handleCommand(Player player, String[] args) {
        if (connection == null) {
            player.sendMessage(msg("&cАукцион временно недоступен: ошибка базы данных."));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("sell")) {
            if (args.length != 2) { player.sendMessage(msg("&eИспользование: &f/ah sell <цена>")); return; }
            createListing(player, args[1]);
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("search")) {
            String query = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            open(player, "all", query, "newest", 0);
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("sort")) {
            String sort = args.length > 1 && args[1].equalsIgnoreCase("asc") ? "asc" : "desc";
            open(player, "all", "", sort, 0);
            return;
        }
        open(player, "all", "", "newest", 0);
    }

    private void createListing(Player player, String rawPrice) {
        double price;
        try {
            price = Double.parseDouble(rawPrice);
            if (!Double.isFinite(price) || price <= 0 || price > 1.0E12) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            player.sendMessage(msg("&cУкажи корректную положительную цену."));
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { player.sendMessage(msg("&cВозьми предмет в основную руку.")); return; }
        int slots = getAuctionSlots(player);
        int active = countActive(player.getUniqueId());
        if (active >= slots) {
            player.sendMessage(msg("&cВсе слоты заняты: &f" + active + "/" + slots));
            return;
        }
        ItemStack escrow = hand.clone();
        long now = System.currentTimeMillis();
        long expires = now + plugin.getConfig().getLong("auction.duration-hours", 128) * 3_600_000L;
        String category = classify(escrow.getType());
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO listings(seller_uuid,seller_name,item,material,price,category,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setBytes(3, escrow.serializeAsBytes());
            ps.setString(4, escrow.getType().name());
            ps.setDouble(5, price);
            ps.setString(6, category);
            ps.setLong(7, now);
            ps.setLong(8, expires);
            ps.setString(9, "ACTIVE");
            ps.executeUpdate();
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            player.sendMessage(msg("&aЛот выставлен на &b" + money(price) + "$ &7на 128 часов."));
        } catch (SQLException ex) {
            plugin.getLogger().severe("Could not create auction listing: " + ex.getMessage());
            player.sendMessage(msg("&cНе удалось выставить предмет. Он остался у тебя."));
        }
    }

    private int getAuctionSlots(Player player) {
        String[] ranks = {"eclipse", "crystal", "diamond", "gold", "silver", "stone"};
        for (String rank : ranks) {
            if (player.hasPermission("economicmooneclips.rank." + rank)) {
                return plugin.getConfig().getInt("ranks." + rank + ".auction-slots", 6);
            }
        }
        return 6;
    }

    private int countActive(UUID seller) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM listings WHERE seller_uuid=? AND status='ACTIVE' AND expires_at>?")) {
            ps.setString(1, seller.toString());
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Auction slot query failed: " + ex.getMessage());
            return Integer.MAX_VALUE;
        }
    }

    private void open(Player player, String category, String search, String sort, int page) {
        List<Listing> listings = loadListings(category, search, sort, page);
        AuctionHolder holder = new AuctionHolder(category, search, sort, page, listings);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&0✦ &5Moon&bEclips &8• &fАукцион"));
        for (int i = 0; i < CATEGORIES.length; i++) {
            String cat = CATEGORIES[i];
            inv.setItem(i, button(CATEGORY_ICONS[i], "&b" + categoryName(cat),
                    List.of(cat.equals(category) ? "&aВыбрано" : "&7Нажми для фильтра")));
        }
        inv.setItem(50, button(Material.HOPPER, "&eСортировка: " + sortName(sort),
                List.of("&7Нажми, чтобы изменить порядок", "&8Команды: /ah sort asc|desc")));
        int slot = 9;
        for (Listing listing : listings) {
            if (slot > 44) break;
            ItemStack display = listing.item.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta != null && meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(color("&7Продавец: &f" + listing.sellerName));
            lore.add(color("&7Цена: &b" + money(listing.price) + "$"));
            lore.add(color("&7Осталось: &f" + hoursLeft(listing.expiresAt) + " ч."));
            lore.add(color(listing.sellerUuid.equals(player.getUniqueId().toString())
                    ? "&eНажми, чтобы снять свой лот" : "&aНажми, чтобы купить"));
            if (meta != null) { meta.setLore(lore); display.setItemMeta(meta); }
            display.getItemMeta().getPersistentDataContainer().set(listingKey, PersistentDataType.INTEGER, listing.id);
            ItemMeta marked = display.getItemMeta();
            marked.getPersistentDataContainer().set(listingKey, PersistentDataType.INTEGER, listing.id);
            display.setItemMeta(marked);
            inv.setItem(slot++, display);
        }
        inv.setItem(45, button(Material.ARROW, "&eПредыдущая страница", List.of()));
        inv.setItem(49, button(Material.PAPER, "&fСтраница " + (page + 1),
                List.of("&7Лотов на странице: &f" + listings.size(), "&7Поиск: &f" + (search.isBlank() ? "нет" : search))));
        inv.setItem(53, button(Material.ARROW, "&eСледующая страница", List.of()));
        player.openInventory(inv);
    }

    private List<Listing> loadListings(String category, String search, String sort, int page) {
        List<Listing> result = new ArrayList<>();
        String order = switch (sort) { case "asc" -> "price ASC"; case "desc" -> "price DESC"; default -> "created_at DESC"; };
        String sql = "SELECT * FROM listings WHERE status='ACTIVE' AND expires_at>? "
                + (category.equals("all") ? "" : "AND category=? ")
                + (search.isBlank() ? "" : "AND (LOWER(material) LIKE ? OR LOWER(seller_name) LIKE ?) ")
                + "ORDER BY " + order + " LIMIT 36 OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, System.currentTimeMillis());
            if (!category.equals("all")) ps.setString(i++, category);
            if (!search.isBlank()) { String q = "%" + search.toLowerCase(Locale.ROOT) + "%"; ps.setString(i++, q); ps.setString(i++, q); }
            ps.setInt(i, page * 36);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new Listing(rs.getInt("id"), rs.getString("seller_uuid"),
                            rs.getString("seller_name"), ItemStack.deserializeBytes(rs.getBytes("item")),
                            rs.getDouble("price"), rs.getLong("expires_at")));
                }
            }
        } catch (SQLException | IllegalArgumentException ex) {
            plugin.getLogger().warning("Auction listings query failed: " + ex.getMessage());
        }
        return result;
    }

    private void purchase(Player buyer, int id) {
        Listing listing = getListing(id);
        if (listing == null) { buyer.sendMessage(msg("&cЛот больше недоступен.")); return; }
        if (listing.sellerUuid.equals(buyer.getUniqueId().toString())) {
            withdraw(buyer, id);
            return;
        }
        if (listing.expiresAt <= System.currentTimeMillis()) {
            expireOne(id);
            buyer.sendMessage(msg("&cСрок лота истёк."));
            return;
        }
        if (economy.getBalance(buyer) < listing.price) { buyer.sendMessage(msg("&cНедостаточно денег.")); return; }
        try {
            if (!setStatus(id, "ACTIVE", "SOLD")) { buyer.sendMessage(msg("&cЛот уже куплен или снят.")); return; }
            EconomyResponse debit = economy.withdrawPlayer(buyer, listing.price);
            if (!debit.transactionSuccess()) {
                setStatus(id, "SOLD", "ACTIVE");
                buyer.sendMessage(msg("&cНе удалось списать деньги."));
                return;
            }
            double fee = listing.price * plugin.getConfig().getDouble("auction.commission-percent", 1.2) / 100.0;
            OfflinePlayer seller = Bukkit.getOfflinePlayer(UUID.fromString(listing.sellerUuid));
            EconomyResponse credit = economy.depositPlayer(seller, listing.price - fee);
            if (!credit.transactionSuccess()) {
                economy.depositPlayer(buyer, listing.price);
                setStatus(id, "SOLD", "ACTIVE");
                buyer.sendMessage(msg("&cНе удалось перечислить деньги продавцу. Покупка отменена."));
                return;
            }
            deliver(buyer, listing.item);
            buyer.sendMessage(msg("&aПокупка успешна: &f" + listing.item.getType() + " &7за &b" + money(listing.price) + "$"));
            if (seller.isOnline()) seller.getPlayer().sendMessage(msg("&aТвой лот куплен. Получено: &b" + money(listing.price - fee) + "$ &7(комиссия 1.2%)."));
        } catch (Exception ex) {
            plugin.getLogger().severe("Auction purchase failed for listing " + id + ": " + ex.getMessage());
            buyer.sendMessage(msg("&cОшибка покупки. Обратись к администрации."));
        }
    }

    private void withdraw(Player seller, int id) {
        Listing listing = getListing(id);
        if (listing == null || !listing.sellerUuid.equals(seller.getUniqueId().toString())) {
            seller.sendMessage(msg("&cЭтот лот тебе не принадлежит."));
            return;
        }
        if (!setStatus(id, "ACTIVE", "WITHDRAWN")) {
            seller.sendMessage(msg("&cЛот уже недоступен."));
            return;
        }
        deliver(seller, listing.item);
        seller.sendMessage(msg("&aЛот снят. Предмет возвращён."));
    }

    private void deliver(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
        for (ItemStack rest : overflow.values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
    }

    private Listing getListing(int id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM listings WHERE id=? AND status='ACTIVE'")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Listing(id, rs.getString("seller_uuid"), rs.getString("seller_name"),
                        ItemStack.deserializeBytes(rs.getBytes("item")), rs.getDouble("price"), rs.getLong("expires_at"));
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Auction listing lookup failed: " + ex.getMessage());
            return null;
        }
    }

    private boolean setStatus(int id, String from, String to) {
        try (PreparedStatement ps = connection.prepareStatement("UPDATE listings SET status=? WHERE id=? AND status=?")) {
            ps.setString(1, to); ps.setInt(2, id); ps.setString(3, from);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Auction status update failed: " + ex.getMessage());
            return false;
        }
    }

    private void expireListings() {
        if (connection == null) return;
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT id FROM listings WHERE status='ACTIVE' AND expires_at<=?")) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getInt(1)); }
        } catch (SQLException ex) { plugin.getLogger().warning("Auction expiry scan failed: " + ex.getMessage()); }
        for (int id : ids) expireOne(id);
    }

    private void expireOne(int id) {
        Listing listing = getListing(id);
        if (listing == null) return;
        UUID sellerId = UUID.fromString(listing.sellerUuid);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement mail = connection.prepareStatement("INSERT INTO item_mail(player_uuid,item) VALUES(?,?)")) {
                mail.setString(1, sellerId.toString());
                mail.setBytes(2, listing.item.serializeAsBytes());
                mail.executeUpdate();
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE listings SET status='EXPIRED' WHERE id=? AND status='ACTIVE'")) {
                update.setInt(1, id);
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return;
                }
            }
            connection.commit();
        } catch (SQLException ex) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            plugin.getLogger().severe("Could not safely expire auction listing " + id + ": " + ex.getMessage());
            return;
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
        }
        Player online = Bukkit.getPlayer(sellerId);
        if (online != null) deliverMail(online);
    }

}