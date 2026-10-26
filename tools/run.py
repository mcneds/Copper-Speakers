"""Launch prebuilt, isolated runtimes. Never compiles while Minecraft is running."""
import argparse
import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import time
import shlex
import re

ROOT = Path(__file__).resolve().parents[1]

def prepare(mode):
    work = ROOT / "run" / mode
    work.mkdir(parents=True, exist_ok=True)
    if mode in ("server", "smoke", "custom", "generic", "distance", "late", "wire", "plate", "notes", "sections", "tails"):
        (work / "eula.txt").write_text("eula=true\n")
    if mode == "server" and not (work / "server.properties").exists():
        (work / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=28901\nonline-mode=false\nenforce-secure-profile=false\n"
            "enable-rcon=true\nrcon.port=28902\nrcon.password=copper-local-development\nwhite-list=false\n"
            "gamemode=creative\nforce-gamemode=true\nspawn-protection=0\nview-distance=6\nsimulation-distance=6\n"
            "level-seed=1\nmotd=Copper Speakers isolated development\n")
    if mode in ("smoke", "custom", "generic", "distance", "late", "wire", "plate", "notes", "sections", "tails"):
        config = work / "config/polymer/auto-host.json"
        config.parent.mkdir(parents=True, exist_ok=True)
        # Audio observation uses local test assets. Vanilla pack acceptance is tested separately.
        config.write_text(json.dumps({"enabled": False}))
    if mode == "vanilla":
        (work / "options.txt").write_text(
            'version:5100\nrenderDistance:6\nsimulationDistance:6\nguiScale:2\n'
            'onboardAccessibility:false\nstartedCleanly:true\nskipMultiplayerWarning:true\n'
            'soundCategory_master:0.3\nsoundCategory_records:0.08\nsoundCategory_music:0.0\n')
        # Saved server explicitly accepts the required local test pack, using vanilla's own NBT setting.
        import struct
        def string(value):
            data = value.encode(); return struct.pack(">H", len(data)) + data
        entry = b"".join(b"\x08" + string(k) + string(v) for k,v in
                          [("name","Copper Speakers local"),("ip","127.0.0.1:28901")])
        entry += b"\x01" + string("acceptTextures") + b"\x01\x00"
        nbt = b"\x0a\x00\x00\x09" + string("servers") + b"\x0a" + struct.pack(">i",1) + entry + b"\x00"
        (work / "servers.dat").write_bytes(nbt)
    return work

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["client","server","smoke","custom","generic","distance","late","wire","plate","notes","sections","tails","vanilla"])
    parser.add_argument("--name", required=True, help="New attempt name; existing reports are never overwritten")
    parser.add_argument("--java", type=Path)
    parser.add_argument("--heap", help="Explicit maximum heap, e.g. 2G")
    parser.add_argument("--processors", type=int, help="Explicit JVM processor count; recorded with results")
    parser.add_argument("--diagnostic", action="store_true", help="Limit JIT tier/processor count for startup-crash diagnosis; never for timing measurements")
    parser.add_argument("--packaged", action="store_true", help="Run the dedicated server with the release jar instead of main class/resource directories")
    args = parser.parse_args()
    if args.heap and not re.fullmatch(r"[1-9][0-9]*[MG]", args.heap):
        parser.error("Heap must be a positive size such as 2G or 1024M")
    if args.processors is not None and not 1 <= args.processors <= (os.cpu_count() or 1):
        parser.error("Processor count must be between 1 and the machine's logical processor count")
    if args.processors is not None and args.diagnostic:
        parser.error("--diagnostic already fixes the processor count at 4")
    if args.diagnostic and args.mode in ("smoke", "sections"):
        parser.error("Diagnostic launches must stay separate from timing measurements")
    if args.packaged and args.mode != "server":
        parser.error("--packaged applies only to the dedicated server")
    if not args.name.replace("-","").replace("_","").isalnum():
        parser.error("Use letters, digits, hyphens and underscores for the attempt name")
    result = ROOT / "build/validation" / args.name
    result.mkdir(parents=True, exist_ok=False)
    argname = {"client":"runClient", "server":"runServer", "smoke":"runClientGameTest", "custom":"runClientGameTest", "generic":"runClientGameTest", "distance":"runClientGameTest", "late":"runClientGameTest", "wire":"runClientGameTest", "plate":"runClientGameTest", "notes":"runClientGameTest", "sections":"runClientGameTest", "tails":"runClientGameTest", "vanilla":"vanilla"}[args.mode]
    argfile = ROOT / "build" / (argname + ".args")
    if not argfile.exists():
        parser.error("Run gradlew prepareRuns first, with all clients stopped")
    if args.packaged:
        values = shlex.split(argfile.read_text())
        cp = values.index("-classpath") + 1
        removed = {(ROOT / "build/classes/java/main").as_posix().lower(),
                   (ROOT / "build/resources/main").as_posix().lower()}
        jars = [p for p in values[cp].split(os.pathsep) if p.replace("\\","/").lower() not in removed]
        release_path = ROOT / "build/release-jar.txt"
        if not release_path.exists():
            parser.error("Run gradlew prepareRuns to identify the current release jar")
        mod = Path(release_path.read_text().strip())
        if not mod.exists():
            parser.error("Build the release jar first")
        jars.append(mod.as_posix())
        values[cp] = os.pathsep.join(jars)
        argfile = result / "packaged-server.args"
        argfile.write_text("\n".join('"' + v.replace("\\", "/") + '"' for v in values) + "\n")
    runtime = args.java or ROOT / ".runtime/zulu25.38.107-ca-jre25.0.5-win_x64/bin/java.exe"
    if not runtime.exists():
        parser.error("Run tools/install-runtime.ps1 or pass --java")
    work = prepare(args.mode)
    lock = ROOT / "build/runtime-locks" / f"{os.getpid()}.json"
    lock.parent.mkdir(parents=True, exist_ok=True)
    lock.write_text(json.dumps({"pid":os.getpid(), "mode":args.mode, "attempt":args.name}))
    env = os.environ.copy()
    env["COPPER_SMOKE_OUTPUT"] = str(result / "audio.json")
    if args.mode == "tails":
        env["COPPER_TAILS_FUNCTIONAL_ONLY"] = str(args.diagnostic).lower()
    if args.mode in ("custom", "generic", "distance", "late", "wire", "plate", "notes", "sections", "tails"):
        env["COPPER_CLIENT_TEST"] = args.mode
        env["COPPER_" + args.mode.upper() + "_OUTPUT"] = str(result / (args.mode + ".json"))
    else:
        env.pop("COPPER_CLIENT_TEST", None)
    flags = ["-XX:TieredStopAtLevel=1", "-XX:ActiveProcessorCount=4"] if args.diagnostic else []
    runtime_flags = ["-Xmx" + args.heap] if args.heap else ["-Xmx1G"] if args.mode in ("custom", "generic", "notes") else []
    if args.processors is not None:
        runtime_flags.append("-XX:ActiveProcessorCount=" + str(args.processors))
    status = {"mode":args.mode, "started":time.time(), "status":"incomplete",
              "diagnosticFlags":flags, "argumentFileSha256":hashlib.sha256(argfile.read_bytes()).hexdigest(),
              "runtimeJvmFlags":runtime_flags,
              "preparedJvmFlags":[s for s in shlex.split(argfile.read_text()) if s.startswith("-X")],
              "audioBenchmarkSettings": {k: env[k] for k in ("COPPER_AUDIO_SOURCES", "COPPER_AUDIO_ROUNDS", "COPPER_AUDIO_SAMPLE_TICKS") if k in env}}
    (result / "launch.json").write_text(json.dumps(status, indent=2))
    try:
        with (result / "stdout.log").open("w", encoding="utf-8") as out, (result / "stderr.log").open("w", encoding="utf-8") as err:
            process = subprocess.Popen([str(runtime), *runtime_flags, *flags, "@" + str(argfile)], cwd=work, env=env,
                    stdout=out, stderr=err, creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
            print(f"{args.mode} PID {process.pid}; logs: {result}", flush=True)
            status["pid"] = process.pid
            (result / "launch.json").write_text(json.dumps(status, indent=2))
            code = process.wait()
        status.update(exitCode=code, finished=time.time(),
                      status="exited" if code == 0 else "failed-launch-or-test")
        if args.mode in ("smoke", "custom"):
            audio = result / "audio.json"
            if code != 0 or not audio.exists() or json.loads(audio.read_text())["status"] != "complete":
                status["status"] = "invalid-measurement" if args.mode == "smoke" else "failed-launch-or-test"
            else:
                status["status"] = "complete"
        elif args.mode in ("generic", "distance", "late", "wire", "plate", "notes", "sections", "tails"):
            report = result / (args.mode + ".json")
            status["status"] = "complete" if code == 0 and report.exists() and json.loads(report.read_text())["status"] == "complete" else "failed-launch-or-test"
        (result / "launch.json").write_text(json.dumps(status, indent=2))
        print(json.dumps(status), flush=True)
        return 0 if code == 0 and status["status"] not in ("invalid-measurement", "failed-launch-or-test") else 1
    finally:
        lock.unlink(missing_ok=True)

if __name__ == "__main__":
    sys.exit(main())
