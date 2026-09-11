package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.anomaly.AnomalyType;
import com.hexvane.strangematter.effects.GadgetEffects;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.DespawnComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.modules.projectile.config.StandardPhysicsProvider;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Native swept projectiles create endpoints only after a block collision. */
final class WarpProjectiles {
    private static final System.Logger LOG=System.getLogger(WarpProjectiles.class.getName());
    private static final double RANGE=48, LIFETIME=3;
    private final AnomalyService anomalies;
    private final Map<String,Shot> shots=new ConcurrentHashMap<>();

    private static final class Shot {
        final String token=UUID.randomUUID().toString();
        final PlayerRef owner;
        final World world;
        final boolean purple;
        final Vector3d origin;
        Ref<EntityStore> entity;
        double age;
        Shot(PlayerRef owner,World world,boolean purple,Vector3d origin){
            this.owner=owner;this.world=world;this.purple=purple;this.origin=new Vector3d(origin);
        }
    }
    private record Gun(ItemContainer inventory,short slot,ItemStack stack){}

    WarpProjectiles(AnomalyService anomalies){this.anomalies=anomalies;}
    static String flightKey(boolean purple){return purple?"SMWarpFlightB":"SMWarpFlightA";}
    static String portalKey(boolean purple){return purple?"SMPortalB":"SMPortalA";}
    boolean inFlight(String token){return token!=null&&shots.containsKey(token);}

