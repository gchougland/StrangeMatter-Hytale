package com.hexvane.strangematter.machine;

import com.google.gson.GsonBuilder;
import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3i;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Persistent laboratory machines. All inventory and world operations run on their world thread. */
public final class MachineService implements AutoCloseable {
    public static final Set<String> IDS=Set.of("SM_Research_Machine","SM_Resonant_Burner","SM_Resonance_Condenser","SM_Reality_Forge","SM_Paradoxical_Energy_Cell","SM_Resonant_Conduit","SM_Rift_Stabilizer","SM_Stasis_Projector","SM_Levitation_Pad","SM_Time_Dilation_Block","SM_Anomaly_Nullifier");
    private final Map<String,MachineState> machines=new LinkedHashMap<>();
    private final Set<String> welcomed=new HashSet<>();
    private final Set<String> spentCapsules=new HashSet<>();
    private final Map<String,Double> accumulated=new HashMap<>();
    private final Map<String,Integer> worldTicks=new HashMap<>();
    private final Map<String,List<ResonantNetwork.Route>> routes=new HashMap<>();
    private final Path file;
    public final StrangeMatterConfig config;
    public final ResearchService research;
    public final AnomalyService anomalies;
    public final List<ForgeRecipe> recipes;
    private boolean dirty;
    private record GroundingSite(String world,int x,int y,int z) {}
    private volatile List<GroundingSite> groundingSites=List.of();
    private volatile List<GroundingSite> nullifierSites=List.of();
    private Predicate<String> capsuleReserved=token->false;
    private static final class Save { List<MachineState> machines=new ArrayList<>();Set<String> welcomed=new HashSet<>(),spentCapsules=new HashSet<>(); }

