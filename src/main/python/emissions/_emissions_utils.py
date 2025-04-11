import json
import os
import sys
import pyarrow as pa
import pyarrow.csv as csv
from collections import defaultdict

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import BeamClasses


def generate_emfac_beam_class_mapping(emfac_pop_by_model_year_file, vehicle_class_output_file, to_filter_out):
    """
    Creates vehicle class mapping and saves it to a JSON file if it doesn't exist.
    If the file exists, loads and returns the existing mapping.

    Args:
        to_filter_out:

    Returns:
        dict: The vehicle class mapping (either newly created or loaded from existing file)
    """
    # Check if the file already exists
    if os.path.exists(vehicle_class_output_file):
        print(f"File {vehicle_class_output_file} already exists. Loading existing mapping.")
        with open(vehicle_class_output_file, 'r') as f:
            return json.load(f)

    # Create the mapping
    mapping = {}

    table = csv.read_csv(emfac_pop_by_model_year_file, read_options=pa.csv.ReadOptions(use_threads=True))
    df = table.to_pandas()

    for vehicle in df["vehicle_class"].unique():
        if 'Utility' in vehicle or 'Public' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'Port' in vehicle or 'POLA' in vehicle or 'POAK' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif 'SWCV' in vehicle or 'PTO' in vehicle or 'T6TS' in vehicle:
            mapping[vehicle] = "NotMatched"
        elif vehicle in ['LDA', 'LDT1', 'LDT2', 'MDV']:
            mapping[vehicle] = BeamClasses.CLASS_CAR
        elif vehicle in ['MCY']:
            mapping[vehicle] = BeamClasses.CLASS_BIKE
        elif vehicle in ['UBUS']:
            mapping[vehicle] = BeamClasses.CLASS_MDP
        elif 'LHD' in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_2B3_VOCATIONAL
        elif 'Class 4' in vehicle or 'Class 5' in vehicle or 'Class 6' in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_456_VOCATIONAL
        elif 'Class 7' in vehicle or 'Class 8' in vehicle:
            if 'Tractor' in vehicle or 'CAIRP' in vehicle:
                mapping[vehicle] = BeamClasses.CLASS_78_TRACTOR
            else:
                mapping[vehicle] = BeamClasses.CLASS_78_VOCATIONAL
        elif "T7IS" in vehicle:
            mapping[vehicle] = BeamClasses.CLASS_78_TRACTOR
        else:
            mapping[vehicle] = "NotMatched"

    # Print category groupings
    class_groups = defaultdict(list)
    for vehicle, vehicle_class in mapping.items():
        if vehicle_class in to_filter_out:
            mapping[vehicle] = "NotMatched"
        class_groups[mapping[vehicle]].append(vehicle)
    for vehicle_class, vehicles in class_groups.items():
        print(f"Category: {vehicle_class}")
        for vehicle in vehicles:
            print(f"  - {vehicle}")

    return {k: v for k, v in mapping.items() if v != "NotMatched"}
