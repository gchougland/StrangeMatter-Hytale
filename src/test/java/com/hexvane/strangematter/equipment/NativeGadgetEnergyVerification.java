package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.StackData;
import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.InventorySection;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.Cosmetic;
import com.hypixel.hytale.protocol.EquipmentUpdate;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.protocol.packets.inventory.UpdatePlayerInventory;
import com.hypixel.hytale.protocol.packets.window.OpenWindow;
import com.hypixel.hytale.protocol.packets.window.UpdateWindow;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.container.filter.*;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.bson.*;
import org.joml.Vector3d;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.HashMap;
import java.util.List;

/** Native stacks, codec, inventories and player equipment establish real energy ownership. */
public final class NativeGadgetEnergyVerification {
    public static void verify(World world,Path directory)throws Exception{
        NativeBatteryCapeVerification.verify(world);
        var scanner=new ItemStack("SM_Field_Scanner",1);
        require(GadgetEnergy.charge(scanner)==2000,"Fresh native crafting stack starts full without a refill on movement");
        var gun=new ItemStack("SM_Warp_Gun",1,37,100,null).withMetadata("SMPortalA",Codec.STRING,"retained-portal");
        var migrated=GadgetEnergy.normalize(gun);
        require(GadgetEnergy.charge(migrated)==3700&&migrated.getDurability()==3700&&migrated.getMaxDurability()==10000,"Legacy saved wear migrates its exact fraction into native numeric RE fields");
        require("retained-portal".equals(migrated.getFromMetadataOrNull("SMPortalA",Codec.STRING)),"Energy migration preserves portal identity");
        require(GadgetEnergy.normalize(migrated).equals(migrated),"Migration is idempotent and tooltip does not grow");
        require(GadgetEnergy.charge(GadgetEnergy.normalize(new ItemStack("SM_Warp_Gun",1,0,100,null)))==0,"Broken legacy item stays empty");
        require(GadgetEnergy.charge(scanner.withMetadata(GadgetEnergy.KEY,new BsonString("invalid")))==0,"Malformed new-format record cannot refill");
        require(GadgetEnergy.charge(scanner.withMetadata(GadgetEnergy.KEY,new BsonDocument("Version",new BsonInt32(99)).append("Charge",new BsonInt32(2000))))==0,"Unknown schema cannot refill");
        verifyFormatsAndPresentation(scanner);
        var inv=new SimpleItemContainer((short)3);inv.setItemStackForSlot((short)0,migrated,false);
        require(!GadgetEnergy.spend(inv,(short)0,migrated,4000,false)&&inv.getItemStack((short)0).equals(migrated),"Insufficient power rejects without mutation");
        try(var debit=GadgetEnergy.reserve(inv,(short)0,migrated,100,false)){require(debit!=null,"Valid action reserves energy");}
        require(inv.getItemStack((short)0).equals(migrated),"Failed effect rolls back its exact reservation");
        require(GadgetEnergy.spend(inv,(short)0,migrated,100,false),"Paid action succeeds");
        require(!GadgetEnergy.spend(inv,(short)0,migrated,100,false),"Stale activation cannot debit twice");
        var charged=inv.getItemStack((short)0);require(GadgetEnergy.charge(charged)==3600,"One debit per activation");
        require(GadgetEnergy.spend(inv,(short)0,charged,999999,true)&&inv.getItemStack((short)0).equals(charged),"Creative use does not refill stored charge");
        var pack=GadgetEnergy.withCharge(new ItemStack(GadgetEnergy.PACK,1),500);
        inv.setItemStackForSlot((short)1,pack,false);inv.setItemStackForSlot((short)2,GadgetEnergy.withCharge(scanner,1990),false);
        require(GadgetEnergy.transfer(inv,(short)1,inv,(short)2,100)==10,"Transfer clamps to free capacity");
        require(GadgetEnergy.charge(inv.getItemStack((short)1))==490&&GadgetEnergy.charge(inv.getItemStack((short)2))==2000,"Charge is conserved");
        require(StackData.decode(StackData.encode(inv.getItemStack((short)1))).equals(inv.getItemStack((short)1)),"Typed charge and tooltip survive native save codec");
        var ledger=new HoverboardLedger(directory.resolve("energy-board-check"));
        var receipt=ledger.prepare(UUID.randomUUID(),GadgetEnergy.withCharge(new ItemStack("SM_Hoverboard",1),500));
        require(ledger.reserve(receipt.id)&&ledger.mounted(receipt.id)&&ledger.spend(receipt.id,140,false),"Deployed board spends from owned persistent payload");
        var recovered=new HoverboardLedger(directory.resolve("energy-board-check"));
        require(recovered.charge(receipt.id)==360&&recovered.get(receipt.id).phase==HoverboardLedger.Phase.RETURNING,"Restart returns board with its spent charge, never refills");
        try(var fixture=NativePlayerFixture.create(world,"GadgetEnergyVerification",new Vector3d(24.5,18,24.5))){
            com.hypixel.hytale.server.core.entity.entities.Player.setGameMode(fixture.ref(),GameMode.Adventure,fixture.store());
            verifyNativeEquipmentUpdates(fixture,scanner);
            verifyEquipmentTracking(fixture);
            var armor=fixture.store().getComponent(fixture.ref(),InventoryComponent.Armor.getComponentType()).getInventory();
            require(armor.setItemStackForSlot(BatteryPackService.CHEST,pack,false).succeeded(),"Pack is accepted in native chest equipment slot");
            verifyWornPresentation(fixture,scanner);
            fixture.hotbar().setItemStackForSlot((short)0,GadgetEnergy.withCharge(scanner,0),false);
            fixture.hotbar().setItemStackForSlot((short)1,GadgetEnergy.withCharge(new ItemStack("SM_Arc_Projector",1),0),false);
            var service=new BatteryPackService();service.tick(world,.2);
            require(GadgetEnergy.charge(armor.getItemStack(BatteryPackService.CHEST))==460,"Worn pack uses one shared 40 RE transfer budget");
            require(GadgetEnergy.charge(fixture.hotbar().getItemStack((short)0))+GadgetEnergy.charge(fixture.hotbar().getItemStack((short)1))==40,"Inventory receives exactly the energy removed from pack");
            armor.setItemStackForSlot(BatteryPackService.CHEST,ItemStack.EMPTY,false);service.tick(world,.2);
            require(GadgetEnergy.charge(fixture.hotbar().getItemStack((short)0))+GadgetEnergy.charge(fixture.hotbar().getItemStack((short)1))==40,"Unequipping pack immediately stops transfer");
            fixture.save();service.cleanup(world);
        }
        System.out.println("NATIVE_GADGET_ENERGY_VERIFICATION_PASSED: legacy/v1/v2 migration, full/half/empty native charge bars, stable equipment identity, real hotbar/armor invalidation, safe idempotent packet tooltips, native no-wear assets, typed codec preservation, exact debits/refunds, conserved charging, chest pack lifecycle and durable board depletion.");
    }

