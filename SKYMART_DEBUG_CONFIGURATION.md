# SkyMart Buyer System - Debug Configuration Guide

## Overview

This guide explains how to configure and use the comprehensive debug logging system for the SkyMart buyer system in FarmHelper. The debug system provides detailed insights into buyer behavior, state transitions, GUI interactions, and purchase operations.

## Debug Configuration Location

All debug settings are located in **FarmHelper Config → Auto Composter → SkyMart Debug**

## Master Debug Controls

### Enable All SkyMart Debug Logging
- **Type**: Switch (On/Off)
- **Default**: Off
- **Description**: Master switch that enables debug logging for ALL SkyMart buyers
- **When to use**: Turn this on when you want to debug all buyers at once

### Master SkyMart Debug Level
- **Type**: Dropdown (Minimal/Normal/Verbose/Extreme)
- **Default**: Normal
- **Description**: When master switch is on, this level applies to ALL buyers
- **Levels**:
  - **Minimal (0)**: Only critical errors and basic success messages
  - **Normal (1)**: State transitions, major operations, purchase results
  - **Verbose (2)**: GUI changes, click actions, inventory checks, delays
  - **Extreme (3)**: Every tick, detailed timing, micro-operations

### Log Purchase Performance Stats
- **Type**: Switch (On/Off)  
- **Default**: Off
- **Description**: Logs detailed performance metrics for all purchases
- **Output includes**: Purchase duration, retry counts, success rates, throughput

### Log GUI Detection Issues
- **Type**: Switch (On/Off)
- **Default**: On
- **Description**: Logs detailed info when GUI detection fails or is slow
- **Useful for**: Troubleshooting navigation issues, timeout problems

## Individual Buyer Debug Controls

Each buyer (BiofuelBuyer, BoxOfSeedsBuyer, UnifiedSkyMartBuyer) has its own debug section:

### Individual Debug Logging Switch
- **Purpose**: Enable debug logging for just that specific buyer
- **Priority**: Overridden by master switch when master is enabled

### Individual Debug Level
- **Purpose**: Set specific debug level for that buyer
- **Priority**: Overridden by master level when master is enabled

## Debug Output Format

All debug messages follow a consistent format:

```
[BuyerName][HH:mm:ss.SSS][T+{session_time}ms][S+{state_time}ms][Δ{since_last}ms][Tick#{tick_count}] {message}
```

**Format explanation**:
- `BuyerName`: Which buyer generated the message
- `HH:mm:ss.SSS`: Current timestamp with milliseconds
- `T+{time}ms`: Milliseconds since session started
- `S+{time}ms`: Milliseconds since current state started
- `Δ{time}ms`: Milliseconds since last debug message
- `Tick#{count}`: Current tick number in the session

## Debug Levels Explained

### Level 0: Minimal
**What you see**:
- Session start/stop messages
- Critical errors
- Final purchase results
- Major state failures

**Example**:
```
[BiofuelBuyer][14:23:45.123][T+0ms][S+0ms][Δ0ms][Tick#1] === STARTING BIOFUEL PURCHASE SESSION ===
[BiofuelBuyer][14:23:47.891][T+2768ms][S+0ms][Δ2768ms][Tick#138] ✓ Purchase completed: 5 Biofuel (gained 5, target was 5)
```

### Level 1: Normal (Recommended)
**What you see**:
- Everything from Minimal
- State transitions with reasons
- Phase announcements (PHASE 1: OPENING DESK)
- Success/failure confirmations
- Shopping progress updates

**Example**:
```
[UnifiedSkyMartBuyer][14:23:45.234][T+111ms][S+0ms][Δ111ms][Tick#6] === PHASE 1: OPENING DESK ===
[UnifiedSkyMartBuyer][14:23:45.456][T+333ms][S+222ms][Δ222ms][Tick#17] State Transition: OPEN_DESK → WAIT_FOR_DESK (desk command sent) [Duration: 222ms]
[UnifiedSkyMartBuyer][14:23:46.123][T+1000ms][S+667ms][Δ667ms][Tick#50] ✓ Desk opened successfully!
```

### Level 2: Verbose (Troubleshooting)
**What you see**:
- Everything from Normal
- GUI change detection
- Click action details
- Delay scheduling reasons
- Inventory state changes
- Purchase progress details

**Example**:
```
[BiofuelBuyer][14:23:45.567][T+444ms][S+123ms][Δ100ms][Tick#22] GUI Change: 'null' → 'Desk'
[BiofuelBuyer][14:23:45.678][T+555ms][S+234ms][Δ111ms][Tick#28] Clicking SkyMart: Slot#13 ('SkyMart Portal') with LEFT
[BiofuelBuyer][14:23:45.789][T+666ms][S+345ms][Δ111ms][Tick#33] Delay Scheduled: 2000ms for SkyMart opening timeout
[BiofuelBuyer][14:23:46.234][T+1111ms][S+790ms][Δ445ms][Tick#56] Pre-purchase inventory: 2 Biofuel
```

### Level 3: Extreme (Deep Debugging)
**What you see**:
- Everything from Verbose
- Every tick processing
- Detailed inventory status
- Wait time remaining updates (every 20 ticks)
- Individual shift-click operations
- Micro-timing details

