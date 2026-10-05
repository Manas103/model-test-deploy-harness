package com.manas.harness;

/**
 * One simulated backtest job: a user's submission, pinned to a data vintage and a container
 * digest, with a synthetic duration. subMinute is fixed at creation (durationSeconds < 60) since
 * the fair-share and preemption measurements both split on it.
 */
public final class Job {
    public final long id;
    public final int userId;
    public final String dataVintage;
    public final String containerDigest;
    public final double durationSeconds;
    public final long submitTick;
    public final boolean subMinute;

    public Job(long id, int userId, String dataVintage, String containerDigest, double durationSeconds, long submitTick) {
        this.id = id;
        this.userId = userId;
        this.dataVintage = dataVintage;
        this.containerDigest = containerDigest;
        this.durationSeconds = durationSeconds;
        this.submitTick = submitTick;
        this.subMinute = durationSeconds < 60.0;
    }
}
