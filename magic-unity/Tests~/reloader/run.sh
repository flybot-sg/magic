#!/usr/bin/env bash
# Reloader evidence pack for magic-unity/Editor/Reload/{ClojureReloader,DebouncedFileWatcher}.cs.
# Probes 1-2 demonstrate the environment/design problems the reloader exists for
# (background-thread eval, duplicate events, torn state); Probes 3-7 assert the fixes:
# Probe 3 the debounce in DebouncedFileWatcher.cs (coalescing + rename-over delivery +
# gate), Probe 4 its Dispose/finalizer contract, Probe 5 ClojureReloader's no-finalizer
# + lifecycle null-safety plus logging-and-returning when constructed with no root,
# Probe 6 the watcher's behavioral contract
# (root dedupe, lost-update on a raced claim, the IsPending query), Probe 7
# ClojureReloader's eval path against the real ClojureCLR runtime (a save redefines a
# var, eval failure retires the retry budget, a read failure retries then gives up at
# exactly MaxReadAttempts, a fresh file event resets that budget so a write burst cannot
# starve a save, a successful retry retires its budget instead of re-evaluating forever,
# a due retry is dropped when a newer save is pending, a throwing onChanged cannot escape
# Poll, .cljc reader conditionals pick :cljr, a Dispose from inside onChanged stops the
# rest of the drain).
# Exit 0 = problems reproduced AND the fixes verified.
# The probes drive a shorter settle window through DebouncedFileWatcher's three-argument
# constructor, a test seam; the Editor uses the two-argument one (DefaultSettleMs, 200ms).
# Requirements: csc (Roslyn), mono. No Unity, no JVM: Probes 2, 5 and 7 run on the
# ClojureCLR runtime this package ships in Runtime/clojure-clr/ (tracked, so its absence
# is a failure, not a skip). Probe 5 only links Clojure.dll; Probes 2 and 7 boot the
# runtime, so they stage the whole directory (Microsoft.Dynamic/Scripting alongside
# Clojure.dll -- RT.DoInit Assembly.LoadFile()s its siblings).
# Run from the repo root as `bb reloader-probes`, or directly.
set -euo pipefail
cd "$(dirname "$0")"

EDITOR=../../Editor/Reload
CLR_DIR=../../Runtime/clojure-clr
CLJ_DLL="$CLR_DIR/Clojure.dll"
if [ ! -f "$CLJ_DLL" ]; then
  echo "FAIL: $CLJ_DLL is missing; the ClojureCLR runtime is tracked in this repo (bb sync-clojure-clr)."
  exit 1
fi
echo "runtime under test: $CLJ_DLL"

tmp="$(mktemp -d)"
trap 'pkill -f WatchProbe.exe 2>/dev/null || true; rm -rf "$tmp"' EXIT

