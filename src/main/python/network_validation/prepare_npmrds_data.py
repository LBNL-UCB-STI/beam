import sys

from _data_collection_utils import collect_geographic_boundaries
from _validation_utils import *
from pathlib import Path

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)


def collect_geographic_data(state_fips, county_fips, census_year, study_area, geo_work_dir):
    """Collect and process geographic boundary data."""
    region_boundary_wgs84 = collect_geographic_boundaries(
        state_fips,
        county_fips,
        census_year,
        study_area,
        geo_level='county',
        work_dir=geo_work_dir
    )

    cbg_boundary_wgs84 = collect_geographic_boundaries(
        state_fips,
        county_fips,
        census_year,
        study_area,
        geo_level='county',
        work_dir=geo_work_dir
    )

    return region_boundary_wgs84, cbg_boundary_wgs84


def process_npmrds_data(paths, npmrds_year, region_boundary_wgs84, utm_epsg):
    """Process NPMRDS data and map it to the BEAM network.

    Parameters:
    -----------
    paths : dict
        Dictionary containing all input and output paths
    npmrds_year : str
        Year of NPMRDS data
    region_boundary_wgs84 : GeoDataFrame
        Geographic boundary data for the region
    utm_epsg : int
        EPSG code for UTM projection
    """
    regional_npmrds_station, _, beam_npmrds_network_map, _ = prepare_npmrds_data(
        # input
        npmrds_label=f"NPMRDS_{npmrds_year}",
        npmrds_raw_geo=paths["npmrds_raw_geo"],
        npmrds_raw_data_csv=paths["npmrds_raw_data_csv"],
        npmrds_observed_speed_weight=0.5,
        region_boundary=region_boundary_wgs84,
        beam_network_csv_input=paths["beam_network_csv_input"],
        projected_crs_epsg=utm_epsg,
        distance_buffer_m=20,
        # output
        npmrds_station_geo=paths["npmrds_station_geo"],
        npmrds_data_csv=paths["npmrds_data_csv"],
        npmrds_hourly_speed_csv=paths["npmrds_hourly_speed_csv"],
        npmrds_hourly_speed_by_road_class_csv=paths["npmrds_hourly_speed_by_road_class_csv"],
        beam_network_car_links_geo=paths["beam_network_car_links_geo"],
        beam_npmrds_network_map_geo=paths["beam_npmrds_network_map_geo"])

    return regional_npmrds_station, beam_npmrds_network_map


def plot_results(output_paths, region_boundary_wgs84, regional_npmrds_station, beam_npmrds_network_map):
    """Generate and save plots of the network and stations."""
    # Plot region boundaries and stations
    plt.figure()
    fig, ax = plt.subplots()
    region_boundary_wgs84.boundary.plot(ax=ax, color='black')
    regional_npmrds_station.plot(ax=ax, color='blue')
    plt.title("Region Boundaries and NPMRDS Stations")
    fig.savefig(output_paths["station_plot"], dpi=300)
    plt.show(block=False)

    # Plot BEAM Network and NPMRDS stations
    plt.figure()
    fig, ax = plt.subplots()
    regional_npmrds_station.plot(ax=ax, color='blue', linewidth=2, label='NPMRDS')
    beam_npmrds_network_map.plot(ax=ax, color='red', linewidth=0.5, label='BEAM')
    plt.title("BEAM Network and NPMRDS Stations")
    fig.savefig(output_paths["network_plot"], dpi=300)
    plt.show(block=False)


def main():
    """Main function to prepare NPMRDS data."""
    # Start timing
    st = time.time()
    study_area = "seattle"  # or sfbay, seattle

    # Configuration parameters

    work_dir = os.path.expanduser(f"~/Workspace/Simulation/{study_area}")
    if study_area == "sfbay":
        base_configs = {
            "study_area": study_area,
            "work_dir": work_dir,
            "batch": "calibration--jdeq--20251106",
            "state_fips": "06",
            "county_fips": ['001', '013', '041', '055', '075', '081', '085', '095', '097'],
            "census_year": 2018,
            "npmrds_label": f"NPMRDS_2018",
            "utm_epsg": 26910,
            "npmrds_raw_geo": f"{work_dir}/validation/npmrds/California.shp",
            "npmrds_raw_data_csv": f'{work_dir}/validation/npmrds/al_ca_oct2018_1hr_trucks_pax.csv',
            "network_csv": f"{work_dir}/network/sfbay-area-cbg5500-network/network.csv.gz",
        }
    elif study_area == "seattle":
        base_configs = {
            "study_area": study_area,
            "work_dir": work_dir,
            "batch": "calibration--jdeq--20251106",
            "state_fips": "53",
            "county_fips": ["061", "033", "035", "053"],
            "census_year": 2018,
            "npmrds_label": f"NPMRDS_2018",
            "utm_epsg": 32048,
            "npmrds_raw_geo": f"{work_dir}/validation/NPMRDS/Washington.shp",
            "npmrds_raw_data_csv": f'{work_dir}/validation/npmrds/vt_wi_2018_1hr/vt_wi_2018_1hr.csv',
            "network_csv": f"{work_dir}/network/seattle-area-cbg120-ferry-weakConn-network/seattle-area-cbg120-ferry-weakConn-network.csv.gz",
        }
    else:
        raise ValueError("Invalid study area specified")

    npmrds_dir = os.path.dirname(base_configs["npmrds_raw_geo"])
    network_dir = os.path.dirname(base_configs["network_csv"])
    network_name = Path(Path(base_configs["network_csv"]).stem).stem
    paths = {
        # Input paths
        "geo_dir": f"{work_dir}/geo",
        "npmrds_raw_geo": base_configs["npmrds_raw_geo"],
        "npmrds_raw_data_csv": base_configs["npmrds_raw_data_csv"],
        "network_dir": network_dir,
        "beam_network_csv_input": base_configs["network_csv"],

        # Output paths
        # NPMRDS processed data
        "npmrds_station_geo": f"{npmrds_dir}/{study_area}-npmrds-station.geojson",
        "npmrds_data_csv": f"{npmrds_dir}/{study_area}-npmrds-data.csv",
        "npmrds_hourly_speed_csv": f"{npmrds_dir}/{study_area}-npmrds-hourly-speeds.csv",
        "npmrds_hourly_speed_by_road_class_csv": f"{npmrds_dir}/{study_area}-npmrds-hourly-speed-by-road-class.csv",
        "station_plot": f"{npmrds_dir}/{study_area}-npmrds-station.png",

        # NPMRDS mapped to BEAM network
        "beam_network_car_links_geo": f"{network_dir}/{network_name}--car-only.geojson",
        "beam_npmrds_network_map_geo": f"{network_dir}/{network_name}--npmrds.geojson",
        "network_plot": f"{network_dir}/{network_name}--npmrds.png"
    }

    # Collect geographic data
    region_boundary_wgs84, cbg_boundary_wgs84 = collect_geographic_data(
        base_configs["state_fips"],
        base_configs["county_fips"],
        base_configs["census_year"],
        study_area,
        paths["geo_dir"]
    )

    # Process NPMRDS data
    regional_npmrds_station, beam_npmrds_network_map = process_npmrds_data(
        paths,
        base_configs["census_year"],
        region_boundary_wgs84,
        base_configs["utm_epsg"]
    )

    # Plot results
    plot_results(
        paths,
        region_boundary_wgs84,
        regional_npmrds_station,
        beam_npmrds_network_map
    )

    # Print execution time
    print(f"Execution time of prepare_npmrds_data: {((time.time() - st) / 60.0):.2f}min")


if __name__ == "__main__":
    main()