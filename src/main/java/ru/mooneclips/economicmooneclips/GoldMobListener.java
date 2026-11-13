package ru.mooneclips.economicmooneclips;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Monster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class GoldMobListener implements Listener {
    private static final Set<EntityType> EXCLUDED = EnumSet.of(
            EntityType.PHANTOM, EntityType.ENDERMAN, EntityType.ENDERMITE, EntityType.SILVERFISH
    );
    private final EconomicMoonEclips plugin;
    private final SellManager sellManager;

    public GoldMobListener(EconomicMoonEclips plugin, SellManager sellManager) {
        this.plugin = plugin;
        this.sellManager = sellManager;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        org.bukkit.entity.LivingEntity entity = event.getEntity();
        if (!(entity instanceof Monster) || EXCLUDED.contains(entity.getType())) return;
        double chance = plugin.getConfig().getDouble("settings.gold-mob-chance", 10.0);
        if (ThreadLocalRandom.current().nextDouble(100.0) >= chance) return;
        for (ItemStack drop : event.getDrops()) sellManager.markGoldDrop(drop);
        org.bukkit.entity.Player killer = entity.getKiller();
        if (killer != null) {
            killer.sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("theme.prefix", "&8[&5Moon&bEclips&8] ") + "&6Золотой моб! Его разрешённый дроп продаётся по ×3."));
        }
    }
}