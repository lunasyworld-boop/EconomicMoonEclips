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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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
            "all", "weapons_tools", "armor", "ores_resources", "building_blocks",
            "food", "potions", "rare_items", "miscellaneous"
    };
    private static final Material[] CATEGORY_ICONS = {
            Material.NETHER_STAR, Material.DIAMOND_SWORD, Material.DIAMOND_CHESTPLATE,
            Material.DIAMOND, Material.BRICKS, Material.COOKED_BEEF,
            Material.POTION, Material.NETHERITE_INGOT, Material.CHEST
    };

    private final EconomicMoonEclips plugin;
    private final Economy economy;
    private final NamespacedKey listingKey;
    private final FileConfiguration auctionConfig;
    private Connection connection;

    public AuctionManager(EconomicMoonEclips plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.listingKey = new NamespacedKey(plugin, "auction_listing");
        this.auctionConfig = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "auction.yml"));
        openDatabase();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expireListings, 1200L, 1200L);
    }

    private void openDatabase() {
        try {
            File file = new File(plugin.getDataFolder(), auctionConfig.getString("auction.database-file", "auction.db"));
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                throw new SQLException("Cannot create plugin data folder");
            }
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS listings (id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid TEXT NOT NULL, seller_name TEXT NOT NULL, item BLOB NOT NULL, material TEXT NOT NULL, price REAL NOT NULL, category TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS item_mail (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, item BLOB NOT NULL)");
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Auction database could not be opened: " + ex.getMessage());
        }
    }

    public void close() {
        if (connection != null) {
            try { connection.close(); }
            catch (SQLException ex) { plugin.getLogger().warning("Auction database close failed: " + ex.getMessage()); }
        }
    }

    public void handleCommand(Player player, String[] args) {
        if (connection == null) {
            player.sendMessage(msg("&cАукцион временно недоступен: ошибка базы данных."));
            return;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("sell")) {
            if (args.length != 2) {
                player.sendMessage(msg("&eИспользование: &f/ah sell <цена>"));
                return;
            }
            player.closeInventory();
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
        if (hand.getType().isAir()) {
            player.sendMessage(msg("&cВозьми предмет в основную руку."));
            return;
        }
        int slots = getAuctionSlots(player);
        int active = countActive(player.getUniqueId());
        if (active >= slots) {
            player.sendMessage(msg("&cВсе слоты заняты: &f" + active + "/" + slots));
            return;
        }
        ItemStack escrow = hand.clone();
        long now = System.currentTimeMillis();
        long expires = now + auctionConfig.getLong("auction.duration-hours", 128) * 3_600_000L;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO listings(seller_uuid,seller_name,item,material,price,category,created_at,expires_at,status) VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, player.getName());
            ps.setBytes(3, escrow.serializeAsBytes());
            ps.setString(4, escrow.getType().name());
            ps.setDouble(5, price);
            ps.setString(6, classify(escrow.getType()));
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
                List.of("&7Нажми, чтобы изменить порядок", "&8/ah sort asc или desc")));
        int slot = 9;
        for (Listing listing : listings) {
            if (slot > 44) break;
            ItemStack display = listing.item.clone();
            ItemMeta meta = display.getItemMeta();
            List<String> lore = meta != null && meta.hasLore()
                    ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(color("&7Продавец: &f" + listing.sellerName));
            lore.add(color("&7Цена: &b" + money(listing.price) + "$"));
            lore.add(color("&7Осталось: &f" + hoursLeft(listing.expiresAt) + " ч."));
            lore.add(color(listing.sellerUuid.equals(player.getUniqueId().toString())
                    ? "&eНажми, чтобы снять свой лот" : "&aНажми, чтобы купить"));
            if (meta != null) {
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(listingKey, PersistentDataType.INTEGER, listing.id);
                display.setItemMeta(meta);
            }
            inv.setItem(slot++, display);
        }
        inv.setItem(45, button(Material.ARROW, "&eПредыдущая страница", List.of()));
        inv.setItem(49, button(Material.PAPER, "&fСтраница " + (page + 1),
                List.of("&7Лотов: &f" + listings.size(), "&7Поиск: &f" + (search.isBlank() ? "нет" : search))));
        inv.setItem(53, button(Material.ARROW, "&eСледующая страница", List.of()));
        player.openInventory(inv);
    }

    private List<Listing> loadListings(String category, String search, String sort, int page) {
        List<Listing> result = new ArrayList<>();
        String order = switch (sort) {
            case "asc" -> "price ASC";
            case "desc" -> "price DESC";
            default -> "created_at DESC";
        };
        String sql = "SELECT * FROM listings WHERE status='ACTIVE' AND expires_at>? "
                + (category.equals("all") ? "" : "AND category=? ")
                + (search.isBlank() ? "" : "AND (LOWER(material) LIKE ? OR LOWER(seller_name) LIKE ?) ")
                + "ORDER BY " + order + " LIMIT 36 OFFSET ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            ps.setLong(i++, System.currentTimeMillis());
            if (!category.equals("all")) ps.setString(i++, category);
            if (!search.isBlank()) {
                String q = "%" + search.toLowerCase(Locale.ROOT) + "%";
                ps.setString(i++, q);
                ps.setString(i++, q);
            }
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
        if (listing == null) {
            buyer.sendMessage(msg("&cЛот больше недоступен."));
            return;
        }
        if (listing.sellerUuid.equals(buyer.getUniqueId().toString())) {
            withdraw(buyer, id);
            return;
        }
        if (listing.expiresAt <= System.currentTimeMillis()) {
            expireOne(id);
            buyer.sendMessage(msg("&cСрок лота истёк."));
            return;
        }
        if (economy.getBalance(buyer) < listing.price) {
            buyer.sendMessage(msg("&cНедостаточно денег."));
            return;
        }

        boolean debited = false;
        boolean credited = false;
        OfflinePlayer seller = Bukkit.getOfflinePlayer(UUID.fromString(listing.sellerUuid));
        double fee = listing.price * auctionConfig.getDouble("auction.commission-percent", 1.2) / 100.0;
        double sellerNet = listing.price - fee;
        try {
            if (!setStatus(id, "ACTIVE", "SOLD")) {
                buyer.sendMessage(msg("&cЛот уже куплен или снят."));
                return;
            }
            EconomyResponse debit = economy.withdrawPlayer(buyer, listing.price);
            if (!debit.transactionSuccess()) {
                setStatus(id, "SOLD", "ACTIVE");
                buyer.sendMessage(msg("&cНе удалось списать деньги."));
                return;
            }
            debited = true;

            EconomyResponse credit = economy.depositPlayer(seller, sellerNet);
            if (!credit.transactionSuccess()) {
                EconomyResponse refund = economy.depositPlayer(buyer, listing.price);
                debited = !refund.transactionSuccess();
                if (!debited) setStatus(id, "SOLD", "ACTIVE");
                buyer.sendMessage(msg("&cНе удалось перечислить деньги продавцу. Покупка отменена."));
                return;
            }
            credited = true;

            deliver(buyer, listing.item);
            debited = false;
            credited = false;
            buyer.sendMessage(msg("&aПокупка успешна: &f" + listing.item.getType()
                    + " &7за &b" + money(listing.price) + "$"));
            if (seller.isOnline() && seller.getPlayer() != null) {
                seller.getPlayer().sendMessage(msg("&aТвой лот куплен. Получено: &b"
                        + money(sellerNet) + "$ &7(комиссия 1.2%)."));
            }
        } catch (Exception ex) {
            boolean rollbackOk = true;
            if (credited) {
                EconomyResponse reversal = economy.withdrawPlayer(seller, sellerNet);
                rollbackOk = reversal.transactionSuccess() && rollbackOk;
            }
            if (debited) {
                EconomyResponse refund = economy.depositPlayer(buyer, listing.price);
                rollbackOk = refund.transactionSuccess() && rollbackOk;
            }
            if (rollbackOk) setStatus(id, "SOLD", "ACTIVE");
            else plugin.getLogger().severe("Auction rollback incomplete for listing " + id);
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
        for (ItemStack rest : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private Listing getListing(int id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM listings WHERE id=? AND status='ACTIVE'")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Listing(id, rs.getString("seller_uuid"), rs.getString("seller_name"),
                        ItemStack.deserializeBytes(rs.getBytes("item")), rs.getDouble("price"),
                        rs.getLong("expires_at"));
            }
        } catch (SQLException | IllegalArgumentException ex) {
            plugin.getLogger().warning("Auction listing lookup failed: " + ex.getMessage());
            return null;
        }
    }

    private boolean setStatus(int id, String from, String to) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE listings SET status=? WHERE id=? AND status=?")) {
            ps.setString(1, to);
            ps.setInt(2, id);
            ps.setString(3, from);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            plugin.getLogger().warning("Auction status update failed: " + ex.getMessage());
            return false;
        }
    }

    private void expireListings() {
        if (connection == null) return;
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM listings WHERE status='ACTIVE' AND expires_at<=?")) {
            ps.setLong(1, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        } catch (SQLException ex) {
            plugin.getLogger().warning("Auction expiry scan failed: " + ex.getMessage());
        }
        for (int id : ids) expireOne(id);
    }

    private void expireOne(int id) {
        Listing listing = getListing(id);
        if (listing == null) return;
        UUID sellerId = UUID.fromString(listing.sellerUuid);
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement mail = connection.prepareStatement(
                    "INSERT INTO item_mail(player_uuid,item) VALUES(?,?)")) {
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

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (connection != null) deliverMail(event.getPlayer());
    }

    private void deliverMail(Player player) {
        if (connection == null) return;
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id,item FROM item_mail WHERE player_uuid=?")) {
            ps.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    ItemStack item = ItemStack.deserializeBytes(rs.getBytes("item"));
                    deliver(player, item);
                    ids.add(id);
                }
            }
        } catch (SQLException | IllegalArgumentException ex) {
            plugin.getLogger().warning("Auction mail read failed: " + ex.getMessage());
        }
        for (int id : ids) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM item_mail WHERE id=?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            } catch (SQLException ex) {
                plugin.getLogger().warning("Auction mail cleanup failed: " + ex.getMessage());
            }
        }
    }

    private String classify(Material material) {
        String n = material.name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE")
                || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.equals("BOW")
                || n.equals("CROSSBOW") || n.equals("TRIDENT")) return "weapons_tools";
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS") || n.equals("ELYTRA") || n.equals("SHIELD")) return "armor";
        if (n.contains("ORE") || n.endsWith("_INGOT") || n.equals("DIAMOND")
                || n.equals("EMERALD") || n.startsWith("RAW_")) return "ores_resources";
        if (n.contains("POTION")) return "potions";
        if (n.contains("BREAD") || n.contains("BEEF") || n.contains("PORKCHOP")
                || n.contains("CHICKEN") || n.contains("CARROT") || n.contains("POTATO")
                || n.contains("APPLE") || n.contains("MELON") || n.contains("BERRY")
                || n.contains("COOKIE") || n.contains("FISH")) return "food";
        if (n.contains("NETHER_STAR") || n.contains("DRAGON_EGG") || n.contains("NETHERITE")
                || n.contains("TOTEM") || n.contains("SHULKER")) return "rare_items";
        if (material.isBlock()) return "building_blocks";
        return "miscellaneous";
    }

    private String categoryName(String category) {
        return switch (category) {
            case "weapons_tools" -> "Оружие/инструменты";
            case "armor" -> "Броня";
            case "ores_resources" -> "Руды/ресурсы";
            case "building_blocks" -> "Блоки";
            case "food" -> "Еда";
            case "potions" -> "Зелья";
            case "rare_items" -> "Редкости";
            case "miscellaneous" -> "Разное";
            default -> "Все лоты";
        };
    }

    private String sortName(String sort) {
        return switch (sort) {
            case "asc" -> "цена ↑";
            case "desc" -> "цена ↓";
            default -> "новые";
        };
    }

    private long hoursLeft(long expires) {
        return Math.max(0, (expires - System.currentTimeMillis()) / 3_600_000L);
    }

    private String money(double value) { return String.format(Locale.US, "%.2f", value); }
    private String msg(String text) {
        return color(plugin.getConfig().getString("theme.prefix", "&8[&5Moon&bEclips&8] ") + text);
    }
    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }

    private ItemStack button(Material type, String title, List<String> lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(title));
        meta.setLore(lore.stream().map(AuctionManager::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof AuctionHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        if (slot >= 0 && slot <= 8) {
            open(player, CATEGORIES[slot], holder.search, holder.sort, 0);
            return;
        }
        if (slot == 50) {
            String sort = holder.sort.equals("asc") ? "desc" : "asc";
            open(player, holder.category, holder.search, sort, 0);
            return;
        }
        if (slot == 45) {
            open(player, holder.category, holder.search, holder.sort, Math.max(0, holder.page - 1));
            return;
        }
        if (slot == 53) {
            open(player, holder.category, holder.search, holder.sort, holder.page + 1);
            return;
        }
        Listing listing = holder.listingAt(slot);
        if (listing != null) {
            if (listing.sellerUuid.equals(player.getUniqueId().toString())) withdraw(player, listing.id);
            else purchase(player, listing.id);
            open(player, holder.category, holder.search, holder.sort, holder.page);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AuctionHolder) event.setCancelled(true);
    }

    private static final class AuctionHolder implements InventoryHolder {
        private final String category;
        private final String search;
        private final String sort;
        private final int page;
        private final List<Listing> listings;

        private AuctionHolder(String category, String search, String sort, int page, List<Listing> listings) {
            this.category = category;
            this.search = search;
            this.sort = sort;
            this.page = page;
            this.listings = listings;
        }

        private Listing listingAt(int slot) {
            int index = slot - 9;
            return index >= 0 && index < listings.size() ? listings.get(index) : null;
        }

        @Override public Inventory getInventory() { return null; }
    }

    private record Listing(int id, String sellerUuid, String sellerName, ItemStack item,
                           double price, long expiresAt) {}
}