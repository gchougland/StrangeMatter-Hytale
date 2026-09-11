package com.hexvane.strangematter.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One outstanding world-queue probe; diagnostics never wait for a world or acquire a gameplay lock. */
public final class WorldStallDiagnostics implements AutoCloseable {
    private static final long STALL = TimeUnit.SECONDS.toNanos(20);
    private static final long REPORT_INTERVAL = TimeUnit.SECONDS.toNanos(60);
    private final Map<String, Watch> worlds = new ConcurrentHashMap<>();
    private final Consumer<String> report;
    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;

    private static final class Watch {
        final Executor executor;
        final BooleanSupplier alive;
        volatile long thread;
        volatile String stage = "native world or another plugin";
        volatile Probe pending;
        Watch(Executor executor, BooleanSupplier alive) { this.executor=executor; this.alive=alive; }
    }
    private static final class Probe {
        final long started;
        long reported;
        boolean warned;
        Probe(long started) { this.started=started; }
    }

    public WorldStallDiagnostics(Consumer<String> report) { this(report,System::nanoTime,true); }
    WorldStallDiagnostics(Consumer<String> report, LongSupplier clock, boolean automatic) {
        this.report=Objects.requireNonNull(report); this.clock=clock;
        scheduler=automatic?Executors.newSingleThreadScheduledExecutor(r->{
            var thread=new Thread(r,"StrangeMatter world diagnostics");thread.setDaemon(true);return thread;
        }):null;
        if(scheduler!=null)scheduler.scheduleWithFixedDelay(this::pollSafely,5,5,TimeUnit.SECONDS);
    }

    /** Register/update only from the owning world's thread. A replaced world gets a new watch. */
    public void observe(String name, Executor executor, BooleanSupplier alive) {
        if(closed)return;
        Watch watch=worlds.compute(name,(key,old)->old!=null&&old.executor==executor?old:new Watch(executor,alive));
        watch.thread=Thread.currentThread().threadId();
    }
    public void stage(String name,String stage) {
        Watch watch=worlds.get(name);if(watch!=null)watch.stage=stage;
    }
    public void forget(String name) { worlds.remove(name); }
    private void pollSafely() {
        try { poll(); }
        catch(RuntimeException failure) { /* Diagnostics must never terminate gameplay or flood a failing logger. */ }
    }
    void poll() {
        if(closed)return;
        long now=clock.getAsLong();
        for(var entry:worlds.entrySet()) {
            String name=entry.getKey();Watch watch=entry.getValue();
            if(!watch.alive.getAsBoolean()) { worlds.remove(name,watch);continue; }
            Probe probe=watch.pending;
            if(probe==null) {
                probe=new Probe(now);watch.pending=probe;Probe queued=probe;
                try { watch.executor.execute(()->{if(watch.pending==queued)watch.pending=null;}); }
                catch(RuntimeException stopped) { worlds.remove(name,watch); }
            } else if(now-probe.started>=STALL && (!probe.warned||now-probe.reported>=REPORT_INTERVAL)) {
                probe.warned=true;probe.reported=now;
                report.accept("Strange Matter world stall diagnostic: world="+name+
                    ", queue unresponsive for "+TimeUnit.NANOSECONDS.toSeconds(now-probe.started)+
                    "s, last stage="+watch.stage+". This is a diagnostic, not a determination of which mod caused the stall.\n"+
                    threadSnapshot(watch.thread));
            }
        }
    }
    static String threadSnapshot(long worldThread) {
        var bean=ManagementFactory.getThreadMXBean();
        ThreadInfo[] dump=bean.dumpAllThreads(bean.isObjectMonitorUsageSupported(),bean.isSynchronizerUsageSupported(),96);
        Map<Long,ThreadInfo> byId=new LinkedHashMap<>();
        for(var info:dump)if(info!=null)byId.put(info.getThreadId(),info);
        Set<Long> selected=new LinkedHashSet<>();
        addOwnerChain(worldThread,byId,selected);
        // Include generation workers or other worlds blocked inside this mod even when a future has no lock owner.
        for(var info:dump)if(info!=null&&selected.size()<16&&Arrays.stream(info.getStackTrace()).anyMatch(frame->
                frame.getClassName().startsWith("com.hexvane.strangematter.")&&
                !frame.getClassName().equals(WorldStallDiagnostics.class.getName())))
            addOwnerChain(info.getThreadId(),byId,selected);
        StringBuilder out=new StringBuilder();
        for(long id:selected) {
            ThreadInfo info=byId.get(id);if(info==null)continue;
            out.append('"').append(info.getThreadName()).append("\" id=").append(id).append(' ').append(info.getThreadState());
            if(info.getLockInfo()!=null)out.append(" waiting on ").append(info.getLockInfo());
            if(info.getLockOwnerId()!=-1)out.append(" owned by ").append(info.getLockOwnerName()).append(" id=").append(info.getLockOwnerId());
            out.append('\n');
            for(var frame:info.getStackTrace())out.append("    at ").append(frame).append('\n');
            for(var monitor:info.getLockedMonitors())out.append("    locked ").append(monitor).append(" at depth ").append(monitor.getLockedStackDepth()).append('\n');
            for(var lock:info.getLockedSynchronizers())out.append("    owns ").append(lock).append('\n');
        }
        return out.length()==0?"World thread ended before its stack could be captured.":out.toString();
    }
    private static void addOwnerChain(long id,Map<Long,ThreadInfo> infos,Set<Long> selected) {
        while(id!=-1&&selected.size()<16&&selected.add(id)) {
            ThreadInfo info=infos.get(id);if(info==null)break;id=info.getLockOwnerId();
        }
    }
    @Override public void close() { closed=true;worlds.clear();if(scheduler!=null)scheduler.shutdownNow(); }
}
