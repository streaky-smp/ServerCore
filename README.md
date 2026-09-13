# ServerCore

![Build](https://github.com/streaky-smp/ServerCore/actions/workflows/build.yml/badge.svg)

A modular Paper server framework — economy, shops, auctions, land claims, player
shops, spawn plots, teleports, statistics and leaderboards. Designed as one
coherent system with Bedrock crossplay as a first-class constraint.

## Features

- **Economy** — balances, `/pay`, transaction ledger, admin tools
- **Server Shop** — config-driven catalogue, 12 categories, 96 items
- **Auction House** — fixed-price listings with full item fidelity
- **Land Claims** — rectangular full-height claims, four trust levels, ten permission flags
- **Player Shops** — player-owned stalls with per-item stock and prices
- **Spawn Plots** — commercial district with purchase + recurring rent
- **TPA** — teleport requests with cooldowns, warm-up, safe-destination checks
- **Statistics & Leaderboards** — kills, deaths, playtime, physical floating displays
- **Crossplay** — every GUI works with a single left click (Bedrock-compatible)

## Requirements

| Component | Version |
|---|---|
| Paper | 26.2+ |
| Java | 25 |

## Building

```bash
pwsh ./build.ps1
```

Or with Maven directly:

```bash
mvn clean package
```

The jar lands in `target/ServerCore-0.1.0.jar`.

## Installation

Drop the jar into your Paper server's `plugins/` folder. Runtime libraries
(SQLite, HikariCP, Caffeine) are fetched automatically by Paper's library
loader on first start — nothing else needed.

## Commands

| Command | Description |
|---|---|
| `/sc` | Server status, reload |
| `/bal [player]` | Balance, ranking, transaction history |
| `/pay <player> <amount>` | Send money |
| `/shop [category]` | Server shop |
| `/ah` | Auction house — browse, sell, collect |
| `/claim [radius]` | Land claims — create, expand, trust |
| `/pshop` | Player shops — create, manage, directory |
| `/plot` | Spawn plots — browse, pay rent |
| `/stats [player]` | Player profile and statistics |
| `/lb` | Leaderboard management |
| `/tpa` | Teleport requests |

Full command reference with permissions: see the [Architecture docs](docs/ARCHITECTURE.md).

## Configuration

| File | Contents |
|---|---|
| `config.yml` | Database, logging, GUI, economy settings |
| `messages.yml` | All player-visible text (MiniMessage markup) |
| `shop.yml` | Server shop catalogue |

Reloads are transactional — a bad edit leaves the running config untouched.

## Optional Integrations

All optional. The plugin runs fully without any of them.

| Plugin | Adds |
|---|---|
| Geyser / Floodgate | Bedrock client support |
| LuckPerms | Group/prefix data |
| Vault | Economy bridge for other plugins |
| PlaceholderAPI | Placeholders in other plugins |

## Versioning

This project follows [Semantic Versioning](https://semver.org/). Tags use the
format `v1.2.3`. The CI workflow creates a GitHub Release for every tag.

## Docs

- [Architecture](docs/ARCHITECTURE.md) — module layout and design decisions
- [Crossplay](docs/CROSSPLAY.md) — Bedrock constraints and GUI workarounds
- [Economy](docs/ECONOMY.md) — money representation and calibration
