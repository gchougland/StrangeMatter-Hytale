package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.Bench;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockComponentSection;
import com.hypixel.hytale.server.core.universe.world.storage.*;
import org.joml.Vector3i;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** World-thread powered processing. Delayed UI acknowledgements never pause these jobs. */
public final class FactoryService implements TubeInventoryProvider,AutoCloseable {
    public static final Set<String> IDS=Set.of("SM_Reality_Forge","SM_Resonant_Separator","SM_Flux_Furnace","SM_Pattern_Assembler");
    private static final Set<String> LEGACY=Set.of("SM_Resonant_Burner","SM_Resonance_Condenser");
    public final MachineService machines;
    public final ResearchService research;
    private final FactoryKnowledge knowledge=new FactoryKnowledge();
    private record Site(World world,MachineState state,Ref<ChunkStore> ref,FactoryComponent component){}
    private final Map<String,Site> sites=new ConcurrentHashMap<>();
    private record Recipes(long time,List<FactoryRecipes.Recipe> values){}
    private final Map<String,Recipes> recipeCache=new ConcurrentHashMap<>();
    private volatile java.util.function.BiPredicate<World,Vector3i> recoveryBlocker=(w,p)->false;
    private final Map<String,Set<String>> reservedTokens=new ConcurrentHashMap<>();
    public FactoryService(MachineService machines,ResearchService research){this.machines=machines;this.research=research;FactoryRecipes.loadSeparations(machines);}
    public static boolean owns(String id){return IDS.contains(id);}
    public static void register(com.hypixel.hytale.server.core.plugin.JavaPlugin plugin){FactoryComponent.register(plugin);}
    public void setRecoveryBlocker(java.util.function.BiPredicate<World,Vector3i> blocker){recoveryBlocker=Objects.requireNonNull(blocker);}
    public boolean blocked(World world,MachineState state){return recoveryBlocker.test(world,state.block());}
    public void invalidateKnowledge(UUID owner){knowledge.invalidate(owner);}
    public FactoryComponent register(World world,MachineState state){
        if(!owns(state.id)&&!LEGACY.contains(state.id)||FactoryComponent.getComponentType()==null)return null;
        var store=world.getChunkStore().getStore();store.assertThread();
        var ref=BlockModule.getBlockEntity(world,state.x,state.y,state.z);
        if(ref==null){
            var section=world.getChunkStore().getChunkSectionReferenceAtBlock(state.x,state.y,state.z);if(section==null||!section.isValid())return null;
            var b=store.getComponent(section,BlockComponentSection.getComponentType());if(b==null)return null;
            var holder=ChunkStore.REGISTRY.newHolder();holder.addComponent(BlockModule.BlockStateInfo.getComponentType(),new BlockModule.BlockStateInfo(ChunkUtil.indexBlock(state.x,state.y,state.z),section));holder.addComponent(FactoryComponent.getComponentType(),new FactoryComponent());
            ref=store.addEntity(holder,AddReason.LOAD);
        }
        var component=store.getComponent(ref,FactoryComponent.getComponentType());if(component==null){component=new FactoryComponent();store.addComponent(ref,FactoryComponent.getComponentType(),component);}
        var old=sites.get(state.key());
        if(old==null||old.ref!=ref||old.component!=component){
            var site=new Site(world,state,ref,component);sites.put(state.key(),site);
            if(!component.data.configured){component.input=new SimpleItemContainer((short)inputSize(state.id,component.tier()));component.output=new SimpleItemContainer((short)(state.id.equals("SM_Pattern_Assembler")?10:5));component.data.configured=true;}
            migrate(site);restoreLegacy(site);state.factoryTier=component.tier();
            final var local=site;
            for(var container:List.of(component.input,component.output,component.escrow,component.pending,component.recovery))container.registerChangeEvent(event->mark(local));
            filters(site);state.energy=component.data.energy;mark(site);
        }
        return component;
    }
    private static int inputSize(String id,int tier){return switch(id){case "SM_Reality_Forge"->15;case "SM_Pattern_Assembler"->20;case "SM_Flux_Furnace"->tier>1?10:5;default->5;};}
    private void migrate(Site site){
        var c=site.component;var s=site.state;if(c.data.migrated)return;
        if(s.factoryMigration!=null&&!s.factoryMigration.isEmpty()){
            var saved=FactoryComponent.CODEC.decode(org.bson.BsonDocument.parse(s.factoryMigration),new com.hypixel.hytale.codec.ExtraInfo());
            c.input=saved.input;c.output=saved.output;c.escrow=saved.escrow;c.pending=saved.pending;c.recovery=saved.recovery;c.data=saved.data;
            s.output="";s.outputQuantity=0;s.reservedInputs.clear();s.recipe="";s.progress=0;machines.markDirty();return;
        }
        c.data.owner=s.owner==null?"":s.owner;c.data.energy=s.energy;captureLegacy(site);
        if(s.selectedRecipes!=null)c.data.selected.putAll(s.selectedRecipes);
        if(s.outputQuantity>0&&!s.output.isEmpty())FactoryInventory.add(c.output,List.of(new ItemStack(s.output,s.outputQuantity)));
        if(!s.recipe.isEmpty()){
            var recipe=recipe(s,s.recipe);var reserved=s.reservedInputs.stream().map(MachineState.ReservedInput::toItemStack).toList();
            if(recipe!=null&&!reserved.isEmpty()){FactoryInventory.add(c.escrow,reserved);FactoryInventory.add(c.pending,recipe.outputs());c.data.jobRecipe=recipe.id();c.data.jobFingerprint=recipe.fingerprint();c.data.jobKind="craft";c.data.jobOwner=c.data.owner;c.data.duration=ticks(s,c,recipe);c.data.progress=Math.min(s.progress,c.data.duration);c.data.pattern=recipe.id();}
            else FactoryInventory.add(c.recovery,reserved);
        }
        c.data.migrated=true;
        // The legacy file retains this one-time complete receipt until native block persistence
        // has caught up. New placements have a fresh MachineState and cannot inherit the receipt.
        if(s.outputQuantity>0||!s.recipe.isEmpty()||!s.reservedInputs.isEmpty()||s.fuelTicks>0||s.queuedFuelTicks>0||!s.recoveredFuel.isEmpty()){
            s.factoryMigration=FactoryComponent.CODEC.encode(c,new com.hypixel.hytale.codec.ExtraInfo()).asDocument().toJson();machines.markDirty();machines.save();
        }
        s.output="";s.outputQuantity=0;s.reservedInputs.clear();s.recipe="";s.progress=0;machines.markDirty();
    }
    public void claim(World world,MachineState state,UUID owner){var c=register(world,state);if(c!=null&&c.data.owner.isEmpty()){c.data.owner=owner.toString();mark(site(world,state));}}
    /** Placement hook for the tier on a real dropped machine item; no owner or pattern is imported. */
    public void initializeTier(World world,MachineState state,int tier){
        if(!state.id.equals("SM_Flux_Furnace")&&!state.id.equals("SM_Pattern_Assembler"))return;
        var c=register(world,state);if(c==null||c.busy())return;String benchId=state.id.equals("SM_Flux_Furnace")?"Bench_Furnace":"Bench_WorkBench";var nativeBench=bench(benchId);int highest=1,limit=state.id.equals("SM_Flux_Furnace")?2:3;
        while(highest<limit&&nativeBench!=null&&nativeBench.getTierLevel(highest+1)!=null)highest++;
        c.data.tier=Math.clamp(tier,1,highest);resize(site(world,state));mark(site(world,state));
    }
    public FactoryComponent component(World world,MachineState state){return register(world,state);}
    public List<FactoryRecipes.Recipe> recipes(MachineState state){long now=System.nanoTime();var cache=recipeCache.get(state.id);if(cache==null||now-cache.time>1_000_000_000L){cache=new Recipes(now,FactoryRecipes.list(machines,state.id));recipeCache.put(state.id,cache);}return cache.values;}
    public FactoryRecipes.Recipe recipe(MachineState state,String id){return recipes(state).stream().filter(r->r.id().equals(id)).findFirst().orElse(null);}
    private Site site(World world,MachineState state){var s=sites.get(state.key());return s!=null&&s.world==world&&s.ref.isValid()?s:null;}
    private void mark(Site site){if(site==null||!site.ref.isValid())return;captureLegacy(site);var tokens=new HashSet<String>();var reserved=new ArrayList<>(FactoryInventory.stacks(site.component.escrow));for(var encoded:site.component.data.retiredCapsules)reserved.add(TubeStacks.decode(encoded));for(var stack:reserved){String token=stack.getFromMetadataOrNull("SMAnomaly",com.hypixel.hytale.codec.Codec.STRING);if(token!=null)tokens.add(token);}reservedTokens.put(site.state.key(),Set.copyOf(tokens));if(owns(site.state.id))site.state.reservedInputs=reserved.stream().map(MachineState.ReservedInput::from).collect(java.util.stream.Collectors.toCollection(ArrayList::new));var info=site.ref.getStore().getComponent(site.ref,BlockModule.BlockStateInfo.getComponentType());if(info!=null)info.markNeedsSaving(site.ref.getStore());machines.markDirty();}
    private static void captureLegacy(Site site){var c=site.component.data;var s=site.state;if(s.id.equals("SM_Resonant_Burner")){c.fuelTicks=s.fuelTicks;c.queuedFuelTicks=s.queuedFuelTicks;c.fuelQueue=new ArrayList<>(s.fuelQueue);c.recoveredFuel=new ArrayList<>(s.recoveredFuel);}else if(s.id.equals("SM_Resonance_Condenser"))c.condenserProgress=s.progress;}
    private static void restoreLegacy(Site site){var c=site.component.data;var s=site.state;if(s.id.equals("SM_Resonant_Burner")){s.fuelTicks=c.fuelTicks;s.queuedFuelTicks=c.queuedFuelTicks;s.fuelQueue=new ArrayList<>(c.fuelQueue);s.recoveredFuel=new ArrayList<>(c.recoveredFuel);}else if(s.id.equals("SM_Resonance_Condenser"))s.progress=c.condenserProgress;}
    private void filters(Site site){
        for(short i=0;i<site.component.input.getCapacity();i++)site.component.input.setSlotFilter(FilterActionType.ADD,i,(a,c,s,stack)->ItemStack.isEmpty(stack)||accepts(site,stack));
        for(short i=0;i<site.component.output.getCapacity();i++)site.component.output.setSlotFilter(FilterActionType.ADD,i,(a,c,s,stack)->ItemStack.isEmpty(stack));
    }
    public boolean mayAccess(World world,Vector3i origin,UUID player){var site=sites.get(MachineState.key(world.getName(),origin.x,origin.y,origin.z));return site!=null&&access(site.component,player);}
    public boolean access(FactoryComponent c,UUID player){return c.data.owner.equals(player.toString())||c.data.allowed.contains(player.toString())||PermissionsModule.get().hasPermission(player,"strangematter.admin");}
    public String allow(World world,MachineState state,UUID actor,UUID target,boolean add){var site=site(world,state);if(site==null||!site.component.data.owner.equals(actor.toString())&&!PermissionsModule.get().hasPermission(actor,"strangematter.admin"))return "Only the owner can change access.";if(add)site.component.data.allowed.add(target.toString());else site.component.data.allowed.remove(target.toString());mark(site);return add?"Access granted.":"Access removed.";}
    public List<TubePort> ports(World world,Vector3i origin){var site=sites.get(MachineState.key(world.getName(),origin.x,origin.y,origin.z));if(site==null||!site.ref.isValid())return List.of();return List.of(new TubePort("input",site.state.id.equals("SM_Resonant_Burner")?"Fuel":"Ingredients",site.component.input,stack->accepts(site,stack),false),new TubePort("output","Finished items",site.component.output,stack->false,true));}
    private boolean accepts(Site site,ItemStack stack){
        if(ItemStack.isEmpty(stack))return false;
        if(site.state.id.equals("SM_Resonant_Burner"))return FurnaceFuel.ticks(stack)>0;
        if(site.state.id.equals("SM_Resonance_Condenser"))return false;
        var candidates=recipes(site.state);if(site.state.id.equals("SM_Pattern_Assembler")||site.state.id.equals("SM_Reality_Forge"))candidates=candidates.stream().filter(r->r.id().equals(site.component.data.pattern)).toList();
        for(var r:candidates)for(var material:r.inputs())if(matches(material,stack,r.forge())&&(!r.forge()||machines.availableIngredient(stack,false)))return true;return false;
    }
    public static boolean matches(MaterialQuantity material,ItemStack stack,boolean forge){
        if(ItemStack.isEmpty(stack)||material.isItemExcluded(stack.getItemId()))return false;
        if(forge&&material.getItemId()!=null)return material.getItemId().equals(stack.getItemId());
        var one=new SimpleItemContainer((short)1);one.setItemStackForSlot((short)0,stack,false);
        return InternalContainerUtilMaterial.countMaterialFromItems(one,material,false)>0;
    }
    public String authorization(World world,MachineState state,FactoryComponent c,FactoryRecipes.Recipe recipe,UUID owner){
        if(owner==null)return "Set a machine owner.";
        String node=research.requiredResearchForItem(recipe.output());if(recipe.forge()){var f=machines.recipes.stream().filter(r->r.id.equals(recipe.id())).findFirst().orElse(null);if(node==null&&f!=null)node=f.research;}
        if(node!=null&&!node.isEmpty()&&!research.hasUnlocked(owner,node))return "Complete "+research.researchName(node)+" research.";
        var nativeRecipe=recipe.nativeRecipe();if(nativeRecipe==null)return null;
        if(nativeRecipe.isKnowledgeRequired()&&node==null){var known=knowledge.get(owner,world);if(known==null)return "Checking recipe access";if(!known.contains(recipe.output()))return "Recipe not discovered";}
        if(nativeRecipe.getRequiredMemoriesLevel()>1&&MemoriesPlugin.get().getMemoriesLevel(world.getGameplayConfig())<nativeRecipe.getRequiredMemoriesLevel())return "Requires memory level "+nativeRecipe.getRequiredMemoriesLevel();
        String bench=state.id.equals("SM_Flux_Furnace")?"Furnace":"Workbench";var type=bench.equals("Furnace")?BenchType.Processing:BenchType.Crafting;
        var tags=Set.<String>of();var site=site(world,state);if(site!=null){var bb=site.ref.getStore().getComponent(site.ref,BenchBlock.getComponentType());if(bb!=null){BenchBlock.refreshGrantedAugmentTags(site.ref.getStore(),site.ref,state.block());tags=bb.getGrantedAugmentTags();}}
        boolean tier=false;for(var required:nativeRecipe.getBenchRequirement())if(required.type==type&&bench.equals(required.id)&&required.requiredTierLevel<=c.tier()){tier=true;if(required.requiredAugmentTags==null||tags.containsAll(Arrays.asList(required.requiredAugmentTags)))return null;}
        return tier?"Requires a workbench attachment":"Requires a higher machine tier";
    }
    private static UUID uuid(String text){try{return UUID.fromString(text);}catch(Exception invalid){return null;}}
    public boolean discovered(World world,MachineState state,FactoryComponent c,FactoryRecipes.Recipe recipe,UUID owner){String access=authorization(world,state,c,recipe,owner);return access==null||access.startsWith("Requires a higher")||access.startsWith("Requires a workbench")||access.startsWith("Requires memory");}
    public String start(World world,MachineState state,UUID actor,String recipeId){var site=site(world,state);if(site==null)return "Machine is unavailable.";var c=site.component;if(!access(c,actor))return "Only permitted players can operate this machine.";if(c.busy())return "Finish or stop the current job first.";var recipe=recipe(state,recipeId);if(recipe==null)return "Recipe unavailable.";UUID authority=state.id.equals("SM_Reality_Forge")&&!c.data.repeat?actor:uuid(c.data.owner);String reason=begin(site,recipe,authority);return reason==null?"Crafting started.":reason;}
    private String begin(Site site,FactoryRecipes.Recipe recipe,UUID authority){
        var c=site.component;var state=site.state;if(!state.enabled)return "Paused";
        if(blocked(site.world,state))return "Checking item transfer";
        if(!current(recipe)){recipeCache.remove(state.id);return "Recipe changed. Select it again.";}
        String rejection=authorization(site.world,state,c,recipe,authority);if(rejection!=null)return rejection;
        if(!FactoryInventory.fits(c.output,recipe.outputs()))return "Output full";
        if(state.energy<machines.consumption(state,c.tier()))return "Waiting for power";
        var plan=FactoryInventory.plan(c.input,recipe.inputs(),s->!recipe.forge()||machines.availableIngredient(s,false),recipe.forge());if(plan==null)return "Needs materials";
        if(!FactoryInventory.fits(c.escrow,plan.stacks())||!FactoryInventory.fits(c.pending,recipe.outputs()))return "Recipe exceeds machine capacity";
        if(!FactoryInventory.commit(c.input,plan))return "Ingredients changed";
        FactoryInventory.add(c.escrow,plan.stacks());FactoryInventory.add(c.pending,recipe.outputs());
        c.data.jobRecipe=recipe.id();c.data.jobFingerprint=recipe.fingerprint();c.data.jobOwner=authority.toString();c.data.jobKind="craft";c.data.duration=ticks(state,c,recipe);c.data.progress=0;mark(site);return null;
    }
    public int ticks(MachineState state,FactoryComponent c,FactoryRecipes.Recipe recipe){double seconds=recipe.seconds();if(state.id.equals("SM_Flux_Furnace"))seconds=seconds/1.2*(1-modifier("Bench_Furnace",c.tier()));else if(state.id.equals("SM_Pattern_Assembler"))seconds=Math.max(machines.config.assemblerMinimumSeconds,seconds*machines.config.assemblerTimeMultiplier*(1-modifier("Bench_WorkBench",c.tier())));return Math.max(1,(int)Math.min(Integer.MAX_VALUE,Math.ceil(seconds*20)));}
    public static boolean automaticInput(String id){return id.equals("SM_Flux_Furnace")||id.equals("SM_Resonant_Separator");}
    /** Mirrors the real input queue for the furnace display; selecting a preview never changes a job. */
    public FactoryRecipes.Recipe automaticRecipe(World world,MachineState state){
        var c=component(world,state);if(c==null||!automaticInput(state.id))return null;
        if(c.busy()&&c.data.jobKind.equals("craft"))return recipe(state,c.data.jobRecipe);
        var candidates=new ArrayList<>(recipes(state));candidates.sort(Comparator.comparingInt(r->firstSlot(c.input,r)));
        for(var recipe:candidates)if(FactoryInventory.plan(c.input,recipe.inputs(),s->true,recipe.forge())!=null)return recipe;
        return candidates.stream().filter(r->firstSlot(c.input,r)!=Integer.MAX_VALUE).findFirst().orElse(null);
    }
    private static Bench bench(String itemId){var block=com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getAsset(itemId);return block==null?null:block.getBench();}
    private static double modifier(String id,int tier){var b=bench(id);var level=b==null?null:b.getTierLevel(tier);return level==null?0:level.getCraftingTimeReductionModifier();}
    public void tick20(World world,List<MachineState> states){
        for(var state:states){if(!owns(state.id)&&!LEGACY.contains(state.id))continue;var c=register(world,state);if(c==null)continue;var site=site(world,state);if(site==null)continue;
            if(recoveryBlocker.test(world,state.block())){state.active=false;c.status="Checking item transfer";continue;}
            retireCapsules(site);state.factoryTier=c.tier();
            if(LEGACY.contains(state.id)){legacy(site);continue;}
            state.active=false;if(automaticInput(state.id))c.data.repeat=false;
            if(!state.enabled)c.status="Paused";
            else if(c.busy())advance(site);
            else {
                c.status="Needs materials";boolean automatic=automaticInput(state.id)||c.data.repeat;
                if(state.id.equals("SM_Pattern_Assembler")&&c.data.pattern.isBlank()){automatic=false;c.status="Choose a recipe";}
                if(automatic){var candidates=recipes(state);if(!c.data.pattern.isEmpty()&&(state.id.equals("SM_Pattern_Assembler")||state.id.equals("SM_Reality_Forge")))candidates=candidates.stream().filter(r->r.id().equals(c.data.pattern)).toList();
                    if(state.id.equals("SM_Resonant_Separator")||state.id.equals("SM_Flux_Furnace")){var ordered=new ArrayList<>(candidates);ordered.sort(Comparator.comparingInt(r->firstSlot(c.input,r)));candidates=ordered;}
                    for(var recipe:candidates){int yield=recipe.outputs().stream().filter(s->s.getItemId().equals(recipe.output())).mapToInt(ItemStack::getQuantity).sum();if(c.data.repeat&&stock(c.output,recipe.output())+yield>c.data.target){c.status="Stock target reached";break;}if(FactoryInventory.plan(c.input,recipe.inputs(),s->!recipe.forge()||machines.availableIngredient(s,false),recipe.forge())==null)continue;String reason=begin(site,recipe,uuid(c.data.owner));c.status=reason==null?"Working":reason;if(reason==null)advance(site);break;}
                }
            }
            if(!state.id.equals("SM_Reality_Forge")){if(state.active){if(c.effectTicks++%20==0)com.hexvane.strangematter.effects.GadgetEffects.particle(world,state.id+"_Work",state.center().add(0,.45,0));}else c.effectTicks=0;}
            state.progress=c.data.progress;state.recipe=c.data.jobRecipe;c.data.energy=state.energy;mark(site);
        }
    }
    private static int firstSlot(ItemContainer input,FactoryRecipes.Recipe recipe){for(short s=0;s<input.getCapacity();s++)for(var material:recipe.inputs())if(matches(material,input.getItemStack(s),recipe.forge()))return s;return Integer.MAX_VALUE;}
    private static int stock(ItemContainer output,String id){int n=0;for(var s:FactoryInventory.stacks(output))if(s.getItemId().equals(id))n+=s.getQuantity();return n;}
    private void advance(Site site){
        var c=site.component;var state=site.state;
        if(!c.data.jobKind.equals("upgrade")){var recipe=recipe(state,c.data.jobRecipe);if(recipe==null||!current(recipe)||!recipe.fingerprint().equals(c.data.jobFingerprint)){recipeCache.remove(state.id);c.status="Recipe changed. Stop to recover ingredients.";return;}String reason=authorization(site.world,state,c,recipe,uuid(c.data.jobOwner));if(reason!=null){c.status=reason;return;}}
        var outputs=FactoryInventory.stacks(c.pending);if(!FactoryInventory.fits(c.output,outputs)){c.status="Output full";return;}
        int power=machines.consumption(state,c.tier());if(state.energy<power){c.status="Waiting for power";return;}
        state.energy-=power;state.active=true;c.status="Working";c.data.progress++;
        if(c.data.progress<c.data.duration)return;
        if(c.data.jobKind.equals("upgrade")){c.data.tier=c.data.upgradeTier;resize(site);}
        else {FactoryInventory.add(c.output,outputs);for(var stack:FactoryInventory.stacks(c.escrow))if(stack.getFromMetadataOrNull("SMAnomaly",com.hypixel.hytale.codec.Codec.STRING)!=null)c.data.retiredCapsules.add(TubeStacks.encode(stack));}
        FactoryInventory.clear(c.pending);FactoryInventory.clear(c.escrow);reset(c);c.status="Ready";mark(site);
        if(!state.id.equals("SM_Reality_Forge"))com.hexvane.strangematter.effects.GadgetEffects.sound(site.world,state.id+"_Complete_SFX",state.center());
    }
    private void retireCapsules(Site site){var pending=site.component.data.retiredCapsules;if(pending.isEmpty())return;machines.consumeFactoryCapsules(pending.stream().map(TubeStacks::decode).toList());pending.clear();mark(site);}
    private static boolean current(FactoryRecipes.Recipe recipe){return recipe.nativeRecipe()==null||com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe.getAssetMap().getAsset(recipe.id())==recipe.nativeRecipe();}
    private void resize(Site site){var c=site.component;site.state.factoryTier=c.tier();int size=inputSize(site.state.id,c.tier());if(size>c.input.getCapacity()){var next=new SimpleItemContainer((short)size);FactoryInventory.add(next,FactoryInventory.stacks(c.input));c.input=next;c.input.registerChangeEvent(event->mark(site));filters(site);}}
    private static void reset(FactoryComponent c){c.data.jobRecipe="";c.data.jobOwner="";c.data.jobFingerprint="";c.data.jobKind="";c.data.progress=0;c.data.duration=0;c.data.upgradeTier=0;}
    public String cancel(World world,MachineState state,UUID player){var site=site(world,state);if(site==null||blocked(world,state)||!access(site.component,player))return "Access denied.";var c=site.component;c.data.repeat=false;if(!c.busy())return "Repeat stopped.";var refund=FactoryInventory.stacks(c.escrow);if(!FactoryInventory.fits(c.input,refund))return "Make room in ingredients before stopping.";FactoryInventory.add(c.input,refund);FactoryInventory.clear(c.escrow);FactoryInventory.clear(c.pending);reset(c);mark(site);return "Ingredients returned. Used power is not refunded.";}
    public String select(World world,MachineState state,UUID player,String id){var site=site(world,state);if(site==null||blocked(world,state)||!access(site.component,player))return "Access denied.";var recipe=recipe(state,id);if(recipe==null||!discovered(world,state,site.component,recipe,state.id.equals("SM_Reality_Forge")?player:uuid(site.component.data.owner)))return "Recipe not discovered.";site.component.data.pattern=id;site.component.data.selected.remove(player.toString());site.component.data.selected.put(player.toString(),id);while(site.component.data.selected.size()>MachineState.MAX_RECIPE_SELECTIONS)site.component.data.selected.remove(site.component.data.selected.keySet().iterator().next());state.rememberRecipe(player,id);mark(site);return "Pattern selected.";}
    public String load(World world,MachineState state,UUID player,ItemContainer inventory,String id){var site=site(world,state);if(site==null||blocked(world,state)||!access(site.component,player))return "Access denied.";var recipe=recipe(state,id);if(recipe==null)return "Select a recipe first.";String authorization=authorization(world,state,site.component,recipe,state.id.equals("SM_Reality_Forge")?player:uuid(site.component.data.owner));if(authorization!=null)return authorization;
        var needed=new ArrayList<MaterialQuantity>();var scratch=site.component.input.clone();
        for(var material:recipe.inputs()){int n=Math.max(0,material.getQuantity()-InternalContainerUtilMaterial.countMaterialFromItems(scratch,material,false));if(n>0)needed.add(material.clone(n));}
        if(needed.isEmpty())return "Ingredients already loaded.";
        var plan=FactoryInventory.plan(inventory,needed,s->!recipe.forge()||machines.availableIngredient(s,false),recipe.forge());if(plan==null)return "Your inventory needs more recipe materials.";if(!FactoryInventory.fits(site.component.input,plan.stacks()))return "Ingredient slots are full.";if(!FactoryInventory.commit(inventory,plan))return "Your inventory changed.";FactoryInventory.add(site.component.input,plan.stacks());mark(site);return "Ingredients loaded.";
    }
    public String collect(World world,MachineState state,UUID player,ItemContainer inventory){var site=site(world,state);if(site==null||blocked(world,state)||!access(site.component,player))return "Access denied.";int n=0;for(var source:List.of(site.component.output,site.component.recovery))for(short s=0;s<source.getCapacity();s++){var item=source.getItemStack(s);if(ItemStack.isEmpty(item)||!FactoryInventory.fits(inventory,List.of(item)))continue;var result=source.moveItemStackFromSlot(s,inventory,true,true);if(result.succeeded())n++;}mark(site);return n>0?"Items collected.":"Make room in your inventory.";}
    public List<MaterialQuantity> upgradeMaterials(MachineState state,FactoryComponent c){String id=state.id.equals("SM_Pattern_Assembler")?"Bench_WorkBench":state.id.equals("SM_Flux_Furnace")?"Bench_Furnace":null;if(id==null)return List.of();var bench=bench(id);var upgrade=bench==null?null:bench.getUpgradeRequirement(c.tier());if(upgrade==null)return List.of();var result=new ArrayList<MaterialQuantity>(Arrays.asList(upgrade.getInput()));int amount=c.tier()==1?2:4;result.add(new MaterialQuantity("SM_Resonant_Circuit",null,null,amount,null));if(state.id.equals("SM_Pattern_Assembler"))result.add(new MaterialQuantity("SM_Resonant_Coil",null,null,amount,null));return result;}
    public String upgrade(World world,MachineState state,UUID player,ItemContainer inventory){var site=site(world,state);if(site==null||blocked(world,state)||!access(site.component,player))return "Access denied.";var c=site.component;if(c.busy())return "Finish or stop this job first.";if(!state.enabled)return "Paused";if(state.energy<machines.consumption(state,c.tier()))return "Waiting for power";var cost=upgradeMaterials(state,c);if(cost.isEmpty())return "Highest machine tier reached.";var plan=FactoryInventory.plan(inventory,cost,s->true,false);if(plan==null)return "Missing upgrade materials.";if(!FactoryInventory.fits(c.escrow,plan.stacks()))return "Upgrade exceeds machine capacity";if(!FactoryInventory.commit(inventory,plan))return "Your inventory changed.";FactoryInventory.add(c.escrow,plan.stacks());c.data.jobKind="upgrade";c.data.jobRecipe="upgrade:"+(c.tier()+1);c.data.jobOwner=player.toString();c.data.upgradeTier=c.tier()+1;var b=bench(state.id.equals("SM_Pattern_Assembler")?"Bench_WorkBench":"Bench_Furnace");c.data.duration=Math.max(1,(int)Math.ceil(b.getUpgradeRequirement(c.tier()).getTimeSeconds()*20));c.data.progress=0;mark(site);return "Upgrade started.";}
    public void changed(World world,MachineState state){mark(site(world,state));}
    public String status(FactoryComponent component){return component.status;}
    public void open(PlayerRef player,Ref<EntityStore> ref,Store<EntityStore> store,MachineState state){var world=store.getExternalData().getWorld();claim(world,state,player.getUuid());var c=register(world,state);if(c==null||!access(c,player.getUuid()))return;var entity=store.getComponent(ref,Player.getComponentType());if(entity!=null){var page=new FactoryPage(player,this,state,ref,store);entity.getPageManager().openCustomPageWithWindows(ref,store,page,page.windows());}}
    /** Read-only identity for deferred callbacks; never registers or migrates a replacement block. */
    public FactoryComponent registeredComponent(World world,MachineState state){var site=sites.get(state.key());return site!=null&&site.world==world&&site.state==state?site.component:null;}
    public List<ItemStack> remove(World world,MachineState state){return remove(world,state,null);}
    public List<ItemStack> remove(World world,MachineState state,FactoryComponent expected){var site=sites.get(state.key());if(site==null||site.world!=world||site.state!=state||expected!=null&&site.component!=expected)return List.of();retireCapsules(site);var c=site.component;var all=new ArrayList<ItemStack>();for(var inventory:List.of(c.input,c.output,c.escrow,c.recovery)){all.addAll(FactoryInventory.stacks(inventory));FactoryInventory.clear(inventory);}FactoryInventory.clear(c.pending);reset(c);mark(site);sites.remove(state.key(),site);reservedTokens.remove(state.key());state.reservedInputs.clear();return all;}
    public boolean hasContents(World world,MachineState state){var c=register(world,state);return c!=null&&(c.busy()||!c.data.retiredCapsules.isEmpty()||List.of(c.input,c.output,c.escrow,c.pending,c.recovery).stream().anyMatch(i->!FactoryInventory.stacks(i).isEmpty()));}
    public boolean hasRecovery(World world,MachineState state){var c=register(world,state);return c!=null&&!FactoryInventory.stacks(c.recovery).isEmpty();}
    public ItemContainer outputInventory(World world,MachineState state,boolean recovery){var c=register(world,state);return c==null?EmptyItemContainer.INSTANCE:recovery?c.recovery:c.output;}
    public boolean isCapsuleReserved(String token){return reservedTokens.values().stream().anyMatch(set->set.contains(token));}
    public void cleanup(World world){sites.entrySet().removeIf(e->e.getValue().world==world);}
    @Override public void close(){sites.clear();knowledge.close();}
    private void legacy(Site site){var c=site.component;var s=site.state;if(s.id.equals("SM_Resonant_Burner")){machines.fuel(c.input,s,true);for(var iterator=s.recoveredFuel.iterator();iterator.hasNext();){var stack=iterator.next().toItemStack();if(!FactoryInventory.fits(c.recovery,List.of(stack)))break;FactoryInventory.add(c.recovery,List.of(stack));iterator.remove();}}c.data.energy=s.energy;mark(site);}
    public boolean outputHasRoom(World world,MachineState state,String itemId){var c=register(world,state);return c!=null&&FactoryInventory.fits(c.output,List.of(new ItemStack(itemId,1)));}
    public boolean outputHasRoom(World world,MachineState state,String itemId,int quantity){var c=register(world,state);return c!=null&&!recoveryBlocker.test(world,state.block())&&FactoryInventory.fits(c.output,List.of(new ItemStack(itemId,quantity)));}
    public boolean acceptOutput(World world,MachineState state,ItemStack stack){if(!outputHasRoom(world,state,stack.getItemId(),stack.getQuantity()))return false;var site=site(world,state);FactoryInventory.add(site.component.output,List.of(stack));mark(site);return true;}
    public int outputQuantity(World world,MachineState state){var c=register(world,state);return c==null?0:FactoryInventory.stacks(c.output).stream().mapToInt(ItemStack::getQuantity).sum();}
    public String outputItemId(World world,MachineState state){var c=register(world,state);return c==null?"":FactoryInventory.stacks(c.output).stream().map(ItemStack::getItemId).findFirst().orElse("");}
    public String collect(Player player,MachineState state){var ref=player.getReference();if(ref==null||!ref.isValid())return "Player unavailable.";var store=ref.getStore();var owner=store.getComponent(ref,PlayerRef.getComponentType());if(owner==null)return "Player unavailable.";return collect(store.getExternalData().getWorld(),state,owner.getUuid(),InventoryComponent.getCombined(store,ref,InventoryComponent.HOTBAR_FIRST));}
}
