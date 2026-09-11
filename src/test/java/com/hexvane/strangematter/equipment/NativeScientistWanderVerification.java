package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.worldgen.ScientistPage;
import com.hexvane.strangematter.worldgen.ScientistService;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.UUID;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Native scientist AI and shared trading pause, with real nearby players and restored terrain. */
public final class NativeScientistWanderVerification {
    private record Cell(int x,int y,int z,int block,int rotation,int filler) {}
    private static final Vector3d HOME=new Vector3d(15.832658,247,14.335622);
    private static final int SETTINGS=NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED;

    public static void verify(World world)throws Exception {
        var store=world.getEntityStore().getStore();store.assertThread();
        var chunk=world.getChunkIfInMemory(com.hypixel.hytale.math.util.ChunkUtil.indexChunk(0,0));
        require(chunk!=null,"Scientist arena uses an already loaded column");
        var saved=new ArrayList<Cell>();
        var service=new ScientistService(Files.createTempDirectory("sm-native-scientist-wander-"));
        service.setGenerationEnabled(false);
        UUID merchant=null;double greatestRadius=0,walked=0;int idleTicks=0;
        try{
            for(int x=3;x<=29;x++)for(int z=3;z<=29;z++)for(int y=246;y<=251;y++){
                var section=chunk.getBlockChunk().getSectionAtBlockY(y);
                saved.add(new Cell(x,y,z,section.get(x,y,z),section.getRotationIndex(x,y,z),section.getFiller(x,y,z)));
                String id=y==246?"Rock_Stone":"Empty";var type=BlockType.getAssetMap().getAsset(id);
                chunk.setBlock(x,y,z,BlockType.getAssetMap().getIndex(id),type,0,0,SETTINGS);
            }
            try(var first=NativePlayerFixture.create(world,"Scientist observer",new Vector3d(HOME).add(0,0,6));
                var second=NativePlayerFixture.create(world,"Second customer",new Vector3d(HOME).add(0,0,-6))){
                UUID identity=service.spawnScientist(world,new Vector3d(HOME));var record=service.record(identity);
                require(record!=null&&record.spawned&&record.entity!=null,"Actual ScientistService spawns the native role");
                merchant=record.entity;var original=ref(world,merchant);
                var npc=store.getComponent(original,NPCEntity.getComponentType());
                require(npc.requiresLeashPosition()&&npc.getLeashPoint().distance(HOME)<1e-8,"Wandering role requires and initializes its native spawn leash");
                assertIdentity(world,merchant);
                double dt=1d/world.getTps();Vector3d previous=position(world,merchant);
                for(int i=0;i<ticks(35,dt);i++){
                    advance(world,service,dt);var current=position(world,merchant);double step=horizontal(previous,current);
                    walked+=step;if(step<.0001)idleTicks++;
                    greatestRadius=Math.max(greatestRadius,horizontal(current,HOME));
                    require(step<=.5,"Scientist uses gradual native walking, never a position jump: "+step);
                    require(horizontal(current,HOME)<=4.8,"Native wandering and leash correction stay close to spawn: "+current);
                    require(Math.abs(current.y-HOME.y)<.3,"Scientist remains on its real arena floor");
                    previous=current;assertIdentity(world,merchant);
                }
                require(walked>.8&&greatestRadius>.4,"Scientist walks while real players are nearby: distance="+walked+", radius="+greatestRadius);
                require(idleTicks>ticks(1,dt),"Native wandering includes meaningful stationary pauses");

                // Native codec roundtrip must retain the original home after wandering.
                npc=store.getComponent(ref(world,merchant),NPCEntity.getComponentType());
                var encoded=NPCEntity.CODEC.encode(npc).asDocument();
                require(encoded.containsKey("LeashPos"),"Native NPC save contains its home position");
                var restored=NPCEntity.CODEC.decode(encoded);
                require(restored.getLeashPoint().distance(HOME)<1e-8,"Native save/decode retains spawn home rather than current wandering position");

                var beforeMigration=position(world,merchant);npc.setLeashPoint(new Vector3d());
                service.tick(world,.25);
                npc=store.getComponent(ref(world,merchant),NPCEntity.getComponentType());
                require(npc.getLeashPoint().distance(HOME)<1e-8,"Legacy zero home migrates to the known laboratory anchor");
                require(position(world,merchant).equals(beforeMigration),"Legacy home migration never teleports the scientist");

                // A one-time external displacement represents knockback or a changed room.
                var displaced=ref(world,merchant);store.getComponent(displaced,TransformComponent.getComponentType()).setPosition(new Vector3d(HOME).add(8,0,0));
                var velocity=store.getComponent(displaced,Velocity.getComponentType());if(velocity!=null)velocity.set(new Vector3d());
                previous=position(world,merchant);boolean returned=false;
                for(int i=0;i<ticks(22,dt);i++){
                    advance(world,service,dt);var current=position(world,merchant);
                    require(horizontal(previous,current)<=.5,"Leash return walks through native physics rather than snapping home");
                    require(horizontal(current,HOME)<8.5,"Displaced scientist seeks inward instead of continuing to wander away");
                    if(horizontal(current,HOME)<.8){returned=true;break;}previous=current;
                }
                require(returned,"Native leash Seek returns a displaced scientist to its home approach radius");
                assertIdentity(world,merchant);

                var at=position(world,merchant);
                store.getComponent(first.ref(),TransformComponent.getComponentType()).setPosition(new Vector3d(at).add(2,0,0));
                store.getComponent(second.ref(),TransformComponent.getComponentType()).setPosition(new Vector3d(at).add(-2,0,0));
                require(service.open(first.owner(),store,identity)&&service.open(second.owner(),store,identity),"Both nearby players open the real scientist trade page");
                require(first.player().getPageManager().getCustomPage() instanceof ScientistPage
                        &&second.player().getPageManager().getCustomPage() instanceof ScientistPage,"Native page managers hold both actual ScientistPages");
                require(trading(world,merchant),"Opening a trade immediately selects Trading");
                for(int i=0;i<ticks(.75,dt);i++)advance(world,service,dt);
                var stopped=position(world,merchant);
                for(int i=0;i<ticks(3,dt);i++){
                    advance(world,service,dt);
                    require(trading(world,merchant)&&horizontal(position(world,merchant),stopped)<.04,"Trading stops native walking while the pages remain open");
                }
                first.player().getPageManager().setPage(first.ref(),store,Page.None);
                for(int i=0;i<ticks(1,dt);i++)advance(world,service,dt);
                require(trading(world,merchant)&&horizontal(position(world,merchant),stopped)<.04,"Closing one customer's page cannot release the other customer's pause");
                second.player().getPageManager().setPage(second.ref(),store,Page.None);
                service.tick(world,.25);
                require(!trading(world,merchant),"Closing the last page releases Trading on the ordinary service tick");
                previous=position(world,merchant);double resumed=0;
                for(int i=0;i<ticks(25,dt);i++){
                    advance(world,service,dt);var current=position(world,merchant);resumed+=horizontal(previous,current);previous=current;
                    require(horizontal(current,HOME)<=4.8,"Resumed scientist remains bound to the same home");
                    assertIdentity(world,merchant);
                }
                require(resumed>.8,"Scientist resumes native wandering after the final trade closes");
                require(service.record(identity).entity.equals(merchant),"Trading, walking and migration retain the same persistent scientist UUID");
                service.save();
            }
        }finally{
            if(merchant!=null){var entity=world.getEntityStore().getRefFromUUID(merchant);if(entity!=null&&entity.isValid())store.removeEntity(entity,RemoveReason.REMOVE);}
            for(var old:saved)chunk.setBlock(old.x,old.y,old.z,old.block,BlockType.getAssetMap().getAsset(old.block),old.rotation,old.filler,SETTINGS);
        }
        System.out.println("NATIVE_SCIENTIST_WANDER_VERIFICATION_PASSED: actual native walking near observers, pauses, home radius, gradual return, native LeashPos persistence and migration, two real trading pages, final-close resume and unchanged UUID/appearance. Distance="+walked+", radius="+greatestRadius+", idle ticks="+idleTicks);
    }
    private static int ticks(double seconds,double dt){return (int)Math.ceil(seconds/dt);}
    private static double horizontal(Vector3d a,Vector3d b){return Math.hypot(a.x-b.x,a.z-b.z);}
    private static Ref<EntityStore> ref(World world,UUID id){
        var ref=world.getEntityStore().getRefFromUUID(id);require(ref!=null&&ref.isValid(),"Scientist survives native role transitions with its UUID");return ref;
    }
    private static Vector3d position(World world,UUID id){return new Vector3d(world.getEntityStore().getStore().getComponent(ref(world,id),TransformComponent.getComponentType()).getPosition());}
    private static boolean trading(World world,UUID id){var support=StateSupport.get(ref(world,id),world.getEntityStore().getStore());return support.inState(support.getStateHelper().getStateIndex("Trading"));}
    private static void advance(World world,ScientistService service,double dt){world.getEntityStore().getStore().tick((float)dt);service.tick(world,dt);}
    private static void assertIdentity(World world,UUID id){
        var store=world.getEntityStore().getStore();var ref=ref(world,id);var npc=store.getComponent(ref,NPCEntity.getComponentType());
        require(npc!=null&&ScientistService.ROLE.equals(npc.getRoleName()),"Scientist retains its authored native role");
        require(store.getComponent(ref,UUIDComponent.getComponentType()).getUuid().equals(id),"Native UUID remains unchanged");
        var model=store.getComponent(ref,ModelComponent.getComponentType());
        require(model!=null&&ScientistService.APPEARANCE.equals(model.getModel().getModelAssetId()),"Scientist keeps its dressed custom appearance");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
