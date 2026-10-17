"""Short, network-free Python fixture used only by lifecycle unit tests."""
import os
import signal
import sys
import time

mode = sys.argv[1]
if mode == 'stubborn':
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
print('DUMMY_PID=' + str(os.getpid()), flush=True)
if mode == 'fail':
    raise SystemExit(7)
if mode == 'unsolicited_zero':
    raise SystemExit(0)
if mode in {'ignore_stop', 'stubborn', 'blocked_stdin'}:
    time.sleep(20)
else:
    for line in sys.stdin:
        if line.strip() == 'stop':
            if mode=='slow_stop':time.sleep(.06)
            raise SystemExit(7 if mode == 'fail_stop' else 0)
        print('DUMMY_COMMAND_RECEIVED', flush=True)
