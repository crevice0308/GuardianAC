"""Owned, bounded fixture subprocesses; stdlib only, no server authorization.

The supervisor alone signals its fresh Popen child. Linux additionally arms
PDEATHSIG in a separate single-threaded exec bootstrap (never preexec_fn).
No saved PID is used for process control. See PROCESS-LIFECYCLE.md.
"""
from pathlib import Path
import ctypes
import datetime
import errno
import hashlib
import json
import math
import os
import queue
import re
import select
import signal
import stat
import subprocess
import sys
import threading
import time
import uuid

DEFAULT_MAX_SECONDS = 3600
MAX_ALLOWED_SECONDS = 7200
UNKNOWN_EXIT = 125
FORCED_EXIT = 124
LAUNCH_EXIT = 126
POLL_SECONDS = .05
CONTROL_LIMIT = 65536
MODULE = Path(__file__).resolve()


def configured_max_seconds(environ=None):
    value = (os.environ if environ is None else environ).get('GUARDIAN_FIXTURE_MAX_SECONDS', str(DEFAULT_MAX_SECONDS))
    if not re.fullmatch(r'[0-9]+', value) or not 300 <= int(value) <= MAX_ALLOWED_SECONDS:
        raise ValueError('GUARDIAN_FIXTURE_MAX_SECONDS must be an integer from 300 through 7200')
    return int(value)


def _seconds(value, maximum):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not .01 <= value <= maximum:
        raise ValueError('Invalid bounded fixture lifecycle interval')
    return float(value)


def _stamp():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def _exclusive(path):
    return os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)


def _safe_parent(path):
    if not path.is_absolute() or not path.parent.is_dir():
        raise ValueError('Lifecycle evidence needs an existing absolute parent directory')
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Lifecycle evidence must not follow symlinks')


class _Journal:
    def __init__(self, config):
        self.path = Path(config['record'])
        _safe_parent(self.path)
        self.events = self.path.with_suffix('.events.jsonl')
        _safe_parent(self.events)
        # Reserve both names before any owned child exists. Never reuse evidence.
        with os.fdopen(_exclusive(self.path), 'w', encoding='utf-8'):
            pass
        self.stream = os.fdopen(_exclusive(self.events), 'w', encoding='utf-8')
        self.started = time.monotonic()
        self.data = {
            'schema': 1, 'run_id': config['run_id'], 'sequence': 0,
            'runner_pid': config['runner_pid'], 'supervisor_pid': os.getpid(),
            'child_pid': None, 'child_returncode': None, 'reported_returncode': None,
            'terminal': False, 'termination_observed': False, 'normal_stop': False,
            'reason': None, 'guard_mode': None,
            'max_runtime_seconds': config['max_seconds'],
            'timeout_stop_at_seconds': config['max_seconds'] - min(config['stop_grace'], config['max_seconds']/2),
            'stop_grace_seconds': config['stop_grace'],
            'terminate_grace_seconds': config['terminate_grace'],
            'kill_observation_seconds': config['kill_wait'],
            'max_supervised_seconds': config['max_seconds'] + config['terminate_grace'] + config['kill_wait'],
            'binding_sha256': config['binding_sha256'], 'target': config['target'],
            'lifecycle_source_sha256': hashlib.sha256(MODULE.read_bytes()).hexdigest(),
        }
        self.write('starting')

    def write(self, state, **values):
        self.data.update(values)
        self.data.update(state=state, observed_at_utc=_stamp(),
                         elapsed_seconds=round(time.monotonic() - self.started, 6),
                         sequence=self.data['sequence'] + 1)
        raw = json.dumps(self.data, sort_keys=True) + '\n'
        self.stream.write(raw)
        self.stream.flush()
        os.fsync(self.stream.fileno())
        tmp = self.path.with_name(self.path.name + '.' + self.data['run_id'] + '.tmp')
        try:
            with os.fdopen(_exclusive(tmp), 'w', encoding='utf-8') as out:
                out.write(raw)
                out.flush()
                os.fsync(out.fileno())
            os.replace(tmp, self.path)
            if os.name == 'posix':
                fd = os.open(self.path.parent, os.O_RDONLY)
                try:
                    os.fsync(fd)
                finally:
                    os.close(fd)
        finally:
            if tmp.exists():
                tmp.unlink()


def read_lifecycle(path):
    """Read evidence only. Missing/malformed records never imply an exited child."""
    try:
        value = json.loads(Path(path).read_text(encoding='utf-8'))
        if isinstance(value, dict) and type(value.get('schema')) is int and value.get('schema') == 1:
            return value
    except (OSError, ValueError, UnicodeError):
        pass
    return None


