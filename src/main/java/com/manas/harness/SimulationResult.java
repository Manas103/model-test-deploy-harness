package com.manas.harness;

import java.util.List;
import java.util.Map;

/** serviceSnapshots is a periodic time series of per-user cumulative service received, for measuring fairness during steady state rather than only once every job has finished. */
public record SimulationResult(List<JobResult> jobResults, Map<Integer, Double> serviceReceivedByUser,
                                double totalTicks, List<Map<Integer, Double>> serviceSnapshots) {
}
