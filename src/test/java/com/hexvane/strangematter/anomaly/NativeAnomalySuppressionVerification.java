package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.packets.entities.ChangeVelocity;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Full native field scheduling, finite source ownership, overlapping suppression and callback cancellation. */
public final class NativeAnomalySuppressionVerification {
    public static void verify(World world)throws Exception{
        verifyFields(world);verifyOwnership(world);verifyWarpCancellation(world);
        System.out.println("NATIVE_ANOMALY_SUPPRESSION_VERIFICATION_PASSED: all six schedules disabled with visible/scannable identities, overlapping sites and removal/range resume, native rift health and zeroG packets, haze/phantom cleanup, exact shadow summons, original temporal aging history, overlapping native effect/model sources and external preservation, delayed natural warp cancellation and resume.");
    }
    private static void verifyFields(World world)throws Exception{
        var service=new AnomalyService(Files.createTempDirectory("sm-nullifier-fields-"));service.naturalGeneration=false;
        var sites=new AtomicReference<>(List.of(new Vector3d(20,55,20),new Vector3d(21,55,20)));
        service.setSuppressionHook((w,p,t)->sites.get().stream().anyMatch(site->site.distanceSquared(p)<=144));
        var refs=new ArrayList<Ref<EntityStore>>();var identities=new ArrayList<UUID>();
        try(var player=NativePlayerFixture.create(world,"NullifierFields",new Vector3d(20,55,20))){
            player.player().handleClientReady(false);player.player().setLastSpawnTimeNanos(System.nanoTime()-TimeUnit.SECONDS.toNanos(30));
            var store=player.store();var cow=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(21,55,19),new Rotation3f()).first();refs.add(cow);
            UUID cowId=store.getComponent(cow,UUIDComponent.getComponentType()).getUuid();identities.add(cowId);
            var summoned=NPCPlugin.get().spawnNPC(store,"Zombie",null,new Vector3d(22,55,20),new Rotation3f()).first();refs.add(summoned);
            var fields=new EnumMap<AnomalyType,AnomalyRecord>(AnomalyType.class);
            for(var type:AnomalyType.values())fields.put(type,service.spawn(type,world,new Vector3d(type==AnomalyType.WARP_GATE?26:20,56,20),true));
            var gate=fields.get(AnomalyType.WARP_GATE);var partner=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(50,56,20),true);service.pair(gate.id,partner.id);
            var shadow=fields.get(AnomalyType.ECHOING_SHADOW);UUID summonId=store.getComponent(summoned,UUIDComponent.getComponentType()).getUuid();
            shadow.shadowMobs.add(summonId);shadow.shadowMobPositions.put(summonId,new double[]{22,55,20});
            store.tick(.05f);float before=health(player);service.tick(world,.05);store.tick(.05f);
            require(health(player)==before&&!has(player,"SM_Cognitive_Dissonance")&&!has(player,"SM_Shadow_Veil"),"Two nullifiers prevent scheduled damage, cognitive haze and shadow veil");
            require(!summoned.isValid()&&cow.isValid()&&!shadow.shadowMobs.contains(summonId),"Suppression removes only this field's tracked shadow summon, without removing an ordinary animal");
            require("Cow".equals(store.getComponent(cow,NPCEntity.getComponentType()).getRoleName()),"Suppressed temporal field performs no juvenile role transition");
            for(var field:fields.values()){
                require(field.primary<0&&field.active()&&field.scannable(),"Suppressed "+field.type+" retains its identity while its gameplay schedule stays unexecuted");
                require(player.packets().ofType(SpawnParticleSystem.class).stream().anyMatch(packet->field.particleId().equals(packet.particleSystemId)),"Suppressed "+field.type+" retains its ambient field particles");
                require(corePresent(world,field),"Suppressed "+field.type+" retains its native visual core");
            }
            require(gate.pairedGate.equals(partner.id)&&partner.pairedGate.equals(gate.id),"Suppression leaves both paired gate identities intact");
            require(player.packets().ofType(ChangeVelocity.class).isEmpty(),"Suppressed gravity does not issue native zero G instructions");
            sites.set(List.of(new Vector3d(21,55,20)));service.tick(world,.05);
            require(health(player)==before&&!has(player,"SM_Cognitive_Dissonance"),"Removing one overlapping nullifier leaves the other authoritative");
            sites.set(List.of());service.tick(world,.05);store.tick(.05f);
            require(Math.abs(before-health(player)-5)<.001&&has(player,"SM_Cognitive_Dissonance")&&has(player,"SM_Shadow_Veil"),"Removing the final nullifier immediately resumes native rift damage and both finite effects");
            for(var field:fields.values())if(field.type!=AnomalyType.GRAVITY)require(field.primary>0,"Normal "+field.type+" scheduler resumes after suppression ends");
            require(!player.packets().ofType(ChangeVelocity.class).isEmpty(),"Gravity resumes through actual native player velocity packets");
            // RoleChangeSystem unloads and re-adds the same UUID with a new ECS reference.
            requireRole(world,cowId,"Cow_Calf","Temporal aging resumes through a real native juvenile role transition");
            for(int i=0;i<10;i++){service.tick(world,.05);store.tick(.05f);}
            require(phantoms(player)>0,"Active Thoughtwell creates an actual private apparition before suppression");
            sites.set(List.of(new Vector3d(20,55,20)));before=health(player);service.tick(world,.05);store.tick(.05f);
            require(health(player)==before&&!has(player,"SM_Cognitive_Dissonance")&&!has(player,"SM_Shadow_Veil")&&phantoms(player)==0,"New suppression immediately clears owned haze, veil and visible phantoms without another rift hit");
            requireRole(world,cowId,"Cow_Calf","Suppression does not rewind historical age changes; ordinary juvenile aging continues");
            player.packets().packets.clear();service.tick(world,.05);store.tick(.05f);
            require(player.packets().ofType(ChangeVelocity.class).isEmpty(),"Once the cleanup packet is dispatched, suppressed gravity sends no ongoing motion");
            sites.set(List.of(new Vector3d(100,55,100)));service.tick(world,.05);
            require(Math.abs(before-health(player)-5)<.001&&has(player,"SM_Cognitive_Dissonance"),"Moving the only active nullifier outside twelve blocks resumes the field");
            require(gate.pairedGate.equals(partner.id)&&partner.pairedGate.equals(gate.id),"Resume never changes saved portal pairing");
        }finally{
            service.setSuppressionHook((w,p,t)->true);service.tick(world,.05);service.stopWorld(world);
            for(var ref:refs)if(ref.isValid())world.getEntityStore().getStore().removeEntity(ref,RemoveReason.REMOVE);
            for(var id:identities){var ref=world.getEntityStore().getRefFromUUID(id);if(ref!=null&&ref.isValid())world.getEntityStore().getStore().removeEntity(ref,RemoveReason.REMOVE);}
        }
    }
    private static void verifyOwnership(World world)throws Exception{
        var effects=new HytaleAnomalyEffects();Ref<EntityStore> cow=null;
        try(var player=NativePlayerFixture.create(world,"NullifierOwnership",new Vector3d(20,55,20))){
            player.player().handleClientReady(false);var store=player.store();
            cow=NPCPlugin.get().spawnNPC(store,"Cow",null,new Vector3d(21,55,20),new Rotation3f()).first();store.tick(.05f);
            Model original=store.getComponent(cow,ModelComponent.getComponentType()).getModel();
            var a=new AnomalyRecord(UUID.randomUUID(),AnomalyType.THOUGHTWELL,world.getName(),new Vector3d(20,56,20),true);
            var b=new AnomalyRecord(UUID.randomUUID(),AnomalyType.THOUGHTWELL,world.getName(),new Vector3d(21,56,20),true);
            effects.cognitive(world,a,true,new Random(1));effects.cognitive(world,a,false,new Random(1));
            var fromA=store.getComponent(cow,ModelComponent.getComponentType()).getModel();
            effects.cognitive(world,b,true,new Random(2));effects.cognitive(world,b,false,new Random(2));effects.tickThoughts(world,.4);
            require(fromA!=original&&store.getComponent(cow,ModelComponent.getComponentType()).getModel()!=fromA&&phantoms(player)==1,"Two native Thoughtwells contribute distinct finite disguises and one private apparition");
            effects.suppress(world,b);
            require(has(player,"SM_Cognitive_Dissonance")&&phantoms(player)==1&&store.getComponent(cow,ModelComponent.getComponentType()).getModel()==fromA,"Suppressing the latest source retains the earlier source's haze, apparition and exact disguise");
            effects.suppress(world,a);
            require(!has(player,"SM_Cognitive_Dissonance")&&phantoms(player)==0&&store.getComponent(cow,ModelComponent.getComponentType()).getModel()==original,"Suppressing every source removes all owned effects and restores the original native NPC model");
            effects.cognitive(world,a,false,new Random(3));
            var outside=Model.createUnitScaleModel(ModelAsset.getAssetMap().getAsset("Pig"));store.putComponent(cow,ModelComponent.getComponentType(),new ModelComponent(outside));
            effects.suppress(world,a);require(store.getComponent(cow,ModelComponent.getComponentType()).getModel()==outside,"Suppression preserves a later model change made outside the field");

            var controller=store.getComponent(player.ref(),EffectControllerComponent.getComponentType());var effect=EntityEffect.getAssetMap().getAsset("SM_Cognitive_Dissonance");
            int index=EntityEffect.getAssetMap().getIndex(effect.getId());var leases=new AnomalyEffectLeases();
            controller.addEffect(player.ref(),effect,3,OverlapBehavior.OVERWRITE,store);
            leases.apply(world,a.id,player.ref(),effect.getId(),10);leases.apply(world,b.id,player.ref(),effect.getId(),8);
            leases.tick(world,1);leases.removeSource(world,a.id);leases.removeSource(world,b.id);
            require(Math.abs(controller.getActiveEffects().get(index).getRemainingDuration()-2)<.001,"Removing all field owners restores the external effect's actual remaining duration");
            controller.removeEffect(player.ref(),index,RemovalBehavior.COMPLETE,store);
            leases.apply(world,a.id,player.ref(),effect.getId(),10);leases.apply(world,b.id,player.ref(),effect.getId(),8);
            leases.removeSource(world,a.id);require(controller.hasEffect(index),"A second owned native effect source survives first-source removal");
            leases.removeSource(world,b.id);require(!controller.hasEffect(index),"Last-source removal removes the actual native effect after overlap reapplication");
            controller.addInfiniteEffect(player.ref(),index,effect,store);leases.apply(world,a.id,player.ref(),effect.getId(),5);leases.removeSource(world,a.id);
            require(controller.getActiveEffects().get(index).isInfinite(),"An external infinite effect remains outside nullifier ownership");
            controller.removeEffect(player.ref(),index,RemovalBehavior.COMPLETE,store);leases.clear(world);
        }finally{effects.restore(world);if(cow!=null&&cow.isValid())world.getEntityStore().getStore().removeEntity(cow,RemoveReason.REMOVE);}
    }
    private static void verifyWarpCancellation(World world)throws Exception{
        var service=new AnomalyService(Files.createTempDirectory("sm-nullifier-warp-"));service.naturalGeneration=false;
        boolean[] suppressed={false};service.setSuppressionHook((w,p,t)->suppressed[0]);
        var pending=new ArrayList<CompletableFuture<Object>>();
        var loads=new WarpLandingLoads((w,index)->{var future=new CompletableFuture<Object>();pending.add(future);return future;},System::nanoTime);
        var field=AnomalyService.class.getDeclaredField("gateLoads");field.setAccessible(true);field.set(service,loads);
        var create=AnomalyService.class.getDeclaredMethod("createDistantPair",World.class,AnomalyRecord.class,int.class);create.setAccessible(true);
        try{
            var source=service.spawn(AnomalyType.WARP_GATE,world,new Vector3d(16,240,16),true);source.creatingPair=true;create.invoke(service,world,source,0);
            require(pending.size()==9&&source.creatingPair,"Natural warp has a real pending destination batch before suppression");
            suppressed[0]=true;service.tick(world,.05);require(!source.creatingPair,"Suppression cancels the source's outstanding preparation state");
            for(var future:pending)future.complete(Boolean.TRUE);world.consumeTaskQueue();
            require(service.all().size()==1&&source.pairedGate==null&&pending.size()==9,"Late natural generation completion cannot create a partner for the suppressed source");
            suppressed[0]=false;source.creatingPair=true;create.invoke(service,world,source,0);
            require(pending.size()==18&&source.creatingPair,"An unsuppressed source can prepare its destination again after cancelled work");
        }finally{service.stopWorld(world);for(var future:pending)future.complete(Boolean.TRUE);world.consumeTaskQueue();}
    }
    private static boolean corePresent(World world,AnomalyRecord field){
        return world.getEntityStore().getStore().forEachChunk(ModelComponent.getComponentType(),(chunk,commands)->{
            for(int i=0;i<chunk.size();i++)if((field.particleId()+"_Core").equals(chunk.getComponent(i,ModelComponent.getComponentType()).getModel().getModelAssetId()))return true;return false;
        });
    }
    private static int phantoms(NativePlayerFixture player){
        int[] count={0};var type=ThoughtwellHallucinations.componentType();require(type!=null,"Native private hallucination component is registered");
        player.store().forEachChunk(type,(chunk,commands)->{for(int i=0;i<chunk.size();i++)if(chunk.getComponent(i,type).owner==player.ref())count[0]++;});return count[0];
    }
    private static boolean has(NativePlayerFixture player,String id){
        var controller=player.store().getComponent(player.ref(),EffectControllerComponent.getComponentType());var effect=controller.getActiveEffects().get(EntityEffect.getAssetMap().getIndex(id));
        return effect!=null&&(effect.isInfinite()||effect.getRemainingDuration()>0);
    }
    private static float health(NativePlayerFixture player){return player.store().getComponent(player.ref(),EntityStatMap.getComponentType()).get(DefaultEntityStatTypes.getHealth()).get();}
    private static void requireRole(World world,UUID identity,String expected,String message){
        var ref=world.getEntityStore().getRefFromUUID(identity);
        var npc=ref!=null&&ref.isValid()?world.getEntityStore().getStore().getComponent(ref,NPCEntity.getComponentType()):null;
        String actual=npc==null?"missing":npc.getRoleName();
        require(expected.equals(actual),message+"; UUID="+identity+", expected="+expected+", actual="+actual+", ref="+ref);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