def _validate_config(config):
    required = {'run_id', 'runner_pid', 'argv', 'cwd', 'record', 'max_seconds',
                'stop_grace', 'terminate_grace', 'kill_wait', 'binding_sha256', 'target'}
    if not isinstance(config, dict) or set(config) != required:
        raise ValueError('Invalid lifecycle initialization')
    if not isinstance(config['run_id'], str) or not re.fullmatch(r'[0-9a-f]{32}', config['run_id']):
        raise ValueError('Invalid lifecycle identity')
    if type(config['runner_pid']) is not int or config['runner_pid'] <= 0:
        raise ValueError('Invalid lifecycle owner')
    if not isinstance(config['argv'], list) or not config['argv'] or any(not isinstance(v, str) or '\0' in v for v in config['argv']):
        raise ValueError('Invalid owned child command')
    if not Path(config['argv'][0]).is_absolute() or not isinstance(config['cwd'], str) or not Path(config['cwd']).is_dir():
        raise ValueError('Owned child needs an absolute executable and existing working directory')
    _safe_parent(Path(config['record']))
    for key, maximum in [('max_seconds', MAX_ALLOWED_SECONDS), ('stop_grace', 100), ('terminate_grace', 10), ('kill_wait', 5)]:
        config[key] = _seconds(config[key], maximum)
    target = config['target']
    if target is not None and (not isinstance(target, dict) or set(target) != {'host', 'port', 'variant'}
            or target['host'] != '127.0.0.1' or type(target['port']) is not int
            or not 1024 <= target['port'] <= 65535 or target['variant'] not in {'paper-26.3', 'purpur-26.3'}):
        raise ValueError('Only the exact selected loopback fixture target may be recorded')
    binding = config['binding_sha256']
    if not isinstance(binding, dict) or any(k not in {'plugin', 'server', 'privacy_descriptor'} or not isinstance(v, str) or not re.fullmatch(r'[0-9a-f]{64}', v) for k, v in binding.items()):
        raise ValueError('Only allowlisted SHA-256 bindings may be recorded')
    return config


def _exec_guard(arguments):
    """Fresh single-threaded process; same PID becomes the actual target exec."""
    expected_parent, ready_fd = int(arguments[0]), int(arguments[1])
    try:
        if os.getppid() != expected_parent:
            os.write(ready_fd, b'parent_changed\n')
            return LAUNCH_EXIT
        # Linux clears PDEATHSIG across privilege-changing exec. Refuse those
        # targets rather than claim protection that would disappear at exec.
        if os.stat(arguments[2]).st_mode & (stat.S_ISUID|stat.S_ISGID):
            os.write(ready_fd,b'guard_error\n');return LAUNCH_EXIT
        try:
            capabilities=os.getxattr(arguments[2],'security.capability')
        except OSError as error:
            if error.errno not in {errno.ENODATA,errno.ENOTSUP,errno.EOPNOTSUPP}:raise
            capabilities=b''
        if capabilities:
            os.write(ready_fd,b'guard_error\n');return LAUNCH_EXIT
        libc = ctypes.CDLL(None, use_errno=True)
        if libc.prctl(1, int(signal.SIGKILL), 0, 0, 0) != 0:  # PR_SET_PDEATHSIG
            os.write(ready_fd, b'guard_error\n')
            return LAUNCH_EXIT
        # Closes the parent-died-before-prctl race without signaling any PID.
        if os.getppid() != expected_parent:
            os.write(ready_fd, b'parent_changed\n')
            return LAUNCH_EXIT
        os.write(ready_fd, b'armed\n')
        os.set_inheritable(ready_fd, False)  # Exec closes the error channel; readiness is separate.
        os.execv(arguments[2], arguments[2:])
    except BaseException:
        # Never put exception strings, arguments, environment or proxy data in evidence.
        try:
            os.write(ready_fd, b'exec_error\n')
        except OSError:
            pass
        return LAUNCH_EXIT


class _OwnedLaunchError(RuntimeError):
    def __init__(self, child):
        super().__init__('Owned child launch failed')
        self.child = child


