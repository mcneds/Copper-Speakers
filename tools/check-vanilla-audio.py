"""Functional smoke check using an untouched Minecraft client and its OS audio meter.

Requires the isolated prepared server on port 28901, Windows, and pycaw installed
in build/audio-meter. Reads only the new client's audio session; records no audio.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import rcon

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--pretouch-stacks', action='store_true')
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('This audio-session check requires Windows')
    sys.path.insert(0, str(ROOT / 'build/audio-meter'))
    from pycaw.pycaw import AudioUtilities, IAudioMeterInformation
    import psutil
    out = ROOT / 'build/validation' / args.name
    assert not out.exists(), 'Use a new attempt name'
    result = {'status': 'incomplete', 'scope': 'Untouched publisher client, no Fabric or observer. Only this process audio-session peak is read. Functional audibility/range/stop check; no synchronization timing claim.'}
    for command in ['setblock 0 90 0 minecraft:jukebox', 'setblock 2 90 0 copper_speakers:speaker',
                    'item replace block 0 90 0 container.0 with minecraft:music_disc_cat']:
        rcon.command(command)
    launch = subprocess.Popen([sys.executable, str(ROOT / 'tools/run.py'), 'vanilla', '--name', args.name,
                '--heap', '2G', '--processors', '4', '--java', str(args.java),
                *(['--pretouch-stacks'] if args.pretouch_stacks else [])], cwd=ROOT,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    client = None
    try:
        deadline = time.monotonic() + 180
        while 'CopperTester' not in rcon.command('list'):
            if launch.poll() is not None or time.monotonic() > deadline:
                raise AssertionError('Untouched client did not join')
            time.sleep(.5)
        receipt = json.loads((out / 'launch.json').read_text())
        client = psutil.Process(receipt['pid'])
        assert client.ppid() == launch.pid
        rcon.command('tp CopperTester 256 91 -2')
        time.sleep(5)

        def measure(seconds):
            meters = [s._ctl.QueryInterface(IAudioMeterInformation)
                      for s in AudioUtilities.GetAllSessions() if s.ProcessId == client.pid]
            assert meters, 'No audio session for the untouched client'
            peaks = []
            until = time.monotonic() + seconds
            while time.monotonic() < until:
                peaks.append(max(m.GetPeakValue() for m in meters))
                time.sleep(.05)
            return max(peaks)

        result['outsidePeak'] = measure(2)
        rcon.command('tp CopperTester 0 91 -2')
        result['nearPeak'] = measure(15)
        rcon.command('setblock 0 90 0 minecraft:air')
        time.sleep(2)
        result['stoppedPeak'] = measure(2)
        assert result['outsidePeak'] < .000001
        assert result['nearPeak'] > .00001, 'Late arrival produced no audible record signal'
        assert result['stoppedPeak'] < .000001
        log = (ROOT / 'run/vanilla/logs/latest.log').read_text(errors='replace')
        assert 'FabricLoader' not in log
        result['status'] = 'complete'
    except BaseException as error:
        result.update(status='failed', failure=repr(error))
        raise
    finally:
        if client is not None and client.is_running():
            # Close only the window belonging to the exact child process we launched.
            callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)
            ctypes.windll.user32.PostMessageW.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_size_t, ctypes.c_ssize_t]
            ctypes.windll.user32.GetWindowThreadProcessId.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_ulong)]
            @callback_type
            def close_window(window, unused):
                owner = ctypes.c_ulong()
                ctypes.windll.user32.GetWindowThreadProcessId(window, ctypes.byref(owner))
                if owner.value == client.pid:
                    ctypes.windll.user32.PostMessageW(window, 0x0010, 0, 0)
                return True
            ctypes.windll.user32.EnumWindows(close_window, 0)
        try:
            launch.wait(timeout=20)
        except subprocess.TimeoutExpired:
            subprocess.run(['taskkill', '/PID', str(launch.pid), '/T', '/F'], stdout=subprocess.DEVNULL, check=False)
            launch.wait(timeout=10)
        out.mkdir(parents=True, exist_ok=True)
        (out / 'vanilla-audio.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
