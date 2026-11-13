# MoonKits 1.0.0

Privilege kits for Paper 1.21.4 / Java 21. Uses LuckPerms for permission checks and automatic delivery. It does not register or override EssentialsX `/kit` or `/kits`.

## Requirements
- Paper 1.21.4
- Java 21
- LuckPerms 5.5.x

## Player commands
- `/mkits` — open the kit GUI
- `/mkit` — open the kit GUI
- `/mkit <kit>` — claim a kit
- `/mkit info <kit>` — show kit settings

## Administrator commands
- `/mkitadmin` — open the kit administration GUI
- `/mkit create <id>` — create a kit and open its editor
- `/mkit edit <id>` — edit a kit
- `/mkit delete <id>` — delete a kit
- `/mkit give <player> <kit>` — give a kit to an online player
- `/mkit preview <id>` — preview kit contents
- `/mkit reload` — reload configuration and kits

The editor lets administrators move items into the top 45 slots, shift-click items from their inventory, change the display name, permission and cooldown, toggle automatic delivery, save, and delete kits. Closing the editor also saves its item contents.

## LuckPerms setup

Grant each permission to its matching group only:

```
/lp group eclipse permission set moonkits.kit.eclipse true
/lp group crystal permission set moonkits.kit.crystal true
/lp group diamond permission set moonkits.kit.diamond true
/lp group gold permission set moonkits.kit.gold true
/lp group silver permission set moonkits.kit.silver true
/lp group stone permission set moonkits.kit.stone true
```

The group names above are examples; use the exact group names configured on your server. The plugin checks the permission nodes, not group display names.

Administrators need `moonkits.admin`; reload and give also require `moonkits.reload` and `moonkits.give` respectively.

## Automatic delivery

When a player's effective LuckPerms permission changes from absent to present, the matching kit is automatically delivered once for that grant. If the permission is later removed and granted again, the kit can be delivered again. Delivery state and cooldowns are stored separately in `player-state.yml` and `cooldowns.yml`. A full inventory prevents delivery and leaves the automatic grant pending for a later recalculation or join.

## Build

`mvn --batch-mode clean package` with Java 21. The output is `target/MoonKits-1.0.0.jar`.

GitHub Actions builds the project when files under `MoonKits/` change on `main`, and uploads the JAR as the `MoonKits-1.0.0` artifact.
