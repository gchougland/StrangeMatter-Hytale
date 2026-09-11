package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Finite field contributions let one suppressed source release only the effects it owns. */
final class AnomalyEffectLeases {
    private record Key(Ref<EntityStore> target,int effect){}
    private static final class Lease {
        ActiveEntityEffect active;double external;
        final Map<UUID,Double> sources=new HashMap<>();
    }
    private final Map<Key,Lease> leases=new HashMap<>();
    boolean apply(World world,UUID source,Ref<EntityStore> target,String id,float duration){
        var store=world.getEntityStore().getStore();var effect=EntityEffect.getAssetMap().getAsset(id);
        var controller=store.getComponent(target,EffectControllerComponent.getComponentType());
        if(effect==null||controller==null)return false;
        int index=EntityEffect.getAssetMap().getIndex(id);var key=new Key(target,index);var lease=leases.get(key);
        var before=controller.getActiveEffects().get(index);
        if(lease==null||lease.active!=before){
            lease=new Lease();
            // A preexisting application from another system is not ours to cleanse.
            if(before!=null)lease.external=before.isInfinite()?Double.POSITIVE_INFINITY:Math.max(0,before.getRemainingDuration());
        }
        if(!controller.addEffect(target,effect,duration,OverlapBehavior.OVERWRITE,store))return false;
        lease.active=controller.getActiveEffects().get(index);lease.sources.put(source,(double)duration);leases.put(key,lease);return true;
    }
    void tick(World world,double dt){
        var store=world.getEntityStore().getStore();
        leases.entrySet().removeIf(entry->{
            var ref=entry.getKey().target;if(!ref.isValid())return true;if(ref.getStore()!=store)return false;
            var lease=entry.getValue();lease.external-=dt;
            lease.sources.replaceAll((source,remaining)->remaining-dt);lease.sources.values().removeIf(remaining->remaining<=0);
            var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
            var active=controller==null?null:controller.getActiveEffects().get(entry.getKey().effect);
            return lease.sources.isEmpty()||active==null||active!=lease.active||(!active.isInfinite()&&active.getRemainingDuration()<=0);
        });
    }
    Set<Ref<EntityStore>> removeSource(World world,UUID source){
        var store=world.getEntityStore().getStore();var clearedThoughts=new HashSet<Ref<EntityStore>>();
        int cognition=EntityEffect.getAssetMap().getIndex("SM_Cognitive_Dissonance");
        var iterator=leases.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();var key=entry.getKey();var ref=key.target;var lease=entry.getValue();
            if(!ref.isValid()){iterator.remove();continue;}if(ref.getStore()!=store||lease.sources.remove(source)==null)continue;
            var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
            var active=controller==null?null:controller.getActiveEffects().get(key.effect);
            if(lease.sources.isEmpty()){
                if(key.effect==cognition)clearedThoughts.add(ref);
                if(active!=null&&active==lease.active&&!active.isInfinite()){
                    if(lease.external<=0)controller.removeEffect(ref,key.effect,RemovalBehavior.COMPLETE,store);
                    else controller.addEffect(ref,EntityEffect.getAssetMap().getAsset(key.effect),(float)lease.external,OverlapBehavior.OVERWRITE,store);
                }
                iterator.remove();
            }else if(active!=null&&active==lease.active&&!active.isInfinite()){
                double remaining=Math.max(lease.external,Collections.max(lease.sources.values()));
                controller.addEffect(ref,EntityEffect.getAssetMap().getAsset(key.effect),(float)remaining,OverlapBehavior.OVERWRITE,store);
                lease.active=controller.getActiveEffects().get(key.effect);
            }
        }
        return clearedThoughts;
    }
    void clear(World world){
        var store=world.getEntityStore().getStore();var sources=new HashSet<UUID>();
        for(var entry:leases.entrySet())if(entry.getKey().target.isValid()&&entry.getKey().target.getStore()==store)sources.addAll(entry.getValue().sources.keySet());
        for(var source:sources)removeSource(world,source);
        leases.keySet().removeIf(key->!key.target.isValid()||key.target.getStore()==store);
    }
}
