#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BEAM Network Validation Script

This script validates a BEAM traffic simulation network against NPMRDS data.
It performs link-level and network-level speed validations and can also validate VMT.
"""

import json
import os
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns

from _validation_utils import prepare_npmrds_data, fsystem_to_roadclass_lookup, LinkStats, SpeedValidationSetup

# Add parent directory to path for imports
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)
plt.style.use('ggplot')

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
    beam_network_car_links_geo = f"{run_dir}/{study_area}_network_car_only.geojson"

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
        regional_npmrds_station, _, beam_npmrds_network_map, _, beam_network_car_links_geo = prepare_npmrds_data(
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

    return npmrds_hourly_speed_csv, npmrds_hourly_speed_by_road_class_csv, beam_network_mapped_to_npmrds_geo, beam_network_car_links_geo


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


def generate_validation_stats(setup, processed_link_stats, output_dir, study_area, peak_hour):
    """
    Generate comprehensive statistics for both network and link validation,
    including NPMRDS comparison data, speed analysis by hour, and link counts

    Parameters:
    -----------
    setup : SpeedValidationSetup
        Validation setup object
    processed_link_stats : list
        List of processed link statistics
    output_dir : str
        Directory to save output data
    study_area : str
        Name of the study area
    peak_hour : int
        The peak hour (e.g., 8 for 8 AM)

    Returns:
    --------
    dict
        Dictionary containing all generated statistics
    """
    stats_results = {
        "metadata": {
            "peak_hour": peak_hour,
            "study_area": study_area
        },
        "network_validation": {},
        "link_validation": {},
        "npmrds": {},
        "link_counts": {
            "network": {
                "overall": 0,
                "by_road_class": {}
            },
            "link": {
                "overall": 0,
                "by_road_class": {}
            },
            "npmrds": {
                "overall": 0,
                "by_road_class": {}
            }
        },
        "lowest_speeds": {
            "network": {
                "overall": {},
                "by_road_class": {}
            },
            "link": {
                "overall": {},
                "by_road_class": {}
            },
            "npmrds": {
                "overall": {},
                "by_road_class": {}
            }
        },
        "highest_speeds": {
            "network": {
                "overall": {},
                "by_road_class": {}
            },
            "link": {
                "overall": {},
                "by_road_class": {}
            },
            "npmrds": {
                "overall": {},
                "by_road_class": {}
            }
        }
    }

    # Get the data
    hourly_speed_by_road_class = setup.get_hourly_average_speed_by_road_class(processed_link_stats)

    # Separate NPMRDS data from simulation data
    npmrds_data = hourly_speed_by_road_class[
        hourly_speed_by_road_class['scenario'].str.contains("npmrds", case=False, na=False)
    ]

    hourly_speed_by_road_class_no_npmrds = hourly_speed_by_road_class[
        ~hourly_speed_by_road_class['scenario'].str.contains("npmrds", case=False, na=False)
    ]

    hourly_link_speed = setup.get_hourly_link_speed(processed_link_stats)
    hourly_link_speed_by_road_class = setup.get_hourly_link_speed_by_road_class(processed_link_stats)

    # Process data to get modes (if available)
    has_mode_data = 'mode' in hourly_speed_by_road_class.columns

    # Get the original network data and mapped data from the setup object
    npmrds_beam_network = setup.beam_npmrds_network_map
    beam_network = setup.beam_network_car_links_geo

    # 0. Calculate link counts
    link_counts = stats_results["link_counts"]

    # Network-level link counts
    # Get unique link IDs from the network map data
    unique_network_links = beam_network['linkId'].nunique()
    link_counts["network"]["overall"] = unique_network_links
    road_class_counts = beam_network.groupby('road_class')['linkId'].nunique().to_dict()
    link_counts["network"]["by_road_class"] = road_class_counts

    # Link-level link counts (from hourly_link_speed)
    # Count unique link IDs
    unique_link_ids = npmrds_beam_network['link'].nunique()
    link_counts["link"]["overall"] = unique_link_ids
    road_class_counts = npmrds_beam_network.groupby('road_class')['link'].nunique().to_dict()
    link_counts["link"]["by_road_class"] = road_class_counts

    # NPMRDS link counts
    unique_npmrds_links = npmrds_data['tmc'].nunique()
    link_counts["npmrds"]["overall"] = unique_npmrds_links
    npmrds_road_class_counts = npmrds_data.groupby('road_class')['tmc'].nunique().to_dict()
    link_counts["npmrds"]["by_road_class"] = npmrds_road_class_counts

    # 1. Network Validation Stats
    print("Generating network validation statistics...")
    network_stats = stats_results["network_validation"]

    # Overall average speed
    network_stats["overall_avg_speed"] = hourly_speed_by_road_class_no_npmrds['speed'].mean()

    # Average speed per road category
    network_stats["avg_speed_by_road_class"] = hourly_speed_by_road_class_no_npmrds.groupby('road_class')[
        'speed'].mean().to_dict()

    # Average speed at peak hour - Overall only
    peak_hour_data = hourly_speed_by_road_class_no_npmrds[hourly_speed_by_road_class_no_npmrds['hour'] == peak_hour]
    network_stats[f"avg_speed_hour_{peak_hour}"] = peak_hour_data['speed'].mean()

    # Average speed per mode (if available)
    if has_mode_data:
        network_stats["avg_speed_by_mode"] = hourly_speed_by_road_class_no_npmrds.groupby('mode')[
            'speed'].mean().to_dict()
        network_stats[f"avg_speed_hour_{peak_hour}_by_mode"] = peak_hour_data.groupby('mode')['speed'].mean().to_dict()

    # 2. Link Validation Stats
    print("Generating link validation statistics...")
    link_stats = stats_results["link_validation"]

    # Overall average speed
    link_stats["overall_avg_speed"] = hourly_link_speed['speed'].mean()

    # Average speed per road category
    link_stats["avg_speed_by_road_class"] = hourly_link_speed_by_road_class.groupby('road_class')[
        'speed'].mean().to_dict()

    # Average speed at peak hour - Overall only
    link_stats[f"avg_speed_hour_{peak_hour}"] = hourly_link_speed[hourly_link_speed['hour'] == peak_hour][
        'speed'].mean()

    # Average speed per mode (if available)
    if 'mode' in hourly_link_speed.columns:
        link_stats["avg_speed_by_mode"] = hourly_link_speed.groupby('mode')['speed'].mean().to_dict()
        link_stats[f"avg_speed_hour_{peak_hour}_by_mode"] = \
            hourly_link_speed[hourly_link_speed['hour'] == peak_hour].groupby('mode')['speed'].mean().to_dict()

    # 3. NPMRDS Stats
    print("Generating NPMRDS statistics...")
    npmrds_stats = stats_results["npmrds"]

    if not npmrds_data.empty:
        # Overall average speed
        npmrds_stats["overall_avg_speed"] = npmrds_data['speed'].mean()

        # Average speed per road category
        npmrds_stats["avg_speed_by_road_class"] = npmrds_data.groupby('road_class')['speed'].mean().to_dict()

        # Average speed at peak hour - Overall and by road category
        npmrds_peak_hour_data = npmrds_data[npmrds_data['hour'] == peak_hour]
        npmrds_stats[f"avg_speed_hour_{peak_hour}"] = npmrds_peak_hour_data['speed'].mean()
        npmrds_stats[f"avg_speed_hour_{peak_hour}_by_road_class"] = npmrds_peak_hour_data.groupby('road_class')[
            'speed'].mean().to_dict()

        # Average speed per mode (if available)
        if has_mode_data and 'mode' in npmrds_data.columns:
            npmrds_stats["avg_speed_by_mode"] = npmrds_data.groupby('mode')['speed'].mean().to_dict()
            npmrds_stats[f"avg_speed_hour_{peak_hour}_by_mode"] = npmrds_peak_hour_data.groupby('mode')[
                'speed'].mean().to_dict()
    else:
        print("Warning: No NPMRDS data found in the processed link stats")

    # 4. Lowest Speed Analysis
    print("Analyzing hours with lowest speeds...")
    lowest_speeds = stats_results["lowest_speeds"]

    # 4.1 Network - Overall lowest speed
    network_hourly_avg = hourly_speed_by_road_class_no_npmrds.groupby('hour')['speed'].mean().reset_index()
    network_min_hour_row = network_hourly_avg.loc[network_hourly_avg['speed'].idxmin()]
    lowest_speeds["network"]["overall"] = {
        "hour": int(network_min_hour_row['hour']),
        "speed": float(network_min_hour_row['speed'])
    }

    # 4.2 Network - Lowest speed by road class
    for road_class in hourly_speed_by_road_class_no_npmrds['road_class'].unique():
        road_class_data = hourly_speed_by_road_class_no_npmrds[
            hourly_speed_by_road_class_no_npmrds['road_class'] == road_class
            ]
        if not road_class_data.empty:
            road_class_hourly_avg = road_class_data.groupby('hour')['speed'].mean().reset_index()
            min_hour_row = road_class_hourly_avg.loc[road_class_hourly_avg['speed'].idxmin()]
            lowest_speeds["network"]["by_road_class"][road_class] = {
                "hour": int(min_hour_row['hour']),
                "speed": float(min_hour_row['speed'])
            }

    # 4.3 Link - Overall lowest speed
    link_hourly_avg = hourly_link_speed.groupby('hour')['speed'].mean().reset_index()
    link_min_hour_row = link_hourly_avg.loc[link_hourly_avg['speed'].idxmin()]
    lowest_speeds["link"]["overall"] = {
        "hour": int(link_min_hour_row['hour']),
        "speed": float(link_min_hour_row['speed'])
    }

    # 4.4 Link - Lowest speed by road class
    for road_class in hourly_link_speed_by_road_class['road_class'].unique():
        road_class_data = hourly_link_speed_by_road_class[
            hourly_link_speed_by_road_class['road_class'] == road_class
            ]
        if not road_class_data.empty:
            road_class_hourly_avg = road_class_data.groupby('hour')['speed'].mean().reset_index()
            min_hour_row = road_class_hourly_avg.loc[road_class_hourly_avg['speed'].idxmin()]
            lowest_speeds["link"]["by_road_class"][road_class] = {
                "hour": int(min_hour_row['hour']),
                "speed": float(min_hour_row['speed'])
            }

    # 4.5 NPMRDS - Overall lowest speed
    if not npmrds_data.empty:
        npmrds_hourly_avg = npmrds_data.groupby('hour')['speed'].mean().reset_index()
        npmrds_min_hour_row = npmrds_hourly_avg.loc[npmrds_hourly_avg['speed'].idxmin()]
        lowest_speeds["npmrds"]["overall"] = {
            "hour": int(npmrds_min_hour_row['hour']),
            "speed": float(npmrds_min_hour_row['speed'])
        }

        # 4.6 NPMRDS - Lowest speed by road class
        for road_class in npmrds_data['road_class'].unique():
            road_class_data = npmrds_data[npmrds_data['road_class'] == road_class]
            if not road_class_data.empty:
                road_class_hourly_avg = road_class_data.groupby('hour')['speed'].mean().reset_index()
                min_hour_row = road_class_hourly_avg.loc[road_class_hourly_avg['speed'].idxmin()]
                lowest_speeds["npmrds"]["by_road_class"][road_class] = {
                    "hour": int(min_hour_row['hour']),
                    "speed": float(min_hour_row['speed'])
                }

    # 5. Highest Speed Analysis
    print("Analyzing hours with highest speeds...")
    highest_speeds = stats_results["highest_speeds"]

    # 5.1 Network - Overall highest speed
    network_max_hour_row = network_hourly_avg.loc[network_hourly_avg['speed'].idxmax()]
    highest_speeds["network"]["overall"] = {
        "hour": int(network_max_hour_row['hour']),
        "speed": float(network_max_hour_row['speed'])
    }

    # 5.2 Network - Highest speed by road class
    for road_class in hourly_speed_by_road_class_no_npmrds['road_class'].unique():
        road_class_data = hourly_speed_by_road_class_no_npmrds[
            hourly_speed_by_road_class_no_npmrds['road_class'] == road_class
            ]
        if not road_class_data.empty:
            road_class_hourly_avg = road_class_data.groupby('hour')['speed'].mean().reset_index()
            max_hour_row = road_class_hourly_avg.loc[road_class_hourly_avg['speed'].idxmax()]
            highest_speeds["network"]["by_road_class"][road_class] = {
                "hour": int(max_hour_row['hour']),
                "speed": float(max_hour_row['speed'])
            }

    # 5.3 Link - Overall highest speed
    link_max_hour_row = link_hourly_avg.loc[link_hourly_avg['speed'].idxmax()]
    highest_speeds["link"]["overall"] = {
        "hour": int(link_max_hour_row['hour']),
        "speed": float(link_max_hour_row['speed'])
    }

    # 5.4 Link - Highest speed by road class
    for road_class in hourly_link_speed_by_road_class['road_class'].unique():
        road_class_data = hourly_link_speed_by_road_class[
            hourly_link_speed_by_road_class['road_class'] == road_class
            ]
        if not road_class_data.empty:
            road_class_hourly_avg = road_class_data.groupby('hour')['speed'].mean().reset_index()
            max_hour_row = road_class_hourly_avg.loc[road_class_hourly_avg['speed'].idxmax()]
            highest_speeds["link"]["by_road_class"][road_class] = {
                "hour": int(max_hour_row['hour']),
                "speed": float(max_hour_row['speed'])
            }

    # 5.5 NPMRDS - Overall highest speed
    if not npmrds_data.empty:
        npmrds_max_hour_row = npmrds_hourly_avg.loc[npmrds_hourly_avg['speed'].idxmax()]
        highest_speeds["npmrds"]["overall"] = {
            "hour": int(npmrds_max_hour_row['hour']),
            "speed": float(npmrds_max_hour_row['speed'])
        }

        # 5.6 NPMRDS - Highest speed by road class
        for road_class in npmrds_data['road_class'].unique():
            road_class_data = npmrds_data[npmrds_data['road_class'] == road_class]
            if not road_class_data.empty:
                road_class_hourly_avg = road_class_data.groupby('hour')['speed'].mean().reset_index()
                max_hour_row = road_class_hourly_avg.loc[road_class_hourly_avg['speed'].idxmax()]
                highest_speeds["npmrds"]["by_road_class"][road_class] = {
                    "hour": int(max_hour_row['hour']),
                    "speed": float(max_hour_row['speed'])
                }

    # Save to JSON file
    with open(f"{output_dir}/{study_area}_validation_stats.json", 'w') as f:
        json.dump(stats_results, f, indent=4)

    return stats_results


def save_stats_to_file(stats_results, output_dir, study_area, peak_hour):
    """
    Save statistics to CSV files

    Parameters:
    -----------
    stats_results : dict
        Dictionary containing all the statistics
    output_dir : str
        Directory to save output data
    study_area : str
        Name of the study area
    peak_hour : int
        The peak hour (e.g., 9 for 9 AM)
    """
    # Network validation
    network_stats = stats_results["network_validation"]

    # Overall stats including peak hour
    network_overall_df = pd.DataFrame({
        'metric': ['overall_avg_speed', f'avg_speed_hour_{peak_hour}'],
        'value': [network_stats['overall_avg_speed'], network_stats[f'avg_speed_hour_{peak_hour}']],
        'peak_hour': [None, peak_hour]  # Add peak hour info
    })
    network_overall_df.to_csv(f"{output_dir}/{study_area}_network_overall_stats.csv", index=False)

    # Road class stats
    network_road_class_rows = []
    for road_class, speed in network_stats['avg_speed_by_road_class'].items():
        network_road_class_rows.append({
            'road_class': road_class,
            'avg_speed': speed,
            'peak_hour_used': peak_hour  # Add peak hour info
        })

    network_road_class_df = pd.DataFrame(network_road_class_rows)
    network_road_class_df.to_csv(f"{output_dir}/{study_area}_network_road_class_stats.csv", index=False)

    # Mode stats (if available)
    if 'avg_speed_by_mode' in network_stats:
        network_mode_rows = []
        for mode, speed in network_stats['avg_speed_by_mode'].items():
            row_data = {
                'mode': mode,
                'avg_speed': speed,
                'peak_hour_used': peak_hour  # Add peak hour info
            }
            if f'avg_speed_hour_{peak_hour}_by_mode' in network_stats:
                row_data[f'avg_speed_hour_{peak_hour}'] = network_stats[f'avg_speed_hour_{peak_hour}_by_mode'].get(mode,
                                                                                                                   float(
                                                                                                                       'nan'))

            network_mode_rows.append(row_data)

        network_mode_df = pd.DataFrame(network_mode_rows)
        network_mode_df.to_csv(f"{output_dir}/{study_area}_network_mode_stats.csv", index=False)

    # Link validation
    link_stats = stats_results["link_validation"]

    # Overall stats including peak hour
    link_overall_df = pd.DataFrame({
        'metric': ['overall_avg_speed', f'avg_speed_hour_{peak_hour}'],
        'value': [link_stats['overall_avg_speed'], link_stats[f'avg_speed_hour_{peak_hour}']],
        'peak_hour': [None, peak_hour]  # Add peak hour info
    })
    link_overall_df.to_csv(f"{output_dir}/{study_area}_link_overall_stats.csv", index=False)

    # Road class stats
    link_road_class_rows = []
    for road_class, speed in link_stats['avg_speed_by_road_class'].items():
        link_road_class_rows.append({
            'road_class': road_class,
            'avg_speed': speed,
            'peak_hour_used': peak_hour  # Add peak hour info
        })

    link_road_class_df = pd.DataFrame(link_road_class_rows)
    link_road_class_df.to_csv(f"{output_dir}/{study_area}_link_road_class_stats.csv", index=False)

    # Mode stats (if available)
    if 'avg_speed_by_mode' in link_stats:
        link_mode_rows = []
        for mode, speed in link_stats['avg_speed_by_mode'].items():
            row_data = {
                'mode': mode,
                'avg_speed': speed,
                'peak_hour_used': peak_hour  # Add peak hour info
            }
            if f'avg_speed_hour_{peak_hour}_by_mode' in link_stats:
                row_data[f'avg_speed_hour_{peak_hour}'] = link_stats[f'avg_speed_hour_{peak_hour}_by_mode'].get(mode,
                                                                                                                float(
                                                                                                                    'nan'))

            link_mode_rows.append(row_data)

        link_mode_df = pd.DataFrame(link_mode_rows)
        link_mode_df.to_csv(f"{output_dir}/{study_area}_link_mode_stats.csv", index=False)

    # NPMRDS Stats (if available)
    npmrds_stats = stats_results.get("npmrds", {})
    if npmrds_stats:
        # Overall stats including peak hour
        npmrds_overall_df = pd.DataFrame({
            'metric': ['overall_avg_speed', f'avg_speed_hour_{peak_hour}'],
            'value': [npmrds_stats.get('overall_avg_speed', float('nan')),
                      npmrds_stats.get(f'avg_speed_hour_{peak_hour}', float('nan'))],
            'peak_hour': [None, peak_hour]  # Add peak hour info
        })
        npmrds_overall_df.to_csv(f"{output_dir}/{study_area}_npmrds_overall_stats.csv", index=False)

        # Road class stats
        if 'avg_speed_by_road_class' in npmrds_stats:
            npmrds_road_class_rows = []
            for road_class, speed in npmrds_stats['avg_speed_by_road_class'].items():
                row_data = {
                    'road_class': road_class,
                    'avg_speed': speed,
                    'peak_hour_used': peak_hour  # Add peak hour info
                }
                if f'avg_speed_hour_{peak_hour}_by_road_class' in npmrds_stats:
                    row_data[f'avg_speed_hour_{peak_hour}'] = npmrds_stats[
                        f'avg_speed_hour_{peak_hour}_by_road_class'].get(road_class, float('nan'))

                npmrds_road_class_rows.append(row_data)

            npmrds_road_class_df = pd.DataFrame(npmrds_road_class_rows)
            npmrds_road_class_df.to_csv(f"{output_dir}/{study_area}_npmrds_road_class_stats.csv", index=False)

    # Create a special file combining all data sources for peak hour (9 AM)
    peak_hour_stats = {
        'metadata': {
            'peak_hour': peak_hour,
            'study_area': study_area
        },
        'network_overall': network_stats.get(f'avg_speed_hour_{peak_hour}', float('nan')),
        'link_overall': link_stats.get(f'avg_speed_hour_{peak_hour}', float('nan'))
    }

    if npmrds_stats:
        peak_hour_stats['npmrds_overall'] = npmrds_stats.get(f'avg_speed_hour_{peak_hour}', float('nan'))
        if f'avg_speed_hour_{peak_hour}_by_road_class' in npmrds_stats:
            peak_hour_stats['npmrds_by_road_class'] = npmrds_stats[f'avg_speed_hour_{peak_hour}_by_road_class']

    # Add mode data if available
    if f'avg_speed_hour_{peak_hour}_by_mode' in network_stats:
        peak_hour_stats['network_by_mode'] = network_stats[f'avg_speed_hour_{peak_hour}_by_mode']

    if f'avg_speed_hour_{peak_hour}_by_mode' in link_stats:
        peak_hour_stats['link_by_mode'] = link_stats[f'avg_speed_hour_{peak_hour}_by_mode']

    # Save peak hour stats to JSON
    with open(f"{output_dir}/{study_area}_peak_hour_{peak_hour}_stats.json", 'w') as f:
        json.dump(peak_hour_stats, f, indent=4)

    # Create a simple CSV for peak hour comparison
    peak_hour_rows = [
        {'data_source': 'network', 'metric': 'overall', 'speed': peak_hour_stats['network_overall'],
         'peak_hour': peak_hour},
        {'data_source': 'link', 'metric': 'overall', 'speed': peak_hour_stats['link_overall'], 'peak_hour': peak_hour}
    ]

    if 'npmrds_overall' in peak_hour_stats:
        peak_hour_rows.append({
            'data_source': 'npmrds',
            'metric': 'overall',
            'speed': peak_hour_stats['npmrds_overall'],
            'peak_hour': peak_hour
        })

    # Add road class data for NPMRDS
    if 'npmrds_by_road_class' in peak_hour_stats:
        for road_class, speed in peak_hour_stats['npmrds_by_road_class'].items():
            peak_hour_rows.append({
                'data_source': 'npmrds',
                'metric': f'road_class_{road_class}',
                'speed': speed,
                'peak_hour': peak_hour
            })

    # Add mode data
    if 'network_by_mode' in peak_hour_stats:
        for mode, speed in peak_hour_stats['network_by_mode'].items():
            peak_hour_rows.append({
                'data_source': 'network',
                'metric': f'mode_{mode}',
                'speed': speed,
                'peak_hour': peak_hour
            })

    if 'link_by_mode' in peak_hour_stats:
        for mode, speed in peak_hour_stats['link_by_mode'].items():
            peak_hour_rows.append({
                'data_source': 'link',
                'metric': f'mode_{mode}',
                'speed': speed,
                'peak_hour': peak_hour
            })

    peak_hour_df = pd.DataFrame(peak_hour_rows)
    peak_hour_df.to_csv(f"{output_dir}/{study_area}_peak_hour_{peak_hour}_stats.csv", index=False)

    # Create a comparative stats file (BEAM vs NPMRDS)
    if npmrds_stats:
        # Overall comparison
        comparison_rows = [{
            'metric': 'overall_avg_speed',
            'network': network_stats.get('overall_avg_speed', float('nan')),
            'link': link_stats.get('overall_avg_speed', float('nan')),
            'npmrds': npmrds_stats.get('overall_avg_speed', float('nan')),
            'peak_hour_used': peak_hour  # Add peak hour info
        }, {
            'metric': f'avg_speed_hour_{peak_hour}',
            'network': network_stats.get(f'avg_speed_hour_{peak_hour}', float('nan')),
            'link': link_stats.get(f'avg_speed_hour_{peak_hour}', float('nan')),
            'npmrds': npmrds_stats.get(f'avg_speed_hour_{peak_hour}', float('nan')),
            'peak_hour': peak_hour  # Add peak hour info
        }]

        # Road class comparisons (if available)
        if ('avg_speed_by_road_class' in network_stats and
                'avg_speed_by_road_class' in link_stats and
                'avg_speed_by_road_class' in npmrds_stats):

            # Get the union of all road classes
            all_road_classes = set()
            all_road_classes.update(network_stats['avg_speed_by_road_class'].keys())
            all_road_classes.update(link_stats['avg_speed_by_road_class'].keys())
            all_road_classes.update(npmrds_stats['avg_speed_by_road_class'].keys())

            for road_class in all_road_classes:
                comparison_rows.append({
                    'metric': f'avg_speed_road_class_{road_class}',
                    'network': network_stats['avg_speed_by_road_class'].get(road_class, float('nan')),
                    'link': link_stats['avg_speed_by_road_class'].get(road_class, float('nan')),
                    'npmrds': npmrds_stats['avg_speed_by_road_class'].get(road_class, float('nan')),
                    'peak_hour_used': peak_hour  # Add peak hour info
                })

        comparison_df = pd.DataFrame(comparison_rows)
        comparison_df.to_csv(f"{output_dir}/{study_area}_beam_npmrds_comparison.csv", index=False)


def main():
    """
    Main function to run the validation process
    """
    # Configuration
    study_area = "sfbay"  # or "seattle"
    batch = "20240123"
    scenario = "2018-Baseline-FC10-1-Bis2"
    peak_hour = 8
    do_link_speed_validation = True
    do_network_speed_validation = True
    do_vmt_validation = False
    generate_stats = True  # Flag to control stats generation

    # Load configuration
    config = get_area_config(study_area)
    config["network"]["graph_layers"]["residential"]["min_density_per_km2"] = 5500

    # Setup directories
    study_area_dir, network_dir, run_dir, output_dir, plots_dir = setup_directories(batch, scenario, config)

    # Setup link stats
    link_stats, vehicle_types_files = setup_link_stats(study_area_dir, batch, scenario, run_dir)

    # Prepare NPMRDS files
    npmrds_hourly_speed_csv, npmrds_hourly_speed_by_road_class_csv, beam_network_mapped_to_npmrds_geo, beam_network_car_links_geo = \
        prepare_npmrds_files(study_area, run_dir, network_dir, config)

    # Initialize validation setup
    setup = SpeedValidationSetup(
        npmrds_hourly_speed_csv=npmrds_hourly_speed_csv,
        npmrds_hourly_speed_by_road_class_csv=npmrds_hourly_speed_by_road_class_csv,
        beam_network_mapped_to_npmrds_geo=beam_network_mapped_to_npmrds_geo,
        beam_network_car_links_geo=beam_network_car_links_geo
    )

    # Process link stats if needed
    if do_link_speed_validation or do_network_speed_validation or do_vmt_validation or generate_stats:
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

    # Generate comprehensive statistics if requested
    if generate_stats and processed_link_stats is not None:
        print("Generating comprehensive validation statistics...")
        stats_results = generate_validation_stats(
            setup, processed_link_stats, output_dir, study_area, peak_hour
        )
        print(f"Statistics saved to {output_dir}/{study_area}_validation_stats.json")

        # Print a summary of the lowest speed hours
        lowest_speeds = stats_results["lowest_speeds"]
        highest_speeds = stats_results["highest_speeds"]

        print("\n=== SPEED SUMMARY ===")

        print("\n--- LOWEST SPEEDS ---")
        print(
            f"Network overall: Hour {lowest_speeds['network']['overall']['hour']}: {lowest_speeds['network']['overall']['speed']:.2f} mph")
        print(
            f"Link overall: Hour {lowest_speeds['link']['overall']['hour']}: {lowest_speeds['link']['overall']['speed']:.2f} mph")

        if "hour" in lowest_speeds["npmrds"]["overall"]:
            print(
                f"NPMRDS overall: Hour {lowest_speeds['npmrds']['overall']['hour']}: {lowest_speeds['npmrds']['overall']['speed']:.2f} mph")

        print("\nNetwork lowest speeds by road class:")
        for road_class, data in sorted(lowest_speeds["network"]["by_road_class"].items()):
            print(f"  {road_class}: Hour {data['hour']}: {data['speed']:.2f} mph")

        print("\nLink lowest speeds by road class:")
        for road_class, data in sorted(lowest_speeds["link"]["by_road_class"].items()):
            print(f"  {road_class}: Hour {data['hour']}: {data['speed']:.2f} mph")

        if lowest_speeds["npmrds"]["by_road_class"]:
            print("\nNPMRDS lowest speeds by road class:")
            for road_class, data in sorted(lowest_speeds["npmrds"]["by_road_class"].items()):
                print(f"  {road_class}: Hour {data['hour']}: {data['speed']:.2f} mph")

        print("\n--- HIGHEST SPEEDS ---")
        print(
            f"Network overall: Hour {highest_speeds['network']['overall']['hour']}: {highest_speeds['network']['overall']['speed']:.2f} mph")
        print(
            f"Link overall: Hour {highest_speeds['link']['overall']['hour']}: {highest_speeds['link']['overall']['speed']:.2f} mph")

        if "hour" in highest_speeds["npmrds"]["overall"]:
            print(
                f"NPMRDS overall: Hour {highest_speeds['npmrds']['overall']['hour']}: {highest_speeds['npmrds']['overall']['speed']:.2f} mph")

        print("\nNetwork highest speeds by road class:")
        for road_class, data in sorted(highest_speeds["network"]["by_road_class"].items()):
            print(f"  {road_class}: Hour {data['hour']}: {data['speed']:.2f} mph")

        print("\nLink highest speeds by road class:")
        for road_class, data in sorted(highest_speeds["link"]["by_road_class"].items()):
            print(f"  {road_class}: Hour {data['hour']}: {data['speed']:.2f} mph")

        if highest_speeds["npmrds"]["by_road_class"]:
            print("\nNPMRDS highest speeds by road class:")
            for road_class, data in sorted(highest_speeds["npmrds"]["by_road_class"].items()):
                print(f"  {road_class}: Hour {data['hour']}: {data['speed']:.2f} mph")

    print("\nValidation complete!")


if __name__ == "__main__":
    main()