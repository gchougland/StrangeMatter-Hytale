package com.hexvane.strangematter.research;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;

/** Instrument settings, measured in 20 Hz simulation ticks. */
public final class ResearchSettings {
    public boolean enableMinigames = true;
    public double instabilityDecreaseRate = .004, instabilityBaseIncreaseRate = .001;
    public int energyRequiredAlignmentTicks = 1, energyDriftDelayTicks = 120;
    public int waveSettingsVersion = 1;
    public double energyAmplitudeStep = .05, energyPeriodStep = .05;
    public double gravityBalanceThreshold = .1;
    public int gravityDriftDelayTicks = 90;
    public double shadowAlignmentThreshold = 10, shadowRotationStep = 15;
    public int shadowDriftDelayTicks = 90;
    public double spaceStabilityThreshold = .1, spaceWarpAdjustment = .05;
    public int spaceDriftDelayTicks = 100;
    public int spaceAlignmentMargin = 1;
    public double timeSpeedThreshold = .15, timeSpeedAdjustment = .1, timeSnapThreshold = .05;
    public int timeDriftDelayTicks = 90;
    public int cognitionDriftDelayTicks = 90, stabilityTimingVersion = 1;
    public int disturbanceCooldownTicks = 20;
    public int cognitionMatchDuration = 50, cognitionDifficulty = 3;
    public double cognitionPlaybackSpeed = 2.5;
    public int cognitionCueTicks() { return Math.max(1, (int) Math.round(cognitionMatchDuration / cognitionPlaybackSpeed)); }
    static ResearchSettings load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("research-config.json");
        var gson = new GsonBuilder().setPrettyPrinting().create();
        boolean exists = Files.exists(path), migrated = false;
        var document = exists ? JsonParser.parseString(Files.readString(path)) : null;
        if (document != null && document.isJsonObject() && !document.getAsJsonObject().has("stabilityTimingVersion")) {
            var object = document.getAsJsonObject();
            // Only old shipped defaults migrate. Pack-specific timing and unknown keys survive.
            for (var entry : java.util.Map.of("energyDriftDelayTicks", 600, "gravityDriftDelayTicks", 1000,
                    "shadowDriftDelayTicks", 200, "timeDriftDelayTicks", 200).entrySet()) {
                var value = object.get(entry.getKey());
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                        && value.getAsDouble() == entry.getValue()) object.addProperty(entry.getKey(), 90);
            }
            object.addProperty("stabilityTimingVersion", 1); migrated = true;
        }
        if (document != null && document.isJsonObject() && !document.getAsJsonObject().has("waveSettingsVersion")) {
            var object = document.getAsJsonObject();
            for (var entry : java.util.Map.of("energyRequiredAlignmentTicks", 100, "energyDriftDelayTicks", 90).entrySet()) {
                var value = object.get(entry.getKey());
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                        && value.getAsDouble() == entry.getValue()) object.addProperty(entry.getKey(), entry.getKey().equals("energyDriftDelayTicks") ? 120 : 1);
            }
            object.addProperty("waveSettingsVersion", 1); migrated = true;
        }
        ResearchSettings settings = exists ? gson.fromJson(document, ResearchSettings.class) : new ResearchSettings();
        if (settings == null) throw new IOException("research-config.json cannot be null");
        settings.validate();
        if (!exists || migrated) Files.writeString(path, gson.toJson(migrated ? document : settings) + "\n");
        return settings;
    }
    void validate() {
        range(instabilityDecreaseRate, .0001, .1); range(instabilityBaseIncreaseRate, .0001, .1);
        range(energyRequiredAlignmentTicks, 1, 1200); range(energyDriftDelayTicks, 60, 2400);
        range(energyAmplitudeStep, .01, .5); range(energyPeriodStep, .01, .5);
        range(gravityBalanceThreshold, .01, 1); range(gravityDriftDelayTicks, 20, 1200);
        range(shadowAlignmentThreshold, 1, 50); range(shadowRotationStep, 1, 90); range(shadowDriftDelayTicks, 20, 1200);
        range(spaceStabilityThreshold, .01, 1); range(spaceWarpAdjustment, .01, .5); range(spaceDriftDelayTicks, 20, 1200);
        range(spaceAlignmentMargin, 0, 1); range(cognitionDriftDelayTicks, 20, 2400);
        range(disturbanceCooldownTicks, 1, 200);
        range(timeSpeedThreshold, .01, 1); range(timeSpeedAdjustment, .01, 1); range(timeSnapThreshold, .01, .5); range(timeDriftDelayTicks, 20, 1200);
        validateClockRange();
        range(cognitionMatchDuration, 20, 600); range(cognitionDifficulty, 2, 8);
        range(cognitionPlaybackSpeed, .25, 5);
    }
    private void validateClockRange() {
        int stops = (int) Math.ceil(4 / timeSpeedAdjustment);
        for (int i = 0; i <= stops; i++) {
            double from = Math.min(2, -2 + i * timeSpeedAdjustment); boolean reachable = false;
            for (int j = 0; j <= stops && !reachable; j++) {
                double target = Math.min(2, -2 + j * timeSpeedAdjustment), gap = Math.abs(target - from);
                reachable = Math.abs(target) >= .4 && gap + 1e-8 >= timeSpeedThreshold && gap <= 1 + 1e-8;
            }
            if (!reachable) throw new IllegalArgumentException("Chronal timeSpeedAdjustment " + timeSpeedAdjustment
                    + " and timeSpeedThreshold " + timeSpeedThreshold + " leave no unstable moving target within 1x at speed " + from
                    + ". Reduce the speed threshold or choose a finer compatible adjustment.");
        }
    }
    private static void range(double value, double min, double max) { if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Invalid research configuration value: " + value + " (expected " + min + ".." + max + ")"); }
}
