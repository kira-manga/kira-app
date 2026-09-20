"""Small Darwin command owner: retain direct child leaders until their groups are quiet.

Adapted from the existing app8-apple Commands ownership rule, not its fixture framework.
Never signal a PID discovered by name/path, or reuse a previous controller's PID receipt.
"""
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import time

ROOT = Path.cwd().resolve()
PREFIX = 'app32-apple-batch-01-' + os.environ['GITHUB_RUN_ID'] + '-' + os.environ['GITHUB_RUN_ATTEMPT']
WORK = Path(os.environ['RUNNER_TEMP']).resolve() / (PREFIX + '-work')
OUT = WORK.parent / (PREFIX + '-evidence')
OWNER = dict(run=os.environ['GITHUB_RUN_ID'], sha=os.environ['GITHUB_SHA'], root=str(ROOT))
MARKER = '-Dapp32.owned.root=' + str(WORK)
TOKEN = os.environ.pop('KIRA_PACKAGES_READ_TOKEN', '').encode()
PS = ['/bin/ps', '-axww', '-o', 'pid=,uid=,ppid=,pgid=,stat=,comm=,args=']
CANCELLED = False
os.umask(0o077)


def require(ok, message):
    if not ok:
        raise RuntimeError(message)


def redact(value):
    return value.replace(TOKEN, b'[REDACTED]') if TOKEN else value


def record(name, value):
    data = redact((json.dumps(value, indent=2) + '\n').encode())
    target = OUT / name
    require(len(data) <= 1024 * 1024 and not target.is_symlink(), 'Invalid/oversized JSON evidence')
    target.write_bytes(data)


def identity(path):
    require(path.resolve() == path and not path.is_symlink() and not path.is_mount(), 'Ambiguous owned path')
    info = path.stat()
    require(path.is_dir() and info.st_uid == os.getuid(), 'Foreign/non-directory owned path')
    return dict(owner=OWNER, device=info.st_dev, inode=info.st_ino)


def interrupted(signum, frame):
    global CANCELLED
    CANCELLED = True  # Never raise between Popen creation and registration.


