<p align="center"><img src="https://raw.githubusercontent.com/DyPlugin/DyMarket/main/docs/images/logo.png" alt="DyMarket logo" width="128"></p>

# DyMarket — Economy, Auctions and Estate Made Simple

**Keep every coin accounted for. Trade items in one auction house. Rent and sell your land.**

DyMarket is one light economy plugin for survival, SMP, Towny/Earth and Skyblock servers. It keeps balances in several currencies, delivers web-store credits safely, runs an auction house and lets players sell or rent their DyClaim land, with one SQLite file, no database server and one JAR for every server build.

DyMarket combines a multi-currency economy with a Vault provider, a transaction log with rollback, an idempotent credits bridge for webstores and RCON, a buy-it-now auction house with a chest GUI and a full command path, and estate sale and rent on top of DyClaim. **Version 1.0.0-BETA** is the first public release; BETA means the economy data format may still change before 1.0.0, and DyMarket backs up its data file automatically on every start.

---

## Why DyMarket?

**Several currencies, one plugin.** `money` and `credits` come ready, and more can be added in `config.yml`. Each currency has its own name, symbol, decimals, number format, starting balance, maximum balance and transfer switch. One currency is served to other plugins through Vault, so DyHomes, DyRTP and any Vault plugin charge their prices in it.

**Money that cannot go missing.** No operation can create a negative balance or go over `max-balance`; such an operation fails as a whole. Transfers between two players lock both accounts, and every change is written to an append-only transaction log that `/dymarket log` shows and `/dymarket rollback` reverses.

**Credits from your webstore.** Tebex, RCON scripts and web panels deliver credits with one console command that carries the store's transaction ID. The same ID is never paid twice: a repeat returns "already applied", changes nothing and exits without error, so scripts do not retry. Players send credits to each other, exchange them for money and can get a top-up bonus.

**An auction house that does not duplicate items.** `/ah` opens a chest GUI; every action also has a command. The item leaves the seller's hand before the listing is saved, a listing can only be bought once, and if a payment step fails the buyer is refunded. Expired, cancelled and undeliverable items wait in a collection box.

**Land that earns rent.** With DyClaim 1.3.1 or later, `/estate` sells or rents the connected claims you stand in. A sale transfers every claim to the buyer; rent gives the tenant timed DyClaim trust, renews automatically (on by default) and ends after a grace period when the rent is not paid.

**Safe against abuse.** Rate limits on `/pay`, `/credits send` and auction purchases, confirmations for large payments and purchases, and an alert to staff for transfers at or above `security.alert.transfer-above`.

**Easy switch.** `/dymarket import essentials` copies EssentialsX balances, and `/dymarket import vault` copies balances from the economy currently registered in Vault. Nothing that is already non-zero in DyMarket is overwritten.

**One JAR for every server.** Bukkit, Spigot, Paper, Purpur and Folia. On Folia, items are taken and delivered on each player's own thread, and all disk work runs on one background thread, never on the main or a region thread.

**Five languages.** English, Turkish, German, Spanish and Russian, picked from the player's Minecraft language or their own choice. Messages accept `&` colour codes everywhere and MiniMessage tags on Paper, Purpur and Folia; names and text typed by players are never read as tags.

Planned for later releases: Bedrock forms, bidding auctions and buy orders, MySQL and cross-server support. Until Bedrock forms arrive, Bedrock players use the command path, which covers every GUI action.

---

## Choose your server build

| Server | JAR |
| --- | --- |
| Bukkit, Spigot, Paper, Purpur, Folia | `DyMarket-1.0.0-BETA.jar` |

Java 17 bytecode; use the Java runtime your server needs. Release listing: Minecraft 1.20.x through 26.x. Optional: Vault (to serve or use a Vault economy), DyClaim 1.3.1 or later (estate), PlaceholderAPI and DyDiscord (detected; it has no staff-alert API yet). The SQLite driver comes with the server; DyMarket bundles no libraries.

