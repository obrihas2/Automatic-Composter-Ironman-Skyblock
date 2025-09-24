package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.util.InventoryUtils;
import com.jelly.farmhelperv2.util.KeyBindUtils;
import com.jelly.farmhelperv2.util.LogUtils;
import com.jelly.farmhelperv2.util.PlayerUtils;
import com.jelly.farmhelperv2.util.helper.Clock;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.ContainerChest;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Handles purchasing Biofuel through the SkyMart system (/desk → SkyMart → Farming Essentials → Biofuel)
 */
public class BiofuelBuyer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    @Getter
    private BuyState buyState = BuyState.NONE;
    private final Clock delayClock = new Clock();
    private int fuelAmountToBuy = 0;
    private boolean enabled = false;
    
    // Enhanced debug tracking
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private long sessionStartTime = 0;
    private long stateStartTime = 0;
    private long lastDebugTime = 0;
    private String lastInventoryName = "";
    private int tickCount = 0;
    private int debugLevel = 1; // 0=minimal, 1=normal, 2=verbose, 3=extreme
    private int initialBiofuelCount = 0;
    private int purchaseRetries = 0;
    private BuyState lastFinalState = BuyState.NONE;
    
    public enum BuyState {
        NONE,
        OPEN_DESK,
        WAIT_FOR_DESK,
        OPEN_SKYMART,
        WAIT_FOR_SKYMART,
        OPEN_FARMING_ESSENTIALS,
        WAIT_FOR_FARMING_ESSENTIALS,
        BUY_BIOFUEL,
        WAIT_FOR_BIOFUEL_MENU,
        SELECT_BIOFUEL_BATCH,
        CLICK_CONFIRM,
        CONFIRM_PURCHASE,
        END,
        FAILED
    }
    
    // ========================= DEBUG HELPER METHODS =========================
    
    private void debugLog(int level, String message) {
        if (debugLevel >= level && FarmHelperConfig.biofuelBuyerDebugLogging) {
            long currentTime = System.currentTimeMillis();
            String timestamp = timeFormat.format(new Date(currentTime));
            long elapsedSession = sessionStartTime > 0 ? currentTime - sessionStartTime : 0;
            long elapsedState = stateStartTime > 0 ? currentTime - stateStartTime : 0;
            long sinceLastDebug = lastDebugTime > 0 ? currentTime - lastDebugTime : 0;
            
            String debugMsg = String.format("[BiofuelBuyer][%s][T+%dms][S+%dms][Δ%dms][Tick#%d] %s", 
                timestamp, elapsedSession, elapsedState, sinceLastDebug, tickCount, message);
            
            LogUtils.sendDebug(debugMsg);
            lastDebugTime = currentTime;
        }
    }
    
    private void debugInventoryState() {
        if (debugLevel >= 2) {
            String invName = InventoryUtils.getInventoryName();
            boolean hasChanged = !lastInventoryName.equals(invName != null ? invName : "null");
            
            if (hasChanged) {
                debugLog(2, "GUI Change: '" + lastInventoryName + "' → '" + invName + "'");
                lastInventoryName = invName != null ? invName : "null";
            }
            
            if (debugLevel >= 3) {
                boolean isOpen = mc.currentScreen != null;
                debugLog(3, "Inventory Status: Open=" + isOpen + ", Name='" + invName + "'");
            }
        }
    }
    
    private void debugDumpInventory(String context) {
        // Extra diagnostic dump of current GUI contents
        if (!FarmHelperConfig.logGUIDetectionIssues && debugLevel < 3) return;
        String invName = InventoryUtils.getInventoryName();
        debugLog(2, String.format("%s: GUI='%s'", context, invName));
        if (mc.thePlayer != null && mc.thePlayer.openContainer instanceof ContainerChest) {
            ContainerChest chest = (ContainerChest) mc.thePlayer.openContainer;
            for (Object o : chest.inventorySlots) {
                if (!(o instanceof Slot)) continue;
                Slot s = (Slot) o;
                if (s.getHasStack()) {
                    String name = s.getStack().getDisplayName();
                    int count = s.getStack().stackSize;
                    debugLog(3, String.format("  Slot#%d: '%s' x%d", s.slotNumber, name, count));
                }
            }
        }
    }
    
    private void debugClickAction(String action, Slot slot, InventoryUtils.ClickType clickType) {
        if (debugLevel >= 2) {
            String itemName = slot != null && slot.getHasStack() ? slot.getStack().getDisplayName() : "empty";
            debugLog(2, String.format("%s: Slot#%d ('%s') with %s", 
                action, slot != null ? slot.slotNumber : -1, itemName, clickType.name()));
        }
    }
    
    private void debugDelaySchedule(String reason, long delayMs) {
        if (debugLevel >= 2) {
            debugLog(2, String.format("Delay Scheduled: %dms for %s", delayMs, reason));
        }
    }
    
    private void debugStateTransition(BuyState oldState, BuyState newState, String reason) {
        long currentTime = System.currentTimeMillis();
        long stateTime = stateStartTime > 0 ? currentTime - stateStartTime : 0;
        
        debugLog(1, String.format("State Transition: %s → %s (%s) [Duration: %dms]", 
            oldState.name(), newState.name(), reason, stateTime));
        
        stateStartTime = currentTime;
    }
    
    private void debugBiofuelInventory(String context) {
        if (debugLevel >= 2) {
            int currentCount = InventoryUtils.getAmountOfItemInInventory("Biofuel");
            int gained = currentCount - initialBiofuelCount;
            debugLog(2, String.format("%s: Biofuel inventory = %d (gained %d from start)", 
                context, currentCount, gained));
        }
    }
    
    // ========================= END DEBUG METHODS =========================
    
    /**
     * Starts the Biofuel buying process
     * @param amountToBuy How many Biofuel items to purchase
     */
    public void startBuying(int amountToBuy) {
        if (enabled) {
            debugLog(1, "Start request rejected - already running (State: " + buyState.name() + ")");
            return;
        }
        
        // Initialize debug tracking
        sessionStartTime = System.currentTimeMillis();
        stateStartTime = sessionStartTime;
        lastDebugTime = sessionStartTime;
        tickCount = 0;
        initialBiofuelCount = InventoryUtils.getAmountOfItemInInventory("Biofuel");
        lastInventoryName = "";
        
        debugLog(1, "=== STARTING BIOFUEL PURCHASE SESSION ===");
        debugLog(1, String.format("Session ID: %d, Target: %d Biofuel, Current: %d", 
            sessionStartTime, amountToBuy, initialBiofuelCount));
        
        this.fuelAmountToBuy = amountToBuy;
        BuyState oldState = this.buyState;
        this.buyState = BuyState.OPEN_DESK;
        this.enabled = true;
        this.delayClock.reset();
        this.purchaseRetries = 0;
        
        debugStateTransition(oldState, BuyState.OPEN_DESK, "session initialization");
    }
    
    /**
     * Stops the buying process
     */
    public void stop() {
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] Stopping - State: " + buyState.name());
        }
        this.enabled = false;
        this.lastFinalState = this.buyState;
        this.buyState = BuyState.NONE;
        this.delayClock.reset();
        PlayerUtils.closeScreen();
    }
    
    /**
     * Main tick method to be called from AutoComposter
     * @return true if buying is complete (success or failure)
     */
    public boolean tick() {
        if (!enabled) return true;
        
        // Wait for delay if scheduled
        if (delayClock.isScheduled() && !delayClock.passed()) {
            return false;
        }
        
        switch (buyState) {
            case OPEN_DESK:
                handleOpenDesk();
                break;
            case WAIT_FOR_DESK:
                handleWaitForDesk();
                break;
            case OPEN_SKYMART:
                handleOpenSkyMart();
                break;
            case WAIT_FOR_SKYMART:
                handleWaitForSkyMart();
                break;
            case OPEN_FARMING_ESSENTIALS:
                handleOpenFarmingEssentials();
                break;
            case WAIT_FOR_FARMING_ESSENTIALS:
                handleWaitForFarmingEssentials();
                break;
            case BUY_BIOFUEL:
                handleBuyBiofuel();
                break;
            case WAIT_FOR_BIOFUEL_MENU:
                handleWaitForBiofuelMenu();
                break;
            case SELECT_BIOFUEL_BATCH:
                handleSelectBiofuelBatch();
                break;
            case CLICK_CONFIRM:
                handleClickConfirm();
                break;
            case CONFIRM_PURCHASE:
                handleConfirmPurchase();
                break;
            case END:
                LogUtils.sendDebug("[BiofuelBuyer] Purchase completed successfully");
                stop();
                return true;
            case FAILED:
                LogUtils.sendError("[BiofuelBuyer] Purchase failed");
                stop();
                return true;
        }
        
        return false;
    }
    
    private void handleOpenDesk() {
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] Opening desk with /desk command");
        }
        
        // Close any open screens first
        if (mc.currentScreen != null) {
            PlayerUtils.closeScreen();
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
            return;
        }
        
        KeyBindUtils.stopMovement();
        mc.thePlayer.sendChatMessage("/desk");
        setBuyState(BuyState.WAIT_FOR_DESK);
        scheduleDelay(FarmHelperConfig.biofuelBuyerTimeout); // Wait for desk to open
    }
    
    private void handleWaitForDesk() {
        String invName = InventoryUtils.getInventoryName();
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] Waiting for desk - Current GUI: " + invName);
        }
        
        if (invName != null && invName.equals("Desk")) {
            if (FarmHelperConfig.biofuelBuyerDebugLogging) {
                LogUtils.sendDebug("[BiofuelBuyer] Desk opened successfully");
            }
            setBuyState(BuyState.OPEN_SKYMART);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else {
            // Timeout check - if we've been waiting too long, fail
            if (!delayClock.isScheduled()) {
                LogUtils.sendError("[BiofuelBuyer] Failed to open desk - timeout");
                setBuyState(BuyState.FAILED);
            }
        }
    }
    
    private void handleOpenSkyMart() {
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] Looking for SkyMart in Desk");
        }
        
        Slot skymartSlot = InventoryUtils.getSlotOfItemInContainer("SkyMart");
        if (skymartSlot == null) {
            LogUtils.sendError("[BiofuelBuyer] Cannot find SkyMart button in Desk");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] Clicking SkyMart at slot " + skymartSlot.slotNumber);
        }
        InventoryUtils.clickContainerSlot(skymartSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        setBuyState(BuyState.WAIT_FOR_SKYMART);
        scheduleDelay(FarmHelperConfig.biofuelBuyerTimeout);
    }
    
    private void handleWaitForSkyMart() {
        String invName = InventoryUtils.getInventoryName();
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] Waiting for SkyMart - Current GUI: " + invName);
        }
        
        if (invName != null && invName.equals("SkyMart")) {
            LogUtils.sendDebug("[BiofuelBuyer] SkyMart opened successfully");
            setBuyState(BuyState.OPEN_FARMING_ESSENTIALS);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else if (!delayClock.isScheduled()) {
            LogUtils.sendError("[BiofuelBuyer] Failed to open SkyMart - timeout");
            setBuyState(BuyState.FAILED);
        }
    }
    
    private void handleOpenFarmingEssentials() {
        LogUtils.sendDebug("[BiofuelBuyer] Looking for Farming Essentials in SkyMart");
        
        // Look for Diamond Hoe or "Farming Essentials" text
        Slot farmingEssentialsSlot = InventoryUtils.getSlotOfItemInContainer("Diamond Hoe");
        if (farmingEssentialsSlot == null) {
            farmingEssentialsSlot = InventoryUtils.getSlotOfItemInContainer("Farming Essentials");
        }
        
        if (farmingEssentialsSlot == null) {
            LogUtils.sendError("[BiofuelBuyer] Cannot find Farming Essentials (Diamond Hoe) in SkyMart");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        LogUtils.sendDebug("[BiofuelBuyer] Clicking Farming Essentials at slot " + farmingEssentialsSlot.slotNumber);
        InventoryUtils.clickContainerSlot(farmingEssentialsSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        setBuyState(BuyState.WAIT_FOR_FARMING_ESSENTIALS);
        scheduleDelay(FarmHelperConfig.biofuelBuyerTimeout);
    }
    
    private void handleWaitForFarmingEssentials() {
        String invName = InventoryUtils.getInventoryName();
        LogUtils.sendDebug("[BiofuelBuyer] Waiting for Farming Essentials - Current GUI: " + invName);
        
        // The GUI name might contain "Farming" or "Essentials"
        if (invName != null && (invName.contains("Farming") || invName.contains("Essentials"))) {
            LogUtils.sendDebug("[BiofuelBuyer] Farming Essentials opened successfully");
            setBuyState(BuyState.BUY_BIOFUEL);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else if (!delayClock.isScheduled()) {
            LogUtils.sendError("[BiofuelBuyer] Failed to open Farming Essentials - timeout");
            setBuyState(BuyState.FAILED);
        }
    }
    
    private void handleBuyBiofuel() {
        LogUtils.sendDebug("[BiofuelBuyer] Looking for Biofuel in Farming Essentials");
        
        // Look for green dye (Biofuel)
        Slot biofuelSlot = InventoryUtils.getSlotOfItemInContainer("Biofuel");
        if (biofuelSlot == null) {
            // Try looking for "Green Dye" as alternative
            biofuelSlot = InventoryUtils.getSlotOfItemInContainer("Green Dye");
        }
        
        if (biofuelSlot == null) {
            LogUtils.sendError("[BiofuelBuyer] Cannot find Biofuel in Farming Essentials");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        LogUtils.sendDebug("[BiofuelBuyer] Opening Biofuel purchase menu via right-click at slot " + biofuelSlot.slotNumber);
        
        // Right-click to open purchase menu
        InventoryUtils.clickContainerSlot(biofuelSlot.slotNumber, InventoryUtils.ClickType.RIGHT, InventoryUtils.ClickMode.PICKUP);
        
        // Wait for batch menu to appear
        setBuyState(BuyState.WAIT_FOR_BIOFUEL_MENU);
        scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
    }
    
    private void handleConfirmPurchase() {
        LogUtils.sendDebug("[BiofuelBuyer] Confirming purchase completion");
        
        // Check if we have Biofuel in our inventory now
        int biofuelInInventory = InventoryUtils.getAmountOfItemInInventory("Biofuel");
        LogUtils.sendDebug("[BiofuelBuyer] Biofuel in inventory: " + biofuelInInventory);
        
        int gained = biofuelInInventory - initialBiofuelCount;
        if (gained >= fuelAmountToBuy) {
            LogUtils.sendSuccess("[BiofuelBuyer] Successfully purchased at least " + fuelAmountToBuy + " Biofuel (gained=" + gained + ")");
            setBuyState(BuyState.END);
            scheduleDelay(500);
        } else {
            if (purchaseRetries < 2) {
                purchaseRetries++;
                LogUtils.sendWarning("[BiofuelBuyer] Biofuel not detected after purchase (gained=" + gained + "). Retrying (" + purchaseRetries + "/2)...");
                setBuyState(BuyState.BUY_BIOFUEL);
                scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
            } else {
                LogUtils.sendError("[BiofuelBuyer] Purchase failed after retries (gained=" + gained + ")");
                setBuyState(BuyState.FAILED);
                scheduleDelay(500);
            }
        }
    }
    
    private void handleWaitForBiofuelMenu() {
        // Dump inventory to help diagnose menu content
        debugDumpInventory("Waiting for Biofuel menu");
        String invName = InventoryUtils.getInventoryName();
        debugLog(2, "Waiting for Biofuel menu - Current GUI: '" + invName + "'");
        boolean matches = false;
        if (FarmHelperConfig.biofuelBuyerMenuNameOverride != null && !FarmHelperConfig.biofuelBuyerMenuNameOverride.trim().isEmpty()) {
            matches = invName != null && invName.contains(FarmHelperConfig.biofuelBuyerMenuNameOverride.trim());
        }
        // Fallback: detect by content (presence of Biofuel stacks)
        if (!matches && mc.thePlayer != null && mc.thePlayer.openContainer instanceof ContainerChest) {
            ContainerChest chest = (ContainerChest) mc.thePlayer.openContainer;
            for (Object o : chest.inventorySlots) {
                if (!(o instanceof Slot)) continue;
                Slot s = (Slot) o;
                if (s.getHasStack()) {
                    String name = s.getStack().getDisplayName();
                    if (name.contains("Biofuel") || name.contains("Green Dye")) {
                        matches = true;
                        break;
                    }
                }
            }
        }
        if (!matches) {
            // Another fallback: if any GUI is open after right-click, consider it opened
            matches = mc.currentScreen != null && invName != null;
        }
        if (matches) {
            debugLog(1, "✓ Biofuel batch menu opened");
            setBuyState(BuyState.SELECT_BIOFUEL_BATCH);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else if (!delayClock.isScheduled()) {
            debugLog(1, "✗ FAILED: Biofuel menu did not open in time");
            setBuyState(BuyState.FAILED);
        }
    }

    private void handleSelectBiofuelBatch() {
        debugDumpInventory("Selecting Biofuel batch");
        int batch = FarmHelperConfig.getBiofuelBuyerPurchaseBatch();
        // Prefer selecting by stack size in the menu (stacks represent quantity)
        Slot batchSlot = null;
        if (mc.thePlayer != null && mc.thePlayer.openContainer instanceof ContainerChest) {
            ContainerChest chest = (ContainerChest) mc.thePlayer.openContainer;
            for (Object o : chest.inventorySlots) {
                if (!(o instanceof Slot)) continue;
                Slot s = (Slot) o;
                if (s.getHasStack()) {
                    String name = s.getStack().getDisplayName();
                    int count = s.getStack().stackSize;
                    if ((name.contains("Biofuel") || name.contains("Green Dye")) && count == batch) {
                        batchSlot = s;
                        break;
                    }
                }
            }
        }
        // Fallback to label matching
        if (batchSlot == null) {
            if (FarmHelperConfig.biofuelBuyerBatchLabelOverride != null && !FarmHelperConfig.biofuelBuyerBatchLabelOverride.trim().isEmpty()) {
                batchSlot = InventoryUtils.getSlotOfItemInContainer(FarmHelperConfig.biofuelBuyerBatchLabelOverride.trim());
            }
        }
        if (batchSlot == null) {
            String[] candidates = new String[] {
                "x" + batch,
                batch + "x",
                "Buy " + batch,
                String.valueOf(batch)
            };
            for (String cand : candidates) {
                batchSlot = InventoryUtils.getSlotOfItemInContainer(cand);
                if (batchSlot != null) break;
            }
        }
        if (batchSlot == null) {
            LogUtils.sendError("[BiofuelBuyer] Cannot find batch selection for Biofuel (" + batch + ")");
            setBuyState(BuyState.FAILED);
            return;
        }
        LogUtils.sendDebug("[BiofuelBuyer] Selecting Biofuel batch by stack size '" + batch + "' at slot " + batchSlot.slotNumber);
        InventoryUtils.clickContainerSlot(batchSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        // Some menus might require confirm; try to find confirm, otherwise move to confirmation check
        Slot confirm = null;
        if (FarmHelperConfig.biofuelBuyerConfirmLabelOverride != null && !FarmHelperConfig.biofuelBuyerConfirmLabelOverride.trim().isEmpty()) {
            confirm = InventoryUtils.getSlotOfItemInContainer(FarmHelperConfig.biofuelBuyerConfirmLabelOverride.trim());
        }
        if (confirm == null) {
            String[] names = new String[]{"Confirm", "Purchase", "Buy"};
            for (String n : names) {
                confirm = InventoryUtils.getSlotOfItemInContainer(n);
                if (confirm != null) break;
            }
        }
        if (confirm != null) {
            setBuyState(BuyState.CLICK_CONFIRM);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else {
            setBuyState(BuyState.CONFIRM_PURCHASE);
            scheduleDelay(1000);
        }
    }

    private void handleClickConfirm() {
        // Try explicit override first
        Slot confirm = null;
        if (FarmHelperConfig.biofuelBuyerConfirmLabelOverride != null && !FarmHelperConfig.biofuelBuyerConfirmLabelOverride.trim().isEmpty()) {
            confirm = InventoryUtils.getSlotOfItemInContainer(FarmHelperConfig.biofuelBuyerConfirmLabelOverride.trim());
        }
        if (confirm == null) {
            String[] names = new String[]{"Confirm", "Purchase", "Buy"};
            for (String n : names) {
                confirm = InventoryUtils.getSlotOfItemInContainer(n);
                if (confirm != null) break;
            }
        }
        if (confirm == null) {
            debugLog(1, "✗ FAILED: Cannot find confirm button in Biofuel menu");
            setBuyState(BuyState.FAILED);
            return;
        }
        debugClickAction("Click Confirm", confirm, InventoryUtils.ClickType.LEFT);
        InventoryUtils.clickContainerSlot(confirm.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        setBuyState(BuyState.CONFIRM_PURCHASE);
        scheduleDelay(1000);
    }
    
    private void setBuyState(BuyState newState) {
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] State transition: " + buyState.name() + " → " + newState.name());
        }
        this.buyState = newState;
    }
    
    private void scheduleDelay(long ms) {
        long totalDelay = ms + FarmHelperConfig.biofuelBuyerGUIDelay;
        if (FarmHelperConfig.biofuelBuyerDebugLogging) {
            LogUtils.sendDebug("[BiofuelBuyer] Scheduling delay: " + totalDelay + "ms (base: " + ms + "ms + config: " + FarmHelperConfig.biofuelBuyerGUIDelay + "ms)");
        }
        this.delayClock.schedule(totalDelay);
    }
    
    /**
     * @return true if the buying process is currently running
     */
    public boolean isRunning() {
        return enabled && buyState != BuyState.NONE;
    }
    
    /**
     * @return true if the buying process completed successfully
     */
    public boolean hasSucceeded() {
        return !enabled && (lastFinalState == BuyState.END);
    }
    
    /**
     * @return true if the buying process failed
     */
    public boolean hasFailed() {
        return lastFinalState == BuyState.FAILED || buyState == BuyState.FAILED;
    }
}