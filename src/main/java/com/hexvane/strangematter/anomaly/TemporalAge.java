package com.hexvane.strangematter.anomaly;

/** Minecraft's setAge(-24000) translated to 1,200 seconds of loaded simulation time. */
public final class TemporalAge {
    public String world, adultRole, juvenileRole;
    public double remainingSeconds;
    public TemporalAge() {}
    public TemporalAge(String world,String adult,String juvenile) {
        this.world=world;adultRole=adult;juvenileRole=juvenile;remainingSeconds=1200;
    }
}
