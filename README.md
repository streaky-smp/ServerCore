# ServerCore

A modular Paper server framework: economy, shops, auctions, land claims, spawn
plots, teleports, statistics and leaderboards — designed as one system rather
than a pile of unrelated commands, with Bedrock crossplay treated as a
first-class constraint.

**Status: all 13 phases complete.** 250 automated tests pass; the plugin boots,
migrates, sweeps, audits and shuts down cleanly on a live Paper 26.2 server.

Two things have *not* been exercised by a human at a keyboard, because this build
environment has no Minecraft client: the in-game GUI flows on Java, and every
Bedrock check listed in
[Crossplay](docs/CROSSPLAY.md#what-is-still-unverified). Both are built to a
documented constraint and covered structurally, which is not the same as seen
working. See [Development phases](#development-phases) for what exists, and
[Architecture](docs/ARCHITECTURE.md#testing) for exactly what was verified how.

---

## Target platform

These are verified against the live Paper API, not assumed:

| Component | Version | Notes |
|---|---|---|
| Paper API | `26.2.build.123-stable` | Paper moved to calendar versioning in 2026; the lineage is `1.21.11` → `26.1` → `26.2` |
| Java | **25** | Required by Paper 26.1+. Not optional. |
| Maven | 3.9+ | System-installed |
| SQLite | 3.53.4.0 | Loaded at runtime, not shaded |
| HikariCP | 7.1.0 | Loaded at runtime |
| Caffeine | 3.2.4 | Loaded at runtime |

Runtime libraries are declared in `plugin.yml` under `libraries:` and fetched by
Paper's own library loader on first start. Nothing is shaded, so the plugin jar
stays small and library versions are visible and auditable.

## Building

```bash
pwsh ./build.ps1
```

The jar lands in `target/ServerCore-0.1.0.jar`.

Or manually:

```bash
mvn clean package
```

## Running a test server

The plugin jar can be dropped into any Paper 26.2 server's `plugins/` folder.
Runtime libraries (SQLite, HikariCP, Caffeine) are fetched automatically by
Paper's library loader on first start — no shading, no extra steps.

## A note on where this project lives

Keep it **out of OneDrive** (or any cloud-sync folder). Sync clients hold file
locks on the tens of thousands of transient files a Java build produces, which
makes `clean` and incremental compilation fail intermittently with
`Unable to delete directory`. This was hit for real during development.

`target/` is gitignored and fully regenerable — delete it at any time.

## Optional integrations

Every integration is optional. The plugin loads and runs fully with none of them
installed, and logs exactly what it found at startup.

| Plugin | What it adds | Without it |
|---|---|---|
| Geyser | Bedrock clients can connect | Java-only server, everything else unchanged |
| Floodgate | Identifies individual Bedrock players | Falls back to Floodgate's documented UUID shape |
| LuckPerms | Groups, prefixes, richer permission data | Permission *checks* still work — see below |
| Vault | Economy bridge for other plugins | Other plugins cannot see the economy |
| PlaceholderAPI | Placeholders in other plugins | Placeholders unavailable |

**On LuckPerms specifically:** permission checks do *not* require it. LuckPerms,
PermissionsEx, GroupManager and Paper's built-in handling all implement
`CommandSender#hasPermission`, so routing through that supports every permission
plugin without depending on any of them. The LuckPerms integration exists only
for things Bukkit cannot express, such as reading a player's group or prefix.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/servercore status` (alias `/sc`) | `server.admin` | Service, database and integration state |
| `/servercore reload` | `server.admin` | Re-read configuration |
| `/balance [player]` (aliases `/bal`, `/money`) | `server.economy.balance` | Balance, ranking and recent transactions |
| `/balance gui` | `server.economy.balance` | The same, as a menu with full history |
| `/pay <player> <amount>` | `server.economy.pay` | Send money; confirms above a configured threshold |
| `/eco give\|take\|set <player> <amount>` | `server.economy.admin` | Adjust a balance; always audited |
| `/eco info` | `server.economy.admin` | Money supply, and sources against sinks |
| `/shop [category]` | `server.shop.use` | Browse, buy and sell |
| `/stats [player]` (alias `/profile`) | `server.stats.view` | Profile: balance, combat, playtime, ranks |
| `/leaderboard create\|remove\|list\|refresh` (alias `/lb`) | `server.leaderboard.admin` | Place and manage physical leaderboards |
| `/claim [radius] [name]` | `server.claim.create` | Buy a square claim around you |
| `/claim visualize\|menu\|info\|list` | `server.claim.create` | Show boundaries, manage claims |
| `/claim expand\|shrink\|rename\|delete` | `server.claim.expand` | Resize and manage |
| `/pshop create\|manage\|delete\|rename\|list` | `server.playershop.create` | Run your own stalls |
| `/pshop directory` | `server.playershop.create` | Search every shop by name, owner or item |
| `/plot district` | `server.plot.purchase` | Browse the premium spawn district |
| `/plot pay <id>` / `/plot mail` | `server.plot.purchase` | Pay rent early; collect recovered stock |
| `/plot create\|remove\|setprice\|setrent\|setperiod\|status\|sweep` | `server.plot.admin` | Define and administer plots |
| `/ah` / `/ah sell <price>` / `/ah mine` | `server.auction.use` | Browse, list, collect |
| `/ah remove <id>` / `/ah sweep` | `server.auction.admin` | Remove a listing; force a sweep |
| `/tpa` / `/tpahere` / `/tpaccept` / `/tpdeny` / `/tpacancel` / `/tpalist` | `server.tpa.use` | Teleport requests |
| `/claim trust\|untrust <player> [level]` | `server.claim.create` | Members: access, container, build, manage |

Commands are registered through Paper's Brigadier lifecycle rather than declared
in `plugin.yml`, so they get real argument parsing and tab-completion.

## Configuration

| File | Contents |
|---|---|
| `config.yml` | Database, logging, GUI and notification settings |
| `messages.yml` | All player-visible text, as MiniMessage markup |
| `shop.yml` | Server shop catalogue: 12 categories, 96 items |

Economy settings live under `economy:` in `config.yml`. Amounts there are written
in whole currency, exactly as a player sees them, and converted to integer minor
units on load.

Reloads are transactional: every file is parsed and validated before anything is
applied. A bad edit leaves the running configuration untouched and reports the
error, rather than half-applying it.

Every default value is a **default, not a balanced recommendation**. Economy
numbers in particular must be calibrated against your own server's income rates.

## Development phases

The [specification](#) defines thirteen phases and requires the foundation be
stable before anything is built on it.

- [x] **Phase 1 — Foundation.** Project structure, configuration, logging,
      database, repository layer, GUI framework, permissions, notifications,
      integration detection.
- [x] **Phase 2 — Economy.** Central economy service, accounts, `/balance`,
      `/pay` with fees and confirmation, append-only transaction ledger,
      administration and economy reporting.
- [x] **Phase 3 — Server shop.** Config-driven catalogue, categories, search,
      sort, quantity picker, confirmation, and a buy/sell path ordered so a
      failure never leaves a player ahead.
- [x] **Phase 4 — Statistics.** Kills, deaths and playtime persisted, anti-farm
      windows, buffered high-frequency counters, profile GUI.
- [x] **Phase 5 — Leaderboards.** Reusable ranking framework over both
      statistics and balances, physical floating displays recreated from the
      database at startup, escaped rendering.
- [x] **Phase 6 — Claims.** Rectangular full-height claims, chunk-bucketed
      spatial index, four trust levels, ten permission flags, particle
      visualisation, and purchase/expansion charged atomically with the land.
- [x] **Phase 7 — Player shops.** Player-owned stalls with per-item stock
      and prices, a searchable global directory, click-to-open anchor blocks,
      and purchases that move money, stock and goods atomically.
- [x] **Phase 8 — Spawn plots and rent.** Commercial district, one-off
      purchase plus recurring rent, gradual grace/expiry escalation, and a
      recovery mailbox so shop stock is never destroyed.
- [x] **Phase 9 — Auction house.** Fixed-price listings with full item
      fidelity, conditional-update concurrency guards, and a collection box
      that removes the refund path entirely.
- [x] **Phase 10 — TPA.** Requests with expiry, cooldowns and duplicate
      suppression; warm-up cancelled by movement or damage; safe-destination
      checking; GUI equivalents for touch players.
- [x] **Phase 11 — Administration.** Admin GUI over live server state, audit
      log of every privileged action, economy overrides that are recorded as
      deliberate creation or destruction rather than transfers, and a permission
      tree rooted at `server.admin`.
- [x] **Phase 12 — Crossplay verification.** Every GUI audited for interactions
      a touch device cannot produce. Three real defects found and fixed: Bedrock
      shop owners could not reprice or withdraw stock, claim owners could not
      remove a trusted player, and a search could strand a player on an empty
      page. See [Crossplay](docs/CROSSPLAY.md#the-phase-12-audit).
- [x] **Phase 13 — Hardening.** The exploits in the specification attacked on
      purpose rather than assumed prevented, MiniMessage injection tested against
      hostile input, the block-event claim lookup measured at 50,000 claims, and
      every fire-and-forget async call given a failure path.

## Versioning

This project follows [Semantic Versioning](https://semver.org/) (SemVer):
`MAJOR.MINOR.PATCH`.

- **MAJOR** — incompatible API or data-format changes. A major bump means
  existing configs, database schemas or third-party integrations may break
  without a migration path.
- **MINOR** — new features, new configuration options, new commands. Backward
  compatible with the previous minor release.
- **PATCH** — bug fixes, performance improvements, documentation changes. Fully
  backward compatible; drop-in replacement.

**Why it matters:** server administrators need to know whether updating is safe
to do blindly or requires reading release notes. A patch should never break a
working server. A minor should never remove existing config keys. A major
should clearly signal that migration steps are required.

Tags follow the format `v1.2.3`. The CI workflow creates a GitHub Release for
every tag.

## Further reading

- [Architecture](docs/ARCHITECTURE.md) — module layout and the decisions behind it
- [Crossplay](docs/CROSSPLAY.md) — Bedrock constraints and how the GUI works around them
- [Economy](docs/ECONOMY.md) — how money is represented, why it cannot be duplicated, and how to calibrate the defaults
