package com.hexvane.strangematter.anomaly;

import com.hexvane.strangematter.anomaly.memory.*;
import com.hexvane.strangematter.equipment.NativePlayerFixture;
import com.hypixel.hytale.builtin.adventure.memories.MemoriesPlugin;
import com.hypixel.hytale.builtin.adventure.memories.component.PlayerMemories;
import com.hypixel.hytale.builtin.adventure.memories.memories.Memory;
import com.hypixel.hytale.builtin.adventure.memories.page.MemoriesPage;
import com.hypixel.hytale.builtin.adventure.memories.window.MemoriesWindow;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.protocol.packets.player.UpdateMemoriesCount;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.CommonAssetValidator;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.resources.UniverseResourceType;
import com.hypixel.hytale.server.core.universe.resources.DiskUniverseResourceStorageProvider.DiskUniverseResourceStorage;
import com.hypixel.hytale.server.core.universe.world.World;
import com.google.gson.JsonParser;
import org.joml.Vector3d;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual native Memories catalogue, bench events, player storage and universe-resource disk reload. */
public final class NativeAnomalyMemoriesVerification {
    public static void verify(World world)throws Exception {
        var plugin=MemoriesPlugin.get();require(plugin!=null,"Native Memories plugin initialized before Strange Matter");
        var catalogue=plugin.getAllMemories().get(AnomalyMemoryProvider.CATEGORY);
        require(catalogue!=null&&catalogue.size()==6,"The actual native catalogue contains all six anomaly memories");
        var originals=plugin.getRecordedMemories();var position=new Vector3d(9,220,9);
        var service=new AnomalyService(Files.createTempDirectory("sm-native-memory-fields-"));service.naturalGeneration=false;
        service.setSuppressionHook((w,p,t)->true);service.setMemoryEncounterHook(AnomalyMemories::collectNearby);
        var fields=new ArrayList<AnomalyRecord>();
        try {
            plugin.clearRecordedMemories();verifyContract(catalogue);
            UUID playerId;
            try(var player=NativePlayerFixture.create(world,"AnomalyMemoryCollector",position)){
                playerId=player.owner().getUuid();Player.setGameMode(player.ref(),GameMode.Adventure,player.store());
                var field=field(world,AnomalyType.GRAVITY,position);fields.add(field);
                require(player.store().getComponent(player.ref(),PlayerMemories.getComponentType())==null,"Memories remain locked until native bench unlock");
                require(!AnomalyMemories.collect(world,player.owner(),field),"First contact cannot bypass the native Memories unlock");
                var pouch=new PlayerMemories();pouch.setMemoriesCapacity(3);player.store().putComponent(player.ref(),PlayerMemories.getComponentType(),pouch);
                Player.setGameMode(player.ref(),GameMode.Creative,player.store());
                require(!AnomalyMemories.collect(world,player.owner(),field),"Creative observation does not collect an Adventure memory");
                Player.setGameMode(player.ref(),GameMode.Adventure,player.store());
                field.enabled=false;require(!AnomalyMemories.collect(world,player.owner(),field),"Inactive fields cannot collect memories");
                field.enabled=true;field.contained=true;require(!AnomalyMemories.collect(world,player.owner(),field),"Contained fields cannot collect memories");
                field.contained=false;field.world="another-world";require(!AnomalyMemories.collect(world,player.owner(),field),"Cross-world field identity cannot collect memories");field.world=world.getName();
                var far=field(world,AnomalyType.THOUGHTWELL,new Vector3d(position).add(40,0,0));
                AnomalyMemories.collectNearby(world,far);require(pouch.getRecordedMemories().isEmpty(),"Configured nearby collection does not reach a field forty blocks away");
                for(int i=0;i<3;i++){
                    var observed=field(world,AnomalyType.values()[i],position);observed.natural=false;observed.released=true;
                    require(AnomalyMemories.collect(world,player.owner(),observed),"Released ordinary field collects its native type memory");
                    require(!AnomalyMemories.collect(world,player.owner(),field(world,observed.type,position)),"Another identity of the same anomaly type is deduplicated");
                }
                require(!AnomalyMemories.collect(world,player.owner(),field(world,AnomalyType.values()[3],position))&&pouch.getRecordedMemories().size()==3,"Full native memory pouch delays the fourth type without dropping earlier entries");
                require(!player.packets().ofType(UpdateMemoriesCount.class).isEmpty(),"Collection updates the native memory pouch count packet");
                player.save();
            }
            try(var player=NativePlayerFixture.load(world,playerId,"AnomalyMemoryCollector",position);var ui=new Pages(player)){
                var pouch=player.store().getComponent(player.ref(),PlayerMemories.getComponentType());
                require(pouch!=null&&pouch.getMemoriesCapacity()==3&&pouch.getRecordedMemories().size()==3,"Real native player disk reload preserves typed memory pouch and capacity");
                pouch.setMemoriesCapacity(48);
                // The recurring field hook retries already-seen fields after unlocking or capacity changes.
                for(var type:AnomalyType.values()){
                    var observed=service.spawn(type,world,new Vector3d(position),false);observed.released=true;
                    if(type==AnomalyType.WARP_GATE)observed.portalChannel=1;
                }
                service.tick(world,.05);
                require(pouch.getRecordedMemories().size()==6,"Recurring encounters collect all missing types, including a Warp Gun endpoint, despite suppression");
                var violet=field(world,AnomalyType.WARP_GATE,position);violet.portalChannel=2;
                require(!AnomalyMemories.collect(world,player.owner(),violet),"Cyan, violet and ordinary warp gates share one memory entry");
                var window=new MemoriesWindow();require(window.onOpen0(player.ref(),player.store()),"Native memory pouch window opens");
                var pouchRows=window.getData().getAsJsonArray("memories");require(pouchRows.size()==6,"Native pouch window exposes all six typed memories");
                for(var row:pouchRows)require(row.getAsJsonObject().get("categoryIcon").getAsString().equals(categoryIcon(false)),"Native pouch finds the same custom category icon");

                var initial=ui.open();String categorySelector=category(initial).selector.replace("#Button","");
                require(has(initial,categorySelector+"#CategoryIcon.AssetPath",categoryIcon(false))&&has(initial,categorySelector+"#NewMemoryIndicator.Visible","true"),"Native bench shows ordinary category icon and unrecorded memory indicator");
                ui.click(category(initial));var unknown=ui.last();
                require(count(unknown,"#EmptyBackground.Visible")==6&&count(unknown,"#Icon.AssetPath")==0,"Unrecorded entries remain six anonymous native exploration tiles");
                ui.click(binding(unknown,"#BackButton"));ui.click(binding(ui.last(),"#RecordButton"));
                require(pouch.getRecordedMemories().isEmpty()&&plugin.getRecordedMemories().containsAll(catalogue),"Actual native bench Record event deposits all six and empties the player pouch");
                require(!AnomalyMemories.collect(world,player.owner(),field(world,AnomalyType.GRAVITY,position)),"Globally recorded type cannot refill an emptied pouch");

                var complete=ui.open();categorySelector=category(complete).selector.replace("#Button","");
                require(has(complete,categorySelector+"#CategoryIcon.AssetPath",categoryIcon(true))&&has(complete,categorySelector+"#CompleteCategoryCounter.Visible","true"),"Six recorded entries activate the native completed icon and counter");
                ui.click(category(complete));var revealed=ui.last();
                require(count(revealed,"#Icon.AssetPath")==6&&has(revealed,"#CategoryCount.Text","6/6"),"Native category reveals six icons and6/6 progress");
                var selected=new AnomalyMemory(AnomalyType.GRAVITY);ui.click(memory(revealed,selected.getId()));
                requireDetails(ui.last(),selected);
                ui.closePage();player.save();
                var reload=MemoriesPlugin.class.getDeclaredMethod("onAssetsLoad");reload.setAccessible(true);reload.invoke(plugin);
                require(plugin.getAllMemories().get(AnomalyMemoryProvider.CATEGORY).equals(catalogue),"Native provider catalogue rebuild preserves all anomaly types");
                verifyGlobalDiskReload(plugin,world,player);
            }
            try(var player=NativePlayerFixture.load(world,playerId,"AnomalyMemoryCollector",position)){
                require(player.store().getComponent(player.ref(),PlayerMemories.getComponentType()).getRecordedMemories().isEmpty(),"Deposited memory pouch stays empty after another real player reload");
                require(!AnomalyMemories.collect(world,player.owner(),field(world,AnomalyType.THOUGHTWELL,position)),"Recorded memories stay deduplicated after player reload");
            }
            System.out.println("NATIVE_ANOMALY_MEMORIES_VERIFICATION_PASSED: six-type catalogue and codecs; native UI@2x validation/localization; unlock/Adventure/capacity/range/active guards; released and Warp Gun dedup; recurring encounter retry; native pouch and bench Record/complete/details; real player disk reload and fresh native universe-resource reload; provider catalogue rebuild.");
        }finally{
            service.stopWorld(world);plugin.clearRecordedMemories();var restore=new PlayerMemories();restore.setMemoriesCapacity(Math.max(1,originals.size()));originals.forEach(restore::recordMemory);plugin.recordPlayerMemories(restore);
        }
    }
    private static void verifyContract(Set<Memory> catalogue){
        var distinct=new HashSet<Memory>();
        for(var type:AnomalyType.values()){
            var memory=new AnomalyMemory(type);require(catalogue.contains(memory)&&memory.getId().equals("SM_Anomaly_"+type.name()),"Stable native memory identity "+type);
            var extra=new ExtraInfo();var restored=Memory.CODEC.decode(Memory.CODEC.encode(memory,extra),new ExtraInfo());
            require(restored.equals(memory)&&restored.hashCode()==memory.hashCode()&&((AnomalyMemory)restored).type()==type,"Registered polymorphic native codec retains distinct type identity");distinct.add(restored);
            require(I18nModule.get().getMessage("en-US",memory.getTitle())!=null&&I18nModule.get().getMessage("fr-FR",memory.getTitle())!=null,"Native localization and English fallback resolve memory title");
            require(memory.getDescription()!=null&&I18nModule.get().getMessage("en-US",memory.getDescription().getMessageId())!=null,"Native memory has a translated description");
            validateIcon(memory.getIconPath());
        }
        require(distinct.size()==6,"Native Memory base equality cannot collapse the six anomaly types");
        for(boolean complete:new boolean[]{false,true}){
            String logical=categoryIcon(complete);validateIcon(logical);String physical=logical.substring(0,logical.length()-4)+"@2x.png";
            var asset=CommonAssetRegistry.getByName(physical);require(asset!=null,"Native asset registry contains category2x artwork");
            var bytes=asset.getBlob().join();require(bytes.length>=24&&ByteBuffer.wrap(bytes,16,4).getInt()==256&&ByteBuffer.wrap(bytes,20,4).getInt()==256,"Category icon matches native256px2x convention");
        }
        require(I18nModule.get().getMessage("en-US","server.memories.categories."+AnomalyMemoryProvider.CATEGORY+".title")!=null,"Category title uses the native localization path");
    }
    @SuppressWarnings("unchecked")
    private static void verifyGlobalDiskReload(MemoriesPlugin plugin,World world,NativePlayerFixture player)throws Exception {
        var type=(UniverseResourceType<Object>)fieldValue(plugin,"recordedMemoriesType");var source=Universe.get().getResource(type);
        var directory=Files.createTempDirectory("sm-native-memories-resource-");
        var writer=new DiskUniverseResourceStorage(directory,Universe.get().getStorageManager(),true);writer.save(type,source).get(10,TimeUnit.SECONDS);
        require(Files.size(directory.resolve(type.getId()+".json"))>0,"Native universe resource writer persists the actual Memories resource");
        var restored=new DiskUniverseResourceStorage(directory,Universe.get().getStorageManager(),true).loadAll(List.of(type)).get(type);
        var expected=plugin.getRecordedMemories();require(restored!=source&&((Set<Memory>)fieldValue(restored,"memories")).equals(expected),"Fresh native disk resource decode restores the complete global memory set");
        var current=MemoriesPlugin.class.getDeclaredField("recordedMemories");current.setAccessible(true);var previous=current.get(plugin);
        try{current.set(plugin,restored);require(!AnomalyMemories.collect(world,player.owner(),field(world,AnomalyType.GRAVITY,player.store().getComponent(player.ref(),TransformComponent.getComponentType()).getPosition())),"Freshly reloaded global records still prevent recollection");}
        finally{current.set(plugin,previous);}
    }
    private static AnomalyRecord field(World world,AnomalyType type,Vector3d position){return new AnomalyRecord(UUID.randomUUID(),type,world.getName(),new Vector3d(position),true);}
    private static void validateIcon(String path){var extra=new ExtraInfo();new CommonAssetValidator().accept(path,extra.getValidationResults());require(!extra.getValidationResults().hasFailed(),"Native common-asset validator resolves memory icon, including2x fallback: "+path);}
    private static String categoryIcon(boolean complete){return "UI/Custom/Pages/Memories/categories/"+AnomalyMemoryProvider.CATEGORY+(complete?"Complete":"")+".png";}
    private static Object fieldValue(Object object,String name)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private static void requireDetails(CustomPage page,AnomalyMemory memory){
        var expected=Map.of("#MemoryName.Text",memory.getTitle(),"#MemoryDescription.TextSpans",memory.getDescription().getMessageId(),"#MemoryIcon.AssetPath",memory.getIconPath());
        for(var entry:expected.entrySet())if(!has(page,entry.getKey(),entry.getValue()))throw new AssertionError("Native memory detail missing "+entry.getKey()+"="+entry.getValue()+"; emitted commands="+Arrays.stream(page.commands).map(c->c.selector+"="+c.data).toList());
    }
    private static boolean has(CustomPage page,String selector,String value){return Arrays.stream(page.commands).anyMatch(c->selector.equals(c.selector)&&c.data!=null&&c.data.contains(value));}
    private static long count(CustomPage page,String suffix){return Arrays.stream(page.commands).filter(c->c.selector!=null&&c.selector.endsWith(suffix)).count();}
    private static CustomUIEventBinding binding(CustomPage page,String selector){return Arrays.stream(page.eventBindings).filter(e->selector.equals(e.selector)).findFirst().orElseThrow();}
    private static CustomUIEventBinding category(CustomPage page){return Arrays.stream(page.eventBindings).filter(e->{var data=JsonParser.parseString(e.data).getAsJsonObject();return data.has("Category")&&data.get("Category").getAsString().equals(AnomalyMemoryProvider.CATEGORY);}).findFirst().orElseThrow();}
    private static CustomUIEventBinding memory(CustomPage page,String id){return Arrays.stream(page.eventBindings).filter(e->{var data=JsonParser.parseString(e.data).getAsJsonObject();return data.has("MemoryId")&&data.get("MemoryId").getAsString().equals(id);}).findFirst().orElseThrow();}
    private static final class Pages implements AutoCloseable{
        final NativePlayerFixture player;
        Pages(NativePlayerFixture player){this.player=player;}
        CustomPage open()throws Exception{closePage();player.player().getPageManager().openCustomPage(player.ref(),player.store(),new MemoriesPage(player.owner(),new BlockPosition(9,220,9)));var result=last();ack();return result;}
        CustomPage last(){var packet=player.packets().ofType(CustomPage.class).getLast();var bytes=MemorySegment.ofArray(new byte[packet.computeSize()]);packet.serialize(bytes,0);return CustomPage.toObject(bytes);}
        void click(CustomUIEventBinding binding)throws Exception{ack();var event=new CustomPageEvent(CustomPageEventType.Data,binding.data);var bytes=MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(bytes,0);player.player().getPageManager().handleEvent(player.ref(),player.store(),CustomPageEvent.toObject(bytes));
            // Native category rebuilds are immediate, but SelectMemory.sendUpdate queues
            // its details delta on the world. Exercise that actual publication before ACK.
            player.world().consumeTaskQueue();ack();}
        void ack()throws Exception{var pending=(AtomicInteger)fieldValue(player.player().getPageManager(),"customPageRequiredAcknowledgments");for(int i=0;pending.get()>0&&i<20;i++)player.player().getPageManager().handleEvent(player.ref(),player.store(),new CustomPageEvent(CustomPageEventType.Acknowledge,null));require(pending.get()==0,"Native bench acknowledges all page packets");}
        void closePage()throws Exception{if(player.player().getPageManager().getCustomPage()!=null)player.player().getPageManager().setPage(player.ref(),player.store(),Page.None);ack();}
        @Override public void close()throws Exception{closePage();}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
