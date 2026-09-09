package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.PlayerInventoryPersistence;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Inventory acknowledgements are polled on world ticks; no disk wait blocks a world thread. */
final class HoverboardRecovery {
    private static final System.Logger LOG=System.getLogger(HoverboardRecovery.class.getName());
    private final HoverboardLedger ledger;
    private final BiFunction<World,PlayerRef,CompletableFuture<Void>> save;
    private final Map<UUID,Pending> preparing=new ConcurrentHashMap<>();
    private final Map<UUID,Pending> returning=new ConcurrentHashMap<>();
    private static final class Pending {
        final World world;final PlayerRef owner;final UUID receipt;
        CompletableFuture<Void> future;long retry;
        Pending(World world,PlayerRef owner,UUID receipt){this.world=world;this.owner=owner;this.receipt=receipt;}
    }
    HoverboardRecovery(Path directory){this(directory,PlayerInventoryPersistence::save);}
    HoverboardRecovery(Path directory,BiFunction<World,PlayerRef,CompletableFuture<Void>> save){ledger=new HoverboardLedger(directory);this.save=save;}
    HoverboardLedger ledger(){return ledger;}
    boolean begin(World world,PlayerRef owner,ItemContainer inventory,short slot,ItemStack original){
        if(preparing.containsKey(owner.getUuid()))return false;
        var receipt=ledger.prepare(owner.getUuid(),original);if(receipt==null)return false;
        ItemStack stamped=HoverboardLedger.decode(receipt.item);
        if(!replaceExact(inventory,slot,original,stamped)){ledger.cancelPreparing(receipt.id);return false;}
        Pending pending=new Pending(world,owner,receipt.id);preparing.put(owner.getUuid(),pending);
        pending.future=save.apply(world,owner);return true;
    }
    void fold(UUID receipt){ledger.returnBoard(receipt);}
    void tick(World world,BiPredicate<PlayerRef,UUID> deploy,BiConsumer<PlayerRef,String> notice){
        for(Pending p:preparing.values()){
            if(p.world!=world)continue;
            var ref=p.owner.getReference();
            if(ref==null||!ref.isValid()||ref.getStore()!=world.getEntityStore().getStore()){
                abort(p);continue;
            }
            var inventory=InventoryComponent.getCombined(ref.getStore(),ref,InventoryComponent.BACKPACK_STORAGE_HOTBAR);
            if(inventory==null)continue;
            var receipt=ledger.get(p.receipt);
            if(receipt.phase!=HoverboardLedger.Phase.PREPARING&&receipt.phase!=HoverboardLedger.Phase.RESERVED){preparing.remove(p.owner.getUuid(),p);continue;}
            if(!acknowledged(p))continue;
            if(receipt.phase==HoverboardLedger.Phase.PREPARING){
                short slot=find(inventory,receipt.token());
                if(slot<0){ledger.cancelPreparing(receipt.id);preparing.remove(p.owner.getUuid(),p);notice.accept(p.owner,"Deployment cancelled: the board moved out of your inventory.");continue;}
                ItemStack expected=HoverboardLedger.decode(receipt.item);
                if(!ledger.reserve(receipt.id))continue;
                if(!replaceExact(inventory,slot,expected,ItemStack.EMPTY)){
                    ledger.cancelUnconsumed(receipt.id);preparing.remove(p.owner.getUuid(),p);continue;
                }
                p.future=save.apply(world,p.owner);
            }else{
                if(!ledger.mounted(receipt.id))continue;
                preparing.remove(p.owner.getUuid(),p);
                boolean mounted=false;
                try{mounted=deploy.test(p.owner,receipt.id);}
                finally{if(!mounted)ledger.returnBoard(receipt.id);}
            }
        }
        for(PlayerRef owner:world.getPlayerRefs()){
            var ref=owner.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=world.getEntityStore().getStore())continue;
            for(var receipt:ledger.pendingReturns(owner.getUuid())){
                Pending pending=returning.get(receipt.id);
                if(pending!=null){
                    // A previous world's asynchronous save can finish after a transfer/unload.
                    // Its snapshot is still ordered by native storage; never add a second item.
                    if(pending.future!=null&&!pending.future.isDone())continue;
                    if(pending.future!=null&&!pending.future.isCompletedExceptionally()){
                        ledger.returned(receipt.id);returning.remove(receipt.id,pending);notice.accept(owner,"Hoverboard folded and returned to your inventory.");continue;
                    }
                    if(System.nanoTime()<pending.retry)continue;
                    if(pending.future!=null){reportFailure(pending);continue;}
                }
                // Search all carried sections for existing receipts, but insert a folded board
                // into an open hotbar slot before falling back to storage or the backpack.
                var inventory=InventoryComponent.getCombined(ref.getStore(),ref,InventoryComponent.HOTBAR_STORAGE_BACKPACK);
                if(inventory==null||!reconcile(inventory,receipt)){
                    if(pending==null){pending=new Pending(world,owner,receipt.id);returning.put(receipt.id,pending);notice.accept(owner,"Your folded hoverboard is safe. Free an inventory slot to receive it.");}
                    pending.retry=System.nanoTime()+1_000_000_000L;continue;
                }
                pending=new Pending(world,owner,receipt.id);returning.put(receipt.id,pending);pending.future=save.apply(world,owner);
            }
        }
    }
    private boolean acknowledged(Pending p){
        if(p.future==null){if(System.nanoTime()>=p.retry)p.future=save.apply(p.world,p.owner);return false;}
        if(!p.future.isDone())return false;
        if(p.future.isCompletedExceptionally()){reportFailure(p);return false;}
        p.future=null;p.retry=0;return true;
    }
    private static void reportFailure(Pending p){
        try{p.future.join();}catch(RuntimeException ex){LOG.log(System.Logger.Level.ERROR,"Hoverboard inventory save failed; receipt retained for retry",ex);}
        p.future=null;p.retry=System.nanoTime()+5_000_000_000L;
    }
    void cleanup(World world){for(Pending p:List.copyOf(preparing.values()))if(p.world==world)abort(p);}
    private void abort(Pending p){
        if(!preparing.remove(p.owner.getUuid(),p))return;
        var receipt=ledger.get(p.receipt);
        if(receipt.phase==HoverboardLedger.Phase.PREPARING)ledger.cancelPreparing(receipt.id);else ledger.returnBoard(receipt.id);
    }
    static boolean replaceExact(ItemContainer inventory,short slot,ItemStack expected,ItemStack replacement){
        boolean[] replaced={false};inventory.replaceAll((index,current)->{
            if(index!=slot||!Objects.equals(expected,current))return current;
            replaced[0]=true;return replacement;
        });return replaced[0];
    }
    static short find(ItemContainer inventory,String token){for(short slot=0;slot<inventory.getCapacity();slot++)if(token.equals(HoverboardLedger.token(inventory.getItemStack(slot))))return slot;return -1;}
    static boolean reconcile(ItemContainer inventory,HoverboardLedger.Receipt receipt){
        boolean[] present={false};
        inventory.replaceAll((slot,current)->{
            String token=HoverboardLedger.token(current);
            if(receipt.token().equals(token)){
                if(present[0])return ItemStack.EMPTY;
                present[0]=true;return HoverboardLedger.decode(receipt.item);
            }
            if(receipt.retiredToken!=null&&receipt.retiredToken.equals(token))return ItemStack.EMPTY;
            return current;
        });
        if(present[0])return true;
        var item=HoverboardLedger.decode(receipt.item);
        return inventory.canAddItemStack(item,true,false)&&inventory.addItemStack(item,true,false,false).succeeded();
    }
}
