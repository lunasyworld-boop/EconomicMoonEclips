package ru.mooneclips.economicmooneclips;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
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
    private TokensManager tokensManager;
    private java.util.List<java.util.AbstractMap.SimpleEntry<String, Double>> topCache = java.util.List.of();
    private long topCacheAt;

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
        try {
            tokensManager = new TokensManager(getDataFolder());
        } catch (java.sql.SQLException ex) {
            getLogger().severe("Cannot initialize token storage: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        sellManager = new SellManager(this, economy);
        auctionManager = new AuctionManager(this, economy);
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            getLogger().warning("LuckPerms not found. Rank permissions must be provided by a permissions plugin.");
        }
        getLogger().info("EconomicMoonEclips 1.21.1-b enabled. Money: " + economy.getName() + ", Tokens: SQLite");
    }

    @Override
    public void onDisable() {
        if (auctionManager != null) auctionManager.close();
        if (tokensManager != null) tokensManager.close();
    }

    public SellManager getSellManager() { return sellManager; }

    public TokensManager getTokensManager() { return tokensManager; }

    public long getTokens(java.util.UUID playerId) {
        return tokensManager == null ? 0L : tokensManager.getBalance(playerId);
    }

    public boolean depositTokens(OfflinePlayer player, long amount, String reason) {
        return tokensManager != null && tokensManager.deposit(player, amount, "API", reason);
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
        if (name.equals("tokens")) {
            return handleTokensCommand(sender, args);
        }
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
        if (System.currentTimeMillis() - topCacheAt >= 30_000L) {
            topCache = java.util.Arrays.stream(Bukkit.getOfflinePlayers())
                    .filter(p -> p.getName() != null)
                    .map(p -> new java.util.AbstractMap.SimpleEntry<>(p.getName(), economy.getBalance(p)))
                    .sorted(java.util.Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                    .limit(10).toList();
            topCacheAt = System.currentTimeMillis();
        }
        var top = topCache;
        if (top.isEmpty()) sender.sendMessage(color("&7Пока нет данных."));
        for (int i = 0; i < top.size(); i++) {
            var entry = top.get(i);
            sender.sendMessage(color("&b" + (i + 1) + ". &f" + entry.getKey()
                    + " &8— &a" + String.format(Locale.US, "%.2f", entry.getValue()) + "$"));
        }
    }


    private boolean handleTokensCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(color("&eИспользование: /tokens <ник>"));
                return true;
            }
            sender.sendMessage(color("&8[&5Moon&bEclips&8] &fВаш баланс: &e✦ " + tokensManager.getBalance(player.getUniqueId())));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("top")) {
            sender.sendMessage(color("&8&m──────── &5Moon&bEclips &8• &fТоп токенов &8&m────────"));
            var top = tokensManager.getTop(10);
            if (top.isEmpty()) sender.sendMessage(color("&7Пока нет данных."));
            for (int i = 0; i < top.size(); i++) {
                var entry = top.get(i);
                sender.sendMessage(color("&b" + (i + 1) + ". &f" + entry.name() + " &8— &e✦ " + entry.balance()));
            }
            return true;
        }

        if (sub.equals("pay")) {
            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage(color("&cКоманда доступна только игроку."));
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(color("&cИспользование: /tokens pay <игрок> <количество>"));
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                sender.sendMessage(color("&cИгрок не найден."));
                return true;
            }
            long amount = parseTokenAmount(args[2]);
            if (amount <= 0) {
                sender.sendMessage(color("&cУкажи целое положительное количество токенов."));
                return true;
            }
            if (!tokensManager.transfer(player, target, amount)) {
                sender.sendMessage(color("&cПеревод не выполнен. Проверь баланс получателя и свой баланс."));
                return true;
            }
            sender.sendMessage(color("&aПереведено &e✦ " + amount + " &aигроку &f" + target.getName() + "&a."));
            if (target.isOnline()) target.getPlayer().sendMessage(color("&aПолучено &e✦ " + amount + " &aот &f" + player.getName() + "&a."));
            return true;
        }

        if (!sender.hasPermission("economicmooneclips.admin")) {
            sender.sendMessage(color("&cНедостаточно прав."));
            return true;
        }
        if ((sub.equals("give") || sub.equals("take") || sub.equals("set")) && args.length == 3) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.isOnline() && !target.hasPlayedBefore()) {
                sender.sendMessage(color("&cИгрок не найден."));
                return true;
            }
            long amount = parseTokenAmount(args[2]);
            if (amount < 0 || (sub.equals("give") || sub.equals("take")) && amount == 0) {
                sender.sendMessage(color("&cУкажи целое положительное количество токенов."));
                return true;
            }
            boolean success = switch (sub) {
                case "give" -> tokensManager.deposit(target, amount, sender.getName(), "admin/case credit");
                case "take" -> tokensManager.withdraw(target, amount, sender.getName(), "admin debit");
                case "set" -> tokensManager.setBalance(target, amount, sender.getName());
                default -> false;
            };
            if (!success) {
                sender.sendMessage(color("&cОперация не выполнена. Возможно, недостаточно токенов или превышен лимит баланса."));
                return true;
            }
            sender.sendMessage(color("&aБаланс игрока &f" + target.getName() + " &aобновлён: &e✦ " + tokensManager.getBalance(target.getUniqueId())));
            if (target.isOnline() && sub.equals("give")) {
                target.getPlayer().sendMessage(color("&aВам начислено &e✦ " + amount + "&a."));
            }
            return true;
        }
        sender.sendMessage(color("&cИспользование: /tokens [pay <игрок> <количество>|give <игрок> <количество>|take <игрок> <количество>|set <игрок> <количество>|top]"));
        return true;
    }

    private long parseTokenAmount(String input) {
        try {
            long value = Long.parseLong(input);
            return value >= 0 ? value : -1L;
        } catch (NumberFormatException ex) {
            return -1L;
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
        EconomyResponse response;
        if (give) {
            response = economy.depositPlayer(target, value);
        } else {
            if (economy.getBalance(target) < value) {
                sender.sendMessage(color("&cУ игрока недостаточно средств."));
                return true;
            }
            response = economy.withdrawPlayer(target, value);
        }
        if (!response.transactionSuccess()) {
            sender.sendMessage(color("&cОперация с балансом не выполнена: " + response.errorMessage));
            return true;
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