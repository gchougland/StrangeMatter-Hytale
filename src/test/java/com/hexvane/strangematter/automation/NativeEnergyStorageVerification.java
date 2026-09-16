package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.*;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.*;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3i;
import org.joml.Vector3d;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Actual 20 Hz native power routing, face controls and authoritative block persistence. */
public final class NativeEnergyStorageVerification {
    private record Cell(int x,int y,int z,int id,int rotation,int filler){}
    public static void verify(World world,ResearchService research)throws Exception{
        var path=Files.createTempDirectory("sm-native-energy-storage-");var config=new StrangeMatterConfig();
        var recipe=CraftingRecipe.getAssetMap().getAsset(EnergyStoragePorts.ID+"_Recipe_Generated_0");
        require(recipe!=null&&recipe.isKnowledgeRequired()&&EnergyStoragePorts.ID.equals(recipe.getPrimaryOutput().getItemId())
            &&Arrays.stream(recipe.getBenchRequirement()).anyMatch(bench->bench.type==BenchType.Crafting&&"SM_Laboratory".equals(bench.id)&&bench.requiredTierLevel<=1),
            "Native storage crafting is research-gated and available on the basic laboratory bench");
        var chunk=WorldAccess.loaded(world,ChunkUtil.indexChunk(0,0));require(chunk!=null,"Native storage fixture has a loaded chunk");
        var cells=new ArrayList<Cell>();
        try(var machines=new MachineService(path,config,research,new AnomalyService(path));var factory=new FactoryService(machines,research);
            var player=NativePlayerFixture.create(world,"NativeEnergyStorage",new Vector3d(8.5,236,27))){
            machines.setFactory(factory);
            try{
                for(int x=3;x<=14;x++)for(int y=233;y<=238;y++)for(int z=19;z<=29;z++){
                    cells.add(new Cell(x,y,z,WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
                    WorldAccess.set(chunk,x,y,z,y==234?"Rock_Stone":"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
                }
                var storage=place(world,machines,factory,EnergyStoragePorts.ID,8,235,24,player);
                var burner=place(world,machines,factory,"SM_Resonant_Burner",8,235,22,player);place(world,machines,factory,"SM_Resonant_Conduit",8,235,23,player);
                var charger=place(world,machines,factory,"SM_Resonant_Charging_Station",8,235,26,player);place(world,machines,factory,"SM_Resonant_Conduit",8,235,25,player);
                require(storage.energy==0&&machines.capacity(storage)==60000&&factory.ports(world,storage.block()).isEmpty(),"Fresh storage starts empty with a native power-only component and no item tube ports");
                var component=factory.component(world,storage);require(component.input.getCapacity()==0&&component.output.getCapacity()==0,"Storage creates no hidden inventory capacity");
                burner.energy=500;factory.changed(world,burner);machines.tick(world,.05);
                require(burner.energy==480&&storage.energy==20&&charger.energy==0,"First tick receives through the back and cannot forward newly received power");
                machines.tick(world,.05);require(burner.energy==460&&storage.energy==20&&charger.energy==20,"Second tick exports existing reserve through the front while input remains rate-limited");
                require(storage.active&&"Working".equals(world.getBlockType(8,235,24).getCurrentInteractionState()),"Real power transfer activates the native storage presentation");
                var page=ui(world,machines,factory,storage,player);
                click(player,page,"#PortFRONT");int received=charger.energy;machines.tick(world,.05);
                require(EnergyStoragePorts.mode(storage,EnergyStoragePorts.Face.FRONT)==EnergyStoragePorts.Mode.DISABLED&&charger.energy==received,"Live face control immediately invalidates cached front output routes");
                click(player,page,"#PortFRONT");click(player,page,"#PortFRONT");
                click(player,page,"#PortBACK");int fuelReserve=burner.energy;machines.tick(world,.05);
                require(burner.energy==fuelReserve,"An output-configured back face rejects cached incoming power");
                click(player,page,"#PortBACK");click(player,page,"#PortBACK");
                int total=burner.energy+storage.energy+charger.energy;storage.enabled=false;int paused=storage.energy;machines.tick(world,.05);
                require(storage.energy==paused&&burner.energy+storage.energy+charger.energy==total&&!storage.active,"Disabled storage neither receives nor exports");storage.enabled=true;
                player.player().getPageManager().setPage(player.ref(),player.store(),Page.None,false);
                persistence(world,machines,factory,storage,path,research);
                // Rotate the same native block while retaining its saved face modes and reserve.
                clearExcept(world,machines,factory,storage);storage.energy=100;storage.energyFaces=null;factory.changed(world,storage);
                var rotation=RotationTuple.of(Rotation.Ninety,Rotation.None);var type=world.getBlockType(storage.x,storage.y,storage.z);
                WorldAccess.set(chunk,storage.x,storage.y,storage.z,BlockType.getAssetMap().getIndex(type.getId()),type,rotation.index(),0,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
                var front=new Vector3i(0,0,1);rotation.applyRotationTo(front);
                burner=place(world,machines,factory,"SM_Resonant_Burner",8-front.x*2,235,24-front.z*2,player);burner.energy=500;factory.changed(world,burner);
                place(world,machines,factory,"SM_Resonant_Conduit",8-front.x,235,24-front.z,player);
                charger=place(world,machines,factory,"SM_Resonant_Charging_Station",8+front.x*2,235,24+front.z*2,player);place(world,machines,factory,"SM_Resonant_Conduit",8+front.x,235,24+front.z,player);
                machines.tick(world,.05);require(burner.energy==480&&storage.energy==100&&charger.energy==20&&storage.powerRotation==rotation.index(),"Native block rotation preserves back input/front output and conserved transfer");
                sharedAllowance(world,machines,factory,player,storage,burner,charger,rotation);
                for(boolean reverse:new boolean[]{false,true}){
                    clearExcept(world,machines,factory,null);loop(world,machines,factory,player,reverse);
                }
                clearExcept(world,machines,factory,null);stabilizerDistribution(world,machines,factory,player);
                System.out.println("NATIVE_ENERGY_STORAGE_VERIFICATION_PASSED: empty native storage, terminal ports, input/output caps, cache-safe face UI, rotated real network, disabled behavior, block/save reload and order-independent conservative storage rings.");
            }finally{clearExcept(world,machines,factory,null);for(var c:cells)WorldAccess.set(chunk,c.x,c.y,c.z,c.id,BlockType.getAssetMap().getAsset(c.id),c.rotation,c.filler,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);}
        }
    }
    private static void stabilizerDistribution(World world,MachineService machines,FactoryService factory,NativePlayerFixture player){
        var sources=new ArrayList<MachineState>();
        for(int x:new int[]{4,8,12}){sources.add(place(world,machines,factory,"SM_Rift_Stabilizer",x,235,22,player));place(world,machines,factory,"SM_Resonant_Conduit",x,235,23,player);}
        for(int x=4;x<=12;x++)place(world,machines,factory,"SM_Resonant_Conduit",x,235,24,player);
        var condenser=place(world,machines,factory,"SM_Resonance_Condenser",6,235,26,player);place(world,machines,factory,"SM_Resonant_Conduit",6,235,25,player);
        var storage=place(world,machines,factory,EnergyStoragePorts.ID,10,235,26,player);place(world,machines,factory,"SM_Resonant_Conduit",10,235,25,player);
        for(var face:EnergyStoragePorts.Face.values())EnergyStoragePorts.set(storage,face,EnergyStoragePorts.Mode.INPUT);factory.changed(world,storage);
        sources.get(0).energy=sources.get(1).energy=machines.config.riftCapacity;sources.get(2).energy=13;
        machines.tick(world,.05);
        require(storage.energy==20&&condenser.energy==0&&condenser.receivedThisTick==0&&sources.stream().allMatch(s->s.sentThisTick>=6),"Three stabilizers, including two full buffers, share storage fairly while a fresh idle condenser receives nothing");
        require("No anomaly in range".equals(machines.condenserStatus(world,condenser)),"Idle condenser explains its lack of demand rather than reporting a power-routing failure");
        MachinePage.open(player.owner(),machines,condenser,player.store());var page=player.packets().ofType(CustomPage.class).getLast();
        require("No anomaly in range".equals(property(page,"#PowerMeter #ChargeState.Text").getAsString()),"Actual native condenser UI explains its idle power state");
        click(player,page,"#Toggle");require(!condenser.enabled&&machines.get(world,condenser.block())==condenser,"Real condenser UI disables the exact authoritative network state");
        int before=storage.energy;machines.tick(world,.05);require(storage.energy==before+20&&condenser.receivedThisTick==0,"Disabled condenser cannot capture power from cached routes");
        click(player,page,"#Toggle");require(condenser.enabled,"The same native UI immediately re-enables its endpoint");
        player.player().getPageManager().setPage(player.ref(),player.store(),Page.None,false);
        var anomaly=machines.anomalies.spawn(com.hexvane.strangematter.anomaly.AnomalyType.ENERGETIC_RIFT,world,new Vector3d(8.5,237,21.5),false);
        try{
            before=storage.energy;int reserve=sources.stream().mapToInt(s->s.energy).sum()+storage.energy+condenser.energy,progress=condenser.progress;
            machines.tick(world,.05);
            require(condenser.active&&condenser.progress==progress+1&&condenser.receivedThisTick==machines.config.condenserConsumption&&storage.energy==before+20,"Re-enabled active condenser receives only its working tick, with remaining compatible supply charging storage");
            require(sources.stream().mapToInt(s->s.energy).sum()+storage.energy+condenser.energy==reserve+3*machines.config.riftGeneration-machines.config.condenserConsumption,"Buffered multi-generator transfer conserves exact generation, storage and work consumption");
            for(var source:sources)source.energy=0;storage.energy=0;condenser.energy=0;factory.changed(world,storage);factory.changed(world,condenser);
            for(int tick=0;tick<5;tick++){
                machines.tick(world,.05);require(sources.stream().allMatch(s->s.energy==0&&s.sentThisTick==2)&&condenser.receivedThisTick==2&&storage.receivedThisTick==4,"Every fresh generation tick leaves each stabilizer before its buffer fills; work gets 2 RE and storage gets the remaining 4 RE");
            }
            require(storage.energy==20,"Five generation ticks accumulate exactly the surplus in storage");
            var component=factory.component(world,condenser);String shard=MachineService.shard(anomaly.type);int maximum=new com.hypixel.hytale.server.core.inventory.ItemStack(shard,1).getItem().getMaxStack();
            for(short slot=0;slot<component.output.getCapacity();slot++)component.output.setItemStackForSlot(slot,new com.hypixel.hytale.server.core.inventory.ItemStack(shard,maximum),false);
            before=storage.energy;progress=condenser.progress;machines.tick(world,.05);
            require(condenser.receivedThisTick==0&&condenser.progress==progress&&storage.energy==before+6&&"Output full".equals(machines.condenserStatus(world,condenser)),"A full native output stops condenser demand and routes the entire renewable supply into storage");
            machines.toggle(sources.get(0));sources.get(0).energy=100;before=storage.energy;machines.tick(world,.05);
            require(sources.get(0).energy==100&&sources.get(0).sentThisTick==0&&storage.energy==before+4,"A disabled stabilizer exports and generates nothing while other producers remain live");machines.toggle(sources.get(0));
            storage.energy=machines.capacity(storage);sources.get(0).energy=12000;factory.changed(world,storage);machines.tick(world,.05);
            require(sources.get(0).energy==12000,"Energy saved above the new 1200 RE stabilizer capacity is retained until a consumer can drain it");
            System.out.println("NATIVE_STABILIZER_DISTRIBUTION_VERIFICATION_PASSED: three real stabilizers, live condenser UI/cache, idle and full outputs, demand-first fair distribution, immediate generation export and conserved legacy reserve.");
        }finally{machines.anomalies.remove(anomaly.id);}
    }
    private static void sharedAllowance(World world,MachineService machines,FactoryService factory,NativePlayerFixture player,MachineState storage,MachineState burner,MachineState first,RotationTuple rotation){
        var left=new Vector3i(-1,0,0);rotation.applyRotationTo(left);
        var second=place(world,machines,factory,"SM_Resonant_Charging_Station",8+left.x*2,235,24+left.z*2,player);
        place(world,machines,factory,"SM_Resonant_Conduit",8+left.x,235,24+left.z,player);
        machines.cycleStorageFace(world,storage,player.owner(),"LEFT");
        first.energy=machines.capacity(first)-15;second.energy=machines.capacity(second)-15;storage.energy=100;
        factory.changed(world,first);factory.changed(world,second);factory.changed(world,storage);
        int targets=first.energy+second.energy;machines.tick(world,.05);
        require(first.energy+second.energy-targets==20&&storage.sentThisTick==20,
            "Two hungry targets share one 20 RE output allowance, including a partially filled first target");
        var secondBurner=place(world,machines,factory,"SM_Resonant_Burner",8,236,24,player);secondBurner.energy=500;factory.changed(world,secondBurner);
        machines.cycleStorageFace(world,storage,player.owner(),"FRONT");machines.cycleStorageFace(world,storage,player.owner(),"LEFT");
        int sources=burner.energy+secondBurner.energy,reserve=storage.energy;machines.tick(world,.05);
        require(sources-burner.energy-secondBurner.energy==20&&storage.energy-reserve==20&&storage.receivedThisTick==20&&storage.sentThisTick==0,
            "Two independent sources share one 20 RE input allowance and disabled output cannot bypass the buffer");
    }
    private static MachineState place(World world,MachineService machines,FactoryService factory,String id,int x,int y,int z,NativePlayerFixture player){
        WorldAccess.set(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(x,z)),x,y,z,id);var state=machines.register(world,new Vector3i(x,y,z),id);factory.claim(world,state,player.owner().getUuid());return state;
    }
    private static void clearExcept(World world,MachineService machines,FactoryService factory,MachineState keep){
        for(var state:machines.inWorld(world))if(state!=keep){factory.remove(world,state);machines.removed(world,state.block());WorldAccess.set(WorldAccess.loaded(world,ChunkUtil.indexChunkFromBlock(state.x,state.z)),state.x,state.y,state.z,"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);}
    }
    private static CustomPage ui(World world,MachineService machines,FactoryService factory,MachineState storage,NativePlayerFixture player){
        MachinePage.open(player.owner(),machines,storage,player.store());require(player.player().getPageManager().getCustomPage() instanceof MachinePage,"Storage opens its real machine controls");
        var page=player.packets().ofType(CustomPage.class).getLast();var wire=java.lang.foreign.MemorySegment.ofArray(new byte[page.computeSize()]);page.serialize(wire,0);page=CustomPage.toObject(wire);
        require(property(page,"#StoragePorts.Visible").getAsBoolean()&&!property(page,"#InventoryHost.Visible").getAsBoolean()&&property(page,"#Pack.Visible").getAsBoolean(),"Native UI shows face settings and Pack Up without empty item windows");
        for(var face:EnergyStoragePorts.Face.values())require(property(page,"#Port"+face.name()+".Text").getAsString().equals(face.name()+": "+EnergyStoragePorts.mode(storage,face).name()),"Every face shows its actual saved mode");
        return page;
    }
    private static void click(NativePlayerFixture player,CustomPage frame,String selector){
        var binding=Arrays.stream(frame.eventBindings).filter(b->selector.equals(b.selector)).findFirst().orElseThrow();
        var event=new CustomPageEvent(CustomPageEventType.Data,binding.data);var wire=java.lang.foreign.MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(wire,0);event=CustomPageEvent.toObject(wire);
        if(!com.hypixel.hytale.server.core.io.adapter.PacketAdapters.__handleInbound(player.packets(),event))player.player().getPageManager().getCustomPage().handleDataEvent(player.ref(),player.store(),event.data);
        player.world().consumeTaskQueue();
    }
    private static com.google.gson.JsonElement property(CustomPage frame,String selector){var cmd=Arrays.stream(frame.commands).filter(c->selector.equals(c.selector)).toList().getLast();return com.google.gson.JsonParser.parseString(cmd.data).getAsJsonObject().get("0");}
    private static void persistence(World world,MachineService machines,FactoryService factory,MachineState state,Path path,ResearchService research)throws Exception{
        state.energy=12345;EnergyStoragePorts.set(state,EnergyStoragePorts.Face.TOP,EnergyStoragePorts.Mode.DISABLED);factory.changed(world,state);
        var component=factory.component(world,state);component.data.energy=state.energy;
        var saved=FactoryComponent.CODEC.decode(FactoryComponent.CODEC.encode(component,new ExtraInfo()),new ExtraInfo());
        var ref=BlockModule.getBlockEntity(world,state.x,state.y,state.z);ref.getStore().putComponent(ref,FactoryComponent.getComponentType(),saved);
        state.energy=0;state.energyFaces=null;factory.register(world,state);
        require(state.energy==12345&&EnergyStoragePorts.mode(state,EnergyStoragePorts.Face.TOP)==EnergyStoragePorts.Mode.DISABLED,"Native block codec restores reserve and local face modes together");
        machines.save();try(var reloaded=new MachineService(path,machines.config,research,new AnomalyService(path));var restored=new FactoryService(reloaded,research)){
            reloaded.setFactory(restored);var next=reloaded.get(world,state.block());restored.register(world,next);
            require(next.energy==12345&&EnergyStoragePorts.mode(next,EnergyStoragePorts.Face.TOP)==EnergyStoragePorts.Mode.DISABLED,"Machine save reload reattaches the same authoritative reserve and modes");
        }
    }
    private static void loop(World world,MachineService machines,FactoryService factory,NativePlayerFixture player,boolean reverse){
        MachineState a,b;
        if(reverse){b=place(world,machines,factory,EnergyStoragePorts.ID,10,235,24,player);a=place(world,machines,factory,EnergyStoragePorts.ID,6,235,24,player);}
        else{a=place(world,machines,factory,EnergyStoragePorts.ID,6,235,24,player);b=place(world,machines,factory,EnergyStoragePorts.ID,10,235,24,player);}
        for(var state:List.of(a,b))for(var face:EnergyStoragePorts.Face.values())EnergyStoragePorts.set(state,face,EnergyStoragePorts.Mode.DISABLED);
        EnergyStoragePorts.set(a,EnergyStoragePorts.Face.FRONT,EnergyStoragePorts.Mode.OUTPUT);EnergyStoragePorts.set(a,EnergyStoragePorts.Face.BACK,EnergyStoragePorts.Mode.INPUT);
        EnergyStoragePorts.set(b,EnergyStoragePorts.Face.FRONT,EnergyStoragePorts.Mode.INPUT);EnergyStoragePorts.set(b,EnergyStoragePorts.Face.BACK,EnergyStoragePorts.Mode.OUTPUT);
        for(int x=6;x<=10;x++){place(world,machines,factory,"SM_Resonant_Conduit",x,235,23,player);place(world,machines,factory,"SM_Resonant_Conduit",x,235,25,player);}
        a.energy=500;factory.changed(world,a);factory.changed(world,b);machines.tick(world,.05);
        require(a.energy==480&&b.energy==20,"A storage ring cannot return fresh input in the same tick, independent of registration order "+reverse);
        for(int tick=0;tick<40;tick++){
            machines.tick(world,.05);require(a.energy+b.energy==500&&a.sentThisTick<=20&&b.sentThisTick<=20&&a.receivedThisTick<=20&&b.receivedThisTick<=20,"Storage ring conserves energy and shares each endpoint's per-tick input/output caps");
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
