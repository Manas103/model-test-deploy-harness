# Model Test-and-Deploy Harness with Fair-Share Scheduling

A small version of the environment that runs simulated backtests: a max-min fair-share scheduler across multiple users sharing a fixed worker pool, an optional preemption gate that lets short jobs cut in front of long ones, and a byte-identical re-run promotion gate demonstrated against three real, seeded nondeterminism bugs in a real Docker container. Java 21 (the scheduler simulation), Python in a real Docker image (the reproducibility gate). Every number below was measured by running the code in this repository on this machine, not targeted in advance.

## Why this exists

A shared research pool that runs quantitative backtests has two separate problems that are easy to conflate: dividing a fixed worker pool fairly across competing users, and proving that a promoted model actually behaves the same way twice before anyone trusts its output. This repository keeps them as two independently testable pieces: a scheduling simulation (`FairShareScheduler`) and a reproducibility gate demonstrated against real containers (`docker/`), connected only by the idea that both exist to make a shared backtest environment trustworthy.

## Honest framing, up front

- **The scheduler is a discrete-event simulation, not a live job runner.** 2,400 jobs, 8 users and a 4-worker pool are simulated at 1-second tick resolution in-process; no real container is launched for any of the fair-share or preemption numbers below. This is the same simplification this portfolio already uses for other scheduling and allocation measurements (`criticality-minmax-policy-engine`, `expert-role-matching-allocation`).
- **The container-digest pinning and the byte-identical re-run gate are real, not simulated.** `docker/Dockerfile` builds a real `python:3.12-slim`-based image; every run in `docs/repro_gate_output.txt` is pinned to that image's actual `docker inspect` digest and executed with real `docker run`, not mocked.
- **This machine's Docker Desktop is not configured for the Windows-side `docker` CLI.** The demonstration was run inside WSL2 Ubuntu 22.04, where the Docker Engine is installed and running natively; the exact command is in Building and running.
- **Both headline numbers came up short of what this project set out to show, and are reported as measured.** See Measured results and Findings: fair-share deviation and the preemption speedup are both real, debugged, honestly-measured results that do not reach the scale this design aimed for, after the maximum genuine attempts allowed.
- **Machine.** AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home (the Java simulation) with WSL2 Ubuntu 22.04 (the Docker demonstration), JDK 21 Temurin, Python 3.12 (inside the container only).

## Architecture

```
src/main/java/com/manas/harness/
  Job.java                 one simulated job: user, data vintage, container digest, duration, submit tick
  WorkItem.java             a job's live execution state in the scheduler (remaining time, first-dispatch tick)
  JobResult.java            one job's outcome: queue wait, completion tick, whether it was ever preempted
  SimulationResult.java     all job outcomes, final per-user service totals, and a periodic fairness time series
  JobWorkload.java          generates the 2,400-job, 8-user, equal-demand synthetic workload
  FairShareScheduler.java   the tick-based least-served-first dispatcher, with optional preemption
docker/
  job_runner.py             6 modes: 3 deliberately nondeterministic, 3 fixed, one Python process
  Dockerfile                python:3.12-slim, one real image
  run_repro_gate.sh         builds the image, pins its real digest, runs all 6 modes twice, diffs sha256(stdout)
src/test/java/com/manas/harness/
  FairShareSchedulerCorrectnessTest.java   small, hand-checkable fairness and preemption scenarios
  FairShareSchedulerBenchmarkTest.java     the 2,400-job benchmark, writes docs/scheduler_benchmark_output.txt
```

**Why "least-served-first" rather than a weighted round robin.** At every tick, every free worker is handed the waiting job belonging to whichever user has received the least cumulative service so far. This is the standard greedy approximation of max-min fairness for continuously-arriving, divisible demand: a user who has fallen behind is always next in line, and a user who is already ahead waits, with no separate accounting structure needed beyond one running total per user.

**Why preemption dispatches directly rather than just freeing the worker.** The first version of the preemption branch paused the long job and left the freed worker to the same generic least-served-first fill step used for ordinary dispatch. Because that step picks the least-served user with *any* waiting job, not specifically the sub-minute job that justified the preemption, an even-less-served user's own long job could take the slot instead, silently defeating the preemption's purpose. See Findings for how this was caught and fixed.

**Why fairness is measured from periodic snapshots, not the final totals.** Every user in this workload is given identical total demand by construction, so once the whole backlog drains, every user's total received service trivially equals their total demand and the deviation is 0% for any scheduler, fair or not. `SimulationResult.serviceSnapshots()` is a periodic time series of cumulative per-user service; fairness is measured from the middle 60% of that series (steady state, excluding ramp-up and drain), which is the only part of the run where the scheduler's choices are actually visible in the numbers.

## Validation

4 JUnit tests passing (`docs/test_output.txt`):

```
Tests run: 3, ... -- FairShareSchedulerCorrectnessTest
Tests run: 1, ... -- FairShareSchedulerBenchmarkTest
```

`FairShareSchedulerCorrectnessTest` covers: two users with identical continuous demand end up with nearly equal total service on a single worker; a user who arrives later with a backlog is served up to parity rather than ignored in favor of the incumbent; and, directly, that preemption lets a newly-arrived sub-minute job from a zero-service user start almost immediately against a 600-second job already running, while without preemption that same job waits for the full 600 seconds.

