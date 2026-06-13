# BEAM Multi-Node Routing Status

Last updated: March 31, 2026

## Objective

This document tracks the current state of the BEAM multi-node routing rollout.

The V1 goal is intentionally narrow:

1. make distributed routing work reliably on Lawrencium
2. keep the master as the sole owner of simulation state
3. support one master plus `N` worker JVMs
4. support multiple routing workers per worker JVM
5. stop after a correct, repeatable production smoke path exists

This is not a plan for a fully distributed BEAM simulation.

## Current Status

The distributed-routing path is now functionally implemented and locally validated.

What is complete:

- cluster startup flags are the authoritative startup contract
- `--use-local-worker=false` is valid for clustered master startup
- worker-only startup boots only the worker actor tree
- remote routing requests fail explicitly on worker loss and timeout
- remote travel-time updates now return visible success or failure
- worker output/log directories are discoverable
- `beam.cluster.routingWorkersPerNode` is configurable
- local worker hosting is now per worker node, not via a singleton-hosted shared pool
- the master waits for expected compute nodes and for remote worker readiness before starting simulation
- a Lawrencium multi-node launcher exists and supports a baked-image flow

What is not yet complete:

- a clean, fully observed Lawrencium end-to-end acceptance run
- production-scale tuning for routing workers per node
- throughput and memory benchmarking

## Architecture Boundary

Distributed today:

- routing workers only

Still single-node today:

- scheduler
- population and household state
- ridehail state ownership
- parking and charging ownership
- skims aggregation
- overall simulation coordination

The correct framing is still:

- Project A: distributed routing workers
- Project B: distributed simulation-state sharding

Project A is the current deliverable. Project B remains deferred.

## Implemented Changes

### Startup Contract

The following startup flags are now the effective contract:

- `--cluster-type`
- `--node-host`
- `--node-port`
- `--seed-address`
- `--use-local-worker`

Master behavior:

- `cluster-type=master`
- may be launched with `--use-local-worker false`
- waits for the expected number of compute members before simulation startup
- waits for remote worker readiness before simulation startup

Worker behavior:

- `cluster-type=worker`
- boots only the worker actor system and worker router path
- does not start a local master simulation path

### Routing Failure Semantics

The remote routing path in [BeamRouter.scala](/Users/zaneedell/git/beam/src/main/scala/beam/router/BeamRouter.scala) now behaves explicitly rather than best-effort.

Implemented behavior:

- outstanding remote work is tracked sufficiently to fail explicitly
- worker loss fails the request rather than silently dropping it
- worker timeout fails the request rather than silently dropping it
- remote travel-time updates produce visible per-worker success or failure

### Worker Hosting Model

The earlier singleton/proxy design was not sufficient for reliable per-node routee placement.

Current model:

- each worker JVM starts its own local [ClusterWorkerRouter.scala](/Users/zaneedell/git/beam/src/main/scala/beam/router/ClusterWorkerRouter.scala)
- each worker JVM hosts its own local `workerRouter`
- `beam.cluster.routingWorkersPerNode` controls the local routee count per worker JVM
- worker readiness is checked locally on each compute node

This change was necessary because the singleton-based pool kept placing routees on whichever node became available first.

### Worker Logging And Output Discoverability

Cluster worker output is now easier to inspect:

- the worker prints its output directory at startup
- a stable pointer file is written under the base output directory
- worker output can be located without guessing the random suffix

This was added because the worker path does not share the same output directory semantics as a normal single-process BEAM run.

### Lawrencium Launcher

The launcher in [run_cluster_job.sh](/Users/zaneedell/git/beam/lawrencium/run_cluster_job.sh) now supports:

- one Slurm job with multiple nodes
- one master plus one worker process per remaining node
- fixed Akka port assignment
- seed address derived from the master node
- generated master and worker config overlays
- baked-image mode for fully containerized runs
- per-role log paths
- tracking via a persisted Slurm job id file

The container entrypoint in [execute-beam-automatically.sh](/Users/zaneedell/git/beam/docker/beam-environment/execute-beam-automatically.sh) was also updated so baked-image runs can pass:

- `BEAM_APP_ARGS`
- an absolute `BEAM_CONFIG` path inside the container

## Config Surface

The new important config knob is:

```hocon
beam.cluster.routingWorkersPerNode = 1
```

This controls the number of `RoutingWorker` routees hosted by each worker JVM.

Important nuance:

- one worker JVM is still one Lawrencium process
- increasing `routingWorkersPerNode` increases routees inside that JVM
- this is not free and can oversubscribe CPU or memory if set too high

For initial HPC smoke runs, a low value such as `2` is still the right starting point.

## Local Validation

### Automated Tests

The following targeted tests passed after the current refactor:

- `./gradlew specificTest -Psuite=beam.sim.BeamHelperSpec`
- `./gradlew specificTest -Psuite=beam.router.ClusterWorkerRouterSpec`
- `./gradlew specificTest -Psuite=beam.router.RoutingClusterSpec`
- `git diff --check`

### Manual Local Smoke

The local clustered smoke path has been validated on `sf-light-5k`.

Successful local topology:

