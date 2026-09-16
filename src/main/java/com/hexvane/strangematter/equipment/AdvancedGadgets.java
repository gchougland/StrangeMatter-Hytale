package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.ui.gadget.GadgetHudService;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.file.Path;
import java.util.*;

/** EquipmentService integration; all mutations remain on the owning world thread. */
public final class AdvancedGadgets {
    final GraviticManipulator gravity;private final GadgetHudService hud;
    private final Map<UUID,Long> nextPulse=new HashMap<>();private final Map<String,Integer> frames=new HashMap<>();
    public AdvancedGadgets(Path directory,GadgetHudService hud){this.hud=hud;gravity=new GraviticManipulator(directory,hud);}
    public synchronized void setChestMoveBlocker(java.util.function.BiPredicate<World,org.joml.Vector3i> blocker){gravity.blocks.moveBlocked(blocker);}
    public synchronized void interact(PlayerRef p,Store<EntityStore> store,ItemContainer inventory,short slot,ItemStack item,String action){
        var ref=p.getReference();if(ref==null||!ref.isValid()||ref.getStore()!=store||store.getComponent(ref,DeathComponent.getComponentType())!=null)return;
        if("SM_Gravitic_Manipulator".equals(item.getItemId())){gravity.interact(p,store,inventory,slot,item,action);return;}
        if(!"SM_Arc_Projector".equals(item.getItemId())||!"arc_fire".equals(action))return;
        long now=System.nanoTime();if(now<nextPulse.getOrDefault(p.getUuid(),0L))return;
        var aim=AdvancedGadgetTargeting.aim(ref,store,18);if(aim==null)return;var player=store.getComponent(ref,Player.getComponentType());if(player==null)return;
        if(!GadgetEnergy.spend(inventory,slot,item,GadgetEnergy.cost("arc_fire"),player.getGameMode()==GameMode.Creative)){hud.notice(p,store,"Arc Projector","Insufficient energy","Recharge this gadget at a charging station or burner dock.",true);nextPulse.put(p.getUuid(),now+500_000_000L);return;}
        nextPulse.put(p.getUuid(),now+195_000_000L);var world=store.getExternalData().getWorld();ArcProjector.fire(world,ref,aim,frames.merge(world.getName(),5,Integer::sum));
    }
    public synchronized void tick(World world,double dt){gravity.tick(world,dt);if(nextPulse.size()>1024){long cutoff=System.nanoTime()-10_000_000_000L;nextPulse.values().removeIf(v->v<cutoff);}}
    public synchronized void cleanup(World world){gravity.cleanup(world);frames.remove(world.getName());}
}
