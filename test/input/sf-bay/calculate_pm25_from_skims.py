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
EXHAUST_PROCESSES = ("RUNEX", "STREX", "PTOEX")
TRANSIT_PREFIXES = (
    "BUS-",
    "TRAM-",
    "SUBWAY-",
    "RAIL-",
    "FERRY-",
    "CABLE_CAR-",
)
DUST_PROCESS = "PRDUST"
DEFAULT_VEHICLE_TYPE_FILES = (
    "vehicle-tech/vehicleTypes--atlas--2019-Baseline--EM.csv",
    "vehicle-tech/vehicleTypes--frism--2018-Baseline--EM.csv",
)
CATEGORY_BUCKETS = (
    "passenger_cars",
    "light_duty_trucks",
    "medium_duty_trucks",
    "heavy_duty_trucks",
    "other",
)
CATEGORY_LABELS = {
    "passenger_cars": "Passenger cars",
    "light_duty_trucks": "Light-duty freight trucks",
    "medium_duty_trucks": "Medium-duty freight trucks",
    "heavy_duty_trucks": "Heavy-duty freight trucks",
    "other": "Other / uncategorized",
}


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
    parser.add_argument(
        "--vehicle-type-file",
        dest="vehicle_type_files",
        action="append",
        default=None,
        help=(
            "Vehicle type CSV used to map vehicleTypeId to categories. "
            "May be passed multiple times. Defaults to the sf-bay atlas and frism vehicle-tech files."
        ),
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


def resolve_vehicle_type_files(script_path: Path, configured_paths: list[str] | None) -> list[Path]:
    relative_paths = configured_paths or list(DEFAULT_VEHICLE_TYPE_FILES)
    return [(script_path.parent / path).resolve() for path in relative_paths]


def load_vehicle_type_categories(paths: list[Path]) -> dict[str, str]:
    categories: dict[str, str] = {}
    for path in paths:
        with open(path, newline="") as handle:
            reader = csv.DictReader(handle)
            for row in reader:
                vehicle_type_id = row.get("vehicleTypeId")
                vehicle_category = row.get("vehicleCategory")
                if vehicle_type_id and vehicle_category:
                    categories[vehicle_type_id] = vehicle_category
    return categories


def classify_vehicle_bucket(vehicle_type_id: str, vehicle_categories: dict[str, str]) -> str:
    vehicle_category = vehicle_categories.get(vehicle_type_id)
    if vehicle_type_id.startswith("pax-") and vehicle_category == "Car":
        return "passenger_cars"
    if vehicle_category == "Class2b3Vocational":
        return "light_duty_trucks"
    if vehicle_category == "Class12aVocational":
        return "medium_duty_trucks"
    if vehicle_category in {"Class456Vocational", "Class78Vocational", "Class78Tractor"}:
        return "heavy_duty_trucks"
    return "other"


def compute_pollutant(skims_path: Path, pollutant: str) -> dict[str, object]:
    grams_by_source: dict[str, Decimal] = defaultdict(Decimal)
    grams_by_process: dict[str, Decimal] = defaultdict(Decimal)
    grams_by_source_process: dict[str, dict[str, Decimal]] = defaultdict(lambda: defaultdict(Decimal))
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
            grams_by_source_process[source][row["process"]] += pollutant_grams

    return {
        "total_rows": total_rows,
        "pollutant_rows": pollutant_rows,
        "total_observations": total_observations,
        "total_grams": total_grams,
        "grams_by_source": dict(grams_by_source),
        "grams_by_source_process": {source: dict(processes) for source, processes in grams_by_source_process.items()},
        "grams_by_process": dict(sorted(grams_by_process.items())),
    }


def compute_category_breakdown(
    skims_path: Path,
    pollutant: str,
    vehicle_categories: dict[str, str]
) -> dict[str, dict[str, Decimal]]:
    raw_mobile_grams_by_bucket: dict[str, Decimal] = defaultdict(Decimal)
    raw_exhaust_grams_by_bucket: dict[str, Decimal] = defaultdict(Decimal)
    pollutant_upper = pollutant.upper()

    with gzip.open(skims_path, "rt", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            if is_transit_vehicle_type(row["vehicleTypeId"]):
                continue
            bucket = classify_vehicle_bucket(row["vehicleTypeId"], vehicle_categories)
            observations = Decimal(row["observations"]) if row.get("observations") else Decimal("1")
            emissions_map = {
                name.upper(): Decimal(value)
                for name, value in EMISSIONS_ENTRY_PATTERN.findall(row["emissions"])
            }
            pollutant_value = emissions_map.get(pollutant_upper)
            if pollutant_value is None:
                continue

            pollutant_grams = pollutant_value * observations
            process = row["process"]
            if process != DUST_PROCESS:
                raw_mobile_grams_by_bucket[bucket] += pollutant_grams
            if process in EXHAUST_PROCESSES:
                raw_exhaust_grams_by_bucket[bucket] += pollutant_grams

    return {
        "mobile": {bucket: raw_mobile_grams_by_bucket.get(bucket, Decimal("0")) for bucket in CATEGORY_BUCKETS},
        "exhaust": {bucket: raw_exhaust_grams_by_bucket.get(bucket, Decimal("0")) for bucket in CATEGORY_BUCKETS},
    }


def format_decimal(value: Decimal, places: int = 9) -> str:
    return f"{value:.{places}f}"


def main() -> None:
    args = parse_args()
    run_dir = args.run_dir.resolve()
    script_path = Path(__file__).resolve()
    skims_path = find_skims_file(run_dir)
    simulated_population = args.simulated_population or count_simulated_passenger_population(run_dir)
    scale_factor = args.real_population / simulated_population
    pollutant = args.pollutant.upper()
    vehicle_type_files = resolve_vehicle_type_files(script_path, args.vehicle_type_files)
    vehicle_categories = load_vehicle_type_categories(vehicle_type_files)

    result = compute_pollutant(skims_path, pollutant)
    category_breakdown = compute_category_breakdown(skims_path, pollutant, vehicle_categories)
    total_grams = result["total_grams"]
    grams_by_source = result["grams_by_source"]
    grams_by_source_process = result["grams_by_source_process"]
    grams_by_process = result["grams_by_process"]

    transit_grams = grams_by_source.get("transit", Decimal("0"))
    non_transit_grams = grams_by_source.get("non_transit", Decimal("0"))
    scaled_total_grams = transit_grams + non_transit_grams * scale_factor
    annualized_grams = scaled_total_grams * args.annualization_days
    dust_grams = grams_by_process.get(DUST_PROCESS, Decimal("0"))
    mobile_grams = total_grams - dust_grams

    transit_dust_grams = grams_by_source_process.get("transit", {}).get(DUST_PROCESS, Decimal("0"))
    non_transit_dust_grams = grams_by_source_process.get("non_transit", {}).get(DUST_PROCESS, Decimal("0"))
    transit_mobile_grams = transit_grams - transit_dust_grams
    non_transit_mobile_grams = non_transit_grams - non_transit_dust_grams

    scaled_dust_grams = transit_dust_grams + non_transit_dust_grams * scale_factor
    scaled_mobile_grams = transit_mobile_grams + non_transit_mobile_grams * scale_factor
    annualized_dust_grams = scaled_dust_grams * args.annualization_days
    annualized_mobile_grams = scaled_mobile_grams * args.annualization_days
    exhaust_grams = sum((grams_by_process.get(process, Decimal("0")) for process in EXHAUST_PROCESSES), Decimal("0"))
    transit_exhaust_grams = sum(
        (grams_by_source_process.get("transit", {}).get(process, Decimal("0")) for process in EXHAUST_PROCESSES),
        Decimal("0"),
    )
    non_transit_exhaust_grams = sum(
        (grams_by_source_process.get("non_transit", {}).get(process, Decimal("0")) for process in EXHAUST_PROCESSES),
        Decimal("0"),
    )
    scaled_exhaust_grams = transit_exhaust_grams + non_transit_exhaust_grams * scale_factor
    annualized_exhaust_grams = scaled_exhaust_grams * args.annualization_days
    scaled_mobile_bucket_grams = {
        bucket: grams * scale_factor for bucket, grams in category_breakdown["mobile"].items()
    }
    annualized_mobile_bucket_grams = {
        bucket: grams * args.annualization_days for bucket, grams in scaled_mobile_bucket_grams.items()
    }
    scaled_mobile_bucket_total_grams = sum(scaled_mobile_bucket_grams.values(), Decimal("0"))

    print(f"run_dir: {run_dir}")
    print(f"skims_file: {skims_path}")
    print(f"simulated_passenger_population: {simulated_population}")
    print(f"real_population: {args.real_population}")
    print(f"scale_factor_non_transit: {format_decimal(scale_factor, 12)}")
    print(f"annualization_days: {args.annualization_days}")
    print(f"pollutant: {pollutant}")
    print(f"vehicle_type_files: {', '.join(str(path) for path in vehicle_type_files)}")
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

    print(f"scaled_{pollutant}_short_tons_mobile_vs_dust:")
    print(f"  daily_mobile_excluding_{DUST_PROCESS}: {format_decimal(to_short_tons(scaled_mobile_grams), 12)}")
    print(f"  annualized_mobile_excluding_{DUST_PROCESS}: {format_decimal(to_short_tons(annualized_mobile_grams), 12)}")
    print(f"  daily_dust_{DUST_PROCESS}: {format_decimal(to_short_tons(scaled_dust_grams), 12)}")
    print(f"  annualized_dust_{DUST_PROCESS}: {format_decimal(to_short_tons(annualized_dust_grams), 12)}")
    print()

    print(f"scaled_{pollutant}_short_tons_exhaust_only:")
    print(f"  daily_exhaust_only: {format_decimal(to_short_tons(scaled_exhaust_grams), 12)}")
    print(f"  annualized_exhaust_only: {format_decimal(to_short_tons(annualized_exhaust_grams), 12)}")
    print(f"  raw_total_exhaust_only: {format_decimal(to_short_tons(exhaust_grams), 12)}")
    print(f"  raw_transit_exhaust_only: {format_decimal(to_short_tons(transit_exhaust_grams), 12)}")
    print(f"  raw_non_transit_exhaust_only: {format_decimal(to_short_tons(non_transit_exhaust_grams), 12)}")
    print()

    print(f"scaled_{pollutant}_short_tons_mobile_category_split_non_transit:")
    for bucket in CATEGORY_BUCKETS:
        daily_short_tons = to_short_tons(scaled_mobile_bucket_grams[bucket])
        annual_short_tons = to_short_tons(annualized_mobile_bucket_grams[bucket])
        share = (
            (daily_short_tons / to_short_tons(scaled_mobile_bucket_total_grams)) * Decimal("100")
            if scaled_mobile_bucket_total_grams > 0
            else Decimal("0")
        )
        print(
            f"  {bucket}: daily={format_decimal(daily_short_tons, 12)}, "
            f"annualized={format_decimal(annual_short_tons, 12)}, "
            f"share_percent={format_decimal(share, 6)}"
            f"  # {CATEGORY_LABELS[bucket]}"
        )
    print()

    print(f"{pollutant}_by_process_short_tons_raw:")
    for process, grams in grams_by_process.items():
        print(f"  {process}: {format_decimal(to_short_tons(grams), 12)}")


if __name__ == "__main__":
    main()
