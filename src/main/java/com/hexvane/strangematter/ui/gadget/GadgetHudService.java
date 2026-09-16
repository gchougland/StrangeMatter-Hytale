package com.hexvane.strangematter.ui.gadget;

import com.hexvane.strangematter.research.ResearchDisciplineUi;
import com.hexvane.strangematter.research.ResearchType;
import com.hexvane.strangematter.equipment.GadgetEnergy;
import com.hexvane.strangematter.equipment.BatteryPackService;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A keyed native HUD layer: instrument feedback never replaces another mod's HUD. */
public final class GadgetHudService implements AutoCloseable {
    public static final String KEY = "SM_Gadget_Hud";
    static final String DOCUMENT_RESOURCE = "/Common/UI/Custom/StrangeMatter/GadgetHud.ui";
    private static final String DOCUMENT = loadDocument();
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();
    private volatile boolean closed;
    public record EnergyReadout(int current,int capacity,int pack,int packCapacity,int transfer,boolean compact) {}
    public record Readout(String title, String status, String detail, double progress, boolean error, String itemId, ResearchType discipline, EnergyReadout energy) {
        public Readout(String title,String status,String detail,double progress,boolean error,String itemId,ResearchType discipline){this(title,status,detail,progress,error,itemId,discipline,null);}
        public Readout(String title, String status, String detail, double progress, boolean error, String itemId) {
            this(title, status, detail, progress, error, itemId, null);
        }
        public Readout {
            title = bounded(title, 48); status = bounded(status, 82); detail = bounded(detail, 200);
            progress = Double.isFinite(progress) && progress >= 0 ? Math.min(1, progress) : -1;
            itemId = itemId == null || itemId.isBlank() ? "SM_Research_Tablet" : itemId;
        }
        private static String bounded(String value, int length) { return value == null ? "" : value.substring(0, Math.min(length, value.length())); }
    }
    private static final class State {
        final PlayerRef player; final Store<EntityStore> store; final World world; final GadgetHud hud;
        Readout base, notice; long baseUntil, noticeUntil, activeUntil;ItemStack activeStack;
        State(PlayerRef player, Store<EntityStore> store) {
            this.player = player; this.store = store; this.world = store.getExternalData().getWorld(); this.hud = new GadgetHud(player);
        }
        Readout visible(long now) { return notice != null && now < noticeUntil ? notice : now < baseUntil ? base : null; }
    }
    /** Refresh while a gadget is held; stops displaying within one second after updates cease. */
    public void update(PlayerRef player, Store<EntityStore> store, String title, String status, String detail, double progress) {
        update(player, store, title, status, detail, progress, null);
    }
    public void update(PlayerRef player, Store<EntityStore> store, String title, String status, String detail, double progress, ResearchType discipline) {
        onWorld(store, () -> {
            State state = obtain(player, store); if (state == null) return;
            state.activeUntil=0;
            state.base = new Readout(title, status, detail, progress, false, heldItem(player, store), discipline); state.baseUntil = System.nanoTime() + 1_000_000_000L;
            render(state, System.nanoTime());
        });
    }
    /** Deployed items keep their meter attached to the exact payload owned by their recovery ledger. */
    public void active(PlayerRef player,Store<EntityStore> store,ItemStack stack,String title,String status,String detail){
        onWorld(store,()->{State state=obtain(player,store);if(state==null)return;
            state.activeStack=stack;state.activeUntil=System.nanoTime()+1_000_000_000L;state.baseUntil=state.activeUntil;
            state.base=new Readout(title,status,detail,-1,false,stack.getItemId());render(state,System.nanoTime());});
    }
    /** A short result/error overlay; routine held-tool refreshes cannot immediately erase it. */
    public void notice(PlayerRef player, Store<EntityStore> store, String title, String status, String detail, boolean error) {
        notice(player, store, title, status, detail, error, null);
    }
    public void notice(PlayerRef player, Store<EntityStore> store, String title, String status, String detail, boolean error, ResearchType discipline) {
        onWorld(store, () -> {
            State state = obtain(player, store); if (state == null) return;
            state.notice = new Readout(title, status, detail, -1, error, heldItem(player, store), discipline); state.noticeUntil = System.nanoTime() + 4_000_000_000L;
            render(state, System.nanoTime());
        });
    }
    public void clear(PlayerRef player, Store<EntityStore> store) {
        onWorld(store, () -> { State state = states.get(player.getUuid()); if (state != null && state.store == store) remove(state); });
    }
    public void tick(World world, double dt) {
        long now = System.nanoTime();
        for (State state : states.values()) if (state.world == world) render(state, now);
    }
    public void cleanup(World world) { for (State state : states.values()) if (state.world == world) remove(state); }
    private State obtain(PlayerRef player, Store<EntityStore> store) {
        if (closed || component(player, store) == null) return null;
        State previous = states.get(player.getUuid());
        if (previous != null && previous.store == store && previous.player == player) return previous;
        State created = new State(player, store); states.put(player.getUuid(), created); return created;
    }
    private void render(State state, long now) {
        Player player = component(state.player, state.store); Readout visible = state.visible(now);
        if (player == null) { remove(state); return; }
        var held=InventoryComponent.getItemInHand(state.store,state.player.getReference());
        // A worn reserve supplements a held instrument; wearing armor never owns a HUD layer.
        var pack=GadgetEnergy.powered(held)?BatteryPackService.equipped(state.player,state.store):ItemStack.EMPTY;
        if(now<state.activeUntil&&state.activeStack!=null)held=state.activeStack;
        String heldId=ItemStack.isEmpty(held)?"SM_Research_Tablet":held.getItemId();
        if(visible!=null&&!visible.itemId().equals(heldId))visible=state.base!=null&&state.base.itemId().equals(heldId)&&now<state.baseUntil?state.base:null;
        if (visible == null) { remove(state); return; }
        var energy=new EnergyReadout(GadgetEnergy.charge(held),GadgetEnergy.capacity(held),GadgetEnergy.charge(pack),GadgetEnergy.capacity(pack),BatteryPackService.rate(state.player.getUuid()),false);
        visible=new Readout(visible.title(),visible.status(),visible.detail(),visible.progress(),visible.error(),visible.itemId(),visible.discipline(),energy);
        var manager = player.getHudManager();
        if (manager.getCustomHud(KEY) != state.hud) manager.addCustomHud(state.player, state.hud);
        state.hud.render(visible);
    }
    private void remove(State state) {
        if (!states.remove(state.player.getUuid(), state)) return;
        Player player = component(state.player, state.store);
        if (player != null && player.getHudManager().getCustomHud(KEY) == state.hud)
            player.getHudManager().removeCustomHud(state.player, KEY);
    }
    private static Player component(PlayerRef player, Store<EntityStore> store) {
        var ref = player.getReference();
        return ref == null || !ref.isValid() || ref.getStore() != store ? null : store.getComponent(ref, Player.getComponentType());
    }
    private static String heldItem(PlayerRef player, Store<EntityStore> store) {
        var stack = InventoryComponent.getItemInHand(store, player.getReference());
        return stack == null || stack.isEmpty() ? "SM_Research_Tablet" : stack.getItemId();
    }
    private static void onWorld(Store<EntityStore> store, Runnable action) {
        World world = store.getExternalData().getWorld();
        if (world.isInThread()) action.run();
        else try { world.execute(action); } catch (RuntimeException stopped) { /* World teardown owns removal. */ }
    }
    @Override public void close() {
        closed = true;
        for (State state : states.values()) onWorld(state.store, () -> remove(state));
    }
    private static String loadDocument() {
        try (var stream = GadgetHudService.class.getResourceAsStream(DOCUMENT_RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing packaged gadget HUD: " + DOCUMENT_RESOURCE);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read packaged gadget HUD: " + DOCUMENT_RESOURCE, failure);
        }
    }
    static final class GadgetHud extends CustomUIHud {
        private Readout shown;
        GadgetHud(PlayerRef player) { super(player, KEY, 20); }
        @Override protected void build(UICommandBuilder commands) {
            // This small template has no imports. Sending it inline avoids a client document
            // registry lookup while preserving the packaged UI file as the single layout source.
            // Native SpectatingHud uses an absent selector for the document root. An empty
            // string is serialized as a present, zero-length selector and reaches the parser.
            commands.appendInline(null, DOCUMENT);
            // A native HUD reset can reattach this instance. Its newly built elements need values
            // even when the instrument's readout is unchanged since the previous attachment.
            shown = null;
        }
        void render(Readout data) {
            if (data.equals(shown)) return;
            UICommandBuilder cmd = new UICommandBuilder();
            cmd.set("#GadgetTitle.Text", data.title()); cmd.set("#GadgetStatus.Text", data.status()); cmd.set("#GadgetDetail.Text", data.detail());
            cmd.set("#GadgetIcon.ItemId", data.itemId());
            cmd.set("#GadgetDiscipline.Visible", data.discipline() != null);
            if (data.discipline() != null) {
                ResearchDisciplineUi.icon(cmd, "#GadgetDisciplineIcon", data.discipline());
                cmd.set("#GadgetDisciplineName.Text", data.discipline().displayName());
            }
            cmd.set("#GadgetAccent.Background", data.error() ? "#f37c9c" : "#67e8ef");
            cmd.set("#GadgetStatus.Style.TextColor", data.error() ? "#f59bb4" : "#c1a5ef");
            cmd.set("#GadgetProgress.Visible", data.progress() >= 0);
            Anchor fill = new Anchor(); fill.setLeft(Value.of(0)); fill.setTop(Value.of(0)); fill.setHeight(Value.of(5));
            fill.setWidth(Value.of(Math.max(1, (int) (data.progress() * 340)))); cmd.setObject("#GadgetProgressFill.Anchor", fill);
            var energy=data.energy();boolean compact=energy!=null&&energy.compact();
            Anchor panel=new Anchor();panel.setRight(Value.of(34));panel.setBottom(Value.of(176));panel.setWidth(Value.of(376));panel.setHeight(Value.of(compact?114:energy!=null&&energy.packCapacity()>0?330:energy!=null&&energy.capacity()>0?292:244));cmd.setObject("#GadgetPanel.Anchor",panel);
            cmd.set("#GadgetStatus.Visible",!compact);cmd.set("#GadgetDetail.Visible",!compact);
            cmd.set("#GadgetEnergy.Visible",energy!=null&&energy.capacity()>0);
            cmd.set("#GadgetPack.Visible",energy!=null&&energy.packCapacity()>0&&!compact);
            if(energy!=null&&energy.capacity()>0){
                Anchor row=new Anchor();row.setLeft(Value.of(16));row.setTop(Value.of(compact?66:240));row.setWidth(Value.of(340));row.setHeight(Value.of(38));cmd.setObject("#GadgetEnergy.Anchor",row);
                String state=energy.current()==0?"EMPTY":energy.current()*5L<=energy.capacity()?"LOW":"Energy";
                cmd.set("#GadgetEnergyText.Text",state+": "+energy.current()+" / "+energy.capacity()+" RE");
                cmd.set("#GadgetEnergyFill.Visible",energy.current()>0);
                meter(cmd,"#GadgetEnergyFill",(double)energy.current()/energy.capacity());
                cmd.set("#GadgetEnergyFill.Background",energy.current()*5L<=energy.capacity()?"#eeb268":"#50e7ec");
            }
            if(energy!=null&&energy.packCapacity()>0&&!compact)cmd.set("#GadgetPackText.Text","Pack: "+energy.pack()+" / "+energy.packCapacity()+" RE"+(energy.transfer()>0?"  +"+energy.transfer()+" RE/s":""));
            update(false, cmd); shown = data;
        }
        private static void meter(UICommandBuilder cmd,String selector,double amount){Anchor fill=new Anchor();fill.setLeft(Value.of(0));fill.setTop(Value.of(0));fill.setHeight(Value.of(7));fill.setWidth(Value.of(Math.max(1,(int)(Math.clamp(amount,0,1)*340))));cmd.setObject(selector+".Anchor",fill);}
    }
}
