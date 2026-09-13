# Crossplay (Bedrock via Geyser)

The plugin never *requires* Geyser or Floodgate. It detects them and adapts.

## The core constraint

Geyser translates Java chest inventories into Bedrock container UIs faithfully.
Layout, item names and lore carry across. What does **not** carry across is
anything depending on interaction styles a touch device cannot produce, or on
text entry methods that do not round-trip.

The design rule throughout the GUI framework: **every action must be reachable by
a plain left click on a clearly labelled item.**

## What is avoided, and why

| Avoided | Reason | What is used instead |
|---|---|---|
| Hover tooltips as the only source of information | Bedrock shows lore on tap/long-press, not hover. Information a player must read *before* deciding cannot live only in a tooltip. | Item names carry the action; lore carries detail, and never hides anything required to make the decision |
| Anvil rename for text input | Anvil text does not reliably round-trip through Geyser | Chat prompt (`gui/ChatInput`) — identical behaviour on both platforms |
| Sign editing for text input | Bedrock opens a native sign dialog the server sees differently | Chat prompt |
| Shift-click, middle-click, number keys as the *only* path to an action | Not reliably producible on touch or controller | Plain left click always works; right-click only ever duplicates something a button already does |
| Reopening a window to refresh | Flickers on Java and can dismiss the screen entirely on Bedrock | `Menu#refresh()` rewrites the contents of the already-open inventory in place |
| Dynamic window titles | Changing a title requires reopening the window | Status is shown in item names and lore instead |
| Drag-to-deposit items | The framework cancels every drag touching a menu slot, and a half-landed drag is a duplication vector | Click a button; the service reads what the player holds and moves a known quantity |

## The Phase 12 audit

Every GUI was swept for interactions a Bedrock player cannot produce. **Four
were found; three were real bugs and are fixed.**

| Location | Problem | Fix |
|---|---|---|
| `PlayerShopManageMenu` | "Take stock back" was right-click only and "change prices" shift-click only. A Bedrock shop owner could stock a shop but **never reprice it or withdraw anything**. | New `ShopOfferManageMenu`: a plain click opens a screen with labelled *Add to stock*, *Take back*, *Change prices* and *Stop trading this* buttons |
| `ClaimMembersMenu` | Removing a member was right-click only, so a Bedrock claim owner could **add trusted players but never remove one**. | New `ClaimMemberMenu`: a plain click opens a screen with one button per trust level plus *Remove from claim* |
| `PaginatedMenu` | Clearing an active search was right-click only — a Bedrock player who searched could be **stranded on an empty page** | A dedicated *Clear search* button appears whenever a filter is active |
| `ClaimMenu` expand | Right-click expands by 8, left-click by 1 | **Not a bug.** The primary path works; right-click is only a shortcut. Left as is. |

Automated guard against regressions:

```bash
grep -rn "click.isSecondary()\|click.isShift()" src/main/java
```

Every hit must have a working plain-left-click path to the same outcome. As of
this audit there is exactly one hit, and it is the expand shortcut above.

## Confirmation dialogs

Confirm and cancel sit at opposite ends of the row (slots 11 and 15 of a 3-row
menu) rather than adjacent. A mistimed tap on a phone screen lands on neither,
which matters considerably more with touch controls than with a mouse. Closing
the window counts as declining, so pressing Escape never leaves a caller waiting
on a callback that never fires.

## Quantity selection

Every buy/sell screen uses the same `QuantityControls`: six discrete step buttons
(-64, -8, -1, +1, +8, +64) around a readout, plus a *Set to maximum* button.
No scrolling, no dragging, no modifier keys. The maximum button exists because
stepping to 64 with +1 taps on a phone is nobody's idea of a good time.

## Identifying Bedrock players

`IntegrationManager#isBedrockPlayer` resolves in this order:

1. **Floodgate API**, via reflection, if Floodgate is installed and its API binds.
2. **UUID shape** otherwise. Floodgate issues Bedrock accounts a UUID whose high
   64 bits are zero; a genuine Mojang UUID is version 4 and never has that shape.

This is used **only to adapt presentation**, never to gate functionality. A
Bedrock player must be able to reach everything a Java player can — which is
precisely what the audit above was checking.

If Geyser is present without Floodgate, the plugin logs that Bedrock players will
be served but cannot be identified individually. This is a degradation, not a
failure.

## Player-authored text

Shop names, claim names, search terms and leaderboard titles are all
player-authored and all end up inside MiniMessage templates. Every such string is
escaped with `Text#escape` before rendering. Without it, a player could name a
shop `<rainbow>`, or embed a click event that runs a command on whoever views it.
Tested in `LeaderboardRendererTest`.

## Claim visualisation

Particles render through Geyser but are smaller, fade sooner, and are easy to lose
against a bright background on a phone. Visualising therefore **also prints the
corner coordinates and size to chat** — the reliable channel on every platform,
and more useful anyway when planning an expansion. Bedrock players get an extra
line saying the coordinates are the authoritative boundary.

## What is still unverified

The constraints above are enforced by design and by the code audit. They have
**not** been confirmed against a real Bedrock client, because that needs a device
and a Geyser instance this build environment does not have.

Specifically still to confirm by hand:

- Inventory rendering at each menu size (1–6 rows) on a phone screen
- Whether the 6-row menus are comfortable on small displays, or should drop to 5
- The chat-prompt flow with a touch keyboard covering half the screen
- Confirmation dialog tap accuracy at slots 11 and 15
- Whether particle visualisation is visible enough to be useful at all on Bedrock
- Controller navigation through paginated menus

None of these are expected to be broken — they are the difference between "built
to the constraint" and "seen working".

The same caveat applies one level up, and is worth stating plainly: **the Java
GUI flows have not been clicked through by a human either.** The menu framework
is covered structurally — `MenuManager` cancels every click before a handler
runs, refuses clicks on unregistered slots as security violations, and blocks
every item-moving click type — and the services behind the menus are covered by
the test suite. But nobody has opened a shop on a real client and bought
something. Anyone deploying this should walk the GUIs once before opening the
server to players.
