package com.manas.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Generates a synthetic workload: jobsPerUser jobs for each of userCount users. Every user is
 * given the exact same multiset of job durations (70% sub-minute 10-55s, 30% longer 90-900s),
 * only shuffled into a different per-user order, so every user's total demand is identical by
 * construction and the ideal max-min fair share is exactly 1/userCount for all of them. Arrivals
 * are spaced so the aggregate arrival rate sits close to the worker pool's total service rate
 * (see README Findings: the first version arrived far faster than 4 workers could ever drain,
 * which only measures an unboundedly growing backlog, not steady-state fairness).
 */
public final class JobWorkload {

    public static List<Job> generate(int userCount, int jobsPerUser, int workerCount, long seed) {
        Random durationRandom = new Random(seed);
        List<Double> sharedDurations = new ArrayList<>(jobsPerUser);
        double totalDuration = 0;
        for (int j = 0; j < jobsPerUser; j++) {
            boolean subMinute = durationRandom.nextDouble() < 0.7;
            double duration = subMinute ? (10 + durationRandom.nextDouble() * 45) : (90 + durationRandom.nextDouble() * 810);
            sharedDurations.add(duration);
            totalDuration += duration;
        }

        // Each user's long-run fair share of the worker pool is workerCount/userCount workers'
        // worth of continuous service (every user has equal demand, so equal share). Pacing each
        // user's own arrivals to exactly saturate that fair share, rather than the whole pool,
        // keeps the system realistically contended without an unboundedly growing backlog (the
        // first version of this spacing paced against the whole pool while ignoring that all
        // userCount users arrive concurrently, overloading it by a factor of userCount; see
        // README Findings).
        double fairShareWorkers = (double) workerCount / userCount;
        double ticksToClearOneUsersDemandAtFairShare = totalDuration / fairShareWorkers;
        double avgInterArrival = ticksToClearOneUsersDemandAtFairShare / jobsPerUser;

        // Submission ticks are the same schedule for every user (only the per-position job
        // content is independently shuffled per user): this isolates the scheduler's fairness as
        // the only variable behind any measured deviation. The first version gave each user an
        // independently randomized arrival schedule, which meant a fairness measurement taken
        // mid-run was really measuring which users happened to have submitted more cumulative
        // demand by chance, not how fairly the scheduler divided what was actually contended for;
        // see README Findings.
        long[] sharedArrivalTicks = new long[jobsPerUser];
        double t = 0;
        Random jitterRandom = new Random(seed * 13);
        for (int j = 0; j < jobsPerUser; j++) {
            sharedArrivalTicks[j] = Math.round(t);
            t += avgInterArrival * (0.5 + jitterRandom.nextDouble());
        }

        List<Job> jobs = new ArrayList<>(userCount * jobsPerUser);
        long id = 0;
        for (int user = 0; user < userCount; user++) {
            List<Double> durations = new ArrayList<>(sharedDurations);
            Collections.shuffle(durations, new Random(seed * 31 + user));
            Random metaRandom = new Random(seed * 97 + user);
            for (int j = 0; j < jobsPerUser; j++) {
                String vintage = "vintage-" + (1000 + metaRandom.nextInt(50));
                String digest = "sha256:" + Long.toHexString(metaRandom.nextLong() & Long.MAX_VALUE);
                jobs.add(new Job(id++, user, vintage, digest, durations.get(j), sharedArrivalTicks[j]));
            }
        }
        jobs.sort((a, b) -> Long.compare(a.submitTick, b.submitTick));
        return jobs;
    }
}
