package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.anomaly.*;
import com.hexvane.strangematter.machine.MachineService;
import com.hexvane.strangematter.research.*;
import com.hexvane.strangematter.util.InventoryOps;
import com.hexvane.strangematter.effects.GadgetEffects;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.*;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;
import org.joml.Vector3i;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Held tools use native charging interactions; authoritative aim and inventory are checked again at completion. */
public final class EquipmentService {
    static final long HAMMER_SWING_NANOS=700_000_000L;
    private final ResearchService research;private final AnomalyService anomalies;private final MachineService machines;
    private final Map<UUID,Acquisition> acquisitions=new ConcurrentHashMap<>();
    private final Map<UUID,Integer> frequencies=new ConcurrentHashMap<>();
    private final Map<String,Long> cooldowns=new ConcurrentHashMap<>();
    private final LaboratoryFields fields;
    private final LaboratoryProjectiles projectiles;
    private final MobilityTools mobility;
    private final Map<String,Double> hudTime=new ConcurrentHashMap<>();
    private record Acquisition(String world,String item,String subject,UUID anomaly,ResearchType discipline,int amount,long began){}
    private record Aim(Vector3d eye,Vector3d direction,Vector3i block,Vector3i air,double distance){}
    public EquipmentService(ResearchService research,AnomalyService anomalies,MachineService machines){this.research=research;this.anomalies=anomalies;this.machines=machines;fields=new LaboratoryFields(machines);projectiles=new LaboratoryProjectiles(anomalies,fields,machines.dataDirectory());mobility=new MobilityTools(research.hud(),machines.dataDirectory());}
    public boolean capsuleInFlight(String token){return projectiles.inFlight(token);}
    public void interact(InteractionContext context,String action){
        var ref=context.getEntity();if(ref==null||!ref.isValid())return;var store=ref.getStore();var world=store.getExternalData().getWorld();
        var held=context.getHeldItem();var contextContainer=context.getHeldItemContainer();short slot=context.getHeldItemSlot();int sectionId=context.getHeldItemSectionId();
        var raw=context.getTargetBlock();var machinePos=raw==null?null:new Vector3i(raw.x,raw.y,raw.z);
        world.execute(()->{
            if(!ref.isValid()||ref.getStore()!=store)return;var p=store.getComponent(ref,PlayerRef.getComponentType());var player=store.getComponent(ref,Player.getComponentType());if(p==null||player==null)return;
            if("machine".equals(action)){
                var target=machinePos;
                if(!openBlock(p,store,target))openBlock(p,store,TargetUtil.getTargetBlockOrigin(ref,8,store));
                return;
            }
            // Deferred interaction work must reserve the live entity inventory, not a captured
            // container that could have been replaced while the interaction was queued.
            var sectionType=InventoryComponent.getComponentTypeById(sectionId);
            var section=sectionType==null?null:store.getComponent(ref,sectionType);
            var container=section==null?contextContainer:section.getInventory();
            if(section instanceof ActiveSlotInventoryComponent active&&active.getActiveSlot()!=slot)return;
            if(held==null||held.isEmpty()||container==null||slot<0||slot>=container.getCapacity())return;
            var actual=container.getItemStack(slot);if(actual==null||!actual.equals(held))return;
            String id=actual.getItemId();String cd=p.getUuid()+":"+(id.equals("SM_Graviton_Hammer")&&action.startsWith("hammer")?"hammer_swing":action);
            var motion=store.getComponent(ref,MovementStatesComponent.getComponentType());
            boolean crouching=motion!=null&&motion.getMovementStates().crouching;
            boolean restoring=restoresEchoform(id,action,crouching);
            if(actual.getMaxDurability()>0&&actual.isBroken()&&!action.equals("cancel")&&!restoring&&!(id.equals("SM_Warp_Gun")&&action.equals("use"))){say(p,"This tool is depleted. Repair it before using it again.");return;}
            long now=System.nanoTime();if(now<cooldowns.getOrDefault(cd,0L))return;cooldowns.put(cd,now+(action.startsWith("hammer")?HAMMER_SWING_NANOS:150_000_000L));
            if(restoring){mobility.useImprinter(p,store,null,"imprint_revert");return;}
            var aim=aim(ref,store,id.equals("SM_Warp_Gun")||id.equals("SM_Chrono_Blister")?48:12);if(aim==null)return;
            switch(id){
                case "SM_Research_Tablet"->research.openTablet(p,store);
                case "SM_Research_Notes"->research.useNotes(p,store,actual,TargetUtil.getTargetBlockOrigin(ref,8,store));
                case "SM_Field_Scanner"->{if(action.equals("scan_start"))begin(p,store,aim,id,false);else if(action.equals("scan_complete"))completeScan(p,store,aim);else if(action.equals("cancel"))acquisitions.remove(p.getUuid());}
                case "SM_Echo_Vacuum"->{if(action.equals("vacuum_start"))begin(p,store,aim,id,true);else if(action.equals("vacuum_complete"))completeCapture(p,store,aim);else if(action.equals("cancel"))acquisitions.remove(p.getUuid());}
                case "SM_Anomaly_Resonator"->resonate(p,world,aim,action);
                case "SM_Containment_Capsule"->say(p,"Use the Echo Vacuum while carrying an empty capsule to extract a field safely.");
                case "SM_Warp_Gun"->portal(p,store,aim,container,slot,actual,action);
                case "SM_Chrono_Blister"->{if(action.equals("chrono_fire")&&wear(container,slot,actual,1,player.getGameMode()==GameMode.Creative)){projectiles.chrono(world,p.getUuid(),aim.eye,aim.direction);GadgetEffects.use(world,"SM_Chrono_Muzzle",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));say(p,"Temporal blister launched.");}}
                case "SM_Graviton_Hammer"->{if(action.startsWith("hammer"))hammer(p,store,aim,container,slot,actual,action);}
                case "SM_Hoverboard"->mobility.useHoverboard(p,store);
                case "SM_Echoform_Imprinter"->mobility.useImprinter(p,store,TargetUtil.getTargetEntity(ref,5,store),action);
                default->{if(id.startsWith("SM_Containment_Capsule_"))release(p,store,aim,container,slot,actual);}
            }
        });
    }
    static boolean restoresEchoform(String item,String action,boolean crouching){
        return "SM_Echoform_Imprinter".equals(item)&&("imprint_revert".equals(action)||"secondary".equals(action)||"use".equals(action)||(crouching&&("imprint_start".equals(action)||"imprint_complete".equals(action))));
    }
    private boolean openBlock(PlayerRef player,Store<EntityStore> store,Vector3i target){
        if(target==null||target.y<0||target.y>=ChunkUtil.HEIGHT)return false;
        var world=store.getExternalData().getWorld();
        if(world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(target.x,target.z))==null)return false;
        var transform=store.getComponent(player.getReference(),TransformComponent.getComponentType());
        if(transform==null||transform.getPosition().distanceSquared(new Vector3d(target).add(.5,.5,.5))>64)return false;
        var type=world.getBlockType(target.x,target.y,target.z);if(type==null)return false;
        String base=type.getDefaultStateKey();if(base==null)base=type.getId();
        if(MachineService.IDS.contains(base)||MachineService.IDS.contains(type.getId())){machines.open(player,store,target);return true;}
        if(Set.of("SM_Resonite_Door","SM_Resonite_Trapdoor").contains(base)){fields.toggleDoor(world,target,type);return true;}
        return false;
    }
    private Aim aim(Ref<EntityStore> ref,Store<EntityStore> store,double range){
        var t=store.getComponent(ref,TransformComponent.getComponentType());var h=store.getComponent(ref,HeadRotation.getComponentType());if(t==null||h==null)return null;
        var eye=new Vector3d(t.getPosition()).add(0,ModelComponent.getEyeHeight(ref,store),0);var direction=h.getDirection().normalize();var world=store.getExternalData().getWorld();Vector3i previous=null;
        for(double d=0;d<=range;d+=.15){var point=new Vector3d(direction).mul(d).add(eye);var pos=new Vector3i((int)Math.floor(point.x),(int)Math.floor(point.y),(int)Math.floor(point.z));
            if(pos.equals(previous))continue;
            if(pos.y<0||pos.y>=ChunkUtil.HEIGHT||world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.x,pos.z))==null)return new Aim(eye,direction,null,previous,d);
            var type=world.getBlockType(pos.x,pos.y,pos.z);if(type!=null&&type.getMaterial()!=BlockMaterial.Empty)return new Aim(eye,direction,pos,previous,d);
            previous=pos;
        }
        return new Aim(eye,direction,null,previous,range);
    }
    private Optional<AnomalyRecord> targeted(World world,Aim aim,double range){return anomalies.inView(world,aim.eye,aim.direction,Math.min(range,aim.distance+.1));}
    private void begin(PlayerRef p,Store<EntityStore> store,Aim aim,String item,boolean vacuum){
        var world=store.getExternalData().getWorld();var anomaly=targeted(world,aim,vacuum?8:12);
        if(anomaly.isPresent()){
            var a=anomaly.get();if(!vacuum&&!a.scannable()){say(p,"Transported or artificial anomalies do not grant new observations.");return;}
            if(vacuum&&InventoryOps.count(inventory(p,store),"SM_Containment_Capsule")==0){say(p,"Carry an empty containment capsule.");return;}
            acquisitions.put(p.getUuid(),new Acquisition(world.getName(),item,"anomaly:"+a.id,a.id,ResearchType.fromName(a.type.researchType),10,System.nanoTime()));
            GadgetEffects.use(world,vacuum?"SM_Vacuum_Intake":"SM_Scanner_Lock",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));
            return;
        }
        acquisitions.remove(p.getUuid());say(p,"No unoccluded research subject in range.");
    }
    private boolean stillAimed(Acquisition a,World world,Aim aim){
        if(!a.world.equals(world.getName()))return false;
        if(a.anomaly!=null)return targeted(world,aim,a.item.equals("SM_Echo_Vacuum")?8:12).map(x->x.id.equals(a.anomaly)).orElse(false);
        return false;
    }
    private Acquisition ready(PlayerRef p,Store<EntityStore> store,Aim aim,String item){
        var a=acquisitions.remove(p.getUuid());if(a==null||!item.equals(a.item)||System.nanoTime()-a.began<1_900_000_000L||!stillAimed(a,store.getExternalData().getWorld(),aim)){say(p,"Acquisition interrupted. Hold steady on the same subject for two seconds.");return null;}return a;
    }
    private void completeScan(PlayerRef p,Store<EntityStore> store,Aim aim){
        var a=ready(p,store,aim,"SM_Field_Scanner");if(a==null)return;
        if(a.anomaly!=null&&!anomalies.get(a.anomaly).map(AnomalyRecord::scannable).orElse(false))return;
        boolean fresh=research.scan(p.getUuid(),a.subject,a.discipline,a.amount);say(p,fresh?"Recorded +"+a.amount+" "+a.discipline.displayName()+" observations.":"This subject has already been recorded.");
        if(fresh)GadgetEffects.use(store.getExternalData().getWorld(),"SM_Scanner_Complete",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));
    }
    private void completeCapture(PlayerRef p,Store<EntityStore> store,Aim aim){
        var a=ready(p,store,aim,"SM_Echo_Vacuum");if(a==null||a.anomaly==null)return;var inventory=inventory(p,store);
        if(InventoryOps.count(inventory,"SM_Containment_Capsule")==0){say(p,"The empty capsule is missing.");return;}
        // A singleton capsule frees its own slot; a stack needs one free slot for its unique token.
        short slot=-1;boolean freeSlot=false;for(short i=0;i<inventory.getCapacity();i++){var s=inventory.getItemStack(i);if(s==null||s.isEmpty()){freeSlot=true;continue;}if("SM_Containment_Capsule".equals(s.getItemId())&&(slot<0||s.getQuantity()==1))slot=i;}
        if(slot<0)return;var empty=inventory.getItemStack(slot);
        if(empty.getQuantity()>1&&!freeSlot){say(p,"Make room for the filled capsule.");return;}
        var captured=anomalies.capture(a.anomaly);if(captured.isEmpty()){say(p,"Another researcher has already captured this anomaly.");return;}
        var token=captured.get();var full=new ItemStack(token.itemId(),1).withMetadata("SMAnomaly",Codec.STRING,token.token());
        if(empty.getQuantity()==1){if(!inventory.setItemStackForSlot(slot,full,false).succeeded()){anomalies.cancelCapture(token.token());return;}}
        else {
            if(!inventory.removeItemStackFromSlot(slot,empty,1,true,false).succeeded()){anomalies.cancelCapture(token.token());return;}
            if(!inventory.addItemStack(full,true,false,false).succeeded()){
                inventory.setItemStackForSlot(slot,empty,false);anomalies.cancelCapture(token.token());return;
            }
        }
        anomalies.save();GadgetEffects.use(store.getExternalData().getWorld(),"SM_Vacuum_Capture",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));say(p,"Contained "+token.type().displayName+". Its identity is preserved in the capsule.");
    }
    private void release(PlayerRef p,Store<EntityStore> store,Aim aim,ItemContainer container,short slot,ItemStack item){
        var player=store.getComponent(p.getReference(),Player.getComponentType());boolean creative=player!=null&&player.getGameMode()==GameMode.Creative;
        if(!projectiles.validCapsule(item,creative)){say(p,"This capsule token has already been used or is invalid.");return;}
        var result=projectiles.launchCapsule(store.getExternalData().getWorld(),p,aim.eye,aim.direction,container,slot,item,creative);
        switch(result){
            case LAUNCHED->say(p,"Capsule released. Its contained field will deploy on impact.");
            case ALREADY_QUEUED->say(p,projectiles.status(item.getFromMetadataOrNull("SMAnomaly",Codec.STRING))+". The existing throw remains protected; no second capsule was consumed.");
            case ITEM_MOVED->research.hud().notice(p,store,"Containment Capsule","The held capsule moved before launch","Select its current inventory slot and throw again.",true);
            case INVALID_TOKEN->research.hud().notice(p,store,"Containment Capsule","This identity has already been released","Capture a field with the Echo Vacuum to fill a new capsule.",true);
            case STORAGE_FAILED->research.hud().notice(p,store,"Containment Capsule","The server could not save this throw","Your capsule remains in inventory. Check the server storage log.",true);
        }
    }
    private void resonate(PlayerRef p,World world,Aim aim,String action){
        int frequency=frequencies.getOrDefault(p.getUuid(),0);if(action.equals("secondary")){frequency=(frequency+1)%7;frequencies.put(p.getUuid(),frequency);}GadgetEffects.use(world,"SM_Scanner_Lock",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));
        AnomalyType type=frequency==0?null:AnomalyType.values()[frequency-1];var a=anomalies.nearest(world,aim.eye,100000,type);
        if(a.isEmpty()){say(p,"Frequency "+(type==null?"ALL":type.displayName)+": no surveyed field in range.");return;}
        var v=a.get();var delta=v.position().sub(aim.eye);String dir=Math.abs(delta.x)>Math.abs(delta.z)?delta.x>0?"east":"west":delta.z>0?"south":"north";
        say(p,v.type.displayName+" | "+Math.round(delta.length())+" blocks "+dir+" | elevation "+Math.round(v.y));
    }
    private void portal(PlayerRef p,Store<EntityStore> store,Aim aim,ItemContainer inv,short slot,ItemStack gun,String action){
        String a=gun.getFromMetadataOrNull("SMPortalA",Codec.STRING),b=gun.getFromMetadataOrNull("SMPortalB",Codec.STRING);
        if(action.equals("use")){if(!inv.setItemStackForSlot(slot,gun.withMetadata("SMPortalA",Codec.STRING,null).withMetadata("SMPortalB",Codec.STRING,null),false).succeeded())return;removePortal(a);removePortal(b);GadgetEffects.use(store.getExternalData().getWorld(),"SM_Warp_Impact_Purple",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));say(p,"Personal warp pair cleared.");return;}
        if(aim.block==null||aim.air==null){say(p,"Aim at a surface within 48 blocks.");return;}
        boolean purple=action.equals("secondary");
        var record=anomalies.spawn(AnomalyType.WARP_GATE,store.getExternalData().getWorld(),new Vector3d(aim.air).add(.5,.5,.5),false);
        anomalies.setPortalChannel(record.id,purple?2:1);
        var updated=gun.withMetadata(purple?"SMPortalB":"SMPortalA",Codec.STRING,record.id.toString());
        var player=store.getComponent(p.getReference(),Player.getComponentType());if(player==null||player.getGameMode()!=GameMode.Creative)updated=updated.withDurability(Math.max(0,updated.getDurability()-1));
        if(!inv.setItemStackForSlot(slot,updated,false).succeeded()){anomalies.remove(record.id);return;}
        removePortal(purple?b:a);String other=purple?a:b;if(other!=null)try{anomalies.pair(record.id,UUID.fromString(other));}catch(IllegalArgumentException ignored){}
        anomalies.save();
        GadgetEffects.use(store.getExternalData().getWorld(),purple?"SM_Warp_Muzzle_Purple":"SM_Warp_Muzzle_Cyan",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));
        GadgetEffects.use(store.getExternalData().getWorld(),purple?"SM_Warp_Impact_Purple":"SM_Warp_Impact_Cyan",record.position());
        say(p,(purple?"Purple":"Cyan")+" warp aperture projected.");
    }
    private void removePortal(String id){if(id!=null)try{anomalies.remove(UUID.fromString(id));}catch(IllegalArgumentException ignored){}}
    /** A complete orthogonal mining plane, including when looking straight up or down. */
    static float hammerDamageScale(String action) { return switch(action) { case "hammer1" -> 2f; case "hammer2" -> 3f; case "hammer3" -> 4f; default -> 1f; }; }
    static List<Vector3i> hammerCells(Vector3i origin,int axis,int sign,int radius,int depth){
        if(axis<0||axis>2||Math.abs(sign)!=1||radius<0||radius>1||depth<1||depth>9)throw new IllegalArgumentException("Invalid hammer footprint");
        var cells=new ArrayList<Vector3i>();
        for(int d=0;d<depth;d++)for(int u=-radius;u<=radius;u++)for(int v=-radius;v<=radius;v++){
            var offset=switch(axis){case 0->new Vector3i(d*sign,u,v);case 1->new Vector3i(u,d*sign,v);default->new Vector3i(u,v,d*sign);};
            cells.add(new Vector3i(origin).add(offset));
        }
        return cells;
    }
    private void hammer(PlayerRef p,Store<EntityStore> store,Aim aim,ItemContainer inv,short slot,ItemStack hammer,String action){
        if(!action.equals("hammer0"))chargedHammer(p.getReference(),store,aim,action);
        if(aim.block==null||aim.distance>6)return;
        int depth=switch(action){case "hammer1"->3;case "hammer2"->6;case "hammer3"->9;default->1;};
        var direction=aim.direction;int axis=Math.abs(direction.y)>Math.max(Math.abs(direction.x),Math.abs(direction.z))?1:Math.abs(direction.x)>Math.abs(direction.z)?0:2;
        int sign=(axis==0?direction.x:axis==1?direction.y:direction.z)>0?1:-1;
        var movement=store.getComponent(p.getReference(),MovementStatesComponent.getComponentType());
        int radius=action.equals("hammer0")&&movement!=null&&movement.getMovementStates().crouching?0:1;
        var world=store.getExternalData().getWorld();var targets=new ArrayList<Vector3i>();
        for(var pos:hammerCells(aim.block,axis,sign,radius,depth)){
            if(pos.y<0||pos.y>=ChunkUtil.HEIGHT||world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(pos.x,pos.z))==null)continue;
            var type=world.getBlockType(pos.x,pos.y,pos.z);if(type==null||type.getMaterial()==BlockMaterial.Empty)continue;
            if(type.getGathering()==null||type.getId().contains("Bedrock")||MachineService.IDS.contains(type.getId())||(type.getDefaultStateKey()!=null&&MachineService.IDS.contains(type.getDefaultStateKey())))continue;
            if(type.getGathering().getBreaking()!=null||type.getGathering().getSoft()!=null)targets.add(pos);
        }
        var thorium=Item.getAssetMap().getAsset("Tool_Pickaxe_Thorium");
        if(thorium==null||thorium.getTool()==null||targets.isEmpty())return;
        // Use the actual native tier/power and normal health, gathering, protection events, and
        // durability path. One area swing consumes one tool hit, never forcibly breaks stone.
        int broken=BlockHarvestUtils.performBlockDamage(p.getReference(),p.getReference(),targets,new Vector3i(aim.block),hammer,thorium.getTool(),null,false,hammerDamageScale(action),0,false,false,store,world.getChunkStore().getStore());
        GadgetEffects.use(world,"SM_Hammer_Pulse",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));
        GadgetEffects.use(world,"SM_Hammer_Impact",new Vector3d(aim.block).add(.5,.5,.5));
        say(p,"Graviton pulse struck "+targets.size()+" blocks; "+broken+" fractured.");
    }
    static float chargedHammerDamage(String action) { return switch(action) { case "hammer1" -> 20f; case "hammer2" -> 30f; case "hammer3" -> 40f; default -> 0f; }; }
    static boolean damageHammerTarget(Ref<EntityStore> attacker,Ref<EntityStore> target,Store<EntityStore> store,String action) {
        float amount=chargedHammerDamage(action);int cause=DamageCause.getAssetMap().getIndex("Physical");
        if(amount<=0||cause<0||target==null||!target.isValid()||target.equals(attacker))return false;
        if(store.getComponent(target,Player.getComponentType())==null&&store.getComponent(target,NPCEntity.getComponentType())==null)return false;
        DamageSystems.executeDamage(target,store,new Damage(new Damage.EntitySource(attacker),cause,amount));return true;
    }
    private void chargedHammer(Ref<EntityStore> attacker,Store<EntityStore> store,Aim aim,String action) {
        if(chargedHammerDamage(action)<=0)return;
        World world=store.getExternalData().getWorld();
        Vector3d end=new Vector3d(aim.direction).mul(Math.min(3,aim.distance)).add(aim.eye);
        // A short forward impact; no entity hit can reach through a wall or behind the wielder.
        var candidates=EquipmentQueries.inBox(store,new Vector3d(aim.eye).sub(4,4,4),new Vector3d(aim.eye).add(4,4,4),false);
        for(var target:candidates) {
            if(target.equals(attacker))continue;
            var transform=store.getComponent(target,TransformComponent.getComponentType());if(transform==null)continue;
            Vector3d center=new Vector3d(transform.getPosition()).add(0,Math.max(.3,ModelComponent.getEyeHeight(target,store)*.6),0);
            Vector3d delta=new Vector3d(center).sub(aim.eye);double distance=delta.length();
            if(distance>4||distance<.01||delta.div(distance).dot(aim.direction)<.45)continue;
            boolean blocked=false;
            for(double d=.2;d<distance-.2;d+=.2) {
                Vector3d point=new Vector3d(delta).mul(d).add(aim.eye);int x=(int)Math.floor(point.x),y=(int)Math.floor(point.y),z=(int)Math.floor(point.z);
                if(y<0||y>=ChunkUtil.HEIGHT||world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x,z))==null){blocked=true;break;}
                var block=world.getBlockType(x,y,z);if(block!=null&&block.getMaterial()==BlockMaterial.Solid&&!block.getId().equals("Empty")){blocked=true;break;}
            }
            if(!blocked&&damageHammerTarget(attacker,target,store,action))GadgetEffects.use(world,"SM_Hammer_Impact",center);
        }
        GadgetEffects.use(world,"SM_Hammer_Pulse",EquipmentQueries.handheldOrigin(aim.eye,aim.direction));
        GadgetEffects.use(world,"SM_Hammer_Impact",end);
    }
    private static boolean wear(ItemContainer inv,short slot,ItemStack item,int amount,boolean creative){return creative||item.getMaxDurability()<=0||inv.setItemStackForSlot(slot,item.withDurability(Math.max(0,item.getDurability()-amount)),false).succeeded();}
    private static ItemContainer inventory(PlayerRef p,Store<EntityStore> store){return InventoryComponent.getCombined(store,p.getReference(),InventoryComponent.BACKPACK_STORAGE_HOTBAR);}
    private void say(PlayerRef p,String text){
        var ref=p.getReference();if(ref==null||!ref.isValid())return;var store=ref.getStore();var held=InventoryComponent.getItemInHand(store,ref);
        research.hud().notice(p,store,held==null?"Strange Matter":InventoryOps.label(held.getItemId()),text,"",false);
    }
    private void heldReadout(PlayerRef p,Store<EntityStore> store,ItemStack held,Aim aim){
        if(ItemStack.isEmpty(held)||aim==null)return;
        String id=held.getItemId(),title=InventoryOps.label(id),status="",detail="";double progress=-1;
        var a=acquisitions.get(p.getUuid());
        switch(id){
            case "SM_Field_Scanner","SM_Echo_Vacuum"->{
                boolean vacuum=id.equals("SM_Echo_Vacuum");
                var target=targeted(store.getExternalData().getWorld(),aim,vacuum?8:12);
                status=target.map(t->t.type.displayName+(vacuum?"":" / "+(t.scannable()?"Natural field":"Transported field"))).orElse("No research subject in range");
                detail=vacuum?"Hold primary for 2s to extract. Carry an empty containment capsule.":"Hold primary for 2s to record a natural field. Each identity grants observations once.";
                if(a!=null){progress=Math.min(1,(System.nanoTime()-a.began)/2_000_000_000d);status=(vacuum?"Extracting ":"Analyzing ")+target.map(t->t.type.displayName).orElse("field");}
            }
            case "SM_Anomaly_Resonator"->{
                int f=frequencies.getOrDefault(p.getUuid(),0);AnomalyType type=f==0?null:AnomalyType.values()[f-1];
                var nearest=anomalies.nearest(store.getExternalData().getWorld(),aim.eye,100000,type);
                status="Frequency: "+(type==null?"ALL":type.displayName);
                if(nearest.isPresent()){var field=nearest.get();var delta=field.position().sub(aim.eye);String compass=Math.abs(delta.x)>Math.abs(delta.z)?delta.x>0?"east":"west":delta.z>0?"south":"north";detail=field.type.displayName+" / "+Math.round(delta.length())+" blocks "+compass+" / elevation "+Math.round(field.y)+". Secondary changes frequency.";}
                else detail="No surveyed field on this frequency. Secondary changes frequency.";
            }
            case "SM_Warp_Gun"->{status="Cyan: "+portalStatus(held,"SMPortalA")+" / Purple: "+portalStatus(held,"SMPortalB");detail="Primary: cyan aperture. Secondary: purple aperture. Use: clear the linked pair.";}
            case "SM_Chrono_Blister"->{status="Temporal projector ready";detail="Hold primary to charge, then release a temporal blister. Nearby matter slows inside its impact field.";}
            case "SM_Graviton_Hammer"->{status="Thorium mining strength / 3 x 3 face";detail="Primary: area swing. Crouch-primary: one block. Hold secondary 1 / 2 / 3s: depth 3 / 6 / 9, mining power 2 / 3 / 4x and a stronger forward slam.";}
            case "SM_Hoverboard","SM_Echoform_Imprinter"->{mobility.present(p,store,id);return;}
            case "SM_Containment_Capsule"->{status="Empty containment chamber";detail="Carry this capsule and hold the Echo Vacuum on an anomaly for two seconds.";}
            default->{if(!id.startsWith("SM_Containment_Capsule_"))return;status=projectiles.status(held.getFromMetadataOrNull("SMAnomaly",Codec.STRING));detail="Primary or secondary: throw. The original field returns at the impact point.";}
        }
        research.hud().update(p,store,title,status,detail,progress);
    }
    private String portalStatus(ItemStack gun,String key){
        String id=gun.getFromMetadataOrNull(key,Codec.STRING);if(id==null)return "unset";
        try{return anomalies.get(UUID.fromString(id)).filter(a->!a.contained).isPresent()?"projected":"expired";}catch(IllegalArgumentException ignored){return "unset";}
    }
    private static final System.Logger LOG=System.getLogger(EquipmentService.class.getName());
    private final Map<String,Long> failedTicks=new ConcurrentHashMap<>();
    private void tickPart(World world,String part,Runnable action){
        try{action.run();}catch(RuntimeException failure){String key=world.getName()+":"+part;long now=System.nanoTime();if(now>=failedTicks.getOrDefault(key,0L)){failedTicks.put(key,now+5_000_000_000L);LOG.log(System.Logger.Level.ERROR,"Strange Matter "+part+" tick failed; independent gadgets continue",failure);}}
    }
    public void tick(World world,double dt){
        // A bad field or disconnected HUD must not starve durable flight acknowledgements.
        tickPart(world,"projectiles",()->projectiles.tick(world,dt));
        tickPart(world,"fields",()->fields.tick(world,dt));
        tickPart(world,"mobility",()->mobility.tick(world,dt));
        var store=world.getEntityStore().getStore();double old=hudTime.getOrDefault(world.getName(),0d),now=old+dt;hudTime.put(world.getName(),now);boolean present=(int)(old*5)!=(int)(now*5);
        for(var p:world.getPlayerRefs())tickPart(world,"instrument feedback",()->{
            var ref=p.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=store){acquisitions.remove(p.getUuid());return;}
            var held=InventoryComponent.getItemInHand(store,ref);var aim=aim(ref,store,12);var a=acquisitions.get(p.getUuid());
            if(a!=null){
                if(held==null||!a.item.equals(held.getItemId())||aim==null||!stillAimed(a,world,aim)||System.nanoTime()-a.began>5_000_000_000L)acquisitions.remove(p.getUuid());
                else if(present&&a.anomaly!=null)anomalies.get(a.anomaly).ifPresent(r->GadgetEffects.beam(world,a.item.equals("SM_Echo_Vacuum")?"SM_Vacuum_Intake":"SM_Scanner_Lock",EquipmentQueries.handheldOrigin(aim.eye,aim.direction),r.position()));
            }
            if(present){heldReadout(p,store,held,aim);research.refreshNotes(store,ref);}
        });
        tickPart(world,"gadget HUD",()->research.hud().tick(world,dt));
    }
    public void cleanup(World world){projectiles.cleanup(world);fields.cleanup(world);mobility.cleanup(world);research.hud().cleanup(world);hudTime.remove(world.getName());acquisitions.entrySet().removeIf(e->e.getValue().world.equals(world.getName()));}
}
