package ru.mooneclips.moonkits;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public final class KitGui implements Listener {
    private final MoonKitsPlugin plugin;
    private final KitManager manager;
    private final Map<UUID, String> pendingEdits = new HashMap<>();

    public KitGui(MoonKitsPlugin plugin, KitManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void openPlayerMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.PLAYER, null);
        Inventory inv = Bukkit.createInventory(holder, 54, color(plugin.getConfig().getString("settings.player-menu-title", "&5&lMoonKits")));
        holder.setInventory(inv);
        int slot = 0;
        for (Kit kit : manager.getKits()) {
            if (slot >= 54) break;
            boolean allowed = player.hasPermission(kit.getPermission());
            ItemStack icon = iconFor(kit);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(color((allowed ? "&a" : "&c") + kit.getDisplayName()));
            List<String> lore = new ArrayList<>();
            lore.add(color(allowed ? "&aДоступен" : "&cНет доступа"));
            long remaining = manager.getRemainingCooldown(player.getUniqueId(), kit);
            if (remaining > 0) lore.add(color("&7Перезарядка: &f" + KitManager.formatDuration(remaining)));
            else if (allowed) lore.add(color("&eНажми, чтобы получить"));
            meta.setLore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            holder.getSlots().put(slot, kit.getId());
            slot++;
        }
        player.openInventory(inv);
    }

    public void openAdminMenu(Player player) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.ADMIN, null);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&4&lMoonKits &8- &cКиты"));
        holder.setInventory(inv);
        int slot = 0;
        for (Kit kit : manager.getKits()) {
            if (slot >= 45) break;
            ItemStack icon = iconFor(kit);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(color("&e" + kit.getDisplayName()));
            meta.setLore(List.of(color("&7ID: &f" + kit.getId()), color("&aНажми для редактирования")));
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            holder.getSlots().put(slot, kit.getId());
            slot++;
        }
        inv.setItem(49, button(Material.EMERALD, "&aСоздать кит", "&7Команда: /mkit create <название>"));
        inv.setItem(53, button(Material.BARRIER, "&cЗакрыть"));
        player.openInventory(inv);
    }

    public void openEditor(Player player, Kit kit) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.EDITOR, kit.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, color("&5Редактор: &d" + kit.getDisplayName()));
        holder.setInventory(inv);
        List<ItemStack> items = kit.getItems();
        for (int i = 0; i < Math.min(45, items.size()); i++) {
            ItemStack item = items.get(i);
            if (item != null) inv.setItem(i, item.clone());
        }
        inv.setItem(45, button(Material.NAME_TAG, "&eНазвание", "&7Текущее: &f" + kit.getDisplayName()));
        inv.setItem(46, button(Material.TRIPWIRE_HOOK, "&bPermission", "&7" + kit.getPermission()));
        inv.setItem(47, button(Material.CLOCK, "&eПерезарядка", "&7Секунд: &f" + kit.getCooldownSeconds()));
        inv.setItem(48, button(kit.isAutomaticDelivery() ? Material.LIME_DYE : Material.GRAY_DYE,
                kit.isAutomaticDelivery() ? "&aАвтовыдача: ВКЛ" : "&cАвтовыдача: ВЫКЛ"));
        inv.setItem(49, button(Material.EMERALD, "&aСохранить"));
        inv.setItem(50, button(Material.TNT, "&cУдалить кит"));
        inv.setItem(53, button(Material.ARROW, "&7Назад"));
        player.openInventory(inv);
    }

    public void openPreview(Player player, Kit kit) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.PREVIEW, kit.getId());
        Inventory inv = Bukkit.createInventory(holder, 54, color("&7Предпросмотр: &f" + kit.getDisplayName()));
        holder.setInventory(inv);
        List<ItemStack> items = kit.getItems();
        for (int i = 0; i < Math.min(54, items.size()); i++) if (items.get(i) != null) inv.setItem(i, items.get(i).clone());
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (holder.getType() == MenuHolder.Type.PREVIEW) {
            event.setCancelled(true);
            return;
        }
        if (holder.getType() == MenuHolder.Type.PLAYER) {
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            event.setCancelled(true);
            String id = holder.getSlots().get(event.getRawSlot());
            if (id == null) return;
            Kit kit = manager.getKit(id);
            if (kit == null) return;
            if (!player.hasPermission(kit.getPermission())) {
                player.sendMessage(plugin.msg("no-kit-permission"));
                return;
            }
            if (manager.deliver(player, kit, false)) {
                player.sendMessage(plugin.msg("kit-received").replace("%kit%", kit.getDisplayName()));
                openPlayerMenu(player);
            }
            return;
        }
        if (!player.hasPermission("moonkits.admin")) {
            event.setCancelled(true);
            return;
        }
        if (holder.getType() == MenuHolder.Type.ADMIN) {
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            event.setCancelled(true);
            if (event.getRawSlot() == 53) { player.closeInventory(); return; }
            if (event.getRawSlot() == 49) {
                player.closeInventory();
                player.sendMessage(plugin.msg("editor-prompt") + color("&7 Используй /mkit create <название>."));
                return;
            }
            String id = holder.getSlots().get(event.getRawSlot());
            Kit kit = id == null ? null : manager.getKit(id);
            if (kit != null) openEditor(player, kit);
            return;
        }
        if (holder.getType() != MenuHolder.Type.EDITOR) return;
        int raw = event.getRawSlot();
        if (raw >= 0 && raw < 45) {
            event.setCancelled(true);
            ItemStack cursor = event.getCursor();
            ItemStack current = event.getView().getTopInventory().getItem(raw);
            if (cursor != null && !cursor.getType().isAir()) {
                event.getView().getTopInventory().setItem(raw, cursor.clone());
                event.setCursor(null);
            } else if (current != null && !current.getType().isAir()) {
                event.setCursor(current.clone());
                event.getView().getTopInventory().setItem(raw, null);
            }
            return;
        }
        if (raw >= 54) {
            if (event.isShiftClick() && event.getCurrentItem() != null && !event.getCurrentItem().getType().isAir()) {
                event.setCancelled(true);
                int empty = -1;
                for (int slot = 0; slot < 45; slot++) {
                    ItemStack existing = event.getView().getTopInventory().getItem(slot);
                    if (existing == null || existing.getType().isAir()) { empty = slot; break; }
                }
                if (empty >= 0) {
                    event.getView().getTopInventory().setItem(empty, event.getCurrentItem().clone());
                    event.setCurrentItem(null);
                }
            }
            return;
        }
        event.setCancelled(true);
        Kit kit = manager.getKit(holder.getKitId());
        if (kit == null) return;
        switch (raw) {
            case 45 -> prompt(player, kit, "name");
            case 46 -> prompt(player, kit, "permission");
            case 47 -> prompt(player, kit, "cooldown");
            case 48 -> {
                kit.setAutomaticDelivery(!kit.isAutomaticDelivery());
                openEditor(player, kit);
            }
            case 49 -> {
                copyEditorItems(event.getView().getTopInventory(), kit);
                manager.saveKit(kit);
                player.sendMessage(plugin.msg("kit-saved"));
                openEditor(player, kit);
            }
            case 50 -> {
                holder.setDeleted(true);
                manager.deleteKit(kit.getId());
                player.closeInventory();
                player.sendMessage(plugin.msg("kit-deleted").replace("%kit%", kit.getDisplayName()));
                openAdminMenu(player);
            }
            case 53 -> openAdminMenu(player);
            default -> { }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder holder
                && holder.getType() == MenuHolder.Type.EDITOR) {
            for (int raw : event.getRawSlots()) {
                if (raw < 45) { event.setCancelled(true); return; }
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) return;
        if (holder.getType() != MenuHolder.Type.EDITOR || holder.isDeleted()) return;
        Kit kit = manager.getKit(holder.getKitId());
        if (kit == null) return;
        copyEditorItems(event.getInventory(), kit);
        manager.saveKit(kit);
    }

    private void prompt(Player player, Kit kit, String field) {
        pendingEdits.put(player.getUniqueId(), kit.getId() + ":" + field);
        player.closeInventory();
        player.sendMessage(plugin.msg("editor-prompt"));
    }

    @EventHandler
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        String pending = pendingEdits.remove(event.getPlayer().getUniqueId());
        if (pending == null) return;
        event.setCancelled(true);
        String[] parts = pending.split(":", 2);
        String id = parts[0];
        String field = parts[1];
        String value = event.getMessage().trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Kit kit = manager.getKit(id);
            if (kit == null) return;
            if (value.equalsIgnoreCase("cancel")) {
                openEditor(player, kit);
                return;
            }
            switch (field) {
                case "name" -> kit.setDisplayName(value);
                case "permission" -> kit.setPermission(value);
                case "cooldown" -> {
                    try {
                        long seconds = Long.parseLong(value);
                        if (seconds < 0) throw new NumberFormatException();
                        kit.setCooldownSeconds(seconds);
                    } catch (NumberFormatException ex) {
                        player.sendMessage(plugin.msg("invalid-value"));
                        openEditor(player, kit);
                        return;
                    }
                }
            }
            manager.saveKit(kit);
            openEditor(player, kit);
        });
    }

    private void copyEditorItems(Inventory inventory, Kit kit) {
        for (int i = 0; i < 45; i++) kit.setItem(i, inventory.getItem(i));
    }

    private ItemStack iconFor(Kit kit) {
        for (ItemStack item : kit.getItems()) {
            if (item != null && !item.getType().isAir()) return item.clone();
        }
        return new ItemStack(Material.CHEST);
    }

    private ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(KitGui::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    private static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text); }
}