def _spawn_owned(config, journal):
    if sys.platform != 'linux':
        child = subprocess.Popen(config['argv'], cwd=config['cwd'], stdin=subprocess.PIPE,
                                 stdout=None, stderr=subprocess.STDOUT, bufsize=0, close_fds=True)
        try:
            journal.write('child_started', child_pid=child.pid, guard_mode='portable_supervisor_only')
            return child
        except BaseException:
            raise _OwnedLaunchError(child) from None
    read_fd, write_fd = os.pipe()
    child = None
    try:
        command = [sys.executable, '-I', '-S', '-B', str(MODULE), '--exec', str(os.getpid()), str(write_fd), *config['argv']]
        child = subprocess.Popen(command, cwd=config['cwd'], stdin=subprocess.PIPE, stdout=None,
                                 stderr=subprocess.STDOUT, bufsize=0, close_fds=True, pass_fds=(write_fd,))
        os.close(write_fd)
        write_fd = None
        journal.write('child_spawned', child_pid=child.pid, guard_mode='linux_guard_pending')
        result = b''
        until = min(journal.started + config['max_seconds'], time.monotonic() + 5)
        while time.monotonic() < until:
            ready, _, _ = select.select([read_fd], [], [], max(0, until - time.monotonic()))
            if not ready:
                break
            block = os.read(read_fd, 128)
            if not block:
                if result == b'armed\n':
                    journal.write('child_started', guard_mode='linux_pdeathsig_sigkill')
                    return child
                break
            result += block
            if len(result) > 128:
                break
        raise RuntimeError('Owned child exec/parent-death guard failed')
    except BaseException:
        # The supervisor performs one bounded cleanup of this exact owned child.
        if child is not None:raise _OwnedLaunchError(child) from None
        raise
    finally:
        os.close(read_fd)
        if write_fd is not None:
            os.close(write_fd)


def _raw_line():
    # Raw os.read avoids CPython buffered-stdin locks during daemon-thread exit.
    data = bytearray()
    while len(data) <= CONTROL_LIMIT:
        byte = os.read(sys.stdin.fileno(), 1)
        if not byte:
            return bytes(data)
        data += byte
        if byte == b'\n':
            return bytes(data)
    raise ValueError('Oversized control frame')


class _FailureFlag:
    # Appending a first fixed reason avoids buffered I/O and signal-handler locks.
    # At worst competing observations append two reasons; either is a failure.
    def __init__(self):self.reasons=[]
    def set(self,reason='control_overflow'):
        if not self.reasons:self.reasons.append(reason)
    def is_set(self):return bool(self.reasons)
    @property
    def reason(self):return self.reasons[0] if self.reasons else None


def _post(events, broken, event):
    if event[0] in {'runner_eof','protocol_error','stdin_error','supervisor_signal'}:
        broken.set(event[0])
    try:
        events.put_nowait(event)
    except queue.Full:
        broken.set()


def _read_controls(events, broken, run_id):
    while True:
        try:
            raw = _raw_line()
            if not raw:
                _post(events, broken, ('runner_eof', None))
                return
            if not raw.endswith(b'\n'):raise ValueError('Incomplete control frame')
            obj = json.loads(raw)
            if len(raw) > CONTROL_LIMIT or not isinstance(obj, dict) or obj.get('run_id') != run_id:
                raise ValueError()
            op = obj.get('op')
            if not isinstance(op,str) or op not in {'command', 'stop', 'terminate', 'kill', 'abort'} or set(obj) != ({'run_id', 'op', 'text'} if op == 'command' else {'run_id', 'op'}):
                raise ValueError()
            if op == 'command' and (not isinstance(obj['text'], str) or len(obj['text']) > 4096 or any(c in obj['text'] for c in '\r\n\0')):
                raise ValueError()
            if op in {'abort','terminate','kill'}:
                broken.set('runner_error' if op=='abort' else 'explicit_'+op)
            _post(events, broken, (op, obj.get('text')))
        except (OSError, ValueError, UnicodeError, TypeError, RecursionError):
            _post(events, broken, ('protocol_error', None))
            return


def _write_commands(child, commands, events, broken):
    while True:
        command = commands.get()
        if command is None:
            return
        try:
            remaining=memoryview((command+'\n').encode('utf-8'))
            while remaining:
                written=child.stdin.write(remaining)
                if not isinstance(written,int) or written<=0:raise OSError('Incomplete owned-child stdin write')
                remaining=remaining[written:]
            child.stdin.flush()
        except (OSError, ValueError):
            _post(events, broken, ('stdin_error', None))
            return


