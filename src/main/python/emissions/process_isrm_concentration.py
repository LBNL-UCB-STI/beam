"""
ISRM Concentration Processor

This module processes emission data using the Intervention Model for Air Pollution (InMAP) Reduced-Form 
Source-Receptor Matrix (ISRM) to calculate changes in air pollutant concentrations and related health impacts
across different scenarios.

The tool can:
1. Load and process emissions data from different scenarios
2. Merge emissions data with ISRM polygon data
3. Calculate differences in emissions between scenarios
4. Process emission data through ISRM to get concentration results
5. Generate visualizations of emissions and concentrations
"""

import contextily as ctx
import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sys, os, zarr, s3fs, pyreadr
from matplotlib.colors import LinearSegmentedColormap
from matplotlib.patches import FancyArrowPatch
from matplotlib_scalebar.scalebar import ScaleBar
from shapely.geometry import Polygon

from emissions_skims_processor import get_or_upload_emissions_to_duckdb

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name


def process_by_link_type_process(skims_db, pollutants, run_dir):
    merged_df = None
    for pollutant in pollutants:
        skims = pd.read_csv(f"{run_dir}/0.skimsEmissions_{pollutant}.csv.gz")
        grouped_skims = skims.groupby(["linkId", "vehicleTypeId", "process"]).agg({
            f'{pollutant}': 'sum',
            'observations': 'sum'
        }).reset_index()
        grouped_skims[f'tot_{pollutant}'] = grouped_skims[pollutant] * grouped_skims['observations']
        if merged_df is None:
            merged_df = grouped_skims[['linkId', 'vehicleTypeId', 'process', pollutant, f'tot_{pollutant}']]
        else:
            temp_df = grouped_skims[['linkId', 'vehicleTypeId', 'process', pollutant, f'tot_{pollutant}']]
            merged_df = pd.merge(merged_df, temp_df, on=['linkId', 'vehicleTypeId', 'process'], how='outer')

    # Save the merged DataFrame to a CSV file
    output_file = f"{run_dir}/0.skimsEmissions_{"_".join(pollutants)}.csv.gz"
    merged_df.to_csv(output_file, index=False)
    print(f"Saved merged emissions skims to {output_file}")
    return merged_df


def main():
    """
    Main function to run the ISRM concentration processing for multiple scenarios.
    """
    area = "sfbay"
    run_batch = "20240123"
    scenario = "2018-Baseline"
    config = get_area_config(area)
    work_dir = config["work_dir"]
    network_name = "sfbay-area-cbg5500-network"
    config["emissions"][scenario]["run"]["output_dir"] = f"emissions/{run_batch}"
    inmap_conf = config["air-quality"]["inmap"]
    inmap_conf["run"]["output_dir"] = f"inmap/{run_batch}"

    isrm_beam_geo = f"{work_dir}/inmap/{network_name}/isrm-beam--network-intersection.geojson"
    isrm_grid_geo = f"{work_dir}/inmap/ISRM/isrm_polygon.shp"
    pollutants = ["PM2_5"]
    skims_db_file = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/0.skimsEmissions.duckdb"
    skims_db = get_or_upload_emissions_to_duckdb(csv_or_db_file=skims_db_file)
    skims1 = process_by_link_type_process(skims_db, pollutants)

    # Configuration
    emis_shapefile_filepath = f'{work_dir}/{inmap_conf["isrm_grid"]}'
    emission_type = 'All'  # Options: 'onNetwork', 'offNetwork', 'All'
    detail_net_path = f'{work_dir}/{inmap_conf["beam-mapping"]}'
    inmap_url = 's3://inmap-model/isrm_v1.2.1.zarr/'





if __name__ == "__main__":
    main()