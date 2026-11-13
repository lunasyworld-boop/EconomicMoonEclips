package ru.mooneclips.moonkits;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public final class MenuHolder implements InventoryHolder {
    public enum Type { PLAYER, ADMIN, EDITOR, PREVIEW }
    private final Type type;
    private final String kitId;
    private final Map<Integer, String> slots = new HashMap<>();
    private Inventory inventory;
    private boolean deleted;

    public MenuHolder(Type type, String kitId) {
        this.type = type;
        this.kitId = kitId;
    }
    public Type getType() { return type; }
    public String getKitId() { return kitId; }
    public Map<Integer, String> getSlots() { return slots; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}