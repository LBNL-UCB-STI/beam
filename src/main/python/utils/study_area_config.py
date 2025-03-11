"""
Configuration file for study area settings used in OSM network download and processing.
This file contains all the parameters needed to define a study area and its network characteristics.
"""
import os
import osmnx as ox
from osmnx import settings

#############################
########## Methods ##########
#############################

def generate_config_name(config: dict) -> str:
    """
    Generate a configuration name based on study area, graph layers, and tolerance.
    Format: [study_area]-[main_geo_level]-[residential_geo_level][density]-t[tolerance][-ferry]-network

    Example output: sfbay-area-cbg7000-network or sfbay-area-cbg7000-ferry-network
    """
    # Get study area
    study_area = config["study_area"]
    layers = config["graph_layers"]

    # Get residential geographic level and density
    if "residential" in layers:
        density_value = str(layers["residential"]["min_density_per_km2"])
        residential_geo_level = f"-{layers["residential"]["geo_level"]}{density_value}"
    else:
        density_value = ""
        residential_geo_level = ""

    # Ferry suffix
    ferry_suffix = "-ferry" if "ferry" in layers else ""

    # Combine all parts
    return f"{study_area}-area{residential_geo_level}{ferry_suffix}-network"


def create_osm_highway_filter(highway_types):
    """
    Convert a list of highway types to an OSM custom filter string.

    Args:
        highway_types (list): List of highway type strings

    Returns:
        str: OSM custom filter string in the format '["highway"~"type1|type2|..."]'
    """
    # Join the highway types with the pipe character
    highway_regex = "|".join(highway_types)

    # Create the full filter string
    filter_string = f'["highway"~"{highway_regex}"]'

    return filter_string


def get_area_config(area_name):

    """
    Retrieve a deep copy of the configuration for the specified area.

    Args:
        area_name (str): The name of the area ('sfbay' or 'seattle')

    Returns:
        dict: A deep copy of the area's configuration

    Raises:
        ValueError: If an invalid area name is provided
    """
    import copy
    area_configs = {
        "sfbay": sfbay_area_config,
        "seattle": seattle_area_config
    }

    if area_name not in area_configs:
        valid_areas = ", ".join(f"'{area}'" for area in area_configs.keys())
        raise ValueError(f"Invalid area name '{area_name}'. Choose from: {valid_areas}")

    return copy.deepcopy(area_configs[area_name])

#############################
########## Settings #########
#############################

constants = {
    "joule_per_meter_base_rate": 1.213e8, # Energy consumption base rate in joules per meter
    "max_fuel_capacity_in_joule": 1.2e16, # Maximum fuel capacity in joules (represents physical tank limits)
    "meters_per_mile": 1609.34 # Conversion factor from miles to meters
} 

osm_default_highways = ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
                        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified"]

osmnx_settings = {
        "log_console": True,
        "use_cache": True,
        "cache_only_mode": False,
        "all_oneway": True,
        "requests_timeout": 180,
        "overpass_memory": None,
        "max_query_area_size": 50 * 1000 * 50 * 1000,  # 50km × 50km
        "overpass_rate_limit": False,
        "overpass_max_attempts": 3,
        "useful_tags_way": list(ox.settings.useful_tags_way) + [
            "maxweight", "hgv", "maxweight:hgv", "maxlength", "motorcar", "motor_vehicle", "goods", "truck"
        ],
        "overpass_url": "https://overpass-api.de/api",
        # https://wiki.openstreetmap.org/wiki/Overpass_API#Public_Overpass_API_instances
    }

weight_limits = {
        "unit": "lbs",
        "mdv_max": 26000,  # Upper limit for Medium Duty Vehicles (Class 3-6) in pounds
        "hdv_max": 80000,  # Upper limit for Heavy Duty Vehicles (Class 7-8) in pounds
    }

fastsim_routee_files = {
    "primary_powertrain": {
        "freight-md-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(Diesel,_2020,_no_program).csv",
        "freight-md-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
        # "freight-md-E-H2FC-Baseline": np.nan,
        "freight-md-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
        "freight-hdt-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(Diesel,_2020,_no_program).csv",
        "freight-hdt-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
        # "freight-hdt-E-H2FC-Baseline": np.nan,
        "freight-hdt-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
        "freight-hdv-D-Diesel-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(Diesel,_2020,_no_program).csv",
        "freight-hdv-E-BE-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv",
        # "freight-hdv-E-H2FC-Baseline": np.nan,
        "freight-hdv-E-PHEV-Baseline": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv"
    },
    "secondary_powertrain": {
        # "freight-md-D-Diesel-Baseline": np.nan,
        # "freight-md-E-BE-Baseline": np.nan,
        # "freight-md-E-H2FC-Baseline": np.nan,
        "freight-md-E-PHEV-Baseline": ("Diesel",
                                       9595.796035186175,
                                       constants["max_fuel_capacity_in_joule"],
                                       "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(HEV,_2025,_no_program).csv"),
        # "freight-hdt-D-Diesel-Baseline": np.nan,
        # "freight-hdt-E-BE-Baseline": np.nan,
        # "freight-hdt-E-H2FC-Baseline": np.nan,
        "freight-hdt-E-PHEV-Baseline": ("Diesel",
                                        13817.086117829229,
                                        constants["max_fuel_capacity_in_joule"],
                                        "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(HEV,_2025,_no_program).csv"),
        # "freight-hdv-D-Diesel-Baseline": np.nan,
        # "freight-hdv-E-BE-Baseline": np.nan,
        # "freight-hdv-E-H2FC-Baseline": np.nan,
        "freight-hdv-E-PHEV-Baseline": ("Diesel",
                                        14026.761465378302,
                                        constants["max_fuel_capacity_in_joule"],
                                        "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(HEV,_2025,_no_program).csv")
    }
}

