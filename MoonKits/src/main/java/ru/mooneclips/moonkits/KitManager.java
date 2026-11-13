package ru.mooneclips.moonkits;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class KitManager {
    private final MoonKitsPlugin plugin;
    private final Map<String, Kit> kits = new LinkedHashMap<>();
    private final File kitsFile;
    private final File cooldownsFile;
    private final File statesFile;
    private YamlConfiguration kitsYaml;
    private YamlConfiguration cooldownsYaml;
    private YamlConfiguration statesYaml;

    public KitManager(MoonKitsPlugin plugin) {
        this.plugin = plugin;
        kitsFile = new File(plugin.getDataFolder(), "kits.yml");
        cooldownsFile = new File(plugin.getDataFolder(), "cooldowns.yml");
        statesFile = new File(plugin.getDataFolder(), "player-state.yml");
        reload();
    }

    public synchronized void reload() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        kitsYaml = YamlConfiguration.loadConfiguration(kitsFile);
        cooldownsYaml = YamlConfiguration.loadConfiguration(cooldownsFile);
        statesYaml = YamlConfiguration.loadConfiguration(statesFile);
        kits.clear();
        ConfigurationSection root = kitsYaml.getConfigurationSection("kits");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            String path = "kits." + id + ".";
            List<ItemStack> items = new ArrayList<>();
            for (Object value : kitsYaml.getList(path + "items", Collections.emptyList())) {
                if (value instanceof ItemStack item && !item.getType().isAir()) items.add(item.clone());
            }
            kits.put(id.toLowerCase(Locale.ROOT), new Kit(
                    id.toLowerCase(Locale.ROOT),
                    kitsYaml.getString(path + "display-name", id),
                    kitsYaml.getString(path + "permission", "moonkits.kit." + id.toLowerCase(Locale.ROOT)),
                    kitsYaml.getLong(path + "cooldown-seconds", 86400),
                    kitsYaml.getBoolean(path + "automatic-delivery", true),
                    items
            ));
        }
    }

    public synchronized Collection<Kit> getKits() { return new ArrayList<>(kits.values()); }
    public synchronized Kit getKit(String id) { return kits.get(id.toLowerCase(Locale.ROOT)); }

    public synchronized Kit createKit(String rawId) {
        String id = rawId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        if (id.isBlank() || kits.containsKey(id)) return null;
        Kit kit = new Kit(id, id.toUpperCase(Locale.ROOT),
                "moonkits.kit." + id,
                plugin.getConfig().getLong("settings.default-cooldown-seconds", 86400),
                true, new ArrayList<>());
        kits.put(id, kit);
        saveKit(kit);
        return kit;
    }

    public synchronized void saveKit(Kit kit) {
        String path = "kits." + kit.getId() + ".";
        kitsYaml.set(path + "display-name", kit.getDisplayName());
        kitsYaml.set(path + "permission", kit.getPermission());
        kitsYaml.set(path + "cooldown-seconds", kit.getCooldownSeconds());
        kitsYaml.set(path + "automatic-delivery", kit.isAutomaticDelivery());
        kitsYaml.set(path + "items", kit.getItems());
        save(kitsYaml, kitsFile);
        kits.put(kit.getId(), kit);
    }

    public synchronized void deleteKit(String id) {
        kits.remove(id.toLowerCase(Locale.ROOT));
        kitsYaml.set("kits." + id.toLowerCase(Locale.ROOT), null);
        save(kitsYaml, kitsFile);
    }

    public boolean canFit(Player player, Kit kit) {
        Inventory simulation = Bukkit.createInventory(null, 36);
        simulation.setContents(player.getInventory().getStorageContents());
        Map<Integer, ItemStack> leftovers = simulation.addItem(kit.getItems().toArray(new ItemStack[0]));
        return leftovers.isEmpty();
    }

    public boolean deliver(Player player, Kit kit, boolean ignoreCooldown) {
        if (kit == null || kit.getItems().isEmpty()) {
            player.sendMessage(plugin.msg("kit-not-found"));
            return false;
        }
        long remaining = getRemainingCooldown(player.getUniqueId(), kit);
        if (!ignoreCooldown && remaining > 0) {
            player.sendMessage(plugin.msg("cooldown").replace("%time%", formatDuration(remaining)));
            return false;
        }
        if (!canFit(player, kit)) {
            player.sendMessage(plugin.msg("inventory-full"));
            return false;
        }
        player.getInventory().addItem(kit.getItems().toArray(new ItemStack[0]));
        setCooldown(player.getUniqueId(), kit);
        return true;
    }

    public synchronized long getRemainingCooldown(UUID uuid, Kit kit) {
        long until = cooldownsYaml.getLong(uuid + "." + kit.getId(), 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    public synchronized void setCooldown(UUID uuid, Kit kit) {
        long until = System.currentTimeMillis() + kit.getCooldownSeconds() * 1000L;
        cooldownsYaml.set(uuid + "." + kit.getId(), until);
        save(cooldownsYaml, cooldownsFile);
    }

    public synchronized boolean hasPreviousPermissionState(UUID uuid, Kit kit) {
        return statesYaml.contains(uuid + "." + kit.getId());
    }

    public synchronized boolean getPreviousPermissionState(UUID uuid, Kit kit) {
        return statesYaml.getBoolean(uuid + "." + kit.getId(), false);
    }

    public synchronized void setPermissionState(UUID uuid, Kit kit, boolean value) {
        statesYaml.set(uuid + "." + kit.getId(), value);
        save(statesYaml, statesFile);
    }

    private void save(YamlConfiguration yaml, File file) {
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить " + file.getName() + ": " + exception.getMessage());
        }
    }

    public static String formatDuration(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60; seconds %= 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + " д.");
        if (hours > 0) parts.add(hours + " ч.");
        if (minutes > 0) parts.add(minutes + " мин.");
        if (seconds > 0 && parts.size() < 2) parts.add(seconds + " сек.");
        return String.join(" ", parts);
    }
}