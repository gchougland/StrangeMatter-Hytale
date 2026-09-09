package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.entities.PlayAnimation;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
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
            var item=store.addEntity(ItemComponent.generateItemDrop(store,payload,new Vector3d(26,65,21),new Rotation3f(),0,0,0),AddReason.SPAWN);refs.add(item);
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
            require(packet.changeType==ChangeVelocityType.Set&&Math.abs(packet.x-4.5*Math.exp(-GravityMomentum.DAMPING*.05))<1e-5
                    &&Math.abs(packet.z+1.75*Math.exp(-GravityMomentum.DAMPING*.05))<1e-5,"First real native force retains entry direction and speed instead of a vertical-only impulse");
            var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
            require(packet.equals(ChangeVelocity.toObject(bytes)),"Native momentum instruction and force resistance survive actual client wire serialization");
            input.queue(new PlayerInput.RelativeMovement(0,-.5,0));store.tick(.05f);
            require(forces(player).getLast().y>packet.y+.35,"Actual reported downward movement produces bounded upward correction rather than accumulating falling drift");
            input.queue(new PlayerInput.RelativeMovement(0,.5,0));store.tick(.05f);
            for(int i=0;i<40;i++){store.tick(.05f);service.tick(world,.05);}
            var driven=forces(player);require(driven.size()>=40,"Player receives continuing native motion commands rather than a flight-only capability toggle");
            require(driven.getLast().x<packet.x&&driven.getLast().x>packet.x*.6,"Player momentum damps mildly instead of stopping when movement keys are released");
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

            int beforeImpulse=forces(player).size();double priorX=forces(player).getLast().x;
            store.getComponent(player.ref(),Velocity.getComponentType()).addInstruction(new Vector3d(2,1,0),null,ChangeVelocityType.Add);
            store.tick(.05f);service.tick(world,.05);
            var foreign=player.packets().ofType(ChangeVelocity.class).getLast();
            require(forces(player).size()==beforeImpulse&&foreign.changeType==ChangeVelocityType.Add&&foreign.x==2&&foreign.y==1,"Native foreign knockback executes without a later field Set masking it");
            store.tick(.05f);service.tick(world,.05);
            require(forces(player).getLast().x>priorX+1.8,"The next gravity instruction retains the external impulse");
            double afterDirectImpulse=forces(player).getLast().x;
            store.getComponent(player.ref(),Velocity.getComponentType()).addInstruction(new Vector3d(4,2,0),new VelocityConfig(),ChangeVelocityType.Add);
            store.tick(.05f);service.tick(world,.05);store.tick(.05f);service.tick(world,.05);
            require(forces(player).getLast().x<afterDirectImpulse+.01,"Configured knockback stays in its native split channel and is not doubled into retained external momentum");

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

            // Suppression stops our forces and normal physics resumes on the very next native tick.
            service.setSuppressionHook((w,p,t)->true);service.tick(world,.05);
            require(original.fly==fly&&original.horizontalFlySpeed==horizontal&&original.verticalFlySpeed==vertical,"Suppressed field restores original player settings");
            int stopped=forces(player).size();
            for(int i=0;i<20;i++){store.tick(.05f);service.tick(world,.05);}
            require(forces(player).size()==stopped,"Suppression sends no further gravity-owned movement commands");
            require(java.util.Objects.equals(originalChickenAnimation,chickenAnimations.getActiveAnimations()[AnimationSlot.Movement.ordinal()]),"Suppression restores the creature's original native movement animation");
            require(y(store,cow)<cowY-.5&&y(store,item)<itemY-.5,"NPC and item resume gravity after field suppression");
            service.setSuppressionHook((w,p,t)->false);service.tick(world,.05);
            store.tick(.05f);require(forces(player).size()>stopped,"Unsuppressed field resumes momentum-driven movement");
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
            verifyMeasuredEntry(world,service);
            verifyCreativeEntry(world,service);
            verifySteering(world,service);
            verifyOrdinarySteering(world,service);
            verifyWave();
            var id=player.owner().getUuid();player.close();
            require(original.fly==fly,"Native player removal restores the lease before holder saving and transfer");
            try(var restored=NativePlayerFixture.load(world,id,"GravitySubjectRestored",new Vector3d(31,65,20))) {
                service.tick(world,.05);
                require(restored.store().getComponent(restored.ref(),MovementManager.getComponentType()).getSettings().fly==fly
                        &&!restored.store().getComponent(restored.ref(),MovementStatesComponent.getComponentType()).getMovementStates().flying,
                        "Actual saved and reattached player has no residual field flight outside the anomaly");
            }
        } finally {service.stopWorld(world);for(var ref:refs)if(ref.isValid())store.removeEntity(ref,RemoveReason.REMOVE);}
        System.out.println("NATIVE_GRAVITY_VERIFICATION_PASSED: real full-ECS NPC/item suspension and out-of-range falling controls, actual native movement input and repeated momentum force wire contract, native flying clips and wave math, unchanged physics/metadata/avatar/brain, suppression, hat, capture, disable, world restoration and native removal/save/reattach.");
    }
    private static java.util.List<ChangeVelocity> forces(NativePlayerFixture player){return player.packets().ofType(ChangeVelocity.class).stream()
            .filter(p->p.changeType==ChangeVelocityType.Set).toList();}
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
    private static void verifyMeasuredEntry(World world,AnomalyService service)throws Exception{
        try(var subject=NativePlayerFixture.create(world,"GravityMeasuredEntry",new Vector3d(11.9,65,21))){
            var store=subject.store();service.tick(world,.05);store.tick(.05f);
            var velocity=store.getComponent(subject.ref(),Velocity.getComponentType());
            require(velocity.getVelocity().lengthSquared()==0&&velocity.getClientVelocity().lengthSquared()==0,"Fallback fixture has no cached velocity to fabricate momentum from");
            store.getComponent(subject.ref(),PlayerInput.getComponentType()).queue(new PlayerInput.RelativeMovement(.3,0,0));
            store.tick(.05f);
            require(!forces(subject).isEmpty()&&forces(subject).getFirst().x>5.8,"Real native position samples preserve entry momentum when the client omitted velocity");
        }
    }
    private static void verifySteering(World world,AnomalyService service)throws Exception{
        try(var subject=NativePlayerFixture.create(world,"GravitySteering",new Vector3d(22,65,21))){
            var store=subject.store();var input=store.getComponent(subject.ref(),PlayerInput.getComponentType());
            service.tick(world,.05);store.tick(.05f);
            var forward=drive(subject,service,new Vector3d(0,0,-.1),0,0,20);
            require(forward.z<-2&&Math.abs(forward.x)<.001,"Native forward wish gradually adds view-directed momentum");
            require(input.getMovementUpdateQueue().isEmpty(),"Steering observes wishes before native input processing consumes the queue");
            var back=drive(subject,service,new Vector3d(0,0,.1),0,0,20);
            require(back.z>forward.z+2&&back.z>0,"Backward input gradually brakes and reverses forward drift");
            var right=drive(subject,service,new Vector3d(.1,0,0),0,0,20);
            require(right.x>2,"Native right strafe adds sideways momentum");
            var left=drive(subject,service,new Vector3d(-.1,0,0),0,0,20);
            require(left.x<right.x-2&&left.x<0,"Native left strafe brakes and reverses sideways drift");
            var turned=drive(subject,service,new Vector3d(-.1,0,0),(float)(Math.PI/2),0,20);
            require(turned.x<left.x-1.8,"Forward input follows a changed native head yaw");
            var up=drive(subject,service,new Vector3d(0,0,-.1),0,(float)(Math.PI/3),20);
            require(up.y>turned.y+1.2,"Forward input while looking up adds gradual vertical momentum");
            var down=drive(subject,service,new Vector3d(0,0,-.1),0,(float)(-Math.PI/3),40);
            require(down.y<up.y-2,"Forward input while looking down steers downward without a teleport or flight toggle");

            drive(subject,service,new Vector3d(),0,0,1);var idle=forces(subject).getLast();
            for(int i=0;i<10;i++){
                // This is resulting motion, not held input. It must never feed steering.
                input.queue(new PlayerInput.SetClientVelocity(new Vector3d(300,0,300)));
                store.tick(.05f);service.tick(world,.05);
            }
            var coast=forces(subject).getLast();double damping=Math.exp(-GravityMomentum.DAMPING*.5);
            require(Math.abs(coast.x-idle.x*damping)<.001&&Math.abs(coast.z-idle.z*damping)<.001,"Released controls coast with mild damping even when reported resulting velocity is large");
            require(Math.hypot(coast.x,coast.z)>.5,"Releasing movement does not erase existing drift");

            drive(subject,service,new Vector3d(.1,0,0),0,0,1);
            for(int i=0;i<8;i++){store.tick(.05f);service.tick(world,.05);}
            var expired=forces(subject).getLast();store.tick(.05f);service.tick(world,.05);
            require(Math.abs(forces(subject).getLast().x-expired.x*Math.exp(-GravityMomentum.DAMPING*.05))<.001,"A stale wish expires and cannot continue thrust after movement packets stop");

            for(int i=0;i<150;i++){
                var command=drive(subject,service,new Vector3d(.1,0,0),0,0,1);
                require(Math.hypot(command.x,command.z)<=GravityMomentum.STEERING_SPEED+.001,"Continuous steering has a bounded horizontal cruising speed");
                require(new Vector3d(command.x,command.y,command.z).length()<8.4,"Steering, bob and height correction remain bounded together");
                require(command.config==null,"Steering preserves the direct native external velocity channel");
            }
            var beforeTurn=forces(subject).getLast();
            input.queue(new PlayerInput.SetHead(new Direction(0,0,0)));
            input.queue(new PlayerInput.WishMovement(0,0,-.1));
            input.queue(new PlayerInput.SetHead(new Direction((float)(Math.PI/2),0,0)));
            store.tick(.05f);service.tick(world,.05);
            require(forces(subject).getLast().x<beforeTurn.x*Math.exp(-GravityMomentum.DAMPING*.05)-.1,
                    "A later look-only update reprojects forward input using its original wish yaw rather than treating it as strafe");
            var armor=store.getComponent(subject.ref(),InventoryComponent.Armor.getComponentType()).getInventory();
            armor.setItemStackForSlot((short)ItemArmorSlot.Head.ordinal(),new ItemStack("SM_Tinfoil_Hat",1),false);service.tick(world,.05);
            int stopped=forces(subject).size();
            drive(subject,service,new Vector3d(.1,0,0),0,0,3);
            require(forces(subject).size()==stopped,"Protection stops steering as well as passive gravity motion");
        }
    }
    private static ChangeVelocity drive(NativePlayerFixture subject,AnomalyService service,Vector3d wish,float yaw,float pitch,int ticks){
        var input=subject.store().getComponent(subject.ref(),PlayerInput.getComponentType());
        for(int i=0;i<ticks;i++){
            // GamePacketHandler queues head orientation before world-space WishMovement.
            input.queue(new PlayerInput.SetHead(new Direction(yaw,pitch,0)));
            input.queue(new PlayerInput.WishMovement(wish.x,wish.y,wish.z));
            subject.store().tick(.05f);service.tick(subject.store().getExternalData().getWorld(),.05);
        }
        return forces(subject).getLast();
    }
    private static void verifyOrdinarySteering(World world,AnomalyService service)throws Exception{
        try(var subject=NativePlayerFixture.create(world,"GravityOrdinaryControls",new Vector3d(22,65,21))){
            subject.player().handleClientReady(false);service.tick(world,.05);subject.store().tick(.05f);
            var forward=ordinaryDrive(subject,service,new Vector3d(0,0,-3),0,0,true,20);
            require(forward.z<-2,"Ordinary client packets steer forward with no optional wish force");
            var backward=ordinaryDrive(subject,service,new Vector3d(0,0,3),0,0,true,20);
            require(backward.z>forward.z+2&&backward.z>0,"Ordinary no-wish movement gradually brakes and reverses drift");
            var right=ordinaryDrive(subject,service,new Vector3d(3,0,0),0,0,true,20);
            require(right.x>2,"Ordinary no-wish right strafe adds sideways momentum");
            var left=ordinaryDrive(subject,service,new Vector3d(-3,0,0),0,0,true,20);
            require(left.x<right.x-2&&left.x<0,"Ordinary no-wish left strafe reverses sideways momentum");
            var turned=ordinaryDrive(subject,service,new Vector3d(-3,0,0),(float)(Math.PI/2),0,true,20);
            require(turned.x<left.x-1.8,"Ordinary forward direction follows native head yaw");
            var up=ordinaryDrive(subject,service,new Vector3d(0,0,-3),0,(float)(Math.PI/3),true,20);
            require(up.y>turned.y+1.2,"Ordinary horizontal input gains vertical thrust through look pitch");
            var down=ordinaryDrive(subject,service,new Vector3d(0,0,-3),0,(float)(-Math.PI/3),true,40);
            require(down.y<up.y-2,"Ordinary pitched down input steers downward without reading falling velocity as input");

            var idle=ordinaryDrive(subject,service,new Vector3d(),0,0,false,1);
            for(int i=0;i<10;i++)ordinaryDrive(subject,service,new Vector3d(0,-30,0),0,0,false,1);
            var coast=forces(subject).getLast();double damping=Math.exp(-GravityMomentum.DAMPING*.5);
            require(Math.abs(coast.x-idle.x*damping)<.001&&Math.abs(coast.z-idle.z*damping)<.001,"Idle ordinary packets retain moving drift and ignore falling reports");
            // A zero horizontal report can be a collision stop. It must not become reverse thrust.
            var previous=forces(subject).getLast();
            ordinaryPacket(subject,new Vector3d(0,-20,0),0,0,true);
            subject.store().tick(.05f);service.tick(world,.05);
            require(Math.abs(forces(subject).getLast().x-previous.x*Math.exp(-GravityMomentum.DAMPING*.05))<.001
                    &&Math.abs(forces(subject).getLast().z-previous.z*Math.exp(-GravityMomentum.DAMPING*.05))<.001,"Collision-stopped reports do not manufacture reverse input");
            ordinaryDrive(subject,service,new Vector3d(3,0,0),0,0,true,1);
            for(int i=0;i<8;i++){subject.store().tick(.05f);service.tick(world,.05);}
            var expired=forces(subject).getLast();subject.store().tick(.05f);service.tick(world,.05);
            require(Math.abs(forces(subject).getLast().x-expired.x*Math.exp(-GravityMomentum.DAMPING*.05))<.001,"Ordinary input expires even when the last locomotion flags remain active");
            for(int i=0;i<150;i++){
                var command=ordinaryDrive(subject,service,new Vector3d(3,0,0),0,0,true,1);
                require(Math.hypot(command.x,command.z)<=GravityMomentum.STEERING_SPEED+.001&&command.config==null,"Ordinary steering has bounded speed and uses the direct native velocity channel");
            }
        }
    }
    private static ChangeVelocity ordinaryDrive(NativePlayerFixture subject,AnomalyService service,Vector3d locomotion,float yaw,float pitch,boolean moving,int ticks){
        for(int i=0;i<ticks;i++){
            var own=forces(subject).getLast();
            ordinaryPacket(subject,new Vector3d(own.x,own.y,own.z).add(locomotion),yaw,pitch,moving);
            subject.store().tick(.05f);service.tick(subject.world(),.05);
        }
        return forces(subject).getLast();
    }
    private static void ordinaryPacket(NativePlayerFixture subject,Vector3d reported,float yaw,float pitch,boolean moving){
        var packet=new ClientMovement();
        var states=subject.store().getComponent(subject.ref(),MovementStatesComponent.getComponentType()).getMovementStates().clone();
        states.idle=!moving;states.horizontalIdle=!moving;states.walking=moving;states.running=false;states.sprinting=false;
        packet.movementStates=states;packet.velocity=new com.hypixel.hytale.protocol.Vector3d(reported.x,reported.y,reported.z);
        packet.lookOrientation=new Direction(yaw,pitch,0);
        var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);var decoded=ClientMovement.toObject(bytes);
        require(decoded.wishMovement==null,"Ordinary control fixture has no wish force on the real client wire");
        subject.packets().handle(decoded);subject.world().consumeTaskQueue();
        var queue=subject.store().getComponent(subject.ref(),PlayerInput.getComponentType()).getMovementUpdateQueue();
        require(queue.stream().anyMatch(PlayerInput.SetClientVelocity.class::isInstance)
                &&queue.stream().noneMatch(PlayerInput.WishMovement.class::isInstance),"Real GamePacketHandler routes ordinary input without synthetic wishes");
    }
    private static void verifyWave(){
        double amplitude=GravityMomentum.BOB_AMPLITUDE,omega=GravityMomentum.BOB_OMEGA;
        require(Math.abs(GravityMomentum.bob(Math.PI/(2*omega),0)-amplitude)<1e-9
                &&Math.abs(GravityMomentum.bob(3*Math.PI/(2*omega),0)+amplitude)<1e-9,"Shared sine wave has both upward and downward phases at the bounded amplitude");
        var momentum=new Vector3d(3,0,0);var desired=GravityMomentum.desired(momentum,.05,0,0);
        var command=GravityMomentum.instruction(desired,.05,false);
        double meanY=command.y-com.hypixel.hytale.server.core.modules.physics.util.PhysicsConstants.GRAVITY_ACCELERATION*.05*.5;
        require(Math.abs(meanY-desired.y)<1e-9,"Initial gravity compensation is bounded around the desired drift velocity");
        require(GravityMomentum.heightCorrection(10,0)==1.25&&GravityMomentum.heightCorrection(0,10)==-1.25
                &&GravityMomentum.heightCorrection(2,1)>0,"Reported height feedback is corrective and bounded without position writes");
        require(GravityMomentum.bobVelocity(0,0)>0&&GravityMomentum.bobVelocity(Math.PI/omega,0)<0,"Bob velocity reverses gently instead of applying a constant upward force");
        var diagonal=GravityMomentum.steeringDirection(GravityMomentum.inputAxes(new Vector3d(1,0,-1),0),0,Math.PI/4);
        require(Math.abs(diagonal.length()-1)<1e-9&&diagonal.x>0&&diagonal.y>0&&diagonal.z<0,"Diagonal pitched steering preserves one normalized input direction");
        var fast=new Vector3d(14,0,0);GravityMomentum.steer(fast,new Vector3d(1,0,0),0,0,.05);
        require(Math.abs(fast.length()-14)<1e-9,"Steering does not abruptly clamp fast entry momentum or accelerate it further");
        var invalid=GravityMomentum.inputAxes(new Vector3d(Double.NaN,0,0),0);
        require(invalid.lengthSquared()==0,"Invalid wish input cannot introduce nonfinite motion");
        var history=java.util.List.of(new Vector3d(3,0,0),new Vector3d(2.95,0,0));
        require(GravityMomentum.residualAxes(new Vector3d(2.75,-20,0),history,0).lengthSquared()==0,"Ordinary native drag lies inside the residual deadzone");
        require(GravityMomentum.residualAxes(new Vector3d(300,0,300),history,0).lengthSquared()==0,"Large foreign velocity is not inferred as ordinary movement");
    }
    private static double y(Store<EntityStore> store,Ref<EntityStore> ref){return store.getComponent(ref,TransformComponent.getComponentType()).getPosition().y;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
