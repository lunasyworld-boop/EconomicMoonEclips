package ru.mooneclips.economicmooneclips;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class GoldMobListener implements Listener {
    private static final Set<EntityType> EXCLUDED = EnumSet.of(
            EntityType.PHANTOM, EntityType.ENDERMAN, EntityType.ENDERMITE, EntityType.SILVERFISH
    );
    private final EconomicMoonEclips plugin;
    private final SellManager sellManager;
    private final NamespacedKey goldMobKey;

    public GoldMobListener(EconomicMoonEclips plugin, SellManager sellManager) {
        this.plugin = plugin;
        this.sellManager = sellManager;
        this.goldMobKey = new NamespacedKey(plugin, "gold_hostile_mob");
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof Monster mob) || EXCLUDED.contains(mob.getType())) return;
        double chance = plugin.getConfig().getDouble("settings.gold-mob-chance", 10.0);
        if (ThreadLocalRandom.current().nextDouble(100.0) >= chance) return;
        mob.getPersistentDataContainer().set(goldMobKey, PersistentDataType.BYTE, (byte) 1);
        mob.setGlowing(true);
        mob.setCustomName(ChatColor.GOLD + "✦ Золотой " + pretty(mob.getType().name()));
        mob.setCustomNameVisible(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity mob = event.getEntity();
        if (!mob.getPersistentDataContainer().has(goldMobKey, PersistentDataType.BYTE)) return;
        for (ItemStack drop : event.getDrops()) sellManager.markGoldDrop(drop);
        if (mob.getKiller() != null) {
            mob.getKiller().sendMessage(ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("theme.prefix", "&8[&5Moon&bEclips&8] ")
                            + "&6Золотой моб! Его разрешённый дроп продаётся по ×3."));
        }
    }

    private String pretty(String raw) {
        String[] words = raw.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }
}