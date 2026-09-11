package com.hexvane.strangematter.anomaly;

import org.joml.Vector3d;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

/** Persistent identity survives containment and relocation; runtime references never enter the save. */
public final class AnomalyRecord {
    public UUID id;
    public AnomalyType type;
    public String world;
    public double x, y, z;
    /** One-time placement lift. Grounded terrain/crop searches retain their previous anchor. */
    public double placementLift;
    public boolean natural, released, contained, enabled = true;
    public UUID pairedGate, capsuleNonce;
    /** Zero is an ordinary anomaly gate; player gun endpoints persist their cyan/violet identity. */
    public int portalChannel;
    public Set<UUID> shadowMobs = new HashSet<>();
    public Map<UUID,double[]> shadowMobPositions = new HashMap<>();
    public transient double age, particles, primary, secondary, sound;
    public transient boolean creatingPair;

    public AnomalyRecord() {}
    public AnomalyRecord(UUID id, AnomalyType type, String world, Vector3d position, boolean natural) {
        this.id=id; this.type=type; this.world=world; this.natural=natural; move(position);
    }
    public void move(Vector3d position) { x=position.x; y=position.y; z=position.z; }
    public Vector3d position() { return new Vector3d(x,y,z); }
    public double terrainReferenceY() { return y-placementLift; }
    public UUID id() { return id; }
    public AnomalyType type() { return type; }
    public boolean scannable() { return natural && !released && !contained; }
    public boolean active() { return enabled && !contained; }
    public String particleId() {
        return type==AnomalyType.WARP_GATE && portalChannel>0
            ? "SM_Warp_Gate_"+(portalChannel==1?"Cyan":"Purple") : type.particleId;
    }
    public double distanceSquared(Vector3d p) { double dx=x-p.x,dy=y-p.y,dz=z-p.z; return dx*dx+dy*dy+dz*dz; }
}
