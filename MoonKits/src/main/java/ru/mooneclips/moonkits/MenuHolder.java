package ru.mooneclips.moonkits;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MenuHolder implements InventoryHolder {
    public enum Type { PLAYER, ADMIN, EDITOR, PREVIEW, SELECT_PLAYER, SELECT_PRIVILEGE, SELECT_DURATION, ACTIVE_GRANTS }
    private final Type type;
    private final String kitId;
    private final Map<Integer, String> slots = new HashMap<>();
    private final Map<Integer, UUID> playerSlots = new HashMap<>();
    private UUID targetUuid;
    private Inventory inventory;
    private boolean deleted;

    public MenuHolder(Type type, String kitId) {
        this.type = type;
        this.kitId = kitId;
    }
    public Type getType() { return type; }
    public String getKitId() { return kitId; }
    public Map<Integer, String> getSlots() { return slots; }
    public Map<Integer, UUID> getPlayerSlots() { return playerSlots; }
    public UUID getTargetUuid() { return targetUuid; }
    public void setTargetUuid(UUID targetUuid) { this.targetUuid = targetUuid; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
    @Override public Inventory getInventory() { return inventory; }
}
