# Bounded owned-fixture process lifecycle

This is test-only lifecycle hardening. It does not change the production plugin or authorize a server start. Offline implementation/testing used synthetic Python children only, plus mock-only error cases. It did not start Minecraft or a JVM, contact a network service, inspect an unrelated process, or signal a process recovered from a saved PID.

The interrupted historical runs remain incomplete. An unavailable observation channel, a missing process in a different execution namespace, or absent final logs cannot establish normal server shutdown. This implementation cannot retroactively establish termination of those runs.

## Ownership and parent loss

`server_harness.Server` retains both fixture/privacy checks before `java -version` and again before the server launch. The version check now has a 20-second subprocess timeout. `FixtureProcess` is constructed only after the second preflight and redacted privacy descriptor. Existing run/phase evidence is never overwritten.

The runner starts a dedicated Python supervisor with an absolute helper path and `-I -S -B`; the helper uses the standard library only. A bounded private stdin protocol supplies the launch description and later controls. The runner's control-pipe write end is not inherited by the child. No network control endpoint exists.

The supervisor owns one newly created `Popen` child. Only that live handle is used for TERM/KILL/wait; no PID from a journal or previous execution is used for signaling. Control EOF, malformed/stale controls, a runner-reported error, or supervisor TERM/INT become sticky failure reasons. Runner EOF first requests `stop`, then uses the bounded escalation below. Even a cooperative zero exit after parent loss is a failed/incomplete fixture result.

On Linux, the child first runs a separate single-threaded bootstrap. It checks the expected supervisor PID, arms `PR_SET_PDEATHSIG` with `SIGKILL`, checks the parent identity again, then execs the intended target. This is not `preexec_fn` inside the runner's multithreaded Python process. Set-user-ID/set-group-ID or file-capability executables are refused because Linux can clear the parent-death signal across privilege-changing exec. The same child PID becomes the JVM PID, preserving existing RSS measurements.

A close-on-exec error pipe reports explicit bootstrap/guard/exec errors. `armed` followed by EOF means no explicit error was received; it is not proof of Minecraft readiness. The existing `Done (` and plugin assertions remain necessary. A bootstrap killed between arming and exec cannot become a normal zero result.

If the supervisor itself dies abruptly on Linux, the kernel kills the direct guarded child. That mechanism cannot write a terminal journal after supervisor death. The facade returns unavailable/nonzero and, when still alive, writes a separate unavailable-evidence observation. A kernel-kill attempt or missing final state is never called a normal shutdown.

On other platforms the explicit fallback is `portable_supervisor_only`: runner-pipe EOF, graceful shutdown, deadline and owned-child escalation work while the supervisor remains alive. Abrupt destruction of that supervisor is not protected by the Linux kernel mechanism. Linux is the supported mode for fresh runs requiring supervisor-death protection. Portable fallback logic is covered by mock tests; native non-Linux execution has not been certified here.

## Fixed timing budget

- `GUARDIAN_FIXTURE_MAX_SECONDS` defaults to **3600**. Only decimal integers from **300 through 7200** are accepted by the server harness; invalid/absent-as-empty values fail before Java. There is no disable/unbounded option or runtime-extension command.
- Time uses `time.monotonic()`. The absolute deadline starts before the child launch and is not reset by bootstrap completion, output, commands, or repeated stop/TERM/KILL requests.
- A requested normal stop has at most **100 seconds**, clipped to the absolute deadline.
- For automatic timeout, the last **100 seconds** are reserved for a real graceful-stop attempt. With default settings, unfinished active work receives `stop` at **3500 seconds** and immediately becomes a sticky `maximum_runtime` failure. A cooperative exit in that reserved window remains a timeout failure.
- A voluntary normal stop requested earlier may finish in that reserved window and still pass, provided its original grace and the hard deadline have not expired.
- At the end of grace, the supervisor sends TERM to its owned child; after at most **10 seconds**, KILL; then at most **5 seconds** to observe/reap the exit. The default maximum supervised window is therefore **3615 seconds**. Sending KILL without observing exit produces unavailable evidence, never success.
- Small test-only constructor intervals let offline dummy tests finish quickly. They do not change the server environment's 300-second lower bound or production defaults. Their reserved grace is capped at half the short synthetic lifetime.

The default allows an 1800-second soak plus approximately 1700 seconds of finite startup/test phases before automatic shutdown begins. Measure the entire planned run, including those phases. If a legitimate plan needs more time, choose an explicit bounded value before launch; that is not authorization for an otherwise held server run.

## Durable evidence and result meanings

Each phase gets three distinct artifacts beside its ordinary log/privacy descriptor:

- `*-lifecycle.json`: atomic latest state snapshot
- `*-lifecycle.events.jsonl`: append-only complete state events, flushed and fsynced before snapshot replacement
- `*-lifecycle.observer.json`: created only when the runner cannot obtain reliable terminal supervisor evidence