[Downloads](https://github.com/DyPlugin/DyMarket/releases/tag/1.0.0-BETA) · [Changelog](https://github.com/DyPlugin/DyMarket/blob/main/CHANGELOG.md)

## Commands

| Command | Purpose |
|---|---|
| `/balance [player] [currency]` | Show your balance, or another player's |
| `/pay <player> <amount> [currency] [confirm]` | Send money to a player |
| `/baltop [currency] [page]` | Top balances |
| `/credits` | Show your credits |
| `/credits send <player> <amount>` | Send credits to a player |
| `/credits exchange <amount>` | Exchange credits for money |
| `/ah` | Open the auction house |
| `/ah sell <price> [currency] [amount]` | List the item in your main hand |
| `/ah list [page]` | Browse the active listings |
| `/ah search <text>` | Search the listings |
| `/ah info <id>` | Show one listing |
| `/ah buy <id> [confirm]` | Buy a listing |
| `/ah mine` | Your listings; cancel from here |
| `/ah cancel <id>` | Cancel one of your listings |
| `/ah collect` | Open your collection box |
| `/estate sell <price> [currency] [here]` | Put the connected claims you stand in up for sale |
| `/estate rent <price> <days> [currency] [here]` | Offer them for rent, priced per period of `<days>` |
| `/estate unlist [id]` | Take your listing down |
| `/estate list [page]` | Browse estate listings |
| `/estate info [id]` | Show a listing, or the one you stand in |
| `/estate buy [id] [confirm]` | Buy a sale listing |
| `/estate lease [id] [confirm]` | Rent a rent listing |
| `/estate autorenew [id]` | Turn automatic renewal of your lease on or off |
| `/estate leave [id] [confirm]` | End your lease now, without a refund |
| `/estate evict [id]` | End your tenant's lease when the paid period ends |
| `/estate mine` | Your listings and leases |
| `/dymarket help` | Show the commands you may use |
| `/dymarket language <auto/en/tr/de/es/ru>` | Save your language; from the console, set the server language |
| `/dymarket give <player> <amount> [currency] [txid]` | Add to a balance; with a `txid`, at most once |
| `/dymarket take <player> <amount> [currency]` | Take from a balance |
| `/dymarket set <player> <amount> [currency]` | Set a balance |
| `/dymarket log <player> [page]` | Show a player's transactions |
| `/dymarket rollback <id> [force]` | Reverse a transaction |
| `/dymarket merge <from> <to>` | Merge two accounts |
| `/dymarket import <essentials/vault>` | Copy balances from EssentialsX or the current Vault economy |
| `/dymarket ah remove <id>` | Remove any auction listing |
| `/dymarket ah purge <player>` | Remove every auction listing of a player |
| `/dymarket estate unlist <id>` | Take down any estate listing |
| `/dymarket reload` | Validate and reload settings and messages |

`/bal` and `/money` also open `/balance`, `/balancetop` is `/baltop`, `/credit` is `/credits`, `/auction` and `/auctionhouse` are `/ah`, `/realestate` is `/estate`, and `/dym` and `/dyma` are short for `/dymarket`; `/dymarket` alone shows the help, and `/dymarket auction` is the same as `/dymarket ah`. Amounts accept `1k` and `2.5m`, and the currency's number format, such as `1,250.50` under `en-US` or `1.250,50` under `tr-TR`.

- `/pay` asks for `confirm` at or above `pay.confirm-above`, and `/ah buy` asks for it when `auction.confirm-purchase` is on. `/estate buy`, `/estate lease` and `/estate leave` always ask first. Each prints the exact command to repeat with `confirm`; in the GUIs a confirmation window does the same.
- `/estate sell` and `/estate rent` take `here` to list only the chunk you stand in. Without an `id`, `/estate unlist`, `info`, `buy` and `lease` use the listing you stand in; `autorenew`, `leave` and `evict` use the lease you stand in, or your only lease.
- `/dymarket rollback` takes the transaction ID shown by `/dymarket log`. It refuses when the reversal would make a balance negative; `force` stops at zero instead.

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `dymarket.balance` | true | `/balance` |
| `dymarket.balance.others` | op | Other players' balances |
| `dymarket.pay` | true | `/pay` |
| `dymarket.baltop` | true | `/baltop` |
| `dymarket.baltop.hidden` | false | Leave this player out of `/baltop` |
| `dymarket.credits` | true | `/credits` |
| `dymarket.credits.send` | true | `/credits send` |
| `dymarket.credits.exchange` | true | `/credits exchange` |
| `dymarket.ah` | true | Browse and buy in the auction house, manage your listings |
| `dymarket.ah.sell` | true | `/ah sell` |
| `dymarket.estate` | true | Browse, buy and rent estate |
| `dymarket.estate.sell` | true | `/estate sell` |
| `dymarket.estate.rent` | true | `/estate rent` |
| `dymarket.admin` | op | `/dymarket reload`, the server language from the console, and the administrator help lines |
| `dymarket.admin.economy` | op | `give`, `take`, `set`, `log`, `rollback`, `merge`, `import` |
| `dymarket.admin.ah` | op | Cancel or remove any listing, purge a player's listings |
| `dymarket.admin.estate` | op | Take down any estate listing |
| `dymarket.admin.alerts` | op | Suspicious-transfer alerts and update notices |
| `dymarket.ah.limit.<n>` | not set | Active auction listings; the highest wins over `auction.max-listings` |
| `dymarket.ah.limit.unlimited` | op | No auction listing limit |
| `dymarket.estate.limit.<n>` | not set | Active estate listings; the highest wins. Without one there is no estate limit |
| `dymarket.estate.limit.unlimited` | op | No estate listing limit |
| `dymarket.ah.tax.<percent>` | not set | Auction tax; the lowest wins over `auction.tax-percent` |
| `dymarket.bypass.ratelimit` | op | Skip the rate limits on `/pay`, `/credits send` and auction purchases |
| `dymarket.bypass.tax` | false | No auction or estate tax on what this player lists |

`baltop.exclude-permission` names the permission that hides players from `/baltop` (`dymarket.baltop.hidden` by default).

## Placeholders

With PlaceholderAPI:

| Placeholder | Shows |
|---|---|
| `%dymarket_balance%` | Balance in the Vault currency, as a plain number |
| `%dymarket_balance_formatted%` | The same, formatted with the currency symbol |
| `%dymarket_balance_<currency>%` | Balance in any currency, as a plain number |
| `%dymarket_balance_<currency>_formatted%` | The same, formatted |
| `%dymarket_credits%` | Credits, formatted |
| `%dymarket_baltop_<n>_name%` | Name at rank `<n>` of the Vault currency's top list |
| `%dymarket_baltop_<n>_amount%` | Formatted balance at that rank |
| `%dymarket_baltop_<currency>_<n>_name%` | Name at rank `<n>` of another currency's top list |
| `%dymarket_baltop_<currency>_<n>_amount%` | Formatted balance at that rank |
| `%dymarket_ah_listings%` | Active auction listings |
| `%dymarket_ah_mine%` | The player's active auction listings |
| `%dymarket_estate_listings%` | Estate listings open for sale or rent |

Placeholders are answered from memory: balances of players whose account is loaded, and the top list as of its last refresh (`baltop.refresh-seconds`). The market counts are `0` while their module is off.

## Configuration highlights

Every feature has its own switch in `config.yml`. Missing settings are added on start and reload; an invalid value rejects the reload and keeps the running settings.

**Currencies.** Each entry under `currencies` is one currency. `vault: true` marks the one currency served through Vault (exactly one may have it). `provider: internal` keeps the balances in DyMarket; `provider: vault` uses another plugin's Vault economy for that currency instead, and DyMarket then does not register its own.

```yaml
currencies:
  money:
    enabled: true
    name: Money
    plural: Money
    symbol: "$"
    symbol-after: false
    decimals: 2
    format-locale: en-US   # tr-TR gives 1.250,50
    starting-balance: 100.0
    max-balance: 1000000000.0
    transferable: true
    vault: true
    provider: internal
```

**Credits bridge.** Let your webstore or panel run this console command when a purchase completes. It works over RCON and with Tebex, LeaderOS and any store that runs a command with placeholders:

```text
dymarket give {player} {amount} credits {transaction_id}
```

The transaction ID makes the delivery idempotent: if the store sends the same purchase again, DyMarket answers that it was already applied and changes nothing. `credits.topup-bonus-percent` adds extra credits to each delivery with an ID, and `credits.announce-topups` announces them. `credits.exchange.rate` sets how much money one credit buys (1000 by default), `credits.exchange.min` the smallest exchange.

**Payments and security.** `pay.min-amount`, and `pay.confirm-above` asks for a confirmation at or above an amount (0 never asks). `security.rate-limit.pay-per-minute` (10, shared by `/pay` and `/credits send`) and `security.rate-limit.ah-buy-per-minute` (20); 0 turns a limit off. Payments, Vault payments, auction sales and estate sales and rent at or above `security.alert.transfer-above` are shown in the console and to online players with `security.alert.notify-permission`. `security.alert.discord` is reserved for DyDiscord: DyDiscord has no staff-alert API yet, so DyMarket logs one line at start and the alerts stay in game and in the console.

**Auction.** `auction.duration-hours` (48), `auction.tax-percent` (5.0; tax leaves the economy), `auction.min-price` and `auction.max-price`, `auction.max-listings` (7; 0 means no limit; `dymarket.ah.limit.<n>` overrides it), `auction.currency` and `auction.allowed-currencies`, `auction.confirm-purchase`, `auction.broadcast-sales`, an item `auction.blacklist`, `auction.block-items-with-lore-containing`, `auction.gui.rows` and `auction.gui.sort` (`newest`, `price-asc` or `price-desc`). The tax is fixed when the item is listed, from `auction.tax-percent` or the seller's lowest `dymarket.ah.tax.<n>`, so a later change does not touch running listings. The collection box keeps items with a reason: `EXPIRED`, `CANCELLED`, `REMOVED` (by staff), `FULL_INVENTORY` (the buyer's inventory was full) or `PURCHASE` (the buyer left before delivery).

