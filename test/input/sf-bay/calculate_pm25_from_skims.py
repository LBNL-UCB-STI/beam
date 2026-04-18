#!/usr/bin/env python3

from __future__ import annotations

import argparse
import csv
import gzip
import re
from collections import defaultdict
from decimal import Decimal, getcontext
from pathlib import Path


getcontext().prec = 40

GRAMS_PER_SHORT_TON = Decimal("907184.74")
DEFAULT_REAL_POPULATION = Decimal("6200000")
DEFAULT_ANNUALIZATION_DAYS = Decimal("330")
EMISSIONS_ENTRY_PATTERN = re.compile(r"([A-Za-z0-9]+):([0-9Ee+\-.]+)")
TRANSIT_PREFIXES = (
    "BUS-",
    "TRAM-",
    "SUBWAY-",
    "RAIL-",
    "FERRY-",
    "CABLE_CAR-",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Calculate total pollutant mass from a BEAM skimsEmissions file, applying observations, "
            "scaling non-transit emissions to a real population, and annualizing."
        )
    )
    parser.add_argument(
        "run_dir",
        type=Path,
        help="BEAM output run directory, e.g. output/test-sf-bay/test-sfbay-1pct-emissions__...",
    )
    parser.add_argument(
        "--real-population",
        type=Decimal,
        default=DEFAULT_REAL_POPULATION,
        help=f"Real population size to scale non-transit emissions to. Default: {DEFAULT_REAL_POPULATION}",
    )
    parser.add_argument(
        "--annualization-days",
        type=Decimal,
        default=DEFAULT_ANNUALIZATION_DAYS,
        help=f"Number of days to annualize over. Default: {DEFAULT_ANNUALIZATION_DAYS}",
    )
    parser.add_argument(
        "--simulated-population",
        type=Decimal,
        default=None,
        help=(
            "Override the simulated passenger population used for scaling. "
            "If omitted, the script counts non-freight persons in population.csv.gz."
        ),
    )
    parser.add_argument(
        "--pollutant",
        default="PM25",
        help="Pollutant name as encoded in the emissions column, e.g. PM25 or NOx. Default: PM25",
    )
    return parser.parse_args()


def to_short_tons(grams: Decimal) -> Decimal:
    return grams / GRAMS_PER_SHORT_TON


def find_skims_file(run_dir: Path) -> Path:
    candidates = sorted(run_dir.glob("ITERS/it.*/[0-9]*.skimsEmissions.csv.gz"))
    if not candidates:
        raise FileNotFoundError(f"No skims emissions file found under {run_dir}")
    candidates.sort(
        key=lambda path: int(path.parent.name.split(".")[-1])
    )
    return candidates[-1]


def count_simulated_passenger_population(run_dir: Path) -> Decimal:
    population_path = run_dir / "population.csv.gz"
    if not population_path.exists():
        raise FileNotFoundError(
            f"Could not infer simulated passenger population because {population_path} does not exist. "
            "Use --simulated-population."
        )

    count = 0
    with gzip.open(population_path, "rt", newline="") as handle:
        reader = csv.DictReader(handle)
        id_field = reader.fieldnames[0] if reader.fieldnames else None
        if id_field is None:
            raise ValueError(f"{population_path} does not contain a header row")
        for row in reader:
            person_id = row[id_field]
            if not person_id.startswith("ft-"):
                count += 1

    if count <= 0:
        raise ValueError(f"Simulated passenger population inferred from {population_path} is zero")
    return Decimal(count)


def is_transit_vehicle_type(vehicle_type_id: str) -> bool:
    return vehicle_type_id.startswith(TRANSIT_PREFIXES)


def compute_pollutant(skims_path: Path, pollutant: str) -> dict[str, object]:
    grams_by_source: dict[str, Decimal] = defaultdict(Decimal)
    grams_by_process: dict[str, Decimal] = defaultdict(Decimal)
    total_grams = Decimal("0")
    total_observations = Decimal("0")
    total_rows = 0
    pollutant_rows = 0
    pollutant_upper = pollutant.upper()

    with gzip.open(skims_path, "rt", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            total_rows += 1
            observations = Decimal(row["observations"]) if row.get("observations") else Decimal("1")
            total_observations += observations
            emissions_map = {
                name.upper(): Decimal(value)
                for name, value in EMISSIONS_ENTRY_PATTERN.findall(row["emissions"])
            }
            pollutant_value = emissions_map.get(pollutant_upper)
            if pollutant_value is None:
                continue

            pollutant_rows += 1
            pollutant_grams = pollutant_value * observations
            total_grams += pollutant_grams
            grams_by_process[row["process"]] += pollutant_grams
            source = "transit" if is_transit_vehicle_type(row["vehicleTypeId"]) else "non_transit"
            grams_by_source[source] += pollutant_grams

    return {
        "total_rows": total_rows,
        "pollutant_rows": pollutant_rows,
        "total_observations": total_observations,
        "total_grams": total_grams,
        "grams_by_source": dict(grams_by_source),
        "grams_by_process": dict(sorted(grams_by_process.items())),
    }


def format_decimal(value: Decimal, places: int = 9) -> str:
    return f"{value:.{places}f}"


def main() -> None:
    args = parse_args()
    run_dir = args.run_dir.resolve()
    skims_path = find_skims_file(run_dir)
    simulated_population = args.simulated_population or count_simulated_passenger_population(run_dir)
    scale_factor = args.real_population / simulated_population
    pollutant = args.pollutant.upper()

    result = compute_pollutant(skims_path, pollutant)
    total_grams = result["total_grams"]
    grams_by_source = result["grams_by_source"]
    grams_by_process = result["grams_by_process"]

    transit_grams = grams_by_source.get("transit", Decimal("0"))
    non_transit_grams = grams_by_source.get("non_transit", Decimal("0"))
    scaled_total_grams = transit_grams + non_transit_grams * scale_factor
    annualized_grams = scaled_total_grams * args.annualization_days

    print(f"run_dir: {run_dir}")
    print(f"skims_file: {skims_path}")
    print(f"simulated_passenger_population: {simulated_population}")
    print(f"real_population: {args.real_population}")
    print(f"scale_factor_non_transit: {format_decimal(scale_factor, 12)}")
    print(f"annualization_days: {args.annualization_days}")
    print(f"pollutant: {pollutant}")
    print(f"rows: {result['total_rows']}")
    print(f"pollutant_rows: {result['pollutant_rows']}")
    print(f"observations_sum: {result['total_observations']}")
    print()

    print(f"raw_{pollutant}_short_tons:")
    print(f"  total: {format_decimal(to_short_tons(total_grams), 12)}")
    print(f"  transit: {format_decimal(to_short_tons(transit_grams), 12)}")
    print(f"  non_transit: {format_decimal(to_short_tons(non_transit_grams), 12)}")
    print()

    print(f"scaled_{pollutant}_short_tons:")
    print(f"  daily_total: {format_decimal(to_short_tons(scaled_total_grams), 12)}")
    print(f"  annualized_total: {format_decimal(to_short_tons(annualized_grams), 12)}")
    print()

    print(f"{pollutant}_by_process_short_tons_raw:")
    for process, grams in grams_by_process.items():
        print(f"  {process}: {format_decimal(to_short_tons(grams), 12)}")


if __name__ == "__main__":
    main()
