package com.hexvane.strangematter.telemetry;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** Runs without any Beacon dependency, including when another fixture adds Beacon to its classpath. */
public final class BeaconVerification {
    public static void main(String[] args) {
        ClassLoader absent = new ClassLoader(BeaconVerification.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.alechilles.beacon.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        AtomicLong now = new AtomicLong();
        try (BeaconTelemetry bridge = new BeaconTelemetry(absent, now::get, Runnable::run)) {
            bridge.tick();
            check(!bridge.isActive() && bridge.status() == BeaconTelemetry.Status.ABSENT, "Absent Beacon is optional");
            for (int i = 0; i < 100_000; i++) {
                bridge.usage(BeaconTelemetry.Feature.SCAN);
                bridge.performance(BeaconTelemetry.Subsystem.ANOMALIES, 1_000);
                bridge.tick();
            }
            now.addAndGet(BeaconTelemetry.INTERVAL_NANOS);
            bridge.tick();
            check(!bridge.isActive(), "Repeated calls without Beacon remain harmless");
        }
        ArrayList<Runnable> jobs = new ArrayList<>();
        BeaconTelemetry pending = new BeaconTelemetry(absent, now::get, jobs::add);
        pending.tick();
        for (int i = 0; i < 100; i++) {
            now.addAndGet(BeaconTelemetry.INTERVAL_NANOS);
            pending.tick();
        }
        check(jobs.size() == 1, "Busy worker never creates an unbounded telemetry backlog");
        pending.close();
        jobs.getFirst().run();
        pending.usage(BeaconTelemetry.Feature.SCAN);
        pending.tick();
        check(pending.status() == BeaconTelemetry.Status.CLOSED && jobs.size() == 1, "Late task cannot reactivate a closed bridge");
        System.out.println("BEACON_ABSENT_VERIFICATION_PASSED: no dependency, inactive hot path, bounded worker and shutdown");
    }
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
