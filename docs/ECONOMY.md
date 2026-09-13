# Economy

## Money is an integer, always

Currency is a `long` count of **minor units** everywhere in the plugin — cents,
for a two-decimal currency. There is no `double` anywhere in the money path.

Floating point loses precision under repeated addition. An economy that drifts by
fractions of a unit per transaction is an economy that can be farmed, and the
damage compounds silently for months before anyone notices.

Two classes bound the conversion:

- `util/Numbers.parseMoney` — the only way text becomes money
- `economy/Money.format` — the only way money becomes text

They are deliberately not inverses of each other. `formatCompact` rounds, so it is
never used anywhere a player is agreeing to an amount.

### What parsing rejects

| Input | Why |
|---|---|
| `-1` | A negative payment is a withdrawal from the recipient |
| `1e9`, `1E999999` | `BigDecimal` accepts these happily and produces an astronomical balance |
| `1.005` (2dp currency) | Rejected, not rounded — a player must be charged what they typed |
| 16+ digits | Refused well before the overflow edge |
| `NaN`, `Infinity` | Not numbers |

## Nothing creates money by accident

Every balance change goes through `EconomyService`. No other module touches
`sc_account`. That is enforced by convention and by the fact that the SQL lives
inside `AccountRepository`, which the service owns.

### The conditional-update rule

A debit is a single statement whose affected-row count *is* the answer:

```sql
UPDATE sc_account
SET balance = balance - ?, updated_at = ?
WHERE uuid = ? AND balance >= ?
```

The check and the change are one atomic operation. Reading a balance and then
deciding would be a race that no application-level locking fixes cleanly.

`canAfford` exists only to grey out a button or phrase an error. It is documented
as advisory, and **never** gates a real payment.

The same pattern enforces the balance ceiling on credits, for the same reason:
two concurrent deposits must not both pass a separate check and jointly exceed it.

### Rollback is the whole point

A transfer debits, then credits. If the credit is refused — the recipient is at
the balance ceiling — the transaction throws and rolls back. Without that, money
leaves one account and reaches no other.

There is a test for exactly this (`maxBalanceRollsBackTheTransfer`), because it is
the case a reviewer's eye skips over.

### Fees are sinks, not transfers

On `/pay`, the sender is debited `amount + fee` and the recipient receives
`amount`. The fee is credited to nobody, so it leaves circulation. The test
asserts on the *total supply* rather than on balances, because that is the only
way to tell a destroyed fee from one quietly paid to someone.

## The three flows

Every `TransactionType` declares its effect on the money supply:

| Flow | Meaning | Examples |
|---|---|---|
| `SOURCE` | Creates money | Selling to the server shop |
| `SINK` | Destroys money | Shop purchases, claim costs, plot rent, fees |
| `TRANSFER` | Moves it, total unchanged | `/pay`, auction sales, player shops |
| `ADMIN` | Manual intervention | `/eco give`, `/eco set` |

`ADMIN` is separate on purpose. A server owner topping a player up must not look
like the economy organically generating money, or `/eco info` becomes useless.

`/eco info` reports sources against sinks over the last seven days and says
whether the economy is inflating, deflating or balanced.

## The ledger

`sc_transaction` is append-only. Entries are never updated or deleted; a
correction is a new, opposing entry. The ledger therefore always explains how the
current balances came to be, which is what makes a duplication claim
*investigable* rather than a matter of opinion.

A refused payment writes nothing. A ledger containing failed attempts would make
every audit unreliable.

## Calibrating the defaults

**The shipped numbers are defaults, not balanced values.** They are placeholders
chosen to be obviously arbitrary rather than deceptively plausible.

Calibrate against your own server before relying on them. The figure that matters
is **average player income per hour**, which you cannot know until players are
actually playing. Once you do:

| Setting | Rule of thumb |
|---|---|
| `starting-balance` | Roughly one hour of income — enough to trade, not enough to skip progression |
| `large-payment-confirm-threshold` | Around a day's income; low enough to catch a typo, high enough not to nag |
| Claim prices (Phase 6) | Days of income — a claim should be a decision |
| Spawn plot purchase (Phase 8) | Weeks of income — the spec calls this a progression milestone, and it should feel like one |
| Weekly rent (Phase 8) | Affordable from shop revenue alone, so an active shop sustains itself |

Watch `/eco info` over the first weeks. Persistent inflation means your sinks are
too weak or your sources too generous; the server shop's sell prices are usually
the culprit, because they are the main money source.

## Threading

Every `EconomyService` method blocks on the database and must run off the main
thread — `Database` throws if it does not. Commands follow the same shape:

```java
scheduling.thenSync(
    scheduling.supplyAsync(() -> economy.pay(from, to, amount)),
    result -> /* main thread: tell the player */,
    error  -> /* main thread: tell the player it failed */);
```

### Composing with other modules

Later phases must move money *and* something else atomically — the auction house
transferring currency and an item, a shop taking payment and giving blocks. Those
use `debitWithin` / `creditWithin`, which join the caller's existing transaction
rather than opening their own. The money still moves through `EconomyService`;
only the transaction boundary belongs to the caller.

This is what lets the auction house satisfy the spec's "must not directly
manipulate database balance records" rule while still being atomic.

## Verified behaviour

29 economy tests run against a real SQLite database. The ones worth knowing about:

| Test | Asserts |
|---|---|
| `concurrentPaymentsConserveMoney` | 16 threads × 30 random payments; total supply unchanged, no negative balances |
| `concurrentPaymentsFromOneAccountCannotOverspend` | 20 threads sending 30 from a balance of 100 → exactly 3 succeed |
| `feeIsDestroyed` | Supply *decreases* by the fee |
| `feeCountsTowardsAffordability` | Paying your exact balance fails when a fee applies |
| `maxBalanceRollsBackTheTransfer` | A refused credit restores the debit |
| `startingBalanceGrantedOnce` | Rejoining does not top a player up |
| `refusedPaymentIsNotRecorded` | Failed payments leave no ledger entry |