**Example**:
```
[UnifiedSkyMartBuyer][14:23:45.123][T+123ms][S+45ms][Δ5ms][Tick#6] Processing tick in state: WAIT_FOR_DESK
[UnifiedSkyMartBuyer][14:23:45.234][T+234ms][S+156ms][Δ111ms][Tick#12] Inventory Status: Open=true, Name='Desk'
[UnifiedSkyMartBuyer][14:23:45.345][T+345ms][S+267ms][Δ111ms][Tick#17] Waiting for delay: 1655ms remaining (State: WAIT_FOR_SKYMART)
[UnifiedSkyMartBuyer][14:23:45.456][T+456ms][S+378ms][Δ111ms][Tick#23] Shift-click #3 on Biofuel
```

## Common Debug Scenarios

### Scenario 1: Buyer Not Starting
**Problem**: Buyer doesn't seem to activate
**Debug Level**: Normal (1)
**Look for**:
- "Start request rejected - already running" (buyer already active)
- "Session start rejected" messages
- Missing "=== STARTING *** PURCHASE SESSION ===" message

### Scenario 2: GUI Navigation Fails
**Problem**: Buyer gets stuck on desk/SkyMart/Farming Essentials
**Debug Level**: Verbose (2)
**Enable**: Log GUI Detection Issues = ON
**Look for**:
- "GUI Change" messages showing stuck on wrong interface
- "FAILED: *** opening timeout" messages
- "Cannot find *** button" error messages
- Long delays without GUI changes

### Scenario 3: Purchase Issues
**Problem**: Items not being purchased correctly
**Debug Level**: Verbose (2)
**Look for**:
- "Cannot find '***' in Farming Essentials" (item name issues)
- "Pre-purchase inventory" vs "Post-purchase inventory" discrepancies
- "WARNING: Incomplete purchase" messages
- "Shift-click" action logs

### Scenario 4: Performance Issues
**Problem**: Buyer taking too long or hanging
**Debug Level**: Verbose (2)
**Enable**: Log Purchase Performance Stats = ON
**Look for**:
- High elapsed times in format headers
- "Waiting for delay" messages with long durations
- "Duration" values in state transitions
- Session duration in stop messages

### Scenario 5: Inventory Detection Issues
**Problem**: Buyer can't find items or inventory
**Debug Level**: Extreme (3)
**Look for**:
- "Inventory Status" messages
- "Pre-purchase inventory: 0 ***" (item not detected)
- Alternative item name searches
- GUI detection timing

## Performance Impact

**Debug levels and performance**:
- **Minimal/Normal**: Negligible impact, safe for production
- **Verbose**: Small impact, acceptable for troubleshooting
- **Extreme**: Moderate impact, use only for deep debugging

**Recommendations**:
- **Production**: Keep debug OFF or use Minimal level
- **Troubleshooting**: Use Verbose level temporarily
- **Development**: Use Extreme level only when needed

## Debug Log Analysis Tools

### Filtering Logs
Use these patterns to filter logs:
- `[BiofuelBuyer]` - Only BiofuelBuyer messages
- `[T+.*ms]` - Filter by session time
- `State Transition:` - Only state changes
- `✓\|✗` - Only success/failure messages
- `WARNING\|ERROR` - Only problems

### Key Timing Metrics
- **Session Duration**: Time from start to completion
- **State Duration**: Time spent in each state
- **GUI Response Time**: Time between action and GUI change
- **Purchase Time**: Time to complete each item purchase

### Success Indicators
- `✓` symbols indicate successful operations
- `✗` symbols indicate failures
- "Session completed" indicates full completion
- Final purchase results show success counts

## Configuration Examples

### Basic Troubleshooting Setup
```
Master SkyMart Debug: OFF
BiofuelBuyer Debug: ON
BiofuelBuyer Level: Verbose
Log GUI Detection Issues: ON
Log Performance Stats: OFF
```

### Performance Analysis Setup
```
Master SkyMart Debug: ON
Master Debug Level: Verbose
Log Performance Stats: ON
Log GUI Detection Issues: ON
```

### Production Monitoring Setup
```
Master SkyMart Debug: ON
Master Debug Level: Minimal
Log Performance Stats: OFF
Log GUI Detection Issues: ON
```

### Deep Debugging Setup (Temporary Use)
```
Master SkyMart Debug: ON
Master Debug Level: Extreme
Log Performance Stats: ON
Log GUI Detection Issues: ON
```

## Troubleshooting Tips

1. **Start with Verbose level (2)** - good balance of detail vs spam
2. **Enable GUI Detection Issues** - catches most navigation problems
3. **Check timestamps** - look for long gaps indicating hangs
4. **Monitor state transitions** - ensure proper progression
5. **Compare pre/post purchase inventories** - verify item detection
6. **Use session timing** - identify slow operations
7. **Filter by buyer name** - isolate specific buyer issues
8. **Look for retry attempts** - identify persistent problems

## When to Contact Support

Include debug logs when reporting issues, especially:
- Repeated timeout messages
- State transition loops
- GUI detection failures
- Purchase completion issues
- Performance degradation

**Always include**:
- Your debug configuration settings
- Full session logs (start to completion)
- Minecraft version and server details
- Other active macros/mods