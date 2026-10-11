"""Summarize measured game publications; warmup and stationary holds are excluded."""
import csv
import json
import math
import statistics
import sys
from collections import Counter
from pathlib import Path


def summarize(directory):
    directory = Path(directory)
    rows = []
    for run in range(1, 4):
        with (directory / f"publications-{run}.csv").open() as source:
            rows.extend(row for row in csv.DictReader(source) if float(row["seconds"]) <= 25)
    result = {"movingPublications": len(rows), "freshPerSecond": len(rows) / 75}
    result["grids"] = dict(Counter(f'{r["width"]}x{r["height"]}' for r in rows))
    for key in ("traceMs", "copyMs", "ageMs", "pickupMs", "traceCpuMs", "traceBytes",
                "primaryTests", "continuationTests", "shadowTests"):
        values = sorted(float(row[key]) for row in rows)
        result[key] = {"mean": statistics.mean(values), "median": statistics.median(values),
                       "p95": values[math.ceil(len(values) * .95) - 1]}
    return result


if __name__ == "__main__":
    print(json.dumps(summarize(sys.argv[1]), indent=2))
