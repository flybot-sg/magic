#!/usr/bin/env bash
# Evidence pack for magic-unity/Editor/NostrandHost/NostrandHost.cs: nostrand
# hosted in a long-lived AppDomain instead of a fresh `nos` process. What each
# probe asserts is in README.md here.
#
# It links the shipped Nostrand.dll with -r: rather than compiling the Editor
# sources, because the Unity-facing half (Editor/NostrandHost/Unity) references
# UnityEditor. The Unity-free half, NostrandHost.cs, is compiled from source --
# it is the thing under test.
#
# Requirements: csc (Roslyn), mono. No Unity, no JVM: the runtime is the DLL set
# this package ships, which is tracked, so its absence is a failure not a skip.
# The fixture is local and deps-free on purpose; a deps.edn would put the
# network on the path of a regression test.
# Run from the repo root as `bb nostrand-probes`, or directly.
set -euo pipefail
cd "$(dirname "$0")"

MAGIC_DIR="$PWD/../../Runtime/magic"
COMPILER_DIR="$PWD/../../Editor/Compiler"
HOST_SRC="$PWD/../../Editor/NostrandHost/NostrandHost.cs"
FIXTURE="$PWD/fixture"

for f in "$MAGIC_DIR/Clojure.dll" "$MAGIC_DIR/Magic.Runtime.dll" \
         "$COMPILER_DIR/Nostrand.dll" "$COMPILER_DIR/nostrand.tasks.clj.dll"; do
  if [ ! -e "$f" ]; then
    echo "FAIL: $f is missing; run bb build."
    exit 1
  fi
done
echo "runtime under test: $MAGIC_DIR/Clojure.dll"
echo "compiler under test: $COMPILER_DIR ($(ls "$COMPILER_DIR"/*.clj.dll | wc -l | tr -d ' ') clj.dll)"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp" "$FIXTURE/out"' EXIT

# Both shipped directories on the assembly path: nostrand.core seeds
# -assembly-path from MONO_PATH, and it is also how mono resolves Clojure.dll,
# Magic.Runtime.dll and Nostrand.dll for the probe executables.
export MONO_PATH="$MAGIC_DIR:$COMPILER_DIR"
REFS=(-r:"$MAGIC_DIR/Clojure.dll" -r:"$MAGIC_DIR/Magic.Runtime.dll" -r:"$COMPILER_DIR/Nostrand.dll")

echo
echo "=== building the probes against the real NostrandHost.cs ==="
csc -nologo -out:"$tmp/NostrandHostProbe.exe" "${REFS[@]}" NostrandHostProbe.cs "$HOST_SRC"
csc -nologo -out:"$tmp/LoadEmittedDll.exe" "${REFS[@]}" LoadEmittedDll.cs

host_ok=0
if mono "$tmp/NostrandHostProbe.exe" "$MAGIC_DIR" "$COMPILER_DIR" "$FIXTURE" "$FIXTURE/out"; then
  host_ok=1
fi

echo "=== Probe 8: load the emitted DLLs in a second mono process ==="
# Probe 7 edited the dependency between two compiles in one domain and restored
# the source afterwards, so B-V2 exists only in what was emitted. A fresh
# process is the only place that distinction is visible.
second_ok=1
for target in probe.b/f probe.a/g; do
  got="$(mono "$tmp/LoadEmittedDll.exe" "$MAGIC_DIR" "$COMPILER_DIR" "$FIXTURE/out" "$target" | tail -1)"
  if [ "$got" = "B-V2" ]; then
    echo "[PASS] $target from the emitted DLL => $got"
  else
    echo "[FAIL] $target from the emitted DLL => $got (expected B-V2)"
    second_ok=0
  fi
done

echo
if [ "$host_ok" = 1 ] && [ "$second_ok" = 1 ]; then
  echo "RESULT: in-process nostrand green (Probes 1-8)."
  exit 0
else
  echo "RESULT: not green (host=$host_ok emitted=$second_ok)."
  exit 1
fi
