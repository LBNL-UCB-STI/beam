import yaml


def load_scenario_config(yaml_file_path):
    """
    Load scenario configuration from YAML file.

    Parameters:
    yaml_file_path (str): Path to the YAML configuration file

    Returns:
    dict: Configuration dictionary
    """
    with open(yaml_file_path, 'r') as file:
        config = yaml.safe_load(file)
    return config


def calculate_av_energy_consumption(vehicle_config, defaults):
    """
    Calculate total energy consumption of onboard compute for autonomous vehicles.

    Parameters:
    vehicle_config (dict): Vehicle configuration for specific autonomy level
    defaults (dict): Default configuration values

    Returns:
    dict: Contains total energy in TWh and breakdown by road category
    """
    level = vehicle_config['level']

    # Skip level 0 (no autonomous features)
    if level == 0:
        return {
            'total_energy_twh': 0,
            'total_utilization_time_hours': 0,
            'breakdown_by_category': {},
            'total_compute_energy_twh': 0,
            'total_sensors_energy_twh': 0,
            'total_training_energy_twh': 0
        }

    # Get VMT split (use overrides if available, otherwise defaults)
    vmt_split = vehicle_config.get('overrides', {}).get('vmt_split', defaults['vmt_split'])

    # Get speeds (use overrides if available, otherwise defaults)
    speeds_mph = vehicle_config.get('overrides', {}).get('speeds_mph',
                                                         vehicle_config.get('speeds_mph', defaults['speeds_mph']))

    # Get utilization rates (use overrides if available, otherwise defaults)
    utilization_rate = vehicle_config.get('overrides', {}).get('utilization_rate',
                                                               vehicle_config.get('utilization_rate',
                                                                                  defaults['utilization_rate']))

    # Get power draws
    compute_power_kw = vehicle_config.get('compute_tdp_watt', 0) / 1000  # Convert W to kW
    sensors_power_kw = vehicle_config.get('sensors_tdp_watt', 0) / 1000  # Convert W to kW

    # Get VMT
    driving_vmt_daily = vehicle_config.get('driving_vmt_daily', 0)

    total_utilization_time = 0
    breakdown = {}

    # Calculate for each road category
    for category in vmt_split.keys():
        # Calculate VMT for this road category
        vmt_ri = vmt_split[category] * driving_vmt_daily

        # Skip categories with no speed data
        if speeds_mph[category] == 0:
            travel_time_ri = 0
            print(f"Warning: No speed data for {category}, setting travel time to 0")
        else:
            # Calculate travel time
            travel_time_ri = vmt_ri / speeds_mph[category]

        # Get utilization for this road category
        utilization_ri = utilization_rate.get(category, 0)

        # Calculate utilization time for this category
        utilization_time_ri = utilization_ri * travel_time_ri

        # Add to total
        total_utilization_time += utilization_time_ri

        # Store breakdown
        breakdown[category] = {
            'vmt': vmt_ri,
            'speed_mph': speeds_mph[category],
            'travel_time_hours': travel_time_ri,
            'utilization_factor': utilization_ri,
            'utilization_time_hours': utilization_time_ri
        }

    # Calculate energy consumption components
    # Convert daily to annual (365 days)
    annual_utilization_hours = total_utilization_time * 365

    # Compute energy (kWh to TWh)
    compute_energy_twh = (compute_power_kw * annual_utilization_hours) / 1e9

    # Sensors energy (kWh to TWh)
    sensors_energy_twh = (sensors_power_kw * annual_utilization_hours) / 1e9

    # Total onboard energy
    total_energy_twh = compute_energy_twh + sensors_energy_twh

    # Training energy (if applicable)
    training_energy_twh = 0
    if 'training_tdp_watt' in vehicle_config:
        # Assume training runs continuously (8760 hours/year)
        training_power_kw = vehicle_config['training_tdp_watt'] / 1000
        training_energy_twh = (training_power_kw * 8760) / 1e9

    return {
        'total_energy_twh': total_energy_twh,
        'total_utilization_time_hours': total_utilization_time,
        'breakdown_by_category': breakdown,
        'total_compute_energy_twh': compute_energy_twh,
        'total_sensors_energy_twh': sensors_energy_twh,
        'total_training_energy_twh': training_energy_twh,
        'annual_utilization_hours': annual_utilization_hours
    }


