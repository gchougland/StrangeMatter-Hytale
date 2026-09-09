package com.hexvane.strangematter.machine;

import com.hexvane.strangematter.research.ResearchPageData;
import com.hexvane.strangematter.util.InventoryOps;
import com.hexvane.strangematter.ui.LivePageTransport;
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
    private String boundRecipe;
    private boolean forge(){return machine.id.equals("SM_Reality_Forge");}
    public MachinePage(PlayerRef player,MachineService service,MachineState machine){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;this.machine=machine;recipeIndex=service.selectedRecipeIndex(machine,player.getUuid());}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,Store<EntityStore> store){
        if(!initialized){
            input=service.research.pages().attach(playerRef,this,ref,store,data->handleDataEvent(ref,store,data));
            initialized=true;cmd.append(forge()?"StrangeMatter/RealityForge.ui":"StrangeMatter/Machine.ui");
            for(String id:forge()?new String[]{"Close","Collect","Toggle"}:new String[]{"Close","Fuel","FuelMax","Collect","Toggle"})input.bind(events,"#"+id,id,"");
            if(forge()){
                for(int i=0;i<service.recipes.size();i++){
                    var recipe=service.recipes.get(i);String row="#Recipes["+i+"]";
                    cmd.append("#Recipes","StrangeMatter/ForgeRecipeRow.ui");
                    cmd.set(row+" #RecipeIcon.ItemId",recipe.output);cmd.set(row+" #RecipeName.Text",InventoryOps.label(recipe.output));
                    input.bind(events,row+" #SelectRecipe","SelectRecipe",recipe.id);
                }
                boundRecipe=service.recipes.get(recipeIndex).id;bindRecipe(events,boundRecipe);
            }
            refreshLater(ref,store);
        }
        draw(cmd,store.getComponent(ref,Player.getComponentType()));
    }
    private void bindRecipe(UIEventBuilder events,String recipeId){input.bind(events,"#Craft","Craft",recipeId);}
    private void draw(UICommandBuilder cmd,Player player){
        if(forge()){drawForge(cmd,player);return;}
        boolean burner=machine.id.equals("SM_Resonant_Burner");
        cmd.set("#Title.Text",InventoryOps.label(machine.id).toUpperCase());
        cmd.set("#State.Text",machine.active?"OPERATING":machine.enabled?"STANDBY":"DISABLED");
        cmd.set("#Power.Text",service.capacity(machine)>0?"RESONANT ENERGY   "+machine.energy+" / "+service.capacity(machine)+" RE":"SELF-CONTAINED INSTRUMENT");
        int duration=service.config.condenserTicksPerShard;
        cmd.set("#Progress.Text",burner?"Burning: "+fuelTime(machine.fuelTicks)+"  |  Queued: "+fuelTime(machine.queuedFuelTicks):"Cycle: "+(machine.progress*100/Math.max(1,duration))+"%"+(machine.lastAnomaly.isEmpty()?"":"  |  "+machine.lastAnomaly));
        cmd.set("#FuelCapacity.Visible",burner);
        if(burner){
            cmd.set("#FuelCapacityText.Text","FUEL CAPACITY   "+fuelTime(FurnaceFuel.storedTicks(machine))+" / 26m 40s  (includes burning fuel)");
            anchor(cmd,"#FuelFill",0,0,(int)Math.min(738,738*FurnaceFuel.storedTicks(machine)/FurnaceFuel.MAX_FUEL_TICKS),6);
            cmd.set("#Fuel.Disabled",FurnaceFuel.remainingCapacity(machine)==0);cmd.set("#FuelMax.Disabled",FurnaceFuel.remainingCapacity(machine)==0);
        }
        cmd.set("#Output.Text",!machine.recoveredFuel.isEmpty()?"Recovered items: "+machine.recoveredFuel.size()+" - collect to return old non-fuel.":machine.outputQuantity>0?"Output tray: "+machine.outputQuantity+" x "+InventoryOps.label(machine.output):"Output tray empty");
        cmd.set("#Fuel.Visible",burner);cmd.set("#FuelMax.Visible",burner);cmd.set("#Collect.Disabled",machine.outputQuantity==0&&machine.recoveredFuel.isEmpty());
        cmd.set("#Toggle.Text",machine.id.equals("SM_Levitation_Pad")?(machine.ascending?"MODE: ASCEND":"MODE: DESCEND"):(machine.enabled?"DISABLE":"ENABLE"));
        cmd.set("#ForgePanel.Visible",false);
        cmd.set("#Message.Text",message.isEmpty()?help():message);
    }
    private void drawForge(UICommandBuilder cmd,Player player){
        if(player==null)return;
        var recipe=service.recipes.get(recipeIndex);
        var readiness=service.readiness(playerRef.getUuid(),player,recipe);
        int available=0;
        for(int i=0;i<service.recipes.size();i++){
            var entry=service.recipes.get(i);String row="#Recipes["+i+"]";
            String required=service.research.requiredResearchForItem(entry.output);if(required==null)required=entry.research;
            boolean known=service.research.hasUnlocked(playerRef.getUuid(),required),selected=i==recipeIndex;
            if(known)available++;
            cmd.set(row+" #RecipeState.Text",selected?(known?"SELECTED":"SELECTED   RESEARCH NEEDED"):(known?"AVAILABLE":"RESEARCH NEEDED"));
            cmd.set(row+" #RecipeState.Style.TextColor",known?"#8bddca":"#dca2b7");
            cmd.set(row+" #RecipeName.Style.TextColor",selected?"#78f2f6":known?"#d6e2f2":"#aab4c8");
            cmd.set(row+" #RecipeAccent.Background",selected?"#69edf2":known?"#466b73":"#614263");
        }
        cmd.set("#RecipeCount.Text",available+" available   "+service.recipes.size()+" total");
        boolean busy=!machine.recipe.isEmpty(),complete=machine.outputQuantity>0,animating=busy&&machine.enabled;
        double progress=complete?1:busy?Math.min(1,(double)machine.progress/Math.max(1,service.config.forgeCraftTicks)):0;
        cmd.set("#State.Text",complete?"OUTPUT READY":busy?(machine.enabled?"CRAFTING":"PAUSED"):"STANDBY");
        cmd.set("#Recipe.Text",InventoryOps.label(recipe.output));cmd.set("#RecipeIndex.Text",(recipeIndex+1)+" / "+service.recipes.size());
        cmd.set("#Research.Text",(readiness.researched()?"RESEARCH VERIFIED  /  ":"RESEARCH REQUIRED  /  ")+readiness.researchName());
        cmd.set("#Research.Style.TextColor",readiness.researched()?"#a5dccc":"#f0a6bd");
        for(int i=0;i<8;i++){
            cmd.set("#Material"+i+".Visible",i<readiness.materials().size());
            if(i>=readiness.materials().size())continue;
            var material=readiness.materials().get(i);String color=material.missing()==0?"#67e8ef":"#f0a6bd";
            cmd.set("#MaterialIcon"+i+".ItemId",material.icon());cmd.set("#MaterialName"+i+".Text",material.name());
            cmd.set("#MaterialCount"+i+".Text",material.available()+" / "+material.required());
            cmd.set("#MaterialCount"+i+".Style.TextColor",color);cmd.set("#MaterialAccent"+i+".Background",color);
        }
        var working=busy?service.recipes.stream().filter(r->r.id.equals(machine.recipe)).findFirst().orElse(recipe):recipe;
        cmd.set("#ForgePreview.ItemId",complete?machine.output:working.output);
        cmd.set("#ForgeQuantity.Text",(complete?machine.outputQuantity:working.quantity)+" x "+(complete?"READY":"OUTPUT"));
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
        cmd.set("#Requirements.Text",busy?"Materials reserved for "+InventoryOps.label(working.output)+". The chamber will finish this recipe even while you browse.":complete?"Output tray: "+machine.outputQuantity+" x "+InventoryOps.label(machine.output)+". Collect it before starting another recipe.":readiness.message());
        cmd.set("#Requirements.Style.TextColor",busy||complete||readiness.ready()?"#a7dfe7":"#f0a6bd");
        cmd.set("#Craft.Disabled",!readiness.ready()||busy||complete||!machine.enabled);
        cmd.set("#Collect.Disabled",!complete);cmd.set("#Toggle.Text",machine.enabled?"PAUSE":"RESUME");
        cmd.set("#Message.Text",message.isEmpty()?"Materials come from your inventory. Any wood plank type is accepted; capsules reserved for flight cannot be spent.":message);
    }
    private static void anchor(UICommandBuilder cmd,String selector,int x,int y,int width,int height){
        var anchor=new Anchor();anchor.setLeft(Value.of(x));anchor.setTop(Value.of(y));anchor.setWidth(Value.of(width));anchor.setHeight(Value.of(height));cmd.setObject(selector+".Anchor",anchor);
    }
    private static String fuelTime(long ticks){long seconds=Math.max(0,ticks+19)/20;return seconds/60+"m "+seconds%60+"s";}
    private String help(){return switch(machine.id){
        case "SM_Resonant_Conduit"->"Connect machine faces. Up to 500 RE/t per conduit; distance reduces throughput by 5% per block, to a 10% floor. Energy is conserved.";
        case "SM_Resonant_Burner"->"Load One adds one whole furnace fuel item. Load Max fills available capacity from your inventory; fuel burns in order at 20 RE/t. Collect returns non-fuel rescued from an older queue.";
        case "SM_Resonance_Condenser"->"Needs resonant power and an anomaly within 10 blocks. Consumes 2 RE/t; produces a shard every 75 powered seconds.";
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
        var player=store.getComponent(ref,Player.getComponentType());if(player==null)return;
        switch(data.action==null?"":data.action){
            case "Fuel"->message=service.fuel(player,machine);
            case "FuelMax"->message=service.fuelMax(player,machine);
            case "Collect"->message=service.collect(player,machine);
            case "Toggle"->{service.toggle(machine);message="";}
            case "SelectRecipe"->{
                if(!forge()||data.value==null)return;
                int selected=-1;for(int i=0;i<service.recipes.size();i++)if(service.recipes.get(i).id.equals(data.value)){selected=i;break;}
                if(selected<0)return;
                recipeIndex=selected;service.selectRecipe(machine,playerRef.getUuid(),service.recipes.get(recipeIndex).id);message="";
            }
            case "Craft"->{
                if(!forge())return;
                var recipe=service.recipes.get(recipeIndex);
                message=recipe.id.equals(data.value)?service.craft(playerRef,player,machine,recipe.id):"Recipe selection changed. Review the displayed recipe before crafting.";
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
                renderFrame(player);
                refreshLater(ref,store);
            }); } catch(RuntimeException stopped){dispose();}
        }); } catch(RuntimeException stopped){dispose();}
    }
    private void renderFrame(Player player){
        if(!input.ready())return;
        UICommandBuilder cmd=new UICommandBuilder();UIEventBuilder events=new UIEventBuilder();draw(cmd,player);
        String renderedRecipe=service.recipes.get(recipeIndex).id;
        // Recipe text and its matching transaction ID change in the SAME packet.
        // Progress/power animation leaves every existing row binding alone.
        if(forge()&&!renderedRecipe.equals(boundRecipe))bindRecipe(events,renderedRecipe);
        if(input.send(cmd,events))boundRecipe=renderedRecipe;
    }
    private void dispose(){dismissed=true;if(input!=null)input.close();}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){dispose();super.onDismiss(ref,store);}
}
