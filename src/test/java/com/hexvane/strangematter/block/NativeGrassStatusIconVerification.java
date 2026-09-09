package com.hexvane.strangematter.block;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import java.lang.foreign.MemorySegment;

/** Confirms the texture and effect HUD icon survive the native asset wire format. */
public final class NativeGrassStatusIconVerification {
    public static void verify() {
        var grass=BlockType.getAssetMap().getAsset("SM_Anomalous_Grass");
        require(grass!=null,"Anomalous grass loaded");
        var packet=grass.toPacket();var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
        var decoded=com.hypixel.hytale.protocol.BlockType.toObject(bytes);
        require("BlockTextures/StrangeMatter/Anomalous_Grass_Transition.png".equals(decoded.transitionTexture),"Colored transition reaches the native client definition");
        require(decoded.tint.top==-1&&decoded.tint.bottom==-1&&decoded.tint.front==-1&&decoded.tint.back==-1
            &&decoded.tint.left==-1&&decoded.tint.right==-1,"Already painted terrain is not tinted a second time");
        require(decoded.biomeTint.top==0&&decoded.biomeTint.bottom==0&&decoded.biomeTint.front==0
            &&decoded.biomeTint.back==0&&decoded.biomeTint.left==0&&decoded.biomeTint.right==0,"Biome tint cannot alter the current painted grass color");
        require(decoded.transitionToGroups!=null&&decoded.transitionToGroups.length==7,"Grass keeps all existing neighboring block transition groups");
        var effect=EntityEffect.getAssetMap().getAsset("SM_Cognitive_Dissonance");
        require(effect!=null,"Thoughtwell effect loaded");
        var effectPacket=effect.toPacket();var effectBytes=MemorySegment.ofArray(new byte[effectPacket.computeSize()]);effectPacket.serialize(effectBytes,0);
        var decodedEffect=com.hypixel.hytale.protocol.EntityEffect.toObject(effectBytes);
        require("UI/StatusEffects/SM_Cognitive_Dissonance.png".equals(decodedEffect.statusEffectIcon),"Thoughtwell status icon survives native packet serialization");
        require(decodedEffect.debuff&&decodedEffect.duration==5,"Thoughtwell duration and debuff behavior remain intact");
        System.out.println("NATIVE_GRASS_STATUS_ICON_VERIFICATION_PASSED: native colored border reference, unchanged terrain tint and Thoughtwell HUD icon packet");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
