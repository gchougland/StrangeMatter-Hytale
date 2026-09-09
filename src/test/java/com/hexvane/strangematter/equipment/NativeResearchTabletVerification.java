package com.hexvane.strangematter.equipment;

import com.google.gson.JsonParser;
import com.hexvane.strangematter.research.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Native serialized tree pages, actual inventory and input routing under held acknowledgements. */
public final class NativeResearchTabletVerification {
    public static void verify(World world,ResearchService research)throws Exception{
        try(var player=NativePlayerFixture.create(world,"NativeResearchTablet",new Vector3d(8,18,9));var ui=new Pages(player)){
            var id=player.owner().getUuid();var page=new ResearchTabletPage(player.owner(),research,"hoverboard");var initial=ui.open(page);
            require(field(page,"category").equals("general"),"Locked advanced category cannot be entered by constructor selection");
            require(count(initial,"#SelectNode")==15,"All original foundation and anomaly nodes have stable event IDs");
            require(countCommands(initial,"#NodeIcon.ItemId")==15&&countCommands(initial,"#PointIcon.Background")==6,"Actual item nodes and original discipline textures reach the native page packet");
            for(var type:ResearchType.values()){
                require(has(initial,"#Points["+type.ordinal()+"] #PointIcon.Background",type.uiIconPath()),"Point balance uses the exact original "+type+" symbol");
                require(has(initial,"#CostRows["+type.ordinal()+"] #CostIcon.Background",type.uiIconPath()),"Cost row uses the same typed original symbol");
                var topic=research.nodes().stream().filter(n->ResearchType.forResearchNode(n.id())==type).findFirst().orElseThrow();
                String selector=node(initial,topic.id()).selector.replace("#SelectNode","#NodeDisciplineIcon.Background");
                require(has(initial,selector,type.uiIconPath()),"Anomaly discipline node uses its original symbol");
            }
            require(has(initial,"#Forge.Disabled","true"),"Advanced category starts locked");
            require(countCommands(initial,"#SelectNode.TooltipText")==15,"Every node receives a native hover tooltip");
            require(has(initial,"#Nodes[0] #SelectNode.TooltipText","Select this node, then open its field guide."),"Unlocked tooltip describes the actual two step guide control");
            require(Arrays.stream(initial.commands).anyMatch(c->c.selector!=null&&c.selector.startsWith("#Traces[")&&c.selector.endsWith(".Anchor")),"Native circuit trace anchors are present");
            require(ui.pending()>0,"Real PageManager acknowledgement is held");
            ui.click(page,binding(initial,"#Forge"));require(field(page,"category").equals("general"),"An injected locked tab click cannot bypass category research");
            var gravity=node(initial,"gravity_anomalies");ui.click(page,gravity);ui.click(page,binding(initial,"#Purchase"));
            require(field(page,"selected").equals("gravity_anomalies")&&field(page,"message").toString().contains("Selection changed"),"Stale displayed Purchase cannot act on the newly selected node");
            require(notes(player,research,"gravity_anomalies")==0,"Stale action spends no observations and creates no note");
            int before=player.packets().ofType(CustomPage.class).size();ui.frame(page);require(before==player.packets().ofType(CustomPage.class).size(),"Tree visual state waits for the held acknowledgement");
            ui.ackAll();var selected=ui.frame(page);
            require(value(binding(selected,"#Purchase")).equals("gravity_anomalies")&&has(selected,"#Purchase.Disabled","true"),"Selected title and disabled exact research ID share the update");
            require(has(selected,"#CostRows[2] #CostLabel.Text","Gravity  0 / 5")&&has(selected,"#CostRows.Visible","true"),"Original cost and actual observation balance are visible beside the discipline icon");
            require(has(selected,"#DetailDisciplineIcon.Background",ResearchType.GRAVITY.uiIconPath()),"Selected anomaly details use the gravity symbol");
            research.addPoints(id,ResearchType.GRAVITY,5);ui.ackAll();var funded=ui.frame(page);
            require(has(funded,"#Purchase.Disabled","false"),"New observations enable the selected note");ui.click(page,binding(selected,"#Purchase"));
            require(notes(player,research,"gravity_anomalies")==1&&research.points(id,ResearchType.GRAVITY)==0,"Purchase under pending ACK creates the real note and spends exactly five observations");
            require(!research.hasUnlocked(id,"gravity_anomalies"),"Writing the note does not skip the Research Machine experiment");
            ui.click(page,node(initial,"research"));ui.ackAll();var guideSelection=ui.frame(page);ui.click(page,binding(guideSelection,"#Purchase"));
            require(player.player().getPageManager().getCustomPage() instanceof ResearchInfoPage,"Unlocked field guide remains available from the selected node");
            ui.ackAll();var guide=ui.last();
            for(var type:ResearchType.values())require(has(guide,"#GuideDisciplines["+type.ordinal()+"] #DisciplineIcon.Background",type.uiIconPath()),"Field guide keeps a labeled original discipline legend");
            ui.nativeClick(binding(guide,"#Back"));
            require(player.player().getPageManager().getCustomPage() instanceof ResearchTabletPage,"Native guide Back returns to the tablet tree");
            page=(ResearchTabletPage)player.player().getPageManager().getCustomPage();var returned=ui.last();
            research.unlock(id,"reality_forge",true);ui.ackAll();var unlocked=ui.frame(page);require(has(unlocked,"#Forge.Disabled","false"),"Completed research unlocks the category without reopening");
            ui.click(page,binding(returned,"#Forge"));ui.click(page,node(returned,"gravity_anomalies"));
            require(field(page,"selected").equals("reality_forge_category"),"A stale foundation node event cannot select across the changed category");
            ui.ackAll();var advanced=ui.frame(page);require(count(advanced,"#SelectNode")==11,"Advanced tree rebuild and all eleven exact node bindings arrive together");
            require(has(advanced,"#CategoryTitle.Text","REALITY FORGE"),"Category heading matches its graph");
            ui.click(page,node(advanced,"hoverboard"));ui.ackAll();var locked=ui.frame(page);require(has(locked,"#Purchase.Disabled","true"),"Containment prerequisite still gates Hoverboard notes");
            ui.click(page,binding(locked,"#Purchase"));require(notes(player,research,"hoverboard")==0,"Disabled controls cannot bypass the actual prerequisite transaction");
            research.unlock(id,"containment_basics",true);research.addPoints(id,ResearchType.ENERGY,15);research.addPoints(id,ResearchType.GRAVITY,10);
            ui.ackAll();ui.frame(page);ui.click(page,binding(locked,"#Purchase"));
            require(notes(player,research,"hoverboard")==1&&research.points(id,ResearchType.ENERGY)==0&&research.points(id,ResearchType.GRAVITY)==0,"Advanced note uses the original two exact discipline costs");
            ui.click(page,binding(returned,"#Close"));require(player.player().getPageManager().getCustomPage()==null,"Close works while a graph update is awaiting ACK");ui.ackAll();
            var event=new CustomPageEvent(CustomPageEventType.Data,node(advanced,"hoverboard").data);require(PacketAdapters.__handleInbound(player.packets(),event),"Closed tree events remain scoped and consumed");
            require(player.player().getPageManager().getCustomPage()==null,"Stale closed page input cannot reopen or mutate another page");
        }
        System.out.println("NATIVE_RESEARCH_TABLET_VERIFICATION_PASSED: 26 connected nodes, real icons and tooltips, category gating, live ID selection and exact purchases under held ACKs, stale selection rejection, original observation costs, field guide Back and clean Close.");
    }
    private static int notes(NativePlayerFixture player,ResearchService service,String id){int count=0;for(short slot=0;slot<player.inventory().getCapacity();slot++){ItemStack item=player.inventory().getItemStack(slot);var node=service.noteNode(item);if(node!=null&&node.id().equals(id))count+=item.getQuantity();}return count;}
    private static long count(CustomPage page,String suffix){return Arrays.stream(page.eventBindings).filter(b->b.selector.endsWith(suffix)).count();}
    private static long countCommands(CustomPage page,String suffix){return Arrays.stream(page.commands).filter(c->c.selector!=null&&c.selector.endsWith(suffix)).count();}
    private static CustomUIEventBinding binding(CustomPage page,String selector){return Arrays.stream(page.eventBindings).filter(b->b.selector.equals(selector)).findFirst().orElseThrow();}
    private static CustomUIEventBinding node(CustomPage page,String id){return Arrays.stream(page.eventBindings).filter(b->b.selector.endsWith("#SelectNode")&&value(b).equals(id)).findFirst().orElseThrow();}
    private static String value(CustomUIEventBinding binding){return JsonParser.parseString(binding.data).getAsJsonObject().get("Value").getAsString();}
    private static boolean has(CustomPage page,String selector,String text){return Arrays.stream(page.commands).anyMatch(c->selector.equals(c.selector)&&c.data!=null&&c.data.contains(text));}
    private static Object field(Object value,String name)throws Exception{var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Pages implements AutoCloseable{
        final NativePlayerFixture player;int observed;
        Pages(NativePlayerFixture player){this.player=player;observed=player.packets().packets.size();}
        CustomPage open(CustomUIPage page){player.player().getPageManager().openCustomPage(player.ref(),player.store(),page);observe();return last();}
        void observe(){while(observed<player.packets().packets.size()){var packet=player.packets().packets.get(observed++);if(packet instanceof CustomPage||packet instanceof SetPage)PacketAdapters.__handleOutbound(player.packets(),packet);}}
        CustomPage last(){var page=player.packets().ofType(CustomPage.class).getLast();var bytes=MemorySegment.ofArray(new byte[page.computeSize()]);page.serialize(bytes,0);return CustomPage.toObject(bytes);}
        int pending()throws Exception{return ((AtomicInteger)field(player.player().getPageManager(),"customPageRequiredAcknowledgments")).get();}
        void click(ResearchTabletPage page,CustomUIEventBinding binding)throws Exception{
            var event=new CustomPageEvent(CustomPageEventType.Data,binding.data);var bytes=MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(bytes,0);
            require(PacketAdapters.__handleInbound(player.packets(),CustomPageEvent.toObject(bytes)),"Native adapter accepts this tree's wire decoded event");
            var lease=field(page,"input");var drain=lease.getClass().getDeclaredMethod("drain");drain.setAccessible(true);drain.invoke(lease);observe();
        }
        void nativeClick(CustomUIEventBinding binding)throws Exception{
            require(pending()==0,"Static guide native input has no pending ACK");var event=new CustomPageEvent(CustomPageEventType.Data,binding.data);
            require(!PacketAdapters.__handleInbound(player.packets(),event),"Static reading page events remain native");player.player().getPageManager().handleEvent(player.ref(),player.store(),event);observe();
        }
        CustomPage frame(ResearchTabletPage page)throws Exception{var method=ResearchTabletPage.class.getDeclaredMethod("renderFrame",com.hypixel.hytale.component.Ref.class,com.hypixel.hytale.component.Store.class);method.setAccessible(true);method.invoke(page,player.ref(),player.store());observe();return last();}
        void ackAll()throws Exception{observe();while(pending()>0){var event=new CustomPageEvent(CustomPageEventType.Acknowledge,null);require(!PacketAdapters.__handleInbound(player.packets(),event),"Native ACK passes unchanged");player.player().getPageManager().handleEvent(player.ref(),player.store(),event);}}
        @Override public void close()throws Exception{player.player().getPageManager().setPage(player.ref(),player.store(),Page.None);observe();ackAll();}
    }
}
