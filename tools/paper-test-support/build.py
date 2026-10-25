#!/usr/bin/env python3
"""Build local test instrumentation against the already downloaded Paper libraries."""
import os
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parents[2]
server = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root / "run-paper-26.2"
java = Path("/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin")
source = Path(__file__).resolve().parent
build = source / "build"
build.mkdir(exist_ok=True)
jars = list((server / "libraries").rglob("*.jar"))
if not jars:
    raise SystemExit("Start Paper once to populate its libraries directory")
subprocess.run([str(java / "javac"), "-cp", os.pathsep.join(map(str, jars)), "-d", str(build), str(source / "src/dev/minecontrol/audit/TestAudit.java")], check=True)
subprocess.run([str(java / "jar"), "cf", str(server / "plugins/MineControlTestAudit-1.0.0.jar"), "-C", str(build), ".", "-C", str(source), "plugin.yml"], check=True)
