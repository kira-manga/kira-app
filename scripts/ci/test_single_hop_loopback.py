"""Pure observer regression tests: no server, subprocess, real signal, trust or simulator."""
from contextlib import contextmanager
import importlib.util
import json
from pathlib import Path
import signal
import tempfile
import unittest
from unittest.mock import call, patch

SPEC = importlib.util.spec_from_file_location("single_hop_fixture", Path(__file__).with_name("single-hop-loopback.py"))
FIXTURE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FIXTURE)
PID = 2243


class SingleHopServerLifecycleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="kira-single-hop-observer-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.record(PID)

    def record(self, value):
        (self.root / "server-ready.json").write_text(json.dumps({"server_pid": value}))

    def owned(self, pid=PID):
        return f"{pid} S<s /usr/bin/python3 -I -B {FIXTURE.SCRIPT} serve {self.root}"

    @contextmanager
    def observations(self, rows):
        remaining, self.elapsed = list(rows), 0

        def observe(args, root, timeout):
            self.assertEqual(args, ["/bin/ps", "-ww", "-axo", "pid=,stat=,command="])
            self.assertEqual((root, timeout), (self.root, 10))
            value = remaining.pop(0) if len(remaining) > 1 else remaining[0]
            if isinstance(value, tuple):
                value, metadata = value
                self.record(metadata)
            return value

        def sleep(seconds):
            self.assertEqual(seconds, 0.1)
            self.elapsed += 5  # Virtual time only; the real helper's 15-second limit is unchanged.

        with patch.object(FIXTURE, "run", side_effect=observe) as observer, \
                patch.object(FIXTURE.os, "kill") as kill, \
                patch.object(FIXTURE.time, "monotonic", side_effect=lambda: self.elapsed), \
                patch.object(FIXTURE.time, "sleep", side_effect=sleep):
            yield observer, kill

    def test_initial_argv_mismatch_never_receives_a_signal(self):
        with self.observations([f"{PID} R<s (Python)"]) as (observer, kill):
            with self.assertRaises(AssertionError):
                FIXTURE.stop_server(self.root)
            kill.assert_not_called()
            self.assertEqual(observer.call_count, 1)

    def test_signalled_pid_argv_transition_waits_until_actual_absence(self):
        # The first state pair is the real Darwin observation; absent argv also remains live.
        for transition in (f"{PID} R<s (Python)", f"{PID} R<s"):
            with self.subTest(transition=transition), self.observations([self.owned(), transition, ""]) as (observer, kill):
                FIXTURE.stop_server(self.root)
                kill.assert_called_once_with(PID, signal.SIGTERM)
                self.assertEqual(observer.call_count, 3)
                self.assertEqual(self.elapsed, 5)

    def test_persistent_argv_mismatch_refuses_without_sigkill(self):
        with self.observations([self.owned(), f"{PID} R<s (Python)"]) as (observer, kill):
            with self.assertRaises(AssertionError):
                FIXTURE.stop_server(self.root)
            kill.assert_called_once_with(PID, signal.SIGTERM)
            self.assertEqual(self.elapsed, 15)
            self.assertEqual(observer.call_count, 5)  # Strict identity is checked again before escalation.

    def test_normal_exact_owned_escalation_then_absence(self):
        with self.observations([self.owned()] * 5 + [""]) as (observer, kill):
            FIXTURE.stop_server(self.root)
            self.assertEqual(kill.call_args_list, [call(PID, signal.SIGTERM), call(PID, signal.SIGKILL)])
            self.assertEqual(observer.call_count, 6)
            self.assertEqual(self.elapsed, 15)

    def test_changed_pid_is_refused_during_wait_and_before_escalation(self):
        new = PID + 1
        cases = ([self.owned(), self.owned(new)],
                 [self.owned(), (self.owned(new), new)],
                 [self.owned()] * 4 + [(self.owned(new), new)])
        for rows in cases:
            with self.subTest(rows=len(rows)):
                self.record(PID)
                with self.observations(rows) as (_, kill):
                    with self.assertRaises(AssertionError):
                        FIXTURE.stop_server(self.root)
                    kill.assert_called_once_with(PID, signal.SIGTERM)

    def test_multiple_matches_and_malformed_recorded_pid_refuse(self):
        with self.observations([self.owned() + "\n" + self.owned(PID + 1)]) as (_, kill):
            with self.assertRaises(AssertionError):
                FIXTURE.stop_server(self.root)
            kill.assert_not_called()
        for value in (True, 0, -1, "2243", None):
            with self.subTest(metadata=value):
                self.record(value)
                with self.observations([self.owned()]) as (_, kill):
                    with self.assertRaises(AssertionError):
                        FIXTURE.stop_server(self.root)
                    kill.assert_not_called()


if __name__ == "__main__":
    unittest.main()
