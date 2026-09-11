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
        if(c.burnerGeneration<1||c.burnerCapacity<1||c.generatorTransfer<1||c.condenserConsumption<1||c.condenserCapacity<1||c.condenserTicksPerShard<1||c.riftGeneration<1||c.riftCapacity<1||c.maxStabilizersPerRift<1||c.conduitTransfer<1||c.maxNetworkSize<1||c.maxNetworkSize>4096||c.forgeCraftTicks<1||!Double.isFinite(c.conduitDistancePenalty)||!Double.isFinite(c.condenserRadius)||!Double.isFinite(c.stabilizerRadius)||c.condenserRadius<=0||c.stabilizerRadius<=0||c.conduitDistancePenalty<0||c.conduitDistancePenalty>1||c.levitationHeight<1) throw new IOException("Invalid machine balance configuration");
        if(!Double.isFinite(c.nullifierRadius)||c.nullifierRadius<=0||c.nullifierRadius>128)throw new IOException("Nullifier radius must be greater than zero and no more than 128 blocks");
        if(!Files.exists(p)) Files.writeString(p,gson.toJson(c)+"\n");
        return c;
    }
}
