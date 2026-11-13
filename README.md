# EconomicMoonEclips 1.7.0

Minecraft economy plugin for Paper 1.21.4 (Java 21).

## Dependencies
- Paper 1.21.4
- Vault 1.7.3
- EssentialsX Economy 2.22.x (Vault economy provider)
- LuckPerms 5.5.x (rank permissions)

## Build
Every push to `main` starts the GitHub Actions workflow. When the workflow succeeds, download the artifact named `EconomicMoonEclips-1.7.0` from the run page. The JAR is produced as `EconomicMoonEclips-1.7.0.jar`.

Manual build: `mvn clean package` with Java 21 and Maven installed.

## Configuration
The plugin creates these files in `plugins/EconomicMoonEclips/`:
- `config.yml` — theme, rank multipliers, auction slots and gold-mob settings
- `prices.yml` — explicitly allowed sale items and prices
- `messages.yml` — Russian messages
- `auction.yml` — auction settings

Only entries listed in `prices.yml` are sellable. Raw ores and resource blocks are intentionally excluded.

## Current implementation status
- Vault economy hookup and administrative money commands
- Sell GUI with configured categories and prices
- Confirmed `/sell all` flow
- LuckPerms rank multiplier permissions for normal sales
- 10% gold hostile mob spawn chance (excluding Phantom, Enderman, Endermite and Silverfish); approved drops sell for ×3 without rank multiplication
- `/top` reads balances through Vault

The fixed-price auction implementation and full integration/gameplay testing are still in progress. Do not treat this development build as a completed production release.
