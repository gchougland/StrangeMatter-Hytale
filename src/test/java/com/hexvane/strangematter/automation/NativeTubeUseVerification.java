package com.hexvane.strangematter.automation;

import com.hexvane.strangematter.StrangeMatterConfig;
import com.hexvane.strangematter.StrangeMatterInteraction;
import com.hexvane.strangematter.anomaly.AnomalyService;
import com.hexvane.strangematter.equipment.EquipmentService;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.ResearchService;
import com.hexvane.strangematter.util.WorldAccess;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;
import java.nio.file.*;
import java.util.*;
import static com.hexvane.strangematter.automation.TubeEndpoints.*;
import static com.hypixel.hytale.server.core.universe.world.SetBlockSettings.*;

/** Loaded native block Use selection, production deferred dispatch, configuration events and idle flow packets. */
public final class NativeTubeUseVerification {
    private static final Position TUBE=new Position(16,245,16);
    private record Cell(Position position,int id,int rotation,int filler){}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    /** ACK only received packets, including the native closing SetPage receipt. */
    private static final class Client {
        final NativePlayerFixture subject;
        final Map<String,String> bindings=new HashMap<>();
        int cursor,frames;boolean custom;
        Client(NativePlayerFixture subject){this.subject=subject;}
        void receive(){
            subject.world().consumeTaskQueue();
            var packets=subject.packets().packets;
            while(cursor<packets.size()){
                var packet=packets.get(cursor++);
                if(packet instanceof CustomPage page){
                    var bytes=java.lang.foreign.MemorySegment.ofArray(new byte[page.computeSize()]);page.serialize(bytes,0);
                    var decoded=CustomPage.toObject(bytes);
                    if(decoded.clear)bindings.clear();
                    if(decoded.eventBindings!=null)for(var binding:decoded.eventBindings)if(binding.type==CustomUIEventBindingType.Activating)bindings.put(binding.selector,binding.data);
                    custom=true;frames++;acknowledge(subject);
                }else if(packet instanceof SetPage&&custom){
                    custom=false;bindings.clear();acknowledge(subject);
                }
            }
        }
        void click(String selector){
            receive();require(custom,"Native client has an open acknowledged page for "+selector);
            String payload=bindings.get(selector);require(payload!=null,"Native page actually bound "+selector);
            int before=frames;
            var event=new CustomPageEvent(CustomPageEventType.Data,payload);
            var bytes=java.lang.foreign.MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(bytes,0);
            subject.player().getPageManager().handleEvent(subject.ref(),subject.store(),CustomPageEvent.toObject(bytes));
            receive();require(frames==before+1,"Click produced exactly one acknowledged native frame: "+selector+" ("+before+" -> "+frames+")");
        }
    }
    public static void verify(World world)throws Exception{
        var store=world.getEntityStore().getStore();store.assertThread();
        var chunk=Objects.requireNonNull(WorldAccess.loaded(world,0));var saved=new ArrayList<Cell>();
        for(int x=15;x<=17;x++)for(int y=244;y<=247;y++)for(int z=15;z<=17;z++){
            saved.add(new Cell(new Position(x,y,z),WorldAccess.block(chunk,x,y,z),WorldAccess.rotation(chunk,x,y,z),WorldAccess.filler(chunk,x,y,z)));
            WorldAccess.set(chunk,x,y,z,"Empty",NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
        }
        var directory=Files.createTempDirectory("sm-native-tube-use-");var anomalies=new AnomalyService(directory);anomalies.naturalGeneration=false;
        try(var research=new ResearchService(directory);var machines=new MachineService(directory,new StrangeMatterConfig(),research,anomalies);
            var tubes=new TubeService(directory,null);
            var subject=NativePlayerFixture.create(world,"TubeUse",new Vector3d(16.5,245,19.5));
            var distant=NativePlayerFixture.create(world,"TubeDistant",new Vector3d(16.5,275,19.5))){
            var equipment=new EquipmentService(research,anomalies,machines);equipment.setTubeService(tubes);
            var client=new Client(subject);
            try{
                WorldAccess.set(chunk,TUBE.x(),TUBE.y(),TUBE.z(),TubeService.ID);
                for(int face=0;face<6;face++){var p=TUBE.offset(face);WorldAccess.set(chunk,p.x(),p.y(),p.z(),"SM_Resonite_Chest");}
                tubes.placed(world,TUBE.vector(),subject.owner().getUuid());
                var base=BlockType.getAssetMap().getAsset(TubeService.ID);
                subject.store().getComponent(subject.ref(),InventoryComponent.Hotbar.getComponentType()).setActiveSlot((byte)0,subject.ref(),store);
                for(int mask=-1;mask<64;mask++){
                    var type=mask<0?base:base.getBlockForState(String.format(Locale.ROOT,"Connection%02d",mask));
                    require(type!=null,"Native tube connection state exists: "+mask);
                    WorldAccess.set(chunk,TUBE.x(),TUBE.y(),TUBE.z(),BlockType.getAssetMap().getIndex(type.getId()),type,0,0,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);
                    openThroughUse(world,client,equipment,type,true);
                    close(client);
                }
                subject.hotbar().setItemStackForSlot((short)0,new ItemStack("SM_Field_Scanner",1));
                openThroughUse(world,client,equipment,base,false);
                require(subject.hotbar().getItemStack((short)0).getItemId().equals("SM_Field_Scanner"),"Using a tube with another gadget held does not consume or activate it");
                require(has(subject.packets().ofType(CustomPage.class).getLast(),"#Selected.Text","East"),"Initial native page selects the east face");
                client.click("#Take");
                require(has(subject.packets().ofType(CustomPage.class).getLast(),"#Take.Text","> TAKE"),"Received TAKE event selects the draft before saving");
                client.click("#Apply");
                require(tubes.current(world,TUBE).component().face(0).mode==TubeConfiguration.Mode.EXTRACT,"TAKE button applies to the selected native face");
                client.click("#Face1");client.click("#Send");client.click("#Apply");
                require(tubes.current(world,TUBE).component().face(1).mode==TubeConfiguration.Mode.INSERT,"SEND button applies separately without changing TAKE");
                var frame=subject.packets().ofType(CustomPage.class).getLast();
                require(has(frame,"#Face0.Text","TAKE")&&has(frame,"#Face1.Text","SEND"),"Native page packets show saved modes on every face button");
                verifyFilterControls(client,tubes);
                close(client);
                for(int face=2;face<6;face++){
                    var config=new TubeConfiguration();config.mode=face%2==0?TubeConfiguration.Mode.EXTRACT:TubeConfiguration.Mode.INSERT;
                    require(tubes.configure(subject.owner(),store,world,tubes.current(world,TUBE).component().id(),TUBE,face,config),"Configure actual endpoint "+face);
                }
                // Native PlayerRef starts with an origin cache. Its normal UpdatePlayerRef
                // system publishes the ECS position during a tick, not during addToStore.
                var beforeTick=new Vector3d(subject.owner().getTransform().getPosition());
                store.tick(.01f);
                for(var player:List.of(subject,distant)){
                    var actual=store.getComponent(player.ref(),com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType()).getPosition();
                    require(world.getPlayerRefs().contains(player.owner())&&player.owner().getTransform().getPosition().distanceSquared(actual)<1e-10,
                        "Complete native tick tracks and publishes the player position: cached="+player.owner().getTransform().getPosition()+", entity="+actual);
                }
                require(subject.owner().getTransform().getPosition().distanceSquared(TubeService.center(TUBE))<TubeConnectionIndicators.RANGE*TubeConnectionIndicators.RANGE,
                    "Native cue viewer is in range after normal position publication: before="+beforeTick+", after="+subject.owner().getTransform().getPosition());
                subject.packets().packets.clear();distant.packets().packets.clear();
                for(int i=0;i<4;i++)tubes.tick(world,.125);
                var cues=cues(subject);require(cues.size()==24,"Six configured idle faces emit one bounded pulse each eighth second: "+cues.size());
                require(cues(distant).isEmpty()&&tubes.activeFlights(world)==0,"Idle configuration is visible nearby without items in flight or distant broadcasts");
                for(int face=0;face<6;face++){
                    var first=cues.get(face);var last=cues.get(18+face);boolean take=face%2==0;
                    require(first.particleSystemId.equals(take?"SM_Tube_Take":"SM_Tube_Send"),"Distinct native effect IDs for TAKE and SEND "+face);
                    double a=radial(first,face),b=radial(last,face);
                    require(a>0&&a<.5&&b>0&&b<.5&&(take?b<a:b>a),"Pulse moves along the actual connection axis and correct direction "+face+": "+a+" -> "+b);
                    var buffer=java.lang.foreign.MemorySegment.ofArray(new byte[first.computeSize()]);first.serialize(buffer,0);
                    var decoded=SpawnParticleSystem.toObject(buffer);require(decoded.particleSystemId.equals(first.particleSystemId),"Actual native direction cue packet serializes");
                }
                var node=tubes.current(world,TUBE);var budget=new TubeConnectionIndicators();var many=new ArrayList<TubeService.Node>();
                for(int i=0;i<40;i++)many.add(new TubeService.Node(new Position(10+i%10,245,12+i/10),node.ref(),node.component()));
                budget.tick(world,.125,0,many,p->true);
                require(budget.selectedCount()==TubeConnectionIndicators.MAX_PER_PLAYER&&budget.selectedCount()<=TubeConnectionIndicators.MAX_CONNECTIONS,"Dense idle configuration selection enforces both viewer and global cue budgets");
                for(int face=0;face<6;face++)tubes.configure(subject.owner(),store,world,node.component().id(),TUBE,face,new TubeConfiguration());
                subject.packets().packets.clear();tubes.tick(world,.125);
                require(cues(subject).isEmpty(),"Disabling every face stops new cues immediately");
                var take=new TubeConfiguration();take.mode=TubeConfiguration.Mode.EXTRACT;tubes.configure(subject.owner(),store,world,node.component().id(),TUBE,0,take);
                tubes.tick(world,.125);require(!cues(subject).isEmpty(),"Reenabled idle face resumes its cue");
                WorldAccess.set(chunk,TUBE.x(),TUBE.y(),TUBE.z(),"Empty");tubes.removed(world,TUBE.vector());subject.packets().packets.clear();tubes.tick(world,.125);
                require(cues(subject).isEmpty(),"Removed tube cannot leave an emitter or tracked entity behind");
                System.out.println("NATIVE_TUBE_USE PASS: 65 native Use states, empty and gadget hand, real configuration events, six idle direction axes, packet serialization, range and cue budgets, off/removal cleanup");
            }finally{equipment.cleanup(world);tubes.stopWorld(world);}
        }finally{
            for(var c:saved){var p=c.position;WorldAccess.set(chunk,p.x(),p.y(),p.z(),c.id,BlockType.getAssetMap().getAsset(c.id),c.rotation,c.filler,NO_UPDATE_STATE|NO_SEND_PARTICLES|NO_UPDATE_NEIGHBOR_CONNECTIONS|FORCE_CHANGED);}
        }
    }
    private static void verifyFilterControls(Client client,TubeService tubes){
        var subject=client.subject;var held=subject.hotbar().getItemStack((short)0);
        var sample=new ItemStack("Wood_Softwood_Planks",7).withMetadata("TubeUi",com.hypixel.hytale.codec.Codec.STRING,"exact details");
        subject.hotbar().setItemStackForSlot((short)0,sample);
        client.click("#Sample0");client.click("#Match");client.click("#Apply");
        var current=tubes.current(subject.world(),TUBE).component().face(1);
        require(current.match==TubeConfiguration.Match.EXACT&&TubeStacks.same(TubeStacks.decode(current.samples[0]),TubeStacks.quantity(sample,1)),"Actual sample click preserves native item metadata and exact matching mode");
        require(TubeStacks.same(subject.hotbar().getItemStack((short)0),sample),"Copying a filter sample never consumes or changes the held native stack");
        var frame=subject.packets().ofType(CustomPage.class).getLast();
        require(has(frame,"#Icon0.ItemId",sample.getItemId())&&has(frame,"#HeldIcon.ItemId",sample.getItemId())&&has(frame,"#SamplePrompt0.Visible","false"),"Native packets show the real centered sample and held item icons without the empty prompt overlay");
        client.click("#Clear0");client.click("#Apply");require(tubes.current(subject.world(),TUBE).component().face(1).samples[0]==null,"The aligned Clear control removes only its filter sample");
        client.click("#Sample4");client.click("#Match");
        frame=subject.packets().ofType(CustomPage.class).getLast();require(has(frame,"#SamplesGroup.Visible","false")&&has(frame,"#ResourceGroup.Visible","true"),"Resource matching swaps panels without overlapping hidden sample hit targets");
        client.click("#Resource");client.click("#Filter");client.click("#Step");client.click("#LeavePlus");client.click("#FillPlus");client.click("#PriorityPlus");client.click("#BatchMinus");client.click("#Apply");
        current=tubes.current(subject.world(),TUBE).component().face(1);
        require(current.match==TubeConfiguration.Match.RESOURCE&&!current.resource.isBlank()&&current.exclude&&current.leaveBehind==10&&current.fillUpTo==10&&current.priority==1&&current.batch==4,"All resource, exclusion and transfer limit controls persist through actual native page events");
        require(current.samples[4]!=null&&TubeStacks.same(subject.hotbar().getItemStack((short)0),sample),"Changing match mode keeps the other sample and the complete held stack");
        client.click("#Match");frame=subject.packets().ofType(CustomPage.class).getLast();require(has(frame,"#SamplesGroup.Visible","true")&&has(frame,"#ResourceGroup.Visible","false"),"Returning to item matching restores its five sample controls");
        client.click("#Clear4");client.click("#Filter");client.click("#LeaveMinus");client.click("#FillMinus");client.click("#PriorityMinus");client.click("#BatchPlus");client.click("#Apply");
        current=tubes.current(subject.world(),TUBE).component().face(1);require(current.mode==TubeConfiguration.Mode.INSERT&&current.leaveBehind==0&&current.fillUpTo==0&&current.priority==0&&current.batch==5&&!current.exclude,"Less and More restore normal limits without changing the selected SEND direction");
        subject.hotbar().setItemStackForSlot((short)0,held);
    }
    private static void openThroughUse(World world,Client client,EquipmentService equipment,BlockType type,boolean empty){
        var subject=client.subject;
        var root=RootInteraction.getAssetMap().getAsset(type.getInteractions().get(InteractionType.Use));
        require(root!=null&&root.getInteractionIds().length==1,"Native block Use resolves a single server action");
        var interaction=Interaction.getAssetMap().getAsset(root.getInteractionIds()[0]);
        require(interaction instanceof StrangeMatterInteraction,"Loaded block uses the mod's registered native interaction codec");
        var action=StrangeMatterInteraction.CODEC.encode((StrangeMatterInteraction)interaction,new ExtraInfo()).asDocument().getString("Action").getValue();
        require(action.equals("machine"),"Loaded action reaches block dispatch before held inventory guards");
        var manager=subject.store().getComponent(subject.ref(),InteractionModule.get().getInteractionManagerComponent());
        var context=InteractionContext.forInteraction(manager,subject.ref(),InteractionType.Use,subject.store());
        context.getMetaStore().putMetaObject(Interaction.TARGET_BLOCK,new BlockPosition(TUBE.x(),TUBE.y(),TUBE.z()));
        require(ItemStack.isEmpty(context.getHeldItem())==empty,"Native Use context has expected real hand state");
        equipment.interact(context,action);world.consumeTaskQueue();
        require(subject.player().getPageManager().getCustomPage() instanceof TubePage,"Production queued use opens TubePage for "+type.getId());
        var frame=subject.packets().ofType(CustomPage.class).getLast();
        require(Arrays.stream(frame.commands).anyMatch(c->c.type==CustomUICommandType.Append&&"StrangeMatter/TubeConfig.ui".equals(c.text)),"Native configuration page append is actually sent");
        client.receive();
    }
    private static void acknowledge(NativePlayerFixture p){p.player().getPageManager().handleEvent(p.ref(),p.store(),new CustomPageEvent(CustomPageEventType.Acknowledge,null));}
    private static void close(Client client){var p=client.subject;p.player().getPageManager().setPage(p.ref(),p.store(),Page.None,false);client.receive();require(!client.custom,"Native closing SetPage was received and acknowledged");}
    private static List<SpawnParticleSystem> cues(NativePlayerFixture p){return p.packets().ofType(SpawnParticleSystem.class).stream().filter(c->c.particleSystemId.equals("SM_Tube_Take")||c.particleSystemId.equals("SM_Tube_Send")).toList();}
    private static double radial(SpawnParticleSystem p,int face){return (p.position.x-TUBE.x()-.5)*DX[face]+(p.position.y-TUBE.y()-.5)*DY[face]+(p.position.z-TUBE.z()-.5)*DZ[face];}
    private static boolean has(CustomPage page,String selector,String text){return Arrays.stream(page.commands).anyMatch(c->selector.equals(c.selector)&&c.data!=null&&c.data.contains(text));}
}
