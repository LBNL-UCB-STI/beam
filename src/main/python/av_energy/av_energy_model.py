import yaml
import math


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


def calculate_shared_infrastructure_energy(vehicle_config, defaults, utilization_hours, fleet_size):
    """
    Calculate shared infrastructure energy (datacenter storage and training) for the entire fleet.

    Parameters:
    vehicle_config (dict): Vehicle configuration
    defaults (dict): Default configuration values
    utilization_hours (float): Annual utilization hours per vehicle

    Returns:
    dict: Shared infrastructure energy breakdown
    """
    # Calculate datacenter storage energy for the entire fleet
    datacenter_storage_results = calculate_datacenter_storage_energy(
        vehicle_config, utilization_hours, fleet_size=fleet_size
    )

    # Calculate training infrastructure energy for the entire fleet
    training_config = defaults.get('training_config', {})
    training_results = calculate_training_infrastructure_energy(
        vehicle_config, training_config
    )

    return {
        'datacenter_storage_results': datacenter_storage_results,
        'training_results': training_results,
        'total_datacenter_storage_energy_twh': datacenter_storage_results['total_datacenter_storage_energy_twh'],
        'total_training_infrastructure_energy_twh': training_results['total_training_infrastructure_energy_twh']
    }


def calculate_data_storage_energy(vehicle_config, utilization_hours):
    """
    Calculate energy consumption for onboard data storage in AVs.

    Parameters:
    vehicle_config (dict): Vehicle configuration
    utilization_hours (float): Annual utilization hours

    Returns:
    dict: Storage energy breakdown
    """
    # Data generation rate (TB/hour from sensors)
    sensors_data_rate = vehicle_config.get('sensors_data_Mbit_per_second', 0) / 8000  # Convert to TB/hour

    # Storage capacity needed (with buffering)
    buffer_factor = 1.5
    max_storage_hours = 24
    power_per_tb = 2  # for ssd - idle energy consumption
    processing_power_per_gbps_watt = 10  # Watts per Gbps for data processing (compression, encryption)
    required_storage_tb = sensors_data_rate * max_storage_hours * buffer_factor

    storage_power_kw = (required_storage_tb * power_per_tb) / 1000

    # Data processing energy (compression, encryption)
    data_rate_gbps = sensors_data_rate * 1000 * 8 / 3600  # Convert TB/hour to Gbps
    processing_power_kw = (data_rate_gbps * processing_power_per_gbps_watt) / 1000

    # Annual energy calculation
    annual_storage_energy_twh = (storage_power_kw * 8760) / 1e9  # Always on
    annual_processing_energy_twh = (processing_power_kw * utilization_hours) / 1e9  # Only during operation

    return {
        'storage_capacity_tb': required_storage_tb,
        'storage_power_kw': storage_power_kw,
        'processing_power_kw': processing_power_kw,
        'annual_storage_energy_twh': annual_storage_energy_twh,
        'annual_processing_energy_twh': annual_processing_energy_twh,
        'total_onboard_storage_energy_twh': annual_storage_energy_twh + annual_processing_energy_twh
    }


def calculate_data_transmission_energy(vehicle_config, utilization_hours):
    """
    Calculate energy for transmitting data from vehicles to data centers.

    Parameters:
    vehicle_config (dict): Vehicle configuration
    network_config (dict): Network infrastructure configuration
    utilization_hours (float): Annual utilization hours

    Returns:
    dict: Transmission energy breakdown
    """
    # Data transmission rate
    sensors_data_rate_mbps = vehicle_config.get('sensors_data_Mbit_per_second', 0)

    # Compression before transmission
    compression_ratio = 10  # 10:1 compression before transmission
    transmitted_data_rate_mbps = sensors_data_rate_mbps / compression_ratio

    # Vehicle transmission energy (5G/cellular modem)
    vehicle_tx_power_per_mbps_watt = 0.5  # Vehicle 5G/cellular transmission power
    vehicle_tx_power_kw = (transmitted_data_rate_mbps * vehicle_tx_power_per_mbps_watt) / 1000

    # Network infrastructure energy per bit transmitted
    network_energy_per_bit_nanojoules = 20  # Network infrastructure energy per bit
    network_energy_per_bit_j = network_energy_per_bit_nanojoules * 1e-9  # J/bit
    bits_per_hour = transmitted_data_rate_mbps * 1e6 * 3600  # bits/hour
    network_power_kw = (bits_per_hour * network_energy_per_bit_j) / 3600 / 1000  # Convert to kW

    # Annual energy calculations
    vehicle_tx_energy_twh = (vehicle_tx_power_kw * utilization_hours) / 1e9
    network_energy_twh = (network_power_kw * utilization_hours) / 1e9

    return {
        'transmitted_data_rate_mbps': transmitted_data_rate_mbps,
        'vehicle_tx_power_kw': vehicle_tx_power_kw,
        'network_power_kw': network_power_kw,
        'annual_vehicle_tx_energy_twh': vehicle_tx_energy_twh,
        'annual_network_energy_twh': network_energy_twh,
        'total_transmission_energy_twh': vehicle_tx_energy_twh + network_energy_twh
    }


