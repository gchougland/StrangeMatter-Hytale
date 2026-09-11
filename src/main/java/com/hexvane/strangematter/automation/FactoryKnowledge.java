package com.hexvane.strangematter.automation;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Asynchronous read-only owner knowledge; no fake player and no write of an offline holder. */
final class FactoryKnowledge {
    private record Snapshot(Set<String> known,Object session,long expires){}
    private final Map<UUID,Snapshot> snapshots=new ConcurrentHashMap<>();
    private final Map<UUID,Object> loading=new ConcurrentHashMap<>();
    private volatile boolean closed;
    Set<String> get(UUID owner,World machineWorld){
        var universe=Universe.get();if(universe==null)return null;
        var live=universe.getPlayer(owner);var ref=live==null?null:live.getReference();long now=System.nanoTime();
        Object session=live;
        if(ref!=null&&ref.isValid()&&ref.getStore().getExternalData().getWorld()==machineWorld){var player=ref.getStore().getComponent(ref,Player.getComponentType());if(player!=null){var known=Set.copyOf(player.getPlayerConfigData().getKnownRecipes());snapshots.put(owner,new Snapshot(known,session,now+1_000_000_000L));return known;}}
        var cached=snapshots.get(owner);if(cached!=null&&cached.session==session&&cached.expires>now)return cached.known;
        if(loading.containsKey(owner))return null;
        Object request=new Object();loading.put(owner,request);
        if(ref!=null&&ref.isValid()){
            var world=ref.getStore().getExternalData().getWorld();try{world.execute(()->{Set<String> known=null;if(ref.isValid()&&universe.getPlayer(owner)==live){var player=ref.getStore().getComponent(ref,Player.getComponentType());if(player!=null)known=Set.copyOf(player.getPlayerConfigData().getKnownRecipes());}finish(owner,request,session,known,1_000_000_000L);});}catch(RuntimeException stopped){loading.remove(owner,request);}
        } else universe.getPlayerStorage().load(owner).whenComplete((holder,error)->{Set<String> known=null;if(error==null&&holder!=null&&universe.getPlayer(owner)==null){var player=holder.getComponent(Player.getComponentType());if(player!=null)known=Set.copyOf(player.getPlayerConfigData().getKnownRecipes());}finish(owner,request,null,known,5_000_000_000L);});
        return null;
    }
    // A missing or failed read still denies authorization, but shares the normal short
    // cache lifetime so an offline owner does not cause another disk read every tick.
    private void finish(UUID owner,Object request,Object session,Set<String> known,long ttl){if(loading.remove(owner,request)&&!closed&&Universe.get().getPlayer(owner)==session)snapshots.put(owner,new Snapshot(known,session,System.nanoTime()+ttl));}
    void invalidate(UUID owner){snapshots.remove(owner);loading.remove(owner);}
    void close(){closed=true;snapshots.clear();loading.clear();}
}
