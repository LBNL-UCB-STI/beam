import matplotlib.pyplot as plt
import numpy as np
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


def standard_zero_energy_result():
    """
    Return standard zero energy result for Level 0 vehicles (no autonomous features)
    """
    return {
        'total_energy_twh': 0,
        'annual_driving_hours': 0,
        'breakdown_by_category': {},

        # Onboard energy breakdown
        'annual_compute_energy_twh': 0,
        'annual_sensors_energy_twh': 0,
        'annual_onboard_cooling_energy_twh': 0,
        'annual_onboard_storage_energy_twh': 0,
        'annual_vehicle_tx_energy_twh': 0,

        # Infrastructure energy breakdown
        'annual_network_energy_twh': 0,
        'annual_datacenter_storage_energy_twh': 0,
        'annual_training_energy_twh': 0,

        # Detailed breakdowns
        'fleet_size': 0
    }


def calculate_av_energy_consumption(vehicle_config, defaults):
    """
    Calculation of total energy consumption including full infrastructure.

    Parameters:
    vehicle_config (dict): Vehicle configuration for specific autonomy level
    defaults (dict): Default configuration values including new infrastructure configs

    Returns:
    dict: Comprehensive energy breakdown
    """
    level = vehicle_config['level']
    # Get fleet size from vehicle config or use provided value
    fleet_share = vehicle_config.get('fleet_share', 0)
    fleet_size = vehicle_config.get('fleet_size', fleet_share * defaults.get('fleet_total', 0))

    # Skip level 0 (no autonomous features)
    # Handle zero fleet size (like Level 5)
    if level == 0 or fleet_size == 0:
        return standard_zero_energy_result()

    # **************************************************
    # ============ Onboard computing energy ============
    # **************************************************
    # Get configuration values (existing logic)
    vmt_split = vehicle_config.get('overrides', {}).get('vmt_split', defaults['vmt_split'])
    speeds_mph = vehicle_config.get('overrides', {}).get('speeds_mph', defaults['speeds_mph'])
    power_utilization = vehicle_config.get('overrides', {}).get('utilization_rate', defaults['utilization_rate'])
    transmitted_data_ratio = vehicle_config.get('transmitted_data_ratio', 0)
    cooling_overhead = defaults.get('vehicle_config', {}).get('cooling_overhead_factor', 0.77)
    sensors_data_rate_mbps = vehicle_config.get('sensors_data_Mbit_per_second', 0)
    training_tdp_kw = vehicle_config.get('training_tdp_watt', 0)
    training_config = defaults.get('training_config', {})

    # Base power ratings (TDP)
    compute_tdp_kw = vehicle_config.get('compute_tdp_watt', 0) / 1000
    sensors_tdp_kw = vehicle_config.get('sensors_tdp_watt', 0) / 1000
    annual_vmt = vehicle_config.get('driving_vmt_daily', fleet_share * defaults['vmt_total'])

    # Calculate energy by road category
    total_compute_energy = 0
    total_sensors_energy = 0
    total_driving_time = 0
    breakdown_by_category = {}

    for category in vmt_split.keys():
        # Calculate time and distance for this category
        vmt_category = vmt_split[category] * driving_vmt_daily

        if speeds_mph[category] == 0:
            travel_time_category = 0
            print(f"Warning: No speed data for {category}, setting travel time to 0")
        else:
            travel_time_category = vmt_category / speeds_mph[category]

        total_driving_time += travel_time_category

        # Get power utilization for this category (directly from config)
        power_util_category = power_utilization[category]

        # Calculate actual power consumption for this category
        actual_compute_power_kw = compute_tdp_kw * power_util_category
        # Sensor power is assumed to be always used at max capacity
        actual_sensors_power_kw = sensors_tdp_kw

        # Calculate energy for this category
        compute_energy_category = actual_compute_power_kw * travel_time_category
        sensors_energy_category = actual_sensors_power_kw * travel_time_category

        total_compute_energy += compute_energy_category
        total_sensors_energy += sensors_energy_category

        # Store breakdown for analysis
        breakdown_by_category[category] = {
            'vmt': vmt_category,
            'speed_mph': speeds_mph[category],
            'travel_time_hours': travel_time_category,
            'power_utilization_factor': power_util_category,
            'actual_compute_power_kw': actual_compute_power_kw,
            'actual_sensors_power_kw': actual_sensors_power_kw,
            'compute_energy_kwh': compute_energy_category,
            'sensors_energy_kwh': sensors_energy_category
        }

    # Annual energy (multiply daily by 365)
    annual_compute_energy_twh = (total_compute_energy * 365) / 1e9
    annual_sensors_energy_twh = (total_sensors_energy * 365) / 1e9
    annual_driving_hours = total_driving_time * 365

    # **************************************************
    # ============ Onboard cooling energy ============
    # **************************************************
    annual_onboard_cooling_energy_twh = (annual_compute_energy_twh + annual_sensors_energy_twh) * cooling_overhead

    # **************************************************
    # ============ Onboard data storage energy (only if vehicle has sensors) ============
    # **************************************************
    annual_onboard_storage_energy_twh = 0
    if sensors_data_rate_mbps > 0:
        # Data generation rate (TB/hour from sensors)
        # 1 Mbps = 1e6 bits/sec = 125,000 bytes/sec = 0.45 GB/hour = 0.00045 TB/hour
        sensors_data_rate = sensors_data_rate_mbps * 0.00045  # Convert Mbps to TB/hour

        # Storage capacity configuration
        buffer_factor = 1.5  # 50% buffer for data bursts and safety margin (industry standard)
        max_storage_hours = 24  # 24-hour rolling buffer (regulatory requirement for accident investigation)

        # Power consumption parameters
        # Samsung automotive SSD specifications (2-3W per TB)
        power_per_tb_watt = 2.0  # Watts per TB for automotive-grade SSD idle consumption
        # Intel automotive processors for compression/encryption
        processing_power_per_gbps_watt = 10  # Watts per Gbps for real-time data processing

        # Calculate storage requirements
        required_storage_tb = sensors_data_rate * max_storage_hours * buffer_factor
        storage_power_kw = (required_storage_tb * power_per_tb_watt) / 1000

        # Data processing energy (compression, encryption, formatting)
        # Convert TB/hour to Gbps for processing power calculation
        data_rate_gbps = sensors_data_rate * 8000 / 3600  # TB/hour to Gbps (1 TB = 8000 Gb, 1 hour = 3600 sec)
        processing_power_kw = (data_rate_gbps * processing_power_per_gbps_watt) / 1000

        # Annual energy calculation
        # Both storage and processing only during driving (depot model: upload and clean at end of shift)
        annual_storage_energy_twh = (storage_power_kw * annual_driving_hours) / 1e9  # Active storage during driving only
        annual_processing_energy_twh = (processing_power_kw * annual_driving_hours) / 1e9  # Real-time processing during driving
        annual_onboard_storage_energy_twh = annual_storage_energy_twh + annual_processing_energy_twh

    # **************************************************
    # ============ Data transmission energy (only if vehicle has sensors) ============
    # **************************************************
    annual_vehicle_tx_energy_twh = 0
    annual_network_energy_twh = 0
    if sensors_data_rate_mbps > 0:

        # Compression before transmission
        # 3:1 to 6:1 for real-time automotive compression
        compression_ratio = 4.5
        transmitted_data_rate_mbps = (sensors_data_rate_mbps * transmitted_data_ratio) / compression_ratio
        # Vehicle transmission energy (5G/cellular modem)
        # Qualcomm 5G automotive modem specifications
        vehicle_tx_power_per_mbps_watt = 0.5  # Watts per Mbps for 5G/cellular transmission
        vehicle_tx_power_kw = (transmitted_data_rate_mbps * vehicle_tx_power_per_mbps_watt) / 1000

        # Network infrastructure energy per bit transmitted
        network_energy_per_bit_nanojoules = 25  # Network infrastructure energy per bit (higher for 5G)
        network_energy_per_bit_j = network_energy_per_bit_nanojoules * 1e-9  # Convert nJ to J per bit
        bits_per_hour = transmitted_data_rate_mbps * 1e6 * 3600  # Convert Mbps to bits/hour
        network_power_kw = (bits_per_hour * network_energy_per_bit_j) / 3600 / 1000  # Convert J/hour to kW

        # Annual energy calculations
        annual_vehicle_tx_energy_twh = (vehicle_tx_power_kw * annual_driving_hours) / 1e9
        annual_network_energy_twh = (network_power_kw * annual_driving_hours) / 1e9

    # **************************************************
    # ============ DATACENTER STORAGE ENERGY ============
    # **************************************************
    annual_datacenter_storage_energy_twh = 0
    if sensors_data_rate_mbps > 0 and fleet_size > 0:
        # Raw data generation per vehicle (TB/hour)
        sensors_data_rate_tb_hour = sensors_data_rate_mbps * transmitted_data_ratio * 0.00045

        # Preprocessing and compression at datacenter
        preprocessing_compression_ratio = 20  # 20:1 compression after preprocessing
        processed_data_rate_tb_hour = sensors_data_rate_tb_hour / preprocessing_compression_ratio

        # Total fleet data generation per year
        total_annual_data_tb = processed_data_rate_tb_hour * annual_driving_hours * fleet_size

        # Storage retention policy
        data_retention_years = 5  # Years to retain training data
        total_storage_capacity_tb = total_annual_data_tb * data_retention_years

        # Storage system energy consumption
        storage_pue = 1.3  # Modern datacenter PUE (updated from 1.6)
        storage_power_per_tb_watt = 8  # Watts per TB including redundancy and cooling

        storage_power_kw = total_storage_capacity_tb * storage_power_per_tb_watt / 1000
        total_storage_power_with_pue_kw = storage_power_kw * storage_pue

        # Data processing power (ingestion, indexing, backup)
        processing_power_per_tb_year_watt = 100
        annual_processing_power_kw = total_annual_data_tb * processing_power_per_tb_year_watt / 1000
        total_annual_processing_power_with_pue_kw = annual_processing_power_kw * storage_pue

        # Annual datacenter storage energy (TWh per year)
        annual_storage_energy_twh = (total_storage_power_with_pue_kw * 8760) / 1e9  # Storage infrastructure runs 24/7
        annual_processing_energy_twh = (total_annual_processing_power_with_pue_kw * annual_driving_hours) / 1e9  # Processing infrastructure runs by annual_driving_hours
        annual_datacenter_storage_energy_twh = annual_storage_energy_twh + annual_processing_energy_twh

    # **************************************************
    # ============ ANNUAL TRAINING INFRASTRUCTURE ENERGY ============
    # **************************************************
    annual_training_energy_twh = 0
    if training_tdp_kw > 0 and fleet_size > 0:
        # Training efficiency factors
        utilization_rate = training_config.get('gpu_utilization_rate', 0.75)  # 75% GPU utilization
        parallelization_efficiency = 0.85  # 85% scaling efficiency for distributed training
        failed_runs_overhead = training_config.get('failed_runs_overhead_factor', 1.2)  # 20% overhead for failed runs

        # Model updates and iterations per year
        model_iterations_per_year = training_config.get('model_iterations_per_year', 24)  # Every 2 weeks
        continuous_training_factor = training_config.get('continuous_training_factor', 0.3)  # 30% continuous learning

        # Effective training power accounting for efficiency losses
        effective_training_power_kw = (
                training_tdp_kw *
                failed_runs_overhead *
                (1 / parallelization_efficiency) *
                (1 / utilization_rate)
        )

        # Training time allocation per year
        full_retraining_hours_per_iteration = training_config.get('full_retraining_hours_per_iteration', 2160)  # 90 days
        incremental_training_hours_per_iteration = training_config.get('incremental_training_hours_per_iteration', 720)  # 30 days

        # Annual training hours
        annual_training_hours = (
                full_retraining_hours_per_iteration * model_iterations_per_year * 0.25 +  # 25% full retraining
                incremental_training_hours_per_iteration * model_iterations_per_year * 0.75 +  # 75% incremental
                8760 * continuous_training_factor  # Continuous learning hours per year
        )

        # Annual compute energy
        annual_compute_energy_twh = (effective_training_power_kw * annual_training_hours) / 1e9

        # Training infrastructure energy with modern PUE
        training_pue = 1.1  # Modern AI training center PUE (Google-class efficiency)
        cooling_power_ratio = training_config.get('cooling_power_ratio', 0.77)  # 77% of compute power for cooling
        networking_power_ratio = training_config.get('networking_power_ratio', 0.1)  # 10% of compute power

        # Annual infrastructure energy breakdown
        annual_cooling_energy_twh = (effective_training_power_kw * cooling_power_ratio * annual_training_hours) / 1e9
        annual_networking_energy_twh = (effective_training_power_kw * networking_power_ratio * annual_training_hours) / 1e9

        # Total annual training infrastructure energy
        annual_training_energy_twh = (annual_compute_energy_twh + annual_cooling_energy_twh + annual_networking_energy_twh) * training_pue


    # Total energy per vehicle (including allocated share of training)
    annual_total_energy_twh = (
            annual_compute_energy_twh +
            annual_sensors_energy_twh +
            annual_onboard_cooling_energy_twh +
            annual_onboard_storage_energy_twh +
            annual_vehicle_tx_energy_twh +
            annual_network_energy_twh +
            annual_datacenter_storage_energy_twh +
            annual_training_energy_twh
    )

    return {
        'total_energy_twh': annual_total_energy_twh,
        'annual_driving_hours': annual_driving_hours,
        'breakdown_by_category': breakdown_by_category,

        # Onboard energy breakdown
        'annual_compute_energy_twh': annual_compute_energy_twh,
        'annual_sensors_energy_twh': annual_sensors_energy_twh,
        'annual_onboard_cooling_energy_twh': annual_onboard_cooling_energy_twh,
        'annual_onboard_storage_energy_twh': annual_onboard_storage_energy_twh,
        'annual_vehicle_tx_energy_twh': annual_vehicle_tx_energy_twh,

        # Infrastructure energy breakdown
        'annual_network_energy_twh': annual_network_energy_twh,
        'annual_datacenter_storage_energy_twh': annual_datacenter_storage_energy_twh,
        'annual_training_energy_twh': annual_training_energy_twh,

        # Detailed breakdowns
        'fleet_size': fleet_size
    }


