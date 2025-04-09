#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NPMRDS Data and Station Preparation Script

This script prepares NPMRDS (National Performance Management Research Data Set) data
and stations for use in BEAM traffic simulation validation.
"""

import sys

from _data_collection_utils import collect_geographic_boundaries
from _validation_utils import *

# Add parent directory to path for imports
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import get_area_config, generate_network_name


def setup_config_and_paths(study_area, batch, scenario):
    """
    Set up configuration and paths for the study area

    Parameters:
    -----------
    study_area : str
        Name of the study area (e.g., "sfbay", "seattle")
    batch : str
        Batch identifier
    scenario : str
        Scenario name

    Returns:
    --------
    tuple
        Configuration, network directory, and output directory paths
    """
    # Load configuration
    config = get_area_config(study_area)
    config["network"]["graph_layers"]["residential"]["min_density_per_km2"] = 5500

    # Extract relevant config sections
    config_network = config["network"]
    config_npmrds = config_network["validation"]["npmrds"]
    config_geo = config["geo"]

    # Generate paths
    network_name = generate_network_name(config)
    network_dir = f'{config["work_dir"]}/network/{network_name}'
    output_dir = f"{config["work_dir"]}/beam-runs/{batch}/{scenario}"

    return config, config_network, config_npmrds, config_geo, network_dir, output_dir


def collect_boundaries(config, study_area):
    """
    Collect geographic boundaries for the study area

    Parameters:
    -----------
    config : dict
        Configuration dictionary for the study area
    study_area : str
        Name of the study area

    Returns:
    --------
    tuple
        Region and CBG (Census Block Group) boundaries
    """
    # Collect region boundaries
    region_boundary_wgs84 = collect_geographic_boundaries(
        config["state_fips"],
        config["county_fips"],
        config["census_year"],
        study_area,
        geo_level='county',
        work_dir=f'{config["work_dir"]}/geo'
    )

    # Collect CBG boundaries
    cbg_boundary_wgs84 = collect_geographic_boundaries(
        config["state_fips"],
        config["county_fips"],
        config["census_year"],
        study_area,
        geo_level='county',
        work_dir=f'{config["work_dir"]}/geo'
    )

    return region_boundary_wgs84, cbg_boundary_wgs84


def prepare_and_plot_npmrds_data(config, config_npmrds, config_geo, network_dir,
                                output_dir, study_area, region_boundary_wgs84):
    """
    Prepare NPMRDS data and create validation plots

    Parameters:
    -----------
    config : dict
        Study area configuration
    config_npmrds : dict
        NPMRDS configuration section
    config_geo : dict
        Geographic configuration section
    network_dir : str
        Path to network directory
    output_dir : str
        Path to output directory
    study_area : str
        Name of the study area
    region_boundary_wgs84 : GeoDataFrame
        Region boundaries in WGS84 projection

    Returns:
    --------
    tuple
        NPMRDS station, network map, and execution time
    """
    start_time = time.time()

    # Prepare NPMRDS data
    regional_npmrds_station, _, beam_npmrds_network_map, _ = prepare_npmrds_data(
        # input
        npmrds_label=f"NPMRDS_{config_npmrds['year']}",
        npmrds_raw_geo=f"{config['work_dir']}/{config_npmrds['geo']}",
        npmrds_raw_data_csv=f'{config["work_dir"]}/{config_npmrds["data"]}',
        npmrds_observed_speed_weight=0.5,
        region_boundary=region_boundary_wgs84,
        beam_network_csv_input=f"{network_dir}/network.csv.gz",
        projected_crs_epsg=config_geo["utm_epsg"],
        distance_buffer_m=20,
        # output
        npmrds_station_geo=f"{output_dir}/{study_area}_npmrds_station.geojson",
        npmrds_data_csv=f"{output_dir}/{study_area}_npmrds_data.csv",
        npmrds_hourly_speed_csv=f"{output_dir}/{study_area}_npmrds_hourly_speeds.csv",
        npmrds_hourly_speed_by_road_class_csv=f"{output_dir}/{study_area}_npmrds_hourly_speed_by_road_class.csv",
        beam_network_car_links_geo=f"{output_dir}/{study_area}_network_car_only.geojson",
        beam_npmrds_network_map_geo=f"{output_dir}/{study_area}_network_mapped_to_npmrds.geojson"
    )

    # Calculate execution time
    execution_time = (time.time() - start_time) / 60.0

    # Create plots
    plot_validation_maps(study_area, output_dir, region_boundary_wgs84,
                        regional_npmrds_station, beam_npmrds_network_map)

    return regional_npmrds_station, beam_npmrds_network_map, execution_time


def plot_validation_maps(study_area, output_dir, region_boundary, npmrds_station, network_map):
    """
    Create validation map plots

    Parameters:
    -----------
    study_area : str
        Name of the study area
    output_dir : str
        Path to output directory
    region_boundary : GeoDataFrame
        Region boundaries
    npmrds_station : GeoDataFrame
        NPMRDS stations
    network_map : GeoDataFrame
        BEAM network map
    """
    # Plot region boundaries and stations
    print("Plotting region boundaries and stations")
    plt.figure()
    fig, ax = plt.subplots()
    region_boundary.boundary.plot(ax=ax, color='black')
    npmrds_station.plot(ax=ax, color='blue')
    plt.title("Region Boundaries and NPMRDS Stations")
    fig.savefig(f"{output_dir}/{study_area}_npmrds_station.png", dpi=300)
    plt.show(block=False)

    # Plot BEAM Network and NPMRDS stations
    print("Plotting BEAM Network and NPMRDS stations")
    plt.figure()
    fig, ax = plt.subplots()
    npmrds_station.plot(ax=ax, color='blue', linewidth=2, label='NPMRDS')
    network_map.plot(ax=ax, color='red', linewidth=0.5, label='BEAM')
    plt.title("BEAM Network and NPMRDS Stations")
    fig.savefig(f"{output_dir}/{study_area}_network_mapped_to_npmrds.png", dpi=300)
    plt.show(block=False)


def main():
    """
    Main function to prepare NPMRDS data and stations
    """
    # Configuration
    study_area = "sfbay"  # or "seattle"
    batch = "20240123"
    scenario = "2018-Baseline-FC12-Bis2"

    # Setup configuration and paths
    config, config_network, config_npmrds, config_geo, network_dir, output_dir = setup_config_and_paths(
        study_area, batch, scenario
    )

    # Collect geographic boundaries
    region_boundary_wgs84, cbg_boundary_wgs84 = collect_boundaries(config, study_area)

    # Optional: TAZ boundary mapping (commented out in original script)
    # taz_boundary_wgs84 = gpd.read_file(f"{config['work_dir']}/{config_geo["taz_shp"]}").to_crs(epsg=4326)
    # map_cbg_to_taz(
    #     cbg_boundary_wgs84,
    #     config_geo["cbg_id"],
    #     taz_boundary_wgs84,
    #     config_geo["taz_id"],
    #     config_geo["utm_epsg"],
    #     study_area_cbg_taz_map_csv
    # )

    # Prepare and plot NPMRDS data
    _, _, execution_time = prepare_and_plot_npmrds_data(
        config, config_npmrds, config_geo, network_dir, output_dir, study_area, region_boundary_wgs84
    )

    print(f"Execution time of prepare_npmrds_data: {execution_time:.2f}min")


if __name__ == "__main__":
    # In Mac, you might need to cd to folder '/Applications/Python {version}'
    # and run ./Install\ Certificates.command
    main()