    private static void verifyWornPresentation(NativePlayerFixture fixture,ItemStack scanner){
        var store=fixture.store();var ref=fixture.ref();
        var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());
        var utility=store.getComponent(ref,InventoryComponent.Utility.getComponentType());
        var model=store.getComponent(ref,ModelComponent.getComponentType());
        var skin=store.getComponent(ref,PlayerSkinComponent.getComponentType());
        var settings=store.getComponent(ref,PlayerSettings.getComponentType());
        var defaults=PlayerSettings.defaults();
        var equipment=InventoryUtils.createEquipmentUpdate(ref,store,defaults,armor,utility);
        require(GadgetEnergy.PACK.equals(equipment.armorIds[BatteryPackService.CHEST]),"Native default equipment update attaches the pack as chest armor");
        var hidden=new PlayerSettings(defaults.showEntityMarkers(),defaults.armorItemsPreferredPickupLocation(),defaults.weaponAndToolItemsPreferredPickupLocation(),
                defaults.usableItemsItemsPreferredPickupLocation(),defaults.solidBlockItemsPreferredPickupLocation(),defaults.miscItemsPreferredPickupLocation(),
                defaults.creativeSettings(),defaults.hideHelmet(),true,defaults.hideGauntlets(),defaults.hidePants(),defaults.voiceSettings());
        var hiddenEquipment=InventoryUtils.createEquipmentUpdate(ref,store,hidden,armor,utility);
        boolean canHide=fixture.world().getGameplayConfig().getPlayerConfig().getArmorVisibilityOption().canHideCuirass();
        require((canHide?"":GadgetEnergy.PACK).equals(hiddenEquipment.armorIds[BatteryPackService.CHEST]),"Native HideCuirass remains authoritative under the world's armor visibility policy");
        var item=armor.getInventory().getItemStack(BatteryPackService.CHEST).getItem();
        require(Arrays.equals(item.getArmor().toPacket().cosmeticsToHide,new Cosmetic[]{Cosmetic.Cape}),"Backpack targets only the cape and preserves the shirt/outerwear slot");
        var asset=CommonAssetRegistry.getByName(item.getModel());
        require(asset!=null&&CommonAssetRegistry.getByName(item.getTexture())!=null,"Native equipment references a published pack model and texture");
        var attachment=BsonDocument.parse(new String(asset.getBlob().join(),StandardCharsets.UTF_8));
        var chest=attachment.getArray("nodes").getFirst().asDocument();
        require("Chest".equals(chest.getString("name").getValue())&&chest.getDocument("shape").getDocument("settings").getBoolean("isPiece").getValue()
                &&!chest.getArray("children").isEmpty(),"Backpack geometry belongs to the native named Chest attachment piece");
        require(chest.getString("id").getValue().matches("[0-9]+")&&!attachment.containsKey("format"),"Wearable model uses native armor node IDs and export metadata");
        try(var hud=new GadgetHudService()){
            var hotbar=store.getComponent(ref,InventoryComponent.Hotbar.getComponentType());hotbar.setActiveSlot((byte)0,ref,store);
            fixture.hotbar().setItemStackForSlot((short)0,ItemStack.EMPTY,false);
            hud.tick(fixture.world(),.2);
            require(fixture.player().getHudManager().getCustomHud(GadgetHudService.KEY)==null,"Wearing a pack with empty hands never creates a HUD");
            fixture.hotbar().setItemStackForSlot((short)0,GadgetEnergy.withCharge(scanner,1000),false);
            hud.update(fixture.owner(),store,"Field Scanner","Ready","Aim at a subject",-1);
            require(fixture.player().getHudManager().getCustomHud(GadgetHudService.KEY)!=null,"Held supported gadget creates its HUD while wearing a pack");
            require(hudFlag(fixture,"#GadgetPack.Visible",true),"Held powered instrument shows its worn pack reserve");
            fixture.hotbar().setItemStackForSlot((short)0,new ItemStack("SM_Resonite_Ingot",1),false);hud.tick(fixture.world(),.2);
            require(fixture.player().getHudManager().getCustomHud(GadgetHudService.KEY)==null,"Switching to an unrelated item immediately removes the old instrument HUD without a pack-only fallback");
            hud.update(fixture.owner(),store,"Research Tablet","Research","Choose a topic",-1);
            require(hudFlag(fixture,"#GadgetPack.Visible",false),"An unrelated readout cannot expose a pack reserve merely because armor is worn");
            hud.active(fixture.owner(),store,GadgetEnergy.withCharge(new ItemStack("SM_Hoverboard",1),1000),"Hoverboard","Board engaged","Dismount to fold");
            require(hudFlag(fixture,"#GadgetPack.Visible",false)&&fixture.player().getHudManager().getCustomHud(GadgetHudService.KEY)!=null,"Active deployed board retains its own feedback but does not create a worn battery meter with unrelated hands");
            hud.clear(fixture.owner(),store);
            fixture.hotbar().setItemStackForSlot((short)0,GadgetEnergy.withCharge(scanner,1000),false);
            hud.update(fixture.owner(),store,"Field Scanner","Ready","Aim at a subject",-1);
            fixture.hotbar().setItemStackForSlot((short)0,ItemStack.EMPTY,false);hud.tick(fixture.world(),.2);
            require(fixture.player().getHudManager().getCustomHud(GadgetHudService.KEY)==null,"Putting away the supported gadget immediately hides its battery HUD");
        }
        require(store.getComponent(ref,ModelComponent.getComponentType())==model&&store.getComponent(ref,PlayerSkinComponent.getComponentType())==skin
                &&store.getComponent(ref,PlayerSettings.getComponentType())==settings,"Pack presentation preserves the player model, skin and deliberate client settings");
    }
    private static boolean hudFlag(NativePlayerFixture fixture,String selector,boolean expected){
        var packets=fixture.packets().ofType(CustomHud.class);var packet=packets.getLast();
        return packet.commands!=null&&Arrays.stream(packet.commands).anyMatch(c->selector.equals(c.selector)&&c.data!=null&&c.data.contains(Boolean.toString(expected)));
    }

    private static void verifyFormatsAndPresentation(ItemStack scanner){
        var originalDisplay=new ItemDisplayMetadata(Message.raw("Personal Scanner"),Message.raw("Custom instrument description."));
        var v1=scanner.withMetadata(ItemDisplayMetadata.KEYED_CODEC,originalDisplay)
                .withMetadata("TypedMarker",new BsonInt64(73))
                .withMetadata(GadgetEnergy.KEY,new BsonDocument("Version",new BsonInt32(1)).append("Charge",new BsonInt32(731)));
        v1=v1.withMetadata("SMGadgetOriginalDisplay",v1.getMetadata().get(ItemDisplayMetadata.KEY))
                .withMetadata(ItemDisplayMetadata.KEYED_CODEC,new ItemDisplayMetadata(Message.raw("Personal Scanner"),Message.raw("Old description. Energy: 731 / 2000 RE")));
        v1.setOverrideDroppedItemAnimation(true);
        var originalSerialized=StackData.encode(v1);var v2=GadgetEnergy.normalize(v1);
        require(GadgetEnergy.charge(v2)==731&&v2.getDurability()==731&&v2.getMaxDurability()==2000,"V1 exact charge survives migration rather than treating zero native wear fields as full");
        require(v2.getMetadata().getDocument(GadgetEnergy.KEY).equals(new BsonDocument("Version",new BsonInt32(2))),"V2 identity record contains only a stable version marker");
        require(v2.getMetadata().get("TypedMarker").isInt64()&&!v2.getMetadata().containsKey("SMGadgetOriginalDisplay"),"V1 cleanup preserves typed foreign data and restores original display identity");
        require(flatten(v2.getDisplayDescription()).equals("Custom instrument description."),"Migration removes obsolete changing energy text");
        require(StackData.encode(v1).equals(originalSerialized),"Migration cannot mutate the source metadata");
        require(StackData.decode(StackData.encode(v2)).equals(v2)&&GadgetEnergy.charge(StackData.decode(StackData.encode(v2)))==731,"Native typed save codec preserves exact V2 charge and original custom metadata");
        require(v2.getOverrideDroppedItemAnimation()&&StackData.decode(StackData.encode(v2)).getOverrideDroppedItemAnimation(),"Migration and native save retain the original dropped-animation flag");
        for(int amount:new int[]{2000,1000,0}){
            var value=GadgetEnergy.withCharge(v2,amount);var packet=value.toPacket();
            require(GadgetEnergy.charge(value)==amount&&packet.durability==amount&&packet.maxDurability==2000,"Full/half/empty native bar matches owned RE: "+amount);
            require(value.getOverrideDroppedItemAnimation()&&packet.overrideDroppedItemAnimation,"Repeated debit/recharge preserves the native dropped-animation flag");
            require(value.isEquivalentType(v2)&&!value.equals(v2),"Numeric charge changes remain same native equipment type but invalidate stale exact actions");
            require(GadgetEnergy.normalize(value)==value,"Repeated normalization is a no-op on normalized V2 stacks");
            verifyProjection(value,"Custom instrument description.\n\nEnergy: "+amount+" / 2000 RE");
        }
        for(String id:List.of("SM_Field_Scanner","SM_Echo_Vacuum","SM_Anomaly_Resonator","SM_Warp_Gun","SM_Chrono_Blister",
                "SM_Graviton_Hammer","SM_Hoverboard","SM_Echoform_Imprinter",GadgetEnergy.PACK,"SM_Gravitic_Manipulator","SM_Arc_Projector")){
            var item=new ItemStack(id,1).getItem();
            require(!item.isRepairable()&&!item.getDurabilityLossOnDeath()&&item.getDurabilityLossOnHit()==0,"Loaded native powered asset disables repair and wear: "+id);
            require(item.getTool()==null||item.getTool().getDurabilityLossBlockTypes()==null||item.getTool().getDurabilityLossBlockTypes().length==0,"Powered native mining tool does not spend durability: "+id);
        }
    }

    private static void verifyProjection(ItemStack stack,String expected){
        var before=StackData.encode(stack);var cached=stack.toPacket();var cachedBefore=cached.clone();
        var other=new ItemStack("SM_Resonite_Ingot",2).toPacket();
        var broken=cached.clone();broken.metadata="{invalid JSON";
        var map=new HashMap<Integer,ItemWithAllMetadata>();map.put(0,cached);map.put(1,other);map.put(2,broken);
        var source=new InventorySection(map,(short)3);var update=new UpdatePlayerInventory();
        update.hotbar=source;update.storage=source;update.armor=source;update.utility=source;update.tools=source;update.backpack=source;
        GadgetEnergyPresentation.project(update);
        for(var section:List.of(update.hotbar,update.storage,update.armor,update.utility,update.tools,update.backpack)){
            require(section!=source&&section.items!=map&&section.capacity==source.capacity,"Wire projection copies native section/map before replacing gadget display");
            require(section.items.get(1)==other&&section.items.get(2)==broken,"Non-gadgets and malformed foreign packets are preserved without replacement");
            var shown=section.items.get(0);var decoded=packetStack(shown);
            require(shown!=cached&&GadgetEnergy.charge(decoded)==GadgetEnergy.charge(stack),"Projected packet is distinct and preserves numeric charge");
            require(shown.overrideDroppedItemAnimation&&shown.quality==cached.quality,"Packet projection retains original dropped animation and quality");
            require(flatten(decoded.getDisplayDescription()).equals(expected),"Projected tooltip shows the exact current numeric RE once");
            require(flatten(decoded.getFromMetadataOrNull(ItemDisplayMetadata.KEYED_CODEC).getName()).equals("Personal Scanner"),"Projection retains custom item name");
        }
        var once=update.hotbar.items.get(0).clone();GadgetEnergyPresentation.project(update);
        require(update.hotbar.items.get(0).equals(once),"A repeated outbound projection is idempotent and does not append another energy line");
        require(cached.equals(cachedBefore)&&source.items.get(0)==cached&&map.get(0)==cached&&StackData.encode(stack).equals(before),"Projection preserves cached native item packet, original map and persistent stack");
        var open=new OpenWindow();open.inventory=source;GadgetEnergyPresentation.project(open);
        var refresh=new UpdateWindow();refresh.inventory=source;GadgetEnergyPresentation.project(refresh);
        require(open.inventory.items.get(0).equals(once)&&refresh.inventory.items.get(0).equals(once),"Machine windows project exactly the same safe numeric tooltip as player inventory");
    }

    private static ItemStack packetStack(ItemWithAllMetadata packet){
        return new ItemStack(packet.itemId,packet.quantity,packet.durability,packet.maxDurability,packet.quality,
                packet.metadata==null?null:BsonDocument.parse(packet.metadata));
    }
    private static String flatten(Message message){
        var value=new StringBuilder(message.getAnsiMessage());for(var child:message.getChildren())value.append(flatten(child));return value.toString();
    }
    private static void verifyNativeEquipmentUpdates(NativePlayerFixture fixture,ItemStack scanner){
        var hotbar=fixture.store().getComponent(fixture.ref(),InventoryComponent.Hotbar.getComponentType());
        hotbar.setActiveSlot((byte)0,fixture.ref(),fixture.store());
        hotbar.getInventory().setItemStackForSlot((short)0,GadgetEnergy.withCharge(scanner,2000),false);
        hotbar.getChangeEvents().clear();hotbar.setOutdatedEquipment(false);
        for(int amount:new int[]{1900,1700,1800,0,2000}){
            var previous=hotbar.getActiveItem();hotbar.getInventory().setItemStackForSlot((short)0,GadgetEnergy.withCharge(previous,amount),false);
            require(!hotbar.getChangeEvents().isEmpty(),"Charge update still emits native inventory transaction events");
            tick(fixture,new InventorySystems.LegacyHotbarChangeStatSystem());
            require(!hotbar.consumeOutdatedEquipment(),"Actual native hotbar system preserves the held model during debit/recharge, including empty: "+amount);
            hotbar.getChangeEvents().clear();
        }
        hotbar.getInventory().setItemStackForSlot((short)0,GadgetEnergy.withCharge(new ItemStack("SM_Arc_Projector",1),12000),false);
        tick(fixture,new InventorySystems.LegacyHotbarChangeStatSystem());
        require(hotbar.consumeOutdatedEquipment(),"A genuinely different held gadget still invalidates native equipment");hotbar.getChangeEvents().clear();
        var armor=fixture.store().getComponent(fixture.ref(),InventoryComponent.Armor.getComponentType());
        armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,GadgetEnergy.withCharge(new ItemStack(GadgetEnergy.PACK,1),500),false);
        GadgetEnergyArmorUpdates.remember(armor);tick(fixture,new InventorySystems.LegacyArmorChangeStatSystem());GadgetEnergyArmorUpdates.preserveModel(armor);
        require(armor.consumeOutdatedEquipment(),"Equipping pack retains native model invalidation");armor.getChangeEvents().clear();
        var worn=armor.getInventory().getItemStack(BatteryPackService.CHEST);
        armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,GadgetEnergy.withCharge(worn,460),false);
        GadgetEnergyArmorUpdates.remember(armor);tick(fixture,new InventorySystems.LegacyArmorChangeStatSystem());
        GadgetEnergyArmorUpdates.preserveModel(armor);
        require(!armor.consumeOutdatedEquipment()&&!armor.getChangeEvents().isEmpty(),"Pure pack depletion preserves its native model and leaves inventory events intact");armor.getChangeEvents().clear();
        armor.setOutdatedEquipment(true); // Native client settings also invalidate gear visibility without an inventory event.
        armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,GadgetEnergy.withCharge(armor.getInventory().getItemStack(BatteryPackService.CHEST),450),false);
        GadgetEnergyArmorUpdates.remember(armor);tick(fixture,new InventorySystems.LegacyArmorChangeStatSystem());GadgetEnergyArmorUpdates.preserveModel(armor);
        require(armor.consumeOutdatedEquipment(),"A simultaneous client visibility change retains its preexisting armor invalidation");armor.getChangeEvents().clear();
        armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,armor.getInventory().getItemStack(BatteryPackService.CHEST).withMetadata("ForeignAppearance",Codec.STRING,"new-look"),false);
        GadgetEnergyArmorUpdates.remember(armor);tick(fixture,new InventorySystems.LegacyArmorChangeStatSystem());GadgetEnergyArmorUpdates.preserveModel(armor);
        require(armor.consumeOutdatedEquipment(),"A foreign metadata change must retain armor model invalidation");armor.getChangeEvents().clear();
        armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,ItemStack.EMPTY,false);
        GadgetEnergyArmorUpdates.remember(armor);tick(fixture,new InventorySystems.LegacyArmorChangeStatSystem());GadgetEnergyArmorUpdates.preserveModel(armor);
        require(armor.consumeOutdatedEquipment(),"Removing the pack retains armor model invalidation");armor.getChangeEvents().clear();
    }
    private static void tick(NativePlayerFixture fixture,EntityTickingSystem<EntityStore> system){
        fixture.store().forEachChunk(system.getQuery(),(chunk,commands)->{
            for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(fixture.ref()))system.tick(.05f,i,chunk,fixture.store(),commands);
        });
    }
    private static void verifyEquipmentTracking(NativePlayerFixture fixture){
        try(var observer=NativePlayerFixture.create(fixture.world(),"BatteryArmorObserver",new Vector3d(25.5,18,24.5))){
            var store=fixture.store();var ref=fixture.ref();
            var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());
            var visible=store.ensureAndGetComponent(ref,EntityTrackerSystems.Visible.getComponentType());
            var ownerViewer=store.getComponent(ref,EntityTrackerSystems.EntityViewer.getComponentType());
            var observerViewer=store.getComponent(observer.ref(),EntityTrackerSystems.EntityViewer.getComponentType());
            ownerViewer.visible.add(ref);observerViewer.visible.add(ref);
            visible.visibleTo.put(ref,ownerViewer);visible.visibleTo.put(observer.ref(),observerViewer);visible.newlyVisibleTo.clear();
            var sync=new InventorySystems.SyncEquipmentSystem(EntityTrackerSystems.Visible.getComponentType());
            store.getComponent(ref,InventoryComponent.Hotbar.getComponentType()).setOutdatedEquipment(false);
            store.getComponent(ref,InventoryComponent.Utility.getComponentType()).setOutdatedEquipment(false);
            try{
                for(int charge:new int[]{60000,59960,0,-1}){
                    ownerViewer.updates.remove(ref);observerViewer.updates.remove(ref);
                    var before=armor.getInventory().getItemStack(BatteryPackService.CHEST);
                    armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,charge<0?ItemStack.EMPTY:
                            GadgetEnergy.withCharge(charge==60000?new ItemStack(GadgetEnergy.PACK,1):before,charge),false);
                    GadgetEnergyArmorUpdates.remember(armor);tick(fixture,new InventorySystems.LegacyArmorChangeStatSystem());
                    GadgetEnergyArmorUpdates.preserveModel(armor);tick(fixture,sync);armor.getChangeEvents().clear();
                    for(var viewer:List.of(ownerViewer,observerViewer)){
                        var update=viewer.updates.remove(ref);
                        if(charge==59960||charge==0){require(update==null,"Native equipment tracker sends no model reset on pack drain, including empty");continue;}
                        require(update!=null&&update.toUpdatesArray()!=null,"Native equipment tracker queues actual armor updates for wearer and observer");
                        var packet=Arrays.stream(update.toUpdatesArray()).filter(EquipmentUpdate.class::isInstance).map(EquipmentUpdate.class::cast).findFirst().orElseThrow();
                        require((charge<0?"":GadgetEnergy.PACK).equals(packet.armorIds[BatteryPackService.CHEST]),"Native tracker publishes the backpack on equip and clears it on removal");
                    }
                    if(charge==0){
                        visible.newlyVisibleTo.put(observer.ref(),observerViewer);tick(fixture,sync);visible.newlyVisibleTo.clear();
                        var update=observerViewer.updates.remove(ref);
                        require(update!=null&&Arrays.stream(update.toUpdatesArray()).anyMatch(p->p instanceof EquipmentUpdate e&&GadgetEnergy.PACK.equals(e.armorIds[BatteryPackService.CHEST])),"A newly observing client receives the worn empty backpack without requiring another equip");
                    }
                }
            }finally{visible.visibleTo.remove(ref);visible.visibleTo.remove(observer.ref());visible.newlyVisibleTo.clear();ownerViewer.visible.remove(ref);observerViewer.visible.remove(ref);}
        }
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
