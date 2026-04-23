import argparse
from pathlib import Path

import pandas as pd


def parse_args():
    parser = argparse.ArgumentParser(
        description="Backfill missing vehicle type capacities from a reference vehicletypes CSV."
    )
    parser.add_argument("--target", required=True, help="Target vehicletypes CSV to update.")
    parser.add_argument("--reference", required=True, help="Reference vehicletypes CSV to copy capacities from.")
    return parser.parse_args()


def main():
    args = parse_args()
    target_path = Path(args.target).expanduser().resolve()
    reference_path = Path(args.reference).expanduser().resolve()

    target_df = pd.read_csv(target_path)
    reference_df = pd.read_csv(reference_path)

    reference_lookup = reference_df.set_index("vehicleTypeId")[
        ["primaryFuelCapacityInJoule", "secondaryFuelCapacityInJoule"]
    ]

    updated_df = target_df.join(reference_lookup, on="vehicleTypeId", rsuffix="Reference")

    for column in ["primaryFuelCapacityInJoule", "secondaryFuelCapacityInJoule"]:
        reference_column = f"{column}Reference"
        updated_df[column] = updated_df[column].fillna(updated_df[reference_column])
        updated_df.drop(columns=[reference_column], inplace=True)

    updated_df.to_csv(target_path, index=False)


if __name__ == "__main__":
    main()
