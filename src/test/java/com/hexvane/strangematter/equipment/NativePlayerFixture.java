package com.hexvane.strangematter.equipment;

import com.hexvane.strangematter.util.PlayerInventoryPersistence;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.io.ChannelConnection;
import com.hypixel.hytale.protocol.io.ConnectionHandler;
import com.hypixel.hytale.protocol.io.PacketStatsRecorder;
import com.hypixel.hytale.protocol.packets.connection.QuicApplicationErrorCode;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.io.ProtocolVersion;
import com.hypixel.hytale.server.core.modules.entity.component.*;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.modules.physics.component.PhysicsValues;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.playerdata.DiskPlayerStorageProvider;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real native ECS player lifecycle and disk storage, with only the network socket replaced. */
public final class NativePlayerFixture implements AutoCloseable {
    private final World world;
    private final PlayerRef owner;
    private final Player player;
    private final RecordingPackets packets;
    private final Ref<EntityStore> ref;

    private NativePlayerFixture(World world,UUID id,String name,Vector3d position,Holder<EntityStore> holder){
        world.getEntityStore().getStore().assertThread();
        this.world=world;packets=new RecordingPackets();var tracker=new ChunkTracker();
        owner=new PlayerRef(holder,id,name,"en-US",packets,tracker);
        packets.setPlayerRef(owner);
        tracker.setDefaultMaxSectionsPerSecond(owner);
        holder.putComponent(PlayerRef.getComponentType(),owner);
        holder.putComponent(ChunkTracker.getComponentType(),tracker);
        holder.putComponent(UUIDComponent.getComponentType(),new UUIDComponent(id));
        holder.ensureComponent(PositionDataComponent.getComponentType());
        holder.ensureComponent(MovementAudioComponent.getComponentType());
        holder.ensureComponent(MovementStatesComponent.getComponentType());
        holder.ensureComponent(MovementManager.getComponentType());
        holder.ensureComponent(PhysicsValues.getComponentType());
        holder.ensureComponent(HeadRotation.getComponentType());
        holder.putComponent(TransformComponent.getComponentType(),new TransformComponent(position,new Rotation3f()));
        player=holder.ensureAndGetComponent(Player.getComponentType());
        player.init(id,owner);player.setFirstSpawn(false);player.setClientViewRadius(1);
        player.setNetworkId(world.getEntityStore().takeNextNetworkId());
        player.getPlayerConfigData().setWorld(world.getName());
        owner.setWorldUuid(world.getWorldConfig().getUuid());
        holder.putComponent(NetworkId.getComponentType(),new NetworkId(player.getNetworkId()));
        holder.putComponent(EntityTrackerSystems.EntityViewer.getComponentType(),new EntityTrackerSystems.EntityViewer(32,packets));
        // Runs native holder migration, PlayerInitSystem, PlayerRefAddedSystem and PlayerSpawnedSystem.
        ref=Objects.requireNonNull(owner.addToStore(world.getEntityStore().getStore()));
        require(ref.isValid()&&owner.getReference()==ref&&player.getReference()==ref,"Native player and legacy entity attach to the actual store");
        require(hotbar().getCapacity()==InventoryComponent.DEFAULT_HOTBAR_CAPACITY,"Native PlayerInitSystem creates the real hotbar");
    }
    public static NativePlayerFixture create(World world,String name,Vector3d position){
        return new NativePlayerFixture(world,UUID.randomUUID(),name,position,EntityStore.REGISTRY.newHolder());
    }
    public static NativePlayerFixture load(World world,UUID id,String name,Vector3d position)throws Exception{
        return new NativePlayerFixture(world,id,name,position,Universe.get().getPlayerStorage().load(id).get(10,TimeUnit.SECONDS));
    }
    public World world(){return world;}
    public PlayerRef owner(){return owner;}
    public Player player(){return player;}
    public Ref<EntityStore> ref(){return ref;}
    public Store<EntityStore> store(){return world.getEntityStore().getStore();}
    public RecordingPackets packets(){return packets;}
    public ItemContainer inventory(){return InventoryComponent.getCombined(store(),ref,InventoryComponent.BACKPACK_STORAGE_HOTBAR);}
    public ItemContainer hotbar(){return store().getComponent(ref,InventoryComponent.Hotbar.getComponentType()).getInventory();}
    public void save()throws Exception{PlayerInventoryPersistence.save(world,owner).get(10,TimeUnit.SECONDS);}
    @Override public void close(){
        store().assertThread();
        if(owner.getReference()!=null&&owner.getReference().isValid()){
            var tracker=store().getComponent(ref,ChunkTracker.getComponentType());if(tracker!=null)tracker.clear();
            owner.removeFromStore();
        }
        require(!ref.isValid()&&owner.getReference()==null,"Native fixture removal detaches and untracks the player");
    }
    public static void verifyPersistence(World world)throws Exception{
        require(Universe.get().getPlayerStorage() instanceof DiskPlayerStorageProvider.DiskPlayerStorage,"Native DiskPlayerStorage is the active real persistence provider");
        UUID id;ItemStack expected;
        try(var fixture=create(world,"NativePersistenceVerification",new Vector3d(22.5,18,22.5))){
            id=fixture.owner().getUuid();
            expected=new ItemStack("SM_Graviton_Hammer",1).withMetadata("NativePlayerFixture",Codec.STRING,"disk-roundtrip");
            fixture.hotbar().setItemStackForSlot((short)0,expected,false);
            var config=fixture.player().getPlayerConfigData();config.setKnownRecipes(new HashSet<>(Set.of("SM_Field_Scanner")));
            fixture.save();
            require(Universe.get().getPlayerStorage().getPlayers().contains(id),"Required save creates an actual native player JSON file");
            var persisted=Universe.get().getPlayerStorage().load(id).get(10,TimeUnit.SECONDS);
            require(persisted.getComponent(InventoryComponent.Hotbar.getComponentType()).getInventory().getItemStack((short)0).equals(expected),"Real DiskPlayerStorage decode restores exact inventory and metadata");
            require(persisted.getComponent(Player.getComponentType()).getPlayerConfigData().getKnownRecipes().contains("SM_Field_Scanner"),"Real player configuration is serialized alongside inventory");
            // The storage serializer snapshots synchronously; later mutations must not alter its saved holder.
            fixture.hotbar().setItemStackForSlot((short)0,new ItemStack("SM_Resonite_Ingot",2),false);
            var unchanged=Universe.get().getPlayerStorage().load(id).get(10,TimeUnit.SECONDS);
            require(unchanged.getComponent(InventoryComponent.Hotbar.getComponentType()).getInventory().getItemStack((short)0).equals(expected),"Unsaved live inventory mutation does not change the completed disk receipt");
            // Native UNLOAD also saves. Restore the intended inventory before exercising that path.
            fixture.hotbar().setItemStackForSlot((short)0,expected,false);fixture.save();
        }
        try(var restored=load(world,id,"NativePersistenceVerification",new Vector3d(22.5,18,22.5))){
            require(restored.hotbar().getItemStack((short)0).equals(expected),"Native addToStore reattaches an inventory loaded from disk without legacy clone");
        }
        System.out.println("NATIVE_PLAYER_PERSISTENCE_VERIFICATION_PASSED: actual ECS player add/remove, native inventory initialization, required DiskPlayerStorage save/decode, exact item metadata, recipe knowledge and reattached saved player.");
    }
    public static final class RecordingPackets extends GamePacketHandler {
        public final List<ToClientPacket> packets;
        public RecordingPackets(){this(new RecordingChannel());}
        private RecordingPackets(RecordingChannel channel){
            super(channel,new ProtocolVersion(0),null);packets=channel.packets;
            // All native networking systems see a live, writable transport. Their actual packet
            // creation and chunk/entity tracking still run; only delivery to a socket is replaced.
            for(var network:NetworkChannel.values())setChannel(network,channel);
        }
        @Override public String getIdentifier(){return "Native test player (no socket)";}
        @Override public boolean isLocalConnection(){return true;}
        @Override public boolean isLANConnection(){return false;}
        @Override public void tryFlush(){}
        @Override public void write(ToClientPacket packet){packets.add(packet);}
        @Override public void writeNoCache(ToClientPacket packet){packets.add(packet);}
        @Override public boolean writePacket(ToClientPacket packet,boolean cache){packets.add(packet);return true;}
        @Override public void write(ToClientPacket... values){Collections.addAll(packets,values);}
        @Override public void write(ToClientPacket[] values,ToClientPacket last){Collections.addAll(packets,values);packets.add(last);}
        public <T extends ToClientPacket> List<T> ofType(Class<T> type){synchronized(packets){return packets.stream().filter(type::isInstance).map(type::cast).toList();}}
    }
    public static final class RecordingChannel implements ChannelConnection {
        final List<ToClientPacket> packets=Collections.synchronizedList(new ArrayList<>());
        private boolean active=true;
        @Override public void flush(){}
        @Override public void write(ToClientPacket packet){packets.add(packet);}
        @Override public void writeAndFlush(ToClientPacket packet){write(packet);}
        @Override public void write(ToClientPacket[] values){Collections.addAll(packets,values);}
        @Override public void writeAndFlush(ToClientPacket[] values){write(values);}
        @Override public boolean isActive(){return active;}
        @Override public boolean isWritable(){return active;}
        @Override public java.net.SocketAddress remoteAddress(){return new java.net.InetSocketAddress("127.0.0.1",0);}
        @Override public String formatRemoteAddress(){return "native-test-loopback";}
        @Override public void disconnect(FormattedMessage message){active=false;}
        @Override public PacketStatsRecorder getPacketStatsRecorder(){return null;}
        @Override public String getSniHostname(){return "localhost";}
        @Override public boolean isFromSameOrigin(ChannelConnection other){return other instanceof RecordingChannel;}
        @Override public void execute(Runnable runnable){runnable.run();}
        @Override public java.security.cert.X509Certificate getClientCertificate(){return null;}
        @Override public void initTimeoutContext(String stage,String identifier){}
        @Override public void updateTimeoutContext(String stage,String identifier){}
        @Override public void updateTimeoutContext(String stage){}
        @Override public void setPacketTimeout(java.time.Duration timeout){}
        @Override public void clearPacketTimeout(){}
        @Override public void setStageTimeout(String stage,java.time.Duration timeout,java.util.function.BooleanSupplier condition,Runnable onTimeout){}
        @Override public void clearStageTimeout(){}
        @Override public void logConnectionTimings(String message,java.util.logging.Level level){}
        @Override public java.util.concurrent.CompletableFuture<Void> setupAuxiliaryChannels(ConnectionHandler handler,java.util.function.BiConsumer<NetworkChannel,ChannelConnection> ready){
            for(var network:NetworkChannel.values())ready.accept(network,this);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        @Override public void setChannelHandler(ConnectionHandler handler){}
        @Override public void closeConnection(){active=false;}
        @Override public void closeApplicationConnection(){active=false;}
        @Override public void closeApplicationConnection(QuicApplicationErrorCode code){active=false;}
        @Override public void closeApplicationConnection(QuicApplicationErrorCode code,FormattedMessage message){active=false;}
        @Override public void updateStreamPriority(int urgency,boolean incremental){}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
