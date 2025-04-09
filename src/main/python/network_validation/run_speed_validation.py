#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BEAM Network Validation Script

This script validates a BEAM traffic simulation network against NPMRDS data.
It performs link-level and network-level speed validations and can also validate VMT.
"""

import sys
from pathlib import Path

from _validation_utils import *

# Add parent directory to path for imports
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import get_area_config, generate_network_name
from _data_collection_utils import collect_geographic_boundaries


def setup_directories(batch, scenario, config):
    """
    Set up the necessary directories for the validation run

    Parameters:
    -----------
    study_area : str
        Name of the study area (e.g., "sfbay", "seattle")
    batch : str
        Batch identifier
    scenario : str
        Scenario name
    config : dict
        Configuration dictionary containing study area settings

    Returns:
    --------
    tuple
        study_area_dir, network_dir, run_dir, output_dir, plots_dir
    """
    study_area_dir = config["work_dir"]
    network_name = generate_network_name(config)
    network_dir = f'{config["work_dir"]}/network/{network_name}'
    run_dir = f"{config["work_dir"]}/beam-runs/{batch}/{scenario}"

    # Create output directories
    output_dir = f"{run_dir}/validation_output"
    plots_dir = f"{output_dir}/plots"
    Path(output_dir).mkdir(parents=True, exist_ok=True)
    Path(plots_dir).mkdir(parents=True, exist_ok=True)

    return study_area_dir, network_dir, run_dir, output_dir, plots_dir


def prepare_npmrds_files(study_area, run_dir, network_dir, config):
    """
    Prepare NPMRDS files if they don't exist already

    Parameters:
    -----------
    study_area : str
        Name of the study area (e.g., "sfbay", "seattle")
    run_dir : str
        Directory where the run data is stored
    network_dir : str
        Directory where the network data is stored
    config : dict
        Configuration dictionary containing study area settings

    Returns:
    --------
    tuple
        Paths to the NPMRDS hourly speed CSV, road class CSV, and network map geojson
    """
    npmrds_hourly_speed_csv = f"{run_dir}/{study_area}_npmrds_hourly_speeds.csv"
    npmrds_hourly_speed_by_road_class_csv = f"{run_dir}/{study_area}_npmrds_hourly_speed_by_road_class.csv"
    beam_network_mapped_to_npmrds_geo = f"{run_dir}/{study_area}_network_mapped_to_npmrds.geojson"

    if not (os.path.exists(npmrds_hourly_speed_csv) or
            os.path.exists(npmrds_hourly_speed_by_road_class_csv) or
            os.path.exists(beam_network_mapped_to_npmrds_geo)):
        # Collect geographic boundaries
        region_boundary_wgs84 = collect_geographic_boundaries(
            config["state_fips"],
            config["county_fips"],
            config["census_year"],
            study_area,
            geo_level='county',
            work_dir=f'{config["work_dir"]}/geo'
        )

        # Get configuration sections
        config_network = config["network"]
        config_npmrds = config_network["validation"]["npmrds"]
        config_geo = config["geo"]

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
            npmrds_station_geo=f"{run_dir}/{study_area}_npmrds_station.geojson",
            npmrds_data_csv=f"{run_dir}/{study_area}_npmrds_data.csv",
            npmrds_hourly_speed_csv=npmrds_hourly_speed_csv,
            npmrds_hourly_speed_by_road_class_csv=npmrds_hourly_speed_by_road_class_csv,
            beam_network_car_links_geo=f"{run_dir}/{study_area}_network_car_only.geojson",
            beam_npmrds_network_map_geo=beam_network_mapped_to_npmrds_geo
        )

        # Generate plots
        plot_validation_maps(study_area, run_dir, region_boundary_wgs84,
                             regional_npmrds_station, beam_npmrds_network_map)

    return npmrds_hourly_speed_csv, npmrds_hourly_speed_by_road_class_csv, beam_network_mapped_to_npmrds_geo


def plot_validation_maps(study_area, run_dir, region_boundary, npmrds_station, network_map):
    """
    Create validation map plots

    Parameters:
    -----------
    study_area : str
        Name of the study area
    run_dir : str
        Directory where the run data is stored
    region_boundary : GeoDataFrame
        GeoDataFrame containing region boundary
    npmrds_station : GeoDataFrame
        GeoDataFrame containing NPMRDS stations
    network_map : GeoDataFrame
        GeoDataFrame containing network map
    """
    # Plot region boundaries and stations
    print("Plotting region boundaries and stations")
    fig, ax = plt.subplots()
    region_boundary.boundary.plot(ax=ax, color='black')
    npmrds_station.plot(ax=ax, color='blue')
    plt.title("Region Boundaries and NPMRDS Stations")
    fig.savefig(f"{run_dir}/{study_area}_npmrds_station.png", dpi=300)
    plt.show(block=False)

    # Plot BEAM Network and NPMRDS stations
    print("Plotting BEAM Network and NPMRDS stations")
    fig, ax = plt.subplots()
    npmrds_station.plot(ax=ax, color='blue', linewidth=2, label='NPMRDS')
    network_map.plot(ax=ax, color='red', linewidth=0.5, label='BEAM')
    plt.title("BEAM Network and NPMRDS Stations")
    fig.savefig(f"{run_dir}/{study_area}_network_mapped_to_npmrds.png", dpi=300)
    plt.show(block=False)


def setup_link_stats(study_area_dir, batch, scenario, run_dir):
    """
    Set up link statistics data

    Parameters:
    -----------
    study_area_dir : str
        Directory where study area data is stored
    batch : str
        Batch identifier
    scenario : str
        Scenario name
    run_dir : str
        Directory where the run data is stored

    Returns:
    --------
    tuple
        link_stats, vehicle_types_files
    """
    batch_label = batch.replace("-", "")
    scenario_label = scenario.replace("_", "-")

    link_stats = [
        LinkStats(scenario=f"{batch}_{scenario_label}", demand_fraction=0.1,
                  file_path=os.path.join(run_dir, "3.linkstats.csv.gz"))
    ]

    vehicle_types_files = [(
        batch_label,
        scenario_label,
        f"{study_area_dir}/beam-runs/{batch}/{scenario}/0.events.csv.gz",
        f"{study_area_dir}/beam-freight/{batch}/{scenario}/vehicle-tech/ft-vehicletypes--{batch_label}--{scenario_label}.csv"
    )]

    return link_stats, vehicle_types_files


def run_network_speed_validation(study_area, setup, processed_link_stats, output_dir, plots_dir):
    """
    Run network-level speed validation

    Parameters:
    -----------
    study_area : str
        Name of the study area
    setup : SpeedValidationSetup
        Validation setup object
    processed_link_stats : list
        List of processed link statistics
    output_dir : str
        Directory to save output data
    plots_dir : str
        Directory to save plots
    """
    print("Running network-level speed validation...")
    hourly_speed_by_road_class = setup.get_hourly_average_speed_by_road_class(processed_link_stats)
    hourly_speed_by_road_class_no_npmrds = hourly_speed_by_road_class[
        ~hourly_speed_by_road_class['scenario'].str.contains("npmrds", case=False, na=False)
    ]

    # Plot hourly network speed by road class
    plt.figure()
    g = sns.relplot(
        x='hour', y='speed', hue='road_class', col='scenario', kind="line",
        data=hourly_speed_by_road_class_no_npmrds,
        errorbar=('ci', 95), facet_kws={'sharey': True, 'sharex': True}
    )
    g.set_titles("{col_name}")
    g.fig.suptitle('Network-level Speed Validation by Road Class', fontsize=16, y=0.98)
    g.set_xlabels("Hour")
    g.set_ylabels("Speed (mph)")
    g.legend.set_title("Road Category")
    plt.subplots_adjust(top=0.85)
    plt.ylim([0, 70])
    plt.savefig(f"{plots_dir}/{study_area}_beam_npmrds_network_speed_road_class_validation.png", dpi=200)
    plt.show(block=False)

    # Save data
    hourly_speed_by_road_class_no_npmrds.to_csv(
        f"{output_dir}/{study_area}_beam_npmrds_network_speed_road_class_validation.csv", index=False
    )


def run_link_speed_validation(study_area, setup, processed_link_stats, output_dir, plots_dir):
    """
    Run link-level speed validation

    Parameters:
    -----------
    study_area : str
        Name of the study area
    setup : SpeedValidationSetup
        Validation setup object
    processed_link_stats : list
        List of processed link statistics
    output_dir : str
        Directory to save output data
    plots_dir : str
        Directory to save plots
    """
    print("Running link-level speed validation...")
    hourly_link_speed = setup.get_hourly_link_speed(processed_link_stats)

    # Plot hourly link speed
    plt.figure()
    sns.lineplot(x='hour', y='speed', hue='scenario', data=hourly_link_speed, errorbar=('ci', 95))
    plt.ylim([0, 70])
    plt.title("Link-level Speed Validation")
    plt.savefig(f"{plots_dir}/{study_area}_beam_npmrds_link_speed_validation.png", dpi=200)
    plt.show(block=False)

    # Get hourly link speed by road class
    hourly_link_speed_by_road_class = setup.get_hourly_link_speed_by_road_class(processed_link_stats)

    # Plot hourly link speed by road class
    plt.figure()
    road_class_order = list(fsystem_to_roadclass_lookup.values())
    g = sns.relplot(
        x='hour', y='speed', hue='road_class', col='scenario', kind="line",
        hue_order=road_class_order,
        data=hourly_link_speed_by_road_class,
        errorbar=('ci', 95), facet_kws={'sharey': True, 'sharex': True}
    )
    g.set_titles("{col_name}")
    g.fig.suptitle('Link-Level Speed Validation by Road Class', fontsize=16, y=0.98)
    g.set_xlabels("Hour")
    g.set_ylabels("Speed (mph)")
    g.legend.set_title("Road Category")
    plt.subplots_adjust(top=0.85)
    plt.ylim([0, 70])
    plt.savefig(f"{plots_dir}/{study_area}_beam_npmrds_link_speed_road_class_validation.png", dpi=200)
    plt.show(block=False)

    # Save data
    hourly_link_speed_by_road_class.to_csv(
        f"{output_dir}/{study_area}_beam_npmrds_link_speed_road_class_validation.csv", index=False
    )


def run_vmt_validation(vehicle_types_files):
    """
    Run VMT validation
    This function is a placeholder for the VMT validation code that was commented out
    in the original script.

    Parameters:
    -----------
    vehicle_types_files : list
        List of tuples containing vehicle type file information
    """
    print("VMT validation is not currently implemented")
    # Commented code from the original script would go here


def main():
    """
    Main function to run the validation process
    """
    # Configuration
    study_area = "sfbay"  # or "seattle"
    batch = "20240123"
    scenario = "2018-Baseline-FC12-Bis2"
    do_link_speed_validation = True
    do_network_speed_validation = True
    do_vmt_validation = False

    # Load configuration
    config = get_area_config(study_area)
    config["network"]["graph_layers"]["residential"]["min_density_per_km2"] = 5500

    # Setup directories
    study_area_dir, network_dir, run_dir, output_dir, plots_dir = setup_directories(batch, scenario, config)

    # Setup link stats
    link_stats, vehicle_types_files = setup_link_stats(study_area_dir, batch, scenario, run_dir)

    # Prepare NPMRDS files
    npmrds_hourly_speed_csv, npmrds_hourly_speed_by_road_class_csv, beam_network_mapped_to_npmrds_geo = \
        prepare_npmrds_files(study_area, run_dir, network_dir, config)

    # Initialize validation setup
    setup = SpeedValidationSetup(
        npmrds_hourly_speed_csv=npmrds_hourly_speed_csv,
        npmrds_hourly_speed_by_road_class_csv=npmrds_hourly_speed_by_road_class_csv,
        beam_network_mapped_to_npmrds_geo=beam_network_mapped_to_npmrds_geo
    )

    # Process link stats if needed
    if do_link_speed_validation or do_network_speed_validation or do_vmt_validation:
        print(f"Processing link stats: {link_stats}")
        processed_link_stats = setup.process_these_link_stats(
            link_stats=link_stats, assume_daylight_saving=True
        )
    else:
        processed_link_stats = None

    # Run validations as requested
    if do_network_speed_validation:
        run_network_speed_validation(
            study_area, setup, processed_link_stats, output_dir, plots_dir
        )

    if do_link_speed_validation:
        run_link_speed_validation(
            study_area, setup, processed_link_stats, output_dir, plots_dir
        )

    if do_vmt_validation:
        run_vmt_validation(vehicle_types_files)

    print("Validation complete!")


if __name__ == "__main__":
    main()