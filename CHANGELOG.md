# DyMarket 1.1.0-BETA

DyMarket now looks and works like the rest of the Dy family: the same prefix style, help in pages, every staff command under one admin menu, messages that tell players what to type, a classic auction house and adjustable cooldowns. Your data, settings and edited messages carry over unchanged.

## Added

- **Admin menu.** `/dymarket admin` lists every staff command, and `/dyma` is its shortcut (`/dyma give`, `/dyma log`, `/dyma reload` ...). Admin words typed straight after `/dymarket`, such as the `/dymarket give ... credits <txid>` command webstores run, keep working.
- **More names for the menu.** `/market` is the same as `/dymarket` and `/dym`.
- **`/economy` (`/eco`)** holds only the money commands (`give`, `take`, `set`, `reset`, `log`, `rollback`, `merge`, `import`) for staff with `dymarket.admin.economy`, with its own help page.
- **Auction staff tools.** `/dyma ah price <id> <price>` changes the price of any listing and tells the seller. In `/ah`, staff with `dymarket.admin.ah` Shift + left-click a listing to change its price in chat and Shift + right-click to remove it.
- **Help pages.** `/dymarket help [page]` shows eight rows per page and the exact command for the next page; rows you lack permission for are hidden. The console still gets every row at once.
- **Cooldowns.** A wait in seconds for each of `/pay`, `/credits send`, `/credits exchange`, `/ah sell`, auction purchases, `/estate sell` and `/estate rent`, and estate purchases and rentals, under `cooldowns` in `config.yml` (0 turns one off). A running wait survives leaving and rejoining. `dymarket.bypass.cooldown` skips them.
- **`/dyma reset <player> [currency]`** puts a balance back to the currency's starting amount, or every balance when no currency is named.
- **Search by typing.** In the auction house, **Search** closes the window and takes your next chat line as the search (`cancel` stops it); the line is not shown to other players. `/ah search` without text does the same. Right-click the search button to show everything again.
- **`dymarket.player`** groups every player permission for permission plugins.

## Changed

- **Prefix and colours.** The prefix is `[DyMarket]` with light grey brackets, like DyClaim, DyHomes and DyRTP. Headers, usage lines and highlights follow the family style, and every message was rewritten in plainer, friendlier words in all five languages.
- **Errors show the right usage.** A wrong amount, currency or sub-command now says what was wrong and then how the command is typed.
- **TAB.** Amount positions suggest example amounts and currencies only come after them. `/dyma give <player> <currency> <amount>` works as well as `<amount> <currency>`.
- **Auction house window.** The bottom bar holds previous page, sort, search, a short guide, your items for sale, your collection box and next page. Window titles carry no colour code, so every client, dark resource packs included, shows them in its own readable colour; item texts avoid dark grey.
- **Short numbers.** New auction listings, estate listings, leases and transactions get plain numbers (`#12`) instead of random codes. Older codes keep working.
- **`/dyma language`** sets the language for everyone; `/dymarket language` stays the player's own choice.

## Upgrading

- Replace the JAR and restart. Message texts you never edited are replaced with the new ones; texts you edited are kept. The old help lists under `help:` are no longer used and can be deleted.
- The new `cooldowns` section is added to `config.yml` with short waits (1 to 10 seconds). Set a value to 0 to keep the 1.0 behaviour for that action.
- `/dyma` used to be another name for `/dymarket`; it is now the admin shortcut and needs `dymarket.admin`. Players use `/dym` or `/market`.
- Scripts and webstores that run `/dymarket give`, `take`, `set`, `log` or `rollback` need no change.

Full changelog: https://dynastyplugins.com/dymarket/versions/1.1.0-BETA
