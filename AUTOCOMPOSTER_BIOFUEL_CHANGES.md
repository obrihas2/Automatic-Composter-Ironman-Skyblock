# AutoComposter Biofuel Integration Changes

## Summary
Successfully replaced AutoBazaar-based fuel purchasing with BiofuelBuyer that uses SkyMart navigation (/desk → SkyMart → Farming Essentials → Biofuel purchase).

## Files Modified

### 1. Created: `BiofuelBuyer.java`
- **Location**: `src/main/java/com/jelly/farmhelperv2/feature/impl/BiofuelBuyer.java`
- **Purpose**: Handles the complete SkyMart navigation and Biofuel purchasing workflow
- **Key Features**:
  - State machine with detailed debug logging for each step
  - Handles GUI navigation: Desk → SkyMart → Farming Essentials → Biofuel
  - Robust error handling with timeouts and fallbacks
  - Configurable purchase amounts
  - Extensive debug logging for troubleshooting

**State Flow**:
```
NONE → OPEN_DESK → WAIT_FOR_DESK → OPEN_SKYMART → WAIT_FOR_SKYMART 
     → OPEN_FARMING_ESSENTIALS → WAIT_FOR_FARMING_ESSENTIALS 
     → BUY_BIOFUEL → CONFIRM_PURCHASE → END/FAILED
```

### 2. Modified: `AutoComposter.java`
**Key Changes**:

#### Removed AutoBazaar Dependencies:
- Removed `itemsToBuy` ArrayList
- Removed `BuyState` enum and `buyState` variable  
- Removed `onBuyState()` method
- Removed all `AutoBazaar.getInstance()` calls

#### Added BiofuelBuyer Integration:
- Added `BiofuelBuyer biofuelBuyer = new BiofuelBuyer()`
- Added `BUY_BIOFUEL` to `ComposterState` enum
- Added `onBuyBiofuelState()` method
- Updated state transitions to use BiofuelBuyer

#### Enhanced CHECK_COMPOSTER Logic:
- **Before**: Built `itemsToBuy` list and called AutoBazaar
- **After**: Checks inventory directly for Box of Seeds and Biofuel
- Added detailed resource logging (current/max organic matter and fuel)
- Only purchases Biofuel when needed and missing from inventory
- Warns about missing Box of Seeds but continues (no auto-purchase)

#### Updated FILL_COMPOSTER Logic:
- **Before**: Looked for "Volta" fuel items
- **After**: Looks for "Biofuel" items
- Enhanced logging when adding items to composter

## Debug Logging Added

### BiofuelBuyer Logs:
- `[BiofuelBuyer] Starting Biofuel purchase - Amount: X`
- `[BiofuelBuyer] State transition: OLD_STATE → NEW_STATE`
- `[BiofuelBuyer] Opening desk with /desk command`
- `[BiofuelBuyer] Waiting for desk - Current GUI: X`
- `[BiofuelBuyer] Looking for SkyMart in Desk`
- `[BiofuelBuyer] Clicking SkyMart at slot X`
- `[BiofuelBuyer] Looking for Farming Essentials in SkyMart`
- `[BiofuelBuyer] Shift-clicking Biofuel at slot X to purchase Y items`
- `[BiofuelBuyer] Successfully purchased X Biofuel`

### AutoComposter Enhanced Logs:
- `[Auto Composter] Checking composter resources`
- `[Auto Composter] Organic Matter: X/Y`
- `[Auto Composter] Fuel: X/Y`
- `[Auto Composter] Need X Box of Seeds, have Y`
- `[Auto Composter] Need X Biofuel, have Y`
- `[Auto Composter] Missing Biofuel! Need X but only have Y`
- `[Auto Composter] Starting Biofuel purchase`
- `[Auto Composter] Adding Biofuel to composter`

## Workflow Changes

### Old AutoBazaar Workflow:
1. Check composter resources
2. Calculate needed items (Box of Seeds, Volta)
3. Add to `itemsToBuy` list
4. Call `AutoBazaar.buy()` with price limits
5. Wait for purchase completion via Bazaar API
6. Fill composter with purchased items

### New BiofuelBuyer Workflow:
1. Check composter resources
2. Check inventory for Box of Seeds and Biofuel
3. If Biofuel missing: start BiofuelBuyer
4. BiofuelBuyer navigates: /desk → SkyMart → Farming Essentials
5. Purchases Biofuel via shift-click (20k coins each)
6. Returns to composter and fills with available items

## Error Handling

### BiofuelBuyer Error Cases:
- Timeout waiting for Desk GUI → FAILED state
- Cannot find SkyMart button → FAILED state  
- Timeout waiting for SkyMart GUI → FAILED state
- Cannot find Farming Essentials → FAILED state
- Cannot find Biofuel item → FAILED state
- All failures logged with detailed error messages

### AutoComposter Error Handling:
- If BiofuelBuyer fails → continues without fuel (logs error)
- Missing Box of Seeds → warns but continues
- Stuck detection still works with BiofuelBuyer

## Cost Comparison
- **AutoBazaar**: Variable market prices, price manipulation protection
- **BiofuelBuyer**: Fixed 20k coins per Biofuel (SkyMart price)

## Benefits
1. **No Market Dependency**: Immune to Bazaar price fluctuations/manipulation
2. **Reliable Supply**: SkyMart always has Biofuel available
3. **Predictable Costs**: Fixed 20k per Biofuel
4. **Extensive Logging**: Detailed debug info for troubleshooting
5. **Robust Error Handling**: Graceful failure handling with continuation

## Testing Recommendations
1. Test with empty composter (needs both matter and fuel)
2. Test with partial fuel (only needs Biofuel)
3. Test with sufficient resources (should skip buying)
4. Test error scenarios (disconnect during GUI navigation)
5. Verify logs are helpful for debugging issues

## Configuration
Currently uses existing `FarmHelperConfig` settings:
- `FarmHelperConfig.getRandomGUIMacroDelay()` for GUI delays
- Uses `LogUtils.sendDebug()` for extensive logging
- All delays and timeouts are configurable via existing systems