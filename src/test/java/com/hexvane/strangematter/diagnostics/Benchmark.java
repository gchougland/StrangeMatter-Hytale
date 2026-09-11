package com.hexvane.strangematter.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.function.LongSupplier;

/** Comparative in-process benchmarks. No timing work is added to the production plugin. */
public final class Benchmark {
    private static volatile long sink;
    public record Result(String name, String operation, int operationsPerSample, int warmupOperations,
                         double p50Micros, double p95Micros, double p99Micros, double maximumMicros,
                         Double cpuMicrosPerOperation, Double allocatedBytesPerOperation,
                         List<Double> samplesMicros) {}
    public static Result measure(String name, String operation, int batch, LongSupplier work) {
        // Minimum duration and count warm up different sized workloads; setup is outside timing.
        long until=System.nanoTime()+500_000_000L;int warmup=0;
        do { sink=work.getAsLong(); warmup++; } while(warmup<200 || System.nanoTime()<until);
        var bean=ManagementFactory.getThreadMXBean();
        if(bean.isCurrentThreadCpuTimeSupported()&&!bean.isThreadCpuTimeEnabled())bean.setThreadCpuTimeEnabled(true);
        var allocations=bean instanceof com.sun.management.ThreadMXBean b&&b.isThreadAllocatedMemorySupported()?b:null;
        if(allocations!=null&&!allocations.isThreadAllocatedMemoryEnabled())allocations.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId(),cpu=0,bytes=0;var samples=new ArrayList<Double>();
        for(int sample=0;sample<400;sample++){
            long a=allocations==null?0:allocations.getThreadAllocatedBytes(thread);
            long c=bean.isCurrentThreadCpuTimeSupported()?bean.getCurrentThreadCpuTime():0;
            long start=System.nanoTime(),value=0;
            for(int i=0;i<batch;i++)value+=work.getAsLong();
            long elapsed=System.nanoTime()-start;
            if(bean.isCurrentThreadCpuTimeSupported())cpu+=bean.getCurrentThreadCpuTime()-c;
            if(allocations!=null)bytes+=allocations.getThreadAllocatedBytes(thread)-a;
            sink=value;samples.add(elapsed/1000d/batch);
        }
        var sorted=new ArrayList<>(samples);Collections.sort(sorted);double operations=(double)batch*samples.size();
        var result=new Result(name,operation,batch,warmup,percentile(sorted,.5),percentile(sorted,.95),percentile(sorted,.99),sorted.getLast(),
            bean.isCurrentThreadCpuTimeSupported()?cpu/1000d/operations:null,allocations==null?null:bytes/operations,List.copyOf(samples));
        System.out.printf(Locale.ROOT,"BENCHMARK %s p50=%.2f us p95=%.2f us p99=%.2f us%n",name,result.p50Micros,result.p95Micros,result.p99Micros);
        return result;
    }
    private static double percentile(List<Double> sorted,double rank){return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*rank)-1));}
    private Benchmark() {}
}
