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
        System.out.println("NATIVE_COGNITION_SYMBOLS_VERIFICATION_PASSED: nine stable native bindings, all wire-decoded controls delivered, nine exclusive matching glyph cues, no numerical labels or rebound events, original debounce and real sequence completion under pending ACK.");
    }
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
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
