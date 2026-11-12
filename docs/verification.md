# Verification record: 1.0.0-BETA

Checked on 2026-11-12 before the first release.

## Automated checks

`gradle clean build` with Gradle 9.1.0 passed **92 tests, zero failures**. They cover the economy core (deposit, withdraw, transfer, maximum balance, concurrent transfers, idempotent give, rollback, merge), currency formatting and parsing (en-US, tr-TR, suffixes), the credits bridge, the auction house state machine and its storage, the estate service (flood fill, sale compensation, lease timeline) and its storage, SQLite storage, the config validator, command words and the message files.

Java release 17.

## Live checks

Candidate: `DyMarket-1.0.0-BETA.jar`, 418924 bytes, SHA-256 `020F786603602770FC62AB052AE8F2F8C6EE8DB56FF52B346F3BCB1B5DA80175`. The same file was used on both servers below.

The rig is the family's five-plugin suite, `DyClaim\.local\suite-150\suite.cjs` (a copy of `suite-140` extended with DyMarket). Each server was disposable, loopback-only and driven by real offline Mineflayer clients (1.20.4 protocol). bStats and the update check were turned off. Only `Staff` was a server operator; `Seller`, `Buyer`, `Tenant` and `Alt` were ordinary players. Every scenario asserted its outcome through chat, the console, the SQLite file, the client's inventory, the opened window or the world.

Plugins on both servers: DyMarket 1.0.0-BETA, DyClaim 1.3.1 (with the write API), DyHomes 1.2.0, DyRTP 1.4.0, DyDiscord 1.4.0, PlaceholderAPI 2.12.3, and Vault. Paper also had ViaVersion/ViaBackwards 5.12.2; Folia also had LuckPerms 5.5.71.

Test settings: `estate.rent.grace-hours: 0`, `estate.rent.check-interval-minutes: 1`; DyHomes `economy.enabled: true` with a `/sethome` price of 10; DyClaim economy off (free claims).

The run has two server starts. Between them the suite edits `data.db` so that one auction listing and the running lease are already past their end, then watches the expiry sweep and the rent check on the second start.

### Scenarios

**Start-up**
- DyMarket enables with 2 currencies (and reports Folia on Folia).
- It logs `Registered as the Vault economy (money)`.
- The auction and estate modules load; the estate module finds DyClaim 1.3.1.
- The PlaceholderAPI expansion registers.
- There are no DyMarket warnings or enable errors.

**Economy**
- `/balance` shows both currencies with the starting balance.
- `/pay Buyer 1k` sends 1,000 and the receiver is told.
- With `format-locale: tr-TR` (applied by `/dymarket reload`), `/pay Buyer 1.250,50` sends 1,250.50 and is shown as `$1.250,50`; `2,5` is 2.50.
- Both balances are exact afterwards.
- Paying yourself and paying more than you have are both refused.
- `/baltop` lists the richest player with the correct amount.

**Credits**
- `/dymarket give Seller 5 credits TX1` delivers 5 credits.
- Sending the same txid again answers "already delivered; nothing changed", and the balance stays 5.
- `/credits` shows the credits.
- `/credits exchange 2` turns 2 credits into 2,000 money.

**Auction house**
- `/ah sell 100 money 1` lists one diamond and takes it from the hand.
- `/ah buy <id>` asks for confirmation; `/ah buy <id> confirm` delivers the diamond to the buyer.
- The seller is told and receives 95. The buyer pays 100, so the 5% tax leaves the economy.
- A sold listing cannot be bought again.
- `/ah` opens the "Auction House" window (the inventory title is checked).
- `/ah cancel <id>` gives the item back.
- After the restart, the sweep expires the back-dated listing and tells the seller. `/ah collect` returns the item.

**Estate sale**
- The seller claims 3 adjacent chunks with DyClaim and runs `/estate sell 500`, which lists the connected group of 3 chunks.
- The buyer stands in the claim and runs `/estate buy confirm`.
- `/claim info` shows the buyer as the DyClaim owner of all 3 chunks.
- Balances change by +475 for the seller and -500 for the buyer.

