package com.hexvane.strangematter.research;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;

/** Original default minigame settings, measured in Minecraft-compatible 20 Hz ticks. */
public final class ResearchSettings {
    public boolean enableMinigames = true;
    public double instabilityDecreaseRate = .004, instabilityBaseIncreaseRate = .001;
    public int energyRequiredAlignmentTicks = 100, energyDriftDelayTicks = 600;
    public double energyAmplitudeStep = .05, energyPeriodStep = .05;
    public double gravityBalanceThreshold = .1;
    public int gravityDriftDelayTicks = 1000;
    public double shadowAlignmentThreshold = 10, shadowRotationStep = 15;
    public int shadowDriftDelayTicks = 200;
    public double spaceStabilityThreshold = .1, spaceWarpAdjustment = .05;
    public int spaceDriftDelayTicks = 100;
    public double timeSpeedThreshold = .15, timeSpeedAdjustment = .1, timeSnapThreshold = .05;
    public int timeDriftDelayTicks = 200;
    public int cognitionMatchDuration = 50, cognitionDifficulty = 3;
    static ResearchSettings load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("research-config.json");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        ResearchSettings settings = Files.exists(path) ? gson.fromJson(Files.readString(path), ResearchSettings.class) : new ResearchSettings();
        if (settings == null) throw new IOException("research-config.json cannot be null");
        settings.validate();
        if (!Files.exists(path)) Files.writeString(path, gson.toJson(settings) + "\n");
        return settings;
    }
    void validate() {
        range(instabilityDecreaseRate, .0001, .1); range(instabilityBaseIncreaseRate, .0001, .1);
        range(energyRequiredAlignmentTicks, 20, 1200); range(energyDriftDelayTicks, 60, 2400);
        range(energyAmplitudeStep, .01, .5); range(energyPeriodStep, .01, .5);
        range(gravityBalanceThreshold, .01, 1); range(gravityDriftDelayTicks, 20, 1200);
        range(shadowAlignmentThreshold, 1, 50); range(shadowRotationStep, 1, 90); range(shadowDriftDelayTicks, 20, 1200);
        range(spaceStabilityThreshold, .01, 1); range(spaceWarpAdjustment, .01, .5); range(spaceDriftDelayTicks, 20, 1200);
        range(timeSpeedThreshold, .01, 1); range(timeSpeedAdjustment, .01, 1); range(timeSnapThreshold, .01, .5); range(timeDriftDelayTicks, 20, 1200);
        range(cognitionMatchDuration, 20, 600); range(cognitionDifficulty, 2, 8);
    }
    private static void range(double value, double min, double max) { if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid research configuration value: " + value + " (expected " + min + ".." + max + ")"); }
}
