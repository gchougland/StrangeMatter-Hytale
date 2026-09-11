package com.hexvane.strangematter;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;

/** Machine balance in original 20 Hz ticks. Field settings live in anomalies-config.json. */
public final class StrangeMatterConfig {
    public int burnerGeneration = 20, burnerCapacity = 10000, generatorTransfer = 1000;
    public int condenserConsumption = 2, condenserCapacity = 1000, condenserTicksPerShard = 1500;
    public int riftGeneration = 100, riftCapacity = 100000, maxStabilizersPerRift = 3;
    public int conduitTransfer = 500, maxNetworkSize = 64, forgeCraftTicks = 100;
    public int forgeCapacity = 10000, forgeConsumption = 10;
    public int separatorCapacity = 10000, separatorConsumption = 10, separatorCraftTicks = 80;
    public int fluxCapacity = 5000, fluxConsumption = 5;
    public int assemblerCapacity = 10000, assemblerTierCapacity = 5000;
    public int assemblerConsumption = 10, assemblerTierConsumption = 5;
    public double assemblerTimeMultiplier = 2.0, assemblerMinimumSeconds = 3.0;
    public double conduitDistancePenalty = .05, condenserRadius = 10, stabilizerRadius = 16;
    public double nullifierRadius = 12;
    public int levitationHeight = 16;
    public boolean giveStarterTablet = true;

    public static StrangeMatterConfig load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path p=directory.resolve("machines-config.json");
        var gson=new GsonBuilder().setPrettyPrinting().create();
        StrangeMatterConfig c=Files.exists(p)?gson.fromJson(Files.readString(p),StrangeMatterConfig.class):new StrangeMatterConfig();
        if(c==null) throw new IOException("machines-config.json cannot be null");
        if(!Double.isFinite(c.assemblerTimeMultiplier)||c.assemblerTimeMultiplier<=0||c.assemblerTimeMultiplier>100||!Double.isFinite(c.assemblerMinimumSeconds)||c.assemblerMinimumSeconds<.05||c.assemblerMinimumSeconds>3600)throw new IOException("Invalid assembler processing time configuration");
        if(c.forgeCapacity<1||c.forgeConsumption<1||c.separatorCapacity<1||c.separatorConsumption<1||c.separatorCraftTicks<1||c.fluxCapacity<1||c.fluxConsumption<1||c.assemblerCapacity<1||c.assemblerTierCapacity<0||c.assemblerConsumption<1||c.assemblerTierConsumption<0||(long)c.assemblerCapacity+2L*c.assemblerTierCapacity>Integer.MAX_VALUE||(long)c.assemblerConsumption+2L*c.assemblerTierConsumption>Integer.MAX_VALUE)throw new IOException("Invalid automation power configuration");
        if(c.burnerGeneration<1||c.burnerCapacity<1||c.generatorTransfer<1||c.condenserConsumption<1||c.condenserCapacity<1||c.condenserTicksPerShard<1||c.riftGeneration<1||c.riftCapacity<1||c.maxStabilizersPerRift<1||c.conduitTransfer<1||c.maxNetworkSize<1||c.maxNetworkSize>4096||c.forgeCraftTicks<1||!Double.isFinite(c.conduitDistancePenalty)||!Double.isFinite(c.condenserRadius)||!Double.isFinite(c.stabilizerRadius)||c.condenserRadius<=0||c.stabilizerRadius<=0||c.conduitDistancePenalty<0||c.conduitDistancePenalty>1||c.levitationHeight<1) throw new IOException("Invalid machine balance configuration");
        if(!Double.isFinite(c.nullifierRadius)||c.nullifierRadius<=0||c.nullifierRadius>128)throw new IOException("Nullifier radius must be greater than zero and no more than 128 blocks");
        if(!Files.exists(p)) Files.writeString(p,gson.toJson(c)+"\n");
        return c;
    }
}