**Estate rent**
- The new owner runs `/estate rent 50 1`.
- Before renting, the tenant cannot place a block there.
- After `/estate lease confirm` the tenant has paid 50 and can place blocks in two different chunks of the group.
- The tenant is set to 0 money, and the lease's `paid_until` is moved into the past in SQLite.
- On the second start the rent check cannot charge the tenant, so with grace 0 the lease ends and the tenant is told.
- Block placement is denied again, so the DyClaim trust was revoked, and the lease is stored as ended.

**Admin**
- `/dymarket merge Alt Seller` moves Alt's 130 (starting balance plus a give) into Seller and leaves Alt at 0.
- `/dymarket rollback <txid>` reverses a give, and a second rollback is refused.
- `/dymarket reload` with `auction.tax-percent: 150` is refused and names the key. A `pay.min-amount: 5000` set in the same edit is not applied, so the old settings stay. The fixed file reloads.
- `/dymarket log` shows the MERGE and ROLLBACK entries.

**Vault**
- DyHomes `/sethome` with a price of 10 is charged through DyMarket's Vault provider.
- The charge appears in `/dymarket log` as `VAULT`.

**PlaceholderAPI**
- `papi parse Seller %dymarket_balance%` returns the exact balance.
- `%dymarket_balance_formatted%` returns a formatted amount.

**Log scan**
- No stack traces from any of the five Dy plugins.
- No thread-ownership errors (`Thread failed main thread check`, off-region access).
- No DyMarket warnings except the deliberate rejected reload.

The suite also re-ran every DyHomes, DyRTP, DyClaim and DyDiscord scenario from `suite-140`, so the existing plugins were checked again with DyMarket as the Vault economy.

### Paper 26.2

Paper build 129 (`26.2-129`), Java 25. Vault 1.7.3.

- **DyMarket checks: 69/69 passed** (start-up, the DyMarket sections and the log scan).
- **Whole suite: 119 passed, 0 failed, 1 skipped.** The skipped check is DyClaim group trust, because the suite does not install LuckPerms on Paper.

### Folia 1.20.4

Folia build 31 (`git-Folia-31`, MC 1.20.4), Java 21. Vault 1.7.3 does not declare `folia-supported`, so Folia does not load it. The rig therefore uses `vault-folia-stub/Vault-folia-stub.jar`: the VaultAPI 1.7.1 classes plus an empty plugin class, which is all DyMarket and DyHomes need to find each other in the services manager.

The chunks used for estate and auction were far from the spawn, so commands, claim changes and trust changes crossed region threads.

- **DyMarket checks: 70/70 passed** (one more than Paper because Folia detection is also checked).
- **Whole suite: 120 passed, 0 failed, 1 skipped.** The skipped check is the DyClaim display-border entity count, because the Folia console cannot count entities.
- The server log has **zero** `Thread failed main thread check`, `IllegalStateException` or region-ownership errors.

## Bugs fixed

None. Every DyMarket scenario passed on the first run on both platforms. The only failure during development was in the rig itself: the DyClaim `/claim info` output was longer than the window the suite read. The DyMarket source and JAR were not changed.

## Observations

- DyHomes logs `Prices are enabled, but Vault or an economy provider is missing` once at start-up. DyHomes enables before DyMarket has registered its Vault provider. DyHomes picks the provider up through the service-register event, and the paid `/sethome` worked. Adding `loadbefore: [DyHomes, DyRTP]` to DyMarket's `plugin.yml` would silence the warning; this was not changed because §3 of the spec fixes `plugin.yml`.

## Not covered

The following were not tested:

- Spigot and Purpur (the release lists them).
- Every Minecraft version between 1.20.4 and 26.3, and Folia 26.x.
- Real Vault on Folia (the rig uses the API stub).
- DyMarket with `provider: vault` on top of another economy plugin.
- The EssentialsX and Vault importers.
- DyDiscord staff alerts (no Discord bot is connected in the rig).
- The Turkish command aliases and the Turkish client language.
- Auction purchases through the window click path (purchases used the command path).
- The estate GUI.
- High player counts.