def calculate_fleet_energy_consumption(fleet_config):
    """
    Enhanced fleet energy calculation with proper shared infrastructure accounting.
    """
    defaults = fleet_config['defaults']

    fleet_results = {
        "scenario": fleet_config["scenario"],
        "autonomy_levels": {},
        "fleet_totals": {
            "total_annual_energy_twh": 0,
            "total_annual_driving_hours": 0,
            "total_annual_compute_energy_twh": 0,
            "total_annual_sensors_energy_twh": 0,
            "total_annual_onboard_cooling_energy_twh": 0,
            "total_annual_onboard_storage_energy_twh": 0,
            "total_annual_vehicle_tx_energy_twh": 0,
            "total_annual_network_energy_twh": 0,
            "total_annual_datacenter_storage_energy_twh": 0,
            "total_annual_training_energy_twh": 0,
            "breakdown_by_category": {}
        }
    }

    for vehicle_config in fleet_config["fleet"]:
        level = vehicle_config["level"]
        fleet_size = vehicle_config.get('fleet_size', 0)  # Get fleet size from YAML

        print(f"Calculating for level {level} with fleet size {fleet_size:,} vehicles...")

        level_result = calculate_av_energy_consumption(vehicle_config, defaults)
        fleet_results["autonomy_levels"][f"level_{level}"] = level_result

        # Add to fleet totals (MULTIPLY by fleet_size for actual total energy)
        if fleet_size > 0:
            totals = fleet_results["fleet_totals"]
            totals["total_annual_energy_twh"] += level_result["total_energy_twh"]
            totals["total_annual_driving_hours"] += level_result["annual_driving_hours"]
            totals["total_annual_compute_energy_twh"] += level_result["annual_compute_energy_twh"]
            totals["total_annual_sensors_energy_twh"] += level_result["annual_sensors_energy_twh"]
            totals["total_annual_onboard_cooling_energy_twh"] += level_result["annual_onboard_cooling_energy_twh"]
            totals["total_annual_onboard_storage_energy_twh"] += level_result["annual_onboard_storage_energy_twh"]
            totals["total_annual_vehicle_tx_energy_twh"] += level_result["annual_vehicle_tx_energy_twh"]
            totals["total_annual_network_energy_twh"] += level_result["annual_network_energy_twh"]
            totals["total_annual_datacenter_storage_energy_twh"] += level_result["annual_datacenter_storage_energy_twh"]
            totals["total_annual_training_energy_twh"] += level_result["annual_training_energy_twh"]

            # Aggregate breakdown by category
            for category, data in level_result["breakdown_by_category"].items():
                if category not in totals["breakdown_by_category"]:
                    totals["breakdown_by_category"][category] = {
                        'vmt': 0,
                        'travel_time_hours': 0,
                        'compute_energy_kwh': 0,
                        'sensors_energy_kwh': 0
                    }
                totals["breakdown_by_category"][category]['vmt'] += data['vmt']
                totals["breakdown_by_category"][category]['travel_time_hours'] += data['travel_time_hours']
                totals["breakdown_by_category"][category]['compute_energy_kwh'] += data['compute_energy_kwh']
                totals["breakdown_by_category"][category]['sensors_energy_kwh'] += data['sensors_energy_kwh']

    return fleet_results

