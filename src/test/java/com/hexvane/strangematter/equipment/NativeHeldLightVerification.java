package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.protocol.ColorLight;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import java.lang.foreign.MemorySegment;

/** Runs after actual assets load, validating native asset-to-client lighting. */
public final class NativeHeldLightVerification {
    public static void verify(){
        // Icon-aligned orange-red, gold, cyan, blue, purple and green. Native
        // light intensities exclude the neutral component of the texture RGB.
        int[][] expected={{15,3,0},{15,11,0},{0,11,15},{0,7,15},{6,0,15},{0,15,1}};
        String[] families={"Gravitic","Chrono","Energetic","Spatial","Shade","Insight"};
        int count=0;
        for(int i=0;i<families.length;i++)for(String suffix:new String[]{"Lamp","Lantern"}){
            String id="SM_"+families[i]+"_Shard_"+suffix;
            var asset=BlockType.getAssetMap().getAsset(id);
            if(asset==null)throw new AssertionError(id+" native block asset is missing");
            var packet=asset.toPacket();var light=packet.light;
            if(light==null||!light.equals(asset.getLight()))throw new AssertionError(id+" lost light in its block packet");
            var wire=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(wire,0);
            var decoded=com.hypixel.hytale.protocol.BlockType.toObject(wire).light;
            if(decoded==null||decoded.red!=expected[i][0]||decoded.green!=expected[i][1]||decoded.blue!=expected[i][2]||decoded.radius!=0)
                throw new AssertionError(id+" decoded incorrect client light channels");
            count++;
        }
        for(String id:new String[]{"SM_Field_Scanner","SM_Graviton_Hammer"}){
            var item=Item.getAssetMap().getAsset(id);
            if(item==null||item.toPacket().model==null)throw new AssertionError(id+" held model reference missing from client item packet");
        }
        System.out.println("NATIVE_HELD_LIGHT_VERIFICATION_PASSED: "+count+" loaded block packets preserve fixture RGB through wire serialization");
    }
}
