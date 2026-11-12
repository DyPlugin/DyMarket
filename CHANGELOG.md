# DyMarket 1.0.0-BETA

The first public release of DyMarket: a multi-currency economy, a credits bridge for webstores, an auction house and DyClaim estate sale and rent in one light plugin for Bukkit, Spigot, Paper, Purpur and Folia, Minecraft 1.20.x through 26.x. This is a BETA: the economy data format may still change before 1.0.0, and the data file is backed up automatically on every start.

## Added

- **Economy core.**
  - Several currencies: `money` and `credits` by default, more can be added under `currencies`. Each has its own name, symbol, decimals, number format (`format-locale`), starting and maximum balance and transfer switch.
  - `/balance`, `/pay` and `/baltop`, with an optional currency argument. Amounts accept `1k` and `2.5m` and the currency's number format.
  - No operation can create a negative balance or exceed `max-balance`; such an operation fails as a whole. Transfers lock both accounts.
  - An append-only transaction log for every change. `/dymarket log <player>` shows it, `/dymarket rollback <id>` reverses one transaction (`force` stops at zero instead of refusing).
  - `/dymarket give`, `take` and `set` for administrators, and `/dymarket merge <from> <to>`, which moves every balance, listing, collection box item and lease from one account to another.
  - Rate limits for `/pay`, `/credits send` and auction purchases (`security.rate-limit`), a confirmation for large payments (`/pay ... confirm` at or above `pay.confirm-above`) and staff alerts for large transfers (`security.alert`) in game and in the console. DyDiscord is detected, but it has no staff-alert API yet, so DyMarket only logs that once and sends nothing to Discord.
- **Vault.** DyMarket serves the currency marked `vault: true` as the Vault economy, so DyHomes, DyRTP and other Vault plugins use it. With `provider: vault`, a currency uses another plugin's Vault economy instead.
- **Importers.** `/dymarket import essentials` copies EssentialsX balances, and `/dymarket import vault` copies balances from the economy registered in Vault. Nothing already non-zero in DyMarket is overwritten.
- **Credits bridge.**
  - `/dymarket give <player> <amount> credits <txid>` for webstores and RCON. The same transaction ID is applied only once; a repeat reports that it was already applied, changes nothing and exits without error.
  - `/credits send <player> <amount>` between players and `/credits exchange <amount>` from credits to money (`credits.exchange.rate`, 1000 money per credit by default).
  - A top-up bonus (`credits.topup-bonus-percent`) and optional top-up announcements (`credits.announce-topups`).
- **Auction house.**
  - `/ah` opens a chest GUI with sorting, your listings and your collection box; every action also has a command (`sell`, `list`, `search`, `info`, `buy`, `mine`, `cancel`, `collect`). With `auction.confirm-purchase` on, a purchase asks for confirmation: a window in the GUI, `/ah buy <id> confirm` on the command path.
  - Buy-it-now listings in any currency from `auction.allowed-currencies`, with expiry (`auction.duration-hours`, 48), tax (`auction.tax-percent`, 5.0), price bounds, an item blacklist and a lore filter. `auction.max-listings` (7) limits active listings per player; 0 means no limit.
  - The item leaves the seller's hand before the listing is saved, a listing can be bought only once, and a failed payment step is refunded. Items wait in the collection box with a reason: `EXPIRED`, `CANCELLED`, `REMOVED` (by staff), `FULL_INVENTORY` or `PURCHASE` (the buyer left before delivery).
  - Rank permissions for the listing limit (`dymarket.ah.limit.<n>`, highest wins) and tax (`dymarket.ah.tax.<percent>`, lowest wins), and `dymarket.bypass.tax`. The tax is fixed when the item is listed.
- **Estate (DyClaim 1.3.1 or later).**
  - `/estate sell` and `/estate rent` list the connected group of your claims around the chunk you stand in (up to `estate.max-chunks-per-listing`, 64), or only that chunk with `here`. There is no estate listing limit unless a player has `dymarket.estate.limit.<n>`. The tax (`estate.tax-percent`) is fixed when the claims are listed.
  - `/estate buy`, `/estate lease` and `/estate leave` always ask first and run with `confirm`; the GUI shows a confirmation window.
  - A sale transfers every claim to the buyer; if one transfer fails, the claims go back and the money is refunded.
  - Rent gives the tenant timed DyClaim trust with the rights in `estate.rent.rights` and closes the listing to other players while the lease runs. A new lease renews automatically; `/estate autorenew` turns that off and on. An unpaid lease ends after `estate.rent.grace-hours`. `/estate leave` (no refund) and `/estate evict` (at the end of the paid period) end a lease.
  - When the owner unlists a rented listing, or a claim in it is removed or transferred in DyClaim, the lease ends and the owner pays the tenant `estate.rent.refund-on-owner-cancel-percent` of the unused time.
  - `/estate list` opens a chest GUI; players who were offline get their estate and auction notices on their next join.
- **Languages.** English, Turkish, German, Spanish and Russian, from the client language or `/dymarket language`. Turkish command aliases and sub-command words. MiniMessage tags on Paper, Purpur and Folia; text typed by players is never treated as tags.
- **PlaceholderAPI.** `%dymarket_balance%`, `%dymarket_balance_<currency>%` and their `_formatted` forms, `%dymarket_credits%`, `%dymarket_baltop_<n>_name%` and `_amount` (also per currency), `%dymarket_ah_listings%`, `%dymarket_ah_mine%` and `%dymarket_estate_listings%`.
- **Developer API.** `DyMarketAPI` with asynchronous (`deposit`, `withdraw`, `transfer`) and synchronous (`depositSync`, `withdrawSync`, `transferSync`) balance changes, market snapshots, the cancellable `BalanceChangeEvent` and auction and estate events.
- **Folia.** The same JAR runs on Folia with region, entity and global schedulers.
- **Storage.** One SQLite file, `plugins/DyMarket/data.db` (schema 1), written on one background thread, with a backup copy on every start (the last seven are kept).
- **Updates and statistics.** A Modrinth update check every six hours (`updates.check`) and anonymous bStats statistics (`metrics.enabled`).

## Changed

- Nothing; this is the first release.

## Fixed

- Nothing; this is the first release.

## Upgrading

Put `DyMarket-1.0.0-BETA.jar` in `plugins/` and start the server. Coming from another economy plugin, run `/dymarket import essentials`, or `/dymarket import vault` while that plugin is still the registered Vault economy, once before removing it. The estate module needs DyClaim 1.3.1 or later and turns itself off with one console line otherwise. Because this is a BETA, the data format may change before 1.0.0; later versions convert `data.db` automatically, and a backup is made on every start.

Full changelog: https://dynastyplugins.com/dymarket/versions/1.0.0-BETA