# Probes 2 and 7 boot the runtime, which loads the DLLs sitting next to the executable.
stage_runtime() {
  cp "$CLR_DIR"/*.dll "$1/"
}

echo "=== Probe 1: duplicate Changed events per save + watcher thread identity ==="
csc -nologo -out:"$tmp/WatchProbe.exe" WatchProbe.cs
mkdir -p "$tmp/watch"
printf '(ns t)\n' > "$tmp/watch/probe.cljc"
mono "$tmp/WatchProbe.exe" "$tmp/watch" > "$tmp/probe-out.txt" 2>&1 &
disown
sleep 2

saves=5
for i in $(seq $saves); do
  # vim/Neovim classic save: rename original away, write a new file at the path
  mv "$tmp/watch/probe.cljc" "$tmp/watch/probe.cljc~"
  printf '(ns t)\n;; save %s\n' "$i" > "$tmp/watch/probe.cljc"
  rm "$tmp/watch/probe.cljc~"
  sleep 1
done
sleep 1
pkill -f WatchProbe.exe || true
sleep 0.3

events=$(grep -c 'reloader-cfg.*Changed' "$tmp/probe-out.txt" || true)
tids=$(grep 'reloader-cfg.*Changed' "$tmp/probe-out.txt" | sed 's/.*tid=\([0-9]*\).*/\1/' | sort -u | wc -l | tr -d ' ')
grep 'reloader-cfg' "$tmp/probe-out.txt" || true
echo "saves=$saves reloader-config-events=$events distinct-callback-threads=$tids (main=1)"

dup_ok=0
if [ "$events" -gt "$saves" ]; then
  echo "DUPLICATES-PROVEN: more events than saves — each save evals more than once"
  dup_ok=1
else
  echo "DUPLICATES-NOT-REPRODUCED"
fi

echo
echo "=== Probe 2: torn multi-var states under concurrent reload (on ClojureCLR) ==="
# The script is plain Clojure (load-string, future, atom); TornRaceHost boots the
# shipped ClojureCLR and load-files it, so the measurement is of the Editor's runtime.
mkdir -p "$tmp/torn"
csc -nologo -out:"$tmp/torn/TornRaceHost.exe" -r:"$CLJ_DLL" TornRaceHost.cs
stage_runtime "$tmp/torn"
torn_ok=0
if mono "$tmp/torn/TornRaceHost.exe" "$PWD/race_torn_reload.clj" | tee "$tmp/race-out.txt"; then
  grep -q 'TORN-STATE-PROVEN' "$tmp/race-out.txt" && torn_ok=1
fi

echo
echo "=== Probe 3: debounce coalescing + rename-over delivery ==="
# Compiles against the REAL DebouncedFileWatcher.cs (single source of truth): 5 vim
# saves -> 5 reloads (not ~10), and a rename-over save with a non-clj temp name is
# delivered exactly once. This one fails if the fix regresses.
csc -nologo -out:"$tmp/ReloaderQueueTest.exe" \
  ReloaderQueueTest.cs \
  "$EDITOR/DebouncedFileWatcher.cs"
fix_ok=0
if mono "$tmp/ReloaderQueueTest.exe"; then
  fix_ok=1
fi

echo
echo "=== Probe 4: Dispose/finalizer contract of DebouncedFileWatcher ==="
# No finalizer (reflection), Dispose releases the watcher + is idempotent, and AddRoot
# fails fast after Dispose (including under a concurrent AddRoot/Dispose race).
csc -nologo -out:"$tmp/DisposeTest.exe" \
  DisposeTest.cs \
  "$EDITOR/DebouncedFileWatcher.cs"
dispose_ok=0
if mono "$tmp/DisposeTest.exe"; then
  dispose_ok=1
fi

echo
echo "=== Probe 5: ClojureReloader no-finalizer + lifecycle null-safety ==="
# ClojureReloader references clojure.lang.Compiler, so it links Clojure.dll; nothing here
# boots the runtime. The runtime-state gate is the Magic.Unity.Editor.Reload asmdef's define
# constraint, which csc does not see; the source itself compiles in any state.
reloader_fin_ok=0
if csc -nologo -out:"$tmp/ReloaderFinalizerCheck.exe" -r:"$CLJ_DLL" \
    ReloaderFinalizerCheck.cs \
    "$EDITOR/ClojureReloader.cs" \
    "$EDITOR/DebouncedFileWatcher.cs"; then
  cp "$CLJ_DLL" "$tmp/"
  if mono "$tmp/ReloaderFinalizerCheck.exe"; then
    reloader_fin_ok=1
  fi
else
  echo "[BUILD] FAIL: Probe 5 sources did not compile against $CLJ_DLL"
fi

echo
echo "=== Probe 6: DebouncedFileWatcher contract — dedupe, lost-update, IsPending ==="
# Segment-aware root dedupe (several namespaces mapped to one directory), [S2], which
# races a re-mark against an in-flight drain to pin TakeSettled()'s claim as a
# compare-and-remove, and [P1], IsPending's mark-to-hand-out interval (what the
# reloader's retry-supersede check rides on).
csc -nologo -out:"$tmp/WatcherContractTest.exe" \
  WatcherContractTest.cs \
  "$EDITOR/DebouncedFileWatcher.cs"
contract_ok=0
if mono "$tmp/WatcherContractTest.exe"; then
  contract_ok=1
fi

echo
echo "=== Probe 7: ClojureReloader eval path — reload redefines, read-retry budget + its reset + supersede, callback containment, .cljc read-cond ==="
# The only regression probe that BOOTS the runtime (RT.Init) and evals real forms.
mkdir -p "$tmp/eval"
eval_ok=0
if csc -nologo -out:"$tmp/eval/ReloaderEvalTest.exe" -r:"$CLJ_DLL" \
    ReloaderEvalTest.cs \
    "$EDITOR/ClojureReloader.cs" \
    "$EDITOR/DebouncedFileWatcher.cs"; then
  stage_runtime "$tmp/eval"
  if mono "$tmp/eval/ReloaderEvalTest.exe"; then
    eval_ok=1
  fi
else
  echo "[BUILD] FAIL: Probe 7 sources did not compile against $CLJ_DLL"
fi

echo
if [ "$dup_ok" = 1 ] && [ "$torn_ok" = 1 ] && [ "$fix_ok" = 1 ] \
   && [ "$dispose_ok" = 1 ] && [ "$reloader_fin_ok" = 1 ] && [ "$contract_ok" = 1 ] \
   && [ "$eval_ok" = 1 ]; then
  echo "RESULT: problems reproduced (Probes 1-2) and fixes verified (Probes 3-7)."
  exit 0
else
  echo "RESULT: not fully green (dup=$dup_ok torn=$torn_ok fix=$fix_ok dispose=$dispose_ok reloader_fin=$reloader_fin_ok contract=$contract_ok eval=$eval_ok)."
  exit 1
fi