`FairShareSchedulerBenchmarkTest` is the 2,400-job, 8-user scale claim, covered in Measured results.

The reproducibility gate is validated directly by `docker/run_repro_gate.sh`'s own output (`docs/repro_gate_output.txt`): every one of the 3 buggy modes produces a different sha256 across two runs of the same pinned image, and every one of the 3 fixed modes produces the identical sha256, which is the gate's pass/fail signal exactly as the harness would compute it.

## Findings

**What broke: preemption fired 31-44 times but never once changed a sub-minute job's queue wait.** The first preemption design only paused a running long job when the waiting user's cumulative service was more than 1.5x below the running user's, and left the now-free worker to the ordinary least-served-first fill step. Measured p95 sub-minute queue wait was bit-for-bit identical (`7521.0` vs `7521.0`) between the preemption-enabled and preemption-disabled runs, which was the tell: a real effect, even a small one, does not reproduce to the exact same double across two different scheduling policies. The root cause was the fill step re-selecting by least-served-overall rather than specifically dispatching the sub-minute job that justified the preemption; an even-less-served user's own (possibly long) job could and did take the freed slot instead. The fix makes the preempting job's dispatch explicit and immediate rather than routing it back through the generic fill step, and loosens the trigger to "any waiting sub-minute job preempts any running long job" (the 1.5x service-ratio gate was almost never satisfied in the first place, since least-served-first dispatch already keeps user service levels close together by design, which is why preemption barely ever fired under it). After the fix, preemption actually dispatches short jobs ahead of long ones (657 preemptions, a real change in p95 wait), though the resulting improvement is far short of this design's original target; see Measured results.

**What also broke: the first fairness measurement was a trivial 0.00% for every run, including the deliberately unfair one.** Measuring fairness from final per-user totals after every job has completed is measuring the workload's construction (equal demand by design), not the scheduler: that number is 0% for any scheduling policy whatsoever, by definition. The fix measures fairness from periodic snapshots taken during the run instead, which is the only window in which a scheduler's choices are visible at all.

## Measured results

Machine: AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home, JDK 21 Temurin (scheduler); WSL2 Ubuntu 22.04, Docker Engine, Python 3.12-slim in-container (reproducibility gate). Run: `mvn test` for the scheduler, `bash docker/run_repro_gate.sh` inside WSL2 for the gate.

| Claim | Measured | Meets claim |
|---|---|---|
| Every user within 4% of ideal max-min fair share over 2,000+ jobs | 21.3% max deviation during steady state with preemption enabled (21.99% without); 3 genuine attempts made (equal-demand workload construction, synchronized arrival ticks, direct-dispatch preemption), see Findings | no |
| Preemption cuts p95 queue wait for sub-minute jobs 17x | 1.2x (7521.0s to 6370.0s), after fixing the dispatch bug in Findings; 657 of ~1,680 sub-minute jobs' competing long jobs were genuinely preempted | no |
| Gate catches 3 seeded nondeterminism bugs (unordered iteration, unseeded RNG, wall-clock reads) | 3 of 3: unordered-set iteration (PYTHONHASHSEED-dependent), unseeded `random.seed()`, and `time.time()` all produced two different sha256 hashes across two runs of the same pinned image; all 3 corresponding fixed versions (sorted iteration, a vintage-derived seed, no wall-clock read) were byte-identical across two runs | yes |
| Jobs pinned to a data vintage and a container digest | every job carries a `dataVintage` string; the Docker demonstration pins every run to the real `docker inspect` digest `sha256:c29bba2b...` of the image built from `docker/Dockerfile` | yes |

The honest reading of the two misses: this scheduler's own fair-share dispatch is already close to fair by construction (least-served-first), which is exactly why the original 1.5x-service-ratio preemption trigger almost never fired, and why the steady-state deviation, while real, is driven by job-duration granularity (jobs up to 900 seconds against only 4 workers and 8 users) rather than by any bias in the dispatch rule itself. A finer-grained scheduler (more workers, or jobs capped well below the inter-arrival interval) would show both numbers close much faster than this one does; that redesign was out of scope once the 3-attempt cap was reached.

## Building and running

```
mvn test                              # 4 tests: scheduler correctness + the 2,400-job benchmark, ~1s
```

```bash
# Docker reproducibility gate (run inside WSL2; Docker Desktop is not configured for the Windows
# docker CLI on this machine):
wsl.exe -d Ubuntu-22.04 -- bash -lc 'cd "/mnt/c/path/to/model-test-deploy-harness/docker" && bash run_repro_gate.sh'
```

## Limitations

The scheduler is a logical-time simulation; no real container is ever launched for the fair-share or preemption numbers, only for the 6 reproducibility-gate modes. The preemption trigger preempts any long job for any waiting sub-minute job unconditionally, with no cap on how many times a single long job can be preempted in a row; a production version would want a starvation bound on the long job's own total wait. The byte-identical re-run gate here is a direct sha256 comparison of stdout for a single-process job; a real backtest's promotion gate would need to define reproducibility over a structured output artifact, not raw stdout text. No real container orchestration (Kubernetes, a job queue service) is exercised; `FairShareScheduler` is a pure in-process algorithm.