**Estate.** Needs DyClaim 1.3.1 or later; with an older DyClaim or none, the estate module turns itself off with one console line and the rest of DyMarket keeps working. A listing covers the connected group of your claims around the chunk you stand in, up to `estate.max-chunks-per-listing` (64) chunks. There is no estate listing limit unless a player has `dymarket.estate.limit.<n>`. The estate tax comes from `estate.tax-percent` (only `dymarket.bypass.tax` changes it) and is fixed when the claims are listed.

```yaml
estate:
  enabled: true
  currency: money
  allowed-currencies: [money, credits]
  max-chunks-per-listing: 64
  tax-percent: 5.0
  sale:
    enabled: true
    min-price: 1.0
    max-price: 1000000000.0
  rent:
    enabled: true
    min-days: 1
    max-days: 90
    rights: [build, containers, doors, trapdoors, redstone, entities, teleport]
    grace-hours: 24
    refund-on-owner-cancel-percent: 100
    check-interval-minutes: 10
  listing-expires-days: 30   # 0 = never
```

Renting a listing closes it to other players for as long as the lease runs. A new lease starts with automatic renewal on; `/estate autorenew` turns it off and on. When a lease is due and renewal is on, the next period is charged; if that fails, or renewal is off, the lease enters a grace period of `grace-hours`, after which the trust is removed and the lease ends. Both sides are told, now or on their next join. When the owner takes a rented listing down, or a claim in it is removed or transferred in DyClaim, the lease ends and the owner pays the tenant `refund-on-owner-cancel-percent` of the unused time; an owner who cannot pay that refund cannot unlist, and a staff removal pays what the owner has. DyClaim's own market can stay on next to DyMarket; they are independent, and DyMarket is the richer option.

