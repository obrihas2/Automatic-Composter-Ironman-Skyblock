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

/**
 * Handles purchasing Box of Seeds through the SkyMart system (/desk → SkyMart → Farming Essentials → Box of Seeds)
 */
public class BoxOfSeedsBuyer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    
    @Getter
    private BuyState buyState = BuyState.NONE;
    private final Clock delayClock = new Clock();
    private int seedsAmountToBuy = 0;
    private boolean enabled = false;
    
    public enum BuyState {
        NONE,
        OPEN_DESK,
        WAIT_FOR_DESK,
        OPEN_SKYMART,
        WAIT_FOR_SKYMART,
        OPEN_FARMING_ESSENTIALS,
        WAIT_FOR_FARMING_ESSENTIALS,
        BUY_BOX_OF_SEEDS,
        CONFIRM_PURCHASE,
        END,
        FAILED
    }
    
    /**
     * Starts the Box of Seeds buying process
     * @param amountToBuy How many Box of Seeds items to purchase
     */
    public void startBuying(int amountToBuy) {
        if (enabled) return;
        
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Starting Box of Seeds purchase - Amount: " + amountToBuy);
        }
        this.seedsAmountToBuy = amountToBuy;
        this.buyState = BuyState.OPEN_DESK;
        this.enabled = true;
        this.delayClock.reset();
    }
    
    /**
     * Stops the buying process
     */
    public void stop() {
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Stopping - State: " + buyState.name());
        }
        this.enabled = false;
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
            case BUY_BOX_OF_SEEDS:
                handleBuyBoxOfSeeds();
                break;
            case CONFIRM_PURCHASE:
                handleConfirmPurchase();
                break;
            case END:
                if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
                    LogUtils.sendDebug("[BoxOfSeedsBuyer] Purchase completed successfully");
                }
                stop();
                return true;
            case FAILED:
                LogUtils.sendError("[BoxOfSeedsBuyer] Purchase failed");
                stop();
                return true;
        }
        
        return false;
    }
    
    private void handleOpenDesk() {
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Opening desk with /desk command");
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
        scheduleDelay(FarmHelperConfig.boxOfSeedsBuyerTimeout);
    }
    
    private void handleWaitForDesk() {
        String invName = InventoryUtils.getInventoryName();
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Waiting for desk - Current GUI: " + invName);
        }
        
        if (invName != null && invName.equals("Desk")) {
            if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
                LogUtils.sendDebug("[BoxOfSeedsBuyer] Desk opened successfully");
            }
            setBuyState(BuyState.OPEN_SKYMART);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else {
            // Timeout check - if we've been waiting too long, fail
            if (!delayClock.isScheduled()) {
                LogUtils.sendError("[BoxOfSeedsBuyer] Failed to open desk - timeout");
                setBuyState(BuyState.FAILED);
            }
        }
    }
    
    private void handleOpenSkyMart() {
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Looking for SkyMart in Desk");
        }
        
        Slot skymartSlot = InventoryUtils.getSlotOfItemInContainer("SkyMart");
        if (skymartSlot == null) {
            LogUtils.sendError("[BoxOfSeedsBuyer] Cannot find SkyMart button in Desk");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Clicking SkyMart at slot " + skymartSlot.slotNumber);
        }
        InventoryUtils.clickContainerSlot(skymartSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        setBuyState(BuyState.WAIT_FOR_SKYMART);
        scheduleDelay(FarmHelperConfig.boxOfSeedsBuyerTimeout);
    }
    
    private void handleWaitForSkyMart() {
        String invName = InventoryUtils.getInventoryName();
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Waiting for SkyMart - Current GUI: " + invName);
        }
        
        if (invName != null && invName.equals("SkyMart")) {
            if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
                LogUtils.sendDebug("[BoxOfSeedsBuyer] SkyMart opened successfully");
            }
            setBuyState(BuyState.OPEN_FARMING_ESSENTIALS);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else if (!delayClock.isScheduled()) {
            LogUtils.sendError("[BoxOfSeedsBuyer] Failed to open SkyMart - timeout");
            setBuyState(BuyState.FAILED);
        }
    }
    
    private void handleOpenFarmingEssentials() {
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Looking for Farming Essentials in SkyMart");
        }
        
        // Look for Diamond Hoe or "Farming Essentials" text
        Slot farmingEssentialsSlot = InventoryUtils.getSlotOfItemInContainer("Diamond Hoe");
        if (farmingEssentialsSlot == null) {
            farmingEssentialsSlot = InventoryUtils.getSlotOfItemInContainer("Farming Essentials");
        }
        
        if (farmingEssentialsSlot == null) {
            LogUtils.sendError("[BoxOfSeedsBuyer] Cannot find Farming Essentials (Diamond Hoe) in SkyMart");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Clicking Farming Essentials at slot " + farmingEssentialsSlot.slotNumber);
        }
        InventoryUtils.clickContainerSlot(farmingEssentialsSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.PICKUP);
        setBuyState(BuyState.WAIT_FOR_FARMING_ESSENTIALS);
        scheduleDelay(FarmHelperConfig.boxOfSeedsBuyerTimeout);
    }
    
    private void handleWaitForFarmingEssentials() {
        String invName = InventoryUtils.getInventoryName();
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Waiting for Farming Essentials - Current GUI: " + invName);
        }
        
        // The GUI name might contain "Farming" or "Essentials"
        if (invName != null && (invName.contains("Farming") || invName.contains("Essentials"))) {
            if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
                LogUtils.sendDebug("[BoxOfSeedsBuyer] Farming Essentials opened successfully");
            }
            setBuyState(BuyState.BUY_BOX_OF_SEEDS);
            scheduleDelay(FarmHelperConfig.getRandomGUIMacroDelay());
        } else if (!delayClock.isScheduled()) {
            LogUtils.sendError("[BoxOfSeedsBuyer] Failed to open Farming Essentials - timeout");
            setBuyState(BuyState.FAILED);
        }
    }
    
    private void handleBuyBoxOfSeeds() {
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Looking for Box of Seeds in Farming Essentials");
        }
        
        // Look for Box of Seeds - might be called different things in GUI
        Slot boxOfSeedsSlot = InventoryUtils.getSlotOfItemInContainer("Box of Seeds");
        if (boxOfSeedsSlot == null) {
            // Try alternative names
            boxOfSeedsSlot = InventoryUtils.getSlotOfItemInContainer("Seeds");
        }
        
        if (boxOfSeedsSlot == null) {
            LogUtils.sendError("[BoxOfSeedsBuyer] Cannot find Box of Seeds in Farming Essentials");
            setBuyState(BuyState.FAILED);
            return;
        }
        
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Shift-clicking Box of Seeds at slot " + boxOfSeedsSlot.slotNumber + " to purchase " + seedsAmountToBuy + " items");
        }
        
        // Shift-click to buy the item
        for (int i = 0; i < seedsAmountToBuy; i++) {
            InventoryUtils.clickContainerSlot(boxOfSeedsSlot.slotNumber, InventoryUtils.ClickType.LEFT, InventoryUtils.ClickMode.QUICK_MOVE);
        }
        
        setBuyState(BuyState.CONFIRM_PURCHASE);
        scheduleDelay(1000);
    }
    
    private void handleConfirmPurchase() {
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Confirming purchase completion");
        }
        
        // Check if we have Box of Seeds in our inventory now
        int boxOfSeedsInInventory = InventoryUtils.getAmountOfItemInInventory("Box of Seeds");
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Box of Seeds in inventory: " + boxOfSeedsInInventory);
        }
        
        if (boxOfSeedsInInventory >= seedsAmountToBuy) {
            LogUtils.sendSuccess("[BoxOfSeedsBuyer] Successfully purchased " + boxOfSeedsInInventory + " Box of Seeds");
            setBuyState(BuyState.END);
        } else {
            LogUtils.sendWarning("[BoxOfSeedsBuyer] Purchase may have failed - expected " + seedsAmountToBuy + " but got " + boxOfSeedsInInventory);
            // Still continue as END since we tried
            setBuyState(BuyState.END);
        }
        
        scheduleDelay(500);
    }
    
    private void setBuyState(BuyState newState) {
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] State transition: " + buyState.name() + " → " + newState.name());
        }
        this.buyState = newState;
    }
    
    private void scheduleDelay(long ms) {
        long totalDelay = ms + FarmHelperConfig.boxOfSeedsBuyerGUIDelay;
        if (FarmHelperConfig.boxOfSeedsBuyerDebugLogging) {
            LogUtils.sendDebug("[BoxOfSeedsBuyer] Scheduling delay: " + totalDelay + "ms (base: " + ms + "ms + config: " + FarmHelperConfig.boxOfSeedsBuyerGUIDelay + "ms)");
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
        return !enabled && buyState == BuyState.NONE;
    }
    
    /**
     * @return true if the buying process failed
     */
    public boolean hasFailed() {
        return buyState == BuyState.FAILED;
    }
}