    boolean launch(PlayerRef owner,Store<EntityStore> store,Vector3d eye,Vector3d direction,
                   ItemContainer inventory,short slot,ItemStack gun,boolean purple){
        var ref=owner.getReference();
        if(ref==null||!ref.isValid()||ref.getStore()!=store||!eye.isFinite()||!direction.isFinite()||direction.lengthSquared()<.001)return false;
        if(slot<0||slot>=inventory.getCapacity()||!gun.equals(inventory.getItemStack(slot))||!"SM_Warp_Gun".equals(gun.getItemId()))return false;
        var config=ProjectileConfig.getAssetMap().getAsset(purple?"SM_Warp_Bolt_Purple":"SM_Warp_Bolt_Cyan");
        var player=store.getComponent(ref,Player.getComponentType());
        if(config==null||player==null||(player.getGameMode()!=GameMode.Creative&&gun.isBroken()))return false;
        var world=store.getExternalData().getWorld();
        var shot=new Shot(owner,world,purple,eye);
        var reserved=gun.withMetadata(flightKey(purple),Codec.STRING,shot.token);
        if(player.getGameMode()!=GameMode.Creative)reserved=reserved.withDurability(Math.max(0,reserved.getDurability()-1));
        if(!inventory.setItemStackForSlot(slot,reserved,false).succeeded())return false;
        try {
            // forEachChunk supplies a native command buffer and flushes it before returning.
            // Starting at the eye avoids skipping a nearby wall with a muzzle spawn offset.
            store.forEachChunk(PlayerRef.getComponentType(),(chunk,commands)->{
                for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(ref)){
                    shot.entity=ProjectileModule.get().spawnProjectile(ref,commands,config,new Vector3d(eye),new Vector3d(direction).normalize());
                    commands.putComponent(shot.entity,DespawnComponent.getComponentType(),DespawnComponent.despawnInSeconds(commands.getResource(TimeResource.getResourceType()),(float)LIFETIME));
                    commands.run(s->{
                        if(!shot.entity.isValid())return;
                        var physics=s.getComponent(shot.entity,StandardPhysicsProvider.getComponentType());
                        if(physics==null)return;
                        physics.setBounceConsumer(null);
                        physics.setImpactConsumer((projectile,position,block,target,detail,cb)->{
                            if(!shots.remove(shot.token,shot))return;
                            // Collision vectors belong to native physics and will be reused.
                            // Native physics may also report an entity behind a nearer block
                            // in this sweep. The recorded block contact takes precedence.
                            boolean surface=block!=null&&(target==null||physics.isBounced());
                            var impact=new Vector3d(surface?physics.getContactPosition():position);
                            cb.removeEntity(projectile,RemoveReason.REMOVE);
                            cb.run(impactStore->{
                                try {if(surface&&validImpact(shot,impact))openPortal(shot,impactStore,impact);}
                                catch(RuntimeException failure){LOG.log(System.Logger.Level.ERROR,"Warp bolt impact failed",failure);}
                                finally {clearReservation(shot,impactStore);}
                            });
                        });
                        shots.put(shot.token,shot);
                    });
                    return true;
                }
                return false;
            });
            if(!inFlight(shot.token))throw new IllegalStateException("Native warp projectile did not initialize");
        }catch(RuntimeException failure){
            shots.remove(shot.token);
            if(shot.entity!=null&&shot.entity.isValid())store.removeEntity(shot.entity,RemoveReason.REMOVE);
            if(reserved.equals(inventory.getItemStack(slot)))inventory.setItemStackForSlot(slot,gun,false);
            LOG.log(System.Logger.Level.ERROR,"Could not launch warp bolt",failure);
            return false;
        }
        // A newer shot replaces only this colour's pending intent, never the other endpoint.
        cancel(world,gun.getFromMetadataOrNull(flightKey(purple),Codec.STRING));
        GadgetEffects.use(world,purple?"SM_Warp_Muzzle_Purple":"SM_Warp_Muzzle_Cyan",EquipmentQueries.handheldOrigin(eye,direction));
        return true;
    }

    boolean clear(World world,ItemContainer inventory,short slot,ItemStack gun){
        if(slot<0||slot>=inventory.getCapacity()||!gun.equals(inventory.getItemStack(slot))||!"SM_Warp_Gun".equals(gun.getItemId()))return false;
        var updated=gun;
        for(boolean purple:new boolean[]{false,true})updated=updated.withMetadata(portalKey(purple),Codec.STRING,null).withMetadata(flightKey(purple),Codec.STRING,null);
        if(!inventory.setItemStackForSlot(slot,updated,false).succeeded())return false;
        for(boolean purple:new boolean[]{false,true}){
            cancel(world,gun.getFromMetadataOrNull(flightKey(purple),Codec.STRING));
            removePortal(gun.getFromMetadataOrNull(portalKey(purple),Codec.STRING));
        }
        anomalies.save();
        return true;
    }

    private boolean validImpact(Shot shot,Vector3d impact){
        return impact.isFinite()&&shot.age<LIFETIME&&shot.origin.distanceSquared(impact)<=RANGE*RANGE
            &&loaded(shot.world,impact)&&loaded(shot.world,new Vector3d(impact).add(0,1,0));
    }
    private static boolean loaded(World world,Vector3d point){
        return point.y>=0&&point.y<ChunkUtil.HEIGHT&&world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock((int)Math.floor(point.x),(int)Math.floor(point.z)))!=null;
    }
    private void openPortal(Shot shot,Store<EntityStore> store,Vector3d impact){
        var found=findGun(shot,store);if(found==null)return;
        var gate=anomalies.spawn(AnomalyType.WARP_GATE,shot.world,new Vector3d(impact).add(0,1,0),false);
        boolean committed=false;
        try {
            anomalies.setPortalChannel(gate.id,shot.purple?2:1);
            var updated=found.stack.withMetadata(portalKey(shot.purple),Codec.STRING,gate.id.toString()).withMetadata(flightKey(shot.purple),Codec.STRING,null);
            if(!found.stack.equals(found.inventory.getItemStack(found.slot))||!found.inventory.setItemStackForSlot(found.slot,updated,false).succeeded())return;
            committed=true;
            removePortal(found.stack.getFromMetadataOrNull(portalKey(shot.purple),Codec.STRING));
            String other=found.stack.getFromMetadataOrNull(portalKey(!shot.purple),Codec.STRING);
            if(other!=null)try{anomalies.pair(gate.id,UUID.fromString(other));}catch(IllegalArgumentException ignored){}
            anomalies.save();
            GadgetEffects.use(shot.world,shot.purple?"SM_Warp_Impact_Purple":"SM_Warp_Impact_Cyan",gate.position());
        }finally{if(!committed){anomalies.remove(gate.id);anomalies.save();}}
    }
    private Gun findGun(Shot shot,Store<EntityStore> store){
        var owner=shot.owner.getReference();
        if(owner==null||!owner.isValid()||owner.getStore()!=store||store.getExternalData().getWorld()!=shot.world)return null;
        Gun found=null;
        // Resolve live inventory at impact, so changing the selected slot does not lose the shot.
        for(int index=0;index<=InventoryComponent.EVERYTHING.length;index++){
            var type=index==InventoryComponent.EVERYTHING.length?InventoryComponent.Tool.getComponentType():InventoryComponent.EVERYTHING[index];
            var section=store.getComponent(owner,type);if(section==null)continue;
            var inventory=section.getInventory();
            for(short slot=0;slot<inventory.getCapacity();slot++){
                var stack=inventory.getItemStack(slot);
                if(stack==null||!"SM_Warp_Gun".equals(stack.getItemId())||!shot.token.equals(stack.getFromMetadataOrNull(flightKey(shot.purple),Codec.STRING)))continue;
                if(found!=null)return null; // Duplicated metadata must not select an arbitrary gun.
                found=new Gun(inventory,slot,stack);
            }
        }
        return found;
    }
    private void clearReservation(Shot shot,Store<EntityStore> store){
        var found=findGun(shot,store);
        if(found!=null)found.inventory.setItemStackForSlot(found.slot,found.stack.withMetadata(flightKey(shot.purple),Codec.STRING,null),false);
    }
    private void removePortal(String id){
        if(id==null)return;
        try {
            UUID key=UUID.fromString(id);var gate=anomalies.get(key).orElse(null);if(gate==null)return;
            var world=Universe.get().getWorld(gate.world);
            if(world!=null&&!world.isInThread())world.execute(()->{anomalies.remove(key);anomalies.save();});
            else anomalies.remove(key);
        }catch(IllegalArgumentException ignored){}
    }
    private void cancel(World world,String token){
        if(token==null)return;
        var shot=shots.get(token);if(shot==null||shot.world!=world||!shots.remove(token,shot))return;
        var store=world.getEntityStore().getStore();
        if(shot.entity!=null&&shot.entity.isValid())store.removeEntity(shot.entity,RemoveReason.REMOVE);
        clearReservation(shot,store);
    }
    void tick(World world,double dt){
        if(!Double.isFinite(dt)||dt<=0)return;
        var store=world.getEntityStore().getStore();
        for(var shot:new ArrayList<>(shots.values()))if(shot.world==world){
            shot.age+=dt;
            var transform=shot.entity==null||!shot.entity.isValid()?null:store.getComponent(shot.entity,TransformComponent.getComponentType());
            if(shot.age>=LIFETIME||transform==null||!loaded(world,transform.getPosition())||shot.origin.distanceSquared(transform.getPosition())>RANGE*RANGE||findGun(shot,store)==null)cancel(world,shot.token);
        }
    }
    void cleanup(World world){for(var shot:new ArrayList<>(shots.values()))if(shot.world==world)cancel(world,shot.token);}
}
