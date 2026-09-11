package com.hexvane.strangematter.ui;

import com.google.gson.JsonParser;
import com.hexvane.strangematter.research.*;
import com.hypixel.hytale.protocol.packets.interface_.*;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the actual page's binding/render methods through native wire builders and live routing. */
public final class NativeCognitionSymbolsVerification {
    public static void main(String[] args)throws Exception{verify();}
    public static void verify()throws Exception {
        var bind=ResearchMachinePage.class.getDeclaredMethod("bindCognition",UIEventBuilder.class,LivePageTransport.Lease.class);
        var render=ResearchMachinePage.class.getDeclaredMethod("renderCognition",UICommandBuilder.class,ResearchSession.Panel.class);
        bind.setAccessible(true);render.setAccessible(true);
        var game=new ResearchSession(ResearchCatalog.get("cognitive_anomalies"),109);game.begin();
        var clock=new AtomicLong();game.advanceInputClock(0);
        var connection=new LivePageTransport.Connection();var queue=new ArrayDeque<Runnable>();
        var controls=new ArrayList<Integer>();var playing=new AtomicBoolean();var accepted=new AtomicInteger();
        var lease=new LivePageTransport.Lease(connection,queue::addLast,()->true,data->{
            require("control".equals(data.action),"Existing control action retained");
            var parts=data.value.split(":");require(parts.length==3&&parts[0].equals("COGNITION")&&parts[1].equals("symbol"),"Existing discipline and symbol payload retained");
            int index=Integer.parseInt(parts[2]);controls.add(index);
            if(playing.get()){
                game.advanceInputClock(clock.get());
                if(game.control(ResearchType.COGNITION,"symbol",index))accepted.incrementAndGet();
            }
        },clock::get);
        connection.activate(lease);connection.sentPage(true);
        try {
            var events=new UIEventBuilder();bind.invoke(null,events,lease);
            var initial=wire(new UICommandBuilder(),events);
            require(initial.eventBindings.length==9,"Nine native controls are bound exactly once");
            var stableBindings=new String[9];
            for(int i=0;i<9;i++){
                var event=initial.eventBindings[i];
                require(event.selector.equals("#Rune"+i),"Stable row-major native hit target");
                require(JsonParser.parseString(event.data).getAsJsonObject().get("Value").getAsString().equals("COGNITION:symbol:"+i),"Visible symbol retains its original internal index");
                stableBindings[i]=event.data;
                route(connection,queue,event.data);
            }
            require(controls.equals(List.of(0,1,2,3,4,5,6,7,8)),"All nine native click targets reach their intended controls");
            require(!lease.ready(),"Click routing does not consume the pending visual acknowledgement");
            var panel=new ResearchSession.Panel();panel.displaying=true;panel.pattern=new int[]{0};
            for(int lit=0;lit<9;lit++){
                panel.pattern[0]=lit;var commands=new UICommandBuilder();render.invoke(null,commands,panel);
                var frame=wire(commands,new UIEventBuilder());
                require(frame.eventBindings.length==0,"Memory highlighting never rebuilds button event bindings");
                require(frame.commands.length==10,"A cognition frame changes only nine highlights and its readout");
                for(int i=0;i<9;i++)require(flag(frame,"#RuneGlow"+i+".Visible")== (i==lit),"Only the correct matching glyph is illuminated");
                require(Arrays.stream(frame.commands).noneMatch(c->c.selector!=null&&c.selector.matches("#Rune[0-8]\\.Text")),"No frame reintroduces numerical button text");
                for(int i=0;i<9;i++)require(initial.eventBindings[i].data.equals(stableBindings[i]),"Animation leaves the original click bindings unchanged");
            }
            panel.displaying=false;var idle=new UICommandBuilder();render.invoke(null,idle,panel);
            var idleFrame=wire(idle,new UIEventBuilder());
            for(int i=0;i<9;i++)require(!flag(idleFrame,"#RuneGlow"+i+".Visible"),"Recall phase clears every memory glow");
            controls.clear();playing.set(true);long ticks=game.ticks();
            for(int index:game.panel(ResearchType.COGNITION).pattern){
                route(connection,queue,stableBindings[index]);
                require(!lease.ready(),"Symbol input stays responsive under a pending ACK");
                require(game.panel(ResearchType.COGNITION).cooldown==5,"Source five-tick click debounce retained");
                clock.addAndGet(250_000_000L);
            }
            require(accepted.get()==3&&game.panel(ResearchType.COGNITION).stable,"Actual three-symbol sequence completes through unchanged native bindings");
            require(game.ticks()==ticks,"Unacknowledged symbols do not advance unseen simulation frames");
        } finally {lease.close();}
        verifyOrderedPresentation(bind,render,0,false);
        for(long delay:new long[]{0,1_200_000_000L})verifyOrderedPresentation(bind,render,delay,true);
        verifyShadowOverlay();
        verifyOtherOverlays();
        verifyAnimatedCalibration();
        System.out.println("NATIVE_COGNITION_SYMBOLS_VERIFICATION_PASSED: nine stable native bindings, all wire-decoded controls delivered, nine exclusive matching glyph cues, no numerical labels or rebound events, original debounce and real sequence completion under pending ACK.");
    }
    private static final class GatePage extends com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage {
        LivePageTransport.Lease lease;
        GatePage(){super(null,CustomPageLifetime.CanDismissOrCloseThroughInteraction);}
        @Override public void build(com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,UICommandBuilder cmd,UIEventBuilder events,com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store){}
        @Override public void handleDataEvent(com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> ref,com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,String raw){lease.receiveFromNative(raw);}
    }
    private static void verifyOrderedPresentation(java.lang.reflect.Method bind,java.lang.reflect.Method render,long delay,boolean disruptions)throws Exception{
        var manager=new com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager();var page=new GatePage();
        var pageField=manager.getClass().getDeclaredField("customPage");pageField.setAccessible(true);pageField.set(manager,page);
        var counterField=manager.getClass().getDeclaredField("customPageRequiredAcknowledgments");counterField.setAccessible(true);
        // Test-only setup of the real native gate. Production never reads or changes this counter.
        var counter=(AtomicInteger)counterField.get(manager);var clock=new AtomicLong();var queue=new ArrayDeque<Runnable>();
        var connection=new LivePageTransport.Connection();var game=new ResearchSession(ResearchCatalog.get("cognitive_anomalies"),427);
        var p=game.panel(ResearchType.COGNITION);var accepted=new AtomicInteger();game.advanceInputClock(0);
        var lease=new LivePageTransport.Lease(connection,queue::addLast,()->manager.getCustomPage()==page,data->{
            game.advanceInputClock(clock.get());if(game.control(ResearchType.COGNITION,"symbol",Integer.parseInt(data.value.split(":")[2])))accepted.incrementAndGet();
        },clock::get);page.lease=lease;connection.activate(lease);
        var probeField=lease.getClass().getDeclaredField("nativeProbe");probeField.setAccessible(true);
        probeField.set(lease,(java.util.function.Consumer<String>)raw->manager.handleEvent(null,null,new CustomPageEvent(CustomPageEventType.Data,raw)));
        try{
            var initialCommands=new UICommandBuilder();render.invoke(null,initialCommands,p);var events=new UIEventBuilder();bind.invoke(null,events,lease);
            var initial=wire(initialCommands,events);require(lit(initial)==-1,"Inserted note has no highlighted rune before Begin");
            connection.sentPage(true);counter.incrementAndGet();game.begin();
            long ackAt=delay;int frames=0,lastLit=-1;long changedAt=0;var observed=new ArrayList<Integer>();boolean longDelay=false,recall=false;
            for(int pulse=0;pulse<1000; pulse++){
                clock.addAndGet(200_000_000L);
                if(counter.get()>0&&clock.get()>=ackAt){
                    // Reproduce a real ACK which Hytale processes but the passive observer misses.
                    if(!disruptions||frames%7!=0)connection.acknowledged();
                    manager.handleEvent(null,null,new CustomPageEvent(CustomPageEventType.Acknowledge,null));
                }
                long ticks=game.ticks();int index=p.displayIndex;boolean gap=p.displayGap;
                if(!lease.ready()){
                    require(game.ticks()==ticks&&p.displayIndex==index&&p.displayGap==gap,"Pending native frame cannot skip a cue or advance instrument physics");
                    continue;
                }
                game.advancePresentationClock(clock.get());for(int i=0;i<4;i++)game.tick();
                var commands=new UICommandBuilder();render.invoke(null,commands,p);var frame=wire(commands,new UIEventBuilder());
                require(frame.eventBindings.length==0,"Full cue playback retains the initial click bindings");
                int shown=lit(frame);
                if(shown!=lastLit){
                    if(lastLit>=0)require(clock.get()-changedAt>=new ResearchSettings().cognitionCueTicks()*50_000_000L,"Every numbered symbol has its full configured visible hold");
                    if(shown>=0){
                        require(lastLit==-1,"Each consecutive symbol is separated by an acknowledged dark gap");
                        if(!observed.isEmpty())require(clock.get()-changedAt>=300_000_000L,"Inter-symbol gap is visibly held");
                        require(shown==p.pattern[observed.size()],"Visible cues follow the complete generated order");observed.add(shown);
                    }
                    lastLit=shown;changedAt=clock.get();
                }
                require(connection.reserve(),"Cue frame reserves only a native-ready transport slot");connection.sentPage(false);counter.incrementAndGet();frames++;
                ackAt=clock.get()+delay;
                if(disruptions&&shown>=0&&observed.size()==2&&!longDelay){ackAt=clock.get()+8_000_000_000L;longDelay=true;}
                if(!p.displaying&&p.cueStarted){recall=true;break;}
                require(game.state()==ResearchSession.State.RUNNING,"Full visible pattern fits the original instability budget");
            }
            require(recall&&observed.equals(Arrays.stream(p.pattern).boxed().toList())&&(!disruptions||longDelay),"Complete ordered pattern and recall survive delayed and unobserved native ACKs");
            require(clock.get()<65_000_000_000L,"Delayed frames cannot stretch one highlight indefinitely");
            if(!disruptions){
                // Genuine 20 Hz case: no network pauses discount the instability budget.
                // Allow three full seconds of recall before entering the answer.
                for(int i=0;i<15;i++){
                    clock.addAndGet(200_000_000L);connection.acknowledged();
                    manager.handleEvent(null,null,new CustomPageEvent(CustomPageEventType.Acknowledge,null));
                    require(lease.ready(),"Immediate actual ACK permits the next 20 Hz batch");
                    game.advancePresentationClock(clock.get());for(int tick=0;tick<4;tick++)game.tick();
                    var commands=new UICommandBuilder();render.invoke(null,commands,p);var frame=wire(commands,new UIEventBuilder());
                    require(lit(frame)==-1&&!p.displaying,"Recall interval remains dark and playable");
                    require(connection.reserve(),"One native recall frame at a time");connection.sentPage(false);counter.incrementAndGet();
                }
                require(game.ticks()*50_000_000L==clock.get()&&game.ticks()>=140&&game.ticks()<200,
                        "The entire default cue and recall run at genuine 20 Hz without a paused instability clock");
                require(game.state()==ResearchSession.State.RUNNING&&game.instability()<.85,
                        "Full default pattern plus three seconds of recall fit the original failure window with room to answer");
            }
            // While the recall frame is still outstanding, real bound controls remain playable.
            for(int symbol:p.pattern){route(connection,queue,initial.eventBindings[symbol].data);clock.addAndGet(250_000_000L);}
            require(accepted.get()==p.pattern.length&&p.stable&&counter.get()==1,"Full recall succeeds at original debounce without consuming the native ACK");
            var locked=new UICommandBuilder();render.invoke(null,locked,p);require(lit(wire(locked,new UIEventBuilder()))==-1,"Correct sequence clears all highlights instead of leaving one stuck");
            connection.acknowledged();manager.handleEvent(null,null,new CustomPageEvent(CustomPageEventType.Acknowledge,null));
            require(counter.get()==0&&lease.ready(),"Original ACK drains after recall without underflow");
        }finally{lease.close();}
        System.out.println("PASS: complete numbered cognition cue with dark gaps, real native gate, "+delay/1_000_000+"ms RTT, "+(disruptions?"8s pending frame and skipped ACK observations":"genuine 20 Hz with three seconds of recall")+" and live input.");
    }
    private static void verifyShadowOverlay()throws Exception{
        var render=ResearchMachinePage.class.getDeclaredMethod("renderShadow",UICommandBuilder.class,ResearchSession.Panel.class);render.setAccessible(true);
        try(var service=new ResearchService(java.nio.file.Files.createTempDirectory("sm-shadow-overlay-"))){
            var page=new ResearchMachinePage(overlayPlayer(),service,new org.joml.Vector3i());
            for(int angle=-60;angle<=60;angle+=15)for(int distance=20;distance<=50;distance+=5){
                var p=new ResearchSession.Panel();p.value=angle;p.secondary=distance;p.target=angle+180;p.targetSecondary=ResearchSession.shadowLength(p);
                var commands=new UICommandBuilder();render.invoke(page,commands,p);var frame=wire(commands,new UIEventBuilder());
                for(int i=0;i<12;i++){
                    var target=anchor(frame,"#ShadowTarget"+i+".Anchor");var live=anchor(frame,"#ShadowLive"+i+".Anchor");
                    for(String axis:List.of("Left","Top"))require(target.get(axis).getAsDouble()+3==live.get(axis).getAsDouble()+2,"Exactly solved shadow segments share their native pixel centres");
                }
            }
        }
    }
    private static void verifyOtherOverlays()throws Exception {
        var energy=ResearchMachinePage.class.getDeclaredMethod("renderEnergy",UICommandBuilder.class,ResearchSession.Panel.class);energy.setAccessible(true);
        var time=ResearchMachinePage.class.getDeclaredMethod("renderTime",UICommandBuilder.class,ResearchSession.Panel.class);time.setAccessible(true);
        try(var service=new ResearchService(java.nio.file.Files.createTempDirectory("sm-instrument-overlay-"))){
            var page=new ResearchMachinePage(overlayPlayer(),service,new org.joml.Vector3i());
            var session=ResearchMachinePage.class.getDeclaredField("session");session.setAccessible(true);session.set(page,new ResearchSession(ResearchCatalog.get("cognitive_anomalies"),1));
            for(int step=0;step<=20;step++){
                var p=new ResearchSession.Panel();p.value=p.target=.5+step*.05;p.secondary=p.targetSecondary=1+(step%6)*.05;
                var commands=new UICommandBuilder();energy.invoke(page,commands,p);var frame=wire(commands,new UIEventBuilder());
                verifyCentres(frame,"WaveTarget","WaveLive",32);
                require(label(frame,"#EnergyLock.Text").equals("Match cyan to purple"),"Default wave instructions never request the removed five-second hold");
            }
            for(int angle=0;angle<360;angle+=5){
                var p=new ResearchSession.Panel();p.angle=p.targetAngle=angle;var commands=new UICommandBuilder();time.invoke(page,commands,p);
                verifyCentres(wire(commands,new UIEventBuilder()),"TimeTarget","TimeLive",12);
            }
            for(double target:new double[]{-.7,.6,1.3,2}){
                var p=new ResearchSession.Panel();p.value=.25;p.target=target;p.angle=30;p.targetAngle=90;
                var commands=new UICommandBuilder();time.invoke(page,commands,p);var frame=wire(commands,new UIEventBuilder());
                require(label(frame,"#TimeReadout.Text").equals("Cyan 0.25x   Match the purple hand"),"Native UI exposes only cyan speed, never the purple target's numeric answer");
            }
        }
    }
    private static com.hypixel.hytale.server.core.universe.PlayerRef overlayPlayer(){
        // These reflection-based render checks need a stable research identity, not a live entity or socket.
        return new com.hypixel.hytale.server.core.universe.PlayerRef(null,UUID.randomUUID(),"InstrumentOverlay","en-US",null,null);
    }
    private static void verifyAnimatedCalibration()throws Exception{
        var bind=ResearchMachinePage.class.getDeclaredMethod("bindSpace",UIEventBuilder.class,LivePageTransport.Lease.class);bind.setAccessible(true);
        var energy=ResearchMachinePage.class.getDeclaredMethod("renderEnergy",UICommandBuilder.class,ResearchSession.Panel.class);energy.setAccessible(true);
        var space=ResearchMachinePage.class.getDeclaredMethod("renderSpace",UICommandBuilder.class,ResearchSession.Panel.class);space.setAccessible(true);
        var gravity=ResearchMachinePage.class.getDeclaredMethod("renderGravity",UICommandBuilder.class,ResearchSession.Panel.class);gravity.setAccessible(true);
        var manager=new com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager();var gatePage=new GatePage();
        var pageField=manager.getClass().getDeclaredField("customPage");pageField.setAccessible(true);pageField.set(manager,gatePage);
        var countField=manager.getClass().getDeclaredField("customPageRequiredAcknowledgments");countField.setAccessible(true);var counter=(AtomicInteger)countField.get(manager);
        var game=new ResearchSession(new ResearchNode("native_puzzles","general","Calibration","",Map.of(ResearchType.ENERGY,1,ResearchType.SPACE,1),List.of()),18);
        game.begin();var wave=game.panel(ResearchType.ENERGY);wave.value=wave.target;wave.secondary=wave.targetSecondary;
        var lattice=game.panel(ResearchType.SPACE);lattice.value=2;lattice.secondary=-2;
        var clock=new AtomicLong();game.advanceInputClock(0);var connection=new LivePageTransport.Connection();var queue=new ArrayDeque<Runnable>();var accepted=new AtomicInteger();
        var lease=new LivePageTransport.Lease(connection,queue::addLast,()->manager.getCustomPage()==gatePage,data->{
            var fields=data.value.split(":");game.advanceInputClock(clock.get());
            require(fields[0].equals("SPACE")&&game.control(ResearchType.SPACE,fields[1],Integer.parseInt(fields[2])),"Wire-decoded axis and bend controls remain valid while a frame is pending");accepted.incrementAndGet();
        },clock::get);gatePage.lease=lease;connection.activate(lease);
        var probe=lease.getClass().getDeclaredField("nativeProbe");probe.setAccessible(true);probe.set(lease,(java.util.function.Consumer<String>)raw->manager.handleEvent(null,null,new CustomPageEvent(CustomPageEventType.Data,raw)));
        try(var service=new ResearchService(java.nio.file.Files.createTempDirectory("sm-calibration-wire-"))){
            var page=new ResearchMachinePage(overlayPlayer(),service,new org.joml.Vector3i());
            var sessionField=ResearchMachinePage.class.getDeclaredField("session");sessionField.setAccessible(true);sessionField.set(page,game);
            var events=new UIEventBuilder();bind.invoke(null,events,lease);
            var bindings=wire(new UICommandBuilder(),events);require(bindings.eventBindings.length==4,"Both Space axes and both directions bind once");
            var cmds=new UICommandBuilder();energy.invoke(page,cmds,wave);var first=wire(cmds,new UIEventBuilder());
            connection.sentPage(true);counter.incrementAndGet();
            for(int poll=0;poll<20;poll++){clock.addAndGet(200_000_000L);require(!lease.ready(),"A real outstanding native ACK never advances the decorative wave");}
            require(wave.angle==0&&game.ticks()==0,"Waiting for native delivery leaves phase and physics untouched");
            connection.acknowledged();manager.handleEvent(null,null,new CustomPageEvent(CustomPageEventType.Acknowledge,null));require(lease.ready(),"Genuine ACK permits the next frame");
            game.tick();require(wave.stable&&wave.stableTicks==1,"The first acknowledged aligned wave tick becomes stable immediately");
            for(int i=0;i<3;i++)game.tick();cmds=new UICommandBuilder();energy.invoke(page,cmds,wave);var moved=wire(cmds,new UIEventBuilder());
            require(wave.angle>0&&!anchor(first,"#WaveLive0.Anchor").equals(anchor(moved,"#WaveLive0.Anchor")),"Acknowledged simulation actually moves the native wave geometry");
            verifyCentres(moved,"WaveTarget","WaveLive",32);require(wave.stableTicks==4,"Visual phase does not disturb correct amplitude and period");
            require(label(moved,"#EnergyLock.Text").equals("Waves locked"),"The first acknowledged aligned frame reports its stable lock");
            require(moved.eventBindings.length==0&&connection.reserve(),"Moving wave patches never replace or rebind controls");connection.sentPage(false);counter.incrementAndGet();
            long ticks=game.ticks();double phase=wave.angle;
            for(String selector:List.of("#SpaceHorizontal","#SpaceMinus","#SpaceVertical","#SpacePlus")){
                var event=Arrays.stream(bindings.eventBindings).filter(e->selector.equals(e.selector)).findFirst().orElseThrow();route(connection,queue,event.data);clock.addAndGet(250_000_000L);
            }
            require(accepted.get()==4&&lattice.value==1&&lattice.secondary==-1,"Actual nonced native controls reach the accepted visual margin using both axes and directions");
            require(counter.get()==1&&game.ticks()==ticks&&wave.angle==phase&&!lease.ready(),"Live puzzle input cannot steal the outstanding native ACK or advance unseen waves");
            connection.acknowledged();manager.handleEvent(null,null,new CustomPageEvent(CustomPageEventType.Acknowledge,null));require(lease.ready(),"Original native ACK still drains normally after live controls");
            for(int i=0;i<4;i++)game.tick();cmds=new UICommandBuilder();space.invoke(page,cmds,lattice);var aligned=wire(cmds,new UIEventBuilder());verifyCentres(aligned,"SpaceTarget","SpaceDot",49);
            require(lattice.stable&&lattice.value==0&&lattice.secondary==0&&aligned.eventBindings.length==0,"A near-aligned lattice visibly settles to exact target centres without rebinding");
            try(var stream=ResearchMachinePage.class.getResourceAsStream("/Common/UI/Custom/StrangeMatter/ResearchMachine.ui")){
                require(stream!=null,"Actual packaged instrument markup is present");var markup=new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                for(int i=0;i<49;i++){
                    final int index=i;
                    var line=markup.lines().filter(s->s.startsWith("Group #SpaceDot"+index+" {")).findFirst().orElseThrow();
                    require(line.contains("Background: #67dce5;"),"Every live lattice dot is cyan, including the former purple diagonal");
                }
            }
            for(int x=-6;x<=6;x++)for(int y=-6;y<=6;y++){
                var panel=new ResearchSession.Panel();panel.value=x;panel.secondary=y;cmds=new UICommandBuilder();space.invoke(page,cmds,panel);var frame=wire(cmds,new UIEventBuilder());
                for(int i=0;i<49;i++){
                    var point=anchor(frame,"#SpaceDot"+i+".Anchor");int left=point.get("Left").getAsInt(),top=point.get("Top").getAsInt();
                    require(left>=68&&left+4<=167&&top>=0&&top+4<=83,"All bent dots fit the actual plot and remain between the two helper labels");
                }
            }
            var force=new ResearchSession.Panel();
            for(int selected=-5;selected<=5;selected++){
                force.value=selected;cmds=new UICommandBuilder();gravity.invoke(page,cmds,force);var frame=wire(cmds,new UIEventBuilder());
                require(anchor(frame,"#ForceSelection.Anchor").get("Left").getAsInt()==(selected+5)*26+1,"Selection marker follows the actual counterforce");
                require(Arrays.stream(frame.commands).noneMatch(c->c.selector!=null&&c.selector.matches("#Force[0-9]+\\.Text")),"Selecting a force never widens its label with brackets");
            }
        }finally{lease.close();}
        System.out.println("PASS: real native ACK paced moving wave geometry, exact moving overlays, coupled Space input under held ACK, 49 restored lattice centres and all 11 unclipped gravity selections.");
    }
    private static void verifyCentres(CustomPage frame,String targetName,String liveName,int count){
        for(int i=0;i<count;i++){
            var target=anchor(frame,"#"+targetName+i+".Anchor");var live=anchor(frame,"#"+liveName+i+".Anchor");
            for(String axis:List.of("Left","Top"))require(target.get(axis).getAsDouble()+3==live.get(axis).getAsDouble()+2,"Exact instrument traces share native pixel centres: "+targetName);
        }
    }
    private static com.google.gson.JsonObject anchor(CustomPage frame,String selector){
        var command=Arrays.stream(frame.commands).filter(c->selector.equals(c.selector)).findFirst().orElseThrow();
        return JsonParser.parseString(command.data).getAsJsonObject().getAsJsonObject("0");
    }
    private static int lit(CustomPage frame){int result=-1;for(int i=0;i<9;i++)if(flag(frame,"#RuneGlow"+i+".Visible")){require(result==-1,"Only one cue may glow");result=i;}return result;}
    private static void route(LivePageTransport.Connection connection,ArrayDeque<Runnable> queue,String data){
        var event=new CustomPageEvent(CustomPageEventType.Data,data);
        var bytes=MemorySegment.ofArray(new byte[event.computeSize()]);event.serialize(bytes,0);
        LivePageTransport.route(connection,CustomPageEvent.toObject(bytes).data);
        int drained=0;while(!queue.isEmpty()){require(drained++<30,"Bounded callback queue");queue.removeFirst().run();}
    }
    private static CustomPage wire(UICommandBuilder commands,UIEventBuilder events){
        var page=new CustomPage(ResearchMachinePage.class.getName(),false,false,CustomPageLifetime.CanDismissOrCloseThroughInteraction,commands.getCommands(),events.getEvents());
        var bytes=MemorySegment.ofArray(new byte[page.computeSize()]);page.serialize(bytes,0);return CustomPage.toObject(bytes);
    }
    private static boolean flag(CustomPage frame,String selector){
        var command=Arrays.stream(frame.commands).filter(c->selector.equals(c.selector)).findFirst().orElseThrow();
        return JsonParser.parseString(command.data).getAsJsonObject().get("0").getAsBoolean();
    }
    private static String label(CustomPage frame,String selector){
        var command=Arrays.stream(frame.commands).filter(c->selector.equals(c.selector)).findFirst().orElseThrow();
        return JsonParser.parseString(command.data).getAsJsonObject().get("0").getAsString();
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
