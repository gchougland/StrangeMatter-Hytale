package com.hexvane.strangematter.automation;

import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.model.BlockyModelBoundsParser;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.item.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Visual only. No pickup, merger, interaction, physics velocity, persistence or item ownership. */
final class TubeCarrier {
    static final double MAX_SPAN=.32;
    record Layout(double x,double y,double z,float scale){
        Vector3d origin(Vector3d center){return new Vector3d(center).add(-x*scale,-y*scale,-z*scale);}
        Vector3d center(Vector3d origin){return new Vector3d(origin).add(x*scale,y*scale,z*scale);}
    }
    private static final ConcurrentHashMap<String,CompletableFuture<Layout>> layouts=new ConcurrentHashMap<>();
    static CompletableFuture<Layout> prepare(ItemStack stack){
        var item=stack.getItem();String path=item.getModel();
        if(path==null&&item.getBlockId()!=null){var block=BlockType.getAssetMap().getAsset(item.getBlockId());if(block!=null)path=block.getCustomModel();}
        var asset=path==null?null:CommonAssetRegistry.getByName(path);
        if(asset==null)return CompletableFuture.completedFuture(layout(Box.horizontallyCentered(1,1,1)));
        String key=asset.getName()+":"+asset.getHash();
        return layouts.computeIfAbsent(key,ignored->asset.getBlob().thenApplyAsync(bytes->{
            var bounds=BlockyModelBoundsParser.computeBounds(asset);
            return layout(bounds==null?Box.horizontallyCentered(1,1,1):bounds);
        }).exceptionally(error->layout(Box.horizontallyCentered(1,1,1))));
    }
    private static Layout layout(Box box){
        double extent=Math.max(box.width(),Math.max(box.height(),box.depth()));
        return new Layout((box.min.x+box.max.x)/2,(box.min.y+box.max.y)/2,(box.min.z+box.max.z)/2,(float)Math.min(1,MAX_SPAN/Math.max(.001,extent)));
    }
    static Ref<EntityStore> spawn(World world,ItemStack stack,Vector3d position){
        var layout=prepare(stack).getNow(null);if(layout==null)return null;
        var store=world.getEntityStore().getStore();var holder=EntityStore.REGISTRY.newHolder();
        var display=TubeStacks.quantity(stack,1);display.setOverrideDroppedItemAnimation(true);
        holder.addComponent(ItemComponent.getComponentType(),new ItemComponent(display));
        holder.addComponent(EntityScaleComponent.getComponentType(),new EntityScaleComponent(layout.scale));
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(layout.origin(position),new Rotation3f()));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(UUIDComponent.getComponentType(),new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(PreventPickup.getComponentType(),PreventPickup.INSTANCE);
        holder.addComponent(PreventItemMerging.getComponentType(),PreventItemMerging.INSTANCE);
        holder.addComponent(Intangible.getComponentType(),Intangible.INSTANCE);
        holder.addComponent(Invulnerable.getComponentType(),Invulnerable.INSTANCE);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
        return store.addEntity(holder,AddReason.SPAWN);
    }
    static void move(Ref<EntityStore> ref,Vector3d center){
        if(ref==null||!ref.isValid())return;var store=ref.getStore();
        var layout=prepare(store.getComponent(ref,ItemComponent.getComponentType()).getItemStack()).getNow(null);
        if(layout!=null)store.getComponent(ref,TransformComponent.getComponentType()).setPosition(layout.origin(center));
    }
    static void remove(Ref<EntityStore> ref){if(ref!=null&&ref.isValid())ref.getStore().removeEntity(ref,RemoveReason.REMOVE);}
    private TubeCarrier(){}
}