########## SF Bay Area #########

sfbay_area_config = {
    # OSMNX settings
    "osmnx_settings": osmnx_settings,

    # Vehicle weight classifications (FHWA)
    "weight_limits": weight_limits,

    # FastSim routee files
    "fastsim_routee_files": fastsim_routee_files,

    # Transit stop data

    # if download isn't enabled, we read network from disk
    "download_enabled": True,

    # Base paths
    "work_dir": os.path.expanduser("~/Workspace/Simulation/sfbay"),

    # Geographic settings
    "study_area": "sfbay",
    "state_fips": "06",
    "county_fips": ['001', '013', '041', '055', '075', '081', '085', '095', '097', '087', '113'],
    "census_year": 2018,
    "utm_epsg": 26910,  # NAD83 / UTM zone 10N
    "tolerance": 2,

    # Density thresholds and corresponding network filters
    "graph_layers": {
        "main": {
            "geo_level": "county",
            "custom_filter": create_osm_highway_filter(osm_default_highways),
            "buffer_zone_in_meters": 200
        },
        "residential": {
            "min_density_per_km2": 4500,
            "geo_level": "cbg",
            "custom_filter": create_osm_highway_filter(osm_default_highways + ["residential"]),
            "buffer_zone_in_meters": 20
        }
        # // California has a higher urbanization rate (94.8% urban vs 80.7% national average)
        # // https://dof.ca.gov/wp-content/uploads/sites/352/Forecasting/Demographics/Documents/Urban-Rural_Classification_and_2020_Urban_Area_Criteria_CA_SDC.pdf
        # const avgPersonsPerHousehold = 2.9; // CA average household size (higher than national 2.5)
        #
        # // Core density calculation (using similar proportions as national but adjusted for CA household size)
        # const coreHUDensity = 1275; // National high-density nucleus requirement
        # const caDensityAdjustment = 2.9 / 2.5; // CA vs national household size ratio
        # // Calculate CA-adjusted thresholds
        # const caHighDensityPPSM = coreHUDensity * 2.9;
        # const caInitialCorePPSM = 425 * 2.9;
        # const caUrbanExtensionPPSM = 200 * 2.9;
        # // Result
        # // California-adjusted density thresholds (persons per square mile):
        # //  densest urban cores, typical of downtown areas in major California cities:  7,395 ppsm = 2,855 ppsk
        # // High-density nucleus requirement: 3698 ppsm = 1429 ppsk
        # // Initial core requirement: 1233 ppsm = 475 ppsk
        # // Urban extension requirement: 580 ppsm = 224 ppsk
        # // Rural Areas less than 580 people per square mile
    }
}

########## Seattle Area #########

seattle_area_config = {
    # OSMNX settings
    "osmnx_settings": osmnx_settings,

    # Vehicle weight classifications (FHWA)
    "weight_limits": weight_limits,

    # FastSim routee files
    "fastsim_routee_files": fastsim_routee_files,

    # if download isn't enabled, we read network from disk
    "download_enabled": True,

    # Base paths
    "work_dir": os.path.expanduser("~/Workspace/Simulation/seattle"),

    # Geographic settings
    "study_area": "seattle",
    "state_fips": "53",
    "county_fips": ["061", "033", "035", "053"],
    "census_year": 2018,
    "utm_epsg": 32048,  #
    "tolerance": 2,

    # Density thresholds and corresponding network filters
    "graph_layers": {
        "main": {
            "geo_level": "county",
            "custom_filter": create_osm_highway_filter(osm_default_highways),
            "buffer_zone_in_meters": 200
        },
        "ferry": {
            "geo_level": "county",
            "custom_filter": '["route"="ferry"]',
            "buffer_zone_in_meters": 10000
        },
        "residential": {
            "min_density_per_km2": 0,
            "geo_level": "cbg",
            "custom_filter": create_osm_highway_filter(osm_default_highways + ["residential"]),
            "buffer_zone_in_meters": 20
        }
        # // Washington has a moderate urbanization rate (84.1% urban vs 80.7% national average)
        # // https://www.census.gov/quickfacts/fact/table/WA/INC110223
        # // Washington's urbanization rate is higher than the national average but lower than California's 94.8%
        # const avgPersonsPerHousehold = 2.51; // WA average household size (slightly higher than national 2.5)

        # // Core density calculation (using similar proportions as national but adjusted for WA household size)
        # const coreHUDensity = 1275; // National high-density nucleus requirement
        # const waDensityAdjustment = 2.51 / 2.5; // WA vs national household size ratio
        # // Calculate WA-adjusted thresholds
        # const waHighDensityPPSM = coreHUDensity * 2.51;
        # const waInitialCorePPSM = 425 * 2.51;
        # const waUrbanExtensionPPSM = 200 * 2.51;

        # // Washington-adjusted density thresholds (persons per square mile):
        # // densest urban cores, typical of downtown areas in major Washington cities: 3200 ppsm = 1236 ppsk
        # // High-density nucleus requirement: 3200 ppsm = 1236 ppsk
        # // Initial core requirement: 1067 ppsm = 412 ppsk
        # // Urban extension requirement: 502 ppsm = 194 ppsk
        # // Rural Areas less than 502 people per square mile
    }
}