package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.WorldAccess;

import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.machine.MachineState;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.player.ClientMovement;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerInput;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.lang.foreign.MemorySegment;
import java.util.*;

/** Real native body sweeps, player velocity packets and integrated dropped-item physics. */
public final class NativeLevitationVerification {
    private record Cell(int block,int rotation,int filler) { }
    public static void verify(World world,MachineService machines)throws Exception {
        var store=world.getEntityStore().getStore();var at=new Vector3i(8,190,8);var roof=new Vector3i(8,200,8);var side=new Vector3i(9,198,8);
        var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunkFromBlock(at.x,at.z));
        require(chunk!=null,"Levitation fixture uses an already loaded native column");
        var originals=new LinkedHashMap<Vector3i,Cell>();
        // An earlier geology fixture intentionally leaves rock at y194..199. Snapshot
        // and isolate the entire swept body volume, not only the three explicit test blocks.
        for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)for(int y=189;y<=210;y++){
            var section=WorldAccess.section(chunk,y);
            originals.put(new Vector3i(x,y,z),new Cell(section.get(x,y,z),section.getRotationIndex(x,y,z),section.getFiller(x,y,z)));
        }
        var fields=new LaboratoryFields(machines);Ref<EntityStore> drop=null;
        try(var player=NativePlayerFixture.create(world,"LevitationSubject",new Vector3d(8.5,191,8.5))){
            for(var pos:originals.keySet())writeCell(chunk,pos,new Cell(0,0,0));
            refreshHeights(chunk);
            world.setBlock(at.x,at.y,at.z,"SM_Levitation_Pad");world.setBlock(roof.x,roof.y,roof.z,"Rock_Stone");
            var pad=machines.register(world,at,"SM_Levitation_Pad");pad.enabled=true;pad.ascending=true;
            player.player().handleClientReady(false);
            store.tick(.05f);
            var bounds=store.getComponent(player.ref(),BoundingBox.getComponentType()).getBoundingBox();
            var transform=store.getComponent(player.ref(),TransformComponent.getComponentType());
            var velocity=store.getComponent(player.ref(),Velocity.getComponentType());
            velocity.getClientVelocity().set(1.25,-6,-.5);
            double top=LaboratoryFields.liftShaftTop(store,pad,206);
            require(top>199.95&&top<=200,"Native shaft and particle endpoint reach the real roof underside: "+top);
            velocity.getInstructions().clear();var before=new Vector3d(transform.getPosition());fields.tick(world,.05);
            var instruction=velocity.getInstructions().getLast();
            require(instruction.getType()==ChangeVelocityType.Set&&instruction.getConfig()==null&&instruction.getVelocity().y==4,
                    "Actual field issues native upward velocity without a split force channel");
            require(instruction.getVelocity().x>0&&instruction.getVelocity().x<1.25
                            &&instruction.getVelocity().z<0&&instruction.getVelocity().z>-.5&&transform.getPosition().equals(before),
                    "Lift gently damps released horizontal momentum and never teleports the player");
            store.tick(.05f);var packet=player.packets().ofType(ChangeVelocity.class).getLast();
            var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
            require(packet.equals(ChangeVelocity.toObject(bytes))&&packet.y==4,"Actual player lift instruction survives native wire encoding");

            verifyPlayerControls(player,fields,pad);

            reportPosition(player,new Vector3d(8.5,200-bounds.max.y-.08,8.5));
            velocity.getInstructions().clear();before=new Vector3d(transform.getPosition());fields.tick(world,.05);
            double near=velocity.getInstructions().getLast().getVelocity().y;
            double head=before.y+near*.05+bounds.max.y;
            require(near>0&&near<4&&head<=200-.019&&head>200-.07,
                    "Real body clearance reaches within centimetres of the roof rather than stopping blocks short: head="+head+", maxY="+bounds.max.y);
            require(transform.getPosition().equals(before),"Ceiling approach still uses velocity only");
            reportPosition(player,new Vector3d(before).add(0,near*.05,0));
            velocity.getInstructions().clear();fields.tick(world,.05);
            require(Math.abs(velocity.getInstructions().getLast().getVelocity().y)<1e-6,"The actual body holds just below ceiling contact");
            pad.ascending=false;velocity.getInstructions().clear();fields.tick(world,.05);
            require(velocity.getInstructions().getLast().getVelocity().y==-4,"Descent is four blocks per second, matching ascent");

            // The whole body is swept, including the neighbouring column under an off-centre rider.
            world.setBlock(side.x,side.y,side.z,"Rock_Stone");pad.ascending=true;
            reportPosition(player,new Vector3d(8.9,198-bounds.max.y-.08,8.5));
            velocity.getInstructions().clear();before=new Vector3d(transform.getPosition());fields.tick(world,.05);
            double sideSpeed=velocity.getInstructions().getLast().getVelocity().y;
            require(sideSpeed>0&&before.y+sideSpeed*.05+bounds.max.y<=198-.019,
                    "Neighbouring ceiling blocks stop the actual wide collider safely");
            world.setBlock(side.x,side.y,side.z,"Empty");

            // Real collider contact must release descent, regardless of a stale airborne flag.
            pad.ascending=false;
            reportMotion(player,new Vector3d(0,-4,0),false,null,false);
            reportPosition(player,new Vector3d(8.5,190.421875-bounds.min.y+.02,8.5));
            velocity.getInstructions().clear();fields.tick(world,.05);
            require(velocity.getInstructions().isEmpty(),"Native pad contact stops downward commands before another landing impulse");
            reportMotion(player,new Vector3d(),false,null,true);
            reportPosition(player,new Vector3d(8.5,190.421875-bounds.min.y,8.5));
            velocity.getInstructions().clear();player.packets().packets.clear();
            for(int tick=0;tick<80;tick++){
                fields.tick(world,.05);
                require(velocity.getInstructions().isEmpty(),"Settled player receives no repeated downward or centering instructions");
                store.tick(.05f);
            }
            require(player.packets().ofType(ChangeVelocity.class).isEmpty(),"Four seconds on the pad produce no new native velocity packets or repeated landing commands");
            require(transform.getPosition().y+bounds.min.y==190.421875,"Settled player feet stay at the actual pad top without transform writes");
            reportMotion(player,new Vector3d(3,0,0),true,null,true);store.tick(.05f);
            velocity.getInstructions().clear();fields.tick(world,.05);
            require(velocity.getInstructions().isEmpty(),"Grounded player can walk off without any field command");
            reportPosition(player,new Vector3d(20.5,191,20.5));

            // This independent physics phase starts in ascent after the player landing checks.
            pad.ascending=true;
            var payload=new ItemStack("SM_Raw_Resonite",3).withMetadata("LevitationProof",Codec.STRING,"exact specimen");
            drop=store.addEntity(ItemComponent.generateItemDrop(store,payload,new Vector3d(8.5,191,8.5),new Rotation3f(),0,0,0),AddReason.SPAWN);
            store.getComponent(drop,ItemComponent.getComponentType()).setPickupDelay(90);
            var dropBody=store.getComponent(drop,BoundingBox.getComponentType()).getBoundingBox();
            for(int tick=0;tick<160;tick++){
                fields.tick(world,.05);store.tick(.05f);require(drop.isValid(),"Native levitated item remains present");
                require(y(store,drop)+dropBody.max.y<=200.001,"Integrated native item physics never crosses the roof");
            }
            double raised=y(store,drop);
            require(raised+dropBody.max.y>199.65,"Short native item reaches its own correct head clearance: "+(raised+dropBody.max.y));
            pad.ascending=false;
            for(int tick=0;tick<20;tick++){fields.tick(world,.05);store.tick(.05f);}
            require(y(store,drop)<raised-3.2,"Actual native item descends promptly through full physics ticks");
            for(int tick=0;tick<70;tick++){fields.tick(world,.05);store.tick(.05f);}
            require(y(store,drop)+dropBody.min.y>=190.4218&&y(store,drop)+dropBody.min.y<190.7,
                    "Downward sweep and native physics stop on the pad's actual fractional top surface");
            require(store.getComponent(drop,ItemComponent.getComponentType()).getItemStack().equals(payload),"Levitated item retains quantity and metadata");
            pad.enabled=false;velocity.getInstructions().clear();fields.tick(world,.05);
            require(velocity.getInstructions().isEmpty(),"Disabled pad sends no further player motion");
            world.setBlock(roof.x,roof.y,roof.z,"Empty");
            require(Math.abs(LaboratoryFields.liftShaftTop(store,pad,206)-207)<1e-8,"Open shaft retains the configured range endpoint");
            reportMotion(player,new Vector3d(3,0,0),true,new Vector3d(1,0,0),false);store.tick(.05f);
            require(LevitationInputSystem.wantsToMove(store,player.ref()),"Fresh native movement intent is present before world cleanup");
            fields.cleanup(world);
            require(!LevitationInputSystem.wantsToMove(store,player.ref()),"World cleanup drops all lift input samples");
        }finally{
            if(drop!=null&&drop.isValid())store.removeEntity(drop,RemoveReason.REMOVE);
            machines.removed(world,at);
            for(var entry:originals.entrySet())writeCell(chunk,entry.getKey(),entry.getValue());
            refreshHeights(chunk);fields.cleanup(world);
            for(var entry:originals.entrySet()){
                var p=entry.getKey();var expected=entry.getValue();var section=WorldAccess.section(chunk,p.y);
                require(section.get(p.x,p.y,p.z)==expected.block()&&section.getRotationIndex(p.x,p.y,p.z)==expected.rotation()
                                &&section.getFiller(p.x,p.y,p.z)==expected.filler(),
                        "Fixture restores each original shaft/neighbor block, rotation and filler: "+p);
            }
        }
        System.out.println("NATIVE_LEVITATION_VERIFICATION_PASSED: native shaft/body sweeps, exact head clearance, passive drift centering, native wish and ordinary movement escape, zero repeated settled descent packets, velocity-only player wire packet, faster descent, real item ascent/descent/roof/pad collisions and metadata, unchanged open range and cleanup.");
    }
    private static void verifyPlayerControls(NativePlayerFixture player,LaboratoryFields fields,MachineState pad){
        var store=player.store();var velocity=store.getComponent(player.ref(),Velocity.getComponentType());
        var transform=store.getComponent(player.ref(),TransformComponent.getComponentType());
        reportMotion(player,new Vector3d(4.5,0,-2.25),false,null,false);
        reportPosition(player,new Vector3d(8.82,191,8.3));
        for(int tick=0;tick<32;tick++){
            var before=new Vector3d(transform.getPosition());velocity.getInstructions().clear();fields.tick(player.world(),.05);
            require(!velocity.getInstructions().isEmpty(),"Released entry momentum stays in the lift until its endpoint");
            require(transform.getPosition().equals(before),"Centering never moves the player's native transform");
            store.tick(.05f);var command=player.packets().ofType(ChangeVelocity.class).getLast();
            require(Math.hypot(command.x,command.z)<=1.001,"Passive centering stays below one block per second horizontally");
            var reported=new Vector3d(command.x,command.y,command.z);
            reportMotion(player,reported,false,null,false);
            reportPosition(player,before.add(new Vector3d(reported).mul(.05)));
            require(Math.abs(transform.getPosition().x-8.5)<.5&&Math.abs(transform.getPosition().z-8.5)<.5,
                    "Actual packet feedback catches initial drift inside the one block lift shaft");
        }
        require(Math.hypot(transform.getPosition().x-8.5,transform.getPosition().z-8.5)<.04,
                "Passive rider converges gently to the pad center");
        verifyEdgeEntries(player,fields,pad);
        reportMotion(player,new Vector3d(3,0,0),true,new Vector3d(1,0,0),false);store.tick(.05f);
        require(LevitationInputSystem.wantsToMove(store,player.ref()),"Fresh wish packet creates a short movement lease");
        reportMotion(player,new Vector3d(3,0,0),false,null,false);store.tick(.05f);
        require(!LevitationInputSystem.wantsToMove(store,player.ref()),"Idle packet immediately releases the lease despite retained momentum");
        reportMotion(player,new Vector3d(3,0,0),true,null,false);store.tick(.05f);
        require(LevitationInputSystem.wantsToMove(store,player.ref()),"Ordinary packet creates a fresh movement lease");
        for(int tick=0;tick<10;tick++)store.tick(.05f);
        require(!LevitationInputSystem.wantsToMove(store,player.ref()),"Missing reports expire even if old locomotion flags remain active");
        Ref<EntityStore> departing;
        try(var other=NativePlayerFixture.create(player.world(),"LevitationIntentLifecycle",new Vector3d(20.5,191,20.5))){
            departing=other.ref();other.player().handleClientReady(false);
            reportMotion(other,new Vector3d(3,0,0),true,new Vector3d(1,0,0),false);store.tick(.05f);
            require(LevitationInputSystem.wantsToMove(store,departing),"Separate native player owns its own input sample");
        }
        require(!LevitationInputSystem.wantsToMove(store,departing),"Native player removal clears its sample before a reconnect or world transfer");
        for(boolean explicit:new boolean[]{true,false}){
            reportMotion(player,new Vector3d(),false,null,false);reportPosition(player,new Vector3d(8.5,194,8.5));
            boolean left=false;
            for(int tick=0;tick<12;tick++){
                var walking=new Vector3d(3,0,0);reportMotion(player,walking,true,explicit?new Vector3d(1,0,0):null,false);
                store.tick(.05f);velocity.getInstructions().clear();fields.tick(player.world(),.05);
                if(velocity.getInstructions().isEmpty())left=true;
                else{
                    require(!left,"Lift never pulls a deliberately departing player back into its volume");
                    var command=velocity.getInstructions().getLast().getVelocity();
                    require(command.x==walking.x&&command.z==walking.z,"Native "+(explicit?"wish":"ordinary")+" input immediately disables horizontal centering");
                    walking.y=command.y;
                }
                reportPosition(player,new Vector3d(transform.getPosition()).add(walking.mul(.05)));
            }
            require(left&&transform.getPosition().x>10,"Deliberate "+(explicit?"wish":"ordinary")+" movement leaves the real lift volume naturally");
        }
        reportMotion(player,new Vector3d(),false,null,false);store.tick(.05f);
    }
    private static void verifyEdgeEntries(NativePlayerFixture player,LaboratoryFields fields,MachineState pad){
        var store=player.store();var velocity=store.getComponent(player.ref(),Velocity.getComponentType());
        var transform=store.getComponent(player.ref(),TransformComponent.getComponentType());
        var box=store.getComponent(player.ref(),BoundingBox.getComponentType()).getBoundingBox();
        var min=new Vector3d(pad.x,pad.y+.15,pad.z);var max=new Vector3d(pad.x+1,200,pad.z+1);
        for(var side:new int[][]{{1,0},{-1,0},{0,1},{0,-1},{1,1}}){
            var start=new Vector3d(side[0]>0?pad.x+1-box.min.x-.001:side[0]<0?pad.x-box.max.x+.001:pad.x+.5,
                    191,side[1]>0?pad.z+1-box.min.z-.001:side[1]<0?pad.z-box.max.z+.001:pad.z+.5);
            reportMotion(player,new Vector3d(side[0]*4.5,0,side[1]*4.5),false,null,false);reportPosition(player,start);
            require(EquipmentQueries.inBox(store,min,max,true).contains(player.ref()),"Actual collider initially overlaps the shaft by one millimetre at "+Arrays.toString(side));
            double originalDistance=Math.hypot(start.x-pad.x-.5,start.z-pad.z-.5);
            for(int tick=0;tick<16;tick++){
                var before=new Vector3d(transform.getPosition());velocity.getInstructions().clear();fields.tick(player.world(),.05);
                require(!velocity.getInstructions().isEmpty(),"Passive edge entry remains in the actual field query");
                require(transform.getPosition().equals(before),"Edge overlap guard never writes a player transform");
                store.tick(.05f);var packet=player.packets().ofType(ChangeVelocity.class).getLast();
                require(Math.hypot(packet.x,packet.z)<=1.001,"Edge correction preserves the one block per second bound");
                if(tick==0)require(side[0]*packet.x<=.00001&&side[1]*packet.z<=.00001,"First native edge packet removes unsafe outward momentum");
                var motion=new Vector3d(packet.x,packet.y,packet.z);
                reportMotion(player,motion,false,null,false);reportPosition(player,before.add(new Vector3d(motion).mul(.05)));
                require(EquipmentQueries.inBox(store,min,max,true).contains(player.ref()),"Native collider and spatial query retain passive entry after packet feedback at "+Arrays.toString(side));
            }
            require(Math.hypot(transform.getPosition().x-pad.x-.5,transform.getPosition().z-pad.z-.5)<originalDistance-.15,
                    "Passive edge or corner entry moves inward smoothly");
        }
        // The same one-millimetre corner must remain freely escapable under either input path.
        for(boolean explicit:new boolean[]{true,false}){
            var start=new Vector3d(pad.x+1-box.min.x-.001,194,pad.z+1-box.min.z-.001);
            reportMotion(player,new Vector3d(3,0,3),true,explicit?new Vector3d(1,0,1):null,false);reportPosition(player,start);
            velocity.getInstructions().clear();fields.tick(player.world(),.05);store.tick(.05f);
            var packet=player.packets().ofType(ChangeVelocity.class).getLast();
            require(packet.x==3&&packet.z==3,"Deliberate corner departure bypasses passive overlap protection");
            reportPosition(player,new Vector3d(start).add(packet.x*.05,packet.y*.05,packet.z*.05));
            require(!EquipmentQueries.inBox(store,min,max,true).contains(player.ref()),"Deliberate corner departure leaves the actual native body query");
            velocity.getInstructions().clear();fields.tick(player.world(),.05);
            require(velocity.getInstructions().isEmpty(),"No recapture after deliberate corner departure");
        }
    }
    private static void reportMotion(NativePlayerFixture player,Vector3d velocity,boolean moving,Vector3d wish,boolean grounded){
        var packet=new ClientMovement();
        var states=player.store().getComponent(player.ref(),MovementStatesComponent.getComponentType()).getMovementStates().clone();
        states.idle=!moving;states.horizontalIdle=!moving;states.walking=moving;states.running=false;states.sprinting=false;states.onGround=grounded;
        packet.movementStates=states;packet.velocity=new com.hypixel.hytale.protocol.Vector3d(velocity.x,velocity.y,velocity.z);
        if(wish!=null)packet.wishMovement=new com.hypixel.hytale.protocol.Position(wish.x,wish.y,wish.z);
        var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);
        player.packets().handle(ClientMovement.toObject(bytes));player.world().consumeTaskQueue();
        var queue=player.store().getComponent(player.ref(),PlayerInput.getComponentType()).getMovementUpdateQueue();
        require(queue.stream().anyMatch(PlayerInput.SetClientVelocity.class::isInstance)
                        &&queue.stream().anyMatch(PlayerInput.WishMovement.class::isInstance)==(wish!=null),
                "Real client movement wire reaches native input processing without replacing or inventing wishes");
    }
    private static void writeCell(WorldChunk chunk,Vector3i p,Cell cell){
        WorldAccess.section(chunk,p.y).set(p.x,p.y,p.z,cell.block(),cell.rotation(),cell.filler());
    }
    private static void refreshHeights(WorldChunk chunk){for(int x=6;x<=10;x++)for(int z=6;z<=10;z++)WorldAccess.column(chunk).updateHeight(x,z);}
    private static void reportPosition(NativePlayerFixture player,Vector3d position){
        var store=player.store();var current=store.getComponent(player.ref(),TransformComponent.getComponentType()).getPosition();
        var delta=new Vector3d(position).sub(current);store.getComponent(player.ref(),PlayerInput.getComponentType()).queue(new PlayerInput.RelativeMovement(delta.x,delta.y,delta.z));
        store.tick(.05f);
    }
    private static double y(Store<EntityStore> store,Ref<EntityStore> ref){return store.getComponent(ref,TransformComponent.getComponentType()).getPosition().y;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
