package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.splitvelocity.VelocityConfig;
import com.hypixel.hytale.server.core.modules.splitvelocity.SplitVelocity;
import com.hypixel.hytale.server.core.entity.entities.Player;
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

/** Complete native movement input, force packets, NPC steering, item collision and restoration. */
public final class NativeGravityVerification {
    public static void verify(World world)throws Exception {
        var service=new AnomalyService(Files.createTempDirectory("sm-native-gravity-"));service.naturalGeneration=false;
        var refs=new ArrayList<Ref<EntityStore>>();var store=world.getEntityStore().getStore();
        try(var player=NativePlayerFixture.create(world,"GravitySubject",new Vector3d(20,65,20))) {
            var manager=store.getComponent(player.ref(),MovementManager.getComponentType());
            var original=manager.getSettings();var fly=original.fly;float horizontal=original.horizontalFlySpeed,vertical=original.verticalFlySpeed;
            var model=store.getComponent(player.ref(),ModelComponent.getComponentType());
            var cow=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(23,65,20),new Rotation3f()).first();refs.add(cow);
            var chicken=NPCPlugin.get().spawnNPC(store,"Chicken",null,new Vector3d(21,65,24),new Rotation3f()).first();refs.add(chicken);
            var chickenAnimations=store.getComponent(chicken,ActiveAnimationComponent.getComponentType());
            var originalChickenAnimation=chickenAnimations.getActiveAnimations()[AnimationSlot.Movement.ordinal()];
            var control=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(31,65,20),new Rotation3f()).first();refs.add(control);
            var payload=new ItemStack("SM_Raw_Resonite",3).withMetadata("GravityProof",Codec.STRING,"retain exact drop");
            // The pickup delay isolates the specimen, so it need not start just
            // two blocks inside the field boundary. Its real horizontal drift
            // continues throughout the long wave and must stay inside the field
            // until the later suppression comparison starts.
            var item=store.addEntity(ItemComponent.generateItemDrop(store,payload,new Vector3d(22,65,22),new Rotation3f(),0,0,0),AddReason.SPAWN);refs.add(item);
            // Isolate the long suspension measurement from native pickup by nearby test players.
            store.getComponent(item,ItemComponent.getComponentType()).setPickupDelay(60);
            var itemControl=store.addEntity(ItemComponent.generateItemDrop(store,new ItemStack("SM_Raw_Resonite",1),new Vector3d(31,65,23),new Rotation3f(),0,0,0),AddReason.SPAWN);refs.add(itemControl);
            var itemPhysics=store.getComponent(item,PhysicsValues.getComponentType());
            var npcRole=store.getComponent(cow,NPCEntity.getComponentType()).getRole();var controller=npcRole.getActiveMotionController();
            require(!SplitVelocity.SHOULD_MODIFY_VELOCITY,"Native split velocity feature is enabled, matching the client movement contract");
            controller.getExternalVelocity().set(0,-20,0);
            var separate=new VelocityConfig();separate.setAirResistance(1);separate.setAirResistanceMax(1);
            controller.setVelocity(new Vector3d(2,1.2,.5),separate,false);
            require(controller.getExternalVelocity().y==-20,"Reproduces configured Set leaving the existing falling velocity untouched");
            controller.clearExternalForces();controller.getExternalVelocity().set(0,-20,0);
            controller.setVelocity(new Vector3d(2,1.2,.5),null,false);
            require(controller.getExternalVelocity().equals(new Vector3d(2,1.2,.5)),"Native null-config Set replaces the actual falling channel instead of only the separate force list");
            controller.clearExternalForces();
            store.tick(.05f);
            var field=service.spawn(AnomalyType.GRAVITY,world,new Vector3d(20,66,21),true);service.tick(world,.05);
            var input=store.getComponent(player.ref(),PlayerInput.getComponentType());
            // These are the same native queued updates GamePacketHandler creates from ClientMovement.
            input.queue(new PlayerInput.SetClientVelocity(new Vector3d(4.5,.9,-1.75)));
            store.tick(.05f);
            require(original.fly==fly&&!store.getComponent(player.ref(),MovementStatesComponent.getComponentType()).getMovementStates().flying,"The field does not grant creative flight or change ordinary locomotion settings");
            require(store.getComponent(player.ref(),Velocity.getComponentType()).getClientVelocity().x==4.5,"Native ProcessPlayerInput applies the actual client velocity before the gravity system");
            var packet=forces(player).getFirst();
            require(packet.config==null,"Player gravity uses the actual velocity channel, not SplitVelocity's independent applied force list");
            require(packet.changeType==ChangeVelocityType.Add&&packet.x==0&&packet.z==0
                    &&Math.abs(packet.y-2*GravityMomentum.force(Math.sqrt(2)))<1e-5,
                    "Player overlay matches the source rising branch and never replaces ordinary horizontal motion");
            var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
            require(packet.equals(ChangeVelocity.toObject(bytes)),"Native momentum instruction and force resistance survive actual client wire serialization");
            input.queue(new PlayerInput.SetClientVelocity(new Vector3d(4.5,-10,-1.75)));store.tick(.05f);
            require(forces(player).getLast().y>packet.y&&forces(player).getLast().y<=12*GravityMomentum.force(Math.sqrt(2)),
                    "A falling report receives bounded source lift while crediting the preceding in-flight upward packet");
            for(int i=0;i<40;i++){input.queue(new PlayerInput.SetClientVelocity(new Vector3d(4.5,.9,-1.75)));store.tick(.05f);service.tick(world,.05);}
            var driven=forces(player);require(driven.size()>=40,"Fresh movement reports maintain source-rate vertical lift");
            require(driven.stream().allMatch(v->v.x==0&&v.z==0),"No player impulse damps, retains or steers horizontal motion on the client's behalf");
            require("FlyIdle".equals(chickenAnimations.getActiveAnimations()[AnimationSlot.Movement.ordinal()]),"An affected chicken plays its existing native flying animation");
            require(player.packets().ofType(PlayAnimation.class).stream().anyMatch(p->"FlyIdle".equals(p.animationId)&&p.slot==AnimationSlot.Movement),"Existing viewers receive the native creature flying animation packet");
            require(cow.isValid()&&item.isValid(),"Real NPC and item survive full native physics ticks");
            double cowY=y(store,cow),itemY=y(store,item);
            require(cowY>65.35&&itemY>65.35,"NPC and dropped item rise into suspension during full native ECS integration: cow="+cowY+", item="+itemY);
            require(y(store,control)<63&&y(store,itemControl)<63,"Out-of-range native control NPC and item still fall normally");
            require(store.getComponent(item,ItemComponent.getComponentType()).getItemStack().equals(payload),"Floating drop keeps exact quantity and metadata");
            require(store.getComponent(item,PhysicsValues.getComponentType())==itemPhysics,"Zero G never replaces serialized item physics");
            require(store.getComponent(cow,NPCEntity.getComponentType()).getRole()==npcRole&&npcRole.getActiveMotionController()==controller,"NPC keeps its original brain, role and native controller");
            require(store.getComponent(player.ref(),ModelComponent.getComponentType())==model,"Player avatar remains untouched");