def calculate_datacenter_storage_energy(vehicle_config, utilization_hours, fleet_size):
    """
    Calculate energy for data center storage of preprocessed data.

    Parameters:
    vehicle_config (dict): Vehicle configuration
    utilization_hours (float): Annual utilization hours per vehicle

    Returns:
    dict: Data center storage energy breakdown
    """
    # Raw data generation per vehicle
    sensors_data_rate_tb_hour = vehicle_config.get('sensors_data_Mbit_per_second', 0) / 8000

    # Preprocessing and compression
    preprocessing_compression_ratio = 100  # 100:1 compression after preprocessing
    processed_data_rate_tb_hour = sensors_data_rate_tb_hour / preprocessing_compression_ratio

    # Total fleet data generation
    total_annual_data_tb = processed_data_rate_tb_hour * utilization_hours * fleet_size

    # Storage retention policy
    data_retention_years = 5  # Years to retain training data
    total_storage_capacity_tb = total_annual_data_tb * data_retention_years

    # Storage system energy
    storage_pue = 1.6  # Power Usage Effectiveness for storage systems
    storage_power_per_tb_watt = 8  # Watts per TB including redundancy and cooling
    storage_power_per_tb_kw = storage_power_per_tb_watt / 1000  # Including redundancy

    storage_power_kw = total_storage_capacity_tb * storage_power_per_tb_kw
    total_storage_power_with_pue_kw = storage_power_kw * storage_pue

    # Data processing power (ingestion, indexing, backup)
    processing_power_per_tb_year_watt = 100
    processing_power_kw = total_annual_data_tb * processing_power_per_tb_year_watt / 1000
    total_processing_power_with_pue_kw = processing_power_kw * storage_pue

    # Annual energy calculation
    annual_storage_energy_twh = (total_storage_power_with_pue_kw * 8760) / 1e9
    annual_processing_energy_twh = (total_processing_power_with_pue_kw * 8760) / 1e9

    return {
        'total_storage_capacity_tb': total_storage_capacity_tb,
        'annual_data_generation_tb': total_annual_data_tb,
        'storage_power_kw': storage_power_kw,
        'total_storage_power_with_pue_kw': total_storage_power_with_pue_kw,
        'processing_power_kw': processing_power_kw,
        'annual_storage_energy_twh': annual_storage_energy_twh,
        'annual_processing_energy_twh': annual_processing_energy_twh,
        'total_datacenter_storage_energy_twh': annual_storage_energy_twh + annual_processing_energy_twh
    }


