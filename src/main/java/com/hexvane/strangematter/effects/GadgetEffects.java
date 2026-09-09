package com.hexvane.strangematter.effects;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

/** Finite native world effects. Call on the owning world thread; no UI animation traffic. */
public final class GadgetEffects {
    private GadgetEffects() {}
    public static void particle(World world,String id,Vector3d position) {
        ParticleUtil.spawnParticleEffect(id,position,world.getEntityStore().getStore());
    }
    public static void sound(World world,String id,Vector3d position) {
        int index=SoundEvent.getAssetMap().getIndex(id);
        if(index!=SoundEvent.EMPTY_ID)
            SoundUtil.playSoundEvent3d(index,SoundCategory.SFX,position.x,position.y,position.z,world.getEntityStore().getStore());
    }
    public static void use(World world,String effect,Vector3d position) {
        particle(world,effect,position);
        sound(world,effect+"_SFX",position);
    }
    /** Bounded trace: two short sparks per point, never an anomaly-sized emitter per sample. */
    public static void beam(World world,String effect,Vector3d from,Vector3d to) {
        double length=from.distance(to);
        if(!Double.isFinite(length))return;
        int steps=Math.max(1,Math.min(48,(int)Math.ceil(length/.3)));
        for(int i=0;i<=steps;i++) particle(world,effect,new Vector3d(from).lerp(to,(double)i/steps));
    }
    /** Direction markers span the usable shaft; rings mark its floor and exact ceiling. */
    public static void column(World world,Vector3d bottom,double height,boolean ascending) {
        if(!Double.isFinite(height)||height<=0)return;
        height=Math.min(height,128);
        particle(world,"SM_Levitation_Range",bottom);
        particle(world,"SM_Levitation_Range",new Vector3d(bottom).add(0,height,0));
        int count=Math.max(1,Math.min(32,(int)Math.ceil(height/2)));
        for(int i=0;i<count;i++) particle(world,ascending?"SM_Levitation_Up":"SM_Levitation_Down",
            new Vector3d(bottom).add(0,(i+.5)*height/count,0));
    }
}