class Commands:
    def __init__(self, env, seconds=54 * 60):
        require(all(hasattr(os, name) for name in ('waitid', 'P_PID', 'WEXITED', 'WNOHANG', 'WNOWAIT', 'killpg',
                'CLD_EXITED', 'CLD_KILLED', 'CLD_DUMPED'))
                and signal.getsignal(signal.SIGCHLD) == signal.SIG_DFL, 'Installed non-reaping Darwin APIs required')
        self.env, self.jobs, self.monitor = env, [], None
        self.end = time.monotonic() + seconds
        self.work_end = self.end - 4 * 60
        self.log_bytes = sum(path.stat().st_size for path in OUT.glob('*.log'))

    def start(self, args, label, env, leaf):
        require(len(self.jobs) < 256, 'Command count cap')
        job = dict(label=label, log=None if leaf else f'{os.getpid()}-{len(self.jobs) + 1:03d}-{label}.log',
                   process=None, pid=None, leaf=leaf, reaped=False, forced=False, exit_code=None)
        self.jobs.append(job)
        job['process'] = subprocess.Popen(args, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                          close_fds=True, start_new_session=True)
        job['pid'] = job['process'].pid
        self.checkpoint()
        return job

    def checkpoint(self):
        record(f'commands-{os.getpid()}.json', [{k: v for k, v in job.items() if k != 'process'} for job in self.jobs])

    def peek(self, job):
        process = job['process']
        require(process is not None and not job['reaped'] and process.returncode is None, 'Lost/reaped direct child')
        info = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        require(info is None or (info.si_pid == job['pid'] and info.si_uid == os.getuid()
                and info.si_code in (os.CLD_EXITED, os.CLD_KILLED, os.CLD_DUMPED)), 'Direct child identity changed')
        return info

    def group(self, job, rows):
        self.peek(job)  # ECHILD is refusal, never permission to signal a recycled group.
        group = [row for row in rows if row['pgid'] == job['pid']]
        require(any(row['pid'] == job['pid'] and row['ppid'] == os.getpid() and row['uid'] == os.getuid()
                    for row in group), 'Pinned leader absent/moved; preserve runtime')
        require(len(group) <= 64, 'Owned command group cap')
        return all(row['state'].startswith('Z') for row in group)

    def reap(self, job):
        require(self.peek(job) is not None, 'Cannot reap a live leader')
        job['exit_code'] = job['process'].wait(timeout=2)
        job['reaped'] = True  # No further signals are allowed after this point.
        self.checkpoint()

    def read_stream(self, job, log, deadline, cap, cleaning, monitor):
        pending, output, next_check = b'', bytearray(), time.monotonic() + 60
        while True:
            require(time.monotonic() < deadline and (cleaning or not CANCELLED), 'Command cancelled/timed out')
            if monitor and time.monotonic() >= next_check:
                self.monitor()
                next_check = time.monotonic() + 60
            if not select.select([job['process'].stdout], [], [], 0.2)[0]:
                continue
            chunk = os.read(job['process'].stdout.fileno(), 65536)
            if not chunk:
                output.extend(redact(pending))
                if log:
                    log.write(redact(pending))
                return bytes(output)
            cap -= len(chunk)
            require(cap >= 0, 'Command output cap exceeded')
            if log:
                self.log_bytes += len(chunk)
                require(self.log_bytes <= 24 * 1024 * 1024, 'Public command log aggregate exceeds 24MiB')
            pending = redact(pending + chunk)
            cut = max(0, len(pending) - max(0, len(TOKEN) - 1))
            output.extend(pending[:cut])
            if log:
                log.write(pending[:cut])
                log.flush()
            pending = pending[cut:]

    def capture(self, args, label, seconds=20, cap=1024 * 1024, env=None, cleaning=False, monitor=False, leaf=False):
        require((cleaning or not CANCELLED) and seconds > 0, 'Cancelled/expired before launch')
        require(not leaf or args == PS, 'Only the fixed nonforking ps projection is a trusted leaf')
        deadline = min(time.monotonic() + seconds, self.end if cleaning else self.work_end)
        require(time.monotonic() < deadline, 'Controller deadline exceeded')
        job, log = self.start(args, label, env or self.env, leaf), None
        try:
            if not leaf:
                log = (OUT / job['log']).open('xb')
            raw = self.read_stream(job, log, deadline, cap, cleaning, monitor)
            while self.peek(job) is None and time.monotonic() < deadline:
                time.sleep(0.025)
            require(self.peek(job) is not None, 'Leader did not exit before deadline')
            if not leaf:
                for retry in range(4):
                    if self.group(job, self.census(min(5, deadline - time.monotonic()))):
                        break
                    require(retry < 3, 'Command left live group members')
                    time.sleep(min(1, max(0, deadline - time.monotonic())))
            require(time.monotonic() < deadline and (cleaning or not CANCELLED), 'Late/cancelled command join')
            self.reap(job)
            return job['exit_code'], raw.decode(errors='replace')
        finally:
            if log:
                log.close()
            job['process'].stdout.close()
            self.checkpoint()

    def call(self, args, label, **kwargs):
        code, output = self.capture(args, label, **kwargs)
        require(code == 0, label + ': nonzero exit (see bounded log)')
        return output

    def census(self, seconds=5):
        # Raw all-host command lines are private memory only, never an uploaded log/receipt.
        code, raw = self.capture(PS, 'private-process-census', seconds=seconds, cleaning=True, leaf=True)
        require(code == 0 and raw.endswith('\n') and raw.strip(), 'Incomplete process census')
        rows = []
        for line in raw.splitlines():
            fields = line.split(None, 6)
            require(len(fields) >= 6 and all(part.isdigit() for part in fields[:4]), 'Malformed process census')
            require(fields[4][0] in 'IRSTUZ?', 'Malformed process state')
            rows.append(dict(zip(('pid', 'uid', 'ppid', 'pgid'), map(int, fields[:4])), state=fields[4],
                             executable=Path(fields[5]).name, command=' '.join(fields[5:])))
        require(len(rows) <= 8192 and len({row['pid'] for row in rows}) == len(rows), 'Process census size/identity cap')
        return rows

    def drain(self):
        # A failed/timed-out exact ps child needs no recursive ps observer to be stopped.
        for job in [j for j in self.jobs if j['process'] is not None and not j['reaped'] and j['leaf']]:
            require(time.monotonic() + 3 < self.end, 'No bounded observer drain window')
            if self.peek(job) is None:
                os.killpg(job['pid'], signal.SIGKILL)
                job['forced'] = True
                time.sleep(0.2)
            self.reap(job)
        for job in [j for j in self.jobs if j['process'] is not None and not j['reaped'] and not j['leaf']]:
            require(time.monotonic() + 30 < self.end, 'No bounded group drain window; retain files')
            for sig, grace in ((signal.SIGTERM, 8), (signal.SIGKILL, 4)):
                if self.group(job, self.census()):
                    break
                self.peek(job)
                os.killpg(job['pid'], sig)  # Only this controller's still-unreaped direct-child group.
                job['forced'] = True
                time.sleep(grace)
            require(self.group(job, self.census()), 'Owned group remains; retain runtime/output files')
            self.reap(job)
        self.checkpoint()

    def absence(self, udid=''):
        self.drain()
        rows = self.census()
        remaining = [row for row in rows if row['pid'] != os.getpid() and not row['state'].startswith('Z')
                     and (str(WORK) in row['command'] or str(ROOT) + '/' in row['command']
                          or (udid and udid in row['command']) or row['executable'].endswith('.kexe'))]
        record('workers.json', [{k: v for k, v in row.items() if k != 'command'} for row in remaining])
        require(not remaining, 'Escaped/unknown worker remains; no discovered-PID signals or file cleanup')


signal.signal(signal.SIGTERM, interrupted)
signal.signal(signal.SIGINT, interrupted)