def calculate_training_infrastructure_energy(vehicle_config, training_config):
    """
    Calculate comprehensive training infrastructure energy including efficiency losses.

    Parameters:
    vehicle_config (dict): Vehicle configuration
    training_config (dict): Training infrastructure configuration
    fleet_size (int): Number of vehicles in fleet

    Returns:
    dict: Training infrastructure energy breakdown
    """
    if 'training_tdp_watt' not in vehicle_config:
        return {
            'total_training_infrastructure_energy_twh': 0,
            'compute_energy_twh': 0,
            'cooling_energy_twh': 0,
            'networking_energy_twh': 0,
            'storage_energy_twh': 0,
            'overhead_energy_twh': 0
        }

    base_training_power_kw = vehicle_config['training_tdp_watt'] / 1000

    # Training efficiency factors
    utilization_rate = training_config.get('gpu_utilization_rate', 0.75)  # 75% GPU utilization
    parallelization_efficiency = 0.85  # 85% scaling efficiency for distributed training
    failed_runs_overhead = training_config.get('failed_runs_overhead_factor', 1.2)  # 20% overhead for failed runs

    # Model updates and iterations
    model_iterations_per_year = training_config.get('model_iterations_per_year', 24)  # every other week updates
    continuous_training_factor = training_config.get('continuous_training_factor', 0.3)  # 30% continuous learning

    # Effective training power accounting for efficiency losses
    effective_training_power_kw = (
            base_training_power_kw *
            failed_runs_overhead *
            (1 / parallelization_efficiency) *
            (1 / utilization_rate)
    )

    # Training time allocation
    full_retraining_hours = training_config.get('full_retraining_hours_per_iteration', 2160)  # 90 days
    incremental_training_hours = training_config.get('incremental_training_hours_per_iteration', 720)  # 30 days

    # Annual training compute energy
    annual_training_hours = (
            full_retraining_hours * model_iterations_per_year * 0.25 +  # 25% full retraining
            incremental_training_hours * model_iterations_per_year * 0.75 +  # 75% incremental
            8760 * continuous_training_factor  # Continuous learning
    )

    compute_energy_twh = (effective_training_power_kw * annual_training_hours) / 1e9

    # Data center infrastructure energy
    # datacenter_pue = 1.4  # Modern data center PUE

    # Cooling energy (included in PUE but calculated separately for breakdown)
    cooling_power_ratio = training_config.get('cooling_power_ratio', 0.77)  # 77% of compute power for cooling
    cooling_energy_twh = (effective_training_power_kw * cooling_power_ratio * annual_training_hours) / 1e9

    # Networking energy for distributed training
    networking_power_ratio = training_config.get('networking_power_ratio', 0.1)  # 10% of compute power
    networking_energy_twh = (effective_training_power_kw * networking_power_ratio * annual_training_hours) / 1e9

    # Storage energy for model checkpoints and datasets
    storage_power_ratio = training_config.get('storage_power_ratio', 0.05)  # 5% of compute power
    storage_energy_twh = (effective_training_power_kw * storage_power_ratio * 8760) / 1e9  # Always on

    # Total with PUE
    total_training_energy_twh = compute_energy_twh + cooling_energy_twh + networking_energy_twh

    return {
        'effective_training_power_kw': effective_training_power_kw,
        'annual_training_hours': annual_training_hours,
        'compute_energy_twh': compute_energy_twh,
        'cooling_energy_twh': cooling_energy_twh,
        'networking_energy_twh': networking_energy_twh,
        'storage_energy_twh': storage_energy_twh,
        'total_training_infrastructure_energy_twh': total_training_energy_twh,
        'utilization_efficiency': utilization_rate * parallelization_efficiency
    }


def standard_zero_energy_result():
    """
    Return standard zero energy result for Level 0 vehicles (no autonomous features)
    """
    return {
        'total_energy_twh': 0,
        'total_utilization_time_hours': 0,
        'breakdown_by_category': {},
        'annual_utilization_hours': 0,

        # Onboard energy breakdown
        'onboard_compute_energy_twh': 0,
        'onboard_sensors_energy_twh': 0,
        'onboard_cooling_energy_twh': 0,
        'onboard_storage_energy_twh': 0,

        # Infrastructure energy breakdown
        'data_transmission_energy_twh': 0,
        'datacenter_storage_energy_twh': 0,
        'training_per_vehicle_energy_twh': 0,

        # Fleet-level training energy
        'total_fleet_training_energy_twh': 0,

        # Detailed breakdowns (empty)
        'storage_details': {'total_onboard_storage_energy_twh': 0},
        'transmission_details': {'total_transmission_energy_twh': 0},
        'datacenter_storage_details': {'total_datacenter_storage_energy_twh': 0},
        'training_details': {'total_training_infrastructure_energy_twh': 0},
        'cooling_power_kw': 0,
        'fleet_size': 0
    }

