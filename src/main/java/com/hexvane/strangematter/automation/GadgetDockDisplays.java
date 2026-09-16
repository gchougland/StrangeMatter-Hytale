package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.model.BlockyModelBoundsParser;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.item.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Vector3d;

/** Non-persistent presentation of the real dock slot; the native block inventory owns its item. */
final class GadgetDockDisplays {
    record Layout(double x,double y,double z,double height,double span){}
    private record Display(World world,FactoryComponent component,String item,int rotation,Ref<EntityStore> ref){}
    private static final Map<String,CompletableFuture<Layout>> layouts=new ConcurrentHashMap<>();
    private final Map<String,Display> displays=new ConcurrentHashMap<>();

    static CompletableFuture<Layout> prepare(ItemStack stack){
        var path=stack.getItem().getModel();var asset=path==null?null:CommonAssetRegistry.getByName(path);
        if(asset==null)return CompletableFuture.completedFuture(layout(Box.horizontallyCentered(1,1,1)));
        return layouts.computeIfAbsent(asset.getName()+":"+asset.getHash(),key->asset.getBlob().thenApplyAsync(bytes->{
            var bounds=BlockyModelBoundsParser.computeBounds(asset);
            return layout(bounds==null?Box.horizontallyCentered(1,1,1):bounds);
        }).exceptionally(error->layout(Box.horizontallyCentered(1,1,1))));
    }
    private static Layout layout(Box box){return new Layout((box.min.x+box.max.x)/2,(box.min.y+box.max.y)/2,
            (box.min.z+box.max.z)/2,box.height(),Math.max(box.width(),Math.max(box.height(),box.depth())));}

    void sync(World world,MachineState state,FactoryComponent component){
        world.debugAssertInTickingThread();
        var stack=component==null?null:component.charging.getItemStack((short)0);
        if(!GadgetCharging.accepts(stack)){remove(state.key());return;}
        var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(state.x,state.z));
        if(chunk==null){remove(state.key());return;}
        int rotation=WorldAccess.rotation(chunk,state.x,state.y,state.z);
        var old=displays.get(state.key());
        if(old!=null&&old.world==world&&old.component==component&&old.item.equals(stack.getItemId())
                &&old.rotation==rotation&&old.ref.isValid())return;
        remove(state.key());
        var layout=prepare(stack).getNow(null);if(layout==null)return;
        var ref=spawn(world,state,stack,rotation,layout);
        displays.put(state.key(),new Display(world,component,stack.getItemId(),rotation,ref));
    }
    private static Ref<EntityStore> spawn(World world,MachineState state,ItemStack stack,int rotationIndex,Layout layout){
        boolean burner=state.id.equals("SM_Resonant_Burner");
        float scale=(float)Math.min(1,(burner?.34:.58)/Math.max(.001,layout.span));
        var blockRotation=RotationTuple.get(rotationIndex);
        var itemRotation=GadgetEnergy.PACK.equals(stack.getItemId())
                ?RotationTuple.compose(blockRotation,RotationTuple.of(Rotation.OneEighty,Rotation.None)):blockRotation;
        // Match the authored side cradle and station tray. Bounds centering keeps every gadget
        // above the contacts regardless of its hand-held model's original pivot.
        var center=new Vector3d(burner?19.7/32:0,(burner?.515:.37)+layout.height*scale/2,burner?3.0/32:4.0/32);
        blockRotation.applyRotationTo(center);
        center.add(state.x+.5,state.y,state.z+.5);
        var pivot=new Vector3d(layout.x*scale,layout.y*scale,layout.z*scale);itemRotation.applyRotationTo(pivot);
        var rotation=new Rotation3f();itemRotation.applyRotationTo(rotation);
        var store=world.getEntityStore().getStore();var holder=EntityStore.REGISTRY.newHolder();
        // Never copy ownership or charge metadata into the presentation entity.
        var display=new ItemStack(stack.getItemId(),1);display.setOverrideDroppedItemAnimation(true);
        holder.addComponent(ItemComponent.getComponentType(),new ItemComponent(display));
        holder.addComponent(EntityScaleComponent.getComponentType(),new EntityScaleComponent(scale));
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(center.sub(pivot),rotation));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(UUIDComponent.getComponentType(),new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(PreventPickup.getComponentType(),PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(),PreventItemMerging.INSTANCE);
        holder.addComponent(Intangible.getComponentType(),Intangible.INSTANCE);
        holder.addComponent(Invulnerable.getComponentType(),Invulnerable.INSTANCE);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
        return store.addEntity(holder,AddReason.SPAWN);
    }
    void retain(World world,Set<String> loaded){
        for(var entry:displays.entrySet())if(entry.getValue().world==world&&!loaded.contains(entry.getKey()))remove(entry.getKey());
    }
    void remove(String key){var display=displays.remove(key);if(display!=null)TubeCarrier.remove(display.ref);}
    void cleanup(World world){retain(world,Set.of());}
    void close(){
        for(var d:List.copyOf(displays.values())){
            if(d.world.isInThread())TubeCarrier.remove(d.ref);
            else try{d.world.execute(()->TubeCarrier.remove(d.ref));}catch(RuntimeException stopped){/* World teardown owns remaining entities. */}
        }
        displays.clear();
    }
    Ref<EntityStore> displayed(MachineState state){var display=displays.get(state.key());return display==null?null:display.ref;}
}