def _supervise():
    journal = child = None
    try:
        init = queue.Queue(maxsize=1)
        def read_init():
            try:
                init.put(_raw_line())
            except BaseException:
                init.put(b'')
        threading.Thread(target=read_init, daemon=True).start()
        raw = init.get(timeout=5)
        if not raw:  # Runner vanished before authorizing even this owned launch.
            return LAUNCH_EXIT
        if len(raw) > CONTROL_LIMIT or not raw.endswith(b'\n'):
            raise ValueError()
        config = _validate_config(json.loads(raw))
        journal = _Journal(config)
        events, commands = queue.Queue(maxsize=64), queue.Queue(maxsize=64)
        broken = _FailureFlag()
        # Keep the signal handler minimal; normal loop performs only owned-child actions.
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda number, frame: broken.set('supervisor_signal'))
        threading.Thread(target=_read_controls, args=(events, broken, config['run_id']), daemon=True).start()
        if broken.is_set() or (os.name=='posix' and os.getppid()!=config['runner_pid']):
            journal.write('launch_cancelled',terminal=True,reason=broken.reason or 'runner_eof',
                          reported_returncode=FORCED_EXIT)
            return 1
        child = _spawn_owned(config, journal)
        threading.Thread(target=_write_commands, args=(child, commands, events, broken), daemon=True).start()
        journal.write('running')
        runtime_deadline = journal.started + config['max_seconds']
        timeout_stop_at = runtime_deadline - min(config['stop_grace'], config['max_seconds']/2)
        stop_deadline = term_deadline = kill_deadline = None
        requested_stop = False
        forced_reason = None
        last_heartbeat = time.monotonic()

        def stop(reason=None):
            nonlocal requested_stop, stop_deadline, forced_reason
            if reason and forced_reason is None:
                forced_reason = reason
                if requested_stop:journal.write(journal.data['state'],reason=forced_reason)
            if stop_deadline is None and term_deadline is None and kill_deadline is None:
                requested_stop = True
                stop_deadline = min(time.monotonic() + config['stop_grace'], runtime_deadline)
                journal.write('stop_requested', reason=forced_reason or 'requested_stop')
                # Drop unsent ordinary work; shutdown has priority. The writer
                # itself may be blocked, but this deadline loop never is.
                while True:
                    try:
                        commands.get_nowait()
                    except queue.Empty:
                        break
                commands.put_nowait('stop')

        def force(kill=False, reason=None):
            nonlocal forced_reason, stop_deadline, term_deadline, kill_deadline
            forced_reason = forced_reason or reason or 'stop_timeout'
            stop_deadline = None
            if child.poll() is not None:
                return
            if kill and kill_deadline is None:
                journal.write('kill_requested', reason=forced_reason)
                child.kill()
                term_deadline = None
                kill_deadline = time.monotonic() + config['kill_wait']
            elif term_deadline is None and kill_deadline is None:
                journal.write('terminate_requested', reason=forced_reason)
                child.terminate()
                term_deadline = time.monotonic() + config['terminate_grace']

        while True:
            now = time.monotonic()
            # Failure observations are sticky even if a clean child exit races
            # queued EOF/abort/protocol handling. Late normal stop stays queued.
            if broken.is_set():stop(broken.reason)
            # Observing an exit after the fixed deadline cannot turn expiry green.
            if forced_reason is None and (now >= runtime_deadline or (now >= timeout_stop_at and not requested_stop)):
                forced_reason = 'maximum_runtime'
                stop(forced_reason)
            if stop_deadline is not None and now>=stop_deadline and forced_reason is None:
                forced_reason='stop_timeout'
            code = child.poll()
            if code is not None:
                # poll may release the GIL; include failures observed during it.
                forced_reason=forced_reason or broken.reason
                if time.monotonic()>=runtime_deadline:forced_reason=forced_reason or 'maximum_runtime'
                if forced_reason:
                    state, reported = 'forced_stop', FORCED_EXIT
                elif requested_stop and code == 0:
                    state, reported = 'normal_stop', 0
                else:
                    state, reported = 'unexpected_exit', code or UNKNOWN_EXIT
                journal.write(state, terminal=True, termination_observed=True,
                              normal_stop=state == 'normal_stop', child_returncode=code,
                              reported_returncode=reported, reason=forced_reason or ('requested_stop' if requested_stop else 'unsolicited_exit'))
                return 0 if reported == 0 else 1
            if now >= runtime_deadline and not forced_reason:
                stop('maximum_runtime')
            if stop_deadline is not None and now >= stop_deadline:
                force(reason=forced_reason or 'stop_timeout')
            if term_deadline is not None and now >= term_deadline:
                force(kill=True)
            if kill_deadline is not None and now >= kill_deadline:
                journal.write('terminal_evidence_unavailable', terminal=True, reason=forced_reason,
                              reported_returncode=UNKNOWN_EXIT, termination_observed=False)
                return 1
            if now - last_heartbeat >= 5:
                journal.write(journal.data['state'])
                last_heartbeat = now
            try:
                op, value = events.get(timeout=POLL_SECONDS)
            except queue.Empty:
                continue
            if op == 'command':
                if stop_deadline is None and term_deadline is None and kill_deadline is None:
                    try:
                        commands.put_nowait(value)
                    except queue.Full:
                        stop('command_overflow')
            elif op == 'stop':
                stop()
            elif op == 'abort':
                stop('runner_error')
            elif op == 'terminate':
                force(reason='explicit_terminate')
            elif op == 'kill':
                force(kill=True, reason='explicit_kill')
            elif op in {'runner_eof', 'protocol_error', 'supervisor_signal', 'stdin_error'}:
                stop(op)
    except BaseException as error:
        # An internal failure must still contain the directly owned child; never
        # reuse a PID from the journal. This path intentionally does not claim success.
        code = None
        launch_failed = child is None
        child = getattr(error, 'child', child)
        if child is not None:
            try:
                if child.poll() is None:
                    child.kill()
                code = child.wait(timeout=config['kill_wait'])
            except BaseException:
                pass
        if journal is not None:
            try:
                journal.write('launch_error' if launch_failed else 'supervisor_error', terminal=True,
                              termination_observed=child is not None and code is not None,
                              child_returncode=code, reported_returncode=LAUNCH_EXIT,
                              reason=type(error).__name__)
            except BaseException:
                pass
        return LAUNCH_EXIT
    finally:
        if child is not None and child.stdin is not None:
            child.stdin.close()
        if journal is not None:
            journal.stream.close()


