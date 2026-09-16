package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.joml.Vector3i;
import java.util.*;

/** All addresses are native filler-resolved block origins. Resolving never loads a chunk. */
final class TubeEndpoints {
    record Position(int x,int y,int z) {
        Position(Vector3i p){this(p.x,p.y,p.z);}
        Vector3i vector(){return new Vector3i(x,y,z);}
        Position offset(int face){return new Position(x+DX[face],y+DY[face],z+DZ[face]);}
    }
    static final int[] DX={1,-1,0,0,0,0},DY={0,0,1,-1,0,0},DZ={0,0,0,0,1,-1};
    static final String[] NAMES={"East","West","Up","Down","South","North"};
    record Address(String world,int x,int y,int z,UUID identity,String section) {
        Position position(){return new Position(x,y,z);}
    }
    record Endpoint(World world,Position position,Ref<ChunkStore> ref,TubeEndpointReceipts receipts,TubePort port,boolean suppliedByProvider) {
        Endpoint(World world,Position position,Ref<ChunkStore> ref,TubeEndpointReceipts receipts,TubePort port){this(world,position,ref,receipts,port,false);}
        Address address(){return new Address(world.getName(),position.x,position.y,position.z,receipts.identity(),port.section());}
        void dirty(){var info=ref.getStore().getComponent(ref,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving();}
    }
    private TubeInventoryProvider provider;
    TubeEndpoints(TubeInventoryProvider provider){this.provider=provider;}
    void provider(TubeInventoryProvider value){provider=value;}
    static Position origin(World world,Position point){
        if(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(point.x,point.z))==null)return null;
        var p=SimpleBlockInteraction.resolveBaseBlockPosition(world,new BlockPosition(point.x,point.y,point.z));
        var origin=new Position(p.x,p.y,p.z);
        return WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(origin.x,origin.z))==null?null:origin;
    }
    static Ref<ChunkStore> block(World world,Position point){
        var origin=origin(world,point);return origin==null?null:BlockModule.getBlockEntity(world,origin.x,origin.y,origin.z);
    }
    List<Endpoint> all(World world,Position contact,boolean createReceipt){
        var origin=origin(world,contact);if(origin==null)return List.of();
        if(com.hexvane.strangematter.equipment.GraviticChestMarker.locked(world,origin.vector()))return List.of();
        var ref=BlockModule.getBlockEntity(world,origin.x,origin.y,origin.z);if(ref==null||!ref.isValid())return List.of();
        var store=world.getChunkStore().getStore();List<TubePort> ports=provider==null?List.of():provider.ports(world,origin.vector());
        boolean suppliedByProvider=!ports.isEmpty();
        if(ports.isEmpty()){
            if(!nativeEndpoint(world,origin))return List.of();
            var furnace=store.getComponent(ref,ProcessingBenchBlock.getComponentType());
            if(furnace!=null){
                var result=new ArrayList<TubePort>();
                if(furnace.getInputContainer()!=null)result.add(new TubePort("input","Ingredients",furnace.getInputContainer(),s->true,true));
                if(furnace.getFuelContainer()!=null)result.add(new TubePort("fuel","Fuel",furnace.getFuelContainer(),s->true,true));
                if(furnace.getOutputContainer()!=null)result.add(new TubePort("output","Finished items",furnace.getOutputContainer(),s->false,true));
                ports=result;
            }else{
                var chest=store.getComponent(ref,ItemContainerBlock.getComponentType());
                if(chest!=null)ports=List.of(new TubePort("storage","Storage",chest.getItemContainer(),s->true,true));
            }
        }
        if(ports.isEmpty())return List.of();
        var receipts=store.getComponent(ref,TubeEndpointReceipts.getComponentType());
        if(receipts==null){if(!createReceipt)return List.of();receipts=new TubeEndpointReceipts();store.addComponent(ref,TubeEndpointReceipts.getComponentType(),receipts);
            var info=store.getComponent(ref,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving();}
        var result=new ArrayList<Endpoint>();for(var port:ports)result.add(new Endpoint(world,origin,ref,receipts,port,suppliedByProvider));return result;
    }
    private static boolean nativeEndpoint(World world,Position origin){
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(origin.x,origin.z));if(chunk==null)return false;
        var type=WorldAccess.blockType(chunk,origin.x,origin.y,origin.z);if(type==null)return false;
        String id=type.getDefaultStateKey()==null?type.getId():type.getDefaultStateKey();
        if(id.equals("SM_Resonite_Chest")||id.equals("SM_Resonite_Chest_Large"))return true;
        return com.hypixel.hytale.assetstore.map.DefaultAssetMap.DEFAULT_PACK_KEY.equals(com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAssetPack(id));
    }
    Endpoint resolve(World world,Position point,String section,boolean create){return all(world,point,create).stream().filter(p->p.port.section().equals(section)).findFirst().orElse(null);}
    Endpoint resolve(World world,Address address){
        if(!world.getName().equals(address.world))return null;var endpoint=resolve(world,address.position(),address.section,false);
        return endpoint!=null&&endpoint.receipts.owns(address.identity)?endpoint:null;
    }
    boolean mayAccess(Endpoint endpoint,UUID owner){
        // A factory provider does not own ordinary stock chests or furnace sections.
        // Its fail-closed access check applies only to ports it actually supplied.
        return endpoint.suppliedByProvider?provider!=null&&provider.mayAccess(endpoint.world,endpoint.position.vector(),owner):nativeEndpoint(endpoint.world,endpoint.position);
    }
    static void closeWindows(Endpoint endpoint){
        var store=endpoint.ref.getStore();
        var chest=store.getComponent(endpoint.ref,ItemContainerBlock.getComponentType());
        if(chest!=null)closeWindows(endpoint.world,chest.getWindows().values());
        var bench=store.getComponent(endpoint.ref,com.hypixel.hytale.builtin.crafting.component.BenchBlock.getComponentType());
        if(bench!=null)closeWindows(endpoint.world,bench.getWindows().values());
    }
    private static void closeWindows(World world,Collection<? extends com.hypixel.hytale.server.core.entity.entities.player.windows.Window> windows){
        for(var window:List.copyOf(windows)){
            var player=window.getPlayerRef();if(player==null)continue;var ref=player.getReference();if(ref==null||!ref.isValid())continue;
            if(ref.getStore().getExternalData().getWorld()==world)window.close(ref,ref.getStore());
        }
    }
}