Creation is exclusive; evidence paths and parent symlinks are rejected. Files use mode 0600. Snapshot replacement is fsynced, including the containing directory on POSIX. Events carry a fresh launch identity, sequence, UTC observation time, monotonic elapsed time, runner/supervisor/child PIDs, fixed budgets, guard mode, the selected literal loopback host/port/variant, allowlisted artifact hashes, and separately the actual child exit and effective result.

Neither commands, complete argv, environment variables, proxy settings, configuration content nor raw exception messages are recorded. Failure reasons are fixed identifiers or exception class names. The normal server log is separate and retains its existing command/output behavior; these redaction promises apply to lifecycle evidence.

States include `starting`, `child_spawned`, `child_started`, `running`, `stop_requested`, `terminate_requested`, `kill_requested`, `normal_stop`, `forced_stop`, `unexpected_exit`, `launch_error`, `launch_cancelled`, `supervisor_error`, and `terminal_evidence_unavailable`. A five-second heartbeat refreshes nonterminal runtime evidence. Running/heartbeat state is evidence of an observation at that time, not proof of continued liveness or final completion.

The facade accepts zero only when the matching launch/source/artifact/target/PID snapshot explicitly says terminal `normal_stop`, literal true normal/termination flags, actual integer child exit zero, effective integer zero, reason `requested_stop`, and the exact owned supervisor also exited zero. A spontaneous zero child exit is not a successful test stop. Unknown/stale/malformed/incomplete evidence cannot pass.

Effective results:

- `0`: verified requested normal child stop
- `124`: forced/cancelled/timeout/parent-loss/protocol-failure lifecycle, even when actual child exit is zero
- `125`: unavailable terminal evidence, or unsolicited zero exit
- `126`: launch/helper failure
- Other nonzero values: actual unexpected/nonzero child exit

`Server.stop()` is bounded and idempotent; it returns the effective result and supplies the redacted snapshot as `Server.shutdown_result`. Closed/broken control pipes still trigger evidence collection. The facade deliberately implements only the existing fixture-used `Popen` subset: child `pid`, text `stdin`/`stdout`, `poll()`, `wait(timeout)`, `returncode`, `terminate()`, and `kill()`. It is not a general-purpose drop-in Popen replacement. Child returncode is separately available in the lifecycle snapshot. `wait(timeout)` preserves timeout exceptions; `stop()` converts its own exceeded collection budget to unavailable/nonzero.

All runner success decisions must check their functional assertions plus the effective zero and full normal lifecycle snapshot. A server reaching normal stop alone does not prove those tests passed. The server harness CLI now rejects incomplete/failed/shutdown-error results; common runner terminal gates are maintained separately.

## Limits

- No mode establishes network isolation or supplements permission to contact external services. Existing bStats/spark/startup-update, exact server/config/JAR, import-binding and loopback gates remain required.
- The direct child is protected. Arbitrary descendants that a changed executable might spawn are not covered by a process-group/job/container guarantee; the reviewed server launch must not be replaced by an unreviewed launcher tree.
- Graceful/forced deadlines assume a scheduled supervisor and responsive local storage/kernel. An uninterruptible kernel task, suspended supervisor, hung filesystem fsync, machine failure, or loss of both observer and journal prevents an unconditional real-time termination proof. I/O errors trigger owned-child cleanup; blocked I/O cannot be certified away. No timeout/kill action is represented as observed termination without evidence.
- The short `java -version` check has its own 20-second timeout; the Linux guarded server-child mechanism does not cover that separate preflight subprocess.
- Cross-namespace process visibility is not reliable historical termination evidence. The tests use only their fresh owned handles; the Linux death test opens a pidfd for that exact fresh child before killing its own supervisor.

## Offline checks

```
python -B test-support/runtime/test_fixture_process.py
python -B test-support/runtime/test_fixture_privacy.py
python -B test-support/runtime/test_server_statistics.py
python -B test-support/runtime/test_fixture_safety.py
```

The lifecycle suite covers requested normal/abnormal and unsolicited exits, immutable/automatic deadlines, TERM/KILL escalation, blocked child stdin and bounded queues, malformed/type-invalid/partial/stale controls, parent exception/SIGKILL/EOF, EOF during stop, Linux supervisor death, failed exec, failed supervisor spawn, closed-stop idempotence, privacy canaries, symlink/evidence preservation, strict false-zero/nonce/PID/hash rejection, bootstrap parent-race and guard-failure cases, explicit portable behavior, and mock-only journal I/O or unobserved-kill failures. These are fixture-process tests, not Minecraft runtime results.

The read-only import verifier now binds `fixture_process`, `terminal_gates`, relevant `protocol_client` bytes, and the existing safety/privacy/server modules. It validates the complete selected port pair and handles `run_matrix`'s later client-path insertion separately. Recopying only `server_harness.py` is insufficient: use a fresh byte-identical reviewed support tree and verify the exact planned environment again.
