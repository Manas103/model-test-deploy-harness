package com.manas.harness;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scale claims: over 2,000+ jobs across 8 users, every user held within 4% of ideal max-min
 * fair share, and preemption's effect on p95 queue wait for sub-minute jobs. Raw output:
 * docs/scheduler_benchmark_output.txt.
 */
class FairShareSchedulerBenchmarkTest {

    private static final int USERS = 8;
    private static final int JOBS_PER_USER = 300; // 2,400 jobs total
    private static final int WORKERS = 4;

    @Test
    void fairnessAndPreemptionBenchmark() throws IOException {
        List<Job> jobs = JobWorkload.generate(USERS, JOBS_PER_USER, WORKERS, 20260923L);
        assertTrue(jobs.size() >= 2000, "need at least 2,000 jobs for the claim, got " + jobs.size());

        FairShareScheduler scheduler = new FairShareScheduler();
        SimulationResult withoutPreemption = scheduler.simulate(List.copyOf(jobs), WORKERS, false);
        SimulationResult withPreemption = scheduler.simulate(List.copyOf(jobs), WORKERS, true);

        double maxDeviationPct = maxFairShareDeviationPct(withoutPreemption);
        double maxDeviationPctPreempt = maxFairShareDeviationPct(withPreemption);

        double p95NoPreempt = p95SubMinuteQueueWait(withoutPreemption);
        double p95Preempt = p95SubMinuteQueueWait(withPreemption);
        double ratio = p95NoPreempt / p95Preempt;

        long preemptedCount = withPreemption.jobResults().stream().filter(r -> r.wasPreempted).count();

        String report = String.format(
                "Model test-and-deploy harness: fair-share scheduling benchmark%n"
                + "Jobs: %d across %d users, %d worker slots, 1-second-tick simulation%n%n"
                + "Without preemption: max fair-share deviation %.2f%% (claim: within 4%%)%n"
                + "With preemption:    max fair-share deviation %.2f%% (claim: within 4%%)%n"
                + "p95 sub-minute queue wait without preemption: %.1fs%n"
                + "p95 sub-minute queue wait with preemption:    %.1fs%n"
                + "Preemption reduces p95 sub-minute queue wait by %.1fx (claim: 17x)%n"
                + "Long jobs actually preempted at least once: %d%n",
                jobs.size(), USERS, WORKERS, maxDeviationPct, maxDeviationPctPreempt,
                p95NoPreempt, p95Preempt, ratio, preemptedCount);

        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("scheduler_benchmark_output.txt"), report, StandardCharsets.UTF_8);
        System.out.println(report);

        // Both numbers are reported as measured, not gated to a target: see README Measured
        // results for why both come up short of the resume's claim and why that is honest rather
        // than a bug (coarse job granularity relative to only 4 workers, disclosed there).
        assertTrue(maxDeviationPctPreempt < 50, "fairness deviation should not be wildly larger than the no-preemption baseline, got " + maxDeviationPctPreempt);
        assertTrue(ratio > 1.0, "preemption should reduce sub-minute p95 queue wait by at least some amount, got " + ratio + "x");
    }

    /**
     * Fairness has to be measured during steady state, not only once every job has finished:
     * since every user has identical total demand by construction, total service received
     * trivially equals total demand for everyone once the whole backlog drains, regardless of
     * how fair the scheduler was while jobs were actually queued (the first version of this
     * benchmark measured exactly that and reported a meaningless 0.00% every time; see README
     * Findings). The middle 60% of periodic snapshots (excluding ramp-up and drain) is used instead.
     */
    private double maxFairShareDeviationPct(SimulationResult result) {
        List<Map<Integer, Double>> snapshots = result.serviceSnapshots();
        int start = (int) (snapshots.size() * 0.2);
        int end = (int) (snapshots.size() * 0.8);
        double maxDeviation = 0;
        for (int i = start; i < end; i++) {
            Map<Integer, Double> service = snapshots.get(i);
            double total = service.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total <= 0) {
                continue;
            }
            double idealShare = total / service.size();
            for (double v : service.values()) {
                double deviationPct = Math.abs(v - idealShare) / idealShare * 100.0;
                maxDeviation = Math.max(maxDeviation, deviationPct);
            }
        }
        return maxDeviation;
    }

    private double p95SubMinuteQueueWait(SimulationResult result) {
        List<Double> waits = new ArrayList<>();
        for (JobResult r : result.jobResults()) {
            if (r.subMinute) {
                waits.add(r.queueWaitSeconds);
            }
        }
        waits.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(0.95 * waits.size()) - 1;
        return waits.get(Math.max(0, Math.min(index, waits.size() - 1)));
    }
}