def calculate_fleet_energy_consumption(fleet_config):
    """
    Calculate energy consumption for an entire fleet with different autonomy levels.

    Parameters:
    fleet_config (dict): Fleet configuration dictionary loaded from YAML

    Returns:
    dict: Results for each autonomy level and total fleet consumption
    """
    defaults = fleet_config['defaults']

    fleet_results = {
        "scenario": fleet_config["scenario"],
        "autonomy_levels": {},
        "fleet_totals": {
            "total_energy_twh": 0,
            "total_utilization_time_hours": 0,
            "total_compute_energy_twh": 0,
            "total_sensors_energy_twh": 0,
            "total_training_energy_twh": 0,
            "breakdown_by_category": {}
        }
    }

    # Loop over each autonomy level in the fleet
    for vehicle_config in fleet_config["fleet"]:
        level = vehicle_config["level"]
        print(f"Calculating for level {level}...")

        level_result = calculate_av_energy_consumption(vehicle_config, defaults)

        # Store results for this level
        fleet_results["autonomy_levels"][f"level_{level}"] = level_result

        # Add to fleet totals
        fleet_results["fleet_totals"]["total_energy_twh"] += level_result["total_energy_twh"]
        fleet_results["fleet_totals"]["total_utilization_time_hours"] += level_result["total_utilization_time_hours"]
        fleet_results["fleet_totals"]["total_compute_energy_twh"] += level_result["total_compute_energy_twh"]
        fleet_results["fleet_totals"]["total_sensors_energy_twh"] += level_result["total_sensors_energy_twh"]
        fleet_results["fleet_totals"]["total_training_energy_twh"] += level_result["total_training_energy_twh"]

        # Aggregate breakdown by category
        for category, data in level_result["breakdown_by_category"].items():
            if category not in fleet_results["fleet_totals"]["breakdown_by_category"]:
                fleet_results["fleet_totals"]["breakdown_by_category"][category] = {
                    'vmt': 0,
                    'travel_time_hours': 0,
                    'utilization_time_hours': 0
                }

            fleet_results["fleet_totals"]["breakdown_by_category"][category]['vmt'] += data['vmt']
            fleet_results["fleet_totals"]["breakdown_by_category"][category]['travel_time_hours'] += data[
                'travel_time_hours']
            fleet_results["fleet_totals"]["breakdown_by_category"][category]['utilization_time_hours'] += data[
                'utilization_time_hours']

    return fleet_results


def print_fleet_summary(results):
    """
    Print a summary of fleet energy consumption results.
    """
    print(f"\nScenario: {results['scenario']}")
    print(f"{'=' * 60}")

    # Print results by autonomy level
    for level_key, level_data in results['autonomy_levels'].items():
        level_num = level_key.split('_')[1]
        print(f"\nLevel {level_num}:")
        print(f"  Total Energy: {level_data['total_energy_twh']:.6f} TWh")
        print(f"  Compute Energy: {level_data['total_compute_energy_twh']:.6f} TWh")
        print(f"  Sensors Energy: {level_data['total_sensors_energy_twh']:.6f} TWh")
        if level_data['total_training_energy_twh'] > 0:
            print(f"  Training Energy: {level_data['total_training_energy_twh']:.6f} TWh")
        print(f"  Utilization Time: {level_data['total_utilization_time_hours']:,.0f} hours")

    # Print fleet totals
    totals = results['fleet_totals']
    print(f"\n{'FLEET TOTALS':<25}")
    print(f"{'=' * 60}")
    print(f"Total Energy Consumption: {totals['total_energy_twh']:.6f} TWh")
    print(f"  Compute: {totals['total_compute_energy_twh']:.6f} TWh")
    print(f"  Sensors: {totals['total_sensors_energy_twh']:.6f} TWh")
    print(f"  Training: {totals['total_training_energy_twh']:.6f} TWh")
    print(f"Total Utilization Time: {totals['total_utilization_time_hours']:,.0f} hours")

    # Print breakdown by road category
    print(f"\n{'BREAKDOWN BY ROAD CATEGORY':<25}")
    print(f"{'=' * 75}")
    print(f"{'Category':<25} {'VMT':<15} {'Travel Time (hrs)':<18} {'Util Time (hrs)':<15}")
    print(f"{'-' * 75}")

    total_category_vmt = 0
    total_category_travel_time = 0
    total_category_util_time = 0

    for category, data in totals['breakdown_by_category'].items():
        vmt = data['vmt']
        travel_time = data['travel_time_hours']
        util_time = data['utilization_time_hours']

        total_category_vmt += vmt
        total_category_travel_time += travel_time
        total_category_util_time += util_time

        print(f"{category:<25} {vmt:<15,.0f} {travel_time:<18.2f} {util_time:<15.2f}")

    print(f"{'-' * 75}")
    print(
        f"{'TOTAL':<25} {total_category_vmt:<15,.0f} {total_category_travel_time:<18.2f} {total_category_util_time:<15.2f}")
    print(f"{'=' * 60}")


# Example usage
if __name__ == "__main__":
    scenarios = [
        'scenarios/av_2025_baseline.yaml'
    ]

    for scenario_file in scenarios:
        print(f"\n{'=' * 60}")
        print(f"Processing: {scenario_file}")
        print(f"{'=' * 60}")

        config = load_scenario_config(scenario_file)
        results = calculate_fleet_energy_consumption(config)
        print_fleet_summary(results)