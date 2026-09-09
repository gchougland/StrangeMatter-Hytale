package com.hexvane.strangematter.equipment;

import com.google.gson.JsonParser;
import com.hexvane.strangematter.machine.MachinePage;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.ResearchMachinePage;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.research.ResearchSession;
import com.hexvane.strangematter.research.ResearchType;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** Real page handlers, native wire envelopes, inventory and held acknowledgements. */
public final class NativeLaboratorySelectionVerification {
    public static void verify(World world,ResearchService research,MachineService machines)throws Exception {
        var researchAt=new Vector3i(8,18,8);var forgeAt=new Vector3i(12,18,8);
        world.setBlock(researchAt.x,researchAt.y,researchAt.z,"SM_Research_Machine");
        world.setBlock(forgeAt.x,forgeAt.y,forgeAt.z,"SM_Reality_Forge");
        var forge=machines.register(world,forgeAt,"SM_Reality_Forge");
        try(var fixture=NativePlayerFixture.create(world,"NativeLaboratorySelection",new Vector3d(10,18,9))) {
            var ui=new Pages(fixture);
            try {
                research.addPoints(fixture.owner().getUuid(),ResearchType.GRAVITY,5);
                research.addPoints(fixture.owner().getUuid(),ResearchType.ENERGY,5);
                require(research.purchase(fixture.owner().getUuid(),"gravity_anomalies",fixture.inventory()).startsWith("Created "),"First real inventory note purchased");
                require(research.purchase(fixture.owner().getUuid(),"energy_anomalies",fixture.inventory()).startsWith("Created "),"Second real inventory note purchased");
                var page=new ResearchMachinePage(fixture.owner(),research,researchAt);
                var initial=ui.open(page);
                require(bindings(initial,"#SelectNote")==2,"Every inventory note has a stable selectable token binding");
                require(Arrays.stream(initial.commands).filter(c->c.selector!=null&&c.selector.endsWith("#NoteRowIcon.ItemId")).count()==2,"Native note rows carry actual item icons");
                var oldInsert=binding(initial,"#InsertNote");String first=value(oldInsert);
                var choose=Arrays.stream(initial.eventBindings).filter(b->b.selector.endsWith("#SelectNote")&&!value(b).equals(first)).findFirst().orElseThrow();
                String selected=value(choose);
                require(ui.pending()>0,"Initial window is still awaiting its real native acknowledgement");
                ui.click(page,choose);
                require(selected.equals(field(page,"selectedNote")),"Note selection is processed while the initial frame is unacknowledged");
                ui.click(page,oldInsert);
                require(field(page,"session")==null&&field(page,"message").toString().contains("selection changed"),"An old displayed Insert token cannot load the newly selected note");
                research.addPoints(fixture.owner().getUuid(),ResearchType.SPACE,5);
                research.purchase(fixture.owner().getUuid(),"spatial_anomalies",fixture.inventory());
                ui.click(page,binding(initial,"#RefreshNotes"));
                int before=fixture.packets().ofType(CustomPage.class).size();ui.frame(page);
                require(before==fixture.packets().ofType(CustomPage.class).size(),"Inventory refresh waits for the current frame instead of losing structural row changes");
                ui.ackAll();var updated=ui.frame(page);
                require(bindings(updated,"#SelectNote")==3&&value(binding(updated,"#InsertNote")).equals(selected),"Refreshed row structure and exact selected Insert token share one native packet");
                require(has(updated,"#NoteTitle.Text",research.noteNode(findNote(fixture,selected)).name()),"Selected title is delivered with its matching token");
                ui.click(page,binding(updated,"#InsertNote"));
                var session=(ResearchSession)field(page,"session");
                require(session!=null&&session.node().id().equals(research.noteNode(findNote(fixture,selected)).id()),"Matching Insert opens the selected real note without consuming it");
                require(!ItemStack.isEmpty(findNote(fixture,selected)),"Inserting does not consume a note before research succeeds");
                ui.click(page,binding(initial,"#Close"));
                require(fixture.player().getPageManager().getCustomPage()==null,"Close works while the note detail frame is awaiting ACK");ui.ackAll();

                var forgePage=new MachinePage(fixture.owner(),machines,forge);var forgeInitial=ui.open(forgePage);
                require(bindings(forgeInitial,"#SelectRecipe")==machines.recipes.size(),"Scrollable forge binds every row to its stable recipe ID");
                require(Arrays.stream(forgeInitial.commands).filter(c->c.selector!=null&&c.selector.endsWith("#RecipeIcon.ItemId")).count()==machines.recipes.size(),"Every forge row contains its actual output icon");
                var target=machines.recipes.stream().filter(r->r.id.equals("levitation_pad")).findFirst().orElseThrow();
                var selectRecipe=Arrays.stream(forgeInitial.eventBindings).filter(b->b.selector.endsWith("#SelectRecipe")&&value(b).equals(target.id)).findFirst().orElseThrow();
                var staleCraft=binding(forgeInitial,"#Craft");ui.click(forgePage,selectRecipe);
                require(machines.recipes.get(machines.selectedRecipeIndex(forge,fixture.owner().getUuid())).id.equals(target.id),"Choosing a locked recipe still remembers it for this player and forge");
                ui.click(forgePage,staleCraft);
                require(forge.recipe.isEmpty()&&field(forgePage,"message").toString().contains("selection changed"),"Stale Craft ID is rejected without starting or reserving a different recipe");
                before=fixture.packets().ofType(CustomPage.class).size();ui.frame(forgePage);
                require(before==fixture.packets().ofType(CustomPage.class).size(),"Forge selection inputs remain live while visual updates await ACK");
                ui.ackAll();var locked=ui.frame(forgePage);
                require(has(locked,"#Recipe.Text","Levitation Pad")&&has(locked,"#Craft.Disabled","true")&&value(binding(locked,"#Craft")).equals(target.id),"Locked recipe detail, disabled craft and matching ID share the same packet");
                ui.click(forgePage,binding(locked,"#Craft"));require(forge.recipe.isEmpty(),"Forging a locked displayed recipe cannot bypass research");
                String needed=research.requiredResearchForItem(target.output);research.unlock(fixture.owner().getUuid(),needed==null?target.research:needed,true);
                for(var cost:target.totalCost().entrySet())fixture.inventory().addItemStack(new ItemStack(cost.getKey(),cost.getValue()),true,false,true);
                ui.ackAll();var ready=ui.frame(forgePage);
                require(has(ready,"#Craft.Disabled","false"),"Unlocked recipe becomes craftable after its actual materials arrive");
                ui.click(forgePage,binding(locked,"#Craft"));
                require(forge.recipe.equals(target.id)&&!forge.reservedInputs.isEmpty(),"Matching Craft event reserves the real ingredients even with a visual ACK pending");
                ui.click(forgePage,binding(forgeInitial,"#Close"));ui.ackAll();
                var reopened=new MachinePage(fixture.owner(),machines,forge);var reopening=ui.open(reopened);
                require(has(reopening,"#Recipe.Text","Levitation Pad")&&value(binding(reopening,"#Craft")).equals(target.id),"Reopening restores the saved recipe and its matching transaction binding");
                require(has(reopening,"#ForgeScan.Visible","true"),"Existing coalescence animation remains active while the recipe list is shown");
                ui.click(reopened,binding(reopening,"#Close"));ui.ackAll();
            } finally {ui.close();}
        } finally {
            world.setBlock(researchAt.x,researchAt.y,researchAt.z,"Empty");
            world.setBlock(forgeAt.x,forgeAt.y,forgeAt.z,"Empty");machines.removed(world,forgeAt);
        }
        System.out.println("NATIVE_LABORATORY_SELECTION_VERIFICATION_PASSED: real icon rows, note token selection/refresh/insert, stale note and recipe rejection, research lock, real crafting reservation, selection persistence, active chamber and native Close under held ACKs.");
    }
    private static ItemStack findNote(NativePlayerFixture player,String token){for(short slot=0;slot<player.inventory().getCapacity();slot++){var item=player.inventory().getItemStack(slot);if(token.equals(ResearchService.noteToken(item)))return item;}return ItemStack.EMPTY;}
    private static long bindings(CustomPage page,String ending){return Arrays.stream(page.eventBindings).filter(b->b.selector.endsWith(ending)).count();}
    private static CustomUIEventBinding binding(CustomPage page,String selector){return Arrays.stream(page.eventBindings).filter(b->b.selector.equals(selector)).findFirst().orElseThrow();}
    private static String value(CustomUIEventBinding binding){return JsonParser.parseString(binding.data).getAsJsonObject().get("Value").getAsString();}
    private static boolean has(CustomPage page,String selector,String text){return Arrays.stream(page.commands).anyMatch(c->selector.equals(c.selector)&&c.data!=null&&c.data.contains(text));}
    private static Object field(Object value,String name)throws Exception{var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}

