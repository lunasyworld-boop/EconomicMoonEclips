package ru.mooneclips.economicmooneclips;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.Locale;

public final class EconomicMoonEclips extends JavaPlugin {
    private Economy economy;
    private SellManager sellManager;
    private AuctionManager auctionManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("prices.yml", false);
        saveResource("messages.yml", false);
        saveResource("auction.yml", false);
        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Install Vault and EssentialsX Economy.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        sellManager = new SellManager(this, economy);
        getServer().getPluginManager().registerEvents(new GoldMobListener(this, sellManager), this);
        auctionManager = new AuctionManager(this, economy);
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().warning("LuckPerms not found. Rank permissions must be provided by a permissions plugin.");
        }
        getLogger().info("EconomicMoonEclips 1.7.0 enabled. Economy: " + economy.getName());
    }

    @Override
    public void onDisable() {
        if (auctionManager != null) auctionManager.close();
    }

    private boolean setupEconomy() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> registration = getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null) return false;
        economy = registration.getProvider();
        return economy != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("pmoney") || name.equals("epmoney")) {
            return handleMoneyCommand(sender, name.equals("pmoney"), args);
        }
        if (name.equals("sell")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(color("&cКоманда доступна только игроку."));
                return true;
            }
            sellManager.handleCommand(player, args);
            return true;
        }
        if (name.equals("ah")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(color("&cКоманда доступна только игроку."));
                return true;
            }
            auctionManager.handleCommand(player, args);
            return true;
        }
        if (name.equals("top")) {
            sendTop(sender);
            return true;
        }
        return false;
    }

    private void sendTop(CommandSender sender) {
        sender.sendMessage(color("&8&m──────── &5Moon&bEclips &8• &fТоп балансов &8&m────────"));
        var top = java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(p -> p.getName() != null)
                .map(p -> new java.util.AbstractMap.SimpleEntry<>(p.getName(), economy.getBalance(p)))
                .sorted(java.util.Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(10).toList();
        if (top.isEmpty()) sender.sendMessage(color("&7Пока нет данных."));
        for (int i = 0; i < top.size(); i++) {
            var entry = top.get(i);
            sender.sendMessage(color("&b" + (i + 1) + ". &f" + entry.getKey()
                    + " &8— &a" + String.format(Locale.US, "%.2f", entry.getValue()) + "$"));
        }
    }

    private boolean handleMoneyCommand(CommandSender sender, boolean give, String[] args) {
        if (!sender.hasPermission("economicmooneclips.admin")) {
            sender.sendMessage(color("&cНедостаточно прав."));
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(color("&cИспользование: /" + (give ? "pmoney" : "epmoney") + " <ник> <количество>"));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (!target.isOnline() && !target.hasPlayedBefore()) {
            sender.sendMessage(color("&cИгрок не найден."));
            return true;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(args[1]).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.compareTo(BigDecimal.ZERO) <= 0 || !Double.isFinite(amount.doubleValue())) throw new NumberFormatException();
        } catch (NumberFormatException | ArithmeticException ex) {
            sender.sendMessage(color("&cУкажи положительное число (до 2 знаков после запятой)."));
            return true;
        }
        double value = amount.doubleValue();
        if (give) {
            economy.depositPlayer(target, value);
        } else {
            if (economy.getBalance(target) < value) {
                sender.sendMessage(color("&cУ игрока недостаточно средств."));
                return true;
            }
            economy.withdrawPlayer(target, value);
        }
        sender.sendMessage(color("&8[&5Moon&bEclips&8] " + (give ? "&aВыдано &f" : "&eСнято &f")
                + amount.toPlainString() + "$ " + (give ? "&aигроку &f" : "&eу игрока &f")
                + (target.getName() == null ? args[0] : target.getName()) + "&f."));
        return true;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}