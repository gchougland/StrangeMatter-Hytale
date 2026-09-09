package com.hexvane.strangematter.progression;

import com.google.gson.Gson;
import com.hexvane.strangematter.research.ResearchNode;
import com.hexvane.strangematter.research.ResearchType;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.util.NotificationUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Original advancement criteria, including their actual AND/OR requirement groups. No currency rewards. */
public final class ProgressionService {
    public record Criterion(String trigger, Set<String> values) {}
    public record Definition(String id,String parent,String title,String description,String icon,
                             boolean toast,boolean chat,Map<String,Criterion> criteria,List<List<String>> requirements) {}
    public record Milestone(String id,String parent,String title,String description,String icon,
                            boolean complete,int criteriaMet,int criteriaTotal) {}
    private final List<Definition> definitions;
    private final Path file;
    private final Properties ledger=new Properties();
    private final Map<UUID,List<Definition>> pending=new HashMap<>();
    private final Map<String,Double> elapsed=new HashMap<>();
    private boolean dirty;

    public ProgressionService(Path dataDirectory) {
        file=dataDirectory.resolve("advancements.properties");
        try(InputStream input=getClass().getResourceAsStream("/Server/StrangeMatter/Progression/advancements.json")) {
            if(input==null)throw new IOException("Missing advancement catalogue");
            definitions=List.of(new Gson().fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),Definition[].class));
        }catch(IOException e){throw new UncheckedIOException(e);}
        if(Files.exists(file))try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){ledger.load(reader);}
        catch(IOException e){throw new UncheckedIOException("Cannot load original advancement progress",e);}
    }
    /** Called after the normal first-survival-join tablet grant; this service does not issue another tablet. */
    public synchronized void joined(UUID player){event(player,"joined","");}
    public synchronized void firstContact(UUID player){event(player,"anomaly_effect_applied","");}
    public synchronized void scanned(UUID player,ResearchType type){if(type!=null)event(player,"scan_anomaly",type.getName());}
    public synchronized void completedResearch(UUID player,ResearchNode node) {
        if(node!=null)for(ResearchType type:node.costs().keySet())event(player,"complete_research_category",type.getName());
    }
    public synchronized void inventoryChanged(UUID player,Collection<String> itemIds) {
        for(String id:itemIds)event(player,"inventory_changed",id);
    }
    public synchronized List<Milestone> snapshot(UUID player) {
        return definitions.stream().map(d->new Milestone(d.id,d.parent,d.title,d.description,d.icon,
            complete(player,d.id),(int)d.criteria.keySet().stream().filter(c->met(player,d.id,c)).count(),d.criteria.size())).toList();
    }
    public synchronized boolean complete(UUID player,String id){return ledger.containsKey(player+".done."+id);}
    public void open(com.hypixel.hytale.server.core.universe.PlayerRef player,
                     com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store) {
        var ref=player.getReference();if(ref==null||!ref.isValid())return;
        var entity=store.getComponent(ref,Player.getComponentType());
        if(entity!=null)entity.getPageManager().openCustomPage(ref,store,new ProgressionPage(player,this));
    }
    private boolean met(UUID player,String id,String criterion){return ledger.containsKey(player+".criterion."+id+"."+criterion);}
    private void event(UUID player,String trigger,String value) {
        for(Definition d:definitions) {
            for(var entry:d.criteria.entrySet()) {
                Criterion c=entry.getValue();
                if(c.trigger.equals(trigger)&&(c.values.isEmpty()||c.values.contains(value))) {
                    String key=player+".criterion."+d.id+"."+entry.getKey();
                    if(!ledger.containsKey(key)){ledger.setProperty(key,"true");dirty=true;}
                }
            }
            if(!complete(player,d.id)&&d.requirements.stream().allMatch(group->group.stream().anyMatch(c->met(player,d.id,c)))) {
                ledger.setProperty(player+".done."+d.id,Long.toString(System.currentTimeMillis()));dirty=true;
                if(d.toast||d.chat)pending.computeIfAbsent(player,k->new ArrayList<>()).add(d);
            }
        }
        save();
    }
    /** Run on each world's thread. Inventory criteria include receiving an item, just as in the original. */
    public synchronized void tick(World world,double dt) {
        double time=elapsed.getOrDefault(world.getName(),0d)+dt;
        if(time<1){elapsed.put(world.getName(),time);return;}elapsed.put(world.getName(),0d);
        var store=world.getEntityStore().getStore();
        for(var player:world.getPlayerRefs()) {
            var ref=player.getReference();if(ref==null||!ref.isValid())continue;
            var entity=store.getComponent(ref,Player.getComponentType());if(entity==null)continue;
            if(entity.getGameMode()==GameMode.Adventure)joined(player.getUuid());
            var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_FIRST);
            Set<String> ids=new HashSet<>();
            if(inventory!=null)for(short s=0;s<inventory.getCapacity();s++) {
                var item=inventory.getItemStack(s);if(item!=null&&!item.isEmpty())ids.add(item.getItemId());
            }
            inventoryChanged(player.getUuid(),ids);
            var notices=pending.remove(player.getUuid());if(notices==null)continue;
            for(Definition d:notices) {
                if(d.toast)NotificationUtil.sendNotification(player.getPacketHandler(),"Strange Matter: "+d.title,d.description);
                if(d.chat)for(var recipient:world.getPlayerRefs())recipient.sendMessage(Message.raw(player.getUsername()+" has made the advancement ["+d.title+"]"));
            }
        }
    }
    public synchronized void save() {
        if(!dirty)return;
        try {
            Files.createDirectories(file.getParent());Path next=file.resolveSibling(file.getFileName()+".tmp");
            try(Writer writer=Files.newBufferedWriter(next,StandardCharsets.UTF_8)){ledger.store(writer,"Strange Matter original advancement criteria");}
            try{Files.move(next,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(next,file,StandardCopyOption.REPLACE_EXISTING);}dirty=false;
        }catch(IOException e){throw new UncheckedIOException("Cannot save advancement progress",e);}
    }
}
