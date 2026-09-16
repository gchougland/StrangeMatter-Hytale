package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.protocol.ItemUpdate;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.item.*;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.joml.Vector3d;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Real native trackers and ECS lifecycle for both authored machine cradles. */
public final class NativeGadgetDockDisplayVerification {
    public static void verify(World world,FactoryService factory,MachineState burner,FactoryComponent source,MachineState charger,FactoryComponent target)throws Exception{
        var scanner=source.charging.getItemStack((short)0);var gun=target.charging.getItemStack((short)0);
        var chunk=WorldAccess.loaded(world,com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(burner.x,burner.z));
        int originalRotation=WorldAccess.rotation(chunk,burner.x,burner.y,burner.z);
        try(var viewer=NativePlayerFixture.create(world,"NativeGadgetDockDisplay",new Vector3d(burner.x+.5,burner.y,burner.z+3))){
            var store=viewer.store();
            // One tracker tick publishes both nearby machines together. Keep that packet
            // batch while checking both IDs; an unchanged second item is not resent.
            viewer.packets().packets.clear();store.tick(.05f);
            for(var state:List.of(burner,charger)){
                var ref=factory.dockDisplays.displayed(state);require(ref!=null&&ref.isValid(),"Actual dock item has a live presentation entity: "+state.id);
                var expected=state==burner?scanner:gun;var layout=GadgetDockDisplays.prepare(expected).get(10,TimeUnit.SECONDS);
                int networkId=store.getComponent(ref,NetworkId.getComponentType()).getId();
                require(ref.isValid()&&store.getComponent(ref,Velocity.getComponentType())==null,"Native ECS leaves dock display fixed without item physics");
                require(store.getComponent(ref,PreventPickup.getComponentType())!=null&&store.getComponent(ref,PreventItemMerging.getComponentType())!=null
                        &&store.getComponent(ref,Intangible.getComponentType())!=null&&store.getComponent(ref,Invulnerable.getComponentType())!=null
                        &&store.getComponent(ref,EntityStore.REGISTRY.getNonSerializedComponentType())!=null,"Dock models cannot be collected, merged, captured or persisted");
                var displayed=store.getComponent(ref,ItemComponent.getComponentType()).getItemStack();
                require(displayed.getItemId().equals(expected.getItemId())&&displayed.getOverrideDroppedItemAnimation()&&com.hexvane.strangematter.util.StackData.metadata(displayed)==null,"Actual gadget model is static and contains no copied ownership metadata");
                var packet=viewer.packets().ofType(EntityUpdates.class).stream().filter(p->p.updates!=null).flatMap(p->Arrays.stream(p.updates))
                        .filter(u->u.networkId==networkId&&u.updates!=null).flatMap(u->Arrays.stream(u.updates)).filter(ItemUpdate.class::isInstance).map(ItemUpdate.class::cast)
                        .findFirst().orElseThrow(()->new AssertionError("Native tracker did not publish dock model "+state.id));
                float scale=(float)Math.min(1,(state==burner?.34:.58)/Math.max(.001,layout.span()));
                require(packet.item.overrideDroppedItemAnimation&&Math.abs(packet.entityScale-scale)<1e-7,"Actual native client update transmits the fitted static gadget model");
                var position=store.getComponent(ref,TransformComponent.getComponentType()).getPosition();
                var center=new Vector3d(position).add(layout.x()*scale,layout.y()*scale,layout.z()*scale);
                var expectedCenter=new Vector3d(state.x+.5+(state==burner?19.7/32:0),state.y+(state==burner?.515:.37)+layout.height()*scale/2,state.z+.5+(state==burner?3.0/32:4.0/32));
                require(center.distance(expectedCenter)<1e-6,"Authored cradle center and model pivot align for "+state.id);
            }
            var before=factory.dockDisplays.displayed(burner);
            source.charging.setItemStackForSlot((short)0,GadgetEnergy.withCharge(scanner,GadgetEnergy.charge(scanner)+1),false);factory.dockDisplays.sync(world,burner,source);
            require(factory.dockDisplays.displayed(burner)==before,"Continuous charging never respawns or jitters the displayed model");
            var type=world.getBlockType(burner.x,burner.y,burner.z);int rotated=RotationTuple.of(Rotation.Ninety,Rotation.None).index();
            WorldAccess.set(chunk,burner.x,burner.y,burner.z,BlockType.getAssetMap().getIndex(type.getId()),type,rotated,0,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            factory.dockDisplays.sync(world,burner,source);var turned=factory.dockDisplays.displayed(burner);
            require(!before.isValid()&&turned!=null&&turned.isValid(),"Facing change retires the old display before replacing it");
            var layout=GadgetDockDisplays.prepare(scanner).get(10,TimeUnit.SECONDS);float scale=(float)Math.min(1,.34/Math.max(.001,layout.span()));
            var pivot=new Vector3d(layout.x()*scale,layout.y()*scale,layout.z()*scale);RotationTuple.get(rotated).applyRotationTo(pivot);
            var center=new Vector3d(store.getComponent(turned,TransformComponent.getComponentType()).getPosition()).add(pivot);
            var offset=new Vector3d(19.7/32,.515+layout.height()*scale/2,3.0/32);RotationTuple.get(rotated).applyRotationTo(offset);offset.add(burner.x+.5,burner.y,burner.z+.5);
            require(center.distance(offset)<1e-6,"The side dock follows the burner's actual native facing");
            var pack=new ItemStack(GadgetEnergy.PACK,1);GadgetDockDisplays.prepare(pack).get(10,TimeUnit.SECONDS);
            source.charging.setItemStackForSlot((short)0,pack,false);factory.dockDisplays.sync(world,burner,source);
            var packRef=factory.dockDisplays.displayed(burner);require(!turned.isValid()&&store.getComponent(packRef,ItemComponent.getComponentType()).getItemStack().getItemId().equals(GadgetEnergy.PACK),"Replacing the inserted gadget immediately replaces its actual model");
            source.charging.setItemStackForSlot((short)0,null,false);factory.dockDisplays.sync(world,burner,source);
            require(!packRef.isValid()&&factory.dockDisplays.displayed(burner)==null,"Taking the gadget out destroys its presentation without creating an item drop");
            var chargerRef=factory.dockDisplays.displayed(charger);factory.dockDisplays.retain(world,Set.of());
            require(!chargerRef.isValid()&&factory.dockDisplays.displayed(charger)==null,"Unloaded machine lists retire presentation entities");
        }finally{
            var type=world.getBlockType(burner.x,burner.y,burner.z);WorldAccess.set(chunk,burner.x,burner.y,burner.z,BlockType.getAssetMap().getIndex(type.getId()),type,originalRotation,0,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            source.charging.setItemStackForSlot((short)0,scanner,false);target.charging.setItemStackForSlot((short)0,gun,false);
            factory.dockDisplays.sync(world,burner,source);factory.dockDisplays.sync(world,charger,target);
        }
        System.out.println("NATIVE_GADGET_DOCK_DISPLAY_VERIFICATION_PASSED: client ItemUpdate models, fitted native pivots, burner rotation, charge stability, replacement/removal/unload cleanup and no collectible or serialized duplicates.");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
