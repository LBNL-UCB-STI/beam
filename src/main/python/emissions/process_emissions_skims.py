import sys
import os
import pandas as pd
from pathlib import Path

from _beam_emissions_plotting import plot_hourly_emissions_by_scenario_class_fuel
from _beam_emissions_plotting import plot_pollution_variability_by_process_vehicle_types
from _beam_emissions_plotting import plot_hourly_activity
from _beam_emissions_plotting import plot_pollutants_by_process
from _beam_emissions_plotting import plot_multi_pie_emfac_famos_vmt
from _beam_emissions_plotting import plot_hourly_vmt
from _emissions_utils import read_skims_emissions_chunked

# Get the absolute path to the directory containing this script
current_dir = os.path.dirname(os.path.abspath(__file__))
parent_dir = os.path.dirname(os.path.dirname(current_dir))
sys.path.insert(0, parent_dir)

from python.utils.study_area_config import get_area_config
from python.utils.study_area_config import get_fuel_key
from python.utils.study_area_config import generate_network_name
from python.utils.network_utils import load_network

# Configure pandas display options
pd.set_option('display.max_columns', 20)


def create_model_vmt_comparison_chart(skims_data, emfac_vmt, output_dir):
    """
    Create a comparison chart between EMFAC and FAMOS VMT data

    Args:
        skims_data: Processed skims data
        output_dir: Directory to save output

    Returns:
        DataFrame with combined EMFAC and FAMOS VMT data
    """
    df = emfac_vmt.copy()
    df["fuel_class"] = df["mappedFuel"] + "-" + df["mappedClass"]
    emfac_vmt = df.groupby(["fuel_class"])["total_vmt"].sum().reset_index()
    emfac_vmt.rename(columns={'total_vmt': 'mvmt'}, inplace=True)
    emfac_vmt["model"] = "emfac"

    beam_vmt = skims_data.groupby(["mappedClass", "mappedFuel"])["vmt"].sum().reset_index()
    beam_vmt["fuel_class"] = beam_vmt["mappedFuel"] + "-" + beam_vmt["mappedClass"]
    beam_vmt = beam_vmt[["fuel_class", "vmt"]].copy()
    beam_vmt.rename(columns={'vmt': 'mvmt'}, inplace=True)
    beam_vmt["model"] = "beam"
    emfac_beam_vmt = pd.concat([emfac_vmt, beam_vmt], axis=0)
    emfac_beam_vmt.to_csv(f"{output_dir}/emfac_beam_vmt_by_fuel_class.csv")
    return emfac_beam_vmt

def calculate_delta_emissions(emissions_df, pollutant, scenario1, scenario2):
    """
    Calculate delta emissions between two scenarios

    Args:
        emissions_df: Emissions dataframe
        pollutant: Pollutant name
        scenario1: First scenario name
        scenario2: Second scenario name

    Returns:
        DataFrame with delta emissions data
    """
    pivot_df = emissions_df.pivot(index='h3_cell', columns='scenario', values=pollutant).reset_index()
    pivot_df = pivot_df.fillna(0)
    pivot_df["scenario"] = f"{scenario1}-{scenario2}"
    pivot_df[f'Delta_{pollutant}'] = pivot_df[scenario1] - pivot_df[scenario2]
    return pivot_df


# ################
# ##### Main #####
# ################

