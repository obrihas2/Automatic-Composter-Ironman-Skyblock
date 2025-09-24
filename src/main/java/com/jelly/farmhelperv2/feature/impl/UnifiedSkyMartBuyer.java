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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Unified SkyMart buyer that can purchase multiple items in a single session
 * More efficient than separate buyers as it navigates once and buys everything needed
 */
public class UnifiedSkyMartBuyer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    @Getter
    private BuyState buyState = BuyState.NONE;
    private final Clock delayClock = new Clock();
    private final Clock retryDelay = new Clock();
    private boolean enabled = false;
    
    // Shopping list: item name -> amount needed
    private final Map<String, Integer> shoppingList = new HashMap<>();
    private final Map<String, Integer> purchaseResults = new HashMap<>();
    private final List<String> currentPurchaseQueue = new ArrayList<>();
    private int retryCount = 0;
    private final int MAX_RETRIES = 3;
    
    // Debug timing and context tracking
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private long sessionStartTime = 0;
    private long stateStartTime = 0;
    private long lastDebugTime = 0;
    private String lastInventoryName = "";
    private int tickCount = 0;
    // Debug level is now managed by FarmHelperConfig
    
    public enum BuyState {
        NONE,
        OPEN_DESK,
        WAIT_FOR_DESK,
        OPEN_SKYMART,
        WAIT_FOR_SKYMART,
        OPEN_FARMING_ESSENTIALS,
        WAIT_FOR_FARMING_ESSENTIALS,
        PURCHASE_ITEMS,
        CONFIRM_PURCHASES,
        RETRY_FAILED_ITEMS,
        END,
        FAILED
    }
    
    public static class PurchaseRequest {
        public final String itemName;
        public final int amount;
        public final String[] alternativeNames;
        
        public PurchaseRequest(String itemName, int amount, String... alternativeNames) {
            this.itemName = itemName;
            this.amount = amount;
            this.alternativeNames = alternativeNames;
        }
    }
    
    // ========================= DEBUG HELPER METHODS =========================
    
    private void debugLog(int level, String message) {
        int effectiveLevel = FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel();
        if (effectiveLevel >= level) {
            long currentTime = System.currentTimeMillis();
            String timestamp = timeFormat.format(new Date(currentTime));
            long elapsedSession = sessionStartTime > 0 ? currentTime - sessionStartTime : 0;
            long elapsedState = stateStartTime > 0 ? currentTime - stateStartTime : 0;
            long sinceLastDebug = lastDebugTime > 0 ? currentTime - lastDebugTime : 0;
            
            String debugMsg = String.format("[UnifiedSkyMartBuyer][%s][T+%dms][S+%dms][Δ%dms][Tick#%d] %s", 
                timestamp, elapsedSession, elapsedState, sinceLastDebug, tickCount, message);
            
            LogUtils.sendDebug(debugMsg);
            lastDebugTime = currentTime;
        }
    }
    
    private void debugInventoryState() {
        int effectiveLevel = FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel();
        if (effectiveLevel >= 2) {
            String invName = InventoryUtils.getInventoryName();
            boolean hasChanged = !lastInventoryName.equals(invName != null ? invName : "null");
            
            if (hasChanged) {
                debugLog(2, "GUI Change: '" + lastInventoryName + "' → '" + invName + "'");
                lastInventoryName = invName != null ? invName : "null";
            }
            
            if (effectiveLevel >= 3) {
                boolean isOpen = mc.currentScreen != null;
                debugLog(3, "Inventory Status: Open=" + isOpen + ", Name='" + invName + "'");
            }
        }
    }
    
    private void debugClickAction(String action, Slot slot, InventoryUtils.ClickType clickType) {
        if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2) {
            String itemName = slot != null && slot.getHasStack() ? slot.getStack().getDisplayName() : "empty";
            debugLog(2, String.format("%s: Slot#%d ('%s') with %s", 
                action, slot != null ? slot.slotNumber : -1, itemName, clickType.name()));
        }
    }
    
    private void debugDelaySchedule(String reason, long delayMs) {
        if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2) {
            debugLog(2, String.format("Delay Scheduled: %dms for %s", delayMs, reason));
        }
    }
    
    private void debugStateTransition(BuyState oldState, BuyState newState, String reason) {
        long currentTime = System.currentTimeMillis();
        long stateTime = stateStartTime > 0 ? currentTime - stateStartTime : 0;
        
        debugLog(1, String.format("State Transition: %s → %s (%s) [State Duration: %dms]", 
            oldState.name(), newState.name(), reason, stateTime));
        
        stateStartTime = currentTime;
    }
    
    private void debugShoppingProgress() {
        int effectiveLevel = FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel();
        if (effectiveLevel >= 2) {
            int totalItems = shoppingList.size();
            int completedItems = purchaseResults.size();
            int queuedItems = currentPurchaseQueue.size();
            
            debugLog(2, String.format("Shopping Progress: %d/%d items complete, %d in queue", 
                completedItems, totalItems, queuedItems));
                
            if (effectiveLevel >= 3) {
                for (String item : currentPurchaseQueue) {
                    int needed = shoppingList.getOrDefault(item, 0);
                    debugLog(3, String.format("  Queue: %s x%d", item, needed));
                }
            }
        }
    }
    
    // ========================= END DEBUG METHODS =========================
    
    /**
     * Starts a unified buying session with multiple items
     * @param requests List of items to purchase
     */
    public void startBuyingSession(List<PurchaseRequest> requests) {
        if (enabled) {
            debugLog(1, "Session start rejected - already running (State: " + buyState.name() + ")");
            return;
        }
        
        // Initialize debug timing
        sessionStartTime = System.currentTimeMillis();
        stateStartTime = sessionStartTime;
        lastDebugTime = sessionStartTime;
        tickCount = 0;
        
        debugLog(1, "=== STARTING UNIFIED PURCHASE SESSION ===");
        debugLog(1, "Session ID: " + sessionStartTime + ", Items: " + requests.size());
        
        shoppingList.clear();
        purchaseResults.clear();
        currentPurchaseQueue.clear();
        retryCount = 0;
        lastInventoryName = "";
        
        int totalCost = 0;
        for (PurchaseRequest request : requests) {
            shoppingList.put(request.itemName, request.amount);
            currentPurchaseQueue.add(request.itemName);
            totalCost += request.amount; // Simplified cost calculation
            debugLog(1, String.format("Added to shopping list: %s x%d (alternatives: %s)", 
                request.itemName, request.amount, 
                request.alternativeNames.length > 0 ? String.join(", ", request.alternativeNames) : "none"));
        }
        
        debugLog(1, String.format("Shopping list finalized: %d unique items, %d total purchases", 
            shoppingList.size(), totalCost));
        
        BuyState oldState = this.buyState;
        this.buyState = BuyState.OPEN_DESK;
        this.enabled = true;
        this.delayClock.reset();
        
        debugStateTransition(oldState, BuyState.OPEN_DESK, "session initialization");
        debugShoppingProgress();
    }
    
    /**
     * Stops the buying process
     */
    public void stop() {
        long sessionDuration = sessionStartTime > 0 ? System.currentTimeMillis() - sessionStartTime : 0;
        
        debugLog(1, String.format("=== STOPPING SESSION === (Duration: %dms, Final State: %s)", 
            sessionDuration, buyState.name()));
            
        if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2) {
            debugLog(2, String.format("Session Stats: Ticks=%d, Retries=%d/%d", 
                tickCount, retryCount, MAX_RETRIES));
        }
        
        BuyState oldState = this.buyState;
        this.enabled = false;
        this.buyState = BuyState.NONE;
        this.delayClock.reset();
        this.retryDelay.reset();
        
        if (mc.currentScreen != null) {
            debugLog(2, "Closing GUI screen: " + mc.currentScreen.getClass().getSimpleName());
            PlayerUtils.closeScreen();
        }
        
        debugStateTransition(oldState, BuyState.NONE, "manual stop");
    }
    
    /**
     * Main tick method
     * @return true if buying is complete (success or failure)
     */
    public boolean tick() {
        if (!enabled) {
            if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 3) {
                debugLog(3, "Tick skipped - buyer not enabled");
            }
            return true;
        }
        
        tickCount++;
        
        // Monitor inventory changes
        debugInventoryState();
        
        // Wait for delay if scheduled
        if (delayClock.isScheduled() && !delayClock.passed()) {
            long remaining = delayClock.getRemainingTime();
            if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 3 && tickCount % 20 == 0) { // Log every 20 ticks when waiting
                debugLog(3, String.format("Waiting for delay: %dms remaining (State: %s)", 
                    remaining, buyState.name()));
            }
            return false;
        }
        
        // Wait for retry delay if scheduled
        if (retryDelay.isScheduled() && !retryDelay.passed()) {
            long remaining = retryDelay.getRemainingTime();
            if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2 && tickCount % 20 == 0) {
                debugLog(2, String.format("Waiting for retry delay: %dms remaining", remaining));
            }
            return false;
        }
        
        if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 3) {
            debugLog(3, String.format("Processing tick in state: %s", buyState.name()));
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
            case PURCHASE_ITEMS:
                handlePurchaseItems();
                break;
            case CONFIRM_PURCHASES:
                handleConfirmPurchases();
                break;
            case RETRY_FAILED_ITEMS:
                handleRetryFailedItems();
                break;
            case END:
                logPurchaseResults();
                stop();
                return true;
            case FAILED:
                LogUtils.sendError("[UnifiedSkyMartBuyer] Purchase session failed");
                stop();
                return true;
        }
        
        return false;
    }
    
    private void handleOpenDesk() {
        debugLog(1, "=== PHASE 1: OPENING DESK ===");
        
        if (mc.currentScreen != null) {
            String screenType = mc.currentScreen.getClass().getSimpleName();
            debugLog(2, "Closing existing GUI screen: " + screenType);
            PlayerUtils.closeScreen();
            long delay = FarmHelperConfig.getRandomGUIMacroDelay();
            debugDelaySchedule("close existing GUI", delay);
            scheduleDelay(delay);
            return;
        }
        
        debugLog(2, "Stopping player movement before opening desk");
        KeyBindUtils.stopMovement();
        
        debugLog(1, "Sending /desk command");
        mc.thePlayer.sendChatMessage("/desk");
        
        debugStateTransition(buyState, BuyState.WAIT_FOR_DESK, "desk command sent");
        setBuyState(BuyState.WAIT_FOR_DESK);
        debugDelaySchedule("desk opening timeout", 3000);
        scheduleDelay(3000);
    }
    
    private void handleWaitForDesk() {
        String invName = InventoryUtils.getInventoryName();
        
        if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 3) {
            debugLog(3, String.format("Waiting for desk - Current GUI: '%s'", invName));
        }
        
        if (invName != null && invName.equals("Desk")) {
            debugLog(1, "✓ Desk opened successfully!");
            debugStateTransition(buyState, BuyState.OPEN_SKYMART, "desk GUI detected");
            setBuyState(BuyState.OPEN_SKYMART);
            long delay = FarmHelperConfig.getRandomGUIMacroDelay();
            debugDelaySchedule("prepare for SkyMart navigation", delay);
            scheduleDelay(delay);
        } else {
            if (!delayClock.isScheduled()) {
                debugLog(1, "✗ FAILED: Desk opening timeout - GUI never appeared");
                debugStateTransition(buyState, BuyState.FAILED, "desk timeout");
                setBuyState(BuyState.FAILED);
            } else if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2) {
                long remaining = delayClock.getRemainingTime();
                debugLog(2, String.format("Still waiting for desk (timeout in %dms)", remaining));
            }
        }
    }
    
    private void handleOpenSkyMart() {
        debugLog(1, "=== PHASE 2: OPENING SKYMART ===");
        debugLog(2, "Scanning desk inventory for SkyMart button...");
        
        Slot skymartSlot = InventoryUtils.getSlotOfItemInContainer("SkyMart");
        if (skymartSlot == null) {
            debugLog(1, "✗ FAILED: Cannot find SkyMart button in Desk inventory");
            if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2) {
                // Try to list all available slots for debugging
                debugLog(2, "Available items in desk:");
                // This would require additional inventory scanning - simplified for now
                debugLog(2, "Consider checking if desk inventory loaded properly");
            }
            debugStateTransition(buyState, BuyState.FAILED, "SkyMart button not found");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        debugClickAction("Clicking SkyMart", skymartSlot, InventoryUtils.ClickType.LEFT);
        debugLog(1, String.format("✓ Found SkyMart at slot %d, clicking...", skymartSlot.slotNumber));
        
        InventoryUtils.clickContainerSlot(skymartSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        
        debugStateTransition(buyState, BuyState.WAIT_FOR_SKYMART, "SkyMart clicked");
        setBuyState(BuyState.WAIT_FOR_SKYMART);
        debugDelaySchedule("SkyMart opening timeout", 3000);
        scheduleDelay(3000);
    }
    
    private void handleWaitForSkyMart() {
        String invName = InventoryUtils.getInventoryName();
        LogUtils.sendDebug("[UnifiedSkyMartBuyer] Waiting for SkyMart - Current GUI: " + invName);
        
        if (invName != null && invName.equals("SkyMart")) {
            LogUtils.sendDebug("[UnifiedSkyMartBuyer] SkyMart opened successfully");
            setBuyState(BuyState.OPEN_FARMING_ESSENTIALS);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else if (!delayClock.isScheduled()) {
            LogUtils.sendError("[UnifiedSkyMartBuyer] Failed to open SkyMart - timeout");
            setBuyState(BuyState.FAILED);
        }
    }
    
    private void handleOpenFarmingEssentials() {
        LogUtils.sendDebug("[UnifiedSkyMartBuyer] Looking for Farming Essentials in SkyMart");
        
        Slot farmingEssentialsSlot = InventoryUtils.getSlotOfItemInContainer("Diamond Hoe");
        if (farmingEssentialsSlot == null) {
            farmingEssentialsSlot = InventoryUtils.getSlotOfItemInContainer("Farming Essentials");
        }
        
        if (farmingEssentialsSlot == null) {
            LogUtils.sendError("[UnifiedSkyMartBuyer] Cannot find Farming Essentials (Diamond Hoe) in SkyMart");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        LogUtils.sendDebug("[UnifiedSkyMartBuyer] Clicking Farming Essentials at slot " + farmingEssentialsSlot.slotNumber);
        InventoryUtils.clickContainerSlot(farmingEssentialsSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        setBuyState(BuyState.WAIT_FOR_FARMING_ESSENTIALS);
        scheduleDelay(3000);
    }
    
    private void handleWaitForFarmingEssentials() {
        String invName = InventoryUtils.getInventoryName();
        LogUtils.sendDebug("[UnifiedSkyMartBuyer] Waiting for Farming Essentials - Current GUI: " + invName);
        
        if (invName != null && (invName.contains("Farming") || invName.contains("Essentials"))) {
            LogUtils.sendDebug("[UnifiedSkyMartBuyer] Farming Essentials opened successfully");
            setBuyState(BuyState.PURCHASE_ITEMS);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else if (!delayClock.isScheduled()) {
            LogUtils.sendError("[UnifiedSkyMartBuyer] Failed to open Farming Essentials - timeout");
            setBuyState(BuyState.FAILED);
        }
    }
    
    private void handlePurchaseItems() {
        if (currentPurchaseQueue.isEmpty()) {
            debugLog(1, "=== PHASE 4: ALL ITEMS PROCESSED ===");
            debugLog(1, "Moving to purchase confirmation phase");
            debugStateTransition(buyState, BuyState.CONFIRM_PURCHASES, "purchase queue empty");
            setBuyState(BuyState.CONFIRM_PURCHASES);
            debugDelaySchedule("prepare for confirmation", 1000);
            scheduleDelay(1000);
            return;
        }
        
        String currentItem = currentPurchaseQueue.get(0);
        int amountToBuy = shoppingList.get(currentItem);
        int queuePosition = shoppingList.size() - currentPurchaseQueue.size() + 1;
        
        debugLog(1, String.format("=== PURCHASING ITEM %d/%d: %s ===", 
            queuePosition, shoppingList.size(), currentItem));
        debugLog(1, String.format("Target: %s x%d", currentItem, amountToBuy));
        
        // Check current inventory before purchase
        int currentInventoryAmount = InventoryUtils.getAmountOfItemInInventory(currentItem);
        debugLog(2, String.format("Pre-purchase inventory: %d %s", currentInventoryAmount, currentItem));
        
        Slot itemSlot = findItemSlot(currentItem);
        if (itemSlot == null) {
            debugLog(1, String.format("✗ SKIPPING: Cannot find '%s' in Farming Essentials", currentItem));
            if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2) {
                debugLog(2, "Item not found - possible causes: wrong name, sold out, or GUI not loaded");
                // Try alternative search strategies
                debugLog(2, "Attempting alternative item name searches...");
            }
            purchaseResults.put(currentItem, currentInventoryAmount); // Record current amount
            currentPurchaseQueue.remove(0);
            debugDelaySchedule("skip failed item", 500);
            scheduleDelay(500);
            debugShoppingProgress();
            return;
        }
        
        String itemDisplayName = itemSlot.getHasStack() ? itemSlot.getStack().getDisplayName() : "unknown";
        debugLog(1, String.format("✓ Found target item: '%s' at slot %d", itemDisplayName, itemSlot.slotNumber));
        
        debugLog(2, String.format("Performing %d shift-clicks to purchase %s", amountToBuy, currentItem));
        
        // Perform the purchases with detailed logging
        for (int i = 0; i < amountToBuy; i++) {
            debugClickAction(String.format("Purchase %d/%d", i+1, amountToBuy), 
                itemSlot, InventoryUtils.ClickType.LEFT);
            
            if (FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 3) {
                debugLog(3, String.format("Shift-click #%d on %s", i+1, itemDisplayName));
            }
            
            InventoryUtils.clickContainerSlot(itemSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.QUICK_MOVE);
        }
        
        // Check post-purchase inventory
        int postPurchaseAmount = InventoryUtils.getAmountOfItemInInventory(currentItem);
        int purchasedAmount = postPurchaseAmount - currentInventoryAmount;
        
        debugLog(1, String.format("✓ Purchase completed: %d %s (gained %d, target was %d)", 
            postPurchaseAmount, currentItem, purchasedAmount, amountToBuy));
        
        if (purchasedAmount < amountToBuy && FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel() >= 2) {
            debugLog(2, String.format("WARNING: Incomplete purchase - only got %d/%d %s", 
                purchasedAmount, amountToBuy, currentItem));
        }
        
        currentPurchaseQueue.remove(0);
        debugDelaySchedule("between purchases", 800);
        scheduleDelay(800);
        debugShoppingProgress();
    }
    
    private Slot findItemSlot(String itemName) {
        // Direct name match
        Slot slot = InventoryUtils.getSlotOfItemInContainer(itemName);
        if (slot != null) return slot;
        
        // Try alternative names based on item
        if (itemName.equals("Biofuel")) {
            slot = InventoryUtils.getSlotOfItemInContainer("Green Dye");
            if (slot != null) return slot;
        }
        
        if (itemName.equals("Box of Seeds")) {
            slot = InventoryUtils.getSlotOfItemInContainer("Seeds");
            if (slot != null) return slot;
            slot = InventoryUtils.getSlotOfItemInContainer("Seed");
            if (slot != null) return slot;
        }
        
        return null;
    }
    
    private void handleConfirmPurchases() {
        LogUtils.sendDebug("[UnifiedSkyMartBuyer] Confirming all purchases");
        
        // Check inventory for all purchased items
        List<String> failedItems = new ArrayList<>();
        
        for (Map.Entry<String, Integer> entry : shoppingList.entrySet()) {
            String itemName = entry.getKey();
            int expectedAmount = entry.getValue();
            int actualAmount = InventoryUtils.getAmountOfItemInInventory(itemName);
            
            purchaseResults.put(itemName, actualAmount);
            
            if (actualAmount >= expectedAmount) {
                LogUtils.sendSuccess("[UnifiedSkyMartBuyer] Successfully purchased " + actualAmount + " " + itemName + " (needed " + expectedAmount + ")");
            } else {
                LogUtils.sendWarning("[UnifiedSkyMartBuyer] Purchase incomplete for " + itemName + ": got " + actualAmount + ", needed " + expectedAmount);
                failedItems.add(itemName);
            }
        }
        
        if (!failedItems.isEmpty() && retryCount < MAX_RETRIES) {
            LogUtils.sendDebug("[UnifiedSkyMartBuyer] Retrying failed items (attempt " + (retryCount + 1) + "/" + MAX_RETRIES + ")");
            currentPurchaseQueue.clear();
            currentPurchaseQueue.addAll(failedItems);
            retryCount++;
            setBuyState(BuyState.RETRY_FAILED_ITEMS);
            scheduleRetryDelay(2000);
        } else {
            setBuyState(BuyState.END);
        }
    }
    
    private void handleRetryFailedItems() {
        LogUtils.sendDebug("[UnifiedSkyMartBuyer] Retrying failed items");
        setBuyState(BuyState.PURCHASE_ITEMS);
        scheduleDelay(500);
    }
    
    private void logPurchaseResults() {
        LogUtils.sendDebug("[UnifiedSkyMartBuyer] Purchase session completed. Results:");
        for (Map.Entry<String, Integer> entry : purchaseResults.entrySet()) {
            int requested = shoppingList.getOrDefault(entry.getKey(), 0);
            int obtained = entry.getValue();
            String status = obtained >= requested ? "SUCCESS" : "PARTIAL";
            LogUtils.sendDebug("[UnifiedSkyMartBuyer] " + entry.getKey() + ": " + obtained + "/" + requested + " (" + status + ")");
        }
    }
    
    private void setBuyState(BuyState newState) {
        // Note: debugStateTransition is called from the calling methods for better context
        this.buyState = newState;
    }
    
    private void scheduleDelay(long ms) {
        // Note: debugDelaySchedule is called from the calling methods for better context
        this.delayClock.schedule(ms);
    }
    
    private void scheduleRetryDelay(long ms) {
        debugLog(2, String.format("Scheduling retry delay: %dms", ms));
        this.retryDelay.schedule(ms);
    }
    
    /**
     * @return Current effective debug level from config
     */
    public int getDebugLevel() {
        return FarmHelperConfig.getUnifiedSkyMartBuyerEffectiveDebugLevel();
    }
    
    /**
     * @return Debug level name for display
     */
    public String getDebugLevelName() {
        return FarmHelperConfig.getDebugLevelName(getDebugLevel());
    }
    
    /**
     * @return true if the buying process is currently running
     */
    public boolean isRunning() {
        return enabled && buyState != BuyState.NONE;
    }
    
    /**
     * @return true if the buying process completed successfully for all items
     */
    public boolean hasSucceeded() {
        if (enabled || purchaseResults.isEmpty()) return false;
        
        for (Map.Entry<String, Integer> entry : shoppingList.entrySet()) {
            int expected = entry.getValue();
            int actual = purchaseResults.getOrDefault(entry.getKey(), 0);
            if (actual < expected) return false;
        }
        return true;
    }
    
    /**
     * @return true if the buying process failed completely
     */
    public boolean hasFailed() {
        return buyState == BuyState.FAILED;
    }
    
    /**
     * Get the purchase results map
     * @return Map of item name to actual amount purchased
     */
    public Map<String, Integer> getPurchaseResults() {
        return new HashMap<>(purchaseResults);
    }
    
    /**
     * Get items that were not fully purchased
     * @return Map of item name to missing amount
     */
    public Map<String, Integer> getFailedPurchases() {
        Map<String, Integer> failed = new HashMap<>();
        for (Map.Entry<String, Integer> entry : shoppingList.entrySet()) {
            int expected = entry.getValue();
            int actual = purchaseResults.getOrDefault(entry.getKey(), 0);
            if (actual < expected) {
                failed.put(entry.getKey(), expected - actual);
            }
        }
        return failed;
    }
}
