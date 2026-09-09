package com.hexvane.strangematter.anomaly;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.ItemArmorSlot;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.math.vector.Rotation3f;
import org.joml.Vector3d;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Private, finite false creatures with sensory echoes; never changes the player's model or controls. */
final class ThoughtwellConfusion {
    static final String ECHO="SM_Thoughtwell_Echo", STEP="SM_Thoughtwell_False_Step", CHIME="SM_Thoughtwell_False_Chime";
    static final String VANISH="SM_Thoughtwell_Phantom_Vanish";
    private final Map<UUID,Exposure> exposures=new ConcurrentHashMap<>();
    private static final class Exposure {
        final World world;final Ref<EntityStore> player;
        double remaining,intensity,untilCue,phantomAge;int phase;
        Ref<EntityStore> phantom;Vector3d from,to;
        Exposure(World world,Ref<EntityStore> player){this.world=world;this.player=player;}
    }
    void expose(World world,Ref<EntityStore> player,double duration,double intensity) {
        var owner=world.getEntityStore().getStore().getComponent(player,PlayerRef.getComponentType());
        if(owner==null)return;
        exposures.compute(owner.getUuid(),(id,previous)->{
            if(previous==null||previous.world!=world||previous.player!=player) {
                // A world transfer's old entity belongs to another world thread.
                // Queue its removal there; ownership filtering already hides it.
                if(previous!=null) {
                    var old=previous;
                    old.world.execute(()->removePhantom(old,false));
                }
                previous=new Exposure(world,player);
            }
            previous.remaining=Math.max(previous.remaining,Math.min(10,duration));
            previous.intensity=Math.max(0,Math.min(1,intensity));
            // First cue arrives shortly after the haze, giving it a distinct perceptual beat.
            if(previous.untilCue<=0)previous.untilCue=.35;
            return previous;
        });
    }
    void tick(World world,double dt) {
        var store=world.getEntityStore().getStore();
        int effect=EntityEffect.getAssetMap().getIndex("SM_Cognitive_Dissonance");
        for(var entry:exposures.entrySet()) {
            var exposure=entry.getValue();if(exposure.world!=world)continue;
            var ref=exposure.player;exposure.remaining-=dt;
            if(exposure.remaining<=0||!ref.isValid()||ref.getStore()!=store){removePhantom(exposure,false);exposures.remove(entry.getKey(),exposure);continue;}
            var controller=store.getComponent(ref,EffectControllerComponent.getComponentType());
            var activeEffect=controller==null?null:controller.getActiveEffects().get(effect);
            var armor=store.getComponent(ref,InventoryComponent.Armor.getComponentType());
            short head=(short)ItemArmorSlot.Head.ordinal();
            var hat=armor==null||head>=armor.getInventory().getCapacity()?ItemStack.EMPTY:armor.getInventory().getItemStack(head);
            // DURATION removal sends the cleanse immediately but retains a zero-duration entry
            // until the native effect tick. Map membership alone can leak one more private cue.
            if(activeEffect==null||(!activeEffect.isInfinite()&&activeEffect.getRemainingDuration()<=0)
                    ||store.getComponent(ref,DeathComponent.getComponentType())!=null
                    ||(!ItemStack.isEmpty(hat)&&"SM_Tinfoil_Hat".equals(hat.getItemId()))){removePhantom(exposure,false);exposures.remove(entry.getKey(),exposure);continue;}
            animatePhantom(exposure,dt);
            exposure.untilCue-=dt;if(exposure.untilCue>0)continue;
            emit(exposure);
            exposure.untilCue=3.1-.5*exposure.intensity;
        }
    }
    private void emit(Exposure exposure) {
        var store=exposure.world.getEntityStore().getStore();var ref=exposure.player;
        var transform=store.getComponent(ref,TransformComponent.getComponentType());
        if(transform==null)return;
        var head=store.getComponent(ref,HeadRotation.getComponentType());
        var forward=head==null?new Vector3d(0,0,1):new Vector3d(head.getDirection());
        forward.y=0;if(forward.lengthSquared()<1e-8)forward.set(0,0,1);else forward.normalize();
        var right=new Vector3d(-forward.z,0,forward.x);
        int phase=exposure.phase++;
        double side=(phase&1)==0?1:-1;
        removePhantom(exposure,false);
        // A coherent creature moves from the peripheral view toward the player's
        // current position. Its destination is fixed at appearance, so it does
        // not chase, teleport with the camera or reach the player's body.
        int kind=phase%ThoughtwellHallucinations.MODELS.length;
        double lift=kind==1?.45:.05;
        exposure.from=new Vector3d(transform.getPosition()).add(new Vector3d(forward).mul(4.2))
                .add(new Vector3d(right).mul(side*2.25)).add(0,lift,0);
        exposure.to=new Vector3d(transform.getPosition()).add(new Vector3d(forward).mul(1.55))
                .add(new Vector3d(right).mul(side*.65)).add(0,lift,0);
        var direction=new Vector3d(exposure.to).sub(exposure.from);
        exposure.phantom=ThoughtwellHallucinations.spawn(exposure.world,ref,kind,exposure.from,Rotation3f.lookAt(direction));
        exposure.phantomAge=0;
        // A glyph appears to one side; the false step answers from the opposite rear quarter.
        // Explicit recipient lists keep both impressions private even in multiplayer.
        var glyph=new Vector3d(transform.getPosition()).add(new Vector3d(forward).mul(1.6))
                .add(new Vector3d(right).mul(side*(1.7-.35*exposure.intensity))).add(0,1.35,0);
        var sound=new Vector3d(transform.getPosition()).add(new Vector3d(forward).mul(-1.8))
                .add(new Vector3d(right).mul(-side*1.4)).add(0,.25,0);
        ParticleUtil.spawnParticleEffect(ECHO,glyph.x,glyph.y,glyph.z,List.of(ref),store);
        String soundId=exposure.phase%3==0?CHIME:STEP;
        int index=SoundEvent.getAssetMap().getIndex(soundId);
        if(index!=SoundEvent.EMPTY_ID)SoundUtil.playSoundEvent3dToPlayer(ref,index,SoundCategory.SFX,
                sound.x,sound.y,sound.z,(float)(.8+.2*exposure.intensity),exposure.phase%3==0?.88f:1f,store);
    }
    private void animatePhantom(Exposure exposure,double dt) {
        if(exposure.phantom==null)return;
        if(!exposure.phantom.isValid()){exposure.phantom=null;return;}
        exposure.phantomAge+=dt;
        if(exposure.phantomAge>=2.15){removePhantom(exposure,true);return;}
        var transform=exposure.world.getEntityStore().getStore().getComponent(exposure.phantom,TransformComponent.getComponentType());
        if(transform==null){removePhantom(exposure,false);return;}
        double t=Math.min(1,exposure.phantomAge/2.15);
        var position=new Vector3d(exposure.from).lerp(exposure.to,t*t*(3-2*t));
        if((exposure.phase-1)%3==1)position.y+=Math.sin(t*Math.PI*3)*.12;
        transform.setPosition(position);
    }
    private static void removePhantom(Exposure exposure,boolean dissolve) {
        var phantom=exposure.phantom;exposure.phantom=null;
        if(phantom==null||!phantom.isValid())return;
        var store=exposure.world.getEntityStore().getStore();
        var transform=store.getComponent(phantom,TransformComponent.getComponentType());
        if(dissolve&&transform!=null&&exposure.player.isValid()&&exposure.player.getStore()==store) {
            var p=transform.getPosition();
            ParticleUtil.spawnParticleEffect(VANISH,p.x,p.y+.75,p.z,List.of(exposure.player),store);
        }
        ThoughtwellHallucinations.remove(phantom);
    }
    void clear(World world){for(var entry:exposures.entrySet())if(entry.getValue().world==world){removePhantom(entry.getValue(),false);exposures.remove(entry.getKey(),entry.getValue());}}
}
