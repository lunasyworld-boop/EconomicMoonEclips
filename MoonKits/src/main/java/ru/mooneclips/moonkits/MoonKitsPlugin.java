package ru.mooneclips.moonkits;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class MoonKitsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private KitManager kitManager;
    private KitGui gui;
    private LuckPerms luckPerms;
    private PrivilegeManager privilegeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kitManager = new KitManager(this);
        gui = new KitGui(this, kitManager);
        getServer().getPluginManager().registerEvents(gui, this);
        getServer().getPluginManager().registerEvents(this, this);

        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException exception) {
            getLogger().severe("LuckPerms API не найден. MoonKits отключён.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        privilegeManager = new PrivilegeManager(this);
        getServer().getScheduler().runTaskTimer(this, privilegeManager::checkExpirations, 20L * 30L, 20L * 30L);

        Objects.requireNonNull(getCommand("mkits")).setExecutor(this);
        Objects.requireNonNull(getCommand("mkit")).setExecutor(this);
        Objects.requireNonNull(getCommand("mkit")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("mkitadmin")).setExecutor(this);
        getLogger().info("MoonKits 1.0.0 включён. Интеграция LuckPerms активна.");

        luckPerms.getEventBus().subscribe(this, UserDataRecalculateEvent.class, event -> {
            User user = event.getUser();
            UUID uuid = user.getUniqueId();
            Bukkit.getScheduler().runTask(this, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) processAutomaticKits(user, player);
            });
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        CompletableFuture<User> future = luckPerms.getUserManager().loadUser(uuid);
        future.thenAccept(user -> Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                privilegeManager.handleJoin(uuid, player.getName());
                processAutomaticKits(user, player);
            }
        })).exceptionally(error -> {
            getLogger().warning("Не удалось загрузить LuckPerms-профиль " + uuid + ": " + error.getMessage());
            return null;
        });
    }

    private void processAutomaticKits(User user, Player player) {
        if (!getConfig().getBoolean("settings.automatic-delivery", true)) return;
        for (Kit kit : kitManager.getKits()) {
            if (!kit.isAutomaticDelivery()) continue;
            boolean hasPermission = user.getCachedData().getPermissionData()
                    .checkPermission(kit.getPermission()).asBoolean();
            boolean known = kitManager.hasPreviousPermissionState(player.getUniqueId(), kit);
            boolean previous = kitManager.getPreviousPermissionState(player.getUniqueId(), kit);

            if (!hasPermission) {
                if (!known || previous) kitManager.setPermissionState(player.getUniqueId(), kit, false);
                continue;
            }
            if (!known || !previous) {
                if (kitManager.deliver(player, kit, true)) {
                    kitManager.setPermissionState(player.getUniqueId(), kit, true);
                    player.sendMessage(msg("auto-received").replace("%kit%", kit.getDisplayName()));
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("mkits")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(msg("player-only")); return true; }
            gui.openPlayerMenu(player);
            return true;
        }
        if (name.equals("mkitadmin")) {
            if (!(sender instanceof Player player)) { sender.sendMessage(msg("player-only")); return true; }
            if (!sender.hasPermission("moonkits.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            gui.openAdminMenu(player);
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { sender.sendMessage(msg("player-only")); return true; }
            gui.openPlayerMenu(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("moonkits.reload")) { sender.sendMessage(msg("no-permission")); return true; }
            reloadConfig();
            kitManager.reload();
            sender.sendMessage(msg("reload"));
            return true;
        }
        if (sub.equals("create") || sub.equals("edit") || sub.equals("delete")
                || sub.equals("give") || sub.equals("preview")) {
            if (!sender.hasPermission("moonkits.admin")) { sender.sendMessage(msg("no-permission")); return true; }
        }
        if (sub.equals("create")) {
            if (args.length < 2) { sender.sendMessage(color("&cИспользование: /mkit create <название>")); return true; }
            Kit kit = kitManager.createKit(args[1]);
            if (kit == null) { sender.sendMessage(color("&cТакой кит уже существует или имя некорректно.")); return true; }
            sender.sendMessage(msg("kit-created").replace("%kit%", kit.getId()));
            if (sender instanceof Player player) gui.openEditor(player, kit);
            return true;
        }
        if (sub.equals("edit")) {
            if (args.length < 2) { sender.sendMessage(color("&cИспользование: /mkit edit <название>")); return true; }
            Kit kit = kitManager.getKit(args[1]);
            if (kit == null) { sender.sendMessage(msg("kit-not-found")); return true; }
            if (sender instanceof Player player) gui.openEditor(player, kit);
            else sender.sendMessage(msg("player-only"));
            return true;
        }
        if (sub.equals("delete")) {
            if (args.length < 2) { sender.sendMessage(color("&cИспользование: /mkit delete <название>")); return true; }
            Kit kit = kitManager.getKit(args[1]);
            if (kit == null) { sender.sendMessage(msg("kit-not-found")); return true; }
            kitManager.deleteKit(kit.getId());
            sender.sendMessage(msg("kit-deleted").replace("%kit%", kit.getDisplayName()));
            return true;
        }
        if (sub.equals("give")) {
            if (!sender.hasPermission("moonkits.give")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length < 3) { sender.sendMessage(color("&cИспользование: /mkit give <игрок> <кит>")); return true; }
            Player target = Bukkit.getPlayerExact(args[1]);
            Kit kit = kitManager.getKit(args[2]);
            if (target == null) { sender.sendMessage(color("&cИгрок должен быть онлайн.")); return true; }
            if (kit == null) { sender.sendMessage(msg("kit-not-found")); return true; }
            if (kitManager.deliver(target, kit, true)) {
                sender.sendMessage(msg("admin-give").replace("%kit%", kit.getDisplayName()).replace("%player%", target.getName()));
            }
            return true;
        }
        if (sub.equals("preview")) {
            if (args.length < 2) { sender.sendMessage(color("&cИспользование: /mkit preview <название>")); return true; }
            Kit kit = kitManager.getKit(args[1]);
            if (kit == null) { sender.sendMessage(msg("kit-not-found")); return true; }
            if (sender instanceof Player player) gui.openPreview(player, kit);
            else sender.sendMessage(msg("player-only"));
            return true;
        }
        if (sub.equals("info")) {
            if (args.length < 2) { sender.sendMessage(color("&cИспользование: /mkit info <название>")); return true; }
            Kit kit = kitManager.getKit(args[1]);
            if (kit == null) { sender.sendMessage(msg("kit-not-found")); return true; }
            sender.sendMessage(color("&5MoonKits &8» &f" + kit.getDisplayName()));
            sender.sendMessage(color("&7ID: &f" + kit.getId()));
            sender.sendMessage(color("&7Permission: &f" + kit.getPermission()));
            sender.sendMessage(color("&7Cooldown: &f" + kit.getCooldownSeconds() + " сек."));
            sender.sendMessage(color("&7Автовыдача: &f" + (kit.isAutomaticDelivery() ? "включена" : "выключена")));
            sender.sendMessage(color("&7Слотов с предметами: &f" + kit.getItems().size()));
            return true;
        }

        Kit kit = kitManager.getKit(sub);
        if (kit == null) { sender.sendMessage(msg("kit-not-found")); return true; }
        if (!(sender instanceof Player player)) { sender.sendMessage(msg("player-only")); return true; }
        if (!player.hasPermission(kit.getPermission())) { player.sendMessage(msg("no-kit-permission")); return true; }
        if (kitManager.deliver(player, kit, false)) player.sendMessage(msg("kit-received").replace("%kit%", kit.getDisplayName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(List.of("create", "edit", "delete", "give", "preview", "info", "reload"), args[0]);
        if (args.length == 2 && List.of("edit", "delete", "preview", "info").contains(args[0].toLowerCase(Locale.ROOT)))
            return filter(kitManager.getKits().stream().map(Kit::getId).toList(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("give"))
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("give"))
            return filter(kitManager.getKits().stream().map(Kit::getId).toList(), args[2]);
        return Collections.emptyList();
    }

    private List<String> filter(List<String> values, String prefix) {
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))).toList();
    }


    public LuckPerms getLuckPerms() { return luckPerms; }
    public PrivilegeManager getPrivilegeManager() { return privilegeManager; }

    public String getPrivilegeGroup(String kitId) {
        return getConfig().getString("privileges.groups." + kitId.toLowerCase(Locale.ROOT), kitId);
    }

    public void notifyPrivilegeGranted(UUID uuid, String kitId, long durationMillis) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            Kit kit = kitManager.getKit(kitId);
            String name = kit == null ? kitId : kit.getDisplayName();
            player.sendMessage(color("&aТебе выдана привилегия &f" + name + " &aна &f"
                    + KitManager.formatDuration(durationMillis) + "&a."));
        }
    }

    public void notifyPrivilegeExpired(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) player.sendMessage(color("&eСрок действия привилегии истёк. Тебе назначена группа player."));
    }

    public String msg(String key) {
        String value = getConfig().getString("messages." + key, "&c" + key);
        String prefix = getConfig().getString("messages.prefix", "");
        return color(prefix + value);
    }

    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
}