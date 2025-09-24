package com.jelly.farmhelperv2.feature.impl;

import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.util.InventoryUtils;
import com.jelly.farmhelperv2.util.LogUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for all SkyMart buyers
 */
public class SkyMartBuyerTest {
    
    private BiofuelBuyer biofuelBuyer;
    private BoxOfSeedsBuyer boxOfSeedsBuyer;
    private UnifiedSkyMartBuyer unifiedSkyMartBuyer;
    
    @BeforeEach
    void setUp() {
        // Reset configuration to defaults
        FarmHelperConfig.biofuelBuyerDebugLogging = false;
        FarmHelperConfig.biofuelBuyerTimeout = 2000;
        FarmHelperConfig.biofuelBuyerGUIDelay = 0;
        
        FarmHelperConfig.boxOfSeedsBuyerDebugLogging = false;
        FarmHelperConfig.boxOfSeedsBuyerTimeout = 2000;
        FarmHelperConfig.boxOfSeedsBuyerGUIDelay = 0;
        
        FarmHelperConfig.unifiedSkyMartBuyerDebugLogging = false;
        FarmHelperConfig.unifiedSkyMartBuyerMaxRetries = 3;
        FarmHelperConfig.unifiedSkyMartBuyerPurchaseDelay = 800;
        
        biofuelBuyer = new BiofuelBuyer();
        boxOfSeedsBuyer = new BoxOfSeedsBuyer();
        unifiedSkyMartBuyer = new UnifiedSkyMartBuyer();
    }
    
    @Test
    @DisplayName("BiofuelBuyer should start in NONE state")
    void testBiofuelBuyerInitialState() {
        assertEquals(BiofuelBuyer.BuyState.NONE, biofuelBuyer.getBuyState());
        assertFalse(biofuelBuyer.isRunning());
        assertTrue(biofuelBuyer.hasSucceeded()); // Not running = succeeded
        assertFalse(biofuelBuyer.hasFailed());
    }
    
    @Test
    @DisplayName("BiofuelBuyer should transition to OPEN_DESK when started")
    void testBiofuelBuyerStart() {
        biofuelBuyer.startBuying(2);
        
        assertEquals(BiofuelBuyer.BuyState.OPEN_DESK, biofuelBuyer.getBuyState());
        assertTrue(biofuelBuyer.isRunning());
        assertFalse(biofuelBuyer.hasSucceeded());
        assertFalse(biofuelBuyer.hasFailed());
    }
    
    @Test
    @DisplayName("BoxOfSeedsBuyer should start in NONE state")
    void testBoxOfSeedsBuyerInitialState() {
        assertEquals(BoxOfSeedsBuyer.BuyState.NONE, boxOfSeedsBuyer.getBuyState());
        assertFalse(boxOfSeedsBuyer.isRunning());
        assertTrue(boxOfSeedsBuyer.hasSucceeded());
        assertFalse(boxOfSeedsBuyer.hasFailed());
    }
    
    @Test
    @DisplayName("BoxOfSeedsBuyer should transition to OPEN_DESK when started")
    void testBoxOfSeedsBuyerStart() {
        boxOfSeedsBuyer.startBuying(3);
        
        assertEquals(BoxOfSeedsBuyer.BuyState.OPEN_DESK, boxOfSeedsBuyer.getBuyState());
        assertTrue(boxOfSeedsBuyer.isRunning());
        assertFalse(boxOfSeedsBuyer.hasSucceeded());
        assertFalse(boxOfSeedsBuyer.hasFailed());
    }
    
    @Test
    @DisplayName("UnifiedSkyMartBuyer should start in NONE state")
    void testUnifiedSkyMartBuyerInitialState() {
        assertEquals(UnifiedSkyMartBuyer.BuyState.NONE, unifiedSkyMartBuyer.getBuyState());
        assertFalse(unifiedSkyMartBuyer.isRunning());
        assertFalse(unifiedSkyMartBuyer.hasSucceeded()); // Empty results = not succeeded
        assertFalse(unifiedSkyMartBuyer.hasFailed());
    }
    
    @Test
    @DisplayName("UnifiedSkyMartBuyer should transition to OPEN_DESK when started")
    void testUnifiedSkyMartBuyerStart() {
        List<UnifiedSkyMartBuyer.PurchaseRequest> requests = new ArrayList<>();
        requests.add(new UnifiedSkyMartBuyer.PurchaseRequest("Biofuel", 2, "Green Dye"));
        requests.add(new UnifiedSkyMartBuyer.PurchaseRequest("Box of Seeds", 1, "Seeds"));
        
        unifiedSkyMartBuyer.startBuyingSession(requests);
        
        assertEquals(UnifiedSkyMartBuyer.BuyState.OPEN_DESK, unifiedSkyMartBuyer.getBuyState());
        assertTrue(unifiedSkyMartBuyer.isRunning());
        assertFalse(unifiedSkyMartBuyer.hasSucceeded());
        assertFalse(unifiedSkyMartBuyer.hasFailed());
    }
    
