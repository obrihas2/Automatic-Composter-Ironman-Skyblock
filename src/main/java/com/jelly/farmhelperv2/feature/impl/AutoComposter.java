package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.feature.FeatureManager;
import com.jelly.farmhelperv2.feature.IFeature;
import com.jelly.farmhelperv2.handler.BaritoneHandler;
import com.jelly.farmhelperv2.handler.GameStateHandler;
import com.jelly.farmhelperv2.handler.MacroHandler;
import com.jelly.farmhelperv2.handler.RotationHandler;
import com.jelly.farmhelperv2.pathfinder.FlyPathFinderExecutor;
import com.jelly.farmhelperv2.util.*;
import com.jelly.farmhelperv2.util.helper.Clock;
import com.jelly.farmhelperv2.util.helper.RotationConfiguration;
import com.jelly.farmhelperv2.util.helper.Target;
import jline.internal.Nullable;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StringUtils;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.lang3.tuple.Pair;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoComposter implements IFeature {
    Minecraft mc = Minecraft.getMinecraft();
    private static AutoComposter instance;

    public static AutoComposter getInstance() {
        if (instance == null) {
            instance = new AutoComposter();
        }
        return instance;
    }

    private final BiofuelBuyer biofuelBuyer = new BiofuelBuyer();
    // BoxOfSeedsBuyer removed: Box of Seeds are not purchased, only used from inventory
    // UnifiedSkyMartBuyer disabled: feeding composter directly from inventory per user request
    @Getter
    private final Clock delayClock = new Clock();
    @Getter
    private final Clock stuckClock = new Clock();
    // Background scheduler to re-run the composter periodically
    private final Clock nextRunClock = new Clock();
    private final int STUCK_DELAY = (int) (7_500 + FarmHelperConfig.macroGuiDelay + FarmHelperConfig.macroGuiDelayRandomness);
    private final Pattern composterResourcePattern = Pattern.compile("^(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)/(\\d{1,3})k$");
    @Getter
    private MainState mainState = MainState.NONE;
    @Getter
    private TravelState travelState = TravelState.NONE;
    @Getter
    private ComposterState composterState = ComposterState.NONE;
    private boolean enabled = false;

    /**
     * Expose current buying state for debug HUD
     */
    public String getBuyState() {
        if (biofuelBuyer.isRunning()) {
            return "Biofuel:" + biofuelBuyer.getBuyState().name();
        }
        return "NONE";
    }
    @Getter
    @Setter
    private boolean manuallyStarted = false;
    private BlockPos positionBeforeTp = null;
    private final BlockPos initialComposterPos = new BlockPos(-11, 72, -27);
    private Entity composter = null;
    private boolean composterChecked = false;
    private int endSequenceStep = 0; // Track which step of the end sequence we're on


    private BlockPos composterPos() {
        return new BlockPos(FarmHelperConfig.composterX, FarmHelperConfig.composterY, FarmHelperConfig.composterZ);
    }

    private boolean isComposterPosSet() {
        return FarmHelperConfig.composterX != 0 && FarmHelperConfig.composterY != 0 && FarmHelperConfig.composterZ != 0;
    }

    @Override
    public String getName() {
        return "Auto Composter";
    }

    @Override
    public boolean isRunning() {
        return enabled;
    }

    // HUD scheduling helpers for background loop
    public boolean isNextRunScheduled() {
        return nextRunClock.isScheduled();
    }

    public long getNextRunRemainingMs() {
        return nextRunClock.getRemainingTime();
    }

    @Override
    public boolean shouldPauseMacroExecution() {
        return true;
    }

    @Override
    public boolean shouldStartAtMacroStart() {
        return false;
    }

    @Override
    public void start() {
        MacroHandler.getInstance().getCurrentMacro().ifPresent(macro -> macro.getRotation().reset());
        mainState = MainState.NONE;
        travelState = TravelState.NONE;
        composterState = ComposterState.NONE;
        biofuelBuyer.stop();
        enabled = true;
        composterChecked = false;
        delayClock.reset();
        RotationHandler.getInstance().reset();
        stuckClock.schedule(STUCK_DELAY);
        biofuelBuyer.stop();
        if (MacroHandler.getInstance().isMacroToggled()) {
            MacroHandler.getInstance().pauseMacro();
        }
        LogUtils.sendWarning("[Auto Composter] Macro started");
        
        if (FarmHelperConfig.logAutoComposterEvents) {
            LogUtils.webhookLog("[Auto Composter]\\nAuto Composter started");
        }
        IFeature.super.start();
    }

    @Override
    public void stop() {
        enabled = false;
        manuallyStarted = false;
        LogUtils.sendWarning("[Auto Composter] Macro stopped");
        RotationHandler.getInstance().reset();
        biofuelBuyer.stop();
        KeyBindUtils.stopMovement();
        FlyPathFinderExecutor.getInstance().stop();
        BaritoneHandler.stopPathing();
        MacroHandler.getInstance().getCurrentMacro().ifPresent(cm -> cm.getCheckOnSpawnClock().schedule(5_000));
        IFeature.super.stop();
    }

    @Override
    public void resetStatesAfterMacroDisabled() {
        mainState = MainState.NONE;
        travelState = TravelState.NONE;
        composterState = ComposterState.NONE;
        composterChecked = false;
        biofuelBuyer.stop();
    }

    @Override
    public boolean isToggled() {
        return FarmHelperConfig.autoComposter;
    }

    @Override
    public boolean shouldCheckForFailsafes() {
        return travelState != TravelState.WAIT_FOR_TP && mainState != MainState.END;
    }

    public boolean canEnableMacro(boolean manual) {
        if (!isToggled()) return false;
        if (isRunning()) return false;
        if (!GameStateHandler.getInstance().inGarden()) return false;
        if (mc.thePlayer == null || mc.theWorld == null) return false;
        if (FeatureManager.getInstance().isAnyOtherFeatureEnabled(this)) return false;

        if (GameStateHandler.getInstance().getServerClosingSeconds().isPresent()) {
            LogUtils.sendError("[Auto Composter] Server is closing in " + GameStateHandler.getInstance().getServerClosingSeconds().get() + " seconds!");
            return false;
        }

        if (FarmHelperConfig.autoComposterRequireCookieBuff && GameStateHandler.getInstance().getCookieBuffState() == GameStateHandler.BuffState.NOT_ACTIVE) {
            LogUtils.sendError("[Auto Composter] Cookie buff is not active, skipping...");
            return false;
        }

        if (!manual && FarmHelperConfig.pauseAutoComposterDuringJacobsContest && GameStateHandler.getInstance().inJacobContest()) {
            LogUtils.sendError("[Auto Composter] Jacob's contest is active, skipping...");
            return false;
        }

        if (!manual && GameStateHandler.getInstance().getCurrentPurse() < FarmHelperConfig.autoComposterMinMoney * 1_000) {
            LogUtils.sendError("[Auto Composter] The player's purse is too low, skipping...");
            return false;
        }

        if (!manual) {
            int organicMatterCount = GameStateHandler.getInstance().getOrganicMatterCount();
            int fuelCount = GameStateHandler.getInstance().getFuelCount();
            boolean needsMatter = organicMatterCount < FarmHelperConfig.autoComposterOrganicMatterLeft;
            boolean needsFuel = fuelCount < FarmHelperConfig.autoComposterFuelLeft;
            if (!needsMatter && !needsFuel) {
                LogUtils.sendWarning("[Auto Composter] Resources are above thresholds (OM: " + organicMatterCount + ", Fuel: " + fuelCount + ") — skipping");
                return false;
            }
        }

        return true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {        
        if (!isRunning()) return;
        if (event.phase == TickEvent.Phase.END) return;
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (!GameStateHandler.getInstance().inGarden()) return;
        if (delayClock.isScheduled() && !delayClock.passed()) return;
        if (GameStateHandler.getInstance().getServerClosingSeconds().isPresent()) {
            LogUtils.sendError("[Auto Composter] Server is closing in " + GameStateHandler.getInstance().getServerClosingSeconds().get() + " seconds!");
            stop();
            return;
        }
        if (stuckClock.isScheduled() && stuckClock.passed()) {
            if (biofuelBuyer.isRunning()) {
                stuckClock.reset();
                return;
            }
            // Don't trigger stuck detection during end sequence
            if (mainState == MainState.END) {
                stuckClock.reset();
                return;
            }
            LogUtils.sendError("[Auto Composter] The player is stuck, restarting the macro...");
            stop();
            start();
            return;
        }

        switch (mainState) {
            case NONE:
                // Add delay before pressing B key
                if (!delayClock.isScheduled()) {
                    delayClock.schedule((long) (1100 + Math.random() * 300)); // 1.1-1.4s delay
                    return;
                }
                if (!delayClock.passed()) return;
                
                // Send command to disable other script before starting teleport/travel
                LogUtils.sendDebug("[Auto Composter] Sending disable command to stop other scripts before travel...");
                typeCommand("/ez-stopscript");
                
                // Add delay after sending command before travel
                delayClock.schedule((long) (1100 + Math.random() * 300)); // 1.1-1.4s delay
                setMainState(MainState.TRAVEL);
                break;
            case TRAVEL:
                onTravelState();
                break;
            case AUTO_SELL:
                AutoSell.getInstance().start();
                setMainState(MainState.COMPOSTER);
                delayClock.schedule(getRandomDelay());
                break;
            case COMPOSTER:
                if (AutoSell.getInstance().isRunning()) {
                    stuckClock.schedule(STUCK_DELAY);
                    delayClock.schedule(getRandomDelay());
                    return;
                }
                onComposterState();
                break;
            case END:
                if (mc.currentScreen != null) {
                    PlayerUtils.closeScreen();
                    delayClock.schedule(1_000 + Math.random() * 500);
                    break;
                }
                
                // Add the requested end sequence with 1.1-1.4s delays: escape -> /warp garden -> /ez-listfarms -> /ez-startscript netherwart:1
                if (!delayClock.isScheduled()) {
                    // Initial delay after macro completes 
                    LogUtils.sendDebug("[Auto Composter] Macro completed, starting end sequence step " + endSequenceStep);
                    delayClock.schedule((long) (1100 + Math.random() * 300)); // 1.1-1.4s delay
                    return;
                } else if (delayClock.passed()) {
                    switch (endSequenceStep) {
                        case 0:
                            // Step 0: Close any open screen (like Escape key)
                            LogUtils.sendDebug("[Auto Composter] End sequence step 0: Closing any open screens");
                            if (mc.currentScreen != null) {
                                PlayerUtils.closeScreen();
                            }
                            endSequenceStep = 1;
                            delayClock.schedule((long) (1100 + Math.random() * 300)); // 1.1-1.4s delay
                            return;
                        case 1:
                            // Step 1: /warp garden command - use Robot to type it for consistency
                            LogUtils.sendDebug("[Auto Composter] End sequence step 1: /warp garden");
                            typeCommand("/warp garden");
                            endSequenceStep = 2;
                            delayClock.schedule((long) (1100 + Math.random() * 300)); // 1.1-1.4s delay
                            return;
                        case 2:
                            // Step 2: /ez-listfarms command
                            LogUtils.sendDebug("[Auto Composter] End sequence step 2: /ez-listfarms");
                            typeCommand("/ez-listfarms");
                            endSequenceStep = 3;
                            delayClock.schedule((long) (1100 + Math.random() * 300)); // 1.1-1.4s delay
                            return;
                        case 3:
                            // Step 3: /ez-startscript netherwart:1 command
                            LogUtils.sendDebug("[Auto Composter] End sequence step 3: /ez-startscript netherwart:1");
                            typeCommand("/ez-startscript netherwart:1");
                            // Reset for next run
                            endSequenceStep = 0;
                            break;
                    }
                }
                
                // Schedule background loop if enabled
                if (FarmHelperConfig.autoComposterBackgroundLoopEnabled) {
                    long intervalMs = Math.max(1, FarmHelperConfig.autoComposterLoopIntervalMinutes) * 60_000L;
                    nextRunClock.schedule(intervalMs);
                    LogUtils.sendDebug("[Auto Composter] Scheduled next run in " + FarmHelperConfig.autoComposterLoopIntervalMinutes + " minutes.");
                }
                if (manuallyStarted) {
                    stop();
                } else {
                    stop();
                    MacroHandler.getInstance().triggerWarpGarden(true, true, false);
                    delayClock.schedule(2_500);
                }
                break;
        }
    }

    private void onTravelState() {
        if (mc.currentScreen != null) {
            KeyBindUtils.stopMovement();
            PlayerUtils.closeScreen();
            delayClock.schedule(getRandomDelay());
            return;
        }

        switch (travelState) {
            case NONE:
                if (PlayerUtils.isInBarn()) {
                    travelState = TravelState.GO_TO_COMPOSTER;
                    stuckClock.schedule(30_000L);
                } else {
                    setTravelState(TravelState.TELEPORT_TO_COMPOSTER);
                }
                break;
            case TELEPORT_TO_COMPOSTER:
                // The B key press logic has been moved to happen after CHECK_COMPOSTER determines filling is needed
                positionBeforeTp = mc.thePlayer.getPosition();
                setTravelState(TravelState.WAIT_FOR_TP);
                mc.thePlayer.sendChatMessage("/tptoplot barn");
                delayClock.schedule((long) (600 + Math.random() * 500));
                break;
            case WAIT_FOR_TP:
                if (mc.thePlayer.getDistanceSqToCenter(positionBeforeTp) < 3 || PlayerUtils.isPlayerSuffocating()) {
                    LogUtils.sendDebug("[Auto Composter] Waiting for teleportation...");
                    return;
                }
                setTravelState(TravelState.GO_TO_COMPOSTER);
                delayClock.schedule((long) (600 + Math.random() * 500));
                break;
            case GO_TO_COMPOSTER:
                if (isComposterPosSet()) {
                    if (mc.thePlayer.getDistanceSqToCenter(composterPos()) < 3) {
                        setTravelState(TravelState.END);
                        break;
                    }
                    if (!FarmHelperConfig.autoComposterTravelMethod) {
                        FlyPathFinderExecutor.getInstance().setSprinting(false);
                        FlyPathFinderExecutor.getInstance().setDontRotate(false);
                        FlyPathFinderExecutor.getInstance().findPath(new Vec3(composterPos()).subtract(0.5f, 0.1f, 0.5f), true, true);
                    } else {
                        BaritoneHandler.walkToBlockPos(composterPos());
                    }
                    setTravelState(TravelState.END);
                    break;
                }

                setTravelState(TravelState.FIND_COMPOSTER);
                stuckClock.schedule(30_000);
                break;
            case FIND_COMPOSTER:
                composter = getComposter();
                if (composter == null) {
                    if (!FlyPathFinderExecutor.getInstance().isRunning() && !FarmHelperConfig.autoComposterTravelMethod) {
                        FlyPathFinderExecutor.getInstance().setSprinting(false);
                        FlyPathFinderExecutor.getInstance().setDontRotate(false);
                        FlyPathFinderExecutor.getInstance().findPath(new Vec3(initialComposterPos).addVector(0.5f, 0.5f, 0.5f), true, true);
                    }
                    if (!BaritoneHandler.isPathing() && FarmHelperConfig.autoComposterTravelMethod) {
                        BaritoneHandler.isWalkingToGoalBlock(0.5);
                    }
                    LogUtils.sendDebug("[Auto Composter] Composter not found! Looking for it.");
                    break;
                }
                Vec3 closestVec = PlayerUtils.getClosestVecAround(composter, 1.75);
                if (closestVec == null) {
                    LogUtils.sendError("[Auto Composter] Can't find a valid position around the Composter!");
                    setMainState(MainState.END);
                    break;
                }
                FlyPathFinderExecutor.getInstance().stop();
                BaritoneHandler.stopPathing();
                BlockPos closestPos = new BlockPos(closestVec);
                FarmHelperConfig.composterX = closestPos.getX();
                FarmHelperConfig.composterY = closestPos.getY();
                FarmHelperConfig.composterZ = closestPos.getZ();
                LogUtils.sendSuccess("[Auto Composter] Found Composter! " + composter.getPosition().toString());
                setTravelState(TravelState.GO_TO_COMPOSTER);
                delayClock.schedule((long) (300 + Math.random() * 300));
                stuckClock.schedule(30_000L);
                break;
            case END:
                if (FlyPathFinderExecutor.getInstance().isRunning() || BaritoneHandler.isWalkingToGoalBlock(0.5)) {
                    break;
                }
                KeyBindUtils.stopMovement();
                if (FarmHelperConfig.autoComposterAutosellBeforeFilling) {
                    setMainState(MainState.AUTO_SELL);
                } else {
                    setMainState(MainState.COMPOSTER);
                }
                setTravelState(TravelState.NONE);
                break;
        }
    }

    public void onComposterState() {
        switch (composterState) {
            case NONE:
                setComposterState(ComposterState.ROTATE_TO_COMPOSTER);
                break;
            case ROTATE_TO_COMPOSTER:
                if (mc.currentScreen != null) {
                    KeyBindUtils.stopMovement();
                    PlayerUtils.closeScreen();
                    delayClock.schedule(getRandomDelay());
                    return;
                }
                if (composter == null) {
                    composter = getComposter();
                }
                RotationHandler.getInstance().easeTo(
                        new RotationConfiguration(
                                new Target(composter),
                                FarmHelperConfig.getRandomRotationTime(),
                                null
                        )
                );
                if (FlyPathFinderExecutor.getInstance().isRunning() || BaritoneHandler.isWalkingToGoalBlock(0.5)) {
                    break;
                }
                setComposterState(ComposterState.OPEN_COMPOSTER);
                break;
            case OPEN_COMPOSTER:
                if (mc.currentScreen != null) {
                    PlayerUtils.closeScreen();
                    delayClock.schedule(getRandomDelay());
                    return;
                }
                MovingObjectPosition mop = mc.objectMouseOver;
                if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
                    Entity entity = mop.entityHit;
                    if (entity.equals(composter) || entity.getDistanceToEntity(composter) < 1) {
                        KeyBindUtils.leftClick();
                        if (!composterChecked) {
                            setComposterState(ComposterState.CHECK_COMPOSTER);
                        } else {
                            setComposterState(ComposterState.FILL_COMPOSTER);
                        }
                        RotationHandler.getInstance().reset();
                        stuckClock.schedule(10_000L);
                        delayClock.schedule(600L + Math.random() * 400L);
                        break;
                    }
                } else {
                    if (RotationHandler.getInstance().isRotating()) break;
                    if (mc.thePlayer.getDistanceToEntity(composter) > 3) {
                        setMainState(MainState.TRAVEL);
                        setComposterState(ComposterState.NONE);
                    } else {
                        RotationHandler.getInstance().easeTo(
                                new RotationConfiguration(
                                        new Target(composter),
                                        FarmHelperConfig.getRandomRotationTime(),
                                        () -> {
                                            KeyBindUtils.onTick(mc.gameSettings.keyBindForward);
                                            RotationHandler.getInstance().reset();
                                        }
                                )
                        );
                    }
                    delayClock.schedule(1_000 + Math.random() * 500);
                }
                break;
            case CHECK_COMPOSTER:
                String invName = InventoryUtils.getInventoryName();
                if (invName == null) {
                    setComposterState(ComposterState.OPEN_COMPOSTER);
                    break;
                }
                if (invName.contains("Composter")) {
                    LogUtils.sendDebug("[Auto Composter] Checking Composter");
                    Slot organicMatterSlot = InventoryUtils.getSlotOfIdInContainer(37);
                    Slot fuelSlot = InventoryUtils.getSlotOfIdInContainer(43);
                    if (organicMatterSlot == null || fuelSlot == null) break;
                    ItemStack organicMatterItemStack = organicMatterSlot.getStack();
                    ItemStack fuelItemStack = fuelSlot.getStack();
                    if (organicMatterItemStack == null || fuelItemStack == null) break;
                    ArrayList<String> organicMatterLore = InventoryUtils.getItemLore(organicMatterItemStack);
                    ArrayList<String> fuelLore = InventoryUtils.getItemLore(fuelItemStack);

                    LogUtils.sendDebug("[Auto Composter] Checking composter resources");

                    // Parse current and max values
                    int currentMatter = -1;
                    int maxMatter = -1;
                    for (String line : organicMatterLore) {
                        Matcher matcher = composterResourcePattern.matcher(StringUtils.stripControlCodes(line).trim());
                        if (matcher.matches()) {
                            currentMatter = (int) Double.parseDouble(matcher.group(1).replace(",", ""));
                            maxMatter = Integer.parseInt(matcher.group(2)) * 1_000;
                        }
                    }
                    LogUtils.sendDebug("[Auto Composter] Organic Matter: " + currentMatter + "/" + maxMatter);

                    int currentFuel = -1;
                    int maxFuel = -1;
                    for (String line : fuelLore) {
                        Matcher matcher = composterResourcePattern.matcher(StringUtils.stripControlCodes(line).trim());
                        if (matcher.matches()) {
                            currentFuel = (int) Double.parseDouble(matcher.group(1).replace(",", ""));
                            maxFuel = Integer.parseInt(matcher.group(2)) * 1_000;
                        }
                    }
                    LogUtils.sendDebug("[Auto Composter] Fuel: " + currentFuel + "/" + maxFuel);

                    if (currentMatter == -1 || currentFuel == -1) break;

                    // If we need fuel (below threshold), have none in inventory, and buyer is enabled -> start BiofuelBuyer
                    int biofuelInInventory = InventoryUtils.getAmountOfItemInInventory("Biofuel");
                    int targetFuelCheck = Math.min(maxFuel, Math.max(0, FarmHelperConfig.autoComposterFuelLeft));
                    boolean needsFuel = currentFuel < targetFuelCheck;
                    if (needsFuel && biofuelInInventory == 0 && FarmHelperConfig.useBiofuelBuyer) {
                        int toBuy = FarmHelperConfig.getBiofuelBuyerPurchaseBatch();
                        LogUtils.sendDebug("[Auto Composter] No Biofuel in inventory; starting BiofuelBuyer for " + toBuy + "");
                        biofuelBuyer.startBuying(toBuy);
                        setComposterState(ComposterState.BUY_BIOFUEL);
                        delayClock.schedule(FarmHelperConfig.getRandomGUIMacroDelay());
                        break;
                    }

                    composterChecked = true;
                    
                    // Stay in GUI and move to direct filling from inventory
                    setComposterState(ComposterState.FILL_COMPOSTER);
                    delayClock.schedule(300);
                } else {
                    PlayerUtils.closeScreen();
                    setComposterState(ComposterState.ROTATE_TO_COMPOSTER);
                    delayClock.schedule(400 + Math.random() * 400);
                    stuckClock.schedule(10_000L);
                }
                break;
            case BUY_BIOFUEL:
                onBuyState();
                break;
            case FILL_COMPOSTER:
                String invName2 = InventoryUtils.getInventoryName();
                if (invName2 == null) {
                    setComposterState(ComposterState.OPEN_COMPOSTER);
                    break;
                }
                if (invName2.contains("Composter")) {
                    ContainerChest chest = (ContainerChest) mc.thePlayer.openContainer;
                    // Recompute current levels from GUI lore
                    Slot organicMatterSlot2 = InventoryUtils.getSlotOfIdInContainer(37);
                    Slot fuelSlot2 = InventoryUtils.getSlotOfIdInContainer(43);
                    if (organicMatterSlot2 == null || fuelSlot2 == null) break;
                    ArrayList<String> organicMatterLore2 = InventoryUtils.getItemLore(organicMatterSlot2.getStack());
                    ArrayList<String> fuelLore2 = InventoryUtils.getItemLore(fuelSlot2.getStack());
                    int currentMatter2 = -1, maxMatter2 = -1, currentFuel2 = -1, maxFuel2 = -1;
                    for (String line : organicMatterLore2) {
                        Matcher m = composterResourcePattern.matcher(StringUtils.stripControlCodes(line).trim());
                        if (m.matches()) {
                            currentMatter2 = (int) Double.parseDouble(m.group(1).replace(",", ""));
                            maxMatter2 = Integer.parseInt(m.group(2)) * 1_000;
                        }
                    }
                    for (String line : fuelLore2) {
                        Matcher m = composterResourcePattern.matcher(StringUtils.stripControlCodes(line).trim());
                        if (m.matches()) {
                            currentFuel2 = (int) Double.parseDouble(m.group(1).replace(",", ""));
                            maxFuel2 = Integer.parseInt(m.group(2)) * 1_000;
                        }
                    }

                    // Fill organic matter first, then fuel
                    boolean didAction = false;
                    int targetMatter = Math.min(maxMatter2, Math.max(0, FarmHelperConfig.autoComposterOrganicMatterLeft));
                    if (currentMatter2 < targetMatter) {
                        didAction = fillOrganicMatter(chest.windowId);
                    }

                    // Only fill fuel if organic matter is done
                    if (!didAction) {
                        int targetFuel = Math.min(maxFuel2, Math.max(0, FarmHelperConfig.autoComposterFuelLeft));
                        if (currentFuel2 < targetFuel) {
                            didAction = fillFuel(chest.windowId);
                        }
                    }

                    if (!didAction) {
                        LogUtils.sendWarning("[Auto Composter] Targets met or no matching items found. Finishing.");
                        if (FarmHelperConfig.logAutoComposterEvents) {
                            LogUtils.webhookLog("Auto Composter: Finished (targets met or no items to feed).");
                        }
                        setComposterState(ComposterState.END);
                    }
                } else {
                    PlayerUtils.closeScreen();
                    setComposterState(ComposterState.ROTATE_TO_COMPOSTER);
                    delayClock.schedule(400 + Math.random() * 400);
                    stuckClock.schedule(10_000L);
                }
                break;
            case END:
                setMainState(MainState.END);
                setComposterState(ComposterState.NONE);
                delayClock.schedule(1_800);
                break;
        }


    }

    private boolean fillOrganicMatter(int windowId) {
        int clickDelay = Math.max(0, FarmHelperConfig.autoComposterClickDelayMs);
        // Try enabled enchanted organic matter items in order
        for (String matterName : getPreferredOrganicMatterNames()) {
            int slotId = InventoryUtils.getSlotIdOfItemInContainer(matterName);
            if (slotId != -1) {
                LogUtils.sendDebug("[Auto Composter] Using " + matterName);
                InventoryUtils.clickSlotWithId(slotId, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP, windowId);
                delayClock.schedule(clickDelay);
                return true;
            }
        }
        // Fallback / optionally use Box of Seeds
        if (FarmHelperConfig.autoComposterUseBoxOfSeeds) {
            int bosSlot = InventoryUtils.getSlotIdOfItemInContainer("Box of Seeds");
            if (bosSlot != -1) {
                LogUtils.sendDebug("[Auto Composter] Using Box of Seeds");
                InventoryUtils.clickSlotWithId(bosSlot, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP, windowId);
                delayClock.schedule(clickDelay);
                return true;
            }
        }
        return false;
    }

    private boolean fillFuel(int windowId) {
        int fuelSlotId = InventoryUtils.getSlotIdOfItemInContainer("Biofuel");
        int fuelDelay = Math.max(0, FarmHelperConfig.autoComposterFuelClickDelayMs);
        if (fuelSlotId != -1) {
            LogUtils.sendDebug("[Auto Composter] Feeding Biofuel");
            InventoryUtils.clickSlotWithId(fuelSlotId, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP, windowId);
            delayClock.schedule(fuelDelay);
            return true;
        }
        return false;
    }

    private long getRandomDelay() {
        return (long) (500 + Math.random() * 500);
    }

    private java.util.List<String> getPreferredOrganicMatterNames() {
        java.util.LinkedHashMap<String, Boolean> enabled = new java.util.LinkedHashMap<>();
        enabled.put("Enchanted Wheat", FarmHelperConfig.autoComposterUseEnchantedWheat);
        enabled.put("Enchanted Cactus", FarmHelperConfig.autoComposterUseEnchantedCactus);
        enabled.put("Enchanted Carrot", FarmHelperConfig.autoComposterUseEnchantedCarrot);
        enabled.put("Enchanted Feather", FarmHelperConfig.autoComposterUseEnchantedFeather);
        enabled.put("Enchanted Cocoa Beans", FarmHelperConfig.autoComposterUseEnchantedCocoaBeans);
        enabled.put("Enchanted Leather", FarmHelperConfig.autoComposterUseEnchantedLeather);
        enabled.put("Enchanted Melon", FarmHelperConfig.autoComposterUseEnchantedMelon);
        enabled.put("Enchanted Red Mushroom", FarmHelperConfig.autoComposterUseEnchantedRedMushroom);
        enabled.put("Enchanted Mutton", FarmHelperConfig.autoComposterUseEnchantedMutton);
        enabled.put("Enchanted Nether Wart", FarmHelperConfig.autoComposterUseEnchantedNetherWart);
        enabled.put("Enchanted Pork", FarmHelperConfig.autoComposterUseEnchantedPork);
        enabled.put("Enchanted Potato", FarmHelperConfig.autoComposterUseEnchantedPotato);
        enabled.put("Enchanted Pumpkin", FarmHelperConfig.autoComposterUseEnchantedPumpkin);
        enabled.put("Enchanted Rabbit", FarmHelperConfig.autoComposterUseEnchantedRabbit);
        enabled.put("Enchanted Chicken", FarmHelperConfig.autoComposterUseEnchantedChicken);
        enabled.put("Enchanted Seeds", FarmHelperConfig.autoComposterUseEnchantedSeeds);
        enabled.put("Enchanted Sugar Cane", FarmHelperConfig.autoComposterUseEnchantedSugarCane);

        java.util.List<String> result = new java.util.ArrayList<>();
        // Priority order from config
        if (FarmHelperConfig.autoComposterOrganicPriority != null && !FarmHelperConfig.autoComposterOrganicPriority.trim().isEmpty()) {
            String[] tokens = FarmHelperConfig.autoComposterOrganicPriority.split("\\|");
            for (String t : tokens) {
                String key = t.trim();
                if (enabled.containsKey(key) && enabled.get(key) && !result.contains(key)) {
                    result.add(key);
                }
            }
        }
        // Add remaining enabled items not already in result
        for (java.util.Map.Entry<String, Boolean> e : enabled.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue()) && !result.contains(e.getKey())) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    enum MainState {
        NONE,
        TRAVEL,
        AUTO_SELL,
        COMPOSTER,
        END
    }

    private void setMainState(AutoComposter.MainState state) {
        mainState = state;
        LogUtils.sendDebug("[Auto Composter] Main state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    enum TravelState {
        NONE,
        TELEPORT_TO_COMPOSTER,
        WAIT_FOR_TP,
        GO_TO_COMPOSTER,
        FIND_COMPOSTER,
        END
    }

    private void setTravelState(AutoComposter.TravelState state) {
        travelState = state;
        LogUtils.sendDebug("[Auto Composter] Travel state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    enum ComposterState {
        NONE,
        ROTATE_TO_COMPOSTER,
        OPEN_COMPOSTER,
        CHECK_COMPOSTER,
        BUY_BIOFUEL,
        FILL_COMPOSTER,
        END
    }

    private void setComposterState(AutoComposter.ComposterState state) {
        composterState = state;
        LogUtils.sendDebug("[Auto Composter] Composter state: " + state.name());
        stuckClock.schedule(STUCK_DELAY);
    }

    /**
     * Presses the B key using Minecraft's key binding system to simulate normal keypress.
     * This method directly uses Minecraft's input system like a normal person would press B.
     */
    /**
     * Ensures Minecraft window has focus and properly ungrabs mouse before sending Robot key presses.
     * This prevents keys from being sent to other applications and handles mouse cursor properly.
     */
    /**
     * Types a command using Minecraft's input system to simulate normal typing.
     * This sends the command exactly like a normal person would type it.
     * No threading or delays - executes immediately.
     */
    private void typeCommand(String command) {
        try {
            LogUtils.sendDebug("[Auto Composter] Starting to type command like a normal person: " + command);
            
            // Make sure we're in-game and not in a screen
            if (mc.currentScreen != null) {
                LogUtils.sendDebug("[Auto Composter] Closing current screen first");
                mc.thePlayer.closeScreen();
            }
            
            // Open chat the normal way (like pressing T or /)
            LogUtils.sendDebug("[Auto Composter] Opening chat normally...");
            mc.displayGuiScreen(new net.minecraft.client.gui.GuiChat());
            
            // Check if chat opened immediately
            if (mc.currentScreen instanceof net.minecraft.client.gui.GuiChat) {
                LogUtils.sendDebug("[Auto Composter] Chat opened successfully");
                net.minecraft.client.gui.GuiChat chatGui = (net.minecraft.client.gui.GuiChat) mc.currentScreen;
                
                // Use reflection to access the input field (it might be named differently in 1.8.9)
                try {
                    LogUtils.sendDebug("[Auto Composter] Accessing chat input field using reflection...");
                    
                    // Try common field names for the input field
                    java.lang.reflect.Field inputField = null;
                    String[] possibleNames = {"inputField", "textField", "field_146415_a", "defaultInputFieldText"};
                    
                    for (String fieldName : possibleNames) {
                        try {
                            inputField = net.minecraft.client.gui.GuiChat.class.getDeclaredField(fieldName);
                            LogUtils.sendDebug("[Auto Composter] Found input field: " + fieldName);
                            break;
                        } catch (NoSuchFieldException e) {
                            LogUtils.sendDebug("[Auto Composter] Field '" + fieldName + "' not found, trying next...");
                        }
                    }
                    
                    if (inputField != null) {
                        inputField.setAccessible(true);
                        net.minecraft.client.gui.GuiTextField textField = (net.minecraft.client.gui.GuiTextField) inputField.get(chatGui);
                        
                        if (textField != null) {
                            // Type the command directly into the input field
                            LogUtils.sendDebug("[Auto Composter] Typing command into input field: " + command);
                            textField.setText(command);
                            
                            // Send the command by calling the chat's method
                            LogUtils.sendDebug("[Auto Composter] Sending command...");
                            chatGui.sendChatMessage(command);
                            
                            LogUtils.sendSuccess("[Auto Composter] Successfully sent command: " + command);
                        } else {
                            LogUtils.sendError("[Auto Composter] Input field is null");
                            // Fallback: use direct sendChatMessage
                            mc.thePlayer.sendChatMessage(command);
                            LogUtils.sendSuccess("[Auto Composter] Successfully sent command using fallback: " + command);
                        }
                    } else {
                        LogUtils.sendError("[Auto Composter] Could not find input field in GuiChat");
                        // Fallback: use direct sendChatMessage
                        mc.thePlayer.sendChatMessage(command);
                        LogUtils.sendSuccess("[Auto Composter] Successfully sent command using fallback: " + command);
                    }
                } catch (Exception e) {
                    LogUtils.sendError("[Auto Composter] Reflection failed: " + e.getMessage());
                    // Fallback: just use sendChatMessage directly
                    LogUtils.sendDebug("[Auto Composter] Using fallback method - direct sendChatMessage");
                    mc.thePlayer.sendChatMessage(command);
                    LogUtils.sendSuccess("[Auto Composter] Successfully sent command using fallback: " + command);
                }
                
                // Close chat
                mc.thePlayer.closeScreen();
                
            } else {
                LogUtils.sendError("[Auto Composter] Failed to open chat");
                // Fallback: use sendChatMessage directly
                LogUtils.sendDebug("[Auto Composter] Using direct sendChatMessage as fallback");
                mc.thePlayer.sendChatMessage(command);
                LogUtils.sendSuccess("[Auto Composter] Successfully sent command using direct method: " + command);
            }
            
        } catch (Exception e) {
            LogUtils.sendError("[Auto Composter] Failed to type command: " + command + " - " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Types a single character using Robot
     */
}
