"""List Codex runs recorded by delegate_codex.sh."""

import argparse
import json
from pathlib import Path
import re


DEFAULT_RUNS_DIR = Path(__file__).resolve().parents[1] / "work" / "codex"
RUN_FILENAME = re.compile(r"([0-9]{8}-[0-9]{6})\.(?:last\.md|log)")


def read_runs(directory):
    timestamps = set()
    if directory.exists():
        for path in directory.iterdir():
            match = RUN_FILENAME.fullmatch(path.name)
            if match and path.is_file():
                timestamps.add(match.group(1))

    runs = []
    for timestamp in sorted(timestamps, reverse=True):
        report = directory / f"{timestamp}.last.md"
        summary = ""
        if report.is_file():
            with report.open(encoding="utf-8", errors="replace") as handle:
                summary = handle.readline(80).rstrip("\r\n")
        runs.append({"timestamp": timestamp, "summary": summary})
    return runs


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dir", type=Path, default=DEFAULT_RUNS_DIR, help="run directory")
    parser.add_argument("--json", action="store_true", help="output JSON")
    args = parser.parse_args(argv)

    runs = read_runs(args.dir)
    if args.json:
        print(json.dumps(runs, ensure_ascii=False, indent=2))
        return

    print("Timestamp        Summary")
    print("---------------  -------")
    for run in runs:
        print(f"{run['timestamp']}  {run['summary'] or '-'}")


if __name__ == "__main__":
    main()
