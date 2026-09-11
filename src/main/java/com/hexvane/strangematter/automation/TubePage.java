package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.effects.GadgetEffects;
import com.hexvane.strangematter.research.ResearchPageData;
import com.hexvane.strangematter.util.InventoryOps;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.ui.builder.*;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.*;

/** Ghost filters copy item descriptions only. The page never removes a sample item. */
final class TubePage extends InteractiveCustomUIPage<ResearchPageData>{
    private final TubeService service;private final World world;private final TubeEndpoints.Position position;private final UUID identity;
    private int face,step=1;private TubeConfiguration draft;private boolean built;private String message="Choose a face and edit its settings. Apply to save this connection.";
    TubePage(PlayerRef player,TubeService service,World world,TubeService.Node node){
        super(player,CustomPageLifetime.CanDismissOrCloseThroughInteraction,ResearchPageData.CODEC);this.service=service;this.world=world;position=node.position();identity=node.component().id();draft=node.component().face(0);
    }
    @Override public void build(Ref<EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,Store<EntityStore> store){
        if(!built){cmd.append("StrangeMatter/TubeConfig.ui");built=true;
            bind(events,"#Close","close","");bind(events,"#Apply","apply","");
            for(int i=0;i<6;i++)bind(events,"#Face"+i,"face",""+i);
            for(String key:List.of("Section","Match","Filter","Resource","Step"))bind(events,"#"+key,key.toLowerCase(Locale.ROOT),"");
            for(String mode:List.of("Off","Take","Send"))bind(events,"#"+mode,"mode",mode);
            for(int i=0;i<5;i++){bind(events,"#Sample"+i,"sample",""+i);bind(events,"#Clear"+i,"clear",""+i);}
            for(String key:List.of("Leave","Fill","Priority","Batch")){bind(events,"#"+key+"Minus","adjust",key+":-1");bind(events,"#"+key+"Plus","adjust",key+":1");}
        }
        var node=service.current(world,position);if(node==null){close();return;}
        cmd.set("#Title.Text","GRAVITIC TUBE");cmd.set("#Message.Text",message);cmd.set("#Status.Text",service.status(world,position));
        for(int i=0;i<6;i++)cmd.set("#Face"+i+".Text",(i==face?"> ":"")+TubeEndpoints.NAMES[i]+" "+modeLabel(node.component().face(i).mode));
        cmd.set("#Selected.Text",TubeEndpoints.NAMES[face]+" connection");
        cmd.set("#Off.Text",draft.mode==TubeConfiguration.Mode.OFF?"> OFF":"OFF");
        cmd.set("#Take.Text",draft.mode==TubeConfiguration.Mode.EXTRACT?"> TAKE":"TAKE");
        cmd.set("#Send.Text",draft.mode==TubeConfiguration.Mode.INSERT?"> SEND":"SEND");
        cmd.set("#ModeHelp.Text",switch(draft.mode){case OFF->"This connection is off. No items move through this face.";case EXTRACT->"TAKE pulls items from this container into the tube. Its connection glows cyan.";case INSERT->"SEND delivers items into this container. Its connection glows purple.";});
        var ports=service.ports(world,position.offset(face));var port=ports.stream().filter(p->p.port().section().equals(draft.section)).findFirst().orElse(null);
        cmd.set("#Section.Text",port==null?"Section: "+draft.section:port.port().label());
        cmd.set("#Section.Disabled",ports.isEmpty());cmd.set("#Section.TooltipText","Choose which part of the connected container to use.");
        cmd.set("#Neighbor.Text",TubeService.tube(world,position.offset(face))?"Tube connection is automatic":ports.isEmpty()?"No loaded container on this face":String.join(" / ",ports.stream().map(p->p.port().label()).toList()));
        cmd.set("#Match.Text",switch(draft.match){case ITEM->"Same item";case EXACT->"Same item and details";case RESOURCE->"Resource family";});
        cmd.set("#Filter.Text",draft.exclude?"Exclude matching items":"Allow matching items");cmd.set("#Resource.Text",draft.resource.isEmpty()?"COPY HELD RESOURCE FAMILY":InventoryOps.label(draft.resource));
        cmd.set("#Resource.Visible",draft.match==TubeConfiguration.Match.RESOURCE);
        cmd.set("#SamplesGroup.Visible",draft.match!=TubeConfiguration.Match.RESOURCE);cmd.set("#ResourceGroup.Visible",draft.match==TubeConfiguration.Match.RESOURCE);
        cmd.set("#Match.TooltipText","Choose item type, exact item details, or a shared resource family.");cmd.set("#Filter.TooltipText","Allow only these matches, or exclude them. With no samples or family set, every item is allowed.");
        cmd.set("#FilterHelp.Text",draft.match==TubeConfiguration.Match.RESOURCE?"Copy a resource family, such as wood or fuel, from your held item.":"Click a sample to copy your held item. No samples means any item.");
        var held=InventoryComponent.getItemInHand(store,ref);cmd.set("#HeldIcon.Visible",!ItemStack.isEmpty(held));if(!ItemStack.isEmpty(held))cmd.set("#HeldIcon.ItemId",held.getItemId());
        cmd.set("#HeldName.Text",ItemStack.isEmpty(held)?"No item held":FactoryRecipeCategories.itemName(held.getItemId(),playerRef.getLanguage()));
        for(int i=0;i<5;i++){
            cmd.set("#Sample"+i+".Disabled",draft.match==TubeConfiguration.Match.RESOURCE);cmd.set("#Clear"+i+".Disabled",draft.match==TubeConfiguration.Match.RESOURCE);
            var stack=TubeStacks.decode(draft.samples[i]);cmd.set("#Icon"+i+".Visible",!ItemStack.isEmpty(stack));
            if(!ItemStack.isEmpty(stack))cmd.set("#Icon"+i+".ItemId",stack.getItemId());
            cmd.set("#SamplePrompt"+i+".Visible",ItemStack.isEmpty(stack));
            String label=ItemStack.isEmpty(stack)?"Held item":FactoryRecipeCategories.itemName(stack.getItemId(),playerRef.getLanguage());cmd.set("#SampleLabel"+i+".Text",label);
            cmd.set("#Sample"+i+".TooltipText",ItemStack.isEmpty(stack)?"Copy the item you are holding. It will not be consumed.":label+". Click to replace this sample with your held item.");
        }
        cmd.set("#LeaveValue.Text",""+draft.leaveBehind);cmd.set("#FillValue.Text",draft.fillUpTo==0?"Unlimited":""+draft.fillUpTo);
        cmd.set("#PriorityValue.Text",""+draft.priority);cmd.set("#BatchValue.Text",""+draft.batch);cmd.set("#Step.Text","Stock step: "+step);cmd.set("#Step.TooltipText","Change the amount added or removed by the source and destination stock buttons. Priority and batch always change by one.");
    }
    @Override public void handleDataEvent(Ref<EntityStore> ref,Store<EntityStore> store,ResearchPageData data){
        if("close".equals(data.action)){close();return;}
        if(store.getExternalData().getWorld()!=world){close();return;}
        var node=service.current(world,position);if(node==null||!identity.equals(node.component().id())||!service.canConfigure(playerRef,store,node)){close();return;}
        try{switch(data.action==null?"":data.action){
            case "face"->{face=Math.max(0,Math.min(5,Integer.parseInt(data.value)));draft=node.component().face(face);message="Changes apply to "+TubeEndpoints.NAMES[face]+" only. Apply before choosing another face.";
                GadgetEffects.particle(world,"SM_Tube_Field",TubeService.center(position).add(TubeEndpoints.DX[face]*.6,TubeEndpoints.DY[face]*.6,TubeEndpoints.DZ[face]*.6));}
            case "mode"->draft.mode=switch(data.value){case "Take"->TubeConfiguration.Mode.EXTRACT;case "Send"->TubeConfiguration.Mode.INSERT;default->TubeConfiguration.Mode.OFF;};
            case "match"->draft.match=TubeConfiguration.Match.values()[(draft.match.ordinal()+1)%3];
            case "filter"->draft.exclude=!draft.exclude;
            case "section"->{var ports=service.ports(world,position.offset(face));if(!ports.isEmpty()){int index=-1;for(int i=0;i<ports.size();i++)if(ports.get(i).port().section().equals(draft.section))index=i;draft.section=ports.get((index+1)%ports.size()).port().section();}}
            case "step"->step=step>=1000?1:step*10;
            case "resource"->{var held=InventoryComponent.getItemInHand(store,ref);if(!ItemStack.isEmpty(held)&&held.getItem().getResourceTypes()!=null&&held.getItem().getResourceTypes().length>0){
                var types=held.getItem().getResourceTypes();int index=-1;for(int i=0;i<types.length;i++)if(types[i].id.equals(draft.resource))index=i;draft.resource=types[(index+1)%types.length].id;
            }else message="Hold an item with a resource family, such as wood, ore or fuel.";}
            case "sample"->{int index=Integer.parseInt(data.value);if(index>=0&&index<5){var held=InventoryComponent.getItemInHand(store,ref);draft.samples[index]=ItemStack.isEmpty(held)?null:TubeStacks.encode(TubeStacks.quantity(held,1));}}
            case "clear"->{int index=Integer.parseInt(data.value);if(index>=0&&index<5)draft.samples[index]=null;}
            case "adjust"->{String[] parts=data.value.split(":");int direction=Integer.parseInt(parts[1]);switch(parts[0]){
                case "Leave"->draft.leaveBehind=Math.clamp(draft.leaveBehind+direction*step,0,100000);
                case "Fill"->draft.fillUpTo=Math.clamp(draft.fillUpTo+direction*step,0,100000);
                case "Priority"->draft.priority=Math.clamp(draft.priority+direction,-10,10);
                case "Batch"->draft.batch=Math.clamp(draft.batch+direction,1,5);
                default->{} }}
            case "apply"->{if(service.configure(playerRef,store,world,identity,position,face,draft))message="Saved "+TubeEndpoints.NAMES[face]+" connection. Item samples were not consumed.";else message="The tube changed or is no longer within reach.";}
            default->{} }
        }catch(IllegalArgumentException ex){message="These settings could not be applied.";}
        var cmd=new UICommandBuilder();var events=new UIEventBuilder();build(ref,cmd,events,store);sendUpdate(cmd,events,false);
    }
    private static String modeLabel(TubeConfiguration.Mode mode){return switch(mode){case OFF->"OFF";case EXTRACT->"TAKE";case INSERT->"SEND";};}
    private static void bind(UIEventBuilder events,String selector,String action,String value){events.addEventBinding(CustomUIEventBindingType.Activating,selector,EventData.of("Action",action).append("Value",value),false);}
}
