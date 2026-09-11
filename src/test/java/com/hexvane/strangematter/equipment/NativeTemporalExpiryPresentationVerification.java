package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.protocol.BlockParticleEvent;
import com.hypixel.hytale.server.core.asset.type.blockparticle.config.BlockParticleSet;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem;
import java.lang.foreign.MemorySegment;

/** Expiry uses the native removed block's Break particle event and serialized color. */
public final class NativeTemporalExpiryPresentationVerification {
    public static void verify(){
        var block=BlockType.getAssetMap().getAsset("SM_Time_Dilation_Block");
        if(block==null)throw new AssertionError("Temporal block must be loaded");
        var packet=block.toPacket();
        var wire=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(wire,0);
        var decoded=com.hypixel.hytale.protocol.BlockType.toObject(wire);
        if(!"Stone".equals(decoded.blockParticleSetId))throw new AssertionError("Native block disappearance routing must survive serialization");
        var color=decoded.particleColor;
        if(color==null||(color.red&255)!=255||(color.green&255)!=218||(color.blue&255)!=104)
            throw new AssertionError("Native time dilation block packet must carry yellow expiry tint #ffda68");
        var set=BlockParticleSet.getAssetMap().getAsset("Stone");
        if(set==null||!"Block_Break_Stone".equals(set.getParticleSystemIds().get(BlockParticleEvent.Break)))
            throw new AssertionError("Native Stone set must retain the existing block disappearance effect");
        if(ParticleSystem.getAssetMap().getAsset(set.getParticleSystemIds().get(BlockParticleEvent.Break))==null)
            throw new AssertionError("The native disappearance particle system must resolve");
        System.out.println("NATIVE_TEMPORAL_EXPIRY_PRESENTATION_VERIFICATION_PASSED: native yellow block color survives wire serialization and its disappearance particle system resolves.");
    }
}
