package com.hexvane.strangematter.equipment;

import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.protocol.packets.entities.EntityUpdates;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.Arrays;

/** Native tracker queues and transport verify reversible, viewer-only cape coverage. */
public final class NativeBatteryCapeVerification {
    public static void verify(World world){
        try(var wearer=NativePlayerFixture.create(world,"BatteryCapeWearer",new Vector3d(24.5,18,25.5));
            var observer=NativePlayerFixture.create(world,"BatteryCapeObserver",new Vector3d(25.5,18,25.5))){
            var store=wearer.store();var ref=wearer.ref();
            var owner=wearer.owner();var model=store.getComponent(ref,ModelComponent.getComponentType());
            var defaults=PlayerSettings.defaults();store.putComponent(ref,PlayerSettings.getComponentType(),defaults);
            var source=new PlayerSkin();
            source.bodyCharacteristic="original-body";source.haircut="original-hair";source.undertop="original-undershirt";
            source.overtop="Robe_Blazen_Wizard";source.cape="Cape_New_Beginning.White.Neck_Piece";
            var original=new PlayerSkin(source);var canonical=new PlayerSkinComponent(source);
            store.putComponent(ref,PlayerSkinComponent.getComponentType(),canonical);
            var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());
            var utility=store.getComponent(ref,InventoryComponent.Utility.getComponentType());
            var visible=store.ensureAndGetComponent(ref,EntityTrackerSystems.Visible.getComponentType());
            var self=store.getComponent(ref,EntityTrackerSystems.EntityViewer.getComponentType());
            var other=store.getComponent(observer.ref(),EntityTrackerSystems.EntityViewer.getComponentType());
            self.visible.add(ref);other.visible.add(ref);
            visible.visibleTo.put(ref,self);visible.visibleTo.put(observer.ref(),other);visible.newlyVisibleTo.clear();
            var projection=new BatteryCapePresentation();
            var nativeSkin=new EntityTrackerSystems.EntitySkin(EntityTrackerSystems.Visible.getComponentType(),PlayerSkinComponent.getComponentType());
            var nativeSend=new EntityTrackerSystems.SendPackets(EntityTrackerSystems.EntityViewer.getComponentType());
            try{
                armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,ItemStack.EMPTY,false);
                tick(wearer,nativeSkin);var untouched=self.updates.get(ref);
                projection.project(store,self);
                require(self.updates.get(ref)==untouched&&queued(self,ref).skin==source,"Unworn pack leaves native skin packets untouched");
                clear(ref,self,other);

                armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,new ItemStack(GadgetEnergy.PACK,1),false);
                var equipment=InventoryUtils.createEquipmentUpdate(ref,store,defaults,armor,utility);
                self.queueUpdate(ref,equipment);other.queueUpdate(ref,equipment);
                self.queueRemove(ref,ComponentUpdateType.Nameplate);
                projection.project(store,self);projection.project(store,other);
                var ownUpdate=queued(self,ref);var otherUpdate=queued(other,ref);
                withoutCape(ownUpdate.skin,source);withoutCape(otherUpdate.skin,source);
                require(ownUpdate!=otherUpdate&&ownUpdate.skin!=otherUpdate.skin&&ownUpdate.skin!=source,"Each viewer receives a separate skin update and skin copy");
                require(Arrays.asList(self.updates.get(ref).toUpdatesArray()).contains(equipment)
                        &&Arrays.asList(self.updates.get(ref).toRemovedArray()).contains(ComponentUpdateType.Nameplate),"Projection preserves the native equipment update and unrelated removal");
                require(source.equals(original)&&store.getComponent(ref,PlayerSkinComponent.getComponentType())==canonical,"Cape suppression never edits canonical skin or component identity");
                tick(wearer,nativeSend);tick(observer,nativeSend);
                require(lastSkin(wearer).cape==null&&lastSkin(observer).cape==null,"Native SendPackets delivers the cape-free presentation to wearer and observer");
                for(int charge:new int[]{59960,0,60000}){
                    armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,GadgetEnergy.withCharge(armor.getInventory().getItemStack(BatteryPackService.CHEST),charge),false);
                    projection.project(store,self);projection.project(store,other);
                    require(!self.updates.containsKey(ref)&&!other.updates.containsKey(ref),"Drain, empty charge and recharge do not resend skin: "+charge);
                }

                var changed=new PlayerSkin(source);changed.overtop="Scavenger_Poncho";changed.cape="Cape_Royal_Emissary";
                canonical=new PlayerSkinComponent(changed);store.putComponent(ref,PlayerSkinComponent.getComponentType(),canonical);
                tick(wearer,nativeSkin);var nativeQueued=queued(self,ref);
                require(nativeQueued==queued(other,ref)&&nativeQueued.skin==changed,"Native tracker initially shares its canonical skin update between viewers");
                projection.project(store,self);projection.project(store,other);
                withoutCape(queued(self,ref).skin,changed);withoutCape(queued(other,ref).skin,changed);
                require("Cape_Royal_Emissary".equals(nativeQueued.skin.cape),"Projection cannot mutate a shared native update or a later authoritative skin");
                clear(ref,self,other);

                try(var late=NativePlayerFixture.create(world,"BatteryCapeLateViewer",new Vector3d(26.5,18,25.5))){
                    var lateViewer=store.getComponent(late.ref(),EntityTrackerSystems.EntityViewer.getComponentType());
                    lateViewer.visible.add(ref);visible.visibleTo.put(late.ref(),lateViewer);visible.newlyVisibleTo.put(late.ref(),lateViewer);
                    tick(wearer,nativeSkin);projection.project(store,lateViewer);withoutCape(queued(lateViewer,ref).skin,changed);
                    tick(late,nativeSend);require(lastSkin(late).cape==null,"A newly connected viewer receives the cape-free skin without another equip");
                    visible.visibleTo.remove(late.ref());visible.newlyVisibleTo.clear();lateViewer.visible.remove(ref);
                }

                var disguise=new PlayerSkin(changed);disguise.overtop="viewer-only-shirt";disguise.haircut="viewer-only-hair";disguise.face="viewer-only-face";
                var foreign=new PlayerSkinUpdate(disguise);var foreignBefore=new PlayerSkin(disguise);
                self.queueUpdate(ref,foreign);other.queueUpdate(ref,foreign);
                projection.project(store,self);projection.project(store,other);
                withoutCape(queued(self,ref).skin,disguise);withoutCape(queued(other,ref).skin,disguise);
                require(foreign.skin==disguise&&disguise.equals(foreignBefore)&&canonical.getPlayerSkin()==changed,"A viewer-specific queued disguise retains every non-cape field without modifying its packet or canonical skin");
                clear(ref,self,other);projection.project(store,self);projection.project(store,other);
                require(!self.updates.containsKey(ref)&&!other.updates.containsKey(ref),"Stable canonical state does not overwrite a viewer-specific disguise on the following tick");

                var hidden=hidden(defaults);store.putComponent(ref,PlayerSettings.getComponentType(),hidden);
                var hiddenEquipment=InventoryUtils.createEquipmentUpdate(ref,store,hidden,armor,utility);
                boolean packVisible=GadgetEnergy.PACK.equals(hiddenEquipment.armorIds[BatteryPackService.CHEST]);
                require(BatteryCapePresentation.visiblePack(store,ref)==packVisible,"Cape coverage follows the native world and HideCuirass armor policy exactly");
                projection.project(store,self);projection.project(store,other);
                if(!packVisible){
                    require(queued(self,ref).skin.equals(changed)&&queued(other,ref).skin.equals(changed),"Hiding armor restores the latest exact cape and leaves the shirt intact");
                    clear(ref,self,other);projection.project(store,self);require(!self.updates.containsKey(ref),"Hidden armor does not repeatedly rebuild the skin");
                }
                store.putComponent(ref,PlayerSettings.getComponentType(),defaults);
                projection.project(store,self);projection.project(store,other);clear(ref,self,other);

                armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,ItemStack.EMPTY,false);
                projection.project(store,self);projection.project(store,other);
                require(queued(self,ref).skin.equals(changed)&&queued(other,ref).skin.equals(changed),"Unequip restores the latest canonical cape, not a stale cached avatar");
                clear(ref,self,other);projection.project(store,self);require(!self.updates.containsKey(ref),"Restoration is sent once");

                armor.getInventory().setItemStackForSlot(BatteryPackService.CHEST,new ItemStack(GadgetEnergy.PACK,1),false);
                projection.project(store,self);clear(ref,self,other);
                self.visible.remove(ref);projection.project(store,self);self.visible.add(ref);
                projection.project(store,self);withoutCape(queued(self,ref).skin,changed);
                clear(ref,self,other);
                require(store.getComponent(ref,PlayerSkinComponent.getComponentType())==canonical&&canonical.getPlayerSkin()==changed
                        &&"Cape_Royal_Emissary".equals(changed.cape)&&store.getComponent(ref,ModelComponent.getComponentType())==model
                        &&wearer.owner()==owner&&store.getComponent(ref,PlayerSettings.getComponentType())==defaults,
                        "Visibility reentry preserves current skin, player identity, model and client settings");
                store.tryRemoveComponent(ref,PlayerSkinComponent.getComponentType());
                self.queueRemove(ref,ComponentUpdateType.PlayerSkin);var skinRemoval=self.updates.get(ref);
                projection.project(store,self);
                require(self.updates.get(ref)==skinRemoval&&skinRemoval.toUpdatesArray()==null,"A native skin removal for a morph cannot resurrect the player's avatar");
                store.putComponent(ref,PlayerSkinComponent.getComponentType(),canonical);
            }finally{
                visible.visibleTo.remove(ref);visible.visibleTo.remove(observer.ref());visible.newlyVisibleTo.clear();
                self.visible.remove(ref);other.visible.remove(ref);clear(ref,self,other);
            }
            wearer.reattach();
            var joinedRef=wearer.ref();var joinedViewer=store.getComponent(joinedRef,EntityTrackerSystems.EntityViewer.getComponentType());
            var joinedSkin=store.getComponent(joinedRef,PlayerSkinComponent.getComponentType());joinedViewer.visible.add(joinedRef);
            try{
                projection.project(store,joinedViewer);withoutCape(queued(joinedViewer,joinedRef).skin,joinedSkin.getPlayerSkin());
                clear(joinedRef,joinedViewer);
                store.getComponent(joinedRef,InventoryComponent.Armor.getComponentType()).getInventory().setItemStackForSlot(BatteryPackService.CHEST,ItemStack.EMPTY,false);
                projection.project(store,joinedViewer);
                require(queued(joinedViewer,joinedRef).skin.equals(joinedSkin.getPlayerSkin()),"Native removal/reentry derives coverage from current equipment and restores the untouched skin on unequip");
            }finally{joinedViewer.visible.remove(joinedRef);clear(joinedRef,joinedViewer);}
        }
        System.out.println("NATIVE_BATTERY_CAPE_VERIFICATION_PASSED: exact cape-only viewer copies, native skin/send queues, wearer and observer, shared packet isolation, shirt preservation, no recharge resets, live skin changes, late viewers, visibility reentry and latest-skin restoration on hide/unequip.");
    }

    private static PlayerSettings hidden(PlayerSettings defaults){return new PlayerSettings(defaults.showEntityMarkers(),defaults.armorItemsPreferredPickupLocation(),defaults.weaponAndToolItemsPreferredPickupLocation(),
            defaults.usableItemsItemsPreferredPickupLocation(),defaults.solidBlockItemsPreferredPickupLocation(),defaults.miscItemsPreferredPickupLocation(),
            defaults.creativeSettings(),defaults.hideHelmet(),true,defaults.hideGauntlets(),defaults.hidePants(),defaults.voiceSettings());}
    private static void withoutCape(PlayerSkin skin,PlayerSkin source){var expected=new PlayerSkin(source);expected.cape=null;require(skin.equals(expected),"Only the literal cape field is removed; every other skin field is exact");}
    private static PlayerSkinUpdate queued(EntityTrackerSystems.EntityViewer viewer,com.hypixel.hytale.component.Ref<EntityStore> ref){
        var entry=viewer.updates.get(ref);require(entry!=null,"Expected one skin transition in the native viewer queue");
        var skins=Arrays.stream(entry.toUpdatesArray()).filter(PlayerSkinUpdate.class::isInstance).map(PlayerSkinUpdate.class::cast).toList();
        require(skins.size()==1,"Viewer receives exactly one authoritative skin update");return skins.getFirst();
    }
    private static PlayerSkin lastSkin(NativePlayerFixture viewer){
        return Arrays.stream(viewer.packets().ofType(EntityUpdates.class).getLast().updates).flatMap(update->Arrays.stream(update.updates))
                .filter(PlayerSkinUpdate.class::isInstance).map(PlayerSkinUpdate.class::cast).findFirst().orElseThrow().skin;
    }
    private static void clear(com.hypixel.hytale.component.Ref<EntityStore> ref,EntityTrackerSystems.EntityViewer... viewers){for(var viewer:viewers)viewer.updates.remove(ref);}
    private static void tick(NativePlayerFixture fixture,EntityTickingSystem<EntityStore> system){
        fixture.store().forEachChunk(system.getQuery(),(chunk,commands)->{for(int i=0;i<chunk.size();i++)if(chunk.getReferenceTo(i).equals(fixture.ref()))system.tick(.05f,i,chunk,fixture.store(),commands);});
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