def main():
    area = "sfbay"
    run_batch = "20240123"
    scenario = "2018-Baseline"
    study_area_config = get_area_config(area)
    scenario_config = study_area_config["emissions"][scenario]
    run_config = scenario_config["run"]
    run_config["emissions_dir"] = f"emissions/{run_batch}"
    run_config["events_file"] = f"beam-runs/{run_batch}/{scenario}/0.events.csv.gz"
    run_config["emissions_skims_file"] = f"beam-runs/{run_batch}/{scenario}/0.skimsEmissions.csv.gz"
    run_config["link_stats_file"] = f"beam-runs/{run_batch}/{scenario}/0.linkstats.csv.gz"
    run_config["sample_portion"] = 0.1

    ###################################################################################################


    work_dir = study_area_config["work_dir"]
    output_dir = os.path.join(work_dir, run_config["output_dir"])
    utm_epsg = study_area_config["geo"]["utm_epsg"]

    # Output directories
    plot_dir = f'{output_dir}/_plots'
    Path(plot_dir).mkdir(parents=True, exist_ok=True)

    network_name = generate_network_name(study_area_config)
    network_file = f'{work_dir}/network/{network_name}/network.csv.gz'
    expansion_factor = 1 / run_config["sample_portion"]
    car_bike_fuel_map = scenario_config["mapping"]["fuel"]["emfac-pax"]
    bus_fuel_map = scenario_config["mapping"]["fuel"]["emfac-bus"]
    freight_fuel_map = scenario_config["mapping"]["fuel"]["emfac-ft"]

    # File paths
    ft_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["ft_vehicle_types_file"].replace(".csv", "--EM.csv")}"
    pax_vehicle_types_file = f"{work_dir}/{scenario_config["beam"]["pax_vehicle_types_file"].replace(".csv", "--EM.csv")}"
    tours_file = f"{work_dir}/{scenario_config["beam"]["tours_file"]}"
    carriers_file = f"{work_dir}/{scenario_config["beam"]["carriers_file"].replace(".csv", "--EM.csv")}"
    emissions_skims_file = f"{work_dir}/{run_config["emissions_skims_file"]}"

    # Reading files
    pax_vehicle_types = pd.read_csv(pax_vehicle_types_file)
    ft_vehicle_types = pd.read_csv(ft_vehicle_types_file)
    tours = pd.read_csv(tours_file)[["tourId", 'departureTimeInSec']]
    carriers = pd.read_csv(carriers_file)[["tourId", 'vehicleTypeId']]
    emfac_vmt = pd.read_csv(f"{output_dir}/{area}_emfac_vmt_{scenario}.csv")

    # Processing
    pax_vehicle_types = pax_vehicle_types[~pax_vehicle_types["emissionsRatesFile"].isna()].copy()
    pax_vehicle_types['mappedClass'] = pax_vehicle_types['vehicleCategory'].str.strip()
    pax_vehicle_types['fuel_key'] = pax_vehicle_types.apply(get_fuel_key, axis=1)
    bus_mask = pax_vehicle_types['vehicleCategory'] == "MediumDutyPassenger"
    car_bike_vehicle_types = pax_vehicle_types[~bus_mask].copy()
    car_bike_vehicle_types['mappedFuel'] = car_bike_vehicle_types['fuel_key'].map(car_bike_fuel_map)
    bus_vehicle_types = pax_vehicle_types[bus_mask].copy()
    bus_vehicle_types['mappedFuel'] = bus_vehicle_types['fuel_key'].map(bus_fuel_map)
    ft_vehicle_types['fuel_key'] = ft_vehicle_types.apply(get_fuel_key, axis=1)
    ft_vehicle_types['mappedFuel'] = ft_vehicle_types['fuel_key'].map(freight_fuel_map)
    ft_vehicle_types['mappedClass'] = ft_vehicle_types['vehicleCategory'].str.strip()
    vehicle_types = pd.concat([pax_vehicle_types, ft_vehicle_types], axis=0)

    tours_types_2018 = pd.merge(
        tours,
        pd.merge(
            carriers,
            ft_vehicle_types[["vehicleTypeId", 'mappedFuel', 'mappedClass']],
            on="vehicleTypeId"),
        on="tourId"
    )
    tours_types_2018["scenario"] = scenario

    print("Loading network data...")
    network = load_network(network_file, utm_epsg)

    print("Processing skims data...")
    skims = read_skims_emissions_chunked(
        vehicle_types,
        network,
        emissions_skims_file,
        expansion_factor,
        scenario,
        chunk_size=1000000
    )

    print("Calculating VMT...")
    freight_vmt = skims.groupby(['scenario', 'hour', 'beamFuel', 'class'])['vmt'].sum().reset_index().copy()

    print("Creating VMT comparison with EMFAC...")
    emfac_freight_vmt = create_model_vmt_comparison_chart(skims, emfac_vmt, output_dir)

    print("Generating plots...")
    # Figure 1: Activity plots
    plot_hourly_activity(tours_types_2018, plot_dir, height_size=6)
    plot_hourly_vmt(freight_vmt, plot_dir, height_size=6)

    # Figure 2: VMT comparison
    plot_multi_pie_emfac_famos_vmt(emfac_freight_vmt, plot_dir)

    # Figure 5: Hourly emissions
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'PM2_5', plot_dir, plot_legend=True, height_size=6,font_size=24)
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'NOx', plot_dir, plot_legend=True, height_size=6, font_size=24)
    plot_hourly_emissions_by_scenario_class_fuel(skims, 'CO2', plot_dir, plot_legend=True, height_size=6, font_size=24)

    # Figure 7: Pollution variability
    plot_pollution_variability_by_process_vehicle_types(skims, "PM2_5", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollution_variability_by_process_vehicle_types(skims, "NOx", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollution_variability_by_process_vehicle_types(skims, "CO2", scenario, plot_dir, height_size=6, font_size=24)
    plot_pollutants_by_process(skims, scenario, plot_dir, height_size=6, font_size=24)
    plot_pollutants_by_process(skims, scenario, plot_dir, height_size=6, font_size=24)

    print("Processing completed successfully.")


if __name__ == "__main__":
    main()