## For developers

`dev.dymarket.api.DyMarketAPI` reads and changes balances in major units (`BigDecimal`). `getBalance(UUID, currency)` returns the balance. `deposit`, `withdraw` and `transfer` run on DyMarket's IO thread and return a `CompletableFuture<TransactionResult>`; `depositSync`, `withdrawSync` and `transferSync` (also named `trySyncDeposit`, `trySyncWithdraw` and `trySyncTransfer`) answer on the calling thread. Each change takes a note for the transaction log. `getCurrencies()`, `getAuctionListings()`, `getEstateListings()` and `getLeases(UUID)` return snapshots from memory, safe on any thread.

Events: `BalanceChangeEvent` (cancellable, fired before a change on the thread that asked for it), `AuctionListEvent`, `AuctionPurchaseEvent` and `AuctionCancelEvent` (cancellable), `AuctionExpireEvent` (after the fact, on the IO thread), `EstateListEvent`, `EstateSaleEvent` and `EstateLeaseStartEvent` (cancellable) and `EstateLeaseEndEvent` (after the fact, with the reason). Add `softdepend: [DyMarket]` and check `DyMarketAPI.isAvailable()` first. Other plugins can also use DyMarket through the Vault economy API.

A synchronous call or a Vault call for an offline player whose account is not in memory waits for the IO thread to load it, at most 10 seconds. This is rare, because online players and recently used accounts (up to 10,000) stay in memory.