def print_enhanced_fleet_summary(results):
    """
    Print comprehensive summary including infrastructure energy breakdown.
    """
    print(f"\nScenario: {results['scenario']}")
    print(f"{'=' * 80}")

    # Print results by autonomy level
    for level_key, level_data in results['autonomy_levels'].items():
        level_num = level_key.split('_')[1]
        print(f"\nLevel {level_num}:")
        print(f"  Total Energy: {level_data['total_energy_twh']:.6f} TWh")
        print(f"  Annual Driving Hours: {level_data['annual_driving_hours']:.0f}")
        print(f"  Fleet Size: {level_data['fleet_size']:,.0f}")
        print(f"  Onboard Energy:")
        print(f"    Compute: {level_data['annual_compute_energy_twh']:.6f} TWh")
        print(f"    Sensors: {level_data['annual_sensors_energy_twh']:.6f} TWh")
        print(f"    Cooling: {level_data['annual_onboard_cooling_energy_twh']:.6f} TWh")
        print(f"    Storage: {level_data['annual_onboard_storage_energy_twh']:.6f} TWh")
        print(f"    Vehicle Transmission: {level_data['annual_vehicle_tx_energy_twh']:.6f} TWh")
        print(f"  Infrastructure Energy:")
        print(f"    Network: {level_data['annual_network_energy_twh']:.6f} TWh")
        print(f"    Datacenter Storage: {level_data['annual_datacenter_storage_energy_twh']:.6f} TWh")
        print(f"    Training Infrastructure: {level_data['annual_training_energy_twh']:.6f} TWh")

    # Print fleet totals
    totals = results['fleet_totals']
    print(f"\n{'FLEET TOTALS':<25}")
    print(f"{'=' * 80}")
    print(f"Total Energy Consumption: {totals['total_annual_energy_twh']:.6f} TWh")
    print(f"Total Annual Driving Hours: {totals['total_annual_driving_hours']:.0f}")
    print(f"\nOnboard Energy Breakdown:")
    print(f"  Compute: {totals['total_annual_compute_energy_twh']:.6f} TWh")
    print(f"  Sensors: {totals['total_annual_sensors_energy_twh']:.6f} TWh")
    print(f"  Cooling: {totals['total_annual_onboard_cooling_energy_twh']:.6f} TWh")
    print(f"  Storage: {totals['total_annual_onboard_storage_energy_twh']:.6f} TWh")
    print(f"  Vehicle Transmission: {totals['total_annual_vehicle_tx_energy_twh']:.6f} TWh")
    print(f"\nInfrastructure Energy Breakdown:")
    print(f"  Network: {totals['total_annual_network_energy_twh']:.6f} TWh")
    print(f"  Datacenter Storage: {totals['total_annual_datacenter_storage_energy_twh']:.6f} TWh")
    print(f"  Training Infrastructure: {totals['total_annual_training_energy_twh']:.6f} TWh")

    # Calculate percentages
    if totals['total_annual_energy_twh'] > 0:
        onboard_pct = ((totals['total_annual_compute_energy_twh'] + totals['total_annual_sensors_energy_twh'] +
                        totals['total_annual_onboard_cooling_energy_twh'] + totals['total_annual_onboard_storage_energy_twh'] +
                        totals['total_annual_vehicle_tx_energy_twh']) /
                       totals['total_annual_energy_twh']) * 100
        infrastructure_pct = 100 - onboard_pct

        print(f"\nEnergy Distribution:")
        print(f"  Onboard Systems: {onboard_pct:.1f}%")
        print(f"  Infrastructure: {infrastructure_pct:.1f}%")

    print(f"{'=' * 80}")