    private static final class Pages implements AutoCloseable {
        final NativePlayerFixture player;int observed;
        Pages(NativePlayerFixture player){this.player=player;observed=player.packets().packets.size();}
        CustomPage open(CustomUIPage page){player.player().getPageManager().openCustomPage(player.ref(),player.store(),page);observe();return last();}
        void observe(){while(observed<player.packets().packets.size()){var packet=player.packets().packets.get(observed++);if(packet instanceof CustomPage||packet instanceof SetPage)PacketAdapters.__handleOutbound(player.packets(),packet);}}
        CustomPage last(){var page=player.packets().ofType(CustomPage.class).getLast();var bytes=MemorySegment.ofArray(new byte[page.computeSize()]);page.serialize(bytes,0);return CustomPage.toObject(bytes);}
        int pending()throws Exception{return ((AtomicInteger)field(player.player().getPageManager(),"customPageRequiredAcknowledgments")).get();}
        void click(CustomUIPage page,CustomUIEventBinding binding)throws Exception{
            var event=new CustomPageEvent(CustomPageEventType.Data,binding.data);var bytes=MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(bytes,0);
            require(PacketAdapters.__handleInbound(player.packets(),CustomPageEvent.toObject(bytes)),"Actual native adapter owns the wire-decoded selection event");
            // The harness runs inside one world turn. Drain the already queued
            // lease on that same thread instead of sleeping or resetting ACKs.
            var lease=field(page,"input");var drain=lease.getClass().getDeclaredMethod("drain");drain.setAccessible(true);drain.invoke(lease);observe();
        }
        CustomPage frame(CustomUIPage page)throws Exception{
            Method method;
            if(page instanceof MachinePage){method=MachinePage.class.getDeclaredMethod("renderFrame",com.hypixel.hytale.server.core.entity.entities.Player.class);method.setAccessible(true);method.invoke(page,player.player());}
            else{method=ResearchMachinePage.class.getDeclaredMethod("tickBatch",com.hypixel.hytale.component.Ref.class,com.hypixel.hytale.component.Store.class);method.setAccessible(true);method.invoke(page,player.ref(),player.store());}
            observe();return last();
        }
        void ackAll()throws Exception{observe();while(pending()>0){var event=new CustomPageEvent(CustomPageEventType.Acknowledge,null);require(!PacketAdapters.__handleInbound(player.packets(),event),"Native ACK is forwarded unchanged");player.player().getPageManager().handleEvent(player.ref(),player.store(),event);}}
        @Override public void close()throws Exception{player.player().getPageManager().setPage(player.ref(),player.store(),Page.None);observe();ackAll();}
    }
}