def calculate_av_energy_consumption(vehicle_config, defaults):
    """
    Enhanced calculation of total energy consumption including full infrastructure.

    Parameters:
    vehicle_config (dict): Vehicle configuration for specific autonomy level
    defaults (dict): Default configuration values including new infrastructure configs

    Returns:
    dict: Comprehensive energy breakdown
    """
    level = vehicle_config['level']
    # Get fleet size from vehicle config or use provided value
    fleet_share = vehicle_config.get('fleet_share', 0)
    fleet_size = vehicle_config.get('fleet_size', fleet_share * defaults.get('fleet_total', 0)/365.0)

    # Skip level 0 (no autonomous features)
    # Handle zero fleet size (like Level 5)
    if level == 0 or fleet_size == 0:
        return standard_zero_energy_result()

    # Get configuration values (existing logic)
    vmt_split = vehicle_config.get('overrides', {}).get('vmt_split', defaults['vmt_split'])
    speeds_mph = vehicle_config.get('overrides', {}).get('speeds_mph', defaults['speeds_mph'])
    power_utilization = vehicle_config.get('overrides', {}).get('utilization_rate', defaults['utilization_rate'])

    # Base power ratings (TDP)
    compute_tdp_kw = vehicle_config.get('compute_tdp_watt', 0) / 1000
    sensors_tdp_kw = vehicle_config.get('sensors_tdp_watt', 0) / 1000
    driving_vmt_daily = vehicle_config.get('driving_vmt_daily', fleet_share * defaults['vmt_total'] / 365.0)

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

    # **********************
    # Onboard cooling energy
    # **********************
    cooling_overhead = defaults.get('cooling_config', {}).get('cooling_overhead_factor', 0.77)
    annual_onboard_cooling_energy_twh = (annual_compute_energy_twh + annual_sensors_energy_twh) * cooling_overhead

    # **********************
    # Onboard data storage energy (only if vehicle has sensors)
    # **********************
    annual_onboard_storage_energy_twh = 0
    if vehicle_config.get('sensors_data_Mbit_per_second', 0) > 0:
        # Data generation rate (TB/hour from sensors)
        # 1 Mbps = 1e6 bits/sec = 125,000 bytes/sec = 0.45 GB/hour = 0.00045 TB/hour
        sensors_data_rate = vehicle_config.get('sensors_data_Mbit_per_second', 0) * 0.00045  # Convert Mbps to TB/hour

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
        # Storage always powered (24/7)
        annual_onboard_storage_energy_twh = (((storage_power_kw * 8760) + processing_power_kw) * 365) / 1e9


    # NEW: Data transmission energy (only if vehicle has sensors)
    data_transmission_energy_twh = 0
    transmission_results = {'total_transmission_energy_twh': 0}
    if vehicle_config.get('sensors_data_Mbit_per_second', 0) > 0:
        transmission_results = calculate_data_transmission_energy(vehicle_config, annual_utilization_hours)
        data_transmission_energy_twh = transmission_results['total_transmission_energy_twh']

    # NEW: Shared infrastructure energy calculation
    shared_infrastructure = calculate_shared_infrastructure_energy(
        vehicle_config, defaults, annual_utilization_hours, fleet_size
    )

    # Datacenter storage: Scales with fleet size, so divide per vehicle
    datacenter_storage_energy_twh = shared_infrastructure['total_datacenter_storage_energy_twh']

    # Training infrastructure: SHARED across entire fleet, calculate total and per-vehicle allocation
    total_fleet_training_energy_twh = shared_infrastructure['total_training_infrastructure_energy_twh']

    # Total energy per vehicle (including allocated share of training)
    total_energy_twh = (
            annual_compute_energy_twh +
            annual_sensors_energy_twh +
            annual_onboard_cooling_energy_twh +
            annual_onboard_storage_energy_twh +
            data_transmission_energy_twh +
            datacenter_storage_energy_twh +
            total_fleet_training_energy_twh
    )

    return {
        'total_energy_twh': total_energy_twh,
        'annual_driving_hours': annual_driving_hours,
        'breakdown_by_category': breakdown_by_category,

        # Onboard energy breakdown
        'annual_compute_energy_twh': annual_compute_energy_twh,
        'annual_sensors_energy_twh': annual_sensors_energy_twh,
        'annual_onboard_cooling_energy_twh': annual_onboard_cooling_energy_twh,
        'annual_onboard_storage_energy_twh': annual_onboard_storage_energy_twh,

        # Infrastructure energy breakdown
        'data_transmission_energy_twh': data_transmission_energy_twh,
        'datacenter_storage_energy_twh': datacenter_storage_energy_twh,
        'training_per_vehicle_energy_twh': total_fleet_training_energy_twh/fleet_size,  # Per-vehicle allocation

        # Fleet-level training energy (the actual shared infrastructure)
        'total_fleet_training_energy_twh': total_fleet_training_energy_twh,

        # Detailed breakdowns
        'storage_details': storage_results,
        'transmission_details': transmission_results,
        'datacenter_storage_details': shared_infrastructure['datacenter_storage_results'],
        'training_details': shared_infrastructure['training_results'],
        'cooling_power_kw': onboard_cooling_power_kw,
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
            "total_energy_twh": 0,
            "total_utilization_time_hours": 0,
            "onboard_compute_energy_twh": 0,
            "onboard_sensors_energy_twh": 0,
            "onboard_cooling_energy_twh": 0,
            "onboard_storage_energy_twh": 0,
            "data_transmission_energy_twh": 0,
            "datacenter_storage_energy_twh": 0,
            "training_infrastructure_energy_twh": 0,
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
            totals["total_energy_twh"] += level_result["total_energy_twh"]
            totals["total_utilization_time_hours"] += level_result["total_utilization_time_hours"]
            totals["onboard_compute_energy_twh"] += level_result["onboard_compute_energy_twh"]
            totals["onboard_sensors_energy_twh"] += level_result["onboard_sensors_energy_twh"]
            totals["onboard_cooling_energy_twh"] += level_result["onboard_cooling_energy_twh"]
            totals["onboard_storage_energy_twh"] += level_result["onboard_storage_energy_twh"]
            totals["data_transmission_energy_twh"] += level_result["data_transmission_energy_twh"]
            totals["datacenter_storage_energy_twh"] += level_result["datacenter_storage_energy_twh"]

            # CRITICAL FIX: Use total_fleet_training_energy_twh (shared infrastructure)
            # instead of per-vehicle allocation
            totals["training_infrastructure_energy_twh"] += level_result["total_fleet_training_energy_twh"]

            # Aggregate breakdown by category
            for category, data in level_result["breakdown_by_category"].items():
                if category not in totals["breakdown_by_category"]:
                    totals["breakdown_by_category"][category] = {
                        'vmt': 0,
                        'travel_time_hours': 0,
                        'utilization_time_hours': 0
                    }
                totals["breakdown_by_category"][category]['vmt'] += data['vmt']
                totals["breakdown_by_category"][category]['travel_time_hours'] += data['travel_time_hours']
                totals["breakdown_by_category"][category]['utilization_time_hours'] += data[
                                                                                           'utilization_time_hours']

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
        print(f"  Onboard Energy:")
        print(f"    Compute: {level_data['onboard_compute_energy_twh']:.6f} TWh")
        print(f"    Sensors: {level_data['onboard_sensors_energy_twh']:.6f} TWh")
        print(f"    Cooling: {level_data['onboard_cooling_energy_twh']:.6f} TWh")
        print(f"    Storage: {level_data['onboard_storage_energy_twh']:.6f} TWh")
        print(f"  Infrastructure Energy:")
        print(f"    Data Transmission: {level_data['data_transmission_energy_twh']:.6f} TWh")
        print(f"    Datacenter Storage: {level_data['datacenter_storage_energy_twh']:.6f} TWh")
        print(f"    Training Infrastructure: {level_data['total_fleet_training_energy_twh']:.6f} TWh")

    # Print fleet totals
    totals = results['fleet_totals']
    print(f"\n{'FLEET TOTALS':<25}")
    print(f"{'=' * 80}")
    print(f"Total Energy Consumption: {totals['total_energy_twh']:.6f} TWh")
    print(f"\nOnboard Energy Breakdown:")
    print(f"  Compute: {totals['onboard_compute_energy_twh']:.6f} TWh")
    print(f"  Sensors: {totals['onboard_sensors_energy_twh']:.6f} TWh")
    print(f"  Cooling: {totals['onboard_cooling_energy_twh']:.6f} TWh")
    print(f"  Storage: {totals['onboard_storage_energy_twh']:.6f} TWh")
    print(f"\nInfrastructure Energy Breakdown:")
    print(f"  Data Transmission: {totals['data_transmission_energy_twh']:.6f} TWh")
    print(f"  Datacenter Storage: {totals['datacenter_storage_energy_twh']:.6f} TWh")
    print(f"  Training Infrastructure: {totals['training_infrastructure_energy_twh']:.6f} TWh")

    # Calculate percentages
    if totals['total_energy_twh'] > 0:
        onboard_pct = ((totals['onboard_compute_energy_twh'] + totals['onboard_sensors_energy_twh'] +
                        totals['onboard_cooling_energy_twh'] + totals['onboard_storage_energy_twh']) /
                       totals['total_energy_twh']) * 100
        infrastructure_pct = 100 - onboard_pct

        print(f"\nEnergy Distribution:")
        print(f"  Onboard Systems: {onboard_pct:.1f}%")
        print(f"  Infrastructure: {infrastructure_pct:.1f}%")

    print(f"{'=' * 80}")


# Example usage
if __name__ == "__main__":
    scenarios = [
        'scenarios/av_2025_baseline.yaml',
        'scenarios/av_2035_projected_adoption.yaml',
        'scenarios/av_2050_mass_adoption.yaml'
    ]

    for scenario_file in scenarios:
        print(f"\n{'=' * 80}")
        print(f"Processing: {scenario_file}")
        print(f"{'=' * 80}")

        config = load_scenario_config(scenario_file)
        results = calculate_fleet_energy_consumption(config)
        print_enhanced_fleet_summary(results)