            int beforeImpulse=forces(player).size();
            store.getComponent(player.ref(),Velocity.getComponentType()).addInstruction(new Vector3d(2,1,0),null,ChangeVelocityType.Add);
            store.tick(.05f);service.tick(world,.05);
            var foreign=player.packets().ofType(ChangeVelocity.class).getLast();
            require(forces(player).size()==beforeImpulse&&foreign.changeType==ChangeVelocityType.Add&&foreign.x==2&&foreign.y==1,"Native foreign knockback executes without a later field Set masking it");
            input.queue(new PlayerInput.SetClientVelocity(new Vector3d(6.5,1,-1.75)));store.tick(.05f);service.tick(world,.05);
            require(forces(player).getLast().x==0&&forces(player).getLast().z==0,"Resuming lift cannot double the external horizontal impulse");
            store.getComponent(player.ref(),Velocity.getComponentType()).addInstruction(new Vector3d(4,2,0),new VelocityConfig(),ChangeVelocityType.Add);
            store.tick(.05f);service.tick(world,.05);store.tick(.05f);service.tick(world,.05);
            require(forces(player).getLast().x==0&&forces(player).getLast().z==0,"Configured knockback stays in its native split channel and is never copied by the vertical overlay");

            // Follow a whole native bob cycle, not merely the initial upward settling motion.
            double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY,previous=y(store,cow);boolean rose=false,sank=false;
            for(int i=0;i<170;i++){
                store.tick(.05f);service.tick(world,.05);double current=y(store,cow);
                if(i>20){low=Math.min(low,current);high=Math.max(high,current);rose|=current>previous+.0001;sank|=current<previous-.0001;}
                previous=current;
                require("FlyIdle".equals(chickenAnimations.getActiveAnimations()[AnimationSlot.Movement.ordinal()]),"Native flying clip survives every complete ECS tick throughout the gravity wave");
            }
            require(high-low>.35&&rose&&sank,"Native creature suspension gently rises and descends through the sine wave: range="+(high-low));
            require(cow.isValid()&&item.isValid(),"Long gravity wave retains its isolated NPC and drop: cow="+cow.isValid()+", item="+item.isValid());
            cowY=y(store,cow);itemY=y(store,item);
            String suppressionStart="cow="+motionState(store,cow)+", item="+motionState(store,item);
            require(store.getComponent(cow,TransformComponent.getComponentType()).getPosition().distanceSquared(field.position())<64
                    &&store.getComponent(item,TransformComponent.getComponentType()).getPosition().distanceSquared(field.position())<64,
                    "Suppression comparison must start with both specimens still inside the active field: "+suppressionStart+", field="+field.position());

            // Suppression stops our forces and normal physics resumes on the very next native tick.
            service.setSuppressionHook((w,p,t)->true);service.tick(world,.05);
            require(original.fly==fly&&original.horizontalFlySpeed==horizontal&&original.verticalFlySpeed==vertical,"Suppressed field restores original player settings");
            int stopped=forces(player).size();
            var suppressionTrace=new StringBuilder();
            for(int i=0;i<20;i++){
                store.tick(.05f);service.tick(world,.05);
                if(i==0||i==4||i==9||i==19)suppressionTrace.append("; tick ").append(i+1).append(" cow=").append(motionState(store,cow)).append(", item=").append(motionState(store,item));
            }
            require(forces(player).size()==stopped,"Suppression sends no further gravity-owned movement commands");
            require(java.util.Objects.equals(originalChickenAnimation,chickenAnimations.getActiveAnimations()[AnimationSlot.Movement.ordinal()]),"Suppression restores the creature's original native movement animation");
            require(cow.isValid()&&item.isValid(),"Suppressed specimen unexpectedly removed: start "+suppressionStart+suppressionTrace);
            double suppressedCowY=y(store,cow),suppressedItemY=y(store,item);
            require(suppressedCowY<cowY-.5&&suppressedItemY<itemY-.5,
                    "NPC and item resume gravity after field suppression: cowY="+cowY+" -> "+suppressedCowY+", itemY="+itemY+" -> "+suppressedItemY+"; start "+suppressionStart+suppressionTrace);
            service.setSuppressionHook((w,p,t)->false);service.tick(world,.05);
            store.tick(.05f);require(forces(player).size()>stopped,"Unsuppressed field resumes source vertical lift");
            var armor=store.getComponent(player.ref(),InventoryComponent.Armor.getComponentType()).getInventory();
            armor.setItemStackForSlot((short)ItemArmorSlot.Head.ordinal(),new ItemStack("SM_Tinfoil_Hat",1),false);service.tick(world,.05);
            stopped=forces(player).size();store.tick(.05f);
            require(original.fly==fly&&forces(player).size()==stopped,"Wearing protection stops owned impulses and leaves ordinary locomotion intact");
            armor.setItemStackForSlot((short)ItemArmorSlot.Head.ordinal(),ItemStack.EMPTY,false);service.tick(world,.05);store.tick(.05f);
            var token=service.capture(field.id).orElseThrow();
            require(original.fly==fly&&!store.getComponent(player.ref(),MovementStatesComponent.getComponentType()).getMovementStates().flying,"Capture leaves native movement settings intact and clears the owned motion lease");
            service.release(token.token(),world,field.position()).orElseThrow();service.tick(world,.05);
            original.baseSpeed=7.125f;service.setEnabled(field.id,false);
            require(original.fly==fly&&original.baseSpeed==7.125f,"Disable restores only owned fields and preserves unrelated movement changes");
            service.setEnabled(field.id,true);service.tick(world,.05);service.stopWorld(world);
            require(original.fly==fly&&original.horizontalFlySpeed==horizontal&&original.verticalFlySpeed==vertical,"World cleanup restores original fly capability and speeds");
            service.tick(world,.05);stopped=forces(player).size();store.tick(.05f);require(forces(player).size()>stopped,"Loaded active source can resume momentum after world cleanup");
            verifyCreativeEntry(world,service);
            NativeGravityPlayerVerification.verify(world,service,field.position());
            double amplitude=GravityMomentum.BOB_AMPLITUDE,omega=GravityMomentum.BOB_OMEGA;
            require(Math.abs(GravityMomentum.bob(Math.PI/(2*omega),0)-amplitude)<1e-9
                    &&Math.abs(GravityMomentum.bob(3*Math.PI/(2*omega),0)+amplitude)<1e-9,"Creature/item wave remains unchanged in both directions");
            var id=player.owner().getUuid();player.close();
            require(original.fly==fly,"Native player removal restores the lease before holder saving and transfer");
            try(var restored=NativePlayerFixture.load(world,id,"GravitySubjectRestored",new Vector3d(31,65,20))) {
                service.tick(world,.05);
                require(restored.store().getComponent(restored.ref(),MovementManager.getComponentType()).getSettings().fly==fly
                        &&!restored.store().getComponent(restored.ref(),MovementStatesComponent.getComponentType()).getMovementStates().flying,
                        "Actual saved and reattached player has no residual field flight outside the anomaly");
            }
        } finally {service.stopWorld(world);for(var ref:refs)if(ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);}
        System.out.println("NATIVE_GRAVITY_VERIFICATION_PASSED: real full-ECS NPC/item suspension and out-of-range falling controls, source vertical player lift and native control preservation, native flying clips and wave math, unchanged physics/metadata/avatar/brain, suppression, hat, capture, disable, world restoration and native removal/save/reattach.");
    }
    private static java.util.List<ChangeVelocity> forces(NativePlayerFixture player){return player.packets().ofType(ChangeVelocity.class).stream()
            .filter(p->p.changeType==ChangeVelocityType.Add&&p.config==null&&p.x==0&&p.z==0).toList();}
    private static void verifyCreativeEntry(World world,AnomalyService service)throws Exception{
        try(var subject=NativePlayerFixture.create(world,"GravityCreativeEntry",new Vector3d(22,65,21))){
            var store=subject.store();Player.setGameMode(subject.ref(),GameMode.Creative,store);
            var states=store.getComponent(subject.ref(),MovementStatesComponent.getComponentType());
            var input=store.getComponent(subject.ref(),PlayerInput.getComponentType());
            var grounded=states.getMovementStates().clone();grounded.flying=false;
            input.queue(new PlayerInput.SetMovementStates(grounded));service.tick(world,.05);
            int before=forces(subject).size();store.tick(.05f);
            require(forces(subject).size()>before&&!states.getMovementStates().flying,"A grounded Creative player feels the field without flight being enabled");
            var flying=states.getMovementStates().clone();flying.flying=true;input.queue(new PlayerInput.SetMovementStates(flying));store.tick(.05f);
            int stopped=forces(subject).size();store.tick(.05f);
            require(states.getMovementStates().flying&&forces(subject).size()==stopped,"Actual native flight keeps control and receives no continuing gravity commands");
            input.queue(new PlayerInput.SetMovementStates(grounded));store.tick(.05f);
            require(forces(subject).size()>stopped,"Leaving native flight resumes gravity for Creative players");
        }
    }
    private static String motionState(Store<EntityStore> store,Ref<EntityStore> ref){
        if(!ref.isValid())return "removed";
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        if(transform==null)return "no transform";
        var position=transform.getPosition();var velocity=store.getComponent(ref,Velocity.getComponentType());
        var below=store.getExternalData().getWorld().getBlockType((int)Math.floor(position.x),(int)Math.floor(position.y)-1,(int)Math.floor(position.z));
        var npc=store.getComponent(ref,NPCEntity.getComponentType());
        return "position="+position+", velocity="+(velocity==null?"none":velocity.getVelocity())+", below="+(below==null?"unloaded":below.getId())
                +(npc==null||npc.getRole()==null?"":", external="+npc.getRole().getActiveMotionController().getExternalVelocity());
    }
    private static double y(Store<EntityStore> store,Ref<EntityStore> ref){return store.getComponent(ref,TransformComponent.getComponentType()).getPosition().y;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
