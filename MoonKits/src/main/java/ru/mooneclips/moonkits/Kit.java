package ru.mooneclips.moonkits;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class Kit {
    private final String id;
    private String displayName;
    private String permission;
    private long cooldownSeconds;
    private boolean automaticDelivery;
    private final List<ItemStack> items;

    public Kit(String id, String displayName, String permission, long cooldownSeconds,
               boolean automaticDelivery, List<ItemStack> items) {
        this.id = id;
        this.displayName = displayName;
        this.permission = permission;
        this.cooldownSeconds = cooldownSeconds;
        this.automaticDelivery = automaticDelivery;
        this.items = new ArrayList<>();
        if (items != null) {
            for (ItemStack item : items) this.items.add(item == null || item.getType().isAir() ? null : item.clone());
        }
        trim();
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public long getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(long cooldownSeconds) { this.cooldownSeconds = Math.max(0, cooldownSeconds); }
    public boolean isAutomaticDelivery() { return automaticDelivery; }
    public void setAutomaticDelivery(boolean automaticDelivery) { this.automaticDelivery = automaticDelivery; }

    public List<ItemStack> getItems() {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack item : items) copy.add(item == null ? null : item.clone());
        return copy;
    }

    public List<ItemStack> getNonEmptyItems() {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack item : items) if (item != null && !item.getType().isAir()) copy.add(item.clone());
        return copy;
    }

    public void setItem(int slot, ItemStack item) {
        while (items.size() <= slot) items.add(null);
        items.set(slot, item == null || item.getType().isAir() ? null : item.clone());
        trim();
    }

    public ItemStack getItem(int slot) {
        if (slot < 0 || slot >= items.size()) return null;
        ItemStack item = items.get(slot);
        return item == null ? null : item.clone();
    }

    public int getItemSlots() { return items.size(); }

    private void trim() {
        while (!items.isEmpty() && items.get(items.size() - 1) == null) items.remove(items.size() - 1);
    }
}