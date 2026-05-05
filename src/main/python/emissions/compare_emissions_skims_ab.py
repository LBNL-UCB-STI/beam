#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import duckdb


KEY_COLUMNS = ("hour", "linkId", "vehicleTypeId", "process")
VALUE_COLUMNS = (
    "emissions",
    "travelTimeInSecond",
    "parkingDurationInSecond",
    "observations",
    "iterations",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare two BEAM skimsEmissions outputs and report whether they are logically equivalent."
        )
    )
    parser.add_argument("baseline", type=Path, help="Baseline skimsEmissions file (.parquet, .csv, or .csv.gz)")
    parser.add_argument("candidate", type=Path, help="Candidate skimsEmissions file (.parquet, .csv, or .csv.gz)")
    parser.add_argument(
        "--limit",
        type=int,
        default=20,
        help="Max sample mismatches to print per category. Default: 20",
    )
    parser.add_argument(
        "--travel-time-tol",
        type=float,
        default=0.0,
        help="Absolute tolerance for travelTimeInSecond. Default: 0.0",
    )
    parser.add_argument(
        "--parking-duration-tol",
        type=float,
        default=0.0,
        help="Absolute tolerance for parkingDurationInSecond. Default: 0.0",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print machine-readable JSON summary instead of human-readable text.",
    )
    return parser.parse_args()


def quoted(path: Path) -> str:
    return str(path).replace("'", "''")


def relation_for(path: Path) -> str:
    suffixes = "".join(path.suffixes).lower()
    qpath = quoted(path)
    if suffixes.endswith(".parquet"):
        return f"read_parquet('{qpath}')"
    if suffixes.endswith(".csv.gz") or suffixes.endswith(".csv") or suffixes.endswith(".csv.gzip"):
        return f"read_csv_auto('{qpath}', header=true, compression='auto')"
    raise ValueError(f"Unsupported file type: {path}")


def fetch_scalar(conn: duckdb.DuckDBPyConnection, sql: str) -> int:
    return conn.execute(sql).fetchone()[0]


def fetch_rows(conn: duckdb.DuckDBPyConnection, sql: str) -> list[tuple]:
    return conn.execute(sql).fetchall()


def normalize_sql(source_sql: str) -> str:
    return f"""
    SELECT
      CAST(hour AS INTEGER) AS hour,
      CAST(linkId AS INTEGER) AS linkId,
      CAST(vehicleTypeId AS VARCHAR) AS vehicleTypeId,
      CAST(process AS VARCHAR) AS process,
      CAST(emissions AS VARCHAR) AS emissions,
      CAST(travelTimeInSecond AS DOUBLE) AS travelTimeInSecond,
      CAST(parkingDurationInSecond AS DOUBLE) AS parkingDurationInSecond,
      CAST(observations AS INTEGER) AS observations,
      CAST(iterations AS INTEGER) AS iterations
    FROM {source_sql}
    """


def build_diff_sql(limit: int, travel_time_tol: float, parking_duration_tol: float) -> str:
    return f"""
    WITH joined AS (
      SELECT
        b.hour,
        b.linkId,
        b.vehicleTypeId,
        b.process,
        b.emissions AS baseline_emissions,
        c.emissions AS candidate_emissions,
        b.travelTimeInSecond AS baseline_travel_time,
        c.travelTimeInSecond AS candidate_travel_time,
        b.parkingDurationInSecond AS baseline_parking_duration,
        c.parkingDurationInSecond AS candidate_parking_duration,
        b.observations AS baseline_observations,
        c.observations AS candidate_observations,
        b.iterations AS baseline_iterations,
        c.iterations AS candidate_iterations
      FROM baseline b
      INNER JOIN candidate c
        USING (hour, linkId, vehicleTypeId, process)
    )
    SELECT *
    FROM joined
    WHERE
      baseline_emissions <> candidate_emissions OR
      abs(baseline_travel_time - candidate_travel_time) > {travel_time_tol} OR
      abs(baseline_parking_duration - candidate_parking_duration) > {parking_duration_tol} OR
      baseline_observations <> candidate_observations OR
      baseline_iterations <> candidate_iterations
    ORDER BY hour, linkId, vehicleTypeId, process
    LIMIT {limit}
    """