def plot_scenario_energy_breakdown(scenario_results):
    """
    Plot energy consumption breakdown across multiple scenarios.

    Parameters:
    scenario_results (list): List of results from calculate_fleet_energy_consumption
    """
    # Extract data for plotting
    years = []
    energy_data = {
        'Compute': [],
        'Sensors': [],
        'Onboard Cooling': [],
        'Onboard Storage': [],
        'Vehicle Transmission': [],
        'Network': [],
        'Datacenter Storage': [],
        'Training Infrastructure': []
    }

    # Extract year from scenario name and energy data
    for result in scenario_results:
        scenario_name = result['scenario']
        # Extract year from scenario name (assuming format like "2025 Baseline" or "2035 Projected")
        year = None
        for word in scenario_name.split():
            if word.isdigit() and len(word) == 4:
                year = int(word)
                break

        if year is None:
            print(f"Warning: Could not extract year from scenario '{scenario_name}'")
            continue

        years.append(year)
        totals = result['fleet_totals']

        # Extract energy components (converting to TWh if needed)
        energy_data['Compute'].append(totals.get('total_annual_compute_energy_twh', 0))
        energy_data['Sensors'].append(totals.get('total_annual_sensors_energy_twh', 0))
        energy_data['Onboard Cooling'].append(totals.get('total_annual_onboard_cooling_energy_twh', 0))
        energy_data['Onboard Storage'].append(totals.get('total_annual_onboard_storage_energy_twh', 0))
        energy_data['Vehicle Transmission'].append(totals.get('total_annual_vehicle_tx_energy_twh', 0))
        energy_data['Network'].append(totals.get('total_annual_network_energy_twh', 0))
        energy_data['Datacenter Storage'].append(totals.get('total_annual_datacenter_storage_energy_twh', 0))
        energy_data['Training Infrastructure'].append(totals.get('total_annual_training_energy_twh', 0))

    # Sort by year
    if not years:
        print("No valid scenarios found for plotting")
        return

    sorted_indices = np.argsort(years)
    years = [years[i] for i in sorted_indices]
    for component in energy_data:
        energy_data[component] = [energy_data[component][i] for i in sorted_indices]

    # Create the plot
    fig, ax = plt.subplots(figsize=(12, 8))

    # Define colors for each component
    colors = {
        'Compute': '#FF6B6B',  # Red - Primary compute
        'Sensors': '#4ECDC4',  # Teal - Sensors
        'Onboard Cooling': '#45B7D1',  # Blue - Cooling
        'Onboard Storage': '#96CEB4',  # Green - Storage
        'Vehicle Transmission': '#FECA57',  # Yellow - Vehicle transmission
        'Network': '#FF9FF3',  # Pink - Network infrastructure
        'Datacenter Storage': '#54A0FF',  # Light blue - Datacenter storage
        'Training Infrastructure': '#5F27CD'  # Purple - Training
    }

    # Create stacked bar chart
    bottom = np.zeros(len(years))
    bar_width = 2  # Make bars wider for better visibility

    for component, values in energy_data.items():
        ax.bar(years, values, bottom=bottom, label=component,
               color=colors[component], width=bar_width, alpha=0.8)
        bottom += values

    # Customize the plot
    ax.set_xlabel('Year', fontsize=12, fontweight='bold')
    ax.set_ylabel('Energy Consumption (TWh)', fontsize=12, fontweight='bold')
    ax.set_title('Autonomous Vehicle Fleet Energy Consumption by Component',
                 fontsize=14, fontweight='bold', pad=20)

    # Format x-axis
    ax.set_xticks(years)
    ax.set_xticklabels([str(year) for year in years])

    # Add grid for better readability
    ax.grid(True, alpha=0.3, axis='y')
    ax.set_axisbelow(True)

    # Customize legend
    ax.legend(bbox_to_anchor=(1.05, 1), loc='upper left',
              frameon=True, fancybox=True, shadow=True)

    # Add total values on top of bars
    for i, year in enumerate(years):
        total = sum(energy_data[component][i] for component in energy_data)
        if total > 0:
            ax.text(year, total + total * 0.01, f'{total:.3f} TWh',
                    ha='center', va='bottom', fontweight='bold', fontsize=10)

    # Adjust layout to prevent legend cutoff
    plt.tight_layout()

    # Show percentages in legend
    for i, year in enumerate(years):
        total = sum(energy_data[component][i] for component in energy_data)
        if total > 0:
            print(f"\n{year} Energy Breakdown:")
            for component, values in energy_data.items():
                percentage = (values[i] / total) * 100
                if percentage > 0.1:  # Only show components > 0.1%
                    print(f"  {component}: {values[i]:.4f} TWh ({percentage:.1f}%)")

    plt.show()