    @Test
    @DisplayName("Buyers should stop properly")
    void testBuyersStop() {
        // Start all buyers
        biofuelBuyer.startBuying(1);
        boxOfSeedsBuyer.startBuying(1);
        
        List<UnifiedSkyMartBuyer.PurchaseRequest> requests = new ArrayList<>();
        requests.add(new UnifiedSkyMartBuyer.PurchaseRequest("Biofuel", 1));
        unifiedSkyMartBuyer.startBuyingSession(requests);
        
        // Verify they're running
        assertTrue(biofuelBuyer.isRunning());
        assertTrue(boxOfSeedsBuyer.isRunning());
        assertTrue(unifiedSkyMartBuyer.isRunning());
        
        // Stop them
        biofuelBuyer.stop();
        boxOfSeedsBuyer.stop();
        unifiedSkyMartBuyer.stop();
        
        // Verify they're stopped
        assertFalse(biofuelBuyer.isRunning());
        assertFalse(boxOfSeedsBuyer.isRunning());
        assertFalse(unifiedSkyMartBuyer.isRunning());
        
        assertEquals(BiofuelBuyer.BuyState.NONE, biofuelBuyer.getBuyState());
        assertEquals(BoxOfSeedsBuyer.BuyState.NONE, boxOfSeedsBuyer.getBuyState());
        assertEquals(UnifiedSkyMartBuyer.BuyState.NONE, unifiedSkyMartBuyer.getBuyState());
    }
    
    @Test
    @DisplayName("UnifiedSkyMartBuyer should handle empty purchase requests")
    void testUnifiedSkyMartBuyerEmptyRequests() {
        List<UnifiedSkyMartBuyer.PurchaseRequest> emptyRequests = new ArrayList<>();
        
        unifiedSkyMartBuyer.startBuyingSession(emptyRequests);
        
        assertEquals(UnifiedSkyMartBuyer.BuyState.OPEN_DESK, unifiedSkyMartBuyer.getBuyState());
        assertTrue(unifiedSkyMartBuyer.isRunning());
    }
    
    @Test
    @DisplayName("UnifiedSkyMartBuyer should provide purchase results")
    void testUnifiedSkyMartBuyerResults() {
        List<UnifiedSkyMartBuyer.PurchaseRequest> requests = new ArrayList<>();
        requests.add(new UnifiedSkyMartBuyer.PurchaseRequest("Biofuel", 2));
        requests.add(new UnifiedSkyMartBuyer.PurchaseRequest("Box of Seeds", 3));
        
        unifiedSkyMartBuyer.startBuyingSession(requests);
        
        Map<String, Integer> results = unifiedSkyMartBuyer.getPurchaseResults();
        Map<String, Integer> failures = unifiedSkyMartBuyer.getFailedPurchases();
        
        assertNotNull(results);
        assertNotNull(failures);
        assertTrue(results.isEmpty()); // No purchases completed yet
        assertTrue(failures.isEmpty()); // No failures yet
    }
    
    @Test
    @DisplayName("Configuration should affect buyer behavior")
    void testConfigurationEffects() {
        // Test debug logging configuration
        FarmHelperConfig.biofuelBuyerDebugLogging = true;
        FarmHelperConfig.boxOfSeedsBuyerDebugLogging = true;
        FarmHelperConfig.unifiedSkyMartBuyerDebugLogging = true;
        
        // Test timeout configuration
        FarmHelperConfig.biofuelBuyerTimeout = 5000;
        FarmHelperConfig.boxOfSeedsBuyerTimeout = 5000;
        
        // Test delay configuration
        FarmHelperConfig.biofuelBuyerGUIDelay = 500;
        FarmHelperConfig.boxOfSeedsBuyerGUIDelay = 500;
        FarmHelperConfig.unifiedSkyMartBuyerPurchaseDelay = 1000;
        
        // Start buyers with configuration
        biofuelBuyer.startBuying(1);
        boxOfSeedsBuyer.startBuying(1);
        
        List<UnifiedSkyMartBuyer.PurchaseRequest> requests = new ArrayList<>();
        requests.add(new UnifiedSkyMartBuyer.PurchaseRequest("Biofuel", 1));
        unifiedSkyMartBuyer.startBuyingSession(requests);
        
        // Verify they start properly with custom config
        assertTrue(biofuelBuyer.isRunning());
        assertTrue(boxOfSeedsBuyer.isRunning());
        assertTrue(unifiedSkyMartBuyer.isRunning());
    }
    
    @Test
    @DisplayName("Buyers should handle duplicate start calls gracefully")
    void testDuplicateStartCalls() {
        biofuelBuyer.startBuying(1);
        BiofuelBuyer.BuyState firstState = biofuelBuyer.getBuyState();
        
        // Try to start again - should be ignored
        biofuelBuyer.startBuying(2);
        
        assertEquals(firstState, biofuelBuyer.getBuyState());
        assertTrue(biofuelBuyer.isRunning());
    }
    
    @Test
    @DisplayName("Buyers should return true when tick() is called while not enabled")
    void testTickWhileNotEnabled() {
        // All buyers should return true (complete) when not enabled
        assertTrue(biofuelBuyer.tick());
        assertTrue(boxOfSeedsBuyer.tick());
        assertTrue(unifiedSkyMartBuyer.tick());
    }
    
    @Test
    @DisplayName("UnifiedSkyMartBuyer PurchaseRequest should store data correctly")
    void testPurchaseRequestData() {
        UnifiedSkyMartBuyer.PurchaseRequest request = new UnifiedSkyMartBuyer.PurchaseRequest(
            "Biofuel", 5, "Green Dye", "Lime Dye"
        );
        
        assertEquals("Biofuel", request.itemName);
        assertEquals(5, request.amount);
        assertArrayEquals(new String[]{"Green Dye", "Lime Dye"}, request.alternativeNames);
    }
    
    @Test
    @DisplayName("Buyers should handle configuration changes during operation")
    void testConfigurationChanges() {
        biofuelBuyer.startBuying(1);
        
        // Change configuration mid-operation
        FarmHelperConfig.biofuelBuyerDebugLogging = true;
        FarmHelperConfig.biofuelBuyerGUIDelay = 1000;
        
        // Should still be running
        assertTrue(biofuelBuyer.isRunning());
        assertEquals(BiofuelBuyer.BuyState.OPEN_DESK, biofuelBuyer.getBuyState());
    }
}