class _Input:
    def __init__(self, owner):
        self.owner = owner
        self.pending = ''

    def write(self, text):
        if not isinstance(text, str):
            raise TypeError('Fixture stdin is text-only')
        if len(self.pending) + len(text) > 4097:
            raise ValueError('Fixture command exceeds bounded line size')
        self.pending += text
        while '\n' in self.pending:
            line, self.pending = self.pending.split('\n', 1)
            if line == 'stop':
                self.owner._send('stop')
            else:
                self.owner._send('command', text=line)
        return len(text)

    def flush(self):
        if self.pending:
            raise ValueError('Fixture commands must end with a newline')

    def close(self):
        self.owner._supervisor.stdin.close()

    @property
    def closed(self):
        return self.owner._supervisor.stdin.closed


class FixtureProcess:
    """Popen subset used by fixture runners. pid is always the actual child PID.

    returncode is the verified lifecycle result, not a supervisor exit status.
    A zero child exit without a requested normal stop is deliberately nonzero.
    """
    def __init__(self, argv, *, cwd, record_path, max_seconds=DEFAULT_MAX_SECONDS,
                 stop_grace=100, terminate_grace=10, kill_wait=5, binding_sha256=None, target=None):
        self.record_path = Path(record_path).absolute()
        self.run_id = uuid.uuid4().hex
        self.pid = None
        self._result = None
        config = _validate_config({'run_id': self.run_id, 'runner_pid': os.getpid(),
            'argv': list(argv), 'cwd': str(cwd), 'record': str(self.record_path),
            'max_seconds': max_seconds, 'stop_grace': stop_grace,
            'terminate_grace': terminate_grace, 'kill_wait': kill_wait,
            'binding_sha256': {} if binding_sha256 is None else binding_sha256, 'target': target})
        self._binding_sha256=config['binding_sha256']
        self._target=config['target']
        self._source_sha256=hashlib.sha256(MODULE.read_bytes()).hexdigest()
        if self.record_path.exists() or self.record_path.with_suffix('.events.jsonl').exists():
            raise RuntimeError('Preserve existing lifecycle evidence; use a new phase/run')
        try:
            self._supervisor = subprocess.Popen([sys.executable, '-I', '-S', '-B', str(MODULE), '--supervise'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding='utf-8', errors='replace', bufsize=1,
                close_fds=True, start_new_session=os.name == 'posix')
        except BaseException:
            self._record_unavailable(reason='supervisor_spawn_failed')
            raise
        self.stdout = self._supervisor.stdout
        self.stdin = _Input(self)
        try:
            raw = json.dumps(config) + '\n'
            if len(raw.encode('utf-8')) > CONTROL_LIMIT:
                raise ValueError('Fixture initialization exceeds bounded control size')
            self._supervisor.stdin.write(raw)
            self._supervisor.stdin.flush()
            until = time.monotonic() + 10
            while time.monotonic() < until:
                state = self.lifecycle()
                if state and state.get('child_pid') and state.get('guard_mode') in {'linux_pdeathsig_sigkill', 'portable_supervisor_only'} and state.get('state') not in {'launch_error', 'supervisor_error'}:
                    self.pid = state['child_pid']
                    return
                if self._supervisor.poll() is not None:
                    self.poll()
                    raise RuntimeError('Owned fixture launch failed; inspect redacted lifecycle evidence')
                time.sleep(POLL_SECONDS)
            raise TimeoutError('Owned fixture supervisor did not acknowledge launch')
        except BaseException:
            try:
                self._supervisor.stdin.close()  # EOF starts owned-child cleanup; never kill a guessed PID.
            except OSError:
                pass
            self.stdout.close()
            try:
                self._supervisor.wait(timeout=config['stop_grace'] + config['terminate_grace'] + config['kill_wait'] + 1)
            except subprocess.TimeoutExpired:
                self._record_unavailable()
            raise

    def lifecycle(self):
        state = read_lifecycle(self.record_path)
        return state if state and state.get('run_id') == self.run_id else None

    def _send(self, op, **values):
        self._supervisor.stdin.write(json.dumps({'run_id': self.run_id, 'op': op, **values}) + '\n')
        self._supervisor.stdin.flush()

    def _record_unavailable(self, reason='supervisor_unavailable'):
        path = self.record_path.with_suffix('.observer.json')
        try:
            with os.fdopen(_exclusive(path), 'w', encoding='utf-8') as out:
                json.dump({'schema': 1, 'run_id': self.run_id, 'state': 'terminal_evidence_unavailable',
                    'observed_at_utc': _stamp(), 'normal_stop': False, 'termination_observed': False,
                    'reported_returncode': UNKNOWN_EXIT, 'reason': reason}, out)
                out.write('\n')
                out.flush()
                os.fsync(out.fileno())
        except OSError:
            pass

    def poll(self):
        if self._result is not None:
            return self._result
        if self._supervisor.poll() is None:
            return None
        state = self.lifecycle()
        if (state and state.get('terminal') is True and type(state.get('reported_returncode')) is int
                and type(state.get('sequence')) is int and state['sequence'] >= 2
                and type(state.get('supervisor_pid')) is int and state.get('supervisor_pid') == self._supervisor.pid
                and state.get('binding_sha256') == self._binding_sha256 and state.get('target') == self._target
                and state.get('lifecycle_source_sha256') == self._source_sha256
                and (self.pid is None or state.get('child_pid') == self.pid)):
            code = state['reported_returncode']
            if code == 0 and not (state.get('state') == 'normal_stop' and state.get('reason') == 'requested_stop'
                                 and state.get('normal_stop') is True and state.get('termination_observed') is True
                                 and type(state.get('child_returncode')) is int and state.get('child_returncode') == 0 and self._supervisor.returncode == 0):
                code = UNKNOWN_EXIT
            self._result = code
        else:
            self._result = UNKNOWN_EXIT
            self._record_unavailable()
        return self._result

    @property
    def returncode(self):
        return self.poll()

    def wait(self, timeout=None):
        self._supervisor.wait(timeout=timeout)
        return self.poll()

    def terminate(self):
        if self.poll() is None:
            self._send('terminate')

    def kill(self):
        if self.poll() is None:
            self._send('kill')

    def abort(self):
        if self.poll() is None:
            self._send('abort')

    def stop(self):
        if self._result is not None:return self._result
        if self.poll() is None:
            try:self._send('stop')
            except (OSError,ValueError):pass  # EOF/closed transport still needs evidence collection.
        try:
            return self.wait(timeout=116)
        except subprocess.TimeoutExpired:
            self._result=UNKNOWN_EXIT
            self._record_unavailable(reason='stop_wait_timeout')
            try:self._supervisor.stdin.close()
            except (OSError,ValueError):pass
            return self._result


if __name__ == '__main__':
    if len(sys.argv) >= 5 and sys.argv[1] == '--exec' and sys.platform == 'linux':
        raise SystemExit(_exec_guard(sys.argv[2:]))
    if sys.argv[1:] == ['--supervise']:
        raise SystemExit(_supervise())
    raise SystemExit('Internal owned-fixture helper; no standalone server launch command')