- `1` master JVM
- `2` worker JVMs
- `2` routing workers per worker JVM
- total of `4` distributed routing workers

Observed success criteria:

- master waited for expected compute members
- master waited for remote worker readiness before simulation startup
- both worker JVMs produced `beamLog.out`
- both worker JVMs hosted two routing worker routees
- the local run distributed routees across both worker nodes rather than collapsing onto one

Representative successful local outputs:

- master: [sf-light-5k__2026-03-31_15-33-48_frn](/Users/zaneedell/git/beam/output/sf-light-cluster-local/sf-light-5k__2026-03-31_15-33-48_frn)
- worker `25521`: [sf-light-5k__2026-03-31_15-34-01_aqx](/Users/zaneedell/git/beam/output/sf-light-cluster-local/sf-light-5k__2026-03-31_15-34-01_aqx)
- worker `25522`: [sf-light-5k__2026-03-31_15-34-07_nvq](/Users/zaneedell/git/beam/output/sf-light-cluster-local/sf-light-5k__2026-03-31_15-34-07_nvq)

This is the first point at which the local architecture behaved as intended.

## Lawrencium Progress

### What Has Been Fixed

The Lawrencium path initially failed before BEAM startup for launcher reasons.

Issues already fixed:

- missing creation of the `master/` directory before writing `cluster-master.conf`
- stale submit-path defaults overwriting user-provided `BEAM_CONFIG`, `PULL_CODE`, `PULL_DATA`, and related settings
- lack of explicit `--export=ALL` for `sbatch`
- lack of an unambiguous mapping between run directory and Slurm job id

The launcher now writes:

- `cluster-log-file.log`
- `slurm-job-id.txt`

into each run directory.

### Current HPC Mode

The intended production-adjacent test mode is now:

- fully baked container image
- no host-side Gradle dependency
- no runtime code clone inside the container
- one master container process plus one worker container process per worker node

The current Docker image used for testing is:

- `docker://zaneedell/beam:1.2-multinode-v260305`

### Current HPC Run State

As of March 31, 2026, a fresh Lawrencium smoke run is in progress with:

- Slurm job id `21759258`
- run directory `/global/scratch/users/zaneedell/out_beam_2026.03.31-17.42.59.IN330LFY.lr5.lr_normal.60.cluster`
- walltime `01:30:00`

This run should be treated as the first properly traceable baked-image smoke run after the launcher fixes.

## Known Remaining Risks

### 1. HPC launcher observability is still thin

The launcher now has a correct job-id trail, but the Lawrencium path still needs one clean successful run before it can be considered stable.

### 2. HPC startup overhead can dominate short walltimes

The earlier `00:30:00` run timed out and was not a useful acceptance signal. Image pull, allocation, container startup, and initial BEAM startup are large enough that short walltimes can create misleading failures.

### 3. Worker-per-node tuning is not validated yet

Local correctness has been proven for `routingWorkersPerNode = 2`, but Lawrencium may want a different setting depending on node CPU and memory behavior.

### 4. No production benchmark data exists yet

There is still no throughput curve, queue-depth analysis, or master-memory-offload curve from the cluster path.

## Acceptance Criteria For V1

V1 should be considered complete when all of the following are true:

- local `1 master + 2 workers` smoke passes repeatably
- Lawrencium `1 master + 1 worker` smoke passes
- Lawrencium `1 master + 2 workers` smoke passes
- master startup waits correctly for compute members and remote worker readiness
- both worker nodes host routees in the `1 master + 2 workers` Lawrencium run
- no silent routing request loss occurs
- worker loss and timeout remain explicit failures
- the launcher and runbook are good enough that another engineer can repeat the run

## Immediate Next Steps

### 1. Finish the active Lawrencium smoke

For job `21759258`, confirm:

- Slurm moves from `PENDING` to `RUNNING`
- `cluster-log-file.log` becomes non-empty
- `logs/master-*.log` and `logs/worker-*.log` appear
- the master log shows compute members reaching `Up`
- the master log shows remote workers ready before simulation startup

### 2. Inspect per-node placement on Lawrencium

The critical thing to validate is not just cluster formation, but routee placement:

- each worker node should produce its own `beamLog.out`
- each worker node should show local routing workers becoming ready

### 3. Run the two acceptance smokes

The intended sequence is:

1. `1 master + 1 worker`, `routingWorkersPerNode = 2`
2. `1 master + 2 workers`, `routingWorkersPerNode = 2`

Only after both pass should any benchmark work begin.

### 4. Write down the winning Lawrencium invocation

Once one run succeeds, record:

- exact image tag
- exact fish exports used
- walltime
- node count
- config path
- how to locate logs and outputs

That should become the short runbook for the next engineer.

## Deferred Follow-Ups

These are intentionally out of scope for V1:

- retry or requeue semantics for failed routing work
- throughput benchmark matrix
- memory benchmark matrix
- worker memory instrumentation
- multiple worker JVMs per node
- distributed scheduler or simulation-state sharding

## Recommended Working Rule

Do not expand scope until the baked-image Lawrencium path is clean.

The correct order remains:

1. correctness
2. repeatability
3. observability
4. scaling
5. broader architecture changes
