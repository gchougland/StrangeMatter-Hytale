package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.builtin.adventure.farming.states.FarmingBlock;
import com.hexvane.strangematter.effects.GadgetEffects;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ChangeVelocityType;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.collision.CollisionModule;
import com.hypixel.hytale.server.core.modules.collision.CollisionResult;
import com.hypixel.hytale.server.core.modules.physics.component.Velocity;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.systems.RoleChangeSystem;
import org.joml.Vector3d;
import java.util.*;

/** All Hytale ECS mutations run on the owning world thread. */
final class HytaleAnomalyEffects {
    private final Map<UUID, Ref<EntityStore>> cores = new HashMap<>();
    private final Map<Ref<EntityStore>, TemporaryModel> models = new HashMap<>();
    private final Map<Ref<EntityStore>, Long> teleports = new HashMap<>();
    private final ThoughtwellConfusion thoughts = new ThoughtwellConfusion();
    private final GravityField gravity = new GravityField();
    private record TemporaryModel(Model original, Model applied, long expires) {}
    private static final String[] DISGUISES = {"Chicken", "Cow", "Pig", "Sheep", "Zombie", "Skeleton", "Spider"};
    private static final String[] HOSTILES = {"Zombie", "Skeleton_Fighter", "Skeleton_Archer", "Spider"};

    List<Ref<EntityStore>> entities(World world, AnomalyRecord a) {
        // ParticleUtil reuses the same thread-local list; copy before any particle calls.
        var result=new ArrayList<>(new LinkedHashSet<>(TargetUtil.getAllEntitiesInSphere(a.position(), a.type.radius, world.getEntityStore().getStore())));
        result.removeIf(cores::containsValue);
        return result;
    }
    void particle(World world, String id, Vector3d p) { ParticleUtil.spawnParticleEffect(id, p, world.getEntityStore().getStore()); }
    void sound(World world,AnomalyRecord a) {
        int id=SoundEvent.getAssetMap().getIndex(a.type.particleId+"_Loop");
        if(id>=0)SoundUtil.playSoundEvent3d(id,SoundCategory.Ambient,a.x,a.y,a.z,.65f,1,world.getEntityStore().getStore());
    }

