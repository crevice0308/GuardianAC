"""No JVM, server, socket, old process, or external request: owned Python children only."""
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch
import fixture_process

from fixture_process import (FixtureProcess, FORCED_EXIT, LAUNCH_EXIT, UNKNOWN_EXIT,
                             configured_max_seconds, read_lifecycle)

ROOT = Path(__file__).resolve().parent
CHILD = ROOT / 'test_fixture_child.py'


class FixtureProcessTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='guardianac-owned-process-')
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.processes = []
        self.addCleanup(self.cleanup)

    def cleanup(self):
        for p in self.processes:
            if p.poll() is None:
                p.kill()
                p.wait(3)
            try:
                p.stdin.close()
            except OSError:
                pass
            p.stdout.close()

    def launch(self, mode='normal', **values):
        path = self.home / (str(len(self.processes)) + '-lifecycle.json')
        kwargs = dict(cwd=self.home, record_path=path, max_seconds=3,
                      stop_grace=.15, terminate_grace=.15, kill_wait=.4)
        kwargs.update(values)
        p = FixtureProcess([sys.executable, '-I', '-S', '-B', str(CHILD), mode], **kwargs)
        self.processes.append(p)
        return p

    def record(self, p):
        value = read_lifecycle(p.record_path)
        self.assertIsNotNone(value)
        return value

    def events(self, p):
        return [json.loads(s) for s in p.record_path.with_suffix('.events.jsonl').read_text().splitlines()]

    def test_normal_stop_is_observed_zero_and_pid_is_actual_child(self):
        p = self.launch()
        self.assertEqual('DUMMY_PID=' + str(p.pid), p.stdout.readline().strip())
        self.assertNotEqual(p.pid, p._supervisor.pid)
        self.assertEqual(0, p.stop())
        self.assertEqual(0, p.stop())
        state = self.record(p)
        self.assertTrue(state['normal_stop'])
        self.assertTrue(state['termination_observed'])
        self.assertEqual(0, state['child_returncode'])
        self.assertEqual('normal_stop', state['state'])
        events = self.events(p)
        self.assertEqual(list(range(1, len(events) + 1)), [e['sequence'] for e in events])
        self.assertIn('running', [e['state'] for e in events])
        self.assertEqual(state, events[-1])

    def test_unexpected_failure_and_even_spontaneous_zero_are_not_success(self):
        for mode, raw in [('fail', 7), ('unsolicited_zero', 0)]:
            p = self.launch(mode)
            self.assertNotEqual(0, p.wait(3))
            state = self.record(p)
            self.assertEqual('unexpected_exit', state['state'])
            self.assertEqual(raw, state['child_returncode'])
            self.assertFalse(state['normal_stop'])

    def test_requested_stop_with_nonzero_child_is_failure(self):
        p = self.launch('fail_stop')
        self.assertEqual(7, p.stop())
        self.assertFalse(self.record(p)['normal_stop'])

    def test_ignored_stop_escalates_and_never_returns_success(self):
        p = self.launch('ignore_stop')
        p.stdout.readline()
        self.assertEqual(FORCED_EXIT, p.stop())
        state = self.record(p)
        self.assertEqual('forced_stop', state['state'])
        self.assertEqual('stop_timeout', state['reason'])
        self.assertTrue(state['termination_observed'])
        self.assertIn('terminate_requested', [e['state'] for e in self.events(p)])

    @unittest.skipUnless(os.name == 'posix', 'POSIX TERM-ignore fixture')
    def test_stubborn_child_reaches_owned_kill_and_is_reaped(self):
        p = self.launch('stubborn')
        p.stdout.readline()
        self.assertEqual(FORCED_EXIT, p.stop())
        self.assertEqual(-9, self.record(p)['child_returncode'])
        self.assertIn('kill_requested', [e['state'] for e in self.events(p)])

    def test_runtime_deadline_cannot_be_extended_by_repeated_stop(self):
        p = self.launch('ignore_stop', max_seconds=.3, stop_grace=1)
        p.stdout.readline()
        started = time.monotonic()
        for _ in range(3):
            p._send('stop')
            time.sleep(.07)
        self.assertEqual(FORCED_EXIT, p.wait(3))
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertEqual('maximum_runtime', self.record(p)['reason'])

    def test_plain_runtime_expiry_fails_even_when_child_exits_zero_on_stop(self):
        p = self.launch(max_seconds=.25)
        self.assertEqual(FORCED_EXIT, p.wait(3))
        state=self.record(p)
        self.assertEqual('maximum_runtime', state['reason'])
        self.assertEqual(0,state['child_returncode'])
        self.assertNotIn('terminate_requested',[e['state'] for e in self.events(p)])

    def test_explicit_terminate_and_kill_are_nonzero(self):
        for method in ('terminate', 'kill'):
            p = self.launch()
            p.stdout.readline()
            getattr(p, method)()
            self.assertEqual(FORCED_EXIT, p.wait(3))
            self.assertEqual('explicit_' + method, self.record(p)['reason'])

    def test_wrong_identity_malformed_and_unknown_controls_fail_closed(self):
        frames = [{'run_id': '0' * 32, 'op': 'stop'}, {'op': 'deadline_extension'}, 'not json']
        frames += [{'run_id':'OWN_NONCE','op':value} for value in ([],{},None,0)]
        for frame in frames:
            p = self.launch()
            if isinstance(frame,dict) and frame.get('run_id')=='OWN_NONCE':frame['run_id']=p.run_id
            raw = frame if isinstance(frame, str) else json.dumps(frame)
            p._supervisor.stdin.write(raw + '\n')
            p._supervisor.stdin.flush()
            self.assertEqual(FORCED_EXIT, p.wait(3))
            state = self.record(p)
            self.assertEqual('protocol_error', state['reason'])
            self.assertFalse(state['normal_stop'])

    def test_partial_control_line_does_not_block_deadline(self):
        p = self.launch('ignore_stop', max_seconds=.25)
        p._supervisor.stdin.write('{"op":')
        p._supervisor.stdin.flush()
        self.assertEqual(FORCED_EXIT, p.wait(3))
        self.assertEqual('maximum_runtime', self.record(p)['reason'])

    def test_pipe_eof_requests_stop_but_preserves_runner_loss(self):
        p = self.launch()
        p.stdin.close()
        self.assertEqual(FORCED_EXIT, p.wait(3))
        state = self.record(p)
        self.assertEqual('runner_eof', state['reason'])
        self.assertEqual(0, state['child_returncode'])
        self.assertFalse(state['normal_stop'])
        self.assertIn('stop_requested', [e['state'] for e in self.events(p)])

    def test_eof_observed_during_requested_stop_cannot_race_to_success(self):
        p=self.launch('slow_stop')
        p.stdout.readline()
        p._send('stop')
        p.stdin.close()
        self.assertEqual(FORCED_EXIT,p.wait(3))
        self.assertEqual('runner_eof',self.record(p)['reason'])
        self.assertEqual(0,self.record(p)['child_returncode'])

    def test_stop_is_idempotent_after_lifeline_was_closed(self):
        p=self.launch()
        p.stdin.close()
        self.assertEqual(FORCED_EXIT,p.stop())
        self.assertEqual(FORCED_EXIT,p.stop())
        self.assertEqual('runner_eof',self.record(p)['reason'])

    def test_stop_wait_timeout_is_cached_without_repeating_collection_budget(self):
        p=FixtureProcess.__new__(FixtureProcess)
        p._result=None;p.run_id='a'*32
        p._supervisor=Mock();p._supervisor.poll.return_value=None
        p._supervisor.wait.side_effect=subprocess.TimeoutExpired('synthetic-supervisor',116)
        p._record_unavailable=Mock()
        self.assertEqual(UNKNOWN_EXIT,p.stop())
        self.assertEqual(UNKNOWN_EXIT,p.stop())
        p._supervisor.wait.assert_called_once_with(timeout=116)
        p._record_unavailable.assert_called_once_with(reason='stop_wait_timeout')

    def test_normal_stop_started_before_reserve_can_complete_within_hard_deadline(self):
        p=self.launch('slow_stop',max_seconds=.25,stop_grace=.2)
        p.stdout.readline()
        self.assertEqual(0,p.stop())
        self.assertEqual('normal_stop',self.record(p)['state'])

    def test_abrupt_parent_death_and_parent_exception_preserve_terminal_record(self):
        for cause in ('kill', 'exception'):
            record = self.home / (cause + '.json')
            program = '\n'.join([
                'import sys, time', 'sys.path.insert(0, ' + repr(str(ROOT)) + ')',
                'from fixture_process import FixtureProcess',
                'p=FixtureProcess(' + repr([sys.executable, '-I', '-S', '-B', str(CHILD), 'normal']) +
                    ', cwd=' + repr(str(self.home)) + ', record_path=' + repr(str(record)) +
                    ', max_seconds=3, stop_grace=.15, terminate_grace=.15, kill_wait=.4)',
                'print("PARENT_READY", flush=True)',
                'raise RuntimeError("synthetic runner failure")' if cause == 'exception' else 'time.sleep(20)',
            ])
            runner = subprocess.Popen([sys.executable, '-I', '-S', '-B', '-c', program], stdout=subprocess.PIPE,
                                      stderr=subprocess.DEVNULL, text=True)
            try:
                self.assertEqual('PARENT_READY', runner.stdout.readline().strip())
                if cause == 'kill':
                    runner.kill()  # Only this test's exact Popen child.
                self.assertNotEqual(0, runner.wait(3))
                end = time.monotonic() + 3
                while time.monotonic() < end:
                    state = read_lifecycle(record)
                    if state and state.get('terminal'):
                        break
                    time.sleep(.02)
                self.assertTrue(state['termination_observed'])
                self.assertFalse(state['normal_stop'])
                self.assertEqual('runner_eof', state['reason'])
            finally:
                if runner.poll() is None:
                    runner.kill()
                    runner.wait(3)
                runner.stdout.close()

    @unittest.skipUnless(sys.platform == 'linux' and hasattr(os, 'pidfd_open'), 'Linux pidfd observation')
    def test_supervisor_sigkill_kills_child_but_terminal_evidence_stays_unavailable(self):
        p = self.launch('stubborn')
        p.stdout.readline()
        child_fd = os.pidfd_open(p.pid)
        try:
            p._supervisor.kill()  # Exact owned test supervisor, never a historical PID.
            self.assertEqual(UNKNOWN_EXIT, p.wait(3))
            ready, _, _ = select.select([child_fd], [], [], 3)
            self.assertEqual([child_fd], ready)
        finally:
            os.close(child_fd)
        self.assertFalse(self.record(p)['terminal'])
        observer = json.loads(p.record_path.with_suffix('.observer.json').read_text())
        self.assertFalse(observer['termination_observed'])
        self.assertFalse(observer['normal_stop'])

    def test_failed_exec_is_redacted_durable_launch_error(self):
        path = self.home / 'failed.json'
        with self.assertRaises(RuntimeError):
            FixtureProcess([str(self.home / 'PRIVATE-NONEXISTENT-EXECUTABLE')], cwd=self.home,
                           record_path=path, max_seconds=2, stop_grace=.1,
                           terminate_grace=.1, kill_wait=.3)
        state = read_lifecycle(path)
        self.assertEqual('launch_error', state['state'])
        self.assertEqual(LAUNCH_EXIT, state['reported_returncode'])
        self.assertFalse(state['normal_stop'])
        self.assertNotIn('PRIVATE-NONEXISTENT-EXECUTABLE', path.read_text())

    def test_no_environment_proxy_command_or_private_values_in_lifecycle_records(self):
        marker = 'PRIVATE-ENV-PROXY-SENTINEL'
        with patch.dict(os.environ, {'HTTPS_PROXY': marker, 'PRIVATE_ENV_VALUE': marker}):
            p = self.launch(binding_sha256={'plugin': 'a' * 64})
            p.stdin.write(marker + '\n')
            p.stdin.flush()
            self.assertEqual(0, p.stop())
        for path in self.home.glob('*'):
            if path.is_file():
                self.assertNotIn(marker, path.read_text())
        self.assertEqual({'plugin': 'a' * 64}, self.record(p)['binding_sha256'])
        with self.assertRaises(ValueError):
            self.launch(binding_sha256={'HTTPS_PROXY': marker})

    def test_existing_records_and_invalid_budgets_rejected_before_launch(self):
        path = self.home / 'existing.json'
        path.write_text('preserve me')
        with patch('fixture_process.subprocess.Popen') as popen:
            with self.assertRaises(RuntimeError):
                self.launch(record_path=path)
            for value in (0, -1, float('inf'), float('nan'), 7201, True):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    self.launch(max_seconds=value)
            popen.assert_not_called()
        self.assertEqual('preserve me', path.read_text())

    def test_environment_budget_is_explicit_finite_and_bounded(self):
        self.assertEqual(3600, configured_max_seconds({}))
        self.assertEqual(7200, configured_max_seconds({'GUARDIAN_FIXTURE_MAX_SECONDS': '7200'}))
        for value in ('0', '299', '7201', 'inf', 'nan', '1800.0', '-1', ''):
            with self.subTest(value=value), self.assertRaises(ValueError):
                configured_max_seconds({'GUARDIAN_FIXTURE_MAX_SECONDS': value})

    def test_missing_and_malformed_evidence_is_not_a_success(self):
        self.assertIsNone(read_lifecycle(self.home / 'absent.json'))
        path = self.home / 'malformed.json'
        path.write_text('{')
        self.assertIsNone(read_lifecycle(path))

    def test_blocked_child_stdin_and_bounded_queue_overflow_cannot_block_watchdog(self):
        p=self.launch('blocked_stdin',max_seconds=.8,stop_grace=.15)
        p.stdout.readline()
        for _ in range(100):
            try:p._send('command',text='x'*4096)
            except (OSError,ValueError):break
        self.assertEqual(FORCED_EXIT,p.wait(3))
        self.assertIn(self.record(p)['reason'],{'command_overflow','control_overflow','maximum_runtime'})
        self.assertTrue(self.record(p)['termination_observed'])

    def test_runner_error_requests_stop_but_cannot_be_normal_success(self):
        p=self.launch()
        p.abort()
        self.assertEqual(FORCED_EXIT,p.wait(3))
        self.assertEqual('runner_error',self.record(p)['reason'])
        self.assertEqual(0,self.record(p)['child_returncode'])

    def test_symlink_record_and_parent_rejected_without_spawn(self):
        target=self.home/'preserve.json';target.write_text('private-existing-evidence')
        alias=self.home/'alias.json';alias.symlink_to(target)
        subdir=self.home/'real';subdir.mkdir()
        directory=self.home/'alias';directory.symlink_to(subdir,target_is_directory=True)
        with patch('fixture_process.subprocess.Popen') as popen:
            for path in (alias,directory/'record.json'):
                with self.assertRaises(ValueError):self.launch(record_path=path)
            popen.assert_not_called()
        self.assertEqual('private-existing-evidence',target.read_text())

    def test_supervisor_spawn_failure_records_unknown_without_child_claim(self):
        record=self.home/'spawn-failed.json'
        with patch('fixture_process.subprocess.Popen',side_effect=OSError('PRIVATE-ERROR-MARKER')):
            with self.assertRaises(OSError):self.launch(record_path=record)
        state=json.loads(record.with_suffix('.observer.json').read_text())
        self.assertFalse(state['termination_observed'])
        self.assertEqual('supervisor_spawn_failed',state['reason'])
        self.assertNotIn('PRIVATE-ERROR-MARKER',json.dumps(state))

    def test_stale_malformed_or_false_zero_terminal_snapshot_is_never_success(self):
        p=self.launch()
        self.assertEqual(0,p.stop())
        normal=self.record(p)
        changes=[{'schema':True},{'normal_stop':1},{'termination_observed':1},
                 {'child_returncode':False},{'reported_returncode':False},
                 {'run_id':'0'*32},{'supervisor_pid':-1},{'child_pid':-1},
                 {'binding_sha256':{'plugin':'b'*64}}, {'lifecycle_source_sha256':'b'*64},
                 {'target':{'host':'example.com'}}, {'sequence':True}, {'terminal':1},
                 {'state':'forced_stop'},{'reason':'maximum_runtime'}]
        for changed in changes:
            p.record_path.write_text(json.dumps(normal|changed))
            p._result=None
            with self.subTest(changed=changed):self.assertNotEqual(0,p.poll())
        p.record_path.write_text(json.dumps(normal));p._result=None
        self.assertEqual(0,p.poll())

    def fake_supervision(self, *, write_failure=False):
        # Mock Popen identity only: no unknown PID is signaled or inspected.
        config={'run_id':'a'*32,'runner_pid':os.getppid(),'argv':[sys.executable],
                'cwd':str(self.home),'record':str(self.home/'mock-state.json'),
                'max_seconds':.12,'stop_grace':.03,'terminate_grace':.03,'kill_wait':.03,
                'binding_sha256':{},'target':None}
        child=Mock(pid=1000000000)
        child.poll.return_value=None
        child.wait.return_value=-9
        class NoBackground:
            def __init__(self,target,args=(),**kwargs):self.target,self.args=target,args
            def start(self):
                if self.target.__name__=='read_init':self.target(*self.args)
        real_write=fixture_process._Journal.write
        def write(journal,state,**values):
            if write_failure and state=='running':raise OSError('PRIVATE-STORAGE-ERROR')
            return real_write(journal,state,**values)
        def spawn(cfg,journal):
            journal.write('child_started',child_pid=child.pid,guard_mode='portable_supervisor_only')
            return child
        with patch('fixture_process.threading.Thread',NoBackground), \
                patch('fixture_process._raw_line',return_value=(json.dumps(config)+'\n').encode()), \
                patch('fixture_process._spawn_owned',side_effect=spawn), \
                patch('fixture_process.signal.signal'), \
                patch.object(fixture_process._Journal,'write',write):
            result=fixture_process._supervise()
        return result,child,read_lifecycle(config['record'])

    def test_kill_without_observed_exit_is_unavailable_and_not_success(self):
        result,child,state=self.fake_supervision()
        self.assertNotEqual(0,result)
        child.terminate.assert_called_once()
        child.kill.assert_called_once()
        self.assertEqual('terminal_evidence_unavailable',state['state'])
        self.assertFalse(state['termination_observed'])
        self.assertFalse(state['normal_stop'])
        self.assertIsNone(state['child_returncode'])
        self.assertEqual(UNKNOWN_EXIT,state['reported_returncode'])

    def test_evidence_write_error_cleans_owned_child_and_does_not_leak_exception(self):
        result,child,state=self.fake_supervision(write_failure=True)
        self.assertNotEqual(0,result)
        child.kill.assert_called_once()
        child.wait.assert_called_once()
        self.assertEqual('supervisor_error',state['state'])
        self.assertFalse(state['normal_stop'])
        self.assertEqual(-9,state['child_returncode'])
        self.assertNotIn('PRIVATE-STORAGE-ERROR',json.dumps(state))

    def test_bootstrap_checks_parent_before_and_after_arming_without_exec_on_mismatch(self):
        for parents in ([99],[42,99]):
            libc=Mock();libc.prctl.return_value=0
            with patch('fixture_process.os.getppid',side_effect=parents), \
                    patch('fixture_process.ctypes.CDLL',return_value=libc), \
                    patch('fixture_process.os.write') as write, \
                    patch('fixture_process.os.execv') as execute:
                self.assertEqual(LAUNCH_EXIT,fixture_process._exec_guard(['42','10',sys.executable]))
                execute.assert_not_called()
                write.assert_called_with(10,b'parent_changed\n')
            self.assertEqual(0 if len(parents)==1 else 1,libc.prctl.call_count)

    def test_linux_guard_failure_is_fail_closed_without_exec(self):
        libc=Mock();libc.prctl.return_value=-1
        with patch('fixture_process.os.getppid',return_value=42), \
                patch('fixture_process.ctypes.CDLL',return_value=libc), \
                patch('fixture_process.os.write') as write, \
                patch('fixture_process.os.execv') as execute:
            self.assertEqual(LAUNCH_EXIT,fixture_process._exec_guard(['42','10',sys.executable]))
            execute.assert_not_called()
            write.assert_called_with(10,b'guard_error\n')

    def test_portable_fallback_is_explicit_and_no_pid_wrapper_is_substituted(self):
        child=Mock(pid=12345)
        journal=Mock()
        with patch('fixture_process.sys.platform','nonlinux'), \
                patch('fixture_process.subprocess.Popen',return_value=child) as popen:
            result=fixture_process._spawn_owned({'argv':[sys.executable],'cwd':str(self.home)},journal)
        self.assertIs(child,result)
        self.assertEqual([sys.executable],popen.call_args.args[0])
        self.assertNotIn('preexec_fn',popen.call_args.kwargs)
        self.assertNotIn('pass_fds',popen.call_args.kwargs)
        journal.write.assert_called_once_with('child_started',child_pid=12345,guard_mode='portable_supervisor_only')


if __name__ == '__main__':
    unittest.main()
