package ru.mooneclips.economicmooneclips;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class TraderManager implements Listener {
    private static final int OFFER_COUNT = 18;
    private static final Set<Material> EXCLUDED = EnumSet.of(
        Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.BEDROCK,
        Material.BARRIER, Material.COMMAND_BLOCK, Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK, Material.COMMAND_BLOCK_MINECART,
        Material.STRUCTURE_BLOCK, Material.STRUCTURE_VOID, Material.JIGSAW,
        Material.DEBUG_STICK, Material.KNOWLEDGE_BOOK, Material.LIGHT,
        Material.SPAWNER, Material.TRIAL_SPAWNER, Material.END_PORTAL_FRAME
    );

    private final EconomicMoonEclips plugin;
    private final Economy economy;
    private final File file;
    private final Random random = new Random();
    private final List<Offer> merchantSells = new ArrayList<>();
    private final List<Offer> merchantBuys = new ArrayList<>();
    private long sellRefreshAt;
    private long buyRefreshAt;

    public TraderManager(EconomicMoonEclips plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "trader.yml");
        load();
        refreshIfNeeded();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshIfNeeded, 20L * 60L, 20L * 60L);
    }

    public void open(Player player) { open(player, "buy"); }

    private void open(Player player, String tab) {
        refreshIfNeeded();
        List<Offer> offers = tab.equals("buy") ? merchantSells : merchantBuys;
        TraderHolder holder = new TraderHolder(tab);
        Inventory inv = Bukkit.createInventory(holder, 27,
            color("&0✦ &5Случайный торговец &8• " + (tab.equals("buy") ? "&aПокупка" : "&eСкупка")));
        for (int i = 0; i < offers.size() && i < 18; i++) {
            Offer offer = offers.get(i);
            double price = tab.equals("buy") ? offer.sellPrice : offer.buyPrice;
            inv.setItem(i, button(offer.material, "&f" + pretty(offer.material),
                List.of("&7Цена за штуку: &b" + money(price) + "$",
                    tab.equals("buy") ? "&eНажми, чтобы купить" : "&eНажми, чтобы продать",
                    "&8Ассортимент обновляется каждые 2 часа")));
        }
        inv.setItem(18, button(Material.EMERALD, "&aКупить",
            List.of(tab.equals("buy") ? "&aТекущая вкладка" : "&7Открыть товары торговца")));
        inv.setItem(20, button(Material.GOLD_INGOT, "&eПродать",
            List.of(tab.equals("sell") ? "&aТекущая вкладка" : "&7Продать предметы торговцу")));
        inv.setItem(22, button(Material.CLOCK, "&bОбновление ассортимента",
            List.of("&7Покупка: &f" + timeLeft(sellRefreshAt), "&7Скупка: &f" + timeLeft(buyRefreshAt))));
        inv.setItem(26, button(Material.ARROW, "&cНазад", List.of("&7Вернуться в категории")));
        player.openInventory(inv);
    }

    private void refreshIfNeeded() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        if (sellRefreshAt <= now || merchantSells.isEmpty()) {
            merchantSells.clear();
            merchantSells.addAll(generateOffers());
            sellRefreshAt = now + 2L * 60L * 60L * 1000L;
            changed = true;
        }
        if (buyRefreshAt <= now || merchantBuys.isEmpty()) {
            merchantBuys.clear();
            merchantBuys.addAll(generateOffers());
            buyRefreshAt = now + 2L * 60L * 60L * 1000L;
            changed = true;
        }
        if (changed) save();
    }

    private List<Offer> generateOffers() {
        List<Material> pool = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isAir() && !EXCLUDED.contains(material)) pool.add(material);
        }
        Collections.shuffle(pool, random);
        List<Offer> result = new ArrayList<>();
        Set<Material> used = EnumSet.noneOf(Material.class);
        for (Material material : pool) {
            if (used.add(material)) {
                double sell = randomPrice(material);
                double buy = Math.max(1.0, Math.round(sell * (0.60 + random.nextDouble() * 0.10) * 100.0) / 100.0);
                result.add(new Offer(material, sell, buy));
                if (result.size() >= OFFER_COUNT) break;
            }
        }
        return result;
    }

    private double randomPrice(Material material) {
        String n = material.name();
        int min = 1, max = 100;
        if (n.contains("NETHERITE") || n.equals("ELYTRA") || n.equals("DRAGON_EGG")
                || n.equals("NETHER_STAR") || n.equals("TOTEM_OF_UNDYING")
                || n.equals("ENCHANTED_GOLDEN_APPLE")) { min = 9000; max = 20000; }
        else if (n.contains("DIAMOND") || n.equals("EMERALD") || n.contains("SHULKER")
                || n.contains("TRIDENT") || n.equals("HEAVY_CORE") || n.equals("HEART_OF_THE_SEA")) { min = 1500; max = 9000; }
        else if (n.contains("GOLD") || n.contains("IRON") || n.contains("COPPER")
                || n.contains("REDSTONE") || n.contains("LAPIS") || n.contains("QUARTZ")
                || n.contains("AMETHYST") || n.contains("BLAZE") || n.contains("ENDER")) { min = 150; max = 2500; }
        else if (n.contains("WOOD") || n.contains("LOG") || n.contains("PLANK")
                || n.contains("STONE") || n.contains("DIRT") || n.contains("SAND")
                || n.contains("GRAVEL") || n.contains("COBBLE")) { min = 1; max = 350; }
        else if (n.contains("SWORD") || n.contains("PICKAXE") || n.contains("AXE")
                || n.contains("SHOVEL") || n.contains("HOE") || n.contains("HELMET")
                || n.contains("CHESTPLATE") || n.contains("LEGGINGS") || n.contains("BOOTS")) { min = 100; max = 5000; }
        else if (n.contains("POTION") || n.contains("BOOK") || n.contains("DISC")
                || n.contains("FIREWORK") || n.contains("SPAWN_EGG")) { min = 50; max = 3500; }
        else if (n.contains("BEEF") || n.contains("PORK") || n.contains("CHICKEN")
                || n.contains("BREAD") || n.contains("CARROT") || n.contains("POTATO")
                || n.contains("WHEAT") || n.contains("FISH") || n.contains("APPLE")) { min = 1; max = 250; }
        else { min = 5; max = 1200; }
        return min + random.nextInt(max - min + 1);
    }

    private void buy(Player player, Offer offer) {
        if (!canFit(player, new ItemStack(offer.material, 1))) {
            player.sendMessage(msg("&cОсвободи место в инвентаре."));
            return;
        }
        if (economy.getBalance(player) < offer.sellPrice) {
            player.sendMessage(msg("&cНедостаточно денег. Нужно: &b" + money(offer.sellPrice) + "$"));
            return;
        }
        EconomyResponse debit = economy.withdrawPlayer(player, offer.sellPrice);
        if (!debit.transactionSuccess()) {
            player.sendMessage(msg("&cНе удалось списать деньги."));
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(offer.material, 1));
        if (!overflow.isEmpty()) {
            economy.depositPlayer(player, offer.sellPrice);
            player.sendMessage(msg("&cНе удалось выдать предмет. Деньги возвращены."));
            return;
        }
        player.sendMessage(msg("&aКуплено: &f" + pretty(offer.material) + " &7за &b" + money(offer.sellPrice) + "$"));
    }

    private void sell(Player player, Offer offer) {
        int amount = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack != null && stack.getType() == offer.material) {
                amount += stack.getAmount();
                contents[i] = null;
            }
        }
        if (amount == 0) {
            player.sendMessage(msg("&cУ тебя нет этого предмета."));
            return;
        }
        double total = amount * offer.buyPrice;
        EconomyResponse payout = economy.depositPlayer(player, total);
        if (!payout.transactionSuccess()) {
            player.sendMessage(msg("&cНе удалось зачислить деньги. Предметы сохранены."));
            return;
        }
        player.getInventory().setStorageContents(contents);
        player.sendMessage(msg("&aПродано: &f" + pretty(offer.material) + " x" + amount
            + " &7за &b" + money(total) + "$"));
    }

    private boolean canFit(Player player, ItemStack item) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = item.getAmount();
        for (ItemStack stack : contents) {
            if (stack == null || stack.getType().isAir()) { remaining -= item.getMaxStackSize(); }
            else if (stack.isSimilar(item)) remaining -= Math.max(0, stack.getMaxStackSize() - stack.getAmount());
            if (remaining <= 0) return true;
        }
        return false;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        sellRefreshAt = cfg.getLong("sell-refresh-at", 0L);
        buyRefreshAt = cfg.getLong("buy-refresh-at", 0L);
        loadOffers(cfg, "merchant-sells", merchantSells);
        loadOffers(cfg, "merchant-buys", merchantBuys);
    }

    private void loadOffers(YamlConfiguration cfg, String path, List<Offer> target) {
        for (String key : cfg.getConfigurationSection(path) == null ? List.<String>of()
                : cfg.getConfigurationSection(path).getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || !material.isItem() || material.isAir() || EXCLUDED.contains(material)) continue;
            target.add(new Offer(material, cfg.getDouble(path + "." + key + ".sell", 1),
                cfg.getDouble(path + "." + key + ".buy", 1)));
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("sell-refresh-at", sellRefreshAt);
        cfg.set("buy-refresh-at", buyRefreshAt);
        saveOffers(cfg, "merchant-sells", merchantSells);
        saveOffers(cfg, "merchant-buys", merchantBuys);
        try { cfg.save(file); }
        catch (IOException ex) { plugin.getLogger().warning("Could not save trader stock: " + ex.getMessage()); }
    }

    private void saveOffers(YamlConfiguration cfg, String path, List<Offer> offers) {
        for (Offer offer : offers) {
            cfg.set(path + "." + offer.material.name() + ".sell", offer.sellPrice);
            cfg.set(path + "." + offer.material.name() + ".buy", offer.buyPrice);
        }
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof TraderHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        if (slot == 18) { open(player, "buy"); return; }
        if (slot == 20) { open(player, "sell"); return; }
        if (slot == 26) { player.closeInventory(); plugin.getSellManager().openMain(player); return; }
        if (slot < 0 || slot >= OFFER_COUNT) return;
        List<Offer> offers = holder.tab.equals("buy") ? merchantSells : merchantBuys;
        if (slot >= offers.size()) return;
        Offer offer = offers.get(slot);
        if (holder.tab.equals("buy")) buy(player, offer); else sell(player, offer);
        open(player, holder.tab);
    }

    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof TraderHolder) event.setCancelled(true);
    }

    private ItemStack button(Material type, String title, List<String> lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(title));
        meta.setLore(lore.stream().map(TraderManager::color).toList());
        item.setItemMeta(meta);
        return item;
    }
    private String timeLeft(long timestamp) {
        long seconds = Math.max(0, (timestamp - System.currentTimeMillis()) / 1000);
        return (seconds / 3600) + "ч " + ((seconds % 3600) / 60) + "м";
    }
    private String pretty(Material material) {
        return Arrays.stream(material.name().toLowerCase(Locale.ROOT).split("_"))
            .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
            .reduce((a,b) -> a + " " + b).orElse(material.name());
    }
    private String money(double value) { return String.format(Locale.US, "%.2f", value); }
    private String msg(String text) {
        return color(plugin.getConfig().getString("theme.prefix", "&8[&5Moon&bEclips&8] ") + text);
    }
    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }

    private record Offer(Material material, double sellPrice, double buyPrice) {}
    private static final class TraderHolder implements InventoryHolder {
        private final String tab;
        private TraderHolder(String tab) { this.tab = tab; }
        @Override public Inventory getInventory() { return null; }
    }
}