def plot_multiple_scenarios(scenario_files):
    """
    Load and plot multiple scenarios.

    Parameters:
    scenario_files (list): List of YAML file paths
    """
    scenario_results = []

    for scenario_file in scenario_files:
        try:
            print(f"Loading scenario: {scenario_file}")
            config = load_scenario_config(scenario_file)
            results = calculate_fleet_energy_consumption(config)
            scenario_results.append(results)
        except Exception as e:
            print(f"Error loading scenario {scenario_file}: {e}")

    if scenario_results:
        plot_scenario_energy_breakdown(scenario_results)
    else:
        print("No scenarios loaded successfully for plotting")


if __name__ == "__main__":
    scenarios = [
        'scenarios/av_2025_baseline.yaml',
        'scenarios/av_2035_projected_adoption.yaml',
        'scenarios/av_2050_mass_adoption.yaml'
    ]

    # Process individual scenarios
    scenario_results = []
    for scenario_file in scenarios:
        print(f"\n{'=' * 80}")
        print(f"Processing: {scenario_file}")
        print(f"{'=' * 80}")

        config = load_scenario_config(scenario_file)
        results = calculate_fleet_energy_consumption(config)
        print_enhanced_fleet_summary(results)
        scenario_results.append(results)

    # Create energy breakdown plot
    print(f"\n{'=' * 80}")
    print("Creating energy breakdown plot...")
    print(f"{'=' * 80}")
    plot_scenario_energy_breakdown(scenario_results)

    # Alternative way to plot scenarios directly from files
    # plot_multiple_scenarios(scenarios)