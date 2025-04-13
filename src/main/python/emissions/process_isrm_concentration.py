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

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

# Now use absolute import
from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import generate_network_name


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
    emissions_skims_s1 = f"{work_dir}/beam-runs/20240123/2018-Baseline-EM1/0.skimsEmissions.csv.gz"
    emissions_skims_s0 = ""

    # Configuration
    emis_shapefile_filepath = f'{work_dir}/{inmap_conf["isrm_grid"]}'
    emission_type = 'All'  # Options: 'onNetwork', 'offNetwork', 'All'
    detail_net_path = f'{work_dir}/{inmap_conf["beam-mapping"]}'
    inmap_url = 's3://inmap-model/isrm_v1.2.1.zarr/'





if __name__ == "__main__":
    main()