# Architecture

## Shape

```
ServerCorePlugin          thin bootstrap; constructs services, starts and stops them
   │
   ├── core/              Service lifecycle, registry, thread routing
   ├── config/            Validating YAML loader, transactional reload
   ├── data/              Connections, transactions, migrations, repositories
   ├── log/               Append-only audit trail
   ├── notify/            Messages, notifications, offline delivery
   ├── permission/        Single permission evaluation point
   ├── gui/               Reusable menu framework
   ├── integration/       Optional third-party detection
   ├── command/           Brigadier command registration
   └── util/              Money parsing, durations, Adventure text
```

Modules depend on *interfaces* resolved through `ServiceRegistry`, not on each
other's concrete classes. That is what allows a module to be replaced or tested
in isolation, and what keeps the auction house from reaching into the economy's
database tables directly.

## Decisions worth knowing

### Money is `long` minor units, never `double`

Floating point loses precision under repeated addition. An economy that drifts by
fractions of a cent per transaction is an economy that can be farmed. Every
conversion between player input and internal representation goes through
`util/Numbers`, which rejects negatives, scientific notation, over-precise
decimals and absurdly long digit strings before they reach a balance.

### Writes take the lock up front

SQLite runs in WAL mode: many concurrent readers, one writer. Every transaction
is opened `IMMEDIATE` (configured on the data source, so the driver issues it),
which takes the write lock at the start rather than starting deferred and trying
to upgrade. Upgrading is what produces `SQLITE_BUSY` deadlocks when two writers
overlap.

Money-moving operations use a **conditional update** — `UPDATE ... SET amount =
amount - ? WHERE id = ? AND amount >= ?` — and treat the affected row count as
the authority on whether the operation succeeded. `DatabaseTest` proves this:
twenty threads racing to withdraw 30 from a balance of 100 produce exactly three
successes and a remainder of 10.

### No database call on the main thread

`Database` refuses main-thread access outright rather than trusting convention.
Blocking during a tick stalls the whole server. The standard pattern is
`Scheduling.supplyAsync(...)` to compute off-thread, then `thenSync(...)` to
touch the Bukkit API safely.

`ThreadGuard` exists so the persistence layer holds no reference to Bukkit at
all, which is what makes the repositories unit-testable against a real SQLite
file with no server running.

### Schema versions are frozen once shipped

Migrations are append-only. Changing the statements of an already-released
version would leave servers that ran the old version with a schema that silently
disagrees with the code, so a correction is always a *new* version.

A database **newer** than the code is fatal. Continuing would run queries written
against an older schema, and the likely outcome is silent corruption of the
economy tables.

### The GUI is a trust boundary

`MenuManager` cancels every click inside a plugin inventory *before* any handler
runs. Handlers cause effects by calling services, never by letting a click
through. Anything that could move an item across the boundary — shift-click,
hotbar swap, double-click collect, drags — is cancelled even when the click
landed in the player's own inventory.

A click is resolved by looking up the button registered for that slot, never by
reading the `ItemStack` the client believes is there. A client that fabricates or
edits an item cannot invoke an action it was not offered. Clicks on slots with no
registered button are logged as security violations, because a vanilla client
cannot produce one.

### Audit writes are batched; balance writes are not

A busy server produces hundreds of transactions a minute, and one INSERT with its
own transaction per event would put the audit log on the critical path of every
purchase. Audit entries are queued and flushed on a timer.

The trade-off is a bounded window in which a hard crash loses recent audit rows.
That is acceptable for an audit trail and **not** acceptable for balances, which
is why balances are written synchronously inside their own transaction and never
through the audit path.

### Optional integrations use reflection

Geyser and Floodgate publish their APIs only as snapshot artifacts. Compiling
against a moving snapshot means the build can break without our code changing,
and shipping against one risks `NoClassDefFoundError` if the server runs a
different build. The surface actually needed is one method — "is this player on
Bedrock" — so reflection removes both risks for almost no cost. Without
Floodgate, detection falls back to the documented UUID shape Floodgate itself
assigns Bedrock accounts.

## Testing

`./gradlew test` — 250 tests, no server required.

Four of the money-moving modules carry deliberate concurrency tests that run real
threads against a real SQLite file: concurrent payments out of one account,
concurrent buyers of one auction listing, concurrent buyers of one shop's stock,
and concurrent claims on the same land. Each asserts that exactly one attempt
wins and that the money supply is unchanged by the losers.

`hardening/ExploitResistanceTest` attacks the exploits the specification lists,
on purpose, in one place: negative and zero amounts, integer overflow, privilege
escalation through claim trust levels, inverted and out-of-range bounds, ledger
integrity, and hostile input. `util/TextEscapeTest` fires sixteen hostile
MiniMessage payloads at `Text#escape` and asserts on the *rendered component* --
no click event, no hover event, text intact -- rather than on the escaped string,
which is MiniMessage's business rather than ours.

The database tests run against a real SQLite file rather than a mock, because the
properties under test (rollback, writer collision, WAL recovery) are properties
of SQLite and its locking, not of our code alone.

### Verified on a live server

Phase 1 was confirmed end-to-end against Paper 26.2, not just compiled. The
service count has grown since; the rest of the row still holds:

| Behaviour | Evidence |
|---|---|
| Plugin enables | `ServerCore enabled (8 services)` |
| Library loader | SQLite, HikariCP, Caffeine fetched and loaded at startup |
| Schema migration | `Database schema migrated from version 0 to 1` |
| Migration idempotence | Restart reported `schema up to date (version 1)` |
| Crash recovery | Hard-killed mid-run; restart recovered the WAL and did not re-run the migration |
| Graceful shutdown | WAL (82KB) folded into a single 53KB file; WAL file removed |
| Commands | `/servercore status`, `/servercore reload`, `/sc` alias all functional over RCON |

### A limitation worth knowing: RCON and asynchronous replies

Every command in this plugin that touches the database computes off the main
thread and delivers its reply through `Scheduling#thenSync`. Over RCON that reply
never arrives.

RCON is a request/response protocol: Paper closes the response window when the
command method returns, which is *before* the asynchronous work finishes. The
work itself still runs correctly -- verified by seeding an overdue plot, running
`/plot sweep` over RCON, and observing the plot move from `OWNED` to
`RENT_OVERDUE` with its grace window set -- but anything sent to the sender
afterwards is discarded.

This affects the console and in-game players not at all; both senders outlive the
command. It is a property of RCON rather than a defect here, and the alternative
-- blocking the main thread until the query finishes so the reply lands inside the
response window -- trades a cosmetic limitation for a server-wide stall. That is
not a trade worth making, so the limitation is documented rather than worked
around.

The practical consequence for an administrator: prefer the console or an in-game
account for commands whose answer you need to read. RCON remains fine for
fire-and-forget operations such as the sweeps.

### Verified at the final hardening pass

Re-confirmed against Paper 26.2 with the complete plugin, all thirteen phases in:

| Behaviour | Evidence |
|---|---|
| Plugin enables | `ServerCore enabled (22 services)`, 6 libraries loaded, no warnings |
| Schema at head | `Database schema up to date (version 8)` |
| Degrades without integrations | `Not present (features degrade cleanly): Geyser, Floodgate, LuckPerms, Vault, PlaceholderAPI` — the plugin runs identically on a Java-only server |
| Indexes rebuild at boot | Claim index and player-shop index both repopulated from the database |
| Rent sweep does real work | A plot seeded overdue moved `OWNED` → `RENT_OVERDUE` with a 48-hour grace window written |
| Audit log persists | Privileged actions recorded with actor, action and timestamp |
| Graceful shutdown | 119KB WAL and 32KB shm folded into a single 266KB database; both auxiliary files removed |

### Measured, not assumed

`ClaimIndex#claimAt` runs on the main thread for every block break, place and
interaction, so its cost is multiplied by player *activity* rather than player
count. Measured against an index of 50,000 claims:

| Workload | Result |
|---|---|
| 200,000 lookups across a 50,000-claim index | 219ms — roughly 1.1µs each |
| 200,000 lookups in a world with no claims | 5ms |

A 50ms tick therefore has room for roughly 45,000 claim lookups. A linear scan
over the same index would need minutes. `hardening/HotPathPerformanceTest` holds
this to a budget two orders of magnitude looser than the measurement, so it fails
only on genuine algorithmic regression rather than on a slow machine — and it
also hammers the index from four reader threads while a fifth writes, because the
real index is written by the database thread and read by the main thread.
