<div align="center">
  <img src="images/logo.png" alt="Logo" width="96" height="96" />

  <h1>FarmHelper V2 — Auto Composter</h1>
  <p>Focused, reliable automation for the Garden Composter in Hypixel SkyBlock.</p>
</div>

## Overview

This fork/workspace centers on the Auto Composter feature. It automates traveling to the Garden Composter, checking current resources, optionally buying Biofuel, and filling the composter directly from your inventory using a configurable priority of enchanted items.

The integration uses Minecraft’s native chat system to send commands exactly like a normal player would type them (no Robot, no screen freezing). Before traveling, it cleanly disables other scripts with `/ez-stopscript`, and after finishing it can run an optional end sequence to return to farming.

## Key features

- Native command typing (no Robot) for smooth UI and no freezes
- Pre-travel script stop via `/ez-stopscript`
- Travel to composter using either FlyPathFinder or Baritone
- Automatic detection of the Composter entity and nearest approach position
- Smart parsing of composter GUI to read current Organic Matter and Fuel levels
- Inventory-based filling with prioritized organic matter list and optional Box of Seeds
- Optional Biofuel auto-purchase via the integrated buyer
- Stuck detection and safe restarts
- Optional background loop to rerun on a schedule
- Optional highlight for the saved composter location

## Requirements

- Minecraft 1.8.9 (Forge)
- Hypixel SkyBlock (Garden)
- Baritone (optional) if you choose Baritone travel

## Build and install

Build the mod jar with Gradle, then drop it into your mods folder.

```powershell
./gradlew build
```

The output jar will be under `build/libs/`.

## Configuration (FarmHelperConfig)

Relevant options used by Auto Composter (names reflect fields in `FarmHelperConfig`):

- Composter position:
  - `composterX`, `composterY`, `composterZ` — saved/used for direct travel
- Travel:
  - `autoComposterTravelMethod` — choose pathing (false = FlyPathFinder, true = Baritone)
  - `highlightComposterLocation` — draw a box at the composter location
- Operation thresholds and inputs:
  - `autoComposterOrganicMatterLeft` — target minimum organic matter (in the GUI’s unit)
  - `autoComposterFuelLeft` — target minimum fuel
  - `autoComposterUseBoxOfSeeds` — allow using Box of Seeds as filler
  - `useBiofuelBuyer` — enable automatic Biofuel purchases when inventory has none
  - Per-item toggles for organic matter (e.g., `autoComposterUseEnchantedWheat`, `autoComposterUseEnchantedCactus`, ...)
  - `autoComposterOrganicPriority` — pipe-separated priority list (e.g., `Enchanted Nether Wart|Enchanted Wheat|...`)
- Timings and delays:
  - `macroGuiDelay`, `macroGuiDelayRandomness` — general GUI delays
  - `autoComposterClickDelayMs`, `autoComposterFuelClickDelayMs` — per-click delays for filling
- Safety and environment:
  - `autoComposterRequireCookieBuff` — require a cookie buff to run
  - `pauseAutoComposterDuringJacobsContest` — skip when Jacob’s Contest is active
  - `autoComposterMinMoney` — minimum purse to proceed
- Background loop:
  - `autoComposterBackgroundLoopEnabled` — rerun automatically on a schedule
  - `autoComposterLoopIntervalMinutes` — interval between runs
- Logging:
  - `logAutoComposterEvents` — enable webhook logs for key events

## How it works

The logic is a simple state machine (see `AutoComposter.java`).

1. Start: pauses any other active macro and sends `/ez-stopscript` with a small randomized delay.
2. Travel: teleports towards the Barn/Garden and moves to the composter position (saved or discovered).
3. Rotate & open: orients to the Composter armor stand and opens its GUI.
4. Check: reads Organic Matter and Fuel values from item lore.
5. Buy (optional): if fuel is below threshold and none in inventory, the Biofuel buyer can run.
6. Fill: feeds the composter from your inventory according to priority and thresholds.
7. End: closes screens, optionally runs an end sequence, schedules the next run if background loop is enabled, and restores your normal routine.

### Commands integration

- Pre-travel stop: `/ez-stopscript`
- End sequence (optional, already wired in the code):
  - `/warp garden`
  - `/ez-listfarms`
  - `/ez-startscript netherwart:1`

You can customize these commands inside `AutoComposter.java` if your setup differs.

## Usage

1. Configure thresholds, priorities, and travel method in the Farm Helper config UI or config file.
2. Ensure you’re in the Garden and the composter position is set (or let the feature auto-discover it once).
3. Enable Auto Composter. It will stop other scripts, travel, check/fill the composter, and finish.
4. Optionally enable the background loop to keep your composter topped up over time.

## Troubleshooting

- Stuck or not moving:
  - Check if Baritone or FlyPathFinder is enabled per your travel method setting.
  - Look at the in-game debug logs for the current state (Travel/Composter/End). The feature auto-restarts on stuck detection.
- Not filling organic matter:
  - Verify the per-item toggles and the `autoComposterOrganicPriority` string.
  - Ensure items are present in your inventory.
- Fuel not filling / no Biofuel:
  - Make sure `useBiofuelBuyer` is enabled if you rely on automatic purchases, or keep Biofuel in your inventory.
- Commands not executing:
  - The feature uses native chat. Confirm chat isn’t blocked and you’re not in another GUI when the command fires.

## License

Distributed under the license found in `LICENSE`.