    void core(World world, AnomalyRecord a) {
        var store=world.getEntityStore().getStore();
        Ref<EntityStore> old=cores.get(a.id);
        if (old!=null && old.isValid()) {
            var transform=store.getComponent(old,TransformComponent.getComponentType());
            if(transform!=null && a.type!=AnomalyType.WARP_GATE) {
                transform.setPosition(a.position().add(0,Math.sin(a.age*.8)*.12,0));
                transform.setRotation(new Rotation3f(0,(float)(a.age*.16),0));
            }
            return;
        }
        ModelAsset asset=ModelAsset.getAssetMap().getAsset(a.particleId()+"_Core");
        if (asset==null) return;
        Model model=Model.createUnitScaleModel(asset);
        var holder=EntityStore.REGISTRY.newHolder();
        holder.addComponent(TransformComponent.getComponentType(),new TransformComponent(a.position(),new Rotation3f()));
        holder.addComponent(ModelComponent.getComponentType(),new ModelComponent(model));
        holder.addComponent(BoundingBox.getComponentType(),new BoundingBox(model.getBoundingBox()));
        holder.addComponent(HeadRotation.getComponentType(),new HeadRotation(new Rotation3f()));
        holder.addComponent(UUIDComponent.getComponentType(),new UUIDComponent(UUID.randomUUID()));
        holder.addComponent(NetworkId.getComponentType(),new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(Intangible.getComponentType(),Intangible.INSTANCE);
        holder.addComponent(Invulnerable.getComponentType(),Invulnerable.INSTANCE);
        holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(),NonSerialized.get());
        cores.put(a.id,store.addEntity(holder,AddReason.SPAWN));
    }
    void removeCore(World world, UUID id) {
        Ref<EntityStore> ref=cores.remove(id);
        if(ref!=null && ref.isValid()) world.getEntityStore().getStore().removeEntity(ref,RemoveReason.REMOVE);
    }
    void cleanup(World world) {
        var store=world.getEntityStore().getStore(); long now=System.currentTimeMillis();
        models.entrySet().removeIf(e->{
            Ref<EntityStore> ref=e.getKey();
            if(!ref.isValid()) return true;
            if(ref.getStore()!=store || e.getValue().expires>now) return false;
            ModelComponent current=store.getComponent(ref,ModelComponent.getComponentType());
            // Only undo our own last model; leave later cosmetic changes by another plugin intact.
            if(current!=null && current.getModel()==e.getValue().applied)
                store.putComponent(ref,ModelComponent.getComponentType(),new ModelComponent(e.getValue().original));
            return true;
        });
        teleports.entrySet().removeIf(e->!e.getKey().isValid() || e.getValue()<now);
    }
    void restore(World world) {
        thoughts.clear(world);
        gravity.clear(world);
        var store=world.getEntityStore().getStore();
        for(var entry:new ArrayList<>(models.entrySet())) if(entry.getKey().isValid() && entry.getKey().getStore()==store) {
            var current=store.getComponent(entry.getKey(),ModelComponent.getComponentType());
            if(current!=null && current.getModel()==entry.getValue().applied) store.putComponent(entry.getKey(),ModelComponent.getComponentType(),new ModelComponent(entry.getValue().original));
            models.remove(entry.getKey());
        }
        for(var entry:new ArrayList<>(cores.entrySet())) if(entry.getValue()!=null && entry.getValue().isValid() && entry.getValue().getStore()==store) removeCore(world,entry.getKey());
    }
    boolean protectedBy(World world, Ref<EntityStore> ref, String item) {
        var store=world.getEntityStore().getStore();
        if(ref==null||!ref.isValid()||ref.getStore()!=store)return false;
        // Inventory's legacy armor reference is captured when the player enters the world.
        // Read the current ECS component on every check, including after armor replacement.
        var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());
        short head=(short)ItemArmorSlot.Head.ordinal();
        if(armor==null||head>=armor.getInventory().getCapacity())return false;
        var stack=armor.getInventory().getItemStack(head);
        return !ItemStack.isEmpty(stack)&&item.equals(stack.getItemId());
    }
    void gravity(World world,List<AnomalyRecord> active) {gravity.publish(world,active);}
    void removeGravity(World world,UUID source){gravity.remove(world,source);}
    void zap(World world, AnomalyRecord a) {
        for(var ref:entities(world,a)) zapTarget(world,a,ref);
    }
    boolean zapTarget(World world,AnomalyRecord a,Ref<EntityStore> ref) {
        var store=world.getEntityStore().getStore();
        // Hytale has no native Lightning cause. Use an elemental subtype instead of silently
        // classifying the discharge as Physical and subjecting it to unrelated physical armor.
        int cause=DamageCause.getAssetMap().getIndex("SM_Energetic_Rift");
        if(cause<0 || ref==null || !ref.isValid() || ref.getStore()!=store) return false;
        var player=store.getComponent(ref,Player.getComponentType());
        if(player==null && store.getComponent(ref,NPCEntity.getComponentType())==null) return false;
        // Creative and a worn hat remain immune, while visible contact still shows that an
        // ungrounded field is alive. Native spawn protection/Invulnerable can also cancel damage.
        boolean immune=player!=null && (player.getGameMode()==GameMode.Creative || protectedBy(world,ref,"SM_Tinfoil_Hat"));
        // Original 1 / 20 maximum HP: scale to Hytale's standard 100 HP pool.
        if(!immune)DamageSystems.executeDamage(ref,store,new Damage(new Damage.EnvironmentSource("energetic rift"),cause,5));
        var t=store.getComponent(ref,TransformComponent.getComponentType());
        if(t!=null) {
            Vector3d impact=new Vector3d(t.getPosition()).add(0,1,0);
            GadgetEffects.beam(world,"SM_Rift_Arc",a.position(),impact);
            GadgetEffects.use(world,"SM_Rift_Hit",impact);
        }
        return !immune;
    }
    void cognitive(World world,AnomalyRecord a,boolean players,Random random) {
        var store=world.getEntityStore().getStore();
        for(var ref:entities(world,a)) {
            if(!ref.isValid()) continue;
            Player p=store.getComponent(ref,Player.getComponentType());
            if(players) {
                if(p!=null && !protectedBy(world,ref,"SM_Tinfoil_Hat")) {
                    var t=store.getComponent(ref,TransformComponent.getComponentType());
                    var effect=EntityEffect.getAssetMap().getAsset("SM_Cognitive_Dissonance");
                    var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
                    float duration=t==null?5:(float)(5+5*Math.max(0,1-Math.sqrt(a.distanceSquared(t.getPosition()))/6));
                    if(effect!=null && controller!=null) {
                        controller.addEffect(ref,effect,duration,OverlapBehavior.OVERWRITE,store);
                        thoughts.expose(world,ref,duration,t==null?0:Math.max(0,1-Math.sqrt(a.distanceSquared(t.getPosition()))/6));
                    }
                }
            } else if(p==null && store.getComponent(ref,NPCEntity.getComponentType())!=null) {
                ModelAsset asset=ModelAsset.getAssetMap().getAsset(DISGUISES[random.nextInt(DISGUISES.length)]);
                if(asset!=null) temporaryModel(world,ref,Model.createUnitScaleModel(asset),5000);
                effect(world,ref,"SM_Cognitive_Dissonance");
                var targets=store.getComponent(ref,MarkedEntitySupport.getComponentType());
                if(targets!=null && random.nextDouble()<.3)targets.setMarkedEntity("LockedTarget",null);
            }
        }
    }
    void tickThoughts(World world,double dt){thoughts.tick(world,dt);}
    private void temporaryModel(World world,Ref<EntityStore> ref,Model model,long duration) {
        var store=world.getEntityStore().getStore();
        ModelComponent old=store.getComponent(ref,ModelComponent.getComponentType());
        if(old==null) return;
        TemporaryModel prior=models.get(ref);
        models.put(ref,new TemporaryModel(prior==null?old.getModel():prior.original,model,System.currentTimeMillis()+duration));
        // PersistentModel remains original, so unloading/restart cannot make disguises permanent.
        store.putComponent(ref,ModelComponent.getComponentType(),new ModelComponent(model));
    }
    boolean temporalMobs(World world,AnomalyRecord a,Map<UUID,TemporalAge> ages) {
        var store=world.getEntityStore().getStore();
        boolean changed=false;
        for(var ref:entities(world,a)) {
            if(!ref.isValid() || store.getComponent(ref,NPCEntity.getComponentType())==null) continue;
            var npc=store.getComponent(ref,NPCEntity.getComponentType());
            var uuid=store.getComponent(ref,UUIDComponent.getComponentType());
            var transition=TemporalRoles.transition(npc.getRoleName());
            if(uuid!=null && npc.getRole()!=null && transition.isPresent()) {
                var next=transition.get();int role=NPCPlugin.get().getIndex(next.target());
                if(role>=0) {
                    // Native role rebuild preserves the entity's UUID and holder rather than killing/replacing the animal.
                    RoleChangeSystem.requestRoleChange(ref,npc.getRole(),role,true,store);
                    if(next.becomingYoung())ages.put(uuid.getUuid(),new TemporalAge(world.getName(),next.adult(),next.juvenile()));
                    else ages.remove(uuid.getUuid());
                    changed=true;
                }
            }
            var transform=store.getComponent(ref,TransformComponent.getComponentType());
            if(transform!=null)particle(world,"SM_Anomaly_Scan",transform.getPosition());
        }
        return changed;
    }
    boolean tickAges(World world,Map<UUID,TemporalAge> ages,double dt) {
        boolean changed=false;var store=world.getEntityStore().getStore();
        var iterator=ages.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next();var age=entry.getValue();if(!age.world.equals(world.getName()))continue;
            var ref=world.getEntityStore().getRefFromUUID(entry.getKey());
            if(ref==null || !ref.isValid())continue;
            var npc=store.getComponent(ref,NPCEntity.getComponentType());if(npc==null || npc.getRole()==null)continue;
            String current=npc.getRoleName();
            if(current.equals(age.adultRole)) {iterator.remove();changed=true;continue;}
            // Preserve taming performed while young; don't turn a newly tamed calf back into a wild cow.
            var transition=TemporalRoles.transition(current);
            if(transition.isEmpty() || transition.get().becomingYoung()) {iterator.remove();changed=true;continue;}
            age.adultRole=transition.get().adult();age.juvenileRole=transition.get().juvenile();
            age.remainingSeconds-=dt;changed=true;
            if(age.remainingSeconds>0)continue;
            int role=NPCPlugin.get().getIndex(age.adultRole);
            if(role>=0)RoleChangeSystem.requestRoleChange(ref,npc.getRole(),role,true,store);
            iterator.remove();
        }
        return changed;
    }
    void effect(World world,Ref<EntityStore> ref,String id) {
        var store=world.getEntityStore().getStore(); var effect=EntityEffect.getAssetMap().getAsset(id);
        var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
        if(effect!=null && controller!=null) controller.addEffect(ref,effect,store);
    }
    void shadow(World world,AnomalyRecord a,Random random) {
        var store=world.getEntityStore().getStore();
        // Unloaded mobs count toward the cap; a vanished entity in its still-loaded last chunk can be replaced.
        a.shadowMobs.removeIf(id->{
            var ref=world.getEntityStore().getRefFromUUID(id);
            if(ref!=null && ref.isValid()) {
                var t=store.getComponent(ref,TransformComponent.getComponentType());
                if(t!=null)a.shadowMobPositions.put(id,new double[]{t.getPosition().x,t.getPosition().y,t.getPosition().z});
                return false;
            }
            var p=a.shadowMobPositions.get(id);
            boolean removed=ref!=null || (p!=null && world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock((int)Math.floor(p[0]),(int)Math.floor(p[2])))!=null);
            if(removed)a.shadowMobPositions.remove(id);return removed;
        });
        for(var ref:entities(world,a)) if(ref.isValid()) {
            if(store.getComponent(ref,Player.getComponentType())!=null) effect(world,ref,"SM_Shadow_Veil");
            else if(store.getComponent(ref,NPCEntity.getComponentType())!=null) {
                effect(world,ref,"SM_Shadow_Shelter");
                var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
                int burning=EntityEffect.getAssetMap().getIndex("Burn");
                if(controller!=null && burning>=0) controller.removeEffect(ref,burning,store);
            }
        }
        if(a.shadowMobs.size()>=6 || random.nextDouble()>=.25) return;
        for(int attempt=0;attempt<5;attempt++) {
            double angle=random.nextDouble()*Math.PI*2,r=2+random.nextDouble()*5;
            Vector3d safe=safeSurface(world,(int)(a.x+Math.cos(angle)*r),(int)(a.z+Math.sin(angle)*r));
            if(safe==null || a.distanceSquared(safe)>64) continue;
            var pair=NPCPlugin.get().spawnNPC(store,HOSTILES[random.nextInt(HOSTILES.length)],null,safe,new Rotation3f());
            if(pair!=null) {
                var uuid=store.getComponent(pair.first(),UUIDComponent.getComponentType());
                if(uuid!=null) {a.shadowMobs.add(uuid.getUuid());a.shadowMobPositions.put(uuid.getUuid(),new double[]{safe.x,safe.y,safe.z});}
                particle(world,"SM_Echoing_Shadow",safe); break;
            }
        }
    }
    void teleport(World world,AnomalyRecord source,AnomalyRecord destination) {
        var store=world.getEntityStore().getStore();
        for(var ref:entities(world,source)) {
            if(!ref.isValid() || teleports.containsKey(ref) || cores.containsValue(ref)) continue;
            var transform=store.getComponent(ref,TransformComponent.getComponentType()); if(transform==null) continue;
            var bounds=store.getComponent(ref,BoundingBox.getComponentType());if(bounds==null)continue;
            Vector3d safe=null;
            // Different bodies need different clearances; checking a player's two air blocks is insufficient for a cow or golem.
            for(int i=0;i<16 && safe==null;i++) {
                double angle=i*Math.PI/4,radius=i<8?4:6;
                var candidate=safeSurface(world,(int)Math.floor(destination.x+Math.cos(angle)*radius),(int)Math.floor(destination.z+Math.sin(angle)*radius));
                if(candidate==null)continue;
                candidate.y-=bounds.getBoundingBox().getMin().y;
                if(fitsAt(world,bounds,candidate))safe=candidate;
            }
            if(safe==null)continue;
            teleports.put(ref,System.currentTimeMillis()+5000);
            store.putComponent(ref,Teleport.getComponentType(),new Teleport(world,safe,transform.getRotation()));
            particle(world,"SM_Anomaly_Capture",source.position()); particle(world,"SM_Anomaly_Capture",safe);
        }
    }
    private boolean fitsAt(World world,BoundingBox bounds,Vector3d position) {
        var box=bounds.getBoundingBox();
        if(box.width()>16 || box.height()>16 || box.depth()>16)return false;
        int minX=(int)Math.floor(position.x+box.getMin().x),maxX=(int)Math.floor(position.x+box.getMax().x);
        int minY=(int)Math.floor(position.y+box.getMin().y),maxY=(int)Math.floor(position.y+box.getMax().y);
        int minZ=(int)Math.floor(position.z+box.getMin().z),maxZ=(int)Math.floor(position.z+box.getMax().z);
        if(minY<1 || maxY>=320)return false;
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++) {
            var chunk=world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z));if(chunk==null)return false;
            for(int y=minY;y<=maxY;y++) {
                if(chunk.getFluidId(x,y,z)!=0)return false;
                var block=chunk.getBlockType(x,y,z);if(block==null || block.getDamageToEntities()>0)return false;
            }
            var support=chunk.getBlockType(x,minY-1,z);if(support!=null && support.getDamageToEntities()>0)return false;
        }
        int result=CollisionModule.get().validatePosition(world,box,position,new CollisionResult());
        return result!=CollisionModule.VALIDATE_INVALID;
    }
    static Vector3d safeSurface(World world,int x,int z) {
        WorldChunk chunk=world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z));
        if(chunk==null) return null;
        int y=chunk.getHeight(x,z)+1;
        if(y<2 || y>315 || chunk.getBlock(x,y,z)!=0 || chunk.getBlock(x,y+1,z)!=0 || chunk.getFluidId(x,y,z)!=0 || chunk.getFluidId(x,y+1,z)!=0) return null;
        var ground=chunk.getBlockType(x,y-1,z);
        if(ground==null || ground.getId().contains("Water") || ground.getId().contains("Lava") || ground.getId().contains("Leaves")) return null;
        return new Vector3d(x+.5,y,z+.5);
    }
    boolean hasRod(World world,Vector3d center,double radius) {
        int r=(int)Math.ceil(radius);
        for(int dx=-r;dx<=r;dx++) for(int dz=-r;dz<=r;dz++) for(int dy=-r;dy<=r;dy++) {
            if(dx*dx+dy*dy+dz*dz>radius*radius) continue;
            int x=(int)Math.floor(center.x)+dx,y=(int)Math.floor(center.y)+dy,z=(int)Math.floor(center.z)+dz;
            if(y<0 || y>319) continue;
            var c=world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x,z)); if(c==null) continue;
            var b=c.getBlockType(x,y,z);
            // Stabilizers are evaluated by the enabled machine grounding hook. A physical
            // disabled stabilizer must not silently ground a rift just by existing nearby.
            if(b!=null && b.getId().contains("Lightning_Rod")) return true;
        }
        return false;
    }
    static String shardOre(AnomalyType type) {
        return switch(type) {
            case GRAVITY->"SM_Gravitic_Shard_Ore";case TEMPORAL_BLOOM->"SM_Chrono_Shard_Ore";
            case ENERGETIC_RIFT->"SM_Energetic_Shard_Ore";case WARP_GATE->"SM_Spatial_Shard_Ore";
            case ECHOING_SHADOW->"SM_Shade_Shard_Ore";case THOUGHTWELL->"SM_Insight_Shard_Ore";
        };
    }
    void terrainGenerated(WorldChunk chunk,AnomalyRecord a,Random random,AnomalyGenerationSettings settings) {
        if(!settings.terrainPatches)return;
        String shard=shardOre(a.type);
        int radius=a.type==AnomalyType.WARP_GATE?5:4,resonitePlaced=0,shardsPlaced=0;
        List<org.joml.Vector3i> columns=new ArrayList<>();
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++) {
            if(dx*dx+dz*dz>radius*radius)continue;
            int x=(int)Math.floor(a.x)+dx,z=(int)Math.floor(a.z)+dz;
            // Only newly generated pre-load chunks enter here. Never cross into an existing
            // adjacent chunk or retrofit terrain underneath player builds or released capsules.
            if(ChunkUtil.chunkCoordinate(x)!=chunk.getX() || ChunkUtil.chunkCoordinate(z)!=chunk.getZ())continue;
            int ground=chunk.getHeight(x,z);if(Math.abs(ground-a.y)>8)continue;
            columns.add(new org.joml.Vector3i(x,ground-1,z));
            // Sand, clay, gravel, mud, ash, snow and every other unshaped native soil family
            // participate, including buried soil. Only the raw freshly generated holder is edited.
            for(int y=ground;y>0;y--) {
                var soil=chunk.getBlockType(x,y,z);
                if(soil!=null&&AnomalyTerrain.soil(soil.getId()))
                    setGeneratedBlock(chunk,x,y,z,y==ground?"SM_Anomalous_Grass":"SM_Anomalous_Dirt");
            }
            if(random.nextDouble()<settings.resoniteColumnChance)resonitePlaced+=oreColumn(chunk,x,ground-1,z,"SM_Resonite_Ore",random);
            if(random.nextDouble()<settings.shardColumnChance)shardsPlaced+=oreColumn(chunk,x,ground-1,z,shard,random);
        }
        // Preserve the source density rolls, but each generated field with suitable geology
        // also guarantees its two advertised resources. An explicit zero chance still disables it.
        Collections.shuffle(columns,random);
        for(var c:columns) {
            if(resonitePlaced==0 && settings.resoniteColumnChance>0)resonitePlaced+=oreColumn(chunk,c.x,c.y,c.z,"SM_Resonite_Ore",random);
            if(shardsPlaced==0 && settings.shardColumnChance>0)shardsPlaced+=oreColumn(chunk,c.x,c.y,c.z,shard,random);
            if((resonitePlaced>0||settings.resoniteColumnChance<=0)&&(shardsPlaced>0||settings.shardColumnChance<=0))break;
        }
    }
    static boolean oreHost(String name) { return AnomalyTerrain.rock(name); }
    private int oreColumn(WorldChunk chunk,int x,int y,int z,String id,Random random) {
        if(BlockType.getAssetMap().getAsset(id)==null)return 0;
        int length=1+random.nextInt(3),placed=0;
        // The source walks to the world's bottom; a shallow arbitrary 24-block cap missed
        // the bedrock under Hytale's deep soil/sediment layers and cave ceilings.
        for(int blockY=y;blockY>0 && placed<length;blockY--) {
            var old=chunk.getBlockType(x,blockY,z);if(old==null)continue;
            if(!oreHost(old.getId())) {if(placed>0)break;continue;}
            setGeneratedBlock(chunk,x,blockY,z,id);placed++;
        }
        return placed;
    }
    private void setGeneratedBlock(WorldChunk chunk,int x,int y,int z,String id) {
        int blockId=BlockType.getAssetMap().getIndex(id);if(blockId<0)return;
        // Direct holder mutation is the worldgen path: no live ECS, drops, inventory, or player placement is touched.
        chunk.getBlockChunk().getSectionAtBlockY(y).set(x,y,z,blockId,0,0);
        chunk.markNeedsSaving();
    }
    void crops(World world,AnomalyRecord a,Random random) {
        // Crop mutation matches the original ±1..2 stages, five vertical levels, every five seconds.
        for(int dx=-8;dx<=8;dx++) for(int dz=-8;dz<=8;dz++) if(dx*dx+dz*dz<=64)
            for(int dy=-3;dy<=2;dy++) changeCrop(world,(int)Math.floor(a.x)+dx,(int)Math.floor(a.y)+dy,(int)Math.floor(a.z)+dz,(random.nextBoolean()?1:-1)*(1+random.nextInt(2)));
    }
    static boolean changeCrop(World world,int x,int y,int z,int delta) {
        if(y<0 || y>=320) return false;
        var chunkStore=world.getChunkStore().getStore();
        var sectionRef=world.getChunkStore().getChunkSectionReferenceAtBlock(x,y,z);
        if(sectionRef==null || !sectionRef.isValid()) return false;
        var section=chunkStore.getComponent(sectionRef,BlockSection.getComponentType());
        if(section==null) return false;
        BlockType block=BlockType.getAssetMap().getAsset(section.get(x,y,z));
        if(block==null || block.getFarming()==null || block.getFarming().getStages()==null) return false;
        var farm=block.getFarming(); var stages=farm.getStages().get(farm.getStartingStageSet());
        if(stages==null || stages.length==0) return false;
        var now=world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType()).getGameTime();
        int index=ChunkUtil.indexBlock(x,y,z);
        var blockRef=BlockModule.getBlockEntity(chunkStore,sectionRef,x,y,z);
        FarmingBlock f=blockRef==null?null:chunkStore.getComponent(blockRef,FarmingBlock.getComponentType());
        if(f==null) {
            f=new FarmingBlock(); f.setLastTickGameTime(now); f.setCurrentStageSet(farm.getStartingStageSet()); f.setGrowthProgress(Math.max(0,stages.length-2));
            if(blockRef==null) {
                var holder=com.hypixel.hytale.server.core.universe.world.storage.ChunkStore.REGISTRY.newHolder();
                holder.addComponent(BlockModule.BlockStateInfo.getComponentType(),new BlockModule.BlockStateInfo(index,sectionRef));
                holder.addComponent(FarmingBlock.getComponentType(),f); blockRef=chunkStore.addEntity(holder,AddReason.SPAWN);
            } else chunkStore.putComponent(blockRef,FarmingBlock.getComponentType(),f);
            f.setGrowthProgress(stages.length-1);
        }
        if(blockRef==null) return false;
        stages=farm.getStages().get(f.getCurrentStageSet()); if(stages==null || stages.length==0) return false;
        int old=Math.clamp((int)f.getGrowthProgress(),0,stages.length-1),next=Math.clamp(old+delta,0,stages.length-1);
        if(old==next) return false;
        f.setGrowthProgress(next);f.setExecutions(0);f.setGeneration(f.getGeneration()+1);f.setLastTickGameTime(now);
        var components=chunkStore.getComponent(sectionRef,BlockComponentSection.getComponentType()); if(components!=null) components.markBlockNeedsSaving(index);
        section.scheduleTick(index,now);
        stages[next].apply(chunkStore,sectionRef,blockRef,ChunkUtil.localCoordinate(x),ChunkUtil.localCoordinate(y),ChunkUtil.localCoordinate(z),stages[old]);
        section.setTicking(x,y,z,true); return true;
    }
}
