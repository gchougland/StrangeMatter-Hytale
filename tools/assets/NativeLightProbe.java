import com.hypixel.hytale.server.core.codec.ProtocolCodecs;
import com.hypixel.hytale.protocol.ColorLight;
import org.bson.BsonDocument;
import java.lang.foreign.MemorySegment;
import java.nio.file.*;

/** Direct installed-codec/wire probe. No game, asset regeneration or Gradle is needed. */
public final class NativeLightProbe {
    public static void main(String[] args)throws Exception {
        Path items=Path.of("src/main/resources/Server/Item/Items/StrangeMatter");
        for(String family:new String[]{"Gravitic","Chrono","Energetic","Spatial","Shade","Insight"}){
            for(String fixture:new String[]{"Lamp","Lantern"}){
                String id="SM_"+family+"_Shard_"+fixture;
                var document=BsonDocument.parse(Files.readString(items.resolve(id+".json")));
                var light=ProtocolCodecs.COLOR_LIGHT.decode(document.getDocument("BlockType").getDocument("Light"));
                var wire=MemorySegment.ofArray(new byte[light.computeSize()]);light.serialize(wire,0);
                var decoded=ColorLight.toObject(wire);
                if(!light.equals(decoded))throw new AssertionError(id+" native ColorLight roundtrip changed channels");
                if(Math.min(decoded.red,Math.min(decoded.green,decoded.blue))!=0||Math.max(decoded.red,Math.max(decoded.green,decoded.blue))!=15||decoded.radius!=0)
                    throw new AssertionError(id+" must retain colored voxel emission without a neutral-white component");
                System.out.println(id+" native packet RGB="+decoded.red+","+decoded.green+","+decoded.blue+" radius="+decoded.radius);
            }
        }
        System.out.println("NATIVE_LIGHT_CODEC_WIRE_VERIFICATION_PASSED");
    }
}
