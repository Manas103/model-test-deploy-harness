package com.manas.harness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tick-based (1-second resolution) max-min fair-share simulation across a fixed number of
 * worker slots. At every tick, each free worker is handed the waiting job belonging to whichever
 * user has received the least service so far (ties broken by lower userId), which is the
 * standard "always serve the least-served queue" approximation of max-min fairness for
 * divisible, continuously-arriving demand. When preemptionEnabled, a long-running job already in
 * progress can be paused (not restarted: its remaining time is preserved and it resumes later
 * from where it left off) to let a newly-eligible sub-minute job from a far less-served user run
 * immediately, which is what the p95-queue-wait-for-sub-minute-jobs claim measures.
 */
public final class FairShareScheduler {

    public SimulationResult simulate(List<Job> jobs, int workerCount, boolean preemptionEnabled) {
        Map<Integer, Deque<WorkItem>> queues = new HashMap<>();
        Map<Integer, Double> serviceReceived = new HashMap<>();
        for (Job job : jobs) {
            queues.computeIfAbsent(job.userId, k -> new ArrayDeque<>());
            serviceReceived.putIfAbsent(job.userId, 0.0);
        }

        Job[] runningJob = new Job[workerCount];
        WorkItem[] runningItem = new WorkItem[workerCount];
        List<JobResult> results = new ArrayList<>(jobs.size());
        Map<Long, Boolean> everPreempted = new HashMap<>();
        List<Map<Integer, Double>> snapshots = new ArrayList<>();
        long lastSubmitTick = jobs.isEmpty() ? 0 : jobs.get(jobs.size() - 1).submitTick;
        long snapshotInterval = Math.max(1, lastSubmitTick / 200);

        int nextArrivalIndex = 0;
        long tick = 0;
        int completed = 0;

        while (completed < jobs.size()) {
            // Admit every job whose submitTick has arrived by now.
            while (nextArrivalIndex < jobs.size() && jobs.get(nextArrivalIndex).submitTick <= tick) {
                Job job = jobs.get(nextArrivalIndex);
                queues.get(job.userId).addLast(new WorkItem(job));
                nextArrivalIndex++;
            }

            // Run one second of work on every busy worker; free the ones that just finished.
            for (int w = 0; w < workerCount; w++) {
                if (runningItem[w] != null) {
                    runningItem[w].remainingSeconds -= 1;
                    serviceReceived.merge(runningJob[w].userId, 1.0, Double::sum);
                    if (runningItem[w].remainingSeconds <= 0) {
                        results.add(new JobResult(runningJob[w].id, runningJob[w].userId, runningJob[w].subMinute,
                                runningItem[w].firstDispatchTick - runningJob[w].submitTick, tick + 1,
                                everPreempted.getOrDefault(runningJob[w].id, false)));
                        completed++;
                        runningJob[w] = null;
                        runningItem[w] = null;
                    }
                }
            }

            if (preemptionEnabled) {
                for (int w = 0; w < workerCount; w++) {
                    if (runningItem[w] == null || runningJob[w].subMinute) {
                        continue; // nothing running, or already a short job: nothing to preempt
                    }
                    Integer candidateUser = leastServedUserWithWaitingSubMinuteJob(queues, serviceReceived);
                    if (candidateUser == null) {
                        continue;
                    }
                    // Any waiting sub-minute job preempts any running long job: the entire point
                    // of this gate is that a backtest job's size should not decide how long a
                    // short job waits behind it. An earlier version only preempted when the
                    // waiting user's service level was far enough below the running job's
                    // user, which meant the vast majority of long-running jobs were never
                    // preempted (the fair-share dispatch already keeps service levels close by
                    // design, so that gate was almost never satisfied), and queue-wait p95 for
                    // sub-minute jobs was statistically unaffected; see README Findings.

                    // Pause the long job: put it back at the front of its own queue, unstarted-work preserved.
                    queues.get(runningJob[w].userId).addFirst(runningItem[w]);
                    everPreempted.put(runningJob[w].id, true);
                    // Dispatch the sub-minute job that justified the preemption immediately, onto
                    // this same now-free worker: leaving this to the generic least-served fill
                    // step below would let an even-less-served user's own (possibly
                    // non-sub-minute) job take the slot instead, silently defeating the
                    // preemption's purpose. This was the first bug found here: preemptions fired
                    // (counted, logged) but never actually changed any sub-minute job's queue
                    // wait; see README Findings.
                    WorkItem preempting = queues.get(candidateUser).pollFirst();
                    if (!preempting.hasStarted()) {
                        preempting.firstDispatchTick = tick + 1;
                    }
                    runningJob[w] = preempting.job;
                    runningItem[w] = preempting;
                }
            }

            // Fill every free worker with the least-served user's next waiting item.
            for (int w = 0; w < workerCount; w++) {
                if (runningItem[w] != null) {
                    continue;
                }
                Integer user = leastServedUserWithAnyWaitingJob(queues, serviceReceived);
                if (user == null) {
                    continue;
                }
                WorkItem item = queues.get(user).pollFirst();
                if (!item.hasStarted()) {
                    item.firstDispatchTick = tick + 1;
                }
                runningJob[w] = item.job;
                runningItem[w] = item;
            }

            if (tick % snapshotInterval == 0) {
                snapshots.add(new HashMap<>(serviceReceived));
            }

            tick++;
        }

        return new SimulationResult(results, serviceReceived, tick, snapshots);
    }

    private Integer leastServedUserWithAnyWaitingJob(Map<Integer, Deque<WorkItem>> queues, Map<Integer, Double> serviceReceived) {
        Integer best = null;
        double bestService = Double.MAX_VALUE;
        for (Map.Entry<Integer, Deque<WorkItem>> e : queues.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            double service = serviceReceived.get(e.getKey());
            if (service < bestService || (service == bestService && (best == null || e.getKey() < best))) {
                best = e.getKey();
                bestService = service;
            }
        }
        return best;
    }

    private Integer leastServedUserWithWaitingSubMinuteJob(Map<Integer, Deque<WorkItem>> queues, Map<Integer, Double> serviceReceived) {
        Integer best = null;
        double bestService = Double.MAX_VALUE;
        for (Map.Entry<Integer, Deque<WorkItem>> e : queues.entrySet()) {
            Deque<WorkItem> q = e.getValue();
            if (q.isEmpty() || !q.peekFirst().job.subMinute) {
                continue;
            }
            double service = serviceReceived.get(e.getKey());
            if (service < bestService || (service == bestService && (best == null || e.getKey() < best))) {
                best = e.getKey();
                bestService = service;
            }
        }
        return best;
    }
}
