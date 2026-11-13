# EconomicMoonEclips 1.7.0

Standalone Minecraft economy plugin for Paper 1.21.4 / Java 21.

## Dependencies
- Paper 1.21.4
- Vault 1.7.3
- EssentialsX Economy 2.22.x
- LuckPerms 5.5.x

## Build and download
Every push to `main` starts GitHub Actions. Open the repository's **Actions** tab, select the latest **Build plugin** run, and download the artifact `EconomicMoonEclips-1.7.0` after it succeeds. The JAR filename is `EconomicMoonEclips-1.7.0.jar`.

Manual build: `mvn clean package` with Java 21 and Maven.

## Configuration
On first start the plugin creates `plugins/EconomicMoonEclips/` with:
- `config.yml` — theme, rank multipliers, auction slots and gold-mob settings
- `prices.yml` — exact allowlist and prices for /sell
- `messages.yml` — Russian message strings
- `auction.yml` — duration, commission, categories and SQLite filename
- `auction.db` — persistent auction listings and item return mail

Only entries in `prices.yml` can be sold. Raw ores and resource blocks are excluded.

## Implemented development features
- Vault economy hookup (EssentialsX Economy provider)
- `/sell` GUI with resources, undead, farm and empty miscellaneous category
- `/sell all` with a timed confirmation and Vault payout result check
- LuckPerms rank permissions and sale multipliers
- 10% gold hostile mob spawn chance; excludes Phantom, Enderman, Endermite and Silverfish; approved drops pay ×3 without rank multiplier
- `/ah` fixed-price listings, rank slot limits, category filters, search command, price sorting, own-listing withdrawal, expiry return mail and SQLite persistence
- `/top`, `/pmoney`, `/epmoney`

## Release status
This is still a development build. A successful Maven build only confirms compilation; the auction's restart/disconnect and transaction edge cases, economy integration, GUI behavior and all gameplay flows must still be tested on a Paper 1.21.4 test server before production use.


## Version 1.8.0
- Removed golden mob effects and bonus mob drops.
- Added persistent random trader with independent buy/sell stock refreshes every two hours.
- Fixed auction GUI handling and added SQLite indexes; cached top balances for 30 seconds.
