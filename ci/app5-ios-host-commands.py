"""Host-only output budget adapter; accepted ownership/launch/settlement code is unchanged."""
import stat

CLEANUP_LOG_RESERVE = 1048576  # Reserved inside, not added to, the existing 4MiB other-log budget.


def host_within_cap(self):
    host, work, cleanup = [], [], []
    custody = True
    for index, task in enumerate(self.tasks):
        receipt = task['receipt']
        size = max(task.get('hostBudgetBytesSeen', 0), receipt.get('outputBytesAtLastRead', 0))
        try:
            info = task['log'].lstat()
        except FileNotFoundError:
            info = None
        if info is not None:
            custody &= stat.S_ISREG(info.st_mode)
            size = max(size, info.st_size)
        task['hostBudgetBytesSeen'] = size  # Unlinked census logs cannot replenish either allowance.
        is_cleanup = self.cleanup_at is not None and index >= self.cleanup_at
        if receipt['label'] == 'host-build':
            custody &= receipt['argv'][:1] == ['/usr/bin/xcodebuild'] and not task['leaf'] and not is_cleanup
            host.append(size)
        else:
            (cleanup if is_cleanup else work).append(size)
    self.log_custody_failed |= not custody or len(host) > 1
    work_ok = all(n <= 1048576 for n in work) and sum(work) <= 4194304 - CLEANUP_LOG_RESERVE
    cleanup_ok = all(n <= 1048576 for n in cleanup) and sum(cleanup) <= CLEANUP_LOG_RESERVE
    host_ok = all(n <= 8388608 for n in host)
    normal_ok = not self.log_custody_failed and host_ok and work_ok and cleanup_ok
    self.log_budget_failed |= not normal_ok
    self.log_budget = dict(hostBytes=sum(host), workOtherBytes=sum(work), cleanupBytes=sum(cleanup),
        cleanupReserveBytes=CLEANUP_LOG_RESERVE, cleanupEntered=self.cleanup_at is not None,
        hostOverflow=not host_ok, stickyFailure=self.log_budget_failed, custodyFailed=self.log_custody_failed,
        validationWithinCap=normal_ok and not self.log_budget_failed,
        cleanupWithinCap=not self.log_custody_failed and work_ok and cleanup_ok)
    # Only the exhausted host WORK log is excluded from cleanup accounting. It stays raw and failed.
    # Other/census caps, custody, fresh observations and the donor's ownership barriers still apply.
    return (not self.log_custody_failed and work_ok and cleanup_ok
            and (self.cleanup_at is not None or (host_ok and not self.log_budget_failed)))


def commands(owner, run, env, end):
    class HostCommands(owner.Commands):
        within_cap = host_within_cap

        def __init__(self, run, env, end):
            super().__init__(run, env, end)
            self.cleanup_at, self.log_budget = None, {}
            self.log_budget_failed = self.log_custody_failed = False

        def begin_cleanup(self, end):
            owner.require(self.cleanup_at is None, 'Cleanup allowance cannot be reset')
            self.within_cap()  # Latch any final work overflow before switching accounting, never forgive it.
            self.cleanup_at, self.end = len(self.tasks), min(self.end, end)

        def start(self, argv, label, seconds=30, end=None, extra=None, cleaning=False, launch_by=None):
            owner.require(not cleaning or self.cleanup_at is not None, 'Cleanup budget not entered')
            if self.cleanup_at is not None:
                # Existing post-build Mach-O evidence uses the donor's non-cleaning call defaults.
                # It still consumes this reserve; cleaning retains its original cancellation meaning.
                owner.require(label != 'host-build' and self.within_cap(), 'Reserved cleanup diagnostic budget exceeded')
            return super().start(argv, label, seconds=seconds, end=end, extra=extra,
                                 cleaning=cleaning, launch_by=launch_by)

        def output(self, task, cleaning=False):
            owner.require(self.within_cap(), 'Host work/cleanup diagnostic budget exceeded at join')
            if task['receipt']['label'] != 'host-build':
                return super().output(task, cleaning=cleaning)
            receipt = task['receipt']
            owner.require(receipt['argv'][0] == '/usr/bin/xcodebuild' and not task['leaf'], 'Wrong host log owner')
            owner.require(receipt['leaderReaped'] and receipt['groupQuiet'] and receipt['actualExit'] == 0
                    and receipt['ended'] < min(receipt['deadline'], self.end) and (cleaning or not owner.CANCELLED)
                    and not receipt['timedOut'] and not receipt['forced'] and not receipt['errors'],
                    'Child failed, cancelled or exceeded its cap: ' + receipt['label'])
            owner.require(cleaning or self.within_cap(), 'Diagnostic output cap exceeded at child exit')
            owner.require(task['log'].stat().st_size <= 8388608, 'Oversized host tool output')
            with task['log'].open('rb') as stream:
                data = stream.read(8388609)
            owner.require(len(data) <= 8388608, 'Host output grew after owned absence')
            receipt['normalJoin'] = True
            return data.decode('utf-8', errors='replace')

        def normal(self):
            self.within_cap()
            return not self.log_budget_failed and super().normal()

    return HostCommands(run, env, end)
