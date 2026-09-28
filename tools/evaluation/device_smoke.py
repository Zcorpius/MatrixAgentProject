#!/usr/bin/env python3
"""Explicit, non-instrumentation device smoke samples. Requires the debug APK and adb shell DUMP.

Uses real configured inference and real app searches, with negative confirmation replies.
No result-selection/playback write or schedule creation is requested. Logs contain only the
probe's structural diagnostics. The original stay-awake setting is restored even on failure.
"""
import argparse
import json
import re
import shlex
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    adb = ['adb', '-s', options.serial]

    def command(*args, timeout=15):
        return subprocess.check_output(adb + list(args), text=True, timeout=timeout).strip()

    def shell(*args):
        return command('shell', shlex.join(args))

    options.output.mkdir(parents=True, exist_ok=True)
    marker = shell('date', '+%m-%d %H:%M:%S.000')
    report = {'mode': 'DEVICE_REAL_APPS', 'complete': False, 'startedAtDevice': marker,
              'device': shell('getprop', 'ro.product.model'),
              'android': shell('getprop', 'ro.build.version.release'), 'samples': []}
    installed_apk = next(line.split(':', 1)[1] for line in shell('pm', 'path', 'com.matrix.agent').splitlines()
                         if line.endswith('/base.apk'))
    report['apkSha256'] = shell('sha256sum', installed_apk).split()[0]
    original_stay_awake = shell('settings', 'get', 'global', 'stay_on_while_plugged_in')

    def save():
        temporary = options.output / 'device-report.json.tmp'
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        temporary.replace(options.output / 'device-report.json')

    def logs():
        return command('logcat', '-d', '-T', marker, '-v', 'threadtime', '-s', 'MatrixMediaProbe:I', '*:S').splitlines()

    def probe(identifier, extras, required, forbidden=()):
        prior = set(logs())
        shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        shell('wm', 'dismiss-keyguard')
        args = ['am', 'broadcast', '-n', 'com.matrix.agent/.debug.MediaProbeReceiver']
        for key, value in extras.items():
            args += ['--es', key, value]
        shell(*args)
        deadline = time.monotonic() + 100
        finished = False
        lines = []
        while time.monotonic() < deadline:
            lines = [line for line in logs() if line not in prior]
            starts = [match.group(1) for line in lines if (match := re.search(r'probe started id=(\d+)', line))]
            if starts and any(f'probe finished id={starts[0]}' in line for line in lines):
                finished = True
                break
            time.sleep(1)
        text = '\n'.join(lines)
        passed = finished and all(re.search(pattern, text) for pattern in required) and not any(re.search(pattern, text) for pattern in forbidden)
        report['samples'].append({'id': identifier, 'passed': bool(passed),
                                  'completed': finished, 'requiredPatterns': required, 'forbiddenPatterns': list(forbidden), 'evidence': lines})
        save()
        print(f'{identifier}: {"PASS" if passed else "FAIL"}', flush=True)
        if not finished:
            raise RuntimeError('probe did not finish; stop before queuing more device work')
        return bool(passed)

    save()
    try:
        shell('svc', 'power', 'stayon', 'usb')
        probe('qq-playback-before', {'capability': 'media.qqmusic.get_state'},
              [r'cap=media\.qqmusic\.get_state status=SUCCESS', r'playback=(PAUSED|PLAYING|STOPPED)'])
        before = '\n'.join(report['samples'][-1]['evidence'])
        match = re.search(r'playback=(PAUSED|PLAYING|STOPPED)', before)
        if not match:
            raise RuntimeError('an active QQ session is required for playback preservation validation')
        original_playback = match.group(1)
        forbidden_writes = [r'engineSearch tool=(media\.qqmusic\.(play|play_search_result|pause|next|previous|seek|open_app)|media\.bilibili\.(resume|pause|open_video|open_search_result)|schedule\.(create|control)) status=']
        probe('qq-selection-first-policy', {'capability': 'media.qqmusic.play',
              'request_text': 'QQ音乐请播放林舟的晨光，先确认选曲'},
              [r'cap=media\.qqmusic\.play policy=DENIED type=CAPABILITY'])
        probe('bili-selection-first-policy', {'capability': 'media.bilibili.resume',
              'request_text': 'B站请播放星海旅行，先搜索标题'},
              [r'cap=media\.bilibili\.resume policy=DENIED type=CAPABILITY'])
        probe('qq-optional-empty-title', {'capability': 'media.qqmusic.search_songs',
              'request_text': 'QQ音乐搜索李健的歌', 'query': '李健', 'artist': '李健', 'title': ''},
              [r'cap=media\.qqmusic\.search_songs status=SUCCESS verified=true', r'candidateCount=[1-8]'])
        qq_ready = probe('model-qq-search-confirmation', {'engine_search_text': 'QQ音乐播放李健的贝加尔湖畔'},
              [r'engineSearch state=SUCCEEDED .*asksConfirmation=true',
               r'engineSearch tool=media\.qqmusic\.search_songs status=SUCCESS'], forbidden_writes)
        if qq_ready:
            probe('model-qq-negative-confirmation', {'engine_reject_text': '不要'},
                  [r'engineReject state=SUCCEEDED .*toolCalls=0 answerReady=true'])
        bili_ready = probe('model-cross-app-search', {'engine_search_text': '改用B站搜索星际穿越'},
              [r'engineSearch state=SUCCEEDED', r'engineSearch tool=media\.bilibili\.search_videos status=SUCCESS'], forbidden_writes)
        if bili_ready:
            probe('model-bili-negative-confirmation', {'engine_reject_text': '不要'},
                  [r'engineReject state=SUCCEEDED .*toolCalls=0 answerReady=true'])
        probe('qq-playback-readback', {'capability': 'media.qqmusic.get_state'},
              [r'cap=media\.qqmusic\.get_state status=SUCCESS', r'playback=' + re.escape(original_playback)])
        # Finished is logged just before service shutdown; allow its main-thread callback to run.
        deadline = time.monotonic() + 5
        service_running = True
        while time.monotonic() < deadline:
            status = shell('dumpsys', 'activity', 'services', 'com.matrix.agent.debug.MediaProbeService')
            service_running = 'ServiceRecord{' in status
            if not service_running:
                break
            time.sleep(0.25)
        report['serviceStopped'] = not service_running
        report['complete'] = True
        report['passed'] = all(sample['passed'] for sample in report['samples']) and not service_running
    finally:
        if original_stay_awake == 'null':
            shell('settings', 'delete', 'global', 'stay_on_while_plugged_in')
        else:
            shell('settings', 'put', 'global', 'stay_on_while_plugged_in', original_stay_awake)
        report['stayAwakeSettingRestored'] = shell('settings', 'get', 'global', 'stay_on_while_plugged_in') == original_stay_awake
        save()
    if not report.get('passed'):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
