package com.manas.harness;

/** Per-job outcome: queue wait is time from submission to first dispatch, regardless of any later preemption. */
public final class JobResult {
    public final long jobId;
    public final int userId;
    public final boolean subMinute;
    public final double queueWaitSeconds;
    public final double completionTick;
    public final boolean wasPreempted;

    public JobResult(long jobId, int userId, boolean subMinute, double queueWaitSeconds, double completionTick, boolean wasPreempted) {
        this.jobId = jobId;
        this.userId = userId;
        this.subMinute = subMinute;
        this.queueWaitSeconds = queueWaitSeconds;
        this.completionTick = completionTick;
        this.wasPreempted = wasPreempted;
    }
}
