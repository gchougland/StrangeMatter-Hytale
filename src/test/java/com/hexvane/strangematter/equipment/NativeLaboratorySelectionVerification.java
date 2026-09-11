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
                require(ResearchMachinePage.open(fixture.owner(),research,researchAt,null,fixture.store()),"Native note slot opens with the custom page");
                var page=(ResearchMachinePage)fixture.player().getPageManager().getCustomPage();ui.observe();var initial=ui.last();
                var noteInventory=(com.hypixel.hytale.server.core.inventory.container.ItemContainer)field(page,"noteInventory");
                require(noteInventory.getCapacity()==1,"Research shows a real one slot note inventory");
                verifyInventoryCells(initial,"#PlayerStorageGrid",36,com.hypixel.hytale.server.core.inventory.InventoryComponent.STORAGE_SECTION_ID);
                verifyInventoryCells(initial,"#PlayerHotbarGrid",9,com.hypixel.hytale.server.core.inventory.InventoryComponent.HOTBAR_SECTION_ID);
                require(Arrays.stream(initial.commands).anyMatch(c->c.selector!=null&&c.selector.endsWith(".Slots")&&c.data.contains("Research Notes:")&&!c.data.contains("ItemDisplay")),
                        "Real research notes retain their explicit slot names without embedding server metadata");
                for(var type:ResearchType.values()){
                    require(has(initial,"#"+type.name()+"Icon.Background",type.uiIconPath()),"Machine header uses the original "+type+" symbol");
                    require(has(initial,"#"+type.name()+"ShutterIcon.Background",type.uiIconPath()),"Closed instrument keeps the same original symbol");
                    require(has(initial,"#NoteDisciplines["+type.ordinal()+"] #DisciplineIcon.Background",type.uiIconPath()),"Note summary uses typed labeled symbols");
                }
                var oldReview=binding(initial,"#ViewInstruments");
                short firstSlot=noteSlot(fixture);String first=ResearchService.noteToken(fixture.inventory().getItemStack(firstSlot));
                require(ui.pending()>0&&fixture.inventory().moveItemStackFromSlot(firstSlot,noteInventory,true,true).succeeded(),"Real note moves while initial page is unacknowledged");
                ui.click(page,oldReview);require(field(page,"session")==null,"An empty stale note binding cannot start a new experiment");
                int before=fixture.packets().ofType(CustomPage.class).size();ui.frame(page);
                require(before==fixture.packets().ofType(CustomPage.class).size(),"Slot detail waits for the current visual ACK while item movement remains immediate");
                ui.ackAll();var updated=ui.frame(page);var firstReview=binding(updated,"#ViewInstruments");
                require(value(firstReview).equals(first)&&has(updated,"#NoteTitle.Text",research.noteNode(noteInventory.getItemStack((short)0)).name()),"Note title and matching review token share the native packet");
                require(noteInventory.moveItemStackFromSlot((short)0,fixture.inventory(),true,true).succeeded(),"Note can be removed without being consumed");
                short second=-1;for(short slot=0;slot<fixture.inventory().getCapacity();slot++){var stack=fixture.inventory().getItemStack(slot);var token=ResearchService.noteToken(stack);if(token!=null&&!token.equals(first)){second=slot;break;}}
                require(second>=0&&fixture.inventory().moveItemStackFromSlot(second,noteInventory,true,true).succeeded(),"Another real note can occupy the slot");
                String selected=ResearchService.noteToken(noteInventory.getItemStack((short)0));
                ui.click(page,firstReview);require(field(page,"session")==null,"Stale review never starts the replacement note");
                ui.ackAll();updated=ui.frame(page);ui.click(page,binding(updated,"#ViewInstruments"));
                var session=(ResearchSession)field(page,"session");
                require(session!=null&&session.node().id().equals(research.noteNode(noteInventory.getItemStack((short)0)).id()),"Review reads the exact real note without moving or consuming it");
                ui.click(page,binding(initial,"#Close"));
                require(fixture.player().getPageManager().getCustomPage()==null&&!ItemStack.isEmpty(noteInventory.getItemStack((short)0)),"Close works under ACK and leaves the saved note safely in the desk");ui.ackAll();

                var target=machines.recipes.stream().filter(r->r.id.equals("levitation_pad")).findFirst().orElseThrow();
                machines.selectRecipe(forge,fixture.owner().getUuid(),target.id);
                var forgePage=new MachinePage(fixture.owner(),machines,forge);var forgeInitial=ui.open(forgePage);
                require(bindings(forgeInitial,"#SelectRecipe")==0,"Undiscovered recipes have no list rows or selection bindings");
                require(Arrays.stream(forgeInitial.commands).noneMatch(c->c.selector!=null&&c.selector.endsWith("#RecipeIcon.ItemId")),"Locked output icons are absent from the recipe list");
                require(has(forgeInitial,"#NoRecipes.Visible","true")&&has(forgeInitial,"#Craft.Disabled","true")
                        &&has(forgeInitial,"#ForgeChamber.Visible","false")&&value(binding(forgeInitial,"#Craft")).isEmpty(),"Empty discovery state hides stale remembered details and disables crafting");
                var injected=withAction(binding(forgeInitial,"#Craft"),"SelectRecipe",target.id);
                ui.click(forgePage,injected);
                require((int)field(forgePage,"recipeIndex")==-1,"Forged selection cannot reveal a hidden recipe");
                ui.click(forgePage,withAction(binding(forgeInitial,"#Craft"),"Craft",target.id));
                require(forge.recipe.isEmpty()&&forge.reservedInputs.isEmpty(),"Forged hidden Craft cannot reserve ingredients");

                research.unlock(fixture.owner().getUuid(),"chrono_blister",true);
                int beforeUnlock=fixture.packets().ofType(CustomPage.class).size();ui.frame(forgePage);
                require(beforeUnlock==fixture.packets().ofType(CustomPage.class).size(),"Newly learned recipe structure waits for the outstanding visual ACK");
                ui.ackAll();var discovered=ui.frame(forgePage);
                long known=machines.recipes.stream().filter(r->machines.knowsRecipe(fixture.owner().getUuid(),r)).count();
                require(known>0&&known<machines.recipes.size()&&bindings(discovered,"#SelectRecipe")==known,"Only researched recipes receive native icon rows and stable ID bindings");
                for(var event:discovered.eventBindings)if(event.selector.endsWith("#SelectRecipe")){
                    var entry=machines.recipes.stream().filter(r->r.id.equals(value(event))).findFirst().orElseThrow();
                    require(machines.knowsRecipe(fixture.owner().getUuid(),entry),"Every displayed recipe is actually unlocked");
                }
                require(Arrays.stream(discovered.commands).filter(c->c.selector!=null&&c.selector.endsWith("#RecipeIcon.ItemId")).count()==known,"Filtered row count matches its output icons without hidden gaps");
                require(!has(discovered,"#Recipe.Text","Levitation Pad")&&Arrays.stream(discovered.eventBindings).noneMatch(e->e.selector.endsWith("#SelectRecipe")&&target.id.equals(value(e))),"The locked remembered recipe is absent from details and rows");
                var staleCraft=binding(discovered,"#Craft");
                String needed=research.requiredResearchForItem(target.output);research.unlock(fixture.owner().getUuid(),needed==null?target.research:needed,true);
                ui.ackAll();var expanded=ui.frame(forgePage);
                var selectRecipe=Arrays.stream(expanded.eventBindings).filter(e->e.selector.endsWith("#SelectRecipe")&&target.id.equals(value(e))).findFirst().orElseThrow();
                ui.click(forgePage,selectRecipe);
                require(machines.recipes.get(machines.selectedRecipeIndex(forge,fixture.owner().getUuid())).id.equals(target.id),"Selecting a newly learned recipe remembers its stable ID");
                ui.click(forgePage,staleCraft);
                require(forge.recipe.isEmpty()&&field(forgePage,"message").toString().contains("selection changed"),"Stale Craft ID cannot start or reserve a different recipe");
                before=fixture.packets().ofType(CustomPage.class).size();ui.frame(forgePage);
                require(before==fixture.packets().ofType(CustomPage.class).size(),"Selection input remains live while the list frame awaits ACK");
                for(var cost:target.totalCost().entrySet())fixture.inventory().addItemStack(new ItemStack(cost.getKey(),cost.getValue()),true,false,true);
                ui.ackAll();var ready=ui.frame(forgePage);
                require(has(ready,"#Recipe.Text","Levitation Pad")&&has(ready,"#Craft.Disabled","false")&&value(binding(ready,"#Craft")).equals(target.id),"Unlocked selected detail and matching Craft ID share one native packet");
                ui.click(forgePage,binding(ready,"#Craft"));
                require(forge.recipe.equals(target.id)&&!forge.reservedInputs.isEmpty(),"Matching Craft reserves real ingredients with a pending visual ACK");
                ui.click(forgePage,binding(forgeInitial,"#Close"));ui.ackAll();
                var reopened=new MachinePage(fixture.owner(),machines,forge);var reopening=ui.open(reopened);
                require(has(reopening,"#Recipe.Text","Levitation Pad")&&value(binding(reopening,"#Craft")).equals(target.id),"Reopening restores the saved researched recipe and transaction binding");
                require(has(reopening,"#ForgeScan.Visible","true"),"Crafting animation remains active beside the filtered list");
                ui.click(reopened,binding(reopening,"#Close"));ui.ackAll();

                // Reproduce an ACK delivered to Hytale without our observer seeing it. Both
                // actual screens used to accept controls indefinitely without sending a frame.
                require(ResearchMachinePage.open(fixture.owner(),research,researchAt,selected,fixture.store()),"Research desk reopens its saved slot");
                var recoveryPage=(ResearchMachinePage)fixture.player().getPageManager().getCustomPage();ui.observe();var recoveryInitial=ui.last();
                ui.click(recoveryPage,binding(recoveryInitial,"#ViewInstruments"));ui.ackNativeOnly();
                var experiment=(ResearchSession)field(recoveryPage,"session");
                require(experiment!=null,"Recovery fixture uses a real inventory note in the research machine");
                ui.click(recoveryPage,binding(recoveryInitial,"#Begin"));
                require(experiment.state()==ResearchSession.State.RUNNING,"Begin still works despite the unmatched observational ACK");
                long beforeTicks=experiment.ticks();int beforeRecovery=fixture.packets().ofType(CustomPage.class).size();
                var recoveredResearch=ui.frame(recoveryPage);
                require(fixture.packets().ofType(CustomPage.class).size()==beforeRecovery+1&&experiment.ticks()==beforeTicks+4,
                        "Actual research pulse reconciles with native readiness and resumes exactly one visible simulation batch");
                require(!recoveredResearch.isInitial&&has(recoveredResearch,"#InstabilityFill.Anchor","Width"),
                        "Recovered research frame updates the instability bar without rebuilding controls");
                beforeTicks=experiment.ticks();ui.frame(recoveryPage);
                require(experiment.ticks()==beforeTicks&&ui.pending()==1,"A genuinely unacknowledged recovery frame still pauses unseen research cues");
                ui.click(recoveryPage,binding(recoveryInitial,"#Close"));ui.ackAll();

                var recoveryForge=new MachinePage(fixture.owner(),machines,forge);
                var recoveryForgeInitial=ui.open(recoveryForge);ui.ackNativeOnly();
                require(!forge.recipe.isEmpty()&&!forge.reservedInputs.isEmpty(),"Forge recovery starts with real reserved crafting materials");
                forge.progress+=7;beforeRecovery=fixture.packets().ofType(CustomPage.class).size();
                var recoveredForge=ui.frame(recoveryForge);
                require(fixture.packets().ofType(CustomPage.class).size()==beforeRecovery+1&&has(recoveredForge,"#ForgeScan.Visible","true"),
                        "Actual forge frame resumes its active crafting display after an unobserved native ACK");
                require(!commandData(recoveryForgeInitial,"#ForgeScan.Anchor").equals(commandData(recoveredForge,"#ForgeScan.Anchor"))
                        &&!commandData(recoveryForgeInitial,"#ForgePreview.Anchor").equals(commandData(recoveredForge,"#ForgePreview.Anchor")),
                        "Recovered forge updates both the scan line and floating output animation to current crafting progress");
                require(recoveredForge.eventBindings.length==0,"Animation recovery keeps the existing recipe and transaction bindings");
                ui.click(recoveryForge,binding(recoveryForgeInitial,"#Close"));ui.ackAll();
                require(ResearchMachinePage.open(fixture.owner(),research,researchAt,null,fixture.store()),"Open research slot for native component replacement check");
                var stalePage=(ResearchMachinePage)fixture.player().getPageManager().getCustomPage();
                var staleInventory=(com.hypixel.hytale.server.core.inventory.container.ItemContainer)field(stalePage,"noteInventory");
                var panel=(com.hexvane.strangematter.ui.MachineInventoryPanel)field(stalePage,"inventoryPanel");int sectionId=panel.windows()[0].getId();
                var blockRef=com.hypixel.hytale.server.core.modules.block.BlockModule.getBlockEntity(world,researchAt.x,researchAt.y,researchAt.z);
                var type=com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock.getComponentType();
                var oldComponent=blockRef.getStore().getComponent(blockRef,type);
                var codec=com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock.CODEC;
                var replacement=codec.decode(codec.encode(oldComponent,new com.hypixel.hytale.codec.ExtraInfo()),new com.hypixel.hytale.codec.ExtraInfo());
                blockRef.getStore().putComponent(blockRef,type,replacement);
                var savedNote=staleInventory.getItemStack((short)0);
                com.hypixel.hytale.server.core.inventory.InventoryUtils.moveItem(fixture.ref(),sectionId,0,1,
                        com.hypixel.hytale.server.core.inventory.InventoryComponent.STORAGE_SECTION_ID,34,fixture.store());
                require(java.util.Objects.equals(staleInventory.getItemStack((short)0),savedNote)
                        &&java.util.Objects.equals(replacement.getItemContainer().getItemStack((short)0),savedNote)
                        &&fixture.player().getWindowManager().getWindow(sectionId)==null,
                        "Research page rejects stale note inventory after a native component replacement");
                ui.frame(stalePage);require(fixture.player().getPageManager().getCustomPage()==null,"Stale research page closes and releases its session");ui.ackAll();
            } finally {ui.close();}
        } finally {
            world.setBlock(researchAt.x,researchAt.y,researchAt.z,"Empty");
            world.setBlock(forgeAt.x,forgeAt.y,forgeAt.z,"Empty");machines.removed(world,forgeAt);
        }
        System.out.println("NATIVE_LABORATORY_SELECTION_VERIFICATION_PASSED: real native note slot, main inventory and hotbar, stale note review rejection, hidden locked recipes and empty state, forged hidden events rejected, newly learned rows under held ACKs, stale selection rejection, real crafting reservation, saved selection, active chamber, research and forge animation recovery after unobserved native ACK, genuine pending frame fairness and native Close.");
    }
    private static CustomUIEventBinding withAction(CustomUIEventBinding source,String action,String value){
        var json=JsonParser.parseString(source.data).getAsJsonObject();String old=json.get("Action").getAsString();
        json.addProperty("Action",old.substring(0,old.lastIndexOf(':')+1)+action);json.addProperty("Value",value);
        var event=new CustomUIEventBinding();event.data=json.toString();return event;
    }
    private static short noteSlot(NativePlayerFixture player){for(short slot=0;slot<player.inventory().getCapacity();slot++)if(ResearchService.noteToken(player.inventory().getItemStack(slot))!=null)return slot;throw new AssertionError("No fixture note");}
    private static ItemStack findNote(NativePlayerFixture player,String token){for(short slot=0;slot<player.inventory().getCapacity();slot++){var item=player.inventory().getItemStack(slot);if(token.equals(ResearchService.noteToken(item)))return item;}return ItemStack.EMPTY;}
    private static long bindings(CustomPage page,String ending){return Arrays.stream(page.eventBindings).filter(b->b.selector.endsWith(ending)).count();}
    private static CustomUIEventBinding binding(CustomPage page,String selector){return Arrays.stream(page.eventBindings).filter(b->b.selector.equals(selector)).findFirst().orElseThrow();}
    private static String value(CustomUIEventBinding binding){return JsonParser.parseString(binding.data).getAsJsonObject().get("Value").getAsString();}
    private static boolean has(CustomPage page,String selector,String text){return Arrays.stream(page.commands).anyMatch(c->selector.equals(c.selector)&&c.data!=null&&c.data.contains(text));}
    private static String commandData(CustomPage page,String selector){return Arrays.stream(page.commands).filter(c->selector.equals(c.selector)).findFirst().orElseThrow().data;}
    static void verifyInventoryCells(CustomPage page,String host,int count,int section){
        require(Arrays.stream(page.commands).filter(c->c.selector!=null&&c.selector.startsWith(host+"[")&&c.selector.endsWith(".InventorySectionId")).count()==count,"Every "+host+" cell has a native section binding");
        for(int slot=0;slot<count;slot++){
            String cell=host+"["+(slot/9)+"]["+(slot%9)+"] #Slot";
            require(JsonParser.parseString(commandData(page,cell+".InventorySectionId")).getAsJsonObject().get("0").getAsInt()==section,"Cell points at the actual native inventory section: "+cell);
            var entries=JsonParser.parseString(commandData(page,cell+".Slots")).getAsJsonObject().getAsJsonArray("0");
            require(entries.size()==1&&entries.get(0).getAsJsonObject().get("InventorySlotIndex").getAsInt()==slot,"Every filled or empty cell retains its actual native slot address: "+cell);
        }
    }
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
        void ackNativeOnly()throws Exception{observe();require(pending()>0,"A real native frame is waiting before the observer gap");while(pending()>0)player.player().getPageManager().handleEvent(player.ref(),player.store(),new CustomPageEvent(CustomPageEventType.Acknowledge,null));}
        @Override public void close()throws Exception{player.player().getPageManager().setPage(player.ref(),player.store(),Page.None);observe();ackAll();}
    }
}
