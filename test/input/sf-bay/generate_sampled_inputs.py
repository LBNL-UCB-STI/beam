#!/usr/bin/env python3

from __future__ import annotations

import argparse
from pathlib import Path
import random

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq


ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "test" / "input" / "sf-bay"
URBANSIM_OUT = OUT / "urbansim" / "atlas-2019"
FREIGHT_OUT = OUT / "freight" / "20250730" / "2018-Baseline"

HOUSEHOLD_SAMPLE_FRACTION = 0.01
FREIGHT_TOUR_SAMPLE_FRACTION = 0.10
RANDOM_SEED = 20260415


def ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)


def write_table(table: pa.Table, path: Path) -> None:
    ensure_dir(path.parent)
    pq.write_table(table, path, compression="snappy")


def unique_pylist(table: pa.Table, column: str) -> list:
    values = pc.unique(table[column])
    if hasattr(values, "combine_chunks"):
        values = values.combine_chunks()
    return values.to_pylist()


def filter_in(table: pa.Table, column: str, values: list) -> pa.Table:
    return table.filter(pc.is_in(table[column], value_set=pa.array(values, type=table[column].type)))


def sample_table(table: pa.Table, fraction: float, seed: int) -> pa.Table:
    count = len(table)
    sample_size = max(1, int(count * fraction))
    rng = random.Random(seed)
    indices = sorted(rng.sample(range(count), sample_size))
    return table.take(pa.array(indices, type=pa.int64()))


def sample_households(urbansim_src: Path) -> tuple[pa.Table, list, list]:
    households = pq.read_table(urbansim_src / "households.parquet")
    sampled = sample_table(households, HOUSEHOLD_SAMPLE_FRACTION, RANDOM_SEED)
    household_ids = unique_pylist(sampled, "household_id")
    write_table(sampled, URBANSIM_OUT / "households.parquet")
    return sampled, household_ids, unique_pylist(sampled, "block_id")


def sample_urbansim(urbansim_src: Path) -> None:
    households, household_ids, block_ids = sample_households(urbansim_src)
    persons = filter_in(pq.read_table(urbansim_src / "persons.parquet"), "household_id", household_ids)
    person_ids = unique_pylist(persons, "person_id")
    plans = filter_in(pq.read_table(urbansim_src / "plans.parquet"), "person_id", person_ids)
    vehicles = filter_in(
        pq.read_table(urbansim_src / "vehicles--2019-Baseline--EM.parquet"),
        "household_id",
        household_ids,
    )

    blocks = filter_in(pq.read_table(urbansim_src / "blocks.parquet"), "block_id", block_ids)

    write_table(persons, URBANSIM_OUT / "persons.parquet")
    write_table(plans, URBANSIM_OUT / "plans.parquet")
    write_table(vehicles, URBANSIM_OUT / "vehicles--2019-Baseline--EM.parquet")
    write_table(blocks, URBANSIM_OUT / "blocks.parquet")

    print(
        "urbansim",
        {
            "households": len(households),
            "persons": len(persons),
            "plans": len(plans),
            "vehicles": len(vehicles),
            "blocks": len(blocks),
        },
    )


def sample_freight(freight_src: Path) -> None:
    tours = pq.read_table(freight_src / "tours--2018-Baseline.parquet")
    sampled_tours = sample_table(tours, FREIGHT_TOUR_SAMPLE_FRACTION, RANDOM_SEED)
    tour_ids = unique_pylist(sampled_tours, "tourId")

    payloads = filter_in(pq.read_table(freight_src / "payloads--2018-Baseline.parquet"), "tourId", tour_ids)
    carriers = filter_in(
        pq.read_table(freight_src / "carriers--2018-Baseline--EM.parquet"),
        "tourId",
        tour_ids,
    )

    write_table(sampled_tours, FREIGHT_OUT / "tours--2018-Baseline.parquet")
    write_table(payloads, FREIGHT_OUT / "payloads--2018-Baseline.parquet")
    write_table(carriers, FREIGHT_OUT / "carriers--2018-Baseline--EM.parquet")

    print(
        "freight",
        {
            "tours": len(sampled_tours),
            "payloads": len(payloads),
            "carriers": len(carriers),
        },
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate sampled SFBay emissions inputs.")
    parser.add_argument("--urbansim-src", type=Path, required=True, help="Source UrbanSim atlas-2019 directory.")
    parser.add_argument("--freight-src", type=Path, required=True, help="Source freight 2018-Baseline directory.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    sample_urbansim(args.urbansim_src)
    sample_freight(args.freight_src)


if __name__ == "__main__":
    main()
