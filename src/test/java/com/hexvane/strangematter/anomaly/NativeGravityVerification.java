package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.FlyMode;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.packets.player.UpdateMovementSettings;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.ArrayList;

/** Complete native ECS gravity/steering/item collision ticks, plus actual player fly packet contracts. */
public final class NativeGravityVerification {
    public static void verify(World world)throws Exception {
        var service=new AnomalyService(Files.createTempDirectory("sm-native-gravity-"));service.naturalGeneration=false;
        var refs=new ArrayList<Ref<EntityStore>>();var store=world.getEntityStore().getStore();
        try(var player=NativePlayerFixture.create(world,"GravitySubject",new Vector3d(20,65,20))) {
            var manager=store.getComponent(player.ref(),MovementManager.getComponentType());
            var original=manager.getSettings();var fly=original.fly;float horizontal=original.horizontalFlySpeed,vertical=original.verticalFlySpeed;
            var model=store.getComponent(player.ref(),ModelComponent.getComponentType());
            var cow=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(23,65,20),new Rotation3f()).first();refs.add(cow);
            var control=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(31,65,20),new Rotation3f()).first();refs.add(control);
            var payload=new ItemStack("SM_Raw_Resonite",3).withMetadata("GravityProof",Codec.STRING,"retain exact drop");
            var item=store.addEntity(ItemComponent.generateItemDrop(store,payload,new Vector3d(17,65,20),new Rotation3f(),0,0,0),AddReason.SPAWN);refs.add(item);
            var itemControl=store.addEntity(ItemComponent.generateItemDrop(store,new ItemStack("SM_Raw_Resonite",1),new Vector3d(31,65,23),new Rotation3f(),0,0,0),AddReason.SPAWN);refs.add(itemControl);
            var itemPhysics=store.getComponent(item,PhysicsValues.getComponentType());
            var npcRole=store.getComponent(cow,NPCEntity.getComponentType()).getRole();var controller=npcRole.getActiveMotionController();
            var field=service.spawn(AnomalyType.GRAVITY,world,new Vector3d(20,66,21),true);service.tick(world,.05);
            require(original.fly==FlyMode.Forced&&store.getComponent(player.ref(),MovementStatesComponent.getComponentType()).getMovementStates().flying,"Native field enables active forced flight, rather than only reducing downward velocity");
            var packet=player.packets().ofType(UpdateMovementSettings.class).getLast();
            var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
            require(packet.equals(UpdateMovementSettings.toObject(bytes)),"Complete native zero-G movement packet survives actual wire serialization");
            for(int i=0;i<40;i++){store.tick(.05f);service.tick(world,.05);}
            require(cow.isValid()&&item.isValid(),"Real NPC and item survive full native physics ticks");
            double cowY=y(store,cow),itemY=y(store,item);
            require(cowY>65.35&&itemY>65.35,"NPC and dropped item rise into suspension during full native ECS integration: cow="+cowY+", item="+itemY);
            require(y(store,control)<63&&y(store,itemControl)<63,"Out-of-range native control NPC and item still fall normally");
            require(store.getComponent(item,ItemComponent.getComponentType()).getItemStack().equals(payload),"Floating drop keeps exact quantity and metadata");
            require(store.getComponent(item,PhysicsValues.getComponentType())==itemPhysics,"Zero G never replaces serialized item physics");
            require(store.getComponent(cow,NPCEntity.getComponentType()).getRole()==npcRole&&npcRole.getActiveMotionController()==controller,"NPC keeps its original brain, role and native controller");
            require(store.getComponent(player.ref(),ModelComponent.getComponentType())==model,"Player avatar remains untouched");

            // Suppression revokes capability and normal physics resumes on the very next native tick.
            service.setSuppressionHook((w,p,t)->true);service.tick(world,.05);
            require(original.fly==fly&&original.horizontalFlySpeed==horizontal&&original.verticalFlySpeed==vertical,"Suppressed field restores original player settings");
            for(int i=0;i<20;i++){store.tick(.05f);service.tick(world,.05);}
            require(y(store,cow)<cowY-.5&&y(store,item)<itemY-.5,"NPC and item resume gravity after field suppression");
            service.setSuppressionHook((w,p,t)->false);service.tick(world,.05);
            require(original.fly==FlyMode.Forced,"Unsuppressed field reacquires player zero G");
            var armor=store.getComponent(player.ref(),InventoryComponent.Armor.getComponentType()).getInventory();
            armor.setItemStackForSlot((short)ItemArmorSlot.Head.ordinal(),new ItemStack("SM_Tinfoil_Hat",1),false);service.tick(world,.05);
            require(original.fly==fly,"Wearing protection restores ordinary locomotion");
            armor.setItemStackForSlot((short)ItemArmorSlot.Head.ordinal(),ItemStack.EMPTY,false);service.tick(world,.05);
            var token=service.capture(field.id).orElseThrow();
            require(original.fly==fly&&!store.getComponent(player.ref(),MovementStatesComponent.getComponentType()).getMovementStates().flying,"Capture immediately clears owned fly state without waiting for another tick");
            service.release(token.token(),world,field.position()).orElseThrow();service.tick(world,.05);
            original.baseSpeed=7.125f;service.setEnabled(field.id,false);
            require(original.fly==fly&&original.baseSpeed==7.125f,"Disable restores only owned fields and preserves unrelated movement changes");
            service.setEnabled(field.id,true);service.tick(world,.05);service.stopWorld(world);
            require(original.fly==fly&&original.horizontalFlySpeed==horizontal&&original.verticalFlySpeed==vertical,"World cleanup restores original fly capability and speeds");
            service.tick(world,.05);require(original.fly==FlyMode.Forced,"Loaded active source can re-enter zero G after world cleanup");
            var id=player.owner().getUuid();player.close();
            require(original.fly==fly,"Native player removal restores the lease before holder saving and transfer");
            try(var restored=NativePlayerFixture.load(world,id,"GravitySubjectRestored",new Vector3d(31,65,20))) {
                service.tick(world,.05);
                require(restored.store().getComponent(restored.ref(),MovementManager.getComponentType()).getSettings().fly==fly
                        &&!restored.store().getComponent(restored.ref(),MovementStatesComponent.getComponentType()).getMovementStates().flying,
                        "Actual saved and reattached player has no residual field flight outside the anomaly");
            }
        } finally {service.stopWorld(world);for(var ref:refs)if(ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);}
        System.out.println("NATIVE_GRAVITY_VERIFICATION_PASSED: real full-ECS NPC/item suspension and out-of-range falling controls, native player flight wire contract, unchanged physics/metadata/avatar/brain, suppression, hat, capture, disable, world restoration and native removal/save/reattach.");
    }
    private static double y(Store<EntityStore> store,Ref<EntityStore> ref){return store.getComponent(ref,TransformComponent.getComponentType()).getPosition().y;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
