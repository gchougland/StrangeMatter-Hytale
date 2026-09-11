package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.machine.MachineState;
import com.hexvane.strangematter.research.ResearchPageData;
import com.hexvane.strangematter.ui.LivePageTransport;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.*;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.*;

/** Separate Workbench recipe chooser. Inventory transfers remain on the machine page. */
public final class FactoryRecipeSelectorPage extends InteractiveCustomUIPage<ResearchPageData> {
    private final FactoryService service;
    private final MachineState state;
    private LivePageTransport.Lease lease;
    private String category="All",search="",message="";
    private List<String> shownRecipes=List.of(),shownTabs=List.of();
    private volatile boolean disposed;
    public FactoryRecipeSelectorPage(PlayerRef player,FactoryService service,MachineState state){super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;this.state=state;}
    private UUID owner(FactoryComponent c){try{return UUID.fromString(c.data.owner);}catch(IllegalArgumentException ignored){return null;}}
    public List<FactoryRecipes.Recipe> known(Store<EntityStore> store){var world=store.getExternalData().getWorld();var c=service.component(world,state);return c==null?List.of():service.recipes(state).stream().filter(r->service.discovered(world,state,c,r,owner(c))).toList();}
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder commands,UIEventBuilder events,Store<EntityStore> store){
        lease=service.research.pages().attach(playerRef,this,ref,store,data->handleDataEvent(ref,store,data));commands.append("StrangeMatter/FactoryRecipeSelector.ui");
        lease.bind(events,"#Back","Back","");lease.bindValue(events,CustomUIEventBindingType.ValueChanged,"#Search","Search","#Search.Value");draw(store,commands,events,true);later(ref,store);
    }
    private void draw(Store<EntityStore> store,UICommandBuilder commands,UIEventBuilder events,boolean initial){
        var c=service.component(store.getExternalData().getWorld(),state);if(c==null)return;var known=known(store);var tabs=FactoryRecipeCategories.tabs(known,playerRef.getLanguage());
        if(tabs.stream().noneMatch(tab->tab.id().equals(category)))category="All";
        var tabIds=tabs.stream().map(FactoryRecipeCategories.Tab::id).toList();
        if(initial||!tabIds.equals(shownTabs)){commands.clear("#Tabs");for(int i=0;i<tabs.size();i++){if(i%5==0)commands.append("#Tabs","StrangeMatter/FactoryRecipeTabRow.ui");var tab=tabs.get(i);String row=tabSelector(i);commands.append("#Tabs["+(i/5)+"]","StrangeMatter/FactoryRecipeTab.ui");commands.set(row+" #Tab.Text",tab.label());lease.bind(events,row+" #Tab","Tab",tab.id());}shownTabs=tabIds;}
        for(int i=0;i<tabs.size();i++)commands.set(tabSelector(i)+" #TabAccent.Visible",tabs.get(i).id().equals(category));
        var visible=known.stream().filter(r->FactoryRecipeCategories.matches(r,category)).filter(r->search.isBlank()||FactoryRecipeCategories.itemName(r.output(),playerRef.getLanguage()).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))).toList();
        var ids=visible.stream().map(FactoryRecipes.Recipe::id).toList();
        if(initial||!ids.equals(shownRecipes)){commands.clear("#RecipeRows");for(int i=0;i<visible.size();i++){var recipe=visible.get(i);String row="#RecipeRows["+i+"]";commands.append("#RecipeRows","StrangeMatter/FactoryRecipeChoice.ui");commands.set(row+" #RecipeIcon.ItemId",recipe.output());commands.set(row+" #RecipeName.Text",FactoryRecipeCategories.itemName(recipe.output(),playerRef.getLanguage()));lease.bind(events,row+" #Choose","Choose",recipe.id());}shownRecipes=ids;}
        for(int i=0;i<visible.size();i++){var recipe=visible.get(i);String row="#RecipeRows["+i+"]";String access=service.authorization(store.getExternalData().getWorld(),state,c,recipe,owner(c));int quantity=recipe.outputs().stream().filter(stack->recipe.output().equals(stack.getItemId())).mapToInt(com.hypixel.hytale.server.core.inventory.ItemStack::getQuantity).sum();
            commands.set(row+" #RecipeStatus.Text",access==null?(recipe.id().equals(c.data.pattern)?"SELECTED RECIPE":quantity+" made each cycle"):access);
            commands.set(row+" #RecipeTime.Text",String.format(Locale.ROOT,"%.1f seconds",service.ticks(state,c,recipe)/20d));commands.set(row+" #RecipeAccent.Background",recipe.id().equals(c.data.pattern)?"#76eee5":access==null?"#476b82":"#a77d54");
            commands.set(row+" #Choose.TooltipText",FactoryRecipeCategories.itemName(recipe.output(),playerRef.getLanguage())+"\n"+(access==null?"Select this recipe for the assembler.":access)+"\n"+quantity+" made each cycle");}
        commands.set("#RecipeCount.Text",visible.size()+" discovered recipes");commands.set("#Empty.Visible",visible.isEmpty());commands.set("#Message.Text",message.isBlank()?"Only the owner's discovered Workbench recipes are shown. Selecting a recipe returns to the assembler.":message);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ResearchPageData data){
        if(disposed||lease==null||!lease.accepts(data))return;
        if(!service.machines.canUse(store,playerRef,state)){dispose();close();return;}var c=service.component(store.getExternalData().getWorld(),state);if(c==null||!service.access(c,playerRef.getUuid())){dispose();close();return;}
        switch(data.action==null?"":data.action){
            case "Back"->{returnToMachine(ref,store);return;}
            case "Search"->search=data.value==null?"":data.value.substring(0,Math.min(80,data.value.length()));
            case "Tab"->{if(!shownTabs.contains(data.value))return;category=data.value;}
            case "Choose"->{if(!shownRecipes.contains(data.value)||known(store).stream().noneMatch(r->r.id().equals(data.value)))return;message=service.select(store.getExternalData().getWorld(),state,playerRef.getUuid(),data.value);if(message.equals("Pattern selected.")){returnToMachine(ref,store);return;}}
            default->{return;}
        }
    }
    private void returnToMachine(Ref<EntityStore> ref,Store<EntityStore> store){dispose();service.open(playerRef,ref,store,state);}
    private static String tabSelector(int index){return "#Tabs["+(index/5)+"]["+(index%5)+"]";}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,String raw){if(lease!=null)lease.receiveFromNative(raw);}
    private void later(Ref<EntityStore> ref,Store<EntityStore> store){try{CompletableFuture.delayedExecutor(200,TimeUnit.MILLISECONDS).execute(()->{if(disposed)return;try{store.getExternalData().getWorld().execute(()->{if(disposed)return;if(!ref.isValid()){dispose();return;}var player=store.getComponent(ref,Player.getComponentType());if(player==null||player.getPageManager().getCustomPage()!=this){dispose();return;}if(!service.machines.canUse(store,playerRef,state)){dispose();close();return;}if(lease.ready()){var commands=new UICommandBuilder();var events=new UIEventBuilder();draw(store,commands,events,false);lease.send(commands,events);}later(ref,store);});}catch(RuntimeException stopped){dispose();}});}catch(RuntimeException stopped){dispose();}}
    private void dispose(){disposed=true;if(lease!=null)lease.close();}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){dispose();super.onDismiss(ref,store);}
}
