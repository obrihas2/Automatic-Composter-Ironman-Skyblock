package com.jelly.farmhelperv2.hud;

import cc.polyfrost.oneconfig.config.core.OneColor;
import cc.polyfrost.oneconfig.hud.TextHud;
import com.jelly.farmhelperv2.config.FarmHelperConfig;
import com.jelly.farmhelperv2.feature.impl.AutoComposter;
import com.jelly.farmhelperv2.util.LogUtils;
import net.minecraft.client.Minecraft;

import java.util.List;

public class ComposterHUD extends TextHud {
    public ComposterHUD() {
        super(true, 1f, 10f, 0.5f, true, true, 1, 5, 5, new OneColor(0, 0, 0, 150), false, 2, new OneColor(0, 0, 0, 127));
    }

    @Override
    protected void getLines(List<String> lines, boolean example) {
        if (Minecraft.getMinecraft().thePlayer == null || Minecraft.getMinecraft().theWorld == null) return;
        // Only show when composter HUD is relevant; follow Streamer Mode hidden policy via HUD's own visibility
        lines.add("§6§lComposter HUD");

        if (FarmHelperConfig.hudComposterShowStatus) {
            lines.add("Enabled: " + FarmHelperConfig.autoComposter);
            lines.add("Loop: " + (FarmHelperConfig.autoComposterBackgroundLoopEnabled ? ("ON, " + FarmHelperConfig.autoComposterLoopIntervalMinutes + "m") : "OFF"));
        }

        AutoComposter ac = AutoComposter.getInstance();

        if (FarmHelperConfig.autoComposterBackgroundLoopEnabled && !ac.isRunning() && ac.isNextRunScheduled()) {
            long remaining = ac.getNextRunRemainingMs();
            lines.add("Next run in: " + com.jelly.farmhelperv2.util.LogUtils.formatTime(remaining));
        }

        if (FarmHelperConfig.hudComposterShowRunState) {
            lines.add("Running: " + ac.isRunning());
            lines.add("State: " + ac.getMainState());
            lines.add("Travel: " + ac.getTravelState());
            lines.add("Composter: " + ac.getComposterState());
        }

        if (FarmHelperConfig.hudComposterShowTiming) {
            lines.add("Click Delay: " + FarmHelperConfig.autoComposterClickDelayMs + " ms");
            lines.add("Fuel: batch=" + FarmHelperConfig.autoComposterFuelBatchSize + ", delay=" + FarmHelperConfig.autoComposterFuelClickDelayMs + " ms");
        }

        if (FarmHelperConfig.hudComposterShowItems) {
            StringBuilder sb = new StringBuilder();
            sb.append("Items: ");
            boolean first = true;
            if (FarmHelperConfig.autoComposterUseEnchantedWheat) { sb.append(first?"":" ,"); sb.append("E. Wheat"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedCactus) { sb.append(first?"":" ,"); sb.append("E. Cactus"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedCarrot) { sb.append(first?"":" ,"); sb.append("E. Carrot"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedFeather) { sb.append(first?"":" ,"); sb.append("E. Feather"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedCocoaBeans) { sb.append(first?"":" ,"); sb.append("E. Cocoa"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedLeather) { sb.append(first?"":" ,"); sb.append("E. Leather"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedMelon) { sb.append(first?"":" ,"); sb.append("E. Melon"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedRedMushroom) { sb.append(first?"":" ,"); sb.append("E. Red Mushroom"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedMutton) { sb.append(first?"":" ,"); sb.append("E. Mutton"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedNetherWart) { sb.append(first?"":" ,"); sb.append("E. Wart"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedPork) { sb.append(first?"":" ,"); sb.append("E. Pork"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedPotato) { sb.append(first?"":" ,"); sb.append("E. Potato"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedPumpkin) { sb.append(first?"":" ,"); sb.append("E. Pumpkin"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedRabbit) { sb.append(first?"":" ,"); sb.append("E. Rabbit"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedChicken) { sb.append(first?"":" ,"); sb.append("E. Chicken"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedSeeds) { sb.append(first?"":" ,"); sb.append("E. Seeds"); first=false; }
            if (FarmHelperConfig.autoComposterUseEnchantedSugarCane) { sb.append(first?"":" ,"); sb.append("E. SugarCane"); first=false; }
            if (FarmHelperConfig.autoComposterUseBoxOfSeeds) { sb.append(first?"":" ,"); sb.append("Box of Seeds"); first=false; }
            lines.add(sb.toString());
        }

        if (FarmHelperConfig.hudComposterShowControls) {
            lines.add("Min purse (k): " + FarmHelperConfig.autoComposterMinMoney);
            lines.add("Pause in Jacob: " + FarmHelperConfig.pauseAutoComposterDuringJacobsContest);
        }

        if (FarmHelperConfig.hudComposterShowLocation) {
            lines.add("Composter XYZ: " + FarmHelperConfig.composterX + "," + FarmHelperConfig.composterY + "," + FarmHelperConfig.composterZ);
            lines.add("Travel: " + (FarmHelperConfig.autoComposterTravelMethod ? "Walk" : "Fly"));
        }

        if (FarmHelperConfig.hudComposterShowHints) {
            lines.add("Run now via Config → Auto Composter → Trigger now");
        }
    }
}
