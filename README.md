<div align="center">
  <img src="images/logo.png" alt="Logo" width="84" height="84" />
  <h1>Auto Composter (FarmHelper V2)</h1>
  <p>Lean, reliable Garden Composter automation — tuned for Taunahi and Ironman.</p>
</div>

## What it does

- Stops your farming script (`/ez-stopscript`), travels to the composter, reads GUI values, and fills from your inventory using a prioritized list.
- Uses native chat (no Robot) for safe, lag-free commands.
- Optional end sequence to resume farming (e.g., `/warp garden` → `/ez-startscript netherwart:1`).

## Setup (quick)

1. Build and install: the jar is under `build/libs/` after
   
   ```powershell
   ./gradlew build
   ```
2. In Garden, let it learn the composter position (or set `composterX/Y/Z`).
3. Configure in `FarmHelperConfig`:
   - Item priority: `autoComposterOrganicPriority` and per-item toggles
   - Targets: `autoComposterOrganicMatterLeft`, `autoComposterFuelLeft`
   - Travel: `autoComposterTravelMethod` (FlyPathFinder or Baritone)
   - Optional: background loop interval

## Taunahi + Ironman flow

1. Farm with Taunahi (e.g., Nether Wart)
2. Auto Composter triggers → `/ez-stopscript`
3. Fills composter → optional end sequence resumes your farm

Customize end commands in `AutoComposter.java` if needed.

## Tips

- Keep a sane click delay (100–200 ms) to avoid GUI hiccups.
- Set a background interval (15–30 min) so composter isn’t your bottleneck.
- Enable webhook logs if you want traces of runs.

## License

See `LICENSE`.