    public MachineService(Path directory,StrangeMatterConfig config,ResearchService research,AnomalyService anomalies)throws IOException {
        this.file=directory.resolve("machines.json");this.config=config;this.research=research;this.anomalies=anomalies;this.recipes=ForgeRecipe.load();
        if(Files.exists(file)) {
            var data=new GsonBuilder().create().fromJson(Files.readString(file),Save.class);
            if(data==null||data.machines==null)throw new IOException("Invalid machine save");
            for(var state:data.machines)if(state!=null&&IDS.contains(state.id)){
                // Conduits are passive. Old GUI power switches must not leave inaccessible disabled wires.
                if(state.id.equals("SM_Resonant_Conduit"))state.enabled=true;
                var saved=state.selectedRecipes;state.selectedRecipes=new LinkedHashMap<>();
                if(saved!=null&&state.id.equals("SM_Reality_Forge"))for(var selection:saved.entrySet()){
                    if(selection.getValue()==null||recipes.stream().noneMatch(r->r.id.equals(selection.getValue())))continue;
                    try{state.rememberRecipe(UUID.fromString(selection.getKey()),selection.getValue());}catch(IllegalArgumentException ignored){}
                }
                if(state.fuelQueue==null)state.fuelQueue=new ArrayList<>();
                if(state.recoveredFuel==null)state.recoveredFuel=new ArrayList<>();
                machines.put(state.key(),state);
            }
            if(data.welcomed!=null)welcomed.addAll(data.welcomed);
            if(data.spentCapsules!=null)spentCapsules.addAll(data.spentCapsules);
            for(String token:spentCapsules)anomalies.consumeCapsule(token);
        }
        refreshGroundingSites();
    }
    private void refreshGroundingSites(){
        groundingSites=machines.values().stream().filter(m->m.enabled&&m.id.equals("SM_Rift_Stabilizer")).map(m->new GroundingSite(m.world,m.x,m.y,m.z)).toList();
        nullifierSites=machines.values().stream().filter(m->m.enabled&&m.id.equals("SM_Anomaly_Nullifier")).map(m->new GroundingSite(m.world,m.x,m.y,m.z)).toList();
    }
    /** Called under the anomaly monitor: immutable sites avoid reversing the machine/anomaly lock order. */
    public boolean suppressed(World world,org.joml.Vector3d position,AnomalyType type){
        if(!world.isInThread()||position==null||!position.isFinite())return false;
        double radius=config.nullifierRadius;
        for(var site:nullifierSites){
            if(!site.world.equals(world.getName())||position.distanceSquared(site.x+.5,site.y+.5,site.z+.5)>radius*radius)continue;
            // A saved registry entry alone is insufficient: unloaded or replaced blocks cannot suppress anything.
            var chunk=world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(site.x,site.z));
            if(chunk!=null&&"SM_Anomaly_Nullifier".equals(baseId(chunk.getBlockType(site.x,site.y,site.z))))return true;
        }
        return false;
    }
    /** Anomaly callbacks run under the anomaly lock: never acquire the machine lock here. */
    public boolean grounded(World world,org.joml.Vector3d position,double radius){
        for(var site:groundingSites){
            if(!site.world.equals(world.getName())||position.distanceSquared(site.x+.5,site.y+.5,site.z+.5)>radius*radius)continue;
            if(world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(site.x,site.z))!=null&&"SM_Rift_Stabilizer".equals(baseId(world.getBlockType(site.x,site.y,site.z))))return true;
        }
        return false;
    }
    public synchronized MachineState get(World world,Vector3i pos){return machines.get(MachineState.key(world.getName(),pos.x,pos.y,pos.z));}
    public Path dataDirectory(){return file.getParent();}
    public void setCapsuleReservationCheck(Predicate<String> check){capsuleReserved=Objects.requireNonNull(check);}
    public synchronized List<MachineState> inWorld(World world){return machines.values().stream().filter(m->m.world.equals(world.getName())).toList();}
    public static String baseId(BlockType type) {
        if(type==null)return "";
        if(IDS.contains(type.getId()))return type.getId();
        String base=type.getDefaultStateKey();return base==null?type.getId():base;
    }
    public synchronized MachineState register(World world,Vector3i pos,String id) {
        if(!IDS.contains(id))id=baseId(BlockType.getAssetMap().getAsset(id));
        if(!IDS.contains(id))return null;
        String key=MachineState.key(world.getName(),pos.x,pos.y,pos.z);
        var current=machines.get(key);
        if(current!=null&&current.id.equals(id)){
            if(id.equals("SM_Resonant_Conduit")&&!current.enabled){current.enabled=true;dirty=true;routes.clear();}
            return current;
        }
        var state=new MachineState(world.getName(),pos,id);machines.put(key,state);refreshGroundingSites();dirty=true;routes.clear();refreshConnections(world,pos);return state;
    }
    public synchronized boolean valid(World world,MachineState state) {
        if(!state.world.equals(world.getName())||machines.get(state.key())!=state)return false;
        var chunk=world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(state.x,state.z));
        if(chunk==null)return false;
        var type=world.getBlockType(state.x,state.y,state.z);
        return type!=null&&state.id.equals(baseId(type));
    }
    public boolean canUse(Store<EntityStore> store,PlayerRef player,MachineState state) {
        var ref=player.getReference();if(ref==null||!ref.isValid()||!valid(store.getExternalData().getWorld(),state))return false;
        var t=store.getComponent(ref,com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        return t!=null&&t.getPosition().distanceSquared(state.center())<=64;
    }
    public void open(PlayerRef player,Store<EntityStore> store,Vector3i pos) {
        var world=store.getExternalData().getWorld();var type=world.getBlockType(pos.x,pos.y,pos.z);
        if(type==null)return;var state=register(world,pos,baseId(type));if(state==null||!canUse(store,player,state))return;
        if(state.id.equals("SM_Resonant_Conduit"))return;
        if(state.id.equals("SM_Stasis_Projector")||state.id.equals("SM_Anomaly_Nullifier")){
            toggle(state);save();
            com.hexvane.strangematter.effects.GadgetEffects.sound(world,state.enabled?"SM_Stasis_Projector_On_SFX":"SM_Stasis_Projector_Off_SFX",state.center());
            if(!state.enabled){state.active=false;MachineWorkEffects.stop(world,state);}
            return;
        }
        if(state.id.equals("SM_Research_Machine")){research.openMachine(player,store,pos);return;}
        var ref=player.getReference();var entity=store.getComponent(ref,Player.getComponentType());
        if(entity!=null)entity.getPageManager().openCustomPage(ref,store,new MachinePage(player,this,state));
    }
    public synchronized void removed(World world,Vector3i pos) {machines.remove(MachineState.key(world.getName(),pos.x,pos.y,pos.z));refreshGroundingSites();dirty=true;routes.clear();refreshConnections(world,pos);}
    private static final int[][] FACES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    // Ports are physical: toggling a machine's power does not remove its cable socket.
    private static final Set<String> NETWORK_PORTS=Set.of("SM_Resonant_Conduit","SM_Resonant_Burner","SM_Rift_Stabilizer","SM_Paradoxical_Energy_Cell","SM_Resonance_Condenser");
    public static int connectionMask(java.util.function.IntPredicate connected) {
        int mask=0;for(int i=0;i<FACES.length;i++)if(connected.test(i))mask|=1<<i;return mask;
    }
    private void refreshConnections(World world,Vector3i changed) {
        updateConduit(world,changed.x,changed.y,changed.z);
        for(var face:FACES)updateConduit(world,changed.x+face[0],changed.y+face[1],changed.z+face[2]);
    }
    private void updateConduit(World world,int x,int y,int z) {
        var chunk=world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x,z));if(chunk==null)return;
        var type=world.getBlockType(x,y,z);if(!"SM_Resonant_Conduit".equals(baseId(type)))return;
        int mask=connectionMask(i->{var d=FACES[i];int nx=x+d[0],ny=y+d[1],nz=z+d[2];
            return world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(nx,nz))!=null
                && NETWORK_PORTS.contains(baseId(world.getBlockType(nx,ny,nz)));});
        String state=String.format(java.util.Locale.ROOT,"Connection%02d",mask);
        if(!state.equals(type.getCurrentInteractionState())||chunk.getRotationIndex(x,y,z)!=0) {
            var shape=type.getBlockForState(state);
            if(shape!=null) {
                // Arms are world-axis masks; clear old item yaw rather than rotate connections away from their neighbors.
                chunk.setBlock(x,y,z,BlockType.getAssetMap().getIndex(shape.getId()),shape,0,0,
                    NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            }
        }
    }
    public synchronized void toggle(MachineState m){
        if(m.id.equals("SM_Resonant_Conduit"))m.enabled=true;
        else if(m.id.equals("SM_Levitation_Pad"))m.ascending=!m.ascending;
        else m.enabled=!m.enabled;
        refreshGroundingSites();dirty=true;routes.clear();
    }
    public synchronized int selectedRecipeIndex(MachineState state,UUID player){
        String selected=state.selectedRecipe(player);
        for(int i=0;i<recipes.size();i++)if(recipes.get(i).id.equals(selected))return i;
        return 0;
    }
    public synchronized void selectRecipe(MachineState state,UUID player,String recipeId){
        if(!state.id.equals("SM_Reality_Forge")||recipes.stream().noneMatch(r->r.id.equals(recipeId)))return;
        state.rememberRecipe(player,recipeId);dirty=true;
    }
    public synchronized String fuel(Player player,MachineState state) {
        return fuel(inventory(player),state,false);
    }
    public synchronized String fuelMax(Player player,MachineState state) {
        return fuel(inventory(player),state,true);
    }
    public synchronized String fuel(ItemContainer inventory,MachineState state,boolean loadMax) {
        if(!state.id.equals("SM_Resonant_Burner"))return "This machine does not burn fuel.";
        recoverInvalidFuel(state);
        int remaining=FurnaceFuel.remainingCapacity(state);
        if(remaining==0)return "Fuel capacity reached: 26m 40s, including the fuel currently burning.";
        var charges=FurnaceFuel.takeUpTo(inventory,remaining,loadMax?Integer.MAX_VALUE:1,taken->{
            // All allocations and metadata serialization finish before native slots are replaced.
            var queue=new ArrayList<>(state.fuelQueue);int added=0;
            for(var charge:taken){queue.add(MachineState.FuelCharge.from(charge.stack(),charge.ticks()));added+=charge.ticks();}
            state.fuelQueue=queue;state.queuedFuelTicks+=added;dirty=true;
        });
        if(charges.isEmpty())return "No whole furnace fuel item fits. Carry charcoal, logs, or another item accepted by a furnace.";
        int added=charges.stream().mapToInt(FurnaceFuel.Charge::ticks).sum();
        return "Loaded "+charges.size()+" fuel item"+(charges.size()==1?"":"s")+" ("+(added/20)+" seconds). "+(FurnaceFuel.storedTicks(state)/20)+" / 1600 seconds stored.";
    }
    /** Preserve still-queued items eaten by the pre-0.3 fuel-quality check. Run after assets load. */
    public synchronized void recoverInvalidFuel(MachineState state) {
        if(!state.id.equals("SM_Resonant_Burner"))return;
        for(var it=state.fuelQueue.iterator();it.hasNext();){
            var charge=it.next();
            if(FurnaceFuel.ticks(charge.toItemStack())>0)continue;
            state.recoveredFuel.add(charge);state.queuedFuelTicks=Math.max(0,state.queuedFuelTicks-charge.ticks());it.remove();dirty=true;
        }
    }
    public synchronized String collect(Player player,MachineState state) {
        recoverInvalidFuel(state);
        if(!state.recoveredFuel.isEmpty()){
            var item=state.recoveredFuel.getFirst().toItemStack();
            if(!InventoryOps.give(inventory(player),item))return "Make room in your inventory.";
            state.recoveredFuel.removeFirst();dirty=true;return "Recovered "+InventoryOps.label(item.getItemId())+" from the old fuel queue.";
        }
        if(state.outputQuantity<=0)return "The output tray is empty.";
        int max=new ItemStack(state.output,1).getItem().getMaxStack();
        int amount=Math.min(state.outputQuantity,Math.max(1,max));
        if(!InventoryOps.give(inventory(player),new ItemStack(state.output,amount)))return "Make room in your inventory.";
        state.outputQuantity-=amount;if(state.outputQuantity==0)state.output="";dirty=true;
        return "Collected "+amount+" item"+(amount==1?".":"s.");
    }
    public synchronized String craft(PlayerRef player,Player entity,MachineState state,String recipeId) {
        if(!state.id.equals("SM_Reality_Forge"))return "A Reality Forge is required.";
        if(!state.recipe.isEmpty()||state.outputQuantity>0)return "Collect the previous output first.";
        var recipe=recipes.stream().filter(r->r.id.equals(recipeId)).findFirst().orElse(null);
        if(recipe==null)return "Unknown recipe.";
        var readiness=readiness(player.getUuid(),entity,recipe);
        if(!readiness.ready())return readiness.message();
        var inv=inventory(entity);
        var reserved=InventoryOps.take(inv,recipe.totalCost(),stack->availableIngredient(stack,entity.getGameMode()==com.hypixel.hytale.protocol.GameMode.Creative));
        if(reserved==null)return "Inventory changed. "+readiness(player.getUuid(),entity,recipe).message();
        state.reservedInputs=new ArrayList<>(reserved.stream().map(MachineState.ReservedInput::from).toList());
        state.recipe=recipe.id;state.progress=0;state.owner=player.getUuid().toString();dirty=true;
        return "Coalescing "+InventoryOps.label(recipe.output)+".";
    }
    public record Material(String id,String icon,String name,int required,int available) {
        public int missing(){return Math.max(0,required-available);}
    }
    public record Readiness(String researchId,String researchName,boolean researched,List<Material> materials) {
        public boolean ready(){return researched&&materials.stream().allMatch(m->m.missing()==0);}
        public String message(){
            var missing=new ArrayList<String>();
            if(!researched)missing.add("Research: "+researchName);
            for(var material:materials)if(material.missing()>0)missing.add(material.missing()+" x "+material.name+" (have "+material.available+" / need "+material.required+")");
            return missing.isEmpty()?"All requirements met. Ready to coalesce.":"Missing: "+String.join("; ",missing)+".";
        }
    }
    public synchronized Readiness readiness(UUID player,Player entity,ForgeRecipe recipe) {
        return readiness(player,inventory(entity),recipe,entity.getGameMode()==com.hypixel.hytale.protocol.GameMode.Creative);
    }
    public synchronized boolean knowsRecipe(UUID player,ForgeRecipe recipe){return research.hasUnlocked(player,recipeResearch(recipe));}
    private String recipeResearch(ForgeRecipe recipe){String node=research.requiredResearchForItem(recipe.output);return node==null?recipe.research:node;}
    public synchronized Readiness readiness(UUID player,ItemContainer inventory,ForgeRecipe recipe,boolean creative) {
        String node=recipeResearch(recipe);
        var materials=new ArrayList<Material>();
        for(var cost:recipe.totalCost().entrySet()){
            String id=cost.getKey(),icon=id;
            if(id.startsWith("resource:")){
                icon=id.equals("resource:Wood_Planks")?"Wood_Softwood_Planks":"SM_Gravitic_Shard";
                for(short s=0;s<inventory.getCapacity();s++){
                    var stack=inventory.getItemStack(s);
                    if(stack!=null&&!stack.isEmpty()&&ItemContainer.getMatchingResourceType(stack.getItem(),id.substring(9))!=null){icon=stack.getItemId();break;}
                }
            }
            String name=id.equals("resource:Wood_Planks")?"Any wood planks":InventoryOps.label(id);
            materials.add(new Material(id,icon,name,cost.getValue(),InventoryOps.count(inventory,id,stack->availableIngredient(stack,creative))));
        }
        return new Readiness(node,research.researchName(node),research.hasUnlocked(player,node),List.copyOf(materials));
    }
    public synchronized boolean availableIngredient(ItemStack stack,boolean creative){
        if(!stack.getItemId().startsWith("SM_Containment_Capsule_"))return true;
        String token=stack.getFromMetadataOrNull("SMAnomaly",com.hypixel.hytale.codec.Codec.STRING);
        if(token==null)return creative;
        if(capsuleReserved.test(token)||spentCapsules.contains(token))return false;
        for(var machine:machines.values())for(var input:machine.reservedInputs){
            if(input.itemId().startsWith("SM_Containment_Capsule_")&&token.equals(input.toItemStack().getFromMetadataOrNull("SMAnomaly",com.hypixel.hytale.codec.Codec.STRING)))return false;
        }
        try{
            String[] identity=token.split(":",-1);if(identity.length!=2)return false;
            UUID id=UUID.fromString(identity[0]),nonce=UUID.fromString(identity[1]);
            return anomalies.get(id).filter(a->a.contained&&nonce.equals(a.capsuleNonce)&&a.type.capsuleItemId().equals(stack.getItemId())).isPresent();
        }catch(IllegalArgumentException ex){return false;}
    }
    public synchronized void welcome(PlayerRef player,Player entity) {
        if(entity.getGameMode()!=com.hypixel.hytale.protocol.GameMode.Adventure)return;
        String id=player.getUuid().toString();if(welcomed.contains(id))return;
        if(!config.giveStarterTablet||InventoryOps.give(inventory(entity),new ItemStack("SM_Research_Tablet",1))){welcomed.add(id);dirty=true;}
    }
    private static ItemContainer inventory(Player player) {
        var ref=player.getReference();
        return InventoryComponent.getCombined(ref.getStore(),ref,InventoryComponent.BACKPACK_STORAGE_HOTBAR);
    }
    public int capacity(MachineState m){return switch(m.id){case "SM_Resonant_Burner"->config.burnerCapacity;case "SM_Rift_Stabilizer"->config.riftCapacity;case "SM_Resonance_Condenser"->config.condenserCapacity;case "SM_Paradoxical_Energy_Cell"->Integer.MAX_VALUE;default->0;};}
    public synchronized void tick(World world,double dt) {
        String wn=world.getName();double elapsed=accumulated.getOrDefault(wn,0d)+Math.min(dt,.5);
        int count=Math.min(10,(int)(elapsed*20));accumulated.put(wn,elapsed-count*.05);
        for(int n=0;n<count;n++)tick20(world);
    }
    private void tick20(World world) {
        int tick=worldTicks.merge(world.getName(),1,Integer::sum);
        List<MachineState> loaded=new ArrayList<>();
        for(var m:inWorld(world)) {
            if(world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(m.x,m.z))==null)continue;
            if(!valid(world,m)){removed(world,m.block());continue;}
            if(m.id.equals("SM_Resonant_Conduit")&&!m.enabled){m.enabled=true;dirty=true;routes.clear();}
            loaded.add(m);m.active=false;
            recoverInvalidFuel(m);
            if(tick%10==1&&m.id.equals("SM_Resonant_Conduit"))updateConduit(world,m.x,m.y,m.z);
        }
        Map<UUID,Integer> riftUsers=new HashMap<>();
        for(var m:loaded) {
            if(!m.enabled)continue;
            switch(m.id) {
                case "SM_Resonant_Burner" -> {
                    if(consumeFuelTick(m)){m.energy=(int)Math.min(config.burnerCapacity,(long)m.energy+config.burnerGeneration);m.active=true;dirty=true;}
                }
                case "SM_Paradoxical_Energy_Cell" -> {m.energy=Integer.MAX_VALUE;m.active=true;}
                case "SM_Anomaly_Nullifier" -> {
                    var anomaly=anomalies.nearest(world,m.center(),config.nullifierRadius);
                    m.active=anomaly.isPresent()&&world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock((int)Math.floor(anomaly.get().x),(int)Math.floor(anomaly.get().z)))!=null;
                    if(m.active&&tick%20==0)ParticleUtil.spawnParticleEffect("SM_Nullifier_Field",new org.joml.Vector3d(m.x+.5,m.y+.65,m.z+.5),world.getEntityStore().getStore());
                }
                case "SM_Rift_Stabilizer" -> {
                    var anomaly=anomalies.nearest(world,m.center(),config.stabilizerRadius,AnomalyType.ENERGETIC_RIFT);
                    if(anomaly.isPresent()&&riftUsers.getOrDefault(anomaly.get().id,0)<config.maxStabilizersPerRift) {
                        riftUsers.merge(anomaly.get().id,1,Integer::sum);m.energy=(int)Math.min(config.riftCapacity,(long)m.energy+config.riftGeneration);m.lastAnomaly=anomaly.get().id.toString();m.active=true;dirty=true;
                        if(tick%5==0)com.hexvane.strangematter.effects.MachineLinks.rift(world,anomaly.get().position(),m.center().add(0,.6,0),tick);
                    }else m.lastAnomaly="";
                }
            }
        }
        var nodes=new HashMap<ResonantNetwork.Position,MachineState>();for(var m:loaded)nodes.put(new ResonantNetwork.Position(m.x,m.y,m.z),m);
        var used=new HashMap<ResonantNetwork.Position,Integer>();
        for(var source:loaded)if(source.enabled&&Set.of("SM_Resonant_Burner","SM_Rift_Stabilizer","SM_Paradoxical_Energy_Cell").contains(source.id)) {
            if(tick%20==1||!routes.containsKey(source.key()))routes.put(source.key(),ResonantNetwork.routes(source,nodes,config.maxNetworkSize));
            int budget=config.generatorTransfer;
            var consumers=routes.get(source.key());
            for(int i=0;i<consumers.size();i++) {
                var route=consumers.get((i+tick)%consumers.size());
                if(!nodes.containsValue(route.consumer())||!route.consumer().enabled||route.wires().stream().anyMatch(p->!nodes.containsKey(p)||!nodes.get(p).enabled))continue;
                int spent=ResonantNetwork.transfer(source,route,budget,config.condenserCapacity,config.conduitTransfer,config.conduitDistancePenalty,used);budget-=spent;if(spent>0)dirty=true;
            }
        }
        for(var m:loaded) {
            if(!m.enabled)continue;
            if(m.id.equals("SM_Resonance_Condenser")) {
                var anomaly=anomalies.nearest(world,m.center(),config.condenserRadius);
                if(anomaly.isPresent()&&m.energy>=config.condenserConsumption&&m.outputQuantity<new ItemStack(shard(anomaly.get().type),1).getItem().getMaxStack()) {
                    String output=shard(anomaly.get().type);
                    if(m.outputQuantity==0||m.output.equals(output)){
                        m.energy-=config.condenserConsumption;m.active=true;m.progress++;m.lastAnomaly=anomaly.get().type.displayName;dirty=true;
                        if(tick%5==0)com.hexvane.strangematter.effects.MachineLinks.condenser(world,anomaly.get().type,anomaly.get().position(),m.center().add(0,.7,0),tick);
                        if(m.progress>=config.condenserTicksPerShard){m.progress=0;m.output=output;m.outputQuantity++;}
                    }
                }
            } else if(m.id.equals("SM_Reality_Forge")&&!m.recipe.isEmpty()) {
                m.active=true;m.progress++;dirty=true;
                if(m.progress>=config.forgeCraftTicks){
                    var recipe=recipes.stream().filter(r->r.id.equals(m.recipe)).findFirst().orElseThrow();
                    var consumedTokens=new ArrayList<String>();
                    for(var input:m.reservedInputs){String token=input.toItemStack().getFromMetadataOrNull("SMAnomaly",com.hypixel.hytale.codec.Codec.STRING);if(token!=null){spentCapsules.add(token);consumedTokens.add(token);}}
                    m.output=recipe.output;m.outputQuantity=recipe.quantity;m.recipe="";m.progress=0;m.reservedInputs.clear();
                    // Persist output and retired capsule receipts together before another recipe can spend a copied token.
                    save();
                    for(String token:consumedTokens)anomalies.consumeCapsule(token);
                }
            }
            if(m.active&&tick%10==0&&Set.of("SM_Reality_Forge","SM_Resonance_Condenser","SM_Rift_Stabilizer","SM_Resonant_Burner").contains(m.id)) {
                String particle=m.id.equals("SM_Resonant_Burner")?"SM_Resonant_Burner_Active":"SM_Resonance_Transfer";
                // A small instrument-scale burst, separate from the anomaly's own full field.
                ParticleUtil.spawnParticleEffect(particle,m.center().add(0,.6,0),0,0,0,1f,1f,world.getEntityStore().getStore());
            }
        }
        // Reconcile every loaded machine, including disabled/full/unpowered instruments and
        // a Working block state restored from disk. Only real work starts the native loops.
        for(var machine:loaded)MachineWorkEffects.sync(world,machine);
        if(tick%200==0&&dirty)save();
    }
    /** Called during orderly world/plugin cleanup; unloading itself also removes client loops. */
    public synchronized void cleanupPresentation(World world){for(var machine:inWorld(world))MachineWorkEffects.stop(world,machine);}
    public static String shard(AnomalyType type){return "SM_"+switch(type){case GRAVITY->"Gravitic";case TEMPORAL_BLOOM->"Chrono";case ENERGETIC_RIFT->"Energetic";case WARP_GATE->"Spatial";case ECHOING_SHADOW->"Shade";case THOUGHTWELL->"Insight";}+"_Shard";}
    static boolean consumeFuelTick(MachineState state){
        if(state.fuelTicks<=0&&state.queuedFuelTicks>0){
            if(!state.fuelQueue.isEmpty()){var fuel=state.fuelQueue.removeFirst();state.fuelTicks=fuel.ticks();state.queuedFuelTicks=Math.max(0,state.queuedFuelTicks-fuel.ticks());}
            else{state.fuelTicks=state.queuedFuelTicks;state.queuedFuelTicks=0;} // Preserve old scalar-only saves.
        }
        if(state.fuelTicks<=0)return false;
        state.fuelTicks--;return true;
    }
    public synchronized void save() {
        var data=new Save();data.machines.addAll(machines.values());data.welcomed.addAll(welcomed);data.spentCapsules.addAll(spentCapsules);
        try {
            Files.createDirectories(file.getParent());Path tmp=file.resolveSibling(file.getFileName()+".tmp");
            Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(data)+"\n");
            try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
            dirty=false;
        } catch(IOException ex){throw new IllegalStateException("Cannot save laboratory state",ex);}
    }
    @Override public void close(){save();}
}
