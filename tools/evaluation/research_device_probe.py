#!/usr/bin/env python3
"""Exercise only the debug probe's synthetic research plan on a connected device.

Fault injection changes the synthetic run's ledger, never the device clock or user plans.
Recovery introduces an explicitly reported 90-second process outage; it is not continuous inference.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

REMOTE = '/sdcard/Android/data/com.matrix.agent/files/verification/research-device.json'
COMPONENT = 'com.matrix.agent/.debug.MediaProbeReceiver'


class Probe:
    def __init__(self, serial: str, output: Path):
        self.adb = ['adb', '-s', serial]
        self.output = output
        output.mkdir(parents=True, exist_ok=True)

    def shell(self, *args):
        return subprocess.run(self.adb + ['shell', *args], check=True, capture_output=True, text=True).stdout

    def read(self):
        result = subprocess.run(self.adb + ['exec-out', 'cat', REMOTE], capture_output=True, text=True)
        return json.loads(result.stdout) if result.returncode == 0 and result.stdout.startswith('{') else {}

    def command(self, command):
        previous = self.read().get('lastObservedAt')
        self.shell('am', 'broadcast', '-n', COMPONENT, '--es', 'research_probe', command)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            report = self.read()
            if report.get('lastObservedAt') and report['lastObservedAt'] != previous:
                return report
            time.sleep(.4)
        raise TimeoutError(f'Debug command has not completed: {command}')

    def save(self, name, report):
        (self.output / f'{name}.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')

    def archive(self):
        report = self.read()
        if not report:
            return
        if report.get('synthetic') is not True:
            raise ValueError('Refusing to control a non-synthetic report')
        self.command('cleanup')
        target = REMOTE.replace('research-device.json', f'research-{report["scheduleId"]}-cleaned-{time.time_ns()}.json')
        self.shell('mv', REMOTE, target)

    def start(self, ready, timeout=50):
        self.archive()
        self.command('start_long')
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            report = self.command('status')
            if ready(report):
                return report
            time.sleep(.5)
        raise TimeoutError('Research did not reach the requested fault boundary')

    def terminal(self, report, timeout=240):
        deadline = time.monotonic() + timeout
        while not report.get('complete') and time.monotonic() < deadline:
            time.sleep(1)
            report = self.command('status')
        if not report.get('complete'):
            raise TimeoutError('Research did not terminate')
        return report

    def faults(self):
        rows = []
        for fault in ['cancel', 'revoke', 'expire', 'exhaust_active', 'exhaust_models']:
            before = self.start(lambda r: r.get('state') == 2 and bool(r.get('steps')))
            start = time.monotonic()
            injection = self.command(fault)
            terminal = self.terminal(injection, 100)
            latency = round((time.monotonic() - start) * 1000)
            self.save(f'research-fault-{fault}', dict(schemaVersion=1, synthetic=True, fault=fault,
                      observationLatencyUpperBoundMillis=latency, before=before, injection=injection, terminal=terminal))
            if terminal['deliveryStatus'] == 2 or (fault == 'exhaust_models' and terminal['modelCalls'] != 40):
                raise AssertionError(f'Fault boundary failed: {fault}')
            row = dict(fault=fault, state=terminal['state'], reason=terminal['reason'], observedWithinMillis=latency,
                       modelCalls=terminal['modelCalls'], deliveryStatus=terminal['deliveryStatus'])
            rows.append(row)
            print(json.dumps(row), flush=True)
            self.archive()
        self.save('research-fault-summary', rows)

    def recovery(self):
        def ready(report):
            steps = report.get('steps', [])
            sources = sum(s['state'] == 3 for s in steps if s['id'] in ['arxiv', 'crossref', 'wikipedia'])
            return sources >= 2 and any(s['state'] == 2 for s in steps if s['id'].startswith('review_'))

        start = time.monotonic()
        before = self.start(ready)
        self.save('research-long-before-restart', before)
        self.shell('am', 'force-stop', 'com.matrix.agent.launcher')
        self.shell('am', 'force-stop', 'com.matrix.agent')
        print('Stopped Host with two source checkpoints and a read-only review in flight', flush=True)
        for elapsed in range(1, 91):
            time.sleep(1)
            running = subprocess.run(self.adb + ['shell', 'pidof', 'com.matrix.agent'], capture_output=True, text=True)
            if running.stdout.strip():
                raise RuntimeError('Host restarted automatically; this is not a controlled 90-second outage')
            if elapsed % 30 == 0:
                print(f'Verified process outage: {elapsed} seconds', flush=True)
        recovered = self.command('recover')
        self.save('research-long-after-restart', recovered)
        terminal = self.terminal(recovered)
        self.save('research-long-recovery-result', dict(schemaVersion=1, controlledProcessDowntimeSeconds=90,
                  wallSecondsFromStartCommand=round(time.monotonic()-start, 3),
                  scope='real source APIs and real model, with a 90-second process outage; not uninterrupted inference', terminal=terminal))
        print(json.dumps({k: terminal.get(k) for k in ['state', 'reason', 'activeMillis', 'deliveryStatus']}), flush=True)
        self.archive()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--scenario', choices=['faults', 'recovery'], required=True)
    args = parser.parse_args()
    probe = Probe(args.serial, args.output)
    try:
        getattr(probe, args.scenario)()
    except Exception:
        # Best effort cancellation affects only this probe's synthetic plan; preserve the report.
        try:
            if probe.read().get('synthetic') is True:
                probe.command('cancel')
        except Exception:
            pass
        raise


if __name__ == '__main__':
    main()
