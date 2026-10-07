#!/usr/bin/env python3
"""Measure an isolated application JVM; keep generated data outside Git.

Requires Java 21 (JAVA_HOME), Python 3 and an already built executable JAR.
Example: python3 scripts/measure_memory.py --output /tmp/whatap-memory-run
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import socket
import statistics
import subprocess
import threading
import time
import urllib.request


CASES = [
    ("empty", 0, 0, 0),
    ("normal-10k", 10_000, 0, 0),
    ("normal-100k", 100_000, 0, 0),
    ("normal-1m", 1_000_000, 0, 0),
    ("invalid-10pct-100k", 100_000, 10, 0),
    ("long-name-100k", 100_000, 0, 512),
]


def latency_summary(values):
    ordered = sorted(values)
    if not ordered:
        return {"count": 0}
    return {
        "count": len(values),
        "median_ms": round(statistics.median(values), 3),
        "p95_ms": round(ordered[max(0, int(len(ordered) * 0.95 + 0.9999) - 1)], 3),
        "max_ms": round(max(values), 3),
    }


def run_case(case, args, java_home, jar):
    name, rows, invalid_every, extra_chars = case
    folder = args.output / name
    folder.mkdir()
    input_file = folder / "user.txt"
    with input_file.open("w", encoding="utf-8") as stream:
        for number in range(1, rows + 1):
            salary = "bad" if invalid_every and number % invalid_every == 0 else "30_000_000"
            stream.write(f"{number},사용자{number}{'가' * extra_chars},30,M,{salary}\n")
    failed = rows // invalid_every if invalid_every else 0
    loaded = rows - failed
    # Ask the OS for an unused port. The readiness check also requires this JVM's load log.
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base_url = f"http://127.0.0.1:{port}"
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, method="GET"):
        start = time.perf_counter()
        with opener.open(urllib.request.Request(base_url + path, method=method), timeout=90) as response:
            body = json.load(response)
        return body, (time.perf_counter() - start) * 1000

    def query():
        body, elapsed = request("/users?page=0&size=20")
        assert body["totalElements"] == loaded, body
        assert len(body["content"]) == min(20, loaded), body
        return elapsed

    def reload():
        body, elapsed = request("/users/reload", "POST")
        assert body == {"loadedCount": loaded, "failedCount": failed}, body
        return elapsed

    command = [
        str(java_home / "bin/java"), "-Xms256m", "-Xmx512m", "-XX:+UseG1GC",
        "-XX:+ExitOnOutOfMemoryError",
        f"-Xlog:gc*:file={folder / 'gc.log'}:time,uptime,level,tags",
        f"-XX:StartFlightRecording=name=loadtest,settings=profile,maxsize=128m,dumponexit=true,filename={folder / 'load.jfr'}",
        "-jar", str(jar), f"--server.port={port}", "--server.address=127.0.0.1",
    ]
    result = {"case": name, "rows": rows, "loaded": loaded, "failed": failed,
              "file_bytes": input_file.stat().st_size, "command": command}
    stop = threading.Event()
    query_thread = None
    sampler = None
    launched = time.perf_counter()
    with (folder / "application.log").open("w") as app_log, (folder / "jstat.txt").open("w") as stats:
        process = subprocess.Popen(command, stdout=app_log, stderr=subprocess.STDOUT,
                                   env={**os.environ, "USER_FILE": str(input_file)})
        try:
            sampler = subprocess.Popen([str(java_home / "bin/jstat"), "-gc", str(process.pid), "100"],
                                       stdout=stats, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 120
            marker = f"User file loaded users={loaded} failures={failed}"
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"Application exited: {process.returncode}")
                if time.monotonic() > deadline:
                    raise TimeoutError("Application startup exceeded 120 seconds")
                if marker in (folder / "application.log").read_text():
                    try:
                        query()
                        break
                    except (OSError, ValueError, AssertionError):
                        pass
                time.sleep(0.1)
            result["startup_to_ready_ms"] = round((time.perf_counter() - launched) * 1000, 3)
            # Exclude one reload and 50 queries from request timing summaries.
            reload()
            for _ in range(50):
                query()
            result["query_baseline"] = latency_summary([query() for _ in range(100)])
            result["reload_idle_ms"] = [round(reload(), 3) for _ in range(3)]
            query_times, query_errors = [], []
            ready = threading.Event()

            def query_worker():
                ready.set()
                while not stop.is_set():
                    try:
                        query_times.append(query())
                    except Exception as error:
                        query_errors.append(str(error))
                    stop.wait(0.05)

            query_thread = threading.Thread(target=query_worker, daemon=True)
            query_thread.start()
            ready.wait()
            result["reload_with_queries_ms"] = [round(reload(), 3) for _ in range(3)]
            stop.set()
            query_thread.join(timeout=95)
            result["query_during_reloads"] = latency_summary(query_times)
            result["query_errors"] = query_errors
            assert not query_errors, query_errors
            result["status"] = "passed"
        except Exception as error:
            result["status"] = "failed"
            result["error"] = f"{type(error).__name__}: {error}"
        finally:
            stop.set()
            if process.poll() is None:
                process.send_signal(signal.SIGTERM)
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            if query_thread:
                query_thread.join(timeout=95)
            if sampler and sampler.poll() is None:
                sampler.terminate()
                sampler.wait(timeout=10)
    result["elapsed_seconds"] = round(time.perf_counter() - launched, 3)
    # jstat values are KiB, sampled every 100ms; not an exact instantaneous peak.
    samples, header = [], None
    for line in (folder / "jstat.txt").read_text().splitlines():
        fields = line.split()
        if "S0U" in fields:
            header = fields
        elif header and len(fields) == len(header):
            try:
                record = dict(zip(header, fields))
                samples.append(sum(float(record[key]) for key in ["S0U", "S1U", "EU", "OU"]) / 1024)
            except ValueError:
                continue
    result["heap_sample_count"] = len(samples)
    result["sampled_heap_peak_mib"] = round(max(samples), 3) if samples else None
    result["last_sample_heap_mib"] = round(samples[-1], 3) if samples else None
    gc_log = (folder / "gc.log").read_text() if (folder / "gc.log").exists() else ""
    pauses = [float(value) for value in re.findall(r"Pause[^\n]* ([\d.]+)ms$", gc_log, re.M)]
    result["gc_pauses"] = latency_summary(pauses)
    result["full_gc_pause_count"] = len(re.findall(r"Pause Full[^\n]*ms$", gc_log, re.M))
    recording = folder / "load.jfr"
    if recording.exists():
        printed = subprocess.run([str(java_home / "bin/jfr"), "print", "--json", "--events",
                                  "jdk.GCHeapSummary", str(recording)], capture_output=True, text=True)
        if printed.returncode == 0:
            events = json.loads(printed.stdout)["recording"]["events"]
            after = [event["values"]["heapUsed"] / 1024**2 for event in events
                     if event["values"].get("when") == "After GC"]
            result["heap_after_gc_mib"] = [round(value, 3) for value in after]
        else:
            result["jfr_error"] = printed.stderr
    (folder / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="New directory for generated data and recordings")
    parser.add_argument("--cases", nargs="+", choices=[case[0] for case in CASES])
    args = parser.parse_args()
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[1]
    jars = [path for path in (root / "build/libs").glob("*.jar") if not path.name.endswith("-plain.jar")]
    if len(jars) != 1:
        raise SystemExit("Build exactly one executable JAR first: ./gradlew clean build")
    java_home = Path(os.environ["JAVA_HOME"])
    version = subprocess.run([str(java_home / "bin/java"), "-version"], capture_output=True, text=True)
    environment = {"java": version.stderr, "os": platform.platform(), "cpu_count": os.cpu_count(),
                   "jar_sha256": hashlib.sha256(jars[0].read_bytes()).hexdigest(),
                   "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                   "jvm_environment_options": {key: os.environ.get(key) for key in
                                               ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]}}
    (args.output / "environment.json").write_text(json.dumps(environment, ensure_ascii=False, indent=2))
    results = []
    for case in CASES:
        if args.cases and case[0] not in args.cases:
            continue
        print(f"START {case[0]}", flush=True)
        result = run_case(case, args, java_home, jars[0].resolve())
        results.append(result)
        (args.output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2))
        print(json.dumps({key: result.get(key) for key in
                          ["case", "status", "error", "sampled_heap_peak_mib", "reload_idle_ms"]}), flush=True)
    if any(result["status"] != "passed" for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
