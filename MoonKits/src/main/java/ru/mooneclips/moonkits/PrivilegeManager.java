package ru.mooneclips.moonkits;

import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

public final class PrivilegeManager {
    public record Grant(UUID uuid, String playerName, String kitId, String group, long expiresAt) {
        public long remainingMillis() { return Math.max(0L, expiresAt - System.currentTimeMillis()); }
    }

    private final MoonKitsPlugin plugin;
    private final File file;
    private final Map<UUID, Grant> grants = new LinkedHashMap<>();
    private YamlConfiguration yaml;

    public PrivilegeManager(MoonKitsPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "privileges.yml");
        load();
    }

    private synchronized void load() {
        yaml = YamlConfiguration.loadConfiguration(file);
        grants.clear();
        ConfigurationSectionCompat.forEach(yaml, uuidText -> {
            try {
                UUID uuid = UUID.fromString(uuidText);
                String path = uuidText + ".";
                String name = yaml.getString(path + "player-name", "Unknown");
                String kit = yaml.getString(path + "kit", "");
                String group = yaml.getString(path + "group", "");
                long expiry = yaml.getLong(path + "expires-at", 0L);
                if (!kit.isBlank() && !group.isBlank() && expiry > 0L)
                    grants.put(uuid, new Grant(uuid, name, kit, group, expiry));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Пропущена некорректная запись в privileges.yml: " + uuidText);
            }
        });
    }

    public void give(UUID uuid, String playerName, String kitId, long durationMillis) {
        String group = plugin.getConfig().getString("privileges.groups." + kitId.toLowerCase(Locale.ROOT), kitId)
                .toLowerCase(Locale.ROOT);
        String defaultGroup = defaultGroup();
        if (group.equals(defaultGroup)) {
            plugin.getLogger().warning("Привилегия " + kitId + " совпадает с группой по умолчанию; выдача отменена.");
            return;
        }
        Grant previous;
        synchronized (this) { previous = grants.get(uuid); }
        plugin.getLuckPerms().getUserManager().modifyUser(uuid, user -> {
            if (previous != null) removeGroup(user, previous.group());
            removeGroup(user, defaultGroup);
            user.data().add(InheritanceNode.builder(group).expiry(Duration.ofMillis(durationMillis)).build());
        }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().warning("Не удалось выдать группу " + group + " игроку " + playerName + ": " + error.getMessage());
                return;
            }
            Grant grant = new Grant(uuid, playerName, kitId, group, System.currentTimeMillis() + durationMillis);
            synchronized (this) { grants.put(uuid, grant); save(); }
            plugin.notifyPrivilegeGranted(uuid, kitId, durationMillis);
        }));
    }

    public void handleJoin(UUID uuid, String playerName) {
        Grant grant;
        synchronized (this) { grant = grants.get(uuid); }
        String defaultGroup = defaultGroup();
        if (grant != null && grant.expiresAt() <= System.currentTimeMillis()) {
            expire(grant);
            return;
        }
        plugin.getLuckPerms().getUserManager().modifyUser(uuid, user -> {
            if (grant != null) {
                removeGroup(user, defaultGroup);
                removeGroup(user, grant.group());
                user.data().add(InheritanceNode.builder(grant.group())
                        .expiry(Duration.ofMillis(grant.remainingMillis())).build());
            } else if (!hasManagedPrivilege(user)) {
                addDefaultIfMissing(user, defaultGroup);
            }
        }).exceptionally(error -> {
            plugin.getLogger().warning("Не удалось проверить привилегию игрока " + playerName + ": " + error.getMessage());
            return null;
        });
    }

    public void checkExpirations() {
        List<Grant> expired;
        synchronized (this) {
            long now = System.currentTimeMillis();
            expired = grants.values().stream().filter(g -> g.expiresAt() <= now).toList();
        }
        expired.forEach(this::expire);
    }

    private void expire(Grant grant) {
        plugin.getLuckPerms().getUserManager().modifyUser(grant.uuid(), user -> {
            removeGroup(user, grant.group());
            if (!hasManagedPrivilege(user)) addDefaultIfMissing(user, defaultGroup());
        }).whenComplete((ignored, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getLogger().warning("Не удалось завершить временную привилегию игрока " + grant.playerName() + ": " + error.getMessage());
                return;
            }
            synchronized (this) {
                Grant current = grants.get(grant.uuid());
                if (current != null && current.expiresAt() == grant.expiresAt()) {
                    grants.remove(grant.uuid());
                    save();
                }
            }
            plugin.notifyPrivilegeExpired(grant.uuid());
        }));
    }

    private void removeGroup(User user, String group) {
        for (Node node : new HashSet<>(user.getNodes())) {
            if (node instanceof InheritanceNode inheritance
                    && inheritance.getGroupName().equalsIgnoreCase(group)) {
                user.data().remove(node);
            }
        }
    }

    private boolean hasManagedPrivilege(User user) {
        Set<String> groups = managedGroups();
        return user.getNodes().stream().anyMatch(node -> node instanceof InheritanceNode inheritance
                && groups.contains(inheritance.getGroupName().toLowerCase(Locale.ROOT)));
    }

    private void addDefaultIfMissing(User user, String group) {
        boolean exists = user.getNodes().stream().anyMatch(node -> node instanceof InheritanceNode inheritance
                && inheritance.getGroupName().equalsIgnoreCase(group));
        if (!exists) user.data().add(InheritanceNode.builder(group).build());
    }

    private String defaultGroup() {
        return plugin.getConfig().getString("privileges.default-group", "player").toLowerCase(Locale.ROOT);
    }

    private Set<String> managedGroups() {
        Set<String> groups = new HashSet<>();
        ConfigurationSectionCompat.forEach(plugin.getConfig().getConfigurationSection("privileges.groups"), id -> {
            String group = plugin.getConfig().getString("privileges.groups." + id, id);
            if (group != null) groups.add(group.toLowerCase(Locale.ROOT));
        });
        return groups;
    }

    public synchronized List<Grant> getActiveGrants() {
        return grants.values().stream().filter(g -> g.expiresAt() > System.currentTimeMillis())
                .sorted(Comparator.comparingLong(Grant::expiresAt)).toList();
    }

    private synchronized void save() {
        yaml = new YamlConfiguration();
        for (Grant grant : grants.values()) {
            String path = grant.uuid().toString() + ".";
            yaml.set(path + "player-name", grant.playerName());
            yaml.set(path + "kit", grant.kitId());
            yaml.set(path + "group", grant.group());
            yaml.set(path + "expires-at", grant.expiresAt());
        }
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Не удалось сохранить privileges.yml: " + exception.getMessage());
        }
    }

    private static final class ConfigurationSectionCompat {
        private static void forEach(org.bukkit.configuration.ConfigurationSection section,
                                    java.util.function.Consumer<String> consumer) {
            if (section != null) section.getKeys(false).forEach(consumer);
        }
        private static void forEach(YamlConfiguration yaml, java.util.function.Consumer<String> consumer) {
            yaml.getKeys(false).forEach(consumer);
        }
    }
}
