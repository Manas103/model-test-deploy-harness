package com.manas.harness;

/** One job's execution state inside the scheduler: how much of it is left to run, and whether it has started at all yet. */
final class WorkItem {
    final Job job;
    double remainingSeconds;
    double firstDispatchTick = -1;

    WorkItem(Job job) {
        this.job = job;
        this.remainingSeconds = job.durationSeconds;
    }

    boolean hasStarted() {
        return firstDispatchTick >= 0;
    }
}
