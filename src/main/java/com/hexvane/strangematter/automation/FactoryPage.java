package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.research.ResearchPageData;
import com.hexvane.strangematter.ui.LivePageTransport;
import com.hexvane.strangematter.ui.MachineInventoryPanel;
import com.hexvane.strangematter.ui.PowerMeter;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.ui.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.*;

/** Stable scoped controls, live native inventory previews and one visual frame at a time. */
public final class FactoryPage extends InteractiveCustomUIPage<ResearchPageData> {
    private final FactoryService service;
    private final MachineState state;
    private LivePageTransport.Lease lease;
    private volatile boolean disposed;
    private String search="",category="All",message="",selected="",bound="",share="";
    private boolean settings;
    private String shownIngredients="";
    private int shownUpgradeRows=-1;
    private final Map<String,String> materialIcons=new HashMap<>();
    private List<String> renderedRows=List.of();
    private final MachineInventoryPanel inventoryPanel;
    private final Ref<EntityStore> playerEntity;
    private final Store<EntityStore> entityStore;
    private final ItemContainer inputInventory;
    private final ItemContainer outputInventory;
    private final boolean recovery;
    public FactoryPage(PlayerRef player,FactoryService service,MachineState state,Ref<EntityStore> ref,Store<EntityStore> store){
        this(player,service,state,ref,store,false);
    }
    private FactoryPage(PlayerRef player,FactoryService service,MachineState state,Ref<EntityStore> ref,Store<EntityStore> store,boolean recovery){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;this.state=state;playerEntity=ref;entityStore=store;
        this.recovery=recovery;var world=store.getExternalData().getWorld();var c=service.component(world,state);inputInventory=c.input;outputInventory=recovery?c.recovery:c.output;
        inventoryPanel=new MachineInventoryPanel(ref,store,inputInventory,outputInventory,()->!disposed&&ref.isValid()&&service.machines.canUse(store,player,state)&&service.component(world,state)==c&&service.access(c,player.getUuid())&&inputInventory==c.input&&outputInventory==(recovery?c.recovery:c.output),()->!service.blocked(world,state));
        inventoryPanel.setPage(this);
    }
    public com.hypixel.hytale.server.core.entity.entities.player.windows.Window[] windows(){return inventoryPanel.windows();}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,Store<EntityStore> store){
        lease=service.research.pages().attach(playerRef,this,ref,store,data->handleDataEvent(ref,store,data));cmd.append("StrangeMatter/Factory.ui");
        var c=service.component(store.getExternalData().getWorld(),state);selected=c.data.selected.getOrDefault(playerRef.getUuid().toString(),c.data.pattern);
        PowerMeter.append(cmd,"#PowerMeter");MachineInventoryPanel.append(cmd,"#InventoryHost");inventoryPanel.build(cmd,events);
        for(String id:List.of("Close","Stop","Repeat","Toggle","StockDown","StockUp","Upgrade","Grant","Revoke","Settings","Recovery"))lease.bind(events,"#"+id,id,"");
        if(inlineRecipes()){lease.bind(events,"#Category","Category","");lease.bindValue(events,CustomUIEventBindingType.ValueChanged,"#Search","Search","#Search.Value");}
        if(state.id.equals("SM_Pattern_Assembler"))lease.bind(events,"#ChoosePattern","ChoosePattern","");
        lease.bindValue(events,CustomUIEventBindingType.ValueChanged,"#AccessName","AccessName","#AccessName.Value");
        draw(ref,store,cmd,events,true);later(ref,store);
    }
    private UUID owner(FactoryComponent c){if(state.id.equals("SM_Reality_Forge")&&!c.data.repeat)return playerRef.getUuid();try{return UUID.fromString(c.data.owner);}catch(Exception ex){return playerRef.getUuid();}}
    private boolean inlineRecipes(){return state.id.equals("SM_Reality_Forge");}
    private void draw(Ref<EntityStore> ref,Store<EntityStore> store,UICommandBuilder cmd,UIEventBuilder events,boolean initial){
        var world=store.getExternalData().getWorld();var c=service.component(world,state);if(c==null)return;
        var known=service.recipes(state).stream().filter(r->service.discovered(world,state,c,r,owner(c))).toList();
        var categories=new ArrayList<String>();categories.add("All");known.stream().map(FactoryRecipes.Recipe::category).distinct().sorted().forEach(categories::add);if(!categories.contains(category))category="All";
        var visible=inlineRecipes()?known.stream().filter(r->category.equals("All")||r.category().equals(category)).filter(r->search.isBlank()||InventoryOps.label(r.output()).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).toList():List.<FactoryRecipes.Recipe>of();
        if(inlineRecipes()&&selected.isEmpty()&&!visible.isEmpty()){selected=visible.getFirst().id();service.select(world,state,playerRef.getUuid(),selected);}
        List<String> ids=visible.stream().map(FactoryRecipes.Recipe::id).toList();
        if(initial||!ids.equals(renderedRows)){cmd.clear("#Recipes");for(int i=0;i<visible.size();i++){var r=visible.get(i);String row="#Recipes["+i+"]";cmd.append("#Recipes","StrangeMatter/ForgeRecipeRow.ui");cmd.set(row+" #RecipeIcon.ItemId",r.output());cmd.set(row+" #RecipeName.Text",InventoryOps.label(r.output()));lease.bind(events,row+" #SelectRecipe","SelectRecipe",r.id());}renderedRows=ids;}
        for(int i=0;i<visible.size();i++){var r=visible.get(i);String row="#Recipes["+i+"]";String access=service.authorization(world,state,c,r,owner(c));cmd.set(row+" #RecipeState.Text",r.id().equals(selected)?"SELECTED":access==null?r.outputs().getFirst().getQuantity()+" made":access);cmd.set(row+" #RecipeAccent.Background",r.id().equals(selected)?"#79f4ed":access==null?"#466773":"#956d42");}
        boolean automatic=FactoryService.automaticInput(state.id);
        var recipe=automatic?service.automaticRecipe(world,state):known.stream().filter(r->r.id().equals(selected)).findFirst().orElse(null);cmd.set("#Title.Text",InventoryOps.label(state.id).toUpperCase(Locale.ROOT));cmd.set("#Tier.Text","Tier "+c.tier());
        cmd.set("#RecipeBrowser.Visible",inlineRecipes()&&!settings);cmd.set("#ProcessOverview.Visible",!inlineRecipes()&&!settings);cmd.set("#ChoosePattern.Visible",state.id.equals("SM_Pattern_Assembler"));cmd.set("#ProcessIcon.ItemId",state.id);
        cmd.set("#ProcessTitle.Text",state.id.equals("SM_Flux_Furnace")?"AUTOMATIC SMELTING":state.id.equals("SM_Resonant_Separator")?"ORE SEPARATION":"SAVED RECIPE");
        cmd.set("#ProcessHelp.Text",state.id.equals("SM_Flux_Furnace")?"Insert ingredients below. Smelting starts automatically when power is available. It runs 20% faster than a regular furnace at the same tier.":state.id.equals("SM_Resonant_Separator")?"Insert supported raw ore below. Each batch separates into metal concentrate, ready for smelting.":"Choose a Workbench recipe and insert its ingredients. The assembler slowly crafts it for you while powered.");
        cmd.set("#ChoosePattern.TooltipText",String.format(Locale.ROOT,"Assembly uses %.1f times the manual recipe duration, with a %.1f second minimum cycle.",service.machines.config.assemblerTimeMultiplier,service.machines.config.assemblerMinimumSeconds));
        cmd.set("#Category.Text",category.replace("Workbench_",""));cmd.set("#RecipeCount.Text",visible.size()+" recipes");cmd.set("#Owner.Text","Owner: "+ownerName(c.data.owner));
        cmd.set("#Selected.Text",recipe==null?(automatic?"Waiting for ingredients":"Choose a discovered recipe"):FactoryRecipeCategories.itemName(recipe.output(),playerRef.getLanguage()));
        String ingredientKey=recipe==null?"":recipe.fingerprint();if(initial||!ingredientKey.equals(shownIngredients)){cmd.clear("#Ingredients");if(recipe!=null)for(var ignored:recipe.inputs())cmd.append("#Ingredients","StrangeMatter/FactoryIngredientRow.ui");shownIngredients=ingredientKey;}
        if(recipe!=null)for(int i=0;i<recipe.inputs().size();i++){var material=recipe.inputs().get(i);String row="#Ingredients["+i+"]";String id=material.getItemId()!=null?material.getItemId():material.getResourceTypeId()!=null?"Any "+material.getResourceTypeId():"Matching material";int n=InternalCount.count(c.input,material,recipe.forge());String icon=materialIcon(material,recipe.forge(),c.input);cmd.set(row+" #MaterialIcon.Visible",!icon.isEmpty());if(!icon.isEmpty())cmd.set(row+" #MaterialIcon.ItemId",icon);cmd.set(row+" #MaterialName.Text",InventoryOps.label(id));cmd.set(row+" #MaterialCount.Text",n+" / "+material.getQuantity());cmd.set(row+" #MaterialCount.Style.TextColor",n>=material.getQuantity()?"#79f4ed":"#f0a6bd");}
        String access=recipe==null?"":service.authorization(world,state,c,recipe,owner(c));cmd.set("#RecipeAccess.Text",recipe==null?(automatic?"The input determines the output":"Open the recipe selector to choose a pattern"):access==null?(automatic?"Recipe chosen from input":"Recipe available"):access);
        int capacity=service.machines.capacity(state);int usage=service.machines.consumption(state,c.tier());PowerMeter.draw(cmd,"#PowerMeter",state.energy,capacity,state.incomingRate,state.active?usage*20:0,(long)Math.max(0,c.data.duration-c.data.progress)*usage,state.enabled?service.status(c):"Paused",false);
        cmd.set("#Status.Text",state.enabled?service.status(c):"Paused");double progress=c.busy()?(double)c.data.progress/Math.max(1,c.data.duration):0;anchor(cmd,"#ProgressFill",0,0,(int)(786*progress),7);cmd.set("#Progress.Text",c.busy()?(int)(progress*100)+"%   "+Math.max(0,(c.data.duration-c.data.progress+19)/20)+" seconds remaining":"Ready for the next batch");
        cmd.set("#Repeat.Text",c.data.repeat?"Repeat on":"Repeat off");cmd.set("#Toggle.Text",state.enabled?"Pause":"Resume");cmd.set("#Stock.Text","Keep "+c.data.target+" in output");
        String stockHelp="With Repeat on, keep this many of the selected output item. Crafting pauses at the target and resumes when items are removed.";
        for(String selector:List.of("#Stock","#StockDown","#StockUp","#Repeat"))cmd.set(selector+".TooltipText",stockHelp);
        for(String selector:List.of("#Craft","#Repeat","#Stock","#StockHelp","#StockDown","#StockUp"))cmd.set(selector+".Visible",!automatic&&!settings);
        cmd.set("#Stop.Visible",!settings);cmd.set("#Toggle.Visible",!settings);cmd.set("#Settings.Text",settings?"BACK":"SETTINGS");
        if(initial){anchor(cmd,"#Stop",automatic?328:544,300,automatic?380:164,40);anchor(cmd,"#Toggle",automatic?724:914,300,automatic?390:200,40);}
        cmd.set("#AutomaticHint.Visible",automatic);cmd.set("#AutomaticHint.Text","Smelting and separation continue while this window is closed.");
        cmd.set("#Preview.Visible",recipe!=null);if(recipe!=null)cmd.set("#Preview.ItemId",recipe.output());
        anchor(cmd,"#Preview",866,175+(state.active?(int)(Math.sin(c.data.progress*.13)*4):0),96,96);cmd.set("#Scan.Visible",state.active);if(state.active)anchor(cmd,"#Scan",784+(c.data.progress%40)*6,173,2,98);
        cmd.set("#SettingsGroup.Visible",settings);
        inventoryPanel.draw(cmd,events);
        cmd.set("#MachineOutputLabel.Text",recovery?"RECOVERED ITEMS":"FINISHED ITEMS");
        cmd.set("#Recovery.Visible",!settings&&(recovery||service.hasRecovery(world,state)));cmd.set("#Recovery.Text",recovery?"SHOW FINISHED ITEMS":"SHOW RECOVERED ITEMS");
        var upgrade=service.upgradeMaterials(state,c);cmd.set("#Upgrade.Visible",!upgrade.isEmpty());
        var supplies=InventoryComponent.getCombined(store,ref,InventoryComponent.Storage.getComponentType(),InventoryComponent.Hotbar.getComponentType());
        if(initial||shownUpgradeRows!=upgrade.size()){cmd.clear("#UpgradeCosts");for(var ignored:upgrade)cmd.append("#UpgradeCosts","StrangeMatter/FactoryUpgradeCost.ui");shownUpgradeRows=upgrade.size();}
        var requirements=FactoryInventory.requirements(supplies,upgrade);
        for(int i=0;i<requirements.size();i++){
            var requirement=requirements.get(i);var material=requirement.material();String row="#UpgradeCosts["+i+"]";String icon=materialIcon(material,false,supplies);
            cmd.set(row+" #UpgradeIcon.Visible",!icon.isEmpty());if(!icon.isEmpty())cmd.set(row+" #UpgradeIcon.ItemId",icon);
            cmd.set(row+" #UpgradeName.Text",material.getItemId()!=null?FactoryRecipeCategories.itemName(material.getItemId(),playerRef.getLanguage()):"Any "+InventoryOps.label(material.getResourceTypeId()));
            cmd.set(row+" #UpgradeCount.Text",requirement.available()+" / "+material.getQuantity());cmd.set(row+" #UpgradeCount.Style.TextColor",requirement.missing()>0?"#f0a6bd":"#79f4ed");
            cmd.set(row+" #UpgradeMissing.Text",requirement.missing()>0?"Need "+requirement.missing()+" more":"Ready");
            cmd.set(row+" #UpgradeMissing.Style.TextColor",requirement.missing()>0?"#f0a6bd":"#91c8bc");
        }
        cmd.set("#UpgradeTitle.Text",upgrade.isEmpty()?"MACHINE UPGRADE":"UPGRADE TO TIER "+(c.tier()+1));
        cmd.set("#UpgradeHelp.Text",upgrade.isEmpty()?"No further upgrades are available.":c.data.jobKind.equals("upgrade")?"Upgrading. Progress continues while this window is closed.":requirements.stream().anyMatch(r->r.missing()>0)?"Gather the missing materials shown above.":"All materials are ready.");
        cmd.set("#Message.Text",message.isEmpty()?"Move supplies into ingredients. Finished items appear in the output slots.":message);
        cmd.set("#Craft.Disabled",recipe==null||access!=null||c.busy()||!state.enabled);
        if(!automatic&&(initial||!selected.equals(bound))){lease.bind(events,"#Craft","Craft",selected);bound=selected;}
    }
    private static String ownerName(String id){try{var owner=Universe.get().getPlayer(UUID.fromString(id));return owner==null?id.substring(0,Math.min(8,id.length())):owner.getUsername();}catch(Exception ex){return "Unclaimed";}}
    private String materialIcon(MaterialQuantity material,boolean forge,ItemContainer input){if(material.getItemId()!=null)return material.getItemId();for(var stack:FactoryInventory.stacks(input))if(FactoryService.matches(material,stack,forge))return stack.getItemId();String key=material.getResourceTypeId()+":"+material.getTagIndex();return materialIcons.computeIfAbsent(key,ignored->com.hypixel.hytale.server.core.asset.type.item.config.Item.getAssetMap().getAssetMap().values().stream().filter(item->FactoryService.matches(material,new ItemStack(item.getId(),1),forge)).map(com.hypixel.hytale.server.core.asset.type.item.config.Item::getId).sorted().findFirst().orElse(""));}
    private static void anchor(UICommandBuilder cmd,String id,int x,int y,int w,int h){var a=new Anchor();a.setLeft(Value.of(x));a.setTop(Value.of(y));a.setWidth(Value.of(w));a.setHeight(Value.of(h));cmd.setObject(id+".Anchor",a);}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ResearchPageData data){
        if(disposed||lease==null||!lease.accepts(data))return;if("Close".equals(data.action)){dispose();close();return;}
        var world=store.getExternalData().getWorld();if(!service.machines.canUse(store,playerRef,state)){dispose();close();return;}var c=service.component(world,state);if(c==null||!service.access(c,playerRef.getUuid()))return;
        if(service.blocked(world,state)){message="Checking item transfer";return;}
        var inventory=InventoryComponent.getCombined(store,ref,InventoryComponent.Storage.getComponentType(),InventoryComponent.Hotbar.getComponentType());var player=store.getComponent(ref,Player.getComponentType());if(player==null)return;
        switch(data.action==null?"":data.action){
            case "Search"->search=data.value==null?"":data.value.substring(0,Math.min(80,data.value.length()));
            case "AccessName"->share=data.value==null?"":data.value;
            case "Settings"->settings=!settings;
            case "ChoosePattern"->{if(!state.id.equals("SM_Pattern_Assembler"))return;dispose();player.getPageManager().openCustomPage(ref,store,new FactoryRecipeSelectorPage(playerRef,service,state));return;}
            case "Recovery"->{reopen(ref,store,!recovery);return;}
            case "Category"->{var categories=new ArrayList<String>();categories.add("All");service.recipes(state).stream().filter(r->service.discovered(world,state,c,r,owner(c))).map(FactoryRecipes.Recipe::category).distinct().sorted().forEach(categories::add);category=categories.get((categories.indexOf(category)+1)%categories.size());}
            case "SelectRecipe"->{if(!renderedRows.contains(data.value))return;String result=service.select(world,state,playerRef.getUuid(),data.value);if(result.equals("Pattern selected."))selected=data.value;message=result;}
            case "Craft"->{if(FactoryService.automaticInput(state.id))return;message=selected.equals(data.value)?service.start(world,state,playerRef.getUuid(),data.value):"Selection changed. Review the recipe first.";}
            case "Stop"->message=service.cancel(world,state,playerRef.getUuid());
            case "Repeat"->{c.data.repeat=!c.data.repeat;message=c.data.repeat?"Repeating uses the owner's discoveries.":"Repeat stopped.";}
            case "Toggle"->state.enabled=!state.enabled;
            case "StockDown"->c.data.target=Math.max(1,c.data.target-25);
            case "StockUp"->c.data.target=Math.min(10000,c.data.target+25);
            case "Upgrade"->message=service.upgrade(world,state,playerRef.getUuid(),inventory);
            case "Grant","Revoke"->{var target=Universe.get().getPlayers().stream().filter(p->p.getUsername().equalsIgnoreCase(share)).findFirst().orElse(null);UUID id=null;try{id=target==null?UUID.fromString(share):target.getUuid();}catch(IllegalArgumentException ignored){}message=id==null?"Enter a nearby player name or a player UUID.":service.allow(world,state,playerRef.getUuid(),id,data.action.equals("Grant"));}
            default->{return;}
        }
        service.changed(world,state);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,String raw){if(lease!=null)lease.receiveFromNative(raw);}
    private void later(Ref<EntityStore> ref,Store<EntityStore> store){try{CompletableFuture.delayedExecutor(200,TimeUnit.MILLISECONDS).execute(()->{if(disposed)return;try{store.getExternalData().getWorld().execute(()->{if(disposed)return;if(!ref.isValid()||!service.machines.canUse(store,playerRef,state)){dispose();return;}var p=store.getComponent(ref,Player.getComponentType());if(p==null||p.getPageManager().getCustomPage()!=this){dispose();return;}var c=service.component(store.getExternalData().getWorld(),state);if(c==null){dispose();return;}if(inputInventory!=c.input||outputInventory!=(recovery?c.recovery:c.output)||!service.blocked(store.getExternalData().getWorld(),state)&&!inventoryPanel.isOpen()){reopen(ref,store,recovery);return;}if(lease.ready()){var cmd=new UICommandBuilder();var events=new UIEventBuilder();draw(ref,store,cmd,events,false);lease.send(cmd,events);}later(ref,store);});}catch(RuntimeException stopped){dispose();}});}catch(RuntimeException stopped){dispose();}}
    private void reopen(Ref<EntityStore> ref,Store<EntityStore> store,boolean recovered){var p=store.getComponent(ref,Player.getComponentType());if(p==null)return;var next=new FactoryPage(playerRef,service,state,ref,store,recovered);next.search=search;next.category=category;next.settings=settings;next.message=message;dispose();p.getPageManager().openCustomPageWithWindows(ref,store,next,next.windows());}
    private void dispose(){disposed=true;if(lease!=null)lease.close();inventoryPanel.close(playerEntity,entityStore);}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){dispose();super.onDismiss(ref,store);}
    private static final class InternalCount {static int count(ItemContainer inventory,MaterialQuantity m,boolean forge){if(forge&&m.getItemId()!=null)return FactoryInventory.stacks(inventory).stream().filter(s->s.getItemId().equals(m.getItemId())).mapToInt(ItemStack::getQuantity).sum();return com.hypixel.hytale.server.core.inventory.container.InternalContainerUtilMaterial.countMaterialFromItems(inventory,m,false);}}
}
