package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.research.ResearchPageData;
import com.hexvane.strangematter.util.InventoryOps;
import com.hexvane.strangematter.ui.LivePageTransport;
import com.hexvane.strangematter.ui.MachineInventoryPanel;
import com.hexvane.strangematter.ui.PowerMeter;
import com.hexvane.strangematter.automation.FactoryService;
import com.hexvane.strangematter.automation.GadgetCharging;
import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.concurrent.*;

public final class MachinePage extends InteractiveCustomUIPage<ResearchPageData> {
    private final MachineService service;
    private final MachineState machine;
    private String message="";
    private int recipeIndex;
    private boolean initialized;
    private volatile boolean dismissed;
    private LivePageTransport.Lease input;
    private MachineInventoryPanel inventoryPanel;
    private Ref<EntityStore> inventoryOwner;
    private Store<EntityStore> inventoryStore;
    private com.hexvane.strangematter.automation.FactoryComponent inventoryComponent;
    private com.hypixel.hytale.server.core.inventory.container.ItemContainer inventoryInput, inventoryOutput;
    private boolean recovering;
    private String boundRecipe;
    private java.util.List<ForgeRecipe> visibleRecipes=java.util.List.of();
    private boolean forge(){return machine.id.equals("SM_Reality_Forge");}
    private boolean charger(){return machine.id.equals("SM_Resonant_Charging_Station");}
    private boolean dock(){return FactoryService.chargingMachine(machine.id);}
    private boolean storage(){return EnergyStoragePorts.storage(machine.id);}
    public MachinePage(PlayerRef player,MachineService service,MachineState machine){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;this.machine=machine;recipeIndex=service.selectedRecipeIndex(machine,player.getUuid());}
    public static void open(PlayerRef player,MachineService service,MachineState machine,Store<EntityStore> store){open(player,service,machine,store,false);}
    private static void open(PlayerRef player,MachineService service,MachineState machine,Store<EntityStore> store,boolean recovery){
        var ref=player.getReference();var entity=store.getComponent(ref,Player.getComponentType());if(entity==null)return;
        var page=new MachinePage(player,service,machine);page.inventoryOwner=ref;page.inventoryStore=store;page.recovering=recovery;
        var factory=service.factory();var world=store.getExternalData().getWorld();
        var component=factory==null?null:factory.component(world,machine);
        if(component!=null){
            if(!factory.access(component,player.getUuid()))return;
            page.inventoryComponent=component;page.inventoryInput=page.charger()?component.charging:component.input;
            page.inventoryOutput=recovery?component.recovery:page.dock()&&!page.charger()?component.charging:component.output;
            if(page.storage()){entity.getPageManager().openCustomPage(ref,store,page);return;}
            page.inventoryPanel=new MachineInventoryPanel(ref,store,page.inventoryInput,page.inventoryOutput,
                ()->!page.dismissed&&page.inventoryCurrent()&&service.canUse(store,player,machine)&&factory.access(component,player.getUuid()),()->!factory.blocked(world,machine),recovery||!page.dock()||page.charger());
            page.inventoryPanel.setPage(page);
            entity.getPageManager().openCustomPageWithWindows(ref,store,page,page.inventoryPanel.windows());
        }else entity.getPageManager().openCustomPage(ref,store,page);
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,Store<EntityStore> store){
        if(!initialized){
            input=service.research.pages().attach(playerRef,this,ref,store,data->handleDataEvent(ref,store,data));
            initialized=true;cmd.append(forge()?"StrangeMatter/RealityForge.ui":"StrangeMatter/Machine.ui");
            for(String id:forge()?new String[]{"Close","Collect","Toggle"}:new String[]{"Close","Recovery","Toggle","Pack"})input.bind(events,"#"+id,id,"");
            if(storage())for(var face:EnergyStoragePorts.Face.values())input.bind(events,"#Port"+face.name(),"StorageFace",face.name());
            if(!forge()){
                PowerMeter.append(cmd,"#PowerMeter");
                if(inventoryPanel!=null){MachineInventoryPanel.append(cmd,"#InventoryHost");inventoryPanel.build(cmd,events);}
                cmd.set("#InventoryHost.Visible",inventoryPanel!=null);
            }
            if(forge()){
                refreshRecipes(cmd,events);
                boundRecipe=selectedRecipeId();bindRecipe(events,boundRecipe);
            }
            refreshLater(ref,store);
        }
        draw(cmd,store.getComponent(ref,Player.getComponentType()),events);
    }
    private void bindRecipe(UIEventBuilder events,String recipeId){input.bind(events,"#Craft","Craft",recipeId);}
    private ForgeRecipe selectedRecipe(){return recipeIndex>=0&&recipeIndex<service.recipes.size()?service.recipes.get(recipeIndex):null;}
    private String selectedRecipeId(){var recipe=selectedRecipe();return recipe==null?"":recipe.id;}
    private void refreshRecipes(UICommandBuilder cmd,UIEventBuilder events){
        var known=service.recipes.stream().filter(recipe->service.knowsRecipe(playerRef.getUuid(),recipe)).toList();
        var selected=selectedRecipe();
        if(selected==null||!known.contains(selected))recipeIndex=known.isEmpty()?-1:service.recipes.indexOf(known.getFirst());
        if(known.equals(visibleRecipes))return;
        visibleRecipes=known;cmd.clear("#Recipes");
        for(int i=0;i<known.size();i++){
            var recipe=known.get(i);String row="#Recipes["+i+"]";
            cmd.append("#Recipes","StrangeMatter/ForgeRecipeRow.ui");
            cmd.set(row+" #RecipeIcon.ItemId",recipe.output);cmd.set(row+" #RecipeName.Text",InventoryOps.label(recipe.output));
            input.bind(events,row+" #SelectRecipe","SelectRecipe",recipe.id);
        }
    }
    private void draw(UICommandBuilder cmd,Player player,UIEventBuilder events){
        if(forge()){drawForge(cmd,player);return;}
        boolean burner=machine.id.equals("SM_Resonant_Burner");
        cmd.set("#Title.Text",InventoryOps.label(machine.id).toUpperCase());
        cmd.set("#State.Text",machine.active?"OPERATING":machine.enabled?"STANDBY":"DISABLED");
        String status=storage()?(!machine.enabled?"Paused":machine.active?"Transferring":machine.energy==0?"Empty":machine.energy>=service.capacity(machine)?"Full":"Stored")
                :dock()&&inventoryComponent!=null?GadgetCharging.status(machine,inventoryComponent):machine.active?"Working":!machine.enabled?"Paused":machine.energy<service.consumption(machine,machine.factoryTier)?"Waiting for power":"Ready";
        if(machine.id.equals("SM_Resonance_Condenser")&&inventoryStore!=null)status=service.condenserStatus(inventoryStore.getExternalData().getWorld(),machine);
        int generated=machine.active?switch(machine.id){case "SM_Resonant_Burner"->service.config.burnerGeneration*20;case "SM_Rift_Stabilizer"->service.config.riftGeneration*20;default->0;}:0;
        PowerMeter.draw(cmd,"#PowerMeter",machine.energy,service.capacity(machine),machine.incomingRate+generated,storage()?machine.outgoingRate:machine.active?service.consumption(machine,machine.factoryTier)*20:0,Math.max(0,service.config.condenserTicksPerShard-machine.progress)*(long)service.consumption(machine,machine.factoryTier),status,machine.id.equals("SM_Paradoxical_Energy_Cell"));
        if(inventoryPanel!=null){inventoryPanel.draw(cmd,events);cmd.set("#MachineInputLabel.Text",burner?"FUEL":charger()?"GADGET DOCK":"INGREDIENTS");cmd.set("#MachineOutputLabel.Text",recovering?"RECOVERED ITEMS":burner?"GADGET DOCK":"FINISHED ITEMS");cmd.set("#MachineOutputGrid.Visible",!charger()||recovering);cmd.set("#MachineOutputLabel.Visible",!charger()||recovering);}
        cmd.set("#DockCharge.Visible",dock());
        cmd.set("#Pack.Visible",FactoryService.packableMachine(machine.id));
        cmd.set("#StoragePorts.Visible",storage());
        if(storage())for(var face:EnergyStoragePorts.Face.values())cmd.set("#Port"+face.name()+".Text",face.name()+": "+EnergyStoragePorts.mode(machine,face).name());
        if(dock()){
            var stack=inventoryComponent==null?null:inventoryComponent.charging.getItemStack((short)0);
            String charge=GadgetCharging.accepts(stack)?InventoryOps.label(stack.getItemId())+"  "+GadgetEnergy.charge(stack)+" / "+GadgetEnergy.capacity(stack)+" RE":"Insert a powered gadget or battery pack";
            cmd.set("#DockCharge.Text",charge+"  |  "+status+"  |  Up to "+((long)(burner?service.config.burnerDockTransfer:service.config.chargerTransfer)*20)+" RE/s");
        }
        boolean recovery=false;
        if(service.factory()!=null&&inventoryStore!=null){var c=service.factory().component(inventoryStore.getExternalData().getWorld(),machine);if(c!=null)for(short i=0;i<c.recovery.getCapacity();i++)if(!com.hypixel.hytale.server.core.inventory.ItemStack.isEmpty(c.recovery.getItemStack(i))){recovery=true;break;}}
        cmd.set("#Recovery.Visible",recovering||recovery);cmd.set("#Recovery.Text",recovering?"SHOW OUTPUT":"RECOVERED ITEMS");
        int duration=service.config.condenserTicksPerShard;
        cmd.set("#Progress.Text",storage()?"Input and output: up to "+((long)service.config.energyStorageTransfer*20)+" RE/s each, shared across their enabled faces.":charger()?"Connect a burner directly or through resonant conduits.":burner?
                (machine.fuelTicks>0?(machine.active?"Burning one fuel item":"Current fuel paused")+(machine.enabled&&machine.energy+service.config.burnerGeneration>service.config.burnerCapacity?" — buffer full":""):"Ignites one fuel item when power is needed")
                        +(machine.queuedFuelTicks>0?"  |  Legacy fuel: "+fuelTime(machine.queuedFuelTicks):"")
                :"Cycle: "+(machine.progress*100/Math.max(1,duration))+"%"+(machine.lastAnomaly.isEmpty()?"":"  |  "+machine.lastAnomaly));
        cmd.set("#FuelCapacity.Visible",burner);
        if(burner){
            int items=0;if(inventoryComponent!=null)for(short slot=0;slot<inventoryComponent.input.getCapacity();slot++){
                var item=inventoryComponent.input.getItemStack(slot);if(FurnaceFuel.ticks(item)>0)items+=item.getQuantity();
            }
            cmd.set("#FuelCapacityText.Text","CURRENT FUEL   "+fuelTime(machine.fuelTicks)+" remaining  |  Fuel items: "+items);
            anchor(cmd,"#FuelFill",0,0,(int)Math.min(738,738L*machine.fuelTicks/Math.max(1,Math.max(machine.fuelDuration,machine.fuelTicks))),6);

        }
        cmd.set("#Toggle.Text",machine.id.equals("SM_Levitation_Pad")?(machine.ascending?"MODE: ASCEND":"MODE: DESCEND"):(machine.enabled?"DISABLE":"ENABLE"));

        cmd.set("#Message.Text",message.isEmpty()?(machine.powerRouteLimited?"Conduit network exceeds the "+service.config.maxNetworkSize+"-block scan limit. Shorten or split this network.":help()):message);
    }
    private void drawForge(UICommandBuilder cmd,Player player){
        if(player==null)return;
        var recipe=selectedRecipe();
        var readiness=recipe==null?null:service.readiness(playerRef.getUuid(),player,recipe);
        for(int i=0;i<visibleRecipes.size();i++){
            var entry=visibleRecipes.get(i);String row="#Recipes["+i+"]";boolean selected=entry==recipe;
            cmd.set(row+" #RecipeState.Text",selected?"SELECTED":"AVAILABLE");
            cmd.set(row+" #RecipeState.Style.TextColor","#8bddca");
            cmd.set(row+" #RecipeName.Style.TextColor",selected?"#78f2f6":"#d6e2f2");
            cmd.set(row+" #RecipeAccent.Background",selected?"#69edf2":"#466b73");
        }
        cmd.set("#RecipeCount.Text",visibleRecipes.size()+" discovered "+(visibleRecipes.size()==1?"recipe":"recipes"));
        cmd.set("#NoRecipes.Visible",visibleRecipes.isEmpty());
        boolean busy=!machine.recipe.isEmpty(),complete=machine.outputQuantity>0,animating=busy&&machine.enabled;
        double progress=complete?1:busy?Math.min(1,(double)machine.progress/Math.max(1,service.config.forgeCraftTicks)):0;
        cmd.set("#State.Text",complete?"OUTPUT READY":busy?(machine.enabled?"CRAFTING":"PAUSED"):"STANDBY");
        cmd.set("#Recipe.Text",recipe==null?"Your discoveries begin here":InventoryOps.label(recipe.output));
        cmd.set("#RecipeIndex.Text",recipe==null?"":(visibleRecipes.indexOf(recipe)+1)+" / "+visibleRecipes.size());
        cmd.set("#Research.Text",readiness==null?"Complete research to discover recipes.":"RESEARCH COMPLETE  /  "+readiness.researchName());
        cmd.set("#Research.Style.TextColor","#a5dccc");
        for(int i=0;i<8;i++){
            cmd.set("#Material"+i+".Visible",readiness!=null&&i<readiness.materials().size());
            if(readiness==null||i>=readiness.materials().size())continue;
            var material=readiness.materials().get(i);String color=material.missing()==0?"#67e8ef":"#f0a6bd";
            cmd.set("#MaterialIcon"+i+".ItemId",material.icon());cmd.set("#MaterialName"+i+".Text",material.name());
            cmd.set("#MaterialCount"+i+".Text",material.available()+" / "+material.required());
            cmd.set("#MaterialCount"+i+".Style.TextColor",color);cmd.set("#MaterialAccent"+i+".Background",color);
        }
        var working=busy?service.recipes.stream().filter(r->r.id.equals(machine.recipe)).findFirst().orElse(recipe):recipe;
        cmd.set("#ForgeChamber.Visible",complete||working!=null);
        if(complete||working!=null)cmd.set("#ForgePreview.ItemId",complete?machine.output:working.output);
        cmd.set("#ForgeQuantity.Text",complete||working!=null?(complete?machine.outputQuantity:working.quantity)+" x "+(complete?"READY":"OUTPUT"):"");
        cmd.set("#Progress.Text",complete?"COLLECT YOUR CREATION":busy?(machine.enabled?"CRAFTING  ":"PAUSED  ")+(int)(progress*100)+"%":"CRAFTING CHAMBER");
        anchor(cmd,"#ForgeFill",0,0,Math.max(1,(int)(392*progress)),5);
        double phase=machine.progress*.14;
        anchor(cmd,"#ForgePreview",153,67+(animating?(int)(Math.sin(phase)*7):0),96,96);
        cmd.set("#ForgeScan.Visible",animating);
        if(animating)anchor(cmd,"#ForgeScan",48+(int)((machine.progress%40)/40d*306),25,2,199);
        for(int i=0;i<8;i++){
            double angle=(animating?phase:0)+i*Math.PI/4;
            anchor(cmd,"#ForgeMote"+i,198+(int)(Math.cos(angle)*140),122+(int)(Math.sin(angle)*91),7,7);
            cmd.set("#ForgeMote"+i+".Background",animating?(i%2==0?"#82ffff":"#cf9fff"):"#46617d");
        }
        cmd.set("#Requirements.Text",busy&&working!=null?"Materials reserved for "+InventoryOps.label(working.output)+". The chamber will finish this recipe even while you browse.":complete?"Output tray: "+machine.outputQuantity+" x "+InventoryOps.label(machine.output)+". Collect it before starting another recipe.":readiness==null?"Use your Research Tablet to choose a topic, then complete its note in a Research Machine.":readiness.message());
        cmd.set("#Requirements.Style.TextColor",busy||complete||readiness==null||readiness.ready()?"#a7dfe7":"#f0a6bd");
        cmd.set("#Craft.Disabled",readiness==null||!readiness.ready()||busy||complete||!machine.enabled);
        cmd.set("#Collect.Disabled",!complete);cmd.set("#Toggle.Text",machine.enabled?"PAUSE":"RESUME");
        cmd.set("#Message.Text",message.isEmpty()?"Materials come from your inventory. Any wood planks can be used. A capsule cannot be used while it is being thrown.":message);
    }
    private static void anchor(UICommandBuilder cmd,String selector,int x,int y,int width,int height){
        var anchor=new Anchor();anchor.setLeft(Value.of(x));anchor.setTop(Value.of(y));anchor.setWidth(Value.of(width));anchor.setHeight(Value.of(height));cmd.setObject(selector+".Anchor",anchor);
    }
    private static String fuelTime(long ticks){long seconds=Math.max(0,ticks+19)/20;return seconds/60+"m "+seconds%60+"s";}
    private boolean inventoryCurrent(){
        if(inventoryComponent==null||inventoryStore==null)return false;
        var world=inventoryStore.getExternalData().getWorld();
        return com.hexvane.strangematter.automation.FactoryPickup.component(world,machine.block())==inventoryComponent
            &&(charger()?inventoryComponent.charging:inventoryComponent.input)==inventoryInput
            &&(recovering?inventoryComponent.recovery:dock()&&!charger()?inventoryComponent.charging:inventoryComponent.output)==inventoryOutput;
    }
    private String help(){return switch(machine.id){
        case "SM_Resonant_Conduit"->"Connect machine faces. Up to 500 RE/t per conduit; distance reduces throughput by 5% per block, to a 10% floor. Energy is conserved.";
        case "SM_Resonant_Burner"->"Place furnace fuel on the left and a gadget in the separate charging dock. Dock charging shares the burner's real stored power with the network. You can remove the gadget at any charge level.";
        case "SM_Resonant_Charging_Station"->"Insert a gadget or battery pack to recharge from the buffer. Remove it at any charge level. Tubes insert depleted gadgets and extract them only when full.";
        case EnergyStoragePorts.ID->"Faces are relative to the front panel. Click a face to cycle INPUT, OUTPUT or DISABLED. Connect directly or through conduits. PACK UP preserves all stored energy and face settings.";
        case "SM_Resonance_Condenser"->"Connect power and keep an anomaly within 10 blocks. The condenser uses 40 RE each second and makes one shard every 75 seconds.";
        case "SM_Reality_Forge"->"Select a discovered recipe. Crafting reserves the listed materials and shards from your inventory. Collect the finished output here.";
        case "SM_Stasis_Projector"->"Suspends a nearby specimen above the lens. Disable the field to release it.";
        case "SM_Levitation_Pad"->"Ascend or descend through a clear shaft. The lift stops below a solid ceiling.";
        case "SM_Rift_Stabilizer"->"Up to three stabilizers harvest each energetic rift within 16 blocks.";
        default->"Laboratory settings persist when the world is saved.";
    };}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ResearchPageData data){
        if(dismissed||input==null||!input.accepts(data))return;
        if("Close".equals(data.action)){dispose();close();return;}
        if(!service.canUse(store,playerRef,machine)){dispose();close();return;}
        if(inventoryComponent!=null&&(!inventoryCurrent()||!service.factory().access(inventoryComponent,playerRef.getUuid())||service.factory().blocked(store.getExternalData().getWorld(),machine)))return;
        var player=store.getComponent(ref,Player.getComponentType());if(player==null)return;
        switch(data.action==null?"":data.action){
            case "Recovery"->{dispose();open(playerRef,service,machine,store,!recovering);return;}
            case "Collect"->message=service.collect(player,machine);
            case "Toggle"->{service.toggle(machine);message="";}
            case "Pack"->{if(FactoryService.packableMachine(machine.id)&&service.factory()!=null)message=service.factory().pack(store.getExternalData().getWorld(),machine,playerRef);}
            case "StorageFace"->{if(storage())message=service.cycleStorageFace(store.getExternalData().getWorld(),machine,playerRef,data.value);}
            case "SelectRecipe"->{
                if(!forge()||data.value==null)return;
                int selected=-1;for(int i=0;i<service.recipes.size();i++)if(service.recipes.get(i).id.equals(data.value)){selected=i;break;}
                if(selected<0||!visibleRecipes.contains(service.recipes.get(selected))||!service.knowsRecipe(playerRef.getUuid(),service.recipes.get(selected)))return;
                recipeIndex=selected;service.selectRecipe(machine,playerRef.getUuid(),service.recipes.get(recipeIndex).id);message="";
            }
            case "Craft"->{
                if(!forge())return;
                var recipe=selectedRecipe();
                message=recipe==null||!service.knowsRecipe(playerRef.getUuid(),recipe)?"Complete research to discover a recipe.":recipe.id.equals(data.value)?service.craft(playerRef,player,machine,recipe.id):"Recipe selection changed. Review the displayed recipe before crafting.";
            }
            default->{return;}
        }
        service.save(); // The next frame renders the final state; input never waits for its ACK.
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,String raw){if(input!=null)input.receiveFromNative(raw);}
    private void refreshLater(Ref<EntityStore> ref,Store<EntityStore> store){
        try { CompletableFuture.delayedExecutor(forge()&&!machine.recipe.isEmpty()?125:500,TimeUnit.MILLISECONDS).execute(()->{
            if(dismissed)return;
            var world=store.getExternalData().getWorld();try { world.execute(()->{
                if(dismissed)return;
                if(!ref.isValid()){dispose();return;}
                if(!service.canUse(store,playerRef,machine)){dispose();close();return;}
                var player=store.getComponent(ref,Player.getComponentType());if(player==null||player.getPageManager().getCustomPage()!=this){dispose();return;}
                if(inventoryComponent!=null&&!inventoryCurrent()){dispose();close();return;}
                if(inventoryPanel!=null&&!inventoryPanel.isOpen()&&service.factory()!=null&&!service.factory().blocked(world,machine)){
                    dispose();open(playerRef,service,machine,store,recovering);return;
                }
                renderFrame(player);
                refreshLater(ref,store);
            }); } catch(RuntimeException stopped){dispose();}
        }); } catch(RuntimeException stopped){dispose();}
    }
    private void renderFrame(Player player){
        if(!input.ready())return;
        UICommandBuilder cmd=new UICommandBuilder();UIEventBuilder events=new UIEventBuilder();if(forge())refreshRecipes(cmd,events);draw(cmd,player,events);
        String renderedRecipe=selectedRecipeId();
        // Recipe text and its matching transaction ID change in the SAME packet.
        // Progress/power animation leaves every existing row binding alone.
        if(forge()&&!renderedRecipe.equals(boundRecipe))bindRecipe(events,renderedRecipe);
        if(input.send(cmd,events))boundRecipe=renderedRecipe;
    }
    private void dispose(){dismissed=true;if(inventoryPanel!=null)inventoryPanel.close(inventoryOwner,inventoryStore);if(input!=null)input.close();}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){dispose();super.onDismiss(ref,store);}
}
