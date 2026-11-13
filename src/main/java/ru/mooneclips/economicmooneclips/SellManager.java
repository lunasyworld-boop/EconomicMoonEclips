package ru.mooneclips.economicmooneclips;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class SellManager {
    private final EconomicMoonEclips plugin;
    private final Economy economy;
    private final TraderManager traderManager;
    private final Map<UUID, Long> pendingConfirmations = new HashMap<>();
    private final Map<String, Map<Material, Double>> prices = new LinkedHashMap<>();
    private final Map<String, String> titles = new LinkedHashMap<>();

    public SellManager(EconomicMoonEclips plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.traderManager = new TraderManager(plugin, economy);
        loadPrices();
        Bukkit.getPluginManager().registerEvents(new SellListener(), plugin);
    }

    private void loadPrices() {
        var file = plugin.getDataFolder().toPath().resolve("prices.yml").toFile();
        var cfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        ConfigurationSection cats = cfg.getConfigurationSection("categories");
        if (cats == null) return;
        for (String category : cats.getKeys(false)) {
            ConfigurationSection section = cats.getConfigurationSection(category);
            if (section == null) continue;
            titles.put(category, section.getString("title", category));
            Map<Material, Double> map = new LinkedHashMap<>();
            ConfigurationSection items = section.getConfigurationSection("items");
            if (items != null) for (String key : items.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                double price = items.getDouble(key, -1);
                if (material != null && material.isItem() && price > 0) map.put(material, price);
                else plugin.getLogger().warning("Invalid sell price entry: " + key + " in " + category);
            }
            prices.put(category, map);
        }
    }

    public void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(new MenuHolder("categories"), 27, color("&0✦ &5Moon&bEclips &8• &fПродажа"));
        int slot = 10;
        Material[] icons = {Material.RAW_IRON, Material.ROTTEN_FLESH, Material.WHEAT, Material.AMETHYST_SHARD};
        int i = 0;
        for (String category : prices.keySet()) {
            Material icon = icons[Math.min(i, icons.length - 1)];
            inv.setItem(slot, button(icon, titles.get(category), List.of("&7Предметов: &f" + prices.get(category).size(), "&eНажми, чтобы открыть")));
            slot += 2;
            i++;
        }
        inv.setItem(22, button(Material.BARRIER, "&cЗакрыть", List.of()));
        player.openInventory(inv);
    }

    private void openCategory(Player player, String category) {
        Map<Material, Double> entries = prices.get(category);
        if (entries == null) return;
        Inventory inv = Bukkit.createInventory(new MenuHolder("category:" + category), 54,
                color("&0Продажа &8• " + titles.get(category)));
        int slot = 0;
        for (Map.Entry<Material, Double> entry : entries.entrySet()) {
            if (slot >= 45) break;
            inv.setItem(slot++, button(entry.getKey(), "&f" + pretty(entry.getKey()),
                    List.of("&7Цена за штуку: &b" + money(entry.getValue()) + "$", "&eНажми, чтобы продать весь стак")));
        }
        inv.setItem(49, button(Material.ARROW, "&eНазад", List.of()));
        player.openInventory(inv);
    }

    public void handleCommand(Player player, String[] args) {
        if (args.length == 0) { openMain(player); return; }
        if (!args[0].equalsIgnoreCase("all")) { player.sendMessage(msg("&cИспользование: /sell [all [confirm]]")); return; }
        if (args.length == 1) {
            pendingConfirmations.put(player.getUniqueId(), System.currentTimeMillis() + plugin.getConfig().getLong("settings.sell-all-confirmation-seconds", 15) * 1000L);
            player.sendMessage(msg("&eПовтори &f/sell all confirm &eв течение " + plugin.getConfig().getLong("settings.sell-all-confirmation-seconds", 15) + " секунд для подтверждения."));
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            Long expiry = pendingConfirmations.remove(player.getUniqueId());
            if (expiry == null || expiry < System.currentTimeMillis()) {
                player.sendMessage(msg("&cПодтверждение истекло. Выполни /sell all ещё раз."));
                return;
            }
            int count = 0;
            double total = 0;
            ItemStack[] contents = player.getInventory().getStorageContents();
            for (int i = 0; i < contents.length; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType().isAir()) continue;
                Double unit = findPrice(stack.getType());
                if (unit == null) continue;
                double multiplier = rankMultiplier(player);
                total += unit * stack.getAmount() * multiplier;
                count += stack.getAmount();
                contents[i] = null;
            }
            if (count == 0) { player.sendMessage(msg("&cНет разрешённых предметов для продажи.")); return; }
            EconomyResponse payout = economy.depositPlayer(player, total);
            if (!payout.transactionSuccess()) {
                player.sendMessage(msg("&cНе удалось зачислить деньги. Предметы сохранены."));
                return;
            }
            player.getInventory().setStorageContents(contents);
            player.sendMessage(msg("&aПродано предметов: &f" + count + " &7на сумму &b" + money(total) + "$"));
            return;
        }
        player.sendMessage(msg("&cИспользование: /sell [all [confirm]]"));
    }

    private Double findPrice(Material material) {
        for (Map<Material, Double> category : prices.values()) {
            Double value = category.get(material);
            if (value != null) return value;
        }
        return null;
    }

    private double rankMultiplier(Player player) {
        String[] ranks = {"eclipse", "crystal", "diamond", "gold", "silver", "stone"};
        for (String rank : ranks) {
            if (player.hasPermission("economicmooneclips.rank." + rank)) {
                return plugin.getConfig().getDouble("ranks." + rank + ".multiplier", 1.0);
            }
        }
        return 1.0;
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        if (!lore.isEmpty()) meta.setLore(lore.stream().map(SellManager::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private String pretty(Material material) {
        return Arrays.stream(material.name().toLowerCase(Locale.ROOT).split("_"))
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1)).reduce((a,b) -> a + " " + b).orElse(material.name());
    }
    private String money(double amount) { return String.format(Locale.US, "%.2f", amount); }
    private String msg(String text) { return color(plugin.getConfig().getString("theme.prefix", "&8[&5Moon&bEclips&8] ") + text); }
    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }

    private final class SellListener implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler
        public void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
            int slot = event.getRawSlot();
            if (holder.id.equals("categories")) {
                if (slot == 22) { player.closeInventory(); return; }
                int index = (slot - 10) / 2;
                if (slot >= 10 && slot <= 16 && (slot - 10) % 2 == 0 && index < prices.size()) {
                    openCategory(player, new ArrayList<>(prices.keySet()).get(index));
                }
                return;
            }
            if (holder.id.startsWith("category:")) {
                String category = holder.id.substring("category:".length());
                if (category.equals("miscellaneous")) { traderManager.open(player); return; }
                if (slot == 49) { openMain(player); return; }
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || clicked.getType().isAir()) return;
                Material type = clicked.getType();
                Double unit = prices.getOrDefault(category, Map.of()).get(type);
                if (unit == null) return;
                int amount = 0;
                double total = 0.0;
                ItemStack[] contents = player.getInventory().getStorageContents();
                for (int i = 0; i < contents.length; i++) {
                    ItemStack stack = contents[i];
                    if (stack != null && stack.getType() == type) {
                        amount += stack.getAmount();
                        total += unit * stack.getAmount() * rankMultiplier(player);
                        contents[i] = null;
                    }
                }
                if (amount == 0) { player.sendMessage(msg("&cУ тебя нет этого предмета.")); return; }
                EconomyResponse payout = economy.depositPlayer(player, total);
                if (!payout.transactionSuccess()) {
                    player.sendMessage(msg("&cНе удалось зачислить деньги. Предметы сохранены."));
                    return;
                }
                player.getInventory().setStorageContents(contents);
                player.sendMessage(msg("&aПродано: &f" + pretty(type) + " x" + amount + " &7за &b" + money(total) + "$"));
                openCategory(player, category);
            }
        }
        @org.bukkit.event.EventHandler
        public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
        }
        @org.bukkit.event.EventHandler
        public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
            if (event.getPlayer() instanceof Player player) {
                // Confirmation is intentionally independent of GUI state.
            }
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        private final String id;
        private MenuHolder(String id) { this.id = id; }
        @Override public Inventory getInventory() { return null; }
    }
}