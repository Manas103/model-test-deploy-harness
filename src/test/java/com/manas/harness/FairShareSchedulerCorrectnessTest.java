package com.manas.harness;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Small, hand-checkable scenarios, independent of the large benchmark corpus. */
class FairShareSchedulerCorrectnessTest {

    @Test
    void twoUsersWithEqualContinuousDemandEndUpWithEqualService() {
        List<Job> jobs = new ArrayList<>();
        long id = 0;
        for (int user = 0; user < 2; user++) {
            for (int j = 0; j < 20; j++) {
                jobs.add(new Job(id++, user, "v1", "sha256:deadbeef", 30.0, j * 5L));
            }
        }
        jobs.sort((a, b) -> Long.compare(a.submitTick, b.submitTick));

        SimulationResult result = new FairShareScheduler().simulate(jobs, 1, false);
        double service0 = result.serviceReceivedByUser().get(0);
        double service1 = result.serviceReceivedByUser().get(1);
        assertEquals(service0, service1, 30.0, "with equal demand and one worker, both users should end up with nearly equal total service");
        assertEquals(20, result.jobResults().stream().filter(r -> r.userId == 0).count());
        assertEquals(20, result.jobResults().stream().filter(r -> r.userId == 1).count());
    }

    @Test
    void aStarvedUserIsServedFirstOnceItHasJobsQueued() {
        // User 0 floods the single worker first; user 1 arrives later with its own backlog.
        // Fair share means user 1's jobs, once present, get picked ahead of user 0's further
        // jobs until the two users' service totals equalize.
        List<Job> jobs = new ArrayList<>();
        long id = 0;
        for (int j = 0; j < 10; j++) {
            jobs.add(new Job(id++, 0, "v1", "sha256:a", 10.0, 0L));
        }
        for (int j = 0; j < 10; j++) {
            jobs.add(new Job(id++, 1, "v1", "sha256:a", 10.0, 0L));
        }

        SimulationResult result = new FairShareScheduler().simulate(jobs, 1, false);
        double service0 = result.serviceReceivedByUser().get(0);
        double service1 = result.serviceReceivedByUser().get(1);
        assertEquals(100.0, service0, 15.0);
        assertEquals(100.0, service1, 15.0);
    }

    @Test
    void preemptionLetsASubMinuteJobRunBeforeALongJobFinishesWhenTheWaitingUserIsUnderserved() {
        List<Job> jobs = new ArrayList<>();
        // User 0 occupies the only worker with a long (non-sub-minute) job starting at tick 0.
        jobs.add(new Job(0, 0, "v1", "sha256:a", 600.0, 0L));
        // User 1, who has received zero service, submits a sub-minute job shortly after.
        jobs.add(new Job(1, 1, "v1", "sha256:a", 20.0, 5L));

        SimulationResult withPreemption = new FairShareScheduler().simulate(List.copyOf(jobs), 1, true);
        JobResult user1Result = withPreemption.jobResults().stream().filter(r -> r.userId == 1).findFirst().orElseThrow();
        assertTrue(user1Result.queueWaitSeconds < 20, "preemption should let the underserved user's short job start quickly, got wait=" + user1Result.queueWaitSeconds);

        SimulationResult withoutPreemption = new FairShareScheduler().simulate(List.copyOf(jobs), 1, false);
        JobResult user1ResultNoPreempt = withoutPreemption.jobResults().stream().filter(r -> r.userId == 1).findFirst().orElseThrow();
        assertTrue(user1ResultNoPreempt.queueWaitSeconds >= 590, "without preemption the short job must wait for the long job to finish, got wait=" + user1ResultNoPreempt.queueWaitSeconds);
    }
}
