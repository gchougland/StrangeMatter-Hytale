package com.hexvane.strangematter.diagnostics;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class WorldStallDiagnosticsVerification {
    public static void verify() throws Exception {
        AtomicLong clock=new AtomicLong();AtomicBoolean alive=new AtomicBoolean(true);
        Queue<Runnable> queue=new ArrayDeque<>();Executor executor=queue::add;
        List<String> reports=new ArrayList<>();
        try(var diagnostics=new WorldStallDiagnostics(reports::add,clock::get,false)) {
            diagnostics.observe("test",executor,alive::get);
            diagnostics.stage("test","Strange Matter anomalies");
            diagnostics.poll();
            for(int i=0;i<100;i++)diagnostics.poll();
            require(queue.size()==1&&reports.isEmpty(),"Blocked worlds cannot accumulate diagnostic tasks");
            clock.set(TimeUnit.SECONDS.toNanos(19));diagnostics.poll();
            require(reports.isEmpty(),"Short waits remain silent");
            clock.set(TimeUnit.SECONDS.toNanos(20));diagnostics.poll();
            require(reports.size()==1&&reports.getFirst().contains("Strange Matter anomalies"),"Stall captures last subsystem and real thread stack");
            require(reports.getFirst().contains("WorldStallDiagnosticsVerification.verify"),"Real JVM thread stack is present");
            clock.set(TimeUnit.SECONDS.toNanos(79));diagnostics.poll();
            require(reports.size()==1&&queue.size()==1,"A persistent stall does not spam logs or queues");
            clock.set(TimeUnit.SECONDS.toNanos(80));diagnostics.poll();
            require(reports.size()==2,"One follow-up per minute during a persistent stall");
            queue.remove().run();diagnostics.poll();queue.remove().run();
            require(reports.size()==2,"Recovery is silent and probes resume");
            alive.set(false);diagnostics.poll();
            require(queue.isEmpty(),"Stopped worlds retire without queuing work");
            alive.set(true);diagnostics.observe("test",executor,alive::get);diagnostics.poll();
            diagnostics.forget("test");clock.addAndGet(TimeUnit.MINUTES.toNanos(2));diagnostics.poll();
            require(reports.size()==2,"Removed worlds do not emit stale diagnostics");
        }
        verifyLockOwner();
        System.out.println("PASS: stalled-world diagnostics remain bounded, capture actual stacks and lock owners, and retire after recovery/removal.");
    }
    private static void verifyLockOwner() throws Exception {
        Object lock=new Object();CountDownLatch started=new CountDownLatch(1);
        Thread waiter=new Thread(()->{started.countDown();synchronized(lock){ /* Released by the fixture. */ }},"SM diagnostic blocked fixture");
        waiter.setDaemon(true);
        synchronized(lock) {
            waiter.start();require(started.await(2,TimeUnit.SECONDS),"Waiter started");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(waiter.getState()!=Thread.State.BLOCKED&&System.nanoTime()<deadline)Thread.onSpinWait();
            require(waiter.getState()==Thread.State.BLOCKED,"Real monitor contention established");
            String snapshot=WorldStallDiagnostics.threadSnapshot(waiter.threadId());
            require(snapshot.contains("SM diagnostic blocked fixture")&&snapshot.contains(" owned by "+Thread.currentThread().getName()),"Waiting thread names its actual monitor owner");
            require(snapshot.contains("id="+Thread.currentThread().threadId()),"Owner chain includes the owning thread's stack");
        }
        waiter.join(2000);require(!waiter.isAlive(),"Fixture releases its blocked thread");
    }
    private static void require(boolean condition,String description){if(!condition)throw new AssertionError(description);}
}
