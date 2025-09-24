# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

Project type and tooling
- Minecraft Forge 1.8.9 client mod built with Architectury Loom and Gradle Kotlin DSL
- Java toolchain targets Java 8 bytecode; builds typically run fine on JDK 17 (CI uses Temurin 17)
- Output artifacts are remapped and placed under build/libs and build/badjars

Quick start (Windows PowerShell)
- Build (tests are disabled by default):
  ```ps1 path=null start=null
  ./gradlew.bat --no-daemon build
  ```
- Clean then build:
  ```ps1 path=null start=null
  ./gradlew.bat --no-daemon clean build
  ```
- Produce shaded dev jar explicitly (also runs as part of build):
  ```ps1 path=null start=null
  ./gradlew.bat --no-daemon shadowJar
  ```
- Assemble remapped release jar (also runs as part of build):
  ```ps1 path=null start=null
  ./gradlew.bat --no-daemon assemble
  ```
- Where to find artifacts:
  - build/libs/FarmHelperV2-<version>.jar (remapped client jar)
  - build/badjars/FarmHelper-<version>-all-dev.jar (fat dev jar)
  - build/badjars/FarmHelper-<version>-without-deps.jar (slim jar)

Java setup notes (Windows PowerShell)
- Recommended JDK: 17 (Gradle and Loom will compile classes for Java 8).
- To use a specific JDK for the current shell session:
  ```ps1 path=null start=null
  $env:JAVA_HOME = 'C:\\Program Files\\Zulu\\zulu-17'
  $env:Path = "$env:JAVA_HOME\\bin;$env:Path"
  ```

Tests
- The build is configured to skip tests by default (tasks.test.enabled = false) to prioritize mod builds.
- To run tests locally:
  1) Temporarily enable tests in build.gradle.kts by setting tasks.test.enabled = true (or removing that line).
  2) Then run:
     ```ps1 path=null start=null
     ./gradlew.bat --no-daemon test
     ```
- Run a single test class (after enabling tests):
  ```ps1 path=null start=null
  ./gradlew.bat --no-daemon test --tests "com.jelly.farmhelperv2.feature.impl.SkyMartBuyerTest"
  ```