## Data and upgrades

Everything is stored in one SQLite file, `plugins/DyMarket/data.db` (`storage.file`), with these tables: `accounts`, `transactions`, `auction_listings`, `collect_box`, `estate_listings`, `estate_leases`, `players` (names and language choices), `notices` (messages waiting for offline players) and `meta` (the schema version). On every start, before the file is opened, DyMarket copies it to `plugins/DyMarket/backups/data-<date>.db` and keeps the last seven copies. Balances are kept in memory and written in the background every `storage.flush-interval-ticks`; everything still waiting is written when the server stops. Disk work never runs on the main or a region thread. If the server has no SQLite driver, or the file cannot be opened, DyMarket says so in one console line and turns itself off.

This is a BETA release: the economy data format may still change before 1.0.0. The current file is schema 1; columns added since the first build (the fixed tax of auction and estate listings and leases, the lease owner's name and the eviction flag) are added in place on start. Later versions convert the file automatically, and a file written by a newer DyMarket is refused rather than changed.

Missing settings and message keys are added on start and reload without touching your edits. DyMarket checks Modrinth every six hours for a newer release and tells players with `dymarket.admin.alerts`; `updates.check: false` turns this off. Anonymous [bStats](https://bstats.org/plugin/bukkit/DyMarket/35262) statistics can be turned off with `metrics.enabled: false`.

## Build

```text
gradle clean build
```

Gradle 9.1 with Java 21 or 25. The JAR is `build/libs/DyMarket-1.0.0-BETA.jar`. The 92 tests use JUnit and Mockito and are not part of the JAR.

## License

DyMarket Source-Available License 1.0, the same terms as DyClaim, DyRTP, DyHomes and DyDiscord: free to use and modify on any server, no paid distribution, and distributed versions must share their source. It is a custom source-available license, not an OSI-approved open-source license. See [LICENSE](https://github.com/DyPlugin/DyMarket/blob/main/LICENSE).

## Get started

1. Put `DyMarket-1.0.0-BETA.jar` in `plugins/` (with Vault, and DyClaim 1.3.1 or later for estate) and start the server.
2. Coming from EssentialsX or another Vault economy? Run `/dymarket import essentials` or `/dymarket import vault` once.
3. Point your webstore's delivery command at `dymarket give {player} {amount} credits {transaction_id}`.
4. Adjust `config.yml` (currencies, auction tax, estate rent) and run `/dymarket reload`.

## Source and support

[Documentation](https://github.com/DyPlugin/DyMarket#readme) · [Report an issue](https://github.com/DyPlugin/DyMarket/issues) · [License](https://github.com/DyPlugin/DyMarket/blob/main/LICENSE)

**By Claww / DyPlugin.**