def main() -> int:
    args = parse_args()
    for path in (args.baseline, args.candidate):
      if not path.exists():
        raise FileNotFoundError(path)

    conn = duckdb.connect(database=":memory:")
    conn.execute("PRAGMA threads=4")
    conn.execute(f"CREATE TEMP VIEW baseline AS {normalize_sql(relation_for(args.baseline))}")
    conn.execute(f"CREATE TEMP VIEW candidate AS {normalize_sql(relation_for(args.candidate))}")

    baseline_rows = fetch_scalar(conn, "SELECT COUNT(*) FROM baseline")
    candidate_rows = fetch_scalar(conn, "SELECT COUNT(*) FROM candidate")
    baseline_keys = fetch_scalar(
        conn,
        "SELECT COUNT(*) FROM (SELECT DISTINCT hour, linkId, vehicleTypeId, process FROM baseline)",
    )
    candidate_keys = fetch_scalar(
        conn,
        "SELECT COUNT(*) FROM (SELECT DISTINCT hour, linkId, vehicleTypeId, process FROM candidate)",
    )
    missing_in_candidate = fetch_scalar(
        conn,
        """
        SELECT COUNT(*) FROM (
          SELECT hour, linkId, vehicleTypeId, process FROM baseline
          EXCEPT
          SELECT hour, linkId, vehicleTypeId, process FROM candidate
        )
        """,
    )
    missing_in_baseline = fetch_scalar(
        conn,
        """
        SELECT COUNT(*) FROM (
          SELECT hour, linkId, vehicleTypeId, process FROM candidate
          EXCEPT
          SELECT hour, linkId, vehicleTypeId, process FROM baseline
        )
        """,
    )
    differing_rows = fetch_scalar(
        conn,
        build_diff_sql(limit=10_000_000, travel_time_tol=args.travel_time_tol, parking_duration_tol=args.parking_duration_tol)
        .replace("LIMIT 10000000", ""),
    )

    missing_in_candidate_sample = fetch_rows(
        conn,
        f"""
        SELECT *
        FROM (
          SELECT hour, linkId, vehicleTypeId, process FROM baseline
          EXCEPT
          SELECT hour, linkId, vehicleTypeId, process FROM candidate
        )
        ORDER BY hour, linkId, vehicleTypeId, process
        LIMIT {args.limit}
        """,
    )
    missing_in_baseline_sample = fetch_rows(
        conn,
        f"""
        SELECT *
        FROM (
          SELECT hour, linkId, vehicleTypeId, process FROM candidate
          EXCEPT
          SELECT hour, linkId, vehicleTypeId, process FROM baseline
        )
        ORDER BY hour, linkId, vehicleTypeId, process
        LIMIT {args.limit}
        """,
    )
    differing_rows_sample = fetch_rows(
        conn,
        build_diff_sql(
            limit=args.limit,
            travel_time_tol=args.travel_time_tol,
            parking_duration_tol=args.parking_duration_tol,
        ),
    )

    passed = (
        baseline_rows == candidate_rows
        and baseline_keys == candidate_keys
        and missing_in_candidate == 0
        and missing_in_baseline == 0
        and differing_rows == 0
    )

    result = {
        "passed": passed,
        "baseline": str(args.baseline),
        "candidate": str(args.candidate),
        "counts": {
            "baseline_rows": baseline_rows,
            "candidate_rows": candidate_rows,
            "baseline_unique_keys": baseline_keys,
            "candidate_unique_keys": candidate_keys,
            "missing_in_candidate": missing_in_candidate,
            "missing_in_baseline": missing_in_baseline,
            "differing_rows": differing_rows,
        },
        "samples": {
            "missing_in_candidate": missing_in_candidate_sample,
            "missing_in_baseline": missing_in_baseline_sample,
            "differing_rows": differing_rows_sample,
        },
        "tolerances": {
            "travel_time_tol": args.travel_time_tol,
            "parking_duration_tol": args.parking_duration_tol,
        },
    }

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print(f"baseline:  {args.baseline}")
        print(f"candidate: {args.candidate}")
        print(f"baseline rows:         {baseline_rows}")
        print(f"candidate rows:        {candidate_rows}")
        print(f"baseline unique keys:  {baseline_keys}")
        print(f"candidate unique keys: {candidate_keys}")
        print(f"missing in candidate:  {missing_in_candidate}")
        print(f"missing in baseline:   {missing_in_baseline}")
        print(f"differing rows:        {differing_rows}")
        print(f"result:                {'PASS' if passed else 'FAIL'}")

        if missing_in_candidate_sample:
            print("\nSample keys missing in candidate:")
            for row in missing_in_candidate_sample:
                print(row)

        if missing_in_baseline_sample:
            print("\nSample keys missing in baseline:")
            for row in missing_in_baseline_sample:
                print(row)

        if differing_rows_sample:
            print("\nSample differing rows:")
            for row in differing_rows_sample:
                print(row)

    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
