package com.hexvane.strangematter.research;

import com.hexvane.strangematter.StrangeMatterPlugin;
import com.hexvane.strangematter.ui.LivePageTransport;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** A flat native tablet with the original two connected research maps. */
public final class ResearchTabletPage extends InteractiveCustomUIPage<ResearchPageData> {
    private final ResearchService service;
    private final AtomicBoolean queued=new AtomicBoolean();
    private String category="general",selected="research",message="",renderedCategory,boundPurchase;
    private ResearchTreeLayout.Plan tree;
    private ResearchService.ProfileView shownProfile;
    private LivePageTransport.Lease input;
    private ScheduledFuture<?> pulse;
    private boolean initialized,dirty;
    private volatile boolean disposed;
    public ResearchTabletPage(PlayerRef player,ResearchService service){this(player,service,null);}
    public ResearchTabletPage(PlayerRef player,ResearchService service,String selectedNode){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;
        var node=service.node(selectedNode);
        if(node!=null&&(node.category().equals("general")||service.hasUnlocked(player.getUuid(),"reality_forge"))){selected=node.id();category=node.category();}
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,Store<EntityStore> store){
        if(!initialized){
            input=service.pages().attach(playerRef,this,ref,store,data->handleDataEvent(ref,store,data));
            cmd.append("StrangeMatter/ResearchTablet.ui");initialized=true;
            input.bind(events,"#Close","close","");input.bind(events,"#General","category","general");input.bind(events,"#Forge","category","reality_forge");input.bind(events,"#Journal","journal","");
            for(var type:ResearchType.values()){
                cmd.append("#Points","StrangeMatter/ResearchPoint.ui");String selector="#Points["+type.ordinal()+"]";
                ResearchDisciplineUi.icon(cmd,selector+" #PointIcon",type);cmd.set(selector+" #PointName.Text",type.displayName());cmd.set(selector+" #PointCount.Style.TextColor",type.color());
                cmd.append("#CostRows","StrangeMatter/ResearchDisciplineCost.ui");ResearchDisciplineUi.icon(cmd,"#CostRows["+type.ordinal()+"] #CostIcon",type);
            }
            startPulse(ref,store);
        }
        buildTree(cmd,events);draw(cmd);bindPurchase(events);renderedCategory=category;boundPurchase=selected;
    }
    private void buildTree(UICommandBuilder cmd,UIEventBuilder events){
        tree=ResearchTreeLayout.arrange(service.nodes(),category);cmd.clear("#Nodes");cmd.clear("#Traces");
        position(cmd,"#Graph",0,0,ResearchTreeLayout.WIDTH,tree.height());position(cmd,"#Nodes",0,0,ResearchTreeLayout.WIDTH,tree.height());position(cmd,"#Traces",0,0,ResearchTreeLayout.WIDTH,tree.height());
        for(int i=0;i<tree.traces().size();i++){
            var line=tree.traces().get(i);cmd.append("#Traces","StrangeMatter/ResearchTrace.ui");position(cmd,"#Traces["+i+"]",line.x(),line.y(),line.width(),line.height());
        }
        for(int i=0;i<tree.nodes().size();i++){
            var node=tree.nodes().get(i);String selector="#Nodes["+i+"]";cmd.append("#Nodes","StrangeMatter/ResearchTreeNode.ui");
            position(cmd,selector,node.x(),node.y(),ResearchTreeLayout.NODE_WIDTH,ResearchTreeLayout.NODE_HEIGHT);
            cmd.set(selector+" #NodeIcon.ItemId",icon(node.research()));cmd.set(selector+" #NodeName.Text",ResearchTreeLayout.caption(node.research()));
            var discipline=ResearchType.forResearchNode(node.research().id());
            cmd.set(selector+" #NodeIcon.Visible",discipline==null);cmd.set(selector+" #NodeDisciplineIcon.Visible",discipline!=null);
            if(discipline!=null)ResearchDisciplineUi.icon(cmd,selector+" #NodeDisciplineIcon",discipline);
            input.bind(events,selector+" #SelectNode","select",node.research().id());
        }
    }
    private void bindPurchase(UIEventBuilder events){input.bind(events,"#Purchase","purchase",selected);}
    private void draw(UICommandBuilder cmd){
        var profile=service.profile(playerRef.getUuid());shownProfile=profile;
        for(var type:ResearchType.values())cmd.set("#Points["+type.ordinal()+"] #PointCount.Text",String.valueOf(profile.points().get(type)));
        cmd.set("#Scanned.Text",profile.scannedCount()+" field observations recorded");boolean forge=profile.unlocked().contains("reality_forge");
        cmd.set("#Forge.Disabled",!forge);cmd.set("#Forge.TooltipText",forge?"Advanced machines, containment and equipment.":"Complete Reality Forge research to open this circuit.");
        cmd.set("#GeneralTab.Background",category.equals("general")?"#6bd8d4":"#324552");cmd.set("#ForgeTab.Background",category.equals("reality_forge")?"#b394df":"#393752");
        cmd.set("#CategoryTitle.Text",category.equals("general")?"FOUNDATIONS AND ANOMALIES":"REALITY FORGE");
        cmd.set("#NodeCount.Text",tree.nodes().stream().filter(n->profile.unlocked().contains(n.research().id())).count()+" / "+tree.nodes().size()+" unlocked");
        cmd.set("#Journal.Disabled",StrangeMatterPlugin.instance()==null||StrangeMatterPlugin.instance().progression()==null);
        for(int i=0;i<tree.traces().size();i++){
            var line=tree.traces().get(i);boolean connected=profile.unlocked().contains(line.parent()),complete=profile.unlocked().contains(line.child());
            cmd.set("#Traces["+i+"].Background",complete?"#45998d":connected?"#655779":"#263e4a");
        }
        for(int i=0;i<tree.nodes().size();i++){
            var node=tree.nodes().get(i).research();String selector="#Nodes["+i+"]";
            boolean unlocked=profile.unlocked().contains(node.id()),ready=profile.unlocked().containsAll(node.prerequisites());
            String color=unlocked?"#70dfc3":ready?"#b39ade":"#516575";
            cmd.set(selector+" #NodeOutline.Background",selected.equals(node.id())?"#8cffff":color);cmd.set(selector+" #NodeLamp.Background",color);
            cmd.set(selector+" #NodeFace.Background",selected.equals(node.id())?"#194550":unlocked?"#15383e":"#102932");
            cmd.set(selector+" #NodeName.Style.TextColor",selected.equals(node.id())?"#a7ffff":ready||unlocked?"#a3bac8":"#617988");
            cmd.set(selector+" #SelectNode.TooltipText",tooltip(node,profile));
        }
        var node=service.node(selected);boolean unlocked=profile.unlocked().contains(node.id()),ready=profile.unlocked().containsAll(node.prerequisites());
        cmd.set("#DetailIcon.ItemId",icon(node));cmd.set("#NodeTitle.Text",node.name());cmd.set("#Description.Text",node.description());
        var discipline=ResearchType.forResearchNode(node.id());cmd.set("#DetailIcon.Visible",discipline==null);cmd.set("#DetailDisciplineIcon.Visible",discipline!=null);
        if(discipline!=null)ResearchDisciplineUi.icon(cmd,"#DetailDisciplineIcon",discipline);
        cmd.set("#NodeStatus.Text",unlocked?"UNLOCKED":ready?"READY TO RESEARCH":"PREREQUISITE NEEDED");cmd.set("#NodeStatus.Style.TextColor",unlocked?"#70dfc3":ready?"#b39ade":"#a28794");
        String prerequisites=node.prerequisites().stream().map(service::researchName).collect(Collectors.joining(", "));
        cmd.set("#Prerequisites.Text",prerequisites.isEmpty()?"Foundation research":"Requires: "+prerequisites);
        cmd.set("#Costs.Text",unlocked?"Research complete. Its field guide is available below.":costText(node,profile));
        boolean showCosts=!unlocked&&!node.costs().isEmpty();cmd.set("#Costs.Visible",!showCosts);cmd.set("#CostRows.Visible",showCosts);
        int costIndex=0;for(var type:ResearchType.values()){
            String row="#CostRows["+type.ordinal()+"]";boolean present=node.costs().containsKey(type);cmd.set(row+".Visible",present);
            if(present){position(cmd,row,0,costIndex++*19,328,19);cmd.set(row+" #CostLabel.Text",type.displayName()+"  "+profile.points().get(type)+" / "+node.costs().get(type));cmd.set(row+" #CostLabel.Style.TextColor",profile.points().get(type)>=node.costs().get(type)?"#82e1e0":"#efaabe");}
        }
        String unavailable=service.availability(playerRef.getUuid(),node);
        cmd.set("#Purchase.Disabled",!unlocked&&unavailable!=null);cmd.set("#Purchase.Text",unlocked?"READ FIELD GUIDE":"CREATE RESEARCH NOTE");
        cmd.set("#Message.Text",!message.isEmpty()?message:unlocked?"Read the guide for instructions and crafting recipes.":unavailable!=null?unavailable:"Create a note, then complete its experiment at a Research Machine.");
    }
    private String tooltip(ResearchNode node,ResearchService.ProfileView profile){
        boolean unlocked=profile.unlocked().contains(node.id()),ready=profile.unlocked().containsAll(node.prerequisites());
        String prerequisites=node.prerequisites().stream().filter(id->!profile.unlocked().contains(id)).map(service::researchName).collect(Collectors.joining(", "));
        return node.name()+"\n"+(unlocked?"Unlocked":ready?"Ready to research":"Complete first: "+prerequisites)+"\n\n"+node.description()
            +(unlocked?"\n\nSelect this node, then open its field guide.":"\n\n"+costText(node,profile));
    }
    private static String costText(ResearchNode node,ResearchService.ProfileView profile){
        if(node.costs().isEmpty())return "Available from the beginning";
        return Arrays.stream(ResearchType.values()).filter(node.costs()::containsKey).map(type->type.displayName()+"  "+profile.points().get(type)+" / "+node.costs().get(type)).collect(Collectors.joining("\n"));
    }
    private static String icon(ResearchNode node){String id=ResearchTreeLayout.icon(node);return Item.getAssetMap().getAsset(id)==null?ResearchService.NOTE_ITEM:id;}
    private static void position(UICommandBuilder cmd,String selector,int x,int y,int width,int height){var a=new Anchor();a.setLeft(Value.of(x));a.setTop(Value.of(y));a.setWidth(Value.of(width));a.setHeight(Value.of(height));cmd.setObject(selector+".Anchor",a);}
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ResearchPageData data){
        if(disposed||input==null||!input.accepts(data)||data.action==null)return;
        switch(data.action){
            case "close"->{dispose();close();return;}
            case "journal"->{var plugin=StrangeMatterPlugin.instance();if(plugin!=null&&plugin.progression()!=null){dispose();plugin.progression().open(playerRef,store);}return;}
            case "category"->{
                if("general".equals(data.value)){category="general";selected="research";}
                else if("reality_forge".equals(data.value)&&service.hasUnlocked(playerRef.getUuid(),"reality_forge")){category="reality_forge";selected="reality_forge_category";}
                else return;
                message="";
            }
            case "select"->{var node=service.node(data.value);if(node==null||!node.category().equals(category))return;selected=node.id();message="";}
            case "purchase"->{
                if(!selected.equals(data.value)){message="Selection changed. Review the selected research first.";break;}
                if(service.hasUnlocked(playerRef.getUuid(),selected)){dispose();service.openInfo(playerRef,store,selected);return;}
                message=service.purchase(playerRef.getUuid(),selected,ResearchService.inventory(store,ref));
                if(message.startsWith("Created ")){
                    var transform=store.getComponent(ref,com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
                    if(transform!=null)com.hexvane.strangematter.effects.GadgetEffects.sound(store.getExternalData().getWorld(),"SM_Research_Note_Create_SFX",transform.getPosition());
                }
            }
            default->{return;}
        }
        dirty=true;renderFrame(ref,store);
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,String raw){if(input!=null)input.receiveFromNative(raw);}
    private void renderFrame(Ref<EntityStore> ref,Store<EntityStore> store){
        if(disposed)return;
        if(!ref.isValid()||ref.getStore()!=store||!ref.equals(playerRef.getReference())){dispose();return;}
        var player=store.getComponent(ref,Player.getComponentType());if(player==null||player.getPageManager().getCustomPage()!=this){dispose();return;}
        if(!input.ready())return;
        if(!dirty&&service.profile(playerRef.getUuid()).equals(shownProfile))return;
        var cmd=new UICommandBuilder();var events=new UIEventBuilder();
        if(!category.equals(renderedCategory))buildTree(cmd,events);draw(cmd);
        if(!selected.equals(boundPurchase))bindPurchase(events);
        if(input.send(cmd,events)){renderedCategory=category;boundPurchase=selected;dirty=false;}
    }
    private void startPulse(Ref<EntityStore> ref,Store<EntityStore> store){
        try{pulse=service.timer.scheduleAtFixedRate(()->{
            if(disposed||!queued.compareAndSet(false,true))return;
            try{store.getExternalData().getWorld().execute(()->{try{renderFrame(ref,store);}finally{queued.set(false);}});}
            catch(RuntimeException stopped){queued.set(false);dispose();}
        },125,125,TimeUnit.MILLISECONDS);}catch(RuntimeException stopped){dispose();}
    }
    private void dispose(){disposed=true;if(pulse!=null)pulse.cancel(false);if(input!=null)input.close();}
    @Override public void onDismiss(Ref<EntityStore> ref,Store<EntityStore> store){dispose();super.onDismiss(ref,store);}
    /** Static reading pages retain their existing native event bindings. */
    static void bind(UIEventBuilder events,String selector,String action,String value){events.addEventBinding(CustomUIEventBindingType.Activating,selector,EventData.of("Action",action).append("Value",value),false);}
}
