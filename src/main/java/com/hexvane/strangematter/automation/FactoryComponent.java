package com.hexvane.strangematter.automation;

import com.google.gson.Gson;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.*;

/** Inventory and job escrow are serialized together in the native block entity holder. */
public final class FactoryComponent implements Component<ChunkStore> {
    private static final Gson GSON=new Gson();
    private static ComponentType<ChunkStore,FactoryComponent> type;
    public static final BuilderCodec<FactoryComponent> CODEC=BuilderCodec.builder(FactoryComponent.class,FactoryComponent::new)
        .append(new KeyedCodec<>("Ingredients",ItemContainer.CODEC),(s,v)->s.input=v,s->s.input).add()
        .append(new KeyedCodec<>("FinishedItems",ItemContainer.CODEC),(s,v)->s.output=v,s->s.output).add()
        .append(new KeyedCodec<>("ReservedIngredients",ItemContainer.CODEC),(s,v)->s.escrow=v,s->s.escrow).add()
        .append(new KeyedCodec<>("ReservedOutputs",ItemContainer.CODEC),(s,v)->s.pending=v,s->s.pending).add()
        .append(new KeyedCodec<>("Recovery",ItemContainer.CODEC),(s,v)->s.recovery=v,s->s.recovery).add()
        .append(new KeyedCodec<>("FactoryState",Codec.STRING),(s,v)->{var d=GSON.fromJson(v,Data.class);if(d!=null)s.data=d;},s->GSON.toJson(s.data)).add().build();
    public static void register(JavaPlugin plugin){if(type==null)type=plugin.getChunkStoreRegistry().registerComponent(FactoryComponent.class,"SM_Factory",CODEC);}
    public static ComponentType<ChunkStore,FactoryComponent> getComponentType(){return type;}
    public ItemContainer input=new SimpleItemContainer((short)20),output=new SimpleItemContainer((short)10);
    public ItemContainer escrow=new SimpleItemContainer((short)128),pending=new SimpleItemContainer((short)128),recovery=new SimpleItemContainer((short)128);
    public Data data=new Data();
    transient Object listenerRef;
    transient String status="Needs materials";
    transient int effectTicks;
    public static final class Data {
        public String owner="",pattern="",jobRecipe="",jobOwner="",jobFingerprint="",jobKind="",identity=UUID.randomUUID().toString();
        public int tier=1,duration,progress,target=100,upgradeTier,energy;
        public int fuelTicks,queuedFuelTicks,condenserProgress;
        public List<com.hexvane.strangematter.machine.MachineState.FuelCharge> fuelQueue=new ArrayList<>(),recoveredFuel=new ArrayList<>();
        public boolean repeat,configured,migrated;
        public Set<String> allowed=new HashSet<>();
        public List<String> retiredCapsules=new ArrayList<>();
        public Map<String,String> selected=new LinkedHashMap<>();
    }
    public boolean busy(){return !data.jobRecipe.isEmpty();}
    public int tier(){return Math.max(1,Math.min(3,data.tier));}
    @Override public FactoryComponent clone(){var c=new FactoryComponent();c.input=input.clone();c.output=output.clone();c.escrow=escrow.clone();c.pending=pending.clone();c.recovery=recovery.clone();c.data=GSON.fromJson(GSON.toJson(data),Data.class);return c;}
}
