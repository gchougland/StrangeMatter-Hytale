package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.automation.*;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.codec.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import org.bson.*;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.nio.file.Files;
import java.util.*;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Real block holders, native material transactions and saved offline player authorization. */
public final class NativeFactoryVerification {
    private static final String SYNTHETIC="SM_Native_Factory_Recipe_Test";
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private record Cell(int x,int y,int z,int id,int rotation,int filler){}
    /** Native asset mutation requires the registry write lock, outside every world tick's read lock. */
    public static java.util.concurrent.CompletableFuture<Void> verifyAsync(World world,ResearchService research){
        return java.util.concurrent.CompletableFuture.runAsync(()->{
            try{CraftingRecipe.getAssetStore().loadAssets("StrangeMatterNativeFactoryVerification",List.of(syntheticRecipe(SYNTHETIC)));}
            catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}
        }).thenRunAsync(()->{try{verify(world,research);}catch(Exception failure){throw new java.util.concurrent.CompletionException(failure);}},world)
          .handleAsync((ignored,failure)->{
              try{CraftingRecipe.getAssetStore().removeAssets(List.of(SYNTHETIC));}
              catch(Throwable cleanup){if(failure!=null)failure.addSuppressed(cleanup);else failure=cleanup;}
              if(failure!=null)throw new java.util.concurrent.CompletionException(failure);
              return null;
          });
    }
    public static void verify(World world,ResearchService research)throws Exception {
        world.getEntityStore().getStore().assertThread();var directory=Files.createTempDirectory("sm-native-factory-");
        var cells=new ArrayList<Cell>();var chunk=WorldAccess.inMemory(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Factory fixture uses a loaded chunk");
        final String synthetic=SYNTHETIC;
        var anomalies=new AnomalyService(directory);
        // Use the server's one registered UI transport. A second ResearchService would
        // install another global packet adapter after the original service's stale-event guard.
        try(var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies);var factories=new FactoryService(machines,research)){
            separatorExtensions(research,anomalies);
            machines.setFactory(factories);
            for(int x=3;x<=21;x++)for(int y=222;y<=226;y++)for(int z=22;z<=25;z++){
                cells.add(new Cell(x,y,z,WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
                WorldAccess.set(chunk,x,y,z,y==222?"Rock_Stone":"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
            }
            var states=new LinkedHashMap<String,MachineState>();int x=4;
            for(String id:List.of("SM_Reality_Forge","SM_Resonant_Separator","SM_Flux_Furnace","SM_Pattern_Assembler")){
                require(WorldAccess.set(chunk,x,223,23,id),"Native factory block placement: "+id);var state=machines.register(world,new Vector3i(x,223,23),id);require(state!=null,"Machine registration");states.put(id,state);x+=5;
            }
            UUID owner;
            try(var player=NativePlayerFixture.create(world,"FactoryOwner",new Vector3d(5,224,22))){
                // ParticleUtil resolves viewers through the native spatial tree, rebuilt by its ECS system.
                player.store().tick(.05f);var nearby=new ArrayList<Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore>>();player.store().getResource(com.hypixel.hytale.server.core.modules.entity.EntityModule.get().getPlayerSpatialResourceType()).getSpatialStructure().collect(new Vector3d(9.5,223.95,23.5),75,nearby);require(nearby.contains(player.ref()),"Native PlayerSpatialSystem indexes the actual factory viewer");
                owner=player.owner().getUuid();research.unlock(owner,"all",true);
                for(var state:states.values()){factories.claim(world,state,owner);var c=factories.component(world,state);require(c!=null&&BlockModule.getComponent(FactoryComponent.getComponentType(),world,state.x,state.y,state.z)==c,"Factory inventory is a native block component");}
                reservationSemantics();
                var separator=states.get("SM_Resonant_Separator");var sc=factories.component(world,separator);
                require(sc.input.getCapacity()==5&&sc.output.getCapacity()==5,"Separator uses real five slot sections");
                var supported=factories.recipes(separator);require(supported.size()==11,"All eleven approved ore families resolve from native furnace yields");
                require(supported.stream().noneMatch(r->r.inputs().stream().anyMatch(m->m.getItemId().contains("Bar")||m.getItemId().contains("Concentrate"))),"Finished metals cannot enter the doubling path");
                sc.input.setItemStackForSlot((short)0,new ItemStack("Ore_Copper",1),false);separator.energy=0;factories.tick20(world,List.of(separator));require(!sc.busy()&&sc.input.getItemStack((short)0).getQuantity()==1,"No power does not reserve or consume ore");
                separator.energy=10000;factories.tick20(world,List.of(separator));require(sc.busy()&&sc.data.progress==1&&separator.energy==9990,"Native ingredient reservation and exactly one powered step");
                require(MachineWorkEffects.sync(world,separator)&&BlockModule.getComponent(FactoryComponent.getComponentType(),world,separator.x,separator.y,separator.z)==sc,"Real powered work changes the native animated block without replacing its inventory");
                require(player.packets().ofType(com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem.class).stream().anyMatch(p->p.particleSystemId.equals("SM_Resonant_Separator_Work")),"Powered separator emits the actual bounded native working particle packet");
                int held=sc.data.progress;separator.energy=0;factories.tick20(world,List.of(separator));require(sc.data.progress==held&&!separator.active,"A power shortage retains progress and stops presentation");
                MachineWorkEffects.sync(world,separator);require(world.getBlockType(separator.x,separator.y,separator.z).getAmbientSoundEventIndex()==com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.EMPTY_ID,"Paused real job returns to the silent native state");
                separator.energy=10000;
                var encoded=FactoryComponent.CODEC.encode(sc,new ExtraInfo());var restored=FactoryComponent.CODEC.decode(encoded,new ExtraInfo());
                require(restored.busy()&&restored.data.progress==held&&FactoryInventory.stacks(restored.escrow).getFirst().getItemId().equals("Ore_Copper"),"Native codec co-saves in-flight ore and progress");
                var sr=BlockModule.getBlockEntity(world,separator.x,separator.y,separator.z);sr.getStore().putComponent(sr,FactoryComponent.getComponentType(),restored);factories.register(world,separator);separator.energy=10000;
                for(int i=0;i<80;i++)factories.tick20(world,List.of(separator));require(count(restored.output,"SM_Copper_Concentrate")==2&&FactoryInventory.stacks(restored.escrow).isEmpty(),"Reloaded job produces exactly two concentrate and clears escrow");
                var beforeFull=TubeStacks.snapshot(restored.output);for(short slot=0;slot<restored.output.getCapacity();slot++)restored.output.setItemStackForSlot(slot,new ItemStack("SM_Copper_Concentrate",new ItemStack("SM_Copper_Concentrate",1).getItem().getMaxStack()),false);
                restored.input.setItemStackForSlot((short)0,new ItemStack("Ore_Copper",1),false);int powerBefore=separator.energy;factories.tick20(world,List.of(separator));require(!restored.busy()&&separator.energy==powerBefore&&count(restored.input,"Ore_Copper")==1,"Full output neither reserves ore nor spends energy");FactoryInventory.clear(restored.input);for(short slot=0;slot<restored.output.getCapacity();slot++)restored.output.setItemStackForSlot(slot,TubeStacks.decode(beforeFull.get(slot)),false);
                var furnace=states.get("SM_Flux_Furnace");var fc=factories.component(world,furnace);furnace.energy=5000;fc.input.setItemStackForSlot((short)0,new ItemStack("SM_Copper_Concentrate",2),false);
                var smelting=factories.automaticRecipe(world,furnace);require(smelting!=null&&smelting.output().equals("Ingredient_Bar_Copper"),"Flux derives its matching output directly from inserted ingredients");int smeltingTicks=factories.ticks(furnace,fc,smelting);require(smeltingTicks==(int)Math.ceil(smelting.nativeRecipe().getTimeSeconds()/1.2*20),"Flux throughput is twenty percent faster than the actual tier one native furnace recipe");
                automaticPage(world,factories,furnace,player);
                for(int i=0;i<smeltingTicks*2+2;i++)factories.tick20(world,List.of(furnace));require(count(fc.output,"Ingredient_Bar_Copper")==2,"New concentrate recipes smelt through actual native Furnace recipe data");require(count(fc.output,"Ingredient_Charcoal")==0,"Electric heat does not invent fuel charcoal");
                var upgradeItems=InventoryComponent.getCombined(player.store(),player.ref(),InventoryComponent.Storage.getComponentType(),InventoryComponent.Hotbar.getComponentType());for(var cost:factories.upgradeMaterials(furnace,fc))upgradeItems.addItemStack(new ItemStack(cost.getItemId(),cost.getQuantity()),true,false,false);var oldInput=fc.input;
                require(factories.upgrade(world,furnace,owner,upgradeItems).equals("Upgrade started."),"Flux upgrade consumes the native furnace materials plus resonant circuits");int upgradeTime=fc.data.duration;for(int i=0;i<upgradeTime;i++)factories.tick20(world,List.of(furnace));require(fc.tier()==2&&furnace.factoryTier==2&&fc.input!=oldInput&&fc.input.getCapacity()==10,"Completed native upgrade expands input and immediately updates the power tier");
                MachineWorkEffects.sync(world,furnace);require(world.getBlockType(furnace.x,furnace.y,furnace.z).getCurrentInteractionState().startsWith("Tier2"),"Native presentation uses the persisted upgraded tier");
                double furnaceTier=BlockType.getAssetMap().getAsset("Bench_Furnace").getBench().getTierLevel(2).getCraftingTimeReductionModifier();require(factories.ticks(furnace,fc,smelting)==Math.max(1,(int)Math.ceil(smelting.nativeRecipe().getTimeSeconds()/1.2*(1-furnaceTier)*20)),"Flux preserves the matching native tier reduction with its twenty percent throughput bonus");
                var forge=states.get("SM_Reality_Forge");var forgeC=factories.component(world,forge);var original=factories.recipes(forge).stream().filter(r->r.id().equals("stasis_projector")).findFirst().orElseThrow();
                for(var m:original.inputs())forgeC.input.addItemStack(new ItemStack(m.getItemId(),m.getQuantity()),true,false,false);forge.energy=10000;
                require(factories.start(world,forge,owner,original.id()).equals("Crafting started."),"The original forge recipe starts through the powered backend");
                long forgeParticles=particleCount(player,"SM_Resonance_Transfer");for(int i=0;i<10;i++)machines.tick(world,.05);require(particleCount(player,"SM_Resonance_Transfer")>forgeParticles,"Powered Forge emits its real working particle after factory progress is evaluated");
                forgeParticles=particleCount(player,"SM_Resonance_Transfer");int forgeProgress=forgeC.data.progress,forgePower=forge.energy;forge.enabled=false;for(int i=0;i<10;i++)machines.tick(world,.05);require(forgeC.data.progress==forgeProgress&&forge.energy==forgePower&&particleCount(player,"SM_Resonance_Transfer")==forgeParticles,"Paused Forge neither progresses nor emits working particles");
                forge.enabled=true;forge.energy=0;for(int i=0;i<10;i++)machines.tick(world,.05);require(forgeC.data.progress==forgeProgress&&particleCount(player,"SM_Resonance_Transfer")==forgeParticles,"Unpowered Forge preserves progress and emits no working particles");forge.energy=forgePower;
                for(int i=forgeProgress;i<100;i++)factories.tick20(world,List.of(forge));require(count(forgeC.output,"SM_Stasis_Projector")==1&&forge.energy==9000,"Existing five second forge craft uses exactly one thousand RE");
                page(world,factories,forge,player);
                legacyFuel(world,chunk,machines,factories,owner);
                var assembler=states.get("SM_Pattern_Assembler");var ac=factories.component(world,assembler);assembler.energy=10000;
                require(CraftingRecipe.getAssetMap().getAsset(synthetic)!=null,"Synthetic native recipe was loaded outside the world asset read lock");
                var selected=factories.recipe(assembler,synthetic);require(selected!=null&&selected.outputs().size()==2,"Assembler keeps distinct recipe IDs and all native outputs");
                var required=new ItemStack("SM_Resonite_Ingot",1).withMetadata("FactoryTest",Codec.STRING,"exact");ac.input.setItemStackForSlot((short)0,required,false);
                var denied=factories.start(world,assembler,owner,synthetic);require(!denied.equals("Crafting started.")&&!ac.busy(),"Assembler does not borrow an online visitor's known recipes");
                selectorPage(world,factories,assembler,player,synthetic);
                player.player().getPlayerConfigData().setKnownRecipes(Set.of("Ingredient_Bar_Copper"));player.save();
                var savedOwner=com.hypixel.hytale.server.core.universe.Universe.get().getPlayerStorage().load(owner).get(10,java.util.concurrent.TimeUnit.SECONDS);require(savedOwner.getComponent(com.hypixel.hytale.server.core.entity.entities.Player.getComponentType()).getPlayerConfigData().getKnownRecipes().contains("Ingredient_Bar_Copper"),"Actual DiskPlayerStorage contains the owner's native recipe knowledge");
            }
            require(com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(owner)==null,"Offline authorization has no logged-in player session to borrow");
            // The socket-free player never entered Universe's network session map. Its first
            // denial can therefore cache the missing pre-save file; explicitly refresh after
            // creating that real file rather than waiting out the five second denial cache.
            factories.invalidateKnowledge(owner);
            var assembler=states.get("SM_Pattern_Assembler");var ac=factories.component(world,assembler);String result="";
            for(int i=0;i<150;i++){result=factories.start(world,assembler,owner,synthetic);if(result.equals("Crafting started."))break;Thread.sleep(10);}
            require(result.equals("Crafting started."),"Read-only async native player storage authorizes the offline owner: "+result);
            require(ac.data.duration==60,"A short automatic recipe has a visible three second minimum cycle");int assemblyTicks=ac.data.duration;for(int i=0;i<assemblyTicks-1;i++)factories.tick20(world,List.of(assembler));MachineWorkEffects.sync(world,assembler);require(ac.busy()&&assembler.active&&count(ac.output,"Ingredient_Bar_Copper")==0&&world.getBlockType(assembler.x,assembler.y,assembler.z).getCurrentInteractionState().equals("Working"),"Assembler stays visibly working through its slower real cycle before producing output");factories.tick20(world,List.of(assembler));require(count(ac.output,"Ingredient_Bar_Copper")==1&&count(ac.output,"SM_Resonite_Ingot")==1,"Offline native recipe preserves multiple outputs including returned metadata");
            var returned=FactoryInventory.stacks(ac.output).stream().filter(s->s.getItemId().equals("SM_Resonite_Ingot")).findFirst().orElseThrow();require("returned".equals(returned.getFromMetadataOrNull("FactoryTest",Codec.STRING)),"Declared output metadata survives processing");
            var another=new ItemStack("SM_Resonite_Ingot",1).withMetadata("FactoryTest",Codec.STRING,"exact");ac.input.setItemStackForSlot((short)0,another,false);require(factories.start(world,assembler,owner,synthetic).equals("Crafting started."),"A second offline recipe reserves exactly one batch");
            var lockedProgress=ac.data.progress;int lockedPower=assembler.energy;factories.setRecoveryBlocker((w,p)->true);factories.tick20(world,List.of(assembler));require(ac.busy()&&ac.data.progress==lockedProgress&&assembler.energy==lockedPower&&!assembler.active,"Ambiguous tube recovery blocks a real active job without spending power");factories.setRecoveryBlocker((w,p)->false);
            ac.data.jobFingerprint="previous native recipe definition";factories.tick20(world,List.of(assembler));require(ac.busy()&&ac.data.progress==lockedProgress&&assembler.energy==lockedPower,"A saved recipe descriptor that no longer matches the native recipe pauses reserved work immediately");require(factories.cancel(world,assembler,owner).startsWith("Ingredients returned")&&TubeStacks.same(ac.input.getItemStack((short)0),another),"Stopping an unavailable recipe returns the exact original metadata once");
            migration(world,machines,factories,states.get("SM_Reality_Forge"));
            System.out.println("NATIVE_FACTORY PASS native containers, reservations, power, replay, ore yield, furnace, forge, multiple outputs, metadata, offline knowledge and migration");
        } finally {
            for(var c:cells){var type=BlockType.getAssetMap().getAsset(c.id);WorldAccess.set(chunk,c.x,c.y,c.z,c.id,type,c.rotation,c.filler,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);}
        }
    }
    private static void legacyFuel(World world,com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk,MachineService machines,FactoryService factories,UUID owner){
        WorldAccess.set(chunk,4,223,25,"SM_Resonant_Burner");var burner=machines.register(world,new Vector3i(4,223,25),"SM_Resonant_Burner");burner.fuelTicks=17;
        var recovered=new ItemStack("SM_Stabilized_Core",1).withMetadata("FactoryLegacy",Codec.STRING,"return");burner.recoveredFuel.add(MachineState.FuelCharge.from(recovered,1));factories.claim(world,burner,owner);var c=factories.component(world,burner);
        var fuel=new ItemStack("Ingredient_Charcoal",2).withMetadata("FactoryLegacy",Codec.STRING,"fuel");int ticks=FurnaceFuel.ticks(fuel);require(ticks>0,"Native charcoal is a real registered fuel");c.input.setItemStackForSlot((short)0,fuel,false);factories.tick20(world,List.of(burner));
        require(FactoryInventory.stacks(c.input).isEmpty()&&c.data.queuedFuelTicks==ticks*2&&c.data.fuelTicks==17,"Native fuel input and sequential burner queue are co-saved together");
        require(burner.recoveredFuel.isEmpty()&&TubeStacks.same(c.recovery.getItemStack((short)0),recovered),"Legacy nonfuel recovery reaches the actual item slot inventory once");
        var snapshot=FactoryComponent.CODEC.decode(FactoryComponent.CODEC.encode(c,new ExtraInfo()),new ExtraInfo());require(TubeStacks.same(snapshot.data.fuelQueue.getFirst().toItemStack(),TubeStacks.quantity(fuel,1)),"Queued native fuel retains all metadata after the block codec roundtrip");
        var ref=BlockModule.getBlockEntity(world,4,223,25);ref.getStore().putComponent(ref,FactoryComponent.getComponentType(),snapshot);burner.fuelTicks=0;burner.queuedFuelTicks=0;burner.fuelQueue.clear();factories.register(world,burner);require(burner.fuelTicks==17&&burner.queuedFuelTicks==ticks*2&&FactoryInventory.stacks(snapshot.input).isEmpty(),"Native block snapshot supersedes stale legacy fuel counters without replaying consumed input");
    }
    private static void page(World world,FactoryService factories,MachineState forge,NativePlayerFixture player)throws Exception{
        factories.select(world,forge,player.owner().getUuid(),"stasis_projector");int observed=player.packets().packets.size();factories.open(player.owner(),player.ref(),player.store(),forge);
        var page=(FactoryPage)player.player().getPageManager().getCustomPage();require(page!=null&&page.windows().length==2,"Factory page opens the real native input and output windows");
        for(var packet:player.packets().packets.subList(observed,player.packets().packets.size()))if(packet instanceof com.hypixel.hytale.protocol.packets.interface_.CustomPage||packet instanceof com.hypixel.hytale.protocol.packets.interface_.SetPage)com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleOutbound(player.packets(),packet);
        var frame=player.packets().ofType(com.hypixel.hytale.protocol.packets.interface_.CustomPage.class).getLast();var bytes=java.lang.foreign.MemorySegment.ofArray(new byte[frame.computeSize()]);frame.serialize(bytes,0);frame=com.hypixel.hytale.protocol.packets.interface_.CustomPage.toObject(bytes);
        NativeLaboratorySelectionVerification.verifyInventoryCells(frame,"#PlayerStorageGrid",36,InventoryComponent.STORAGE_SECTION_ID);
        NativeLaboratorySelectionVerification.verifyInventoryCells(frame,"#PlayerHotbarGrid",9,InventoryComponent.HOTBAR_SECTION_ID);
        require(Arrays.stream(frame.commands).anyMatch(cmd->cmd.selector!=null&&cmd.selector.endsWith("#MaterialIcon.ItemId")),"Every shown exact recipe material has an actual item icon");
        require(Arrays.stream(frame.commands).anyMatch(cmd->"#Stock.TooltipText".equals(cmd.selector)&&cmd.data.contains("selected output item")&&cmd.data.contains("resumes when items are removed")),"The stock target explains the selected output cap and automatic resumption");
        var search=Arrays.stream(frame.eventBindings).filter(event->"#Search".equals(event.selector)).findFirst().orElseThrow();var json=com.google.gson.JsonParser.parseString(search.data).getAsJsonObject();json.addProperty("@Value","levitation");dispatch(page,player,json.toString());
        var field=FactoryPage.class.getDeclaredField("search");field.setAccessible(true);require(field.get(page).equals("levitation"),"Native resolved search value works while the initial factory frame still awaits ACK");
        var craft=Arrays.stream(frame.eventBindings).filter(event->"#Craft".equals(event.selector)).findFirst().orElseThrow();var stale=com.google.gson.JsonParser.parseString(craft.data).getAsJsonObject();stale.addProperty("Value","levitation_pad");dispatch(page,player,stale.toString());require(!factories.component(world,forge).busy(),"A forged or stale recipe value cannot replace the reviewed recipe");
        var close=Arrays.stream(frame.eventBindings).filter(event->"#Close".equals(event.selector)).findFirst().orElseThrow();dispatch(page,player,close.data);require(player.player().getPageManager().getCustomPage()==null,"Factory Close survives the genuine pending initial ACK");
        var counter=player.player().getPageManager().getClass().getDeclaredField("customPageRequiredAcknowledgments");counter.setAccessible(true);var pending=(java.util.concurrent.atomic.AtomicInteger)counter.get(player.player().getPageManager());while(pending.get()>0){var ack=new com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent(com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType.Acknowledge,null);com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleInbound(player.packets(),ack);player.player().getPageManager().handleEvent(player.ref(),player.store(),ack);}
    }
    private static void dispatch(com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage page,NativePlayerFixture player,String raw)throws Exception{
        var event=new com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent(com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType.Data,raw);require(com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleInbound(player.packets(),event),"Actual native adapter routes this factory event");var field=page.getClass().getDeclaredField("lease");field.setAccessible(true);var lease=field.get(page);var drain=lease.getClass().getDeclaredMethod("drain");drain.setAccessible(true);drain.invoke(lease);
    }
    private static void observePages(NativePlayerFixture player,int start){for(var packet:player.packets().packets.subList(start,player.packets().packets.size()))if(packet instanceof com.hypixel.hytale.protocol.packets.interface_.CustomPage||packet instanceof com.hypixel.hytale.protocol.packets.interface_.SetPage)com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleOutbound(player.packets(),packet);}
    private static void acknowledgePages(NativePlayerFixture player)throws Exception{var counter=player.player().getPageManager().getClass().getDeclaredField("customPageRequiredAcknowledgments");counter.setAccessible(true);var pending=(java.util.concurrent.atomic.AtomicInteger)counter.get(player.player().getPageManager());while(pending.get()>0){var ack=new com.hypixel.hytale.protocol.packets.interface_.CustomPageEvent(com.hypixel.hytale.protocol.packets.interface_.CustomPageEventType.Acknowledge,null);com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleInbound(player.packets(),ack);player.player().getPageManager().handleEvent(player.ref(),player.store(),ack);}}
    private static void automaticPage(World world,FactoryService service,MachineState furnace,NativePlayerFixture player)throws Exception{
        var transform=player.store().getComponent(player.ref(),com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());var original=new Vector3d(transform.getPosition());transform.setPosition(new Vector3d(furnace.x+.5,furnace.y+1,furnace.z-1));
        try{int start=player.packets().packets.size();service.open(player.owner(),player.ref(),player.store(),furnace);observePages(player,start);var page=(FactoryPage)player.player().getPageManager().getCustomPage();var frame=player.packets().ofType(com.hypixel.hytale.protocol.packets.interface_.CustomPage.class).getLast();
            require(Arrays.stream(frame.commands).anyMatch(c->"#RecipeBrowser.Visible".equals(c.selector)&&c.data.contains("false")),"Flux hides the recipe browser");
            require(Arrays.stream(frame.eventBindings).noneMatch(e->e.selector.equals("#Search")||e.selector.equals("#Craft")||e.selector.endsWith("#SelectRecipe")),"Flux has no recipe choice or manual craft bindings");
            require(Arrays.stream(frame.commands).anyMatch(c->"#Selected.Text".equals(c.selector)&&c.data.contains(FactoryRecipeCategories.itemName("Ingredient_Bar_Copper",player.owner().getLanguage()))),"Flux displays the actual input-derived smelting output");
            upgradePage(service,furnace,player,page,frame);
            var close=Arrays.stream(frame.eventBindings).filter(e->e.selector.equals("#Close")).findFirst().orElseThrow();dispatch(page,player,close.data);acknowledgePages(player);
        }finally{transform.setPosition(original);}
    }
    private static void upgradePage(FactoryService service,MachineState machine,NativePlayerFixture player,FactoryPage page,com.hypixel.hytale.protocol.packets.interface_.CustomPage initial)throws Exception{
        var costs=service.upgradeMaterials(machine,service.component(player.world(),machine));require(!costs.isEmpty(),"Actual native furnace upgrade has a cost list");
        for(int i=0;i<costs.size();i++){String selector="#UpgradeCosts["+i+"] #UpgradeIcon.ItemId";String item=costs.get(i).getItemId();require(Arrays.stream(initial.commands).anyMatch(c->selector.equals(c.selector)&&c.data.contains(item)),"Every native upgrade material has its actual item icon: "+item);}
        dispatch(page,player,Arrays.stream(initial.eventBindings).filter(e->e.selector.equals("#Settings")).findFirst().orElseThrow().data);
        var supplies=InventoryComponent.getCombined(player.store(),player.ref(),InventoryComponent.Storage.getComponentType(),InventoryComponent.Hotbar.getComponentType());
        var material=costs.getLast();short slot=8;var saved=player.hotbar().getItemStack(slot);player.hotbar().setItemStackForSlot(slot,ItemStack.EMPTY);
        try{
            int before=com.hypixel.hytale.server.core.inventory.container.InternalContainerUtilMaterial.countMaterialFromItems(supplies,material,false);
            player.hotbar().setItemStackForSlot(slot,new ItemStack(material.getItemId(),1));
            var commands=new com.hypixel.hytale.server.core.ui.builder.UICommandBuilder();var events=new com.hypixel.hytale.server.core.ui.builder.UIEventBuilder();
            var draw=FactoryPage.class.getDeclaredMethod("draw",Ref.class,Store.class,com.hypixel.hytale.server.core.ui.builder.UICommandBuilder.class,com.hypixel.hytale.server.core.ui.builder.UIEventBuilder.class,boolean.class);draw.setAccessible(true);draw.invoke(page,player.ref(),player.store(),commands,events,false);
            var packet=new com.hypixel.hytale.protocol.packets.interface_.CustomPage(FactoryPage.class.getName(),false,false,page.getLifetime(),commands.getCommands(),events.getEvents());var bytes=java.lang.foreign.MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);packet=com.hypixel.hytale.protocol.packets.interface_.CustomPage.toObject(bytes);
            require(Arrays.stream(packet.commands).anyMatch(c->"#SettingsGroup.Visible".equals(c.selector)&&c.data.contains("true")),"The actual held ACK Settings event opens the upgrade panel");
            String countSelector="#UpgradeCosts["+(costs.size()-1)+"] #UpgradeCount.Text";
            require(Arrays.stream(packet.commands).anyMatch(c->countSelector.equals(c.selector)&&c.data.contains((before+1)+" / "+material.getQuantity())),"Native wire cost rows refresh when a real hotbar item changes");
            require(Arrays.stream(packet.commands).noneMatch(c->c.type==com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType.Append&&"#UpgradeCosts".equals(c.selector))&&packet.eventBindings.length==0&&Arrays.stream(packet.commands).anyMatch(c->"#PlayerHotbarGrid[0][8] #DisplayRevision.Value".equals(c.selector)),"Cost refreshes keep all bindings and the cost list stable while updating the changed hotbar cell revision");
            require(player.hotbar().getItemStack(slot).getQuantity()==1,"Showing upgrade costs does not consume materials");
        }finally{player.hotbar().setItemStackForSlot(slot,saved);}
    }
    private static void selectorPage(World world,FactoryService service,MachineState assembler,NativePlayerFixture player,String hidden)throws Exception{
        var transform=player.store().getComponent(player.ref(),com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());var original=new Vector3d(transform.getPosition());transform.setPosition(new Vector3d(assembler.x+.5,assembler.y+1,assembler.z-1));
        try{
            var c=service.component(world,assembler);c.data.pattern="";c.data.repeat=true;service.tick20(world,List.of(assembler));require(!c.busy(),"An assembler never chooses an arbitrary recipe before a pattern is selected");c.data.repeat=false;
            int start=player.packets().packets.size();service.open(player.owner(),player.ref(),player.store(),assembler);observePages(player,start);var machine=(FactoryPage)player.player().getPageManager().getCustomPage();var frame=player.packets().ofType(com.hypixel.hytale.protocol.packets.interface_.CustomPage.class).getLast();
            var choose=Arrays.stream(frame.eventBindings).filter(e->e.selector.equals("#ChoosePattern")).findFirst().orElseThrow();require(Arrays.stream(frame.eventBindings).noneMatch(e->e.selector.equals("#Search")||e.selector.endsWith("#SelectRecipe")),"Assembler machine screen has one recipe selector button and no inline recipe list");
            start=player.packets().packets.size();dispatch(machine,player,choose.data);observePages(player,start);require(player.player().getPageManager().getCustomPage() instanceof FactoryRecipeSelectorPage,"Choose recipe opens a separate native selector while the machine frame awaits ACK");var selector=(FactoryRecipeSelectorPage)player.player().getPageManager().getCustomPage();var selectorFrame=player.packets().ofType(com.hypixel.hytale.protocol.packets.interface_.CustomPage.class).getLast();
            var known=selector.known(player.store());require(known.stream().noneMatch(r->r.id().equals(hidden)),"Selector hides the owner's unknown native recipe");
            var nativeCategories=((com.hypixel.hytale.server.core.asset.type.blocktype.config.bench.CraftingBench)BlockType.getAssetMap().getAsset("Bench_WorkBench").getBench()).getCategories();
            for(var tab:nativeCategories)require(Arrays.stream(selectorFrame.eventBindings).anyMatch(e->e.selector.endsWith("#Tab")&&com.google.gson.JsonParser.parseString(e.data).getAsJsonObject().get("Value").getAsString().equals(tab.getId())),"Selector contains the actual native Workbench category "+tab.getId());
            require(Arrays.stream(selectorFrame.commands).filter(cmd->cmd.selector!=null&&cmd.selector.endsWith("#Tab.Text")).noneMatch(cmd->cmd.data.contains("Workbench_")),"Workbench tabs use friendly localized labels");
            var allowed=known.stream().filter(r->service.authorization(world,assembler,c,r,player.owner().getUuid())==null).findFirst().orElseThrow();var select=Arrays.stream(selectorFrame.eventBindings).filter(e->e.selector.endsWith("#Choose")&&com.google.gson.JsonParser.parseString(e.data).getAsJsonObject().get("Value").getAsString().equals(allowed.id())).findFirst().orElseThrow();
            var forged=com.google.gson.JsonParser.parseString(select.data).getAsJsonObject();forged.addProperty("Value",hidden);dispatch(selector,player,forged.toString());require(c.data.pattern.isEmpty(),"A forged hidden recipe ID cannot bypass owner knowledge in the selector");
            var tabEvent=Arrays.stream(selectorFrame.eventBindings).filter(e->e.selector.endsWith("#Tab")).findFirst().orElseThrow();dispatch(selector,player,tabEvent.data);
            start=player.packets().packets.size();dispatch(selector,player,select.data);observePages(player,start);require(c.data.pattern.equals(allowed.id())&&player.player().getPageManager().getCustomPage() instanceof FactoryPage,"Selecting a known recipe preserves its stable ID and returns to the real machine inventory");
            dispatch(selector,player,forged.toString());require(c.data.pattern.equals(allowed.id()),"Old selector events cannot alter the pattern after returning to the machine");
            var returned=(FactoryPage)player.player().getPageManager().getCustomPage();var returnedFrame=player.packets().ofType(com.hypixel.hytale.protocol.packets.interface_.CustomPage.class).getLast();dispatch(returned,player,Arrays.stream(returnedFrame.eventBindings).filter(e->e.selector.equals("#Close")).findFirst().orElseThrow().data);acknowledgePages(player);
        }finally{transform.setPosition(original);}
    }
    private static void reservationSemantics(){
        var inventory=new SimpleItemContainer((short)4);inventory.setItemStackForSlot((short)0,new ItemStack("Wood_Softwood_Planks",1),false);var exact=new MaterialQuantity("Wood_Softwood_Planks",null,null,1,null);var any=new MaterialQuantity(null,"Wood_Planks",null,1,null);
        require(FactoryInventory.plan(inventory,List.of(any,exact),s->true,false)==null&&count(inventory,"Wood_Softwood_Planks")==1,"Overlapping resource and exact inputs never double allocate the same native stack");
        var requirements=FactoryInventory.requirements(inventory,List.of(any,exact));require(requirements.get(0).available()==1&&requirements.get(0).missing()==0&&requirements.get(1).available()==0&&requirements.get(1).missing()==1&&count(inventory,"Wood_Softwood_Planks")==1,"Upgrade material preview reports actual missing quantities without double counting or consuming native resource items");
        var withMeta=new ItemStack("SM_Resonite_Ingot",2).withMetadata("FactoryTest",Codec.STRING,"exact");inventory.setItemStackForSlot((short)1,withMeta,false);
        var wrong=new MaterialQuantity("SM_Resonite_Ingot",null,null,1,null);require(FactoryInventory.plan(inventory,List.of(wrong),s->true,false)==null,"Native exact item metadata is required");
        var right=new MaterialQuantity("SM_Resonite_Ingot",null,null,1,new BsonDocument("FactoryTest",new BsonString("exact")));var plan=FactoryInventory.plan(inventory,List.of(right),s->true,false);require(plan!=null&&FactoryInventory.commit(inventory,plan),"Native matching metadata can be reserved");require(TubeStacks.same(plan.stacks().getFirst(),TubeStacks.quantity(withMeta,1)),"Reserved stack retains its full native data");
    }
    private static void separatorExtensions(ResearchService research,AnomalyService anomalies)throws Exception{
        var directory=Files.createTempDirectory("sm-separator-extension-");var path=directory.resolve("separator-recipes.json");
        Files.writeString(path,"[{\"id\":\"copper_addon\",\"ore\":\"Ore_Copper\",\"bar\":\"Ingredient_Bar_Copper\",\"concentrate\":\"SM_Copper_Concentrate\"}]");
        try(var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies);var factory=new FactoryService(machines,research)){
            var recipe=FactoryRecipes.list(machines,"SM_Resonant_Separator").stream().filter(r->r.id().equals("separator:copper_addon")).findFirst().orElseThrow();require(recipe.inputs().getFirst().getQuantity()==1&&recipe.outputs().getFirst().getQuantity()==2,"Optional separator definition derives its doubled yield from the real loaded furnace recipe");
            Files.writeString(path,"[]");require(FactoryRecipes.list(machines,"SM_Resonant_Separator").stream().anyMatch(r->r.id().equals("separator:copper_addon")),"Separator extension definitions are loaded at service creation instead of reading disk every tick");
        }
        Files.writeString(path,"[{\"id\":\"unsafe\",\"ore\":\"Ingredient_Bar_Copper\",\"bar\":\"Ingredient_Bar_Copper\",\"concentrate\":\"SM_Copper_Concentrate\"}]");
        try(var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies)){
            boolean rejected=false;try(var ignored=new FactoryService(machines,research)){}catch(IllegalStateException invalid){rejected=true;}require(rejected,"Separator extension rejects an ore-to-itself metal multiplication definition before processing");
        }
    }
    private static long particleCount(NativePlayerFixture player,String id){return player.packets().ofType(com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem.class).stream().filter(p->id.equals(p.particleSystemId)).count();}
    private static CraftingRecipe syntheticRecipe(String id)throws Exception {
        String json="{\"Input\":[{\"ItemId\":\"SM_Resonite_Ingot\",\"Quantity\":1,\"Metadata\":{\"FactoryTest\":\"exact\"}}],\"PrimaryOutput\":{\"ItemId\":\"Ingredient_Bar_Copper\",\"Quantity\":1},\"Output\":[{\"ItemId\":\"Ingredient_Bar_Copper\",\"Quantity\":1},{\"ItemId\":\"SM_Resonite_Ingot\",\"Quantity\":1,\"Metadata\":{\"FactoryTest\":\"returned\"}}],\"TimeSeconds\":1,\"KnowledgeRequired\":true,\"BenchRequirement\":[{\"Type\":\"Crafting\",\"Id\":\"Workbench\",\"RequiredTierLevel\":1}]}";
        var recipe=CraftingRecipe.CODEC.decode(BsonDocument.parse(json),new ExtraInfo());var field=CraftingRecipe.class.getDeclaredField("id");field.setAccessible(true);field.set(recipe,id);return recipe;
    }
    private static void migration(World world,MachineService machines,FactoryService factories,MachineState forge){
        var ref=BlockModule.getBlockEntity(world,forge.x,forge.y,forge.z);var nativeStore=ref.getStore();factories.cleanup(world);forge.factoryMigration="";forge.output="SM_Resonite_Ingot";forge.outputQuantity=3;forge.recipe="";forge.reservedInputs.clear();
        nativeStore.putComponent(ref,FactoryComponent.getComponentType(),new FactoryComponent());var c=factories.register(world,forge);require(count(c.output,"SM_Resonite_Ingot")==3&&!forge.factoryMigration.isEmpty()&&forge.outputQuantity==0,"Legacy scalar output is moved with a durable complete migration receipt");
        factories.cleanup(world);nativeStore.putComponent(ref,FactoryComponent.getComponentType(),new FactoryComponent());var replay=factories.register(world,forge);require(count(replay.output,"SM_Resonite_Ingot")==3,"A stale native section recovers from the legacy file migration receipt once");
        factories.register(world,forge);require(count(replay.output,"SM_Resonite_Ingot")==3,"Repeated registration does not duplicate migrated output");
    }
    private static int count(com.hypixel.hytale.server.core.inventory.container.ItemContainer inventory,String id){return FactoryInventory.stacks(inventory).stream().filter(s->s.getItemId().equals(id)).mapToInt(ItemStack::getQuantity).sum();}
    private NativeFactoryVerification(){}
}