CI and release pipeline
- Workflow: .github/workflows/build.yml
  - Triggers on push, runs with Temurin 17.
  - Builds the project: ./gradlew build
  - Uploads artifacts under build/libs/*.jar
  - Creates a GitHub Release when shouldRelease=true in gradle.properties.
    - Pre-release if version contains -pre (e.g., 2.9.7-pre5)
    - Tag name is <version>.<short_sha>
  - Optional Discord webhook notification and artifact upload if DISCORD_WEBHOOK_URL secret is present.
- Versioning switches (gradle.properties):
  - version=2.x.y[-preN]
  - shouldRelease=true|false controls whether the workflow creates a release and posts to Discord

High-level architecture
- Entry point and bootstrapping
  - com.jelly.farmhelperv2.FarmHelper is the @Mod entry point. On FML init it:
    - Initializes config (FarmHelperConfig), registers handlers and features with the Forge event bus, sets up a 1 ms event tick, and wires Baritone listeners.
    - Registers commands (FarmHelperMainCommand via oneconfig CommandManager, and RewarpCommand via ClientCommandHandler) for in-game control.
    - Integrates optional Discord bot control if the dedicated dependency mod is loaded and version-checked (see checkIfJDAVersionCorrect).
  - Coremod/bootstrap:
    - Manifest sets FMLCorePlugin=com.jelly.farmhelperv2.transformer.FMLCore and Tweaker=com.jelly.farmhelperv2.transformer.Tweaker.
    - Mixins declared via mixins.<modid>.json and mixins.baritone.json (SpongePowered Mixin) augment Minecraft client internals (input, rendering, network, GUI, etc.).

- Game state, macros, and control flow
  - GameStateHandler tracks environment (garden presence, buffs, purse/copper, pests, plots, scoreboard/tablist data) for decision-making.
  - MacroHandler is the orchestration layer for active farming macros:
    - Selects a macro implementation from FarmHelperConfig.macroType (FarmHelperConfig.MacroEnum) and manages lifecycle (enable, pause, resume, disable).
    - Coordinates rewarp logic (/warp garden), delayed actions post-teleport, and safeguards around GUI focus and movement keys.
    - Synchronizes with FeatureManager to enable/disable auxiliary features when macro starts/stops, and with clocks for cooldowns and “time since action”.
  - Macros live under macro.impl (e.g., SShapeVerticalCropMacro, SShapeSugarcaneMacro, CircularCropMacro). Each macro encodes movement/rotation and row-change timing.
  - RotationHandler centralizes eased rotation toward targets; used by macros and features when interacting with NPCs/blocks.

- Feature system (modular helpers/macros-within-macro)
  - FeatureManager owns a registry of IFeature implementations (feature.impl.*), providing:
    - enableAll / disableAll for macro lifecycle coupling
    - disableCurrentlyRunning to ensure mutually-exclusive helpers
    - shouldPauseMacroExecution and a pauseExecutionFeatures set for cooperative pausing
    - shouldIgnoreFalseCheck gating so failsafes aren’t triggered by known non-player flows
  - Key features include (non-exhaustive):
    - AutoCookie, AutoGodPot, AutoSell, AutoComposter, AutoPestExchange
    - PestsDestroyer (including on-the-track helper), VisitorsMacro, ProfitCalculator, Scheduler, BPSTracker
    - Reconnect/Repellent/Sprayonator/Wardrobe helpers, performance mode, freelook, etc.

- Failsafe system
  - FailsafeManager wires multiple detection strategies (failsafe.impl.* like Rotation/Teleport/Knockback/WorldChange/etc.).
    - Possible detections are queued, the highest-priority one is selected after a scheduled delay (chooseEmergencyDelay), and reactions are executed.
    - Handles overlay notifications, optional sounds via AudioManager, optional window focus, and logs via BanInfoWS/webhooks.
    - Provides restart-after-failsafe scheduling with optional auto-teleport back to /warp garden.

- Config and user interface
  - FarmHelperConfig extends cc.polyfrost.oneconfig.config.Config; organizes hundreds of options into sections (General, Failsafe, Scheduler, Visitors, Pests, Auto-*, Drawings, HUD, Debug, Experimental).
  - Includes pages for Failsafe Notifications and Custom Failsafe Messages; stores rewarp/spawn locations and color preferences.
  - HUD components under hud.* (StatusHUD, ProfitCalculatorHUD, UsageStatsHUD, DebugHUD) display live state; DebugHUD aggregates key states from active features and MacroHandler.

- Auto Composter and SkyMart buyers
  - AutoComposter manages travel to and interaction with the Composter, checks current organic matter and fuel, and supplies resources.
  - Purchasing strategies:
    - BiofuelBuyer (SkyMart: buy Biofuel)
    - BoxOfSeedsBuyer (SkyMart: buy Box of Seeds)
    - UnifiedSkyMartBuyer (single session buying of both types; more efficient)
  - Buyers implement GUI navigation (/desk → SkyMart → Farming Essentials) and expose granular debug logging guarded by FarmHelperConfig “SkyMart Debug” settings:
    - farmhelper-improved config includes master and per-buyer debug level selection and delays/timeouts to aid troubleshooting.

- Remote control and telemetry
  - remote.* includes Websocket server/client, Discord command handlers (requires farmhelperjdadependency mod and correct JDA dependency version), and optional usage stats.

- Pathfinding and input hooks
  - pathfinder.* integrates Baritone-based fly pathfinding and node processing used by Pests Destroyer and travel helpers.
  - mixin.* packages patch input handling, GUI, network manager, renderer, and chunk systems to support macro control and state observation.

Conventions and development notes
- No linter is configured. Stick to the existing code style and package structure when adding features (feature.impl.* with IFeature), and register them via FeatureManager if they participate in macro lifecycle.
- When adding new features that may “block” macro execution, update FeatureManager.shouldIgnoreFalseCheck and pauseExecutionFeatures usage appropriately so failsafes don’t misfire.
- If you adjust manifest attributes for coremods or mixins, mirror changes in build.gradle.kts (Jar manifest and loom{} section). Ensure mixin refmap names align with defaultRefmapName.

Troubleshooting
- If you see Windows registry warnings about a missing JRE directory during Gradle configuration, they are non-fatal. The build uses your active JAVA_HOME/JDK.
- If your shell session picks an unexpected JDK, explicitly set $env:JAVA_HOME and prepend $env:JAVA_HOME\\bin to $env:Path as shown above.
- Tests referencing JUnit/Mockito will fail if you re-enable them without compatible Java versions or classpath; the build file pins Mockito to a Java 8–compatible line.
