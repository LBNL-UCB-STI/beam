"""
Configuration file for study area settings used in OSM network download and processing.
This file contains all the parameters needed to define a study area and its network characteristics.
"""
import os

import osmnx as ox
import pandas as pd


#############################
########## Methods ##########
#############################

def get_fuel_key(row):
    """
    Derive the standardized fuel key from vehicle data row.

    This function extracts the primary fuel type and adds a suffix
    for electric vehicles based on whether they are pure electric
    or hybrid vehicles.

    Args:
        row (pandas.Series): A row from a vehicle types DataFrame
            containing 'primaryFuelType' and 'secondaryFuelType' columns

    Returns:
        str: A standardized fuel key string
    """
    # Get primary fuel and convert to lowercase
    fuel = row['primaryFuelType'].lower()

    # Special handling for electric vehicles
    if fuel == "electricity":
        # Check if it's a hybrid (has a secondary fuel) or pure electric
        suffix = "only" if pd.isna(row['secondaryFuelType']) else "hybrid"
        return f"{fuel}-{suffix}"

    return fuel

def generate_network_name(config: dict) -> str:
    """
    Generate a configuration name based on study area, graph layers, and tolerance.
    Format: [study_area]-[main_geo_level]-[residential_geo_level][density]-t[tolerance][-ferry]-network

    Example output: sfbay-area-cbg7000-network or sfbay-area-cbg7000-ferry-network
    """
    # Get study area
    study_area = config["area"]["name"]
    layers = config["network"]["graph_layers"]

    # Get residential geographic level and density
    if "residential" in layers:
        density_value = str(layers["residential"]["min_density_per_km2"])
        residential_geo_level = f"-{layers['residential']['geo_level']}{density_value}"
    else:
        residential_geo_level = ""

    strongly_connected_components = config["network"]["strongly_connected_components"]
    if strongly_connected_components:
        connection_label = "strong"
    else:
        connection_label = "weak"

    # Ferry suffix
    ferry_suffix = "-ferry" if "ferry" in layers else ""

    # Combine all parts
    return f"{study_area}-area{residential_geo_level}{ferry_suffix}-{connection_label}Conn-network"


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

# Create a file named beam_classes.py

class BeamClasses:
    """
    BEAM vehicle class definitions with flexible import options.

    This class provides accessible vehicle class constants used in BEAM transportation models,
    with helper methods for grouping and categorization.
    """
    # Freight vehicle classes
    CLASS_2B3_VOCATIONAL = 'Class2b3Vocational'
    CLASS_456_VOCATIONAL = 'Class456Vocational'
    CLASS_78_VOCATIONAL = 'Class78Vocational'
    CLASS_78_TRACTOR = 'Class78Tractor'

    # Non-freight vehicle classes
    CLASS_CAR = "Car"  # includes light and medium duty trucks
    CLASS_BIKE = "Bike"
    CLASS_MDP = "MediumDutyPassenger"

    @classmethod
    def get_medium_heavy_freight_classes(cls):
        """Returns a list of all freight vehicle classes."""
        return [
            cls.CLASS_456_VOCATIONAL,
            cls.CLASS_78_VOCATIONAL,
            cls.CLASS_78_TRACTOR
        ]

    @classmethod
    def get_freight_classes(cls):
        """Returns a list of all freight vehicle classes."""
        return [
            cls.CLASS_2B3_VOCATIONAL,
            cls.CLASS_456_VOCATIONAL,
            cls.CLASS_78_VOCATIONAL,
            cls.CLASS_78_TRACTOR
        ]

    @classmethod
    def get_passenger_classes(cls):
        """Returns a list of all non-freight vehicle classes."""
        return [
            cls.CLASS_CAR,
            cls.CLASS_BIKE,
            cls.CLASS_MDP
        ]

    @classmethod
    def get_all_classes(cls):
        """Returns a list of all vehicle classes."""
        return cls.get_freight_classes() + cls.get_passenger_classes()

    @classmethod
    def is_freight(cls, beam_class):
        """Returns True if the given class is a freight vehicle class."""
        return beam_class in cls.get_freight_classes()

    @classmethod
    def class_to_display_name(cls, beam_class):
        """Converts internal class names to display-friendly names."""
        display_names = {
            cls.CLASS_2B3_VOCATIONAL: "Class 2b/3 Vocational",
            cls.CLASS_456_VOCATIONAL: "Class 4-6 Vocational",
            cls.CLASS_78_VOCATIONAL: "Class 7-8 Vocational",
            cls.CLASS_78_TRACTOR: "Class 7-8 Tractor",
            cls.CLASS_CAR: "Passenger Car",
            cls.CLASS_BIKE: "Bicycle",
            cls.CLASS_MDP: "Medium-Duty Passenger"
        }
        return display_names.get(beam_class, beam_class)

constants = {
    "joule_per_meter_base_rate": 1.213e8, # Energy consumption base rate in joules per meter
    "max_fuel_capacity_in_joule": 1.2e16, # Maximum fuel capacity in joules (represents physical tank limits)
    "meters_per_mile": 1609.34 # Conversion factor from miles to meters
}

osm_residential = ["residential"]

osm_highways = ["motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary",
                "secondary_link", "tertiary", "tertiary_link", "unclassified"]



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
        "md-D-Diesel": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(Diesel,_2020,_no_program).csv",
        "md-E-BE": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
        # "md-E-H2FC": np.nan,
        "md-E-PHEV": "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(BEV,_2025,_no_program).csv",
        "hdt-D-Diesel": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(Diesel,_2020,_no_program).csv",
        "hdt-E-BE": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
        # "hdt-E-H2FC": np.nan,
        "hdt-E-PHEV": "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(BEV,_2025,_no_program).csv",
        "hdv-D-Diesel": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(Diesel,_2020,_no_program).csv",
        "hdv-E-BE": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv",
        # "hdv-E-H2FC": np.nan,
        "hdv-E-PHEV": "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(BEV,_2025,_no_program).csv"
    },
    "secondary_powertrain": {
        # "md-D-Diesel": np.nan,
        # "md-E-BE": np.nan,
        # "md-E-H2FC": np.nan,
        "md-E-PHEV": ("Diesel",
                                       9595.796035186175,
                                       constants["max_fuel_capacity_in_joule"],
                                       "Freight_Baseline_FASTSimData_2020/Class_6_Box_truck_(HEV,_2025,_no_program).csv"),
        # "hdt-D-Diesel": np.nan,
        # "hdt-E-BE": np.nan,
        # "hdt-E-H2FC": np.nan,
        "hdt-E-PHEV": ("Diesel",
                                        13817.086117829229,
                                        constants["max_fuel_capacity_in_joule"],
                                        "Freight_Baseline_FASTSimData_2020/Class_8_Sleeper_cab_high_roof_(HEV,_2025,_no_program).csv"),
        # "hdv-D-Diesel": np.nan,
        # "hdv-E-BE": np.nan,
        # "hdv-E-H2FC": np.nan,
        "hdv-E-PHEV": ("Diesel",
                                        14026.761465378302,
                                        constants["max_fuel_capacity_in_joule"],
                                        "Freight_Baseline_FASTSimData_2020/Class_8_Box_truck_(HEV,_2025,_no_program).csv")
    }
}


########## Emissions #########

emissions_config = {
    "pollutants": {
        'CH4': 'rate_ch4_gram_float',
        'CO': 'rate_co_gram_float',
        'CO2': 'rate_co2_gram_float',
        'HC': 'rate_hc_gram_float',
        'NH3': 'rate_nh3_gram_float',
        'NOx': 'rate_nox_gram_float',
        'PM': 'rate_pm_gram_float',
        'PM10': 'rate_pm10_gram_float',
        'PM2_5': 'rate_pm2_5_gram_float',
        'ROG': 'rate_rog_gram_float',
        'SOx': 'rate_sox_gram_float',
        'TOG': 'rate_tog_gram_float',
        'BC': 'rate_bc_gram_float',
        'BCm': 'rate_bcm_gram_float',
        'BCh': 'rate_bch_gram_float'
    },
    "processes" : [
        "RUNEX", "IDLEX", "STREX", "DIURN", "HOTSOAK", "RUNLOSS", "PMTW", "PMBW", "PRDUST"
    ]
}

########## Vehicle Types #########
vehicle_types_config = {
    "columns": [
        "vehicleTypeId",
        "seatingCapacity",
        "standingRoomCapacity",
        "lengthInMeter",
        "primaryFuelType",
        "primaryFuelConsumptionInJoulePerMeter",
        "primaryFuelCapacityInJoule",
        "primaryVehicleEnergyFile",
        "secondaryFuelType",
        "secondaryFuelConsumptionInJoulePerMeter",
        "secondaryVehicleEnergyFile",
        "secondaryFuelCapacityInJoule",
        "automationLevel",
        "maxVelocity",
        "passengerCarUnit",
        "rechargeLevel2RateLimitInWatts",
        "rechargeLevel3RateLimitInWatts",
        "vehicleCategory",
        "sampleProbabilityWithinCategory",
        "sampleProbabilityString"
    ]
}

########## SF Bay Area #########

sfbay_area_config = {
    # Base paths
    "work_dir": os.path.expanduser("~/Workspace/Simulation/sfbay"),

    "area": {
        "name": "sfbay",
        "state_fips": "06",
        # 087 Santa Cruz
        # 113 Yolo
        "county_fips": ['001', '013', '041', '055', '075', '081', '085', '095', '097'],
        "census_year": 2018,
    },

    "geo": {
        "utm_epsg": 26910, # NAD83 / UTM zone 10N
        "taz_shp": "geo/shp/sfbay-tazs-epsg-26910.shp",
        "taz_id": "taz1454",
        "cbg_id": "GEOID",
    },

    "network": {
        "osmnx_settings": osmnx_settings,
        "weight_limits": weight_limits, # Vehicle weight classifications (FHWA)
        "download_enabled": True, # if download isn't enabled, we read network from disk
        "tolerance": 2,
        "strongly_connected_components": True,
        "graph_layers": { # Density thresholds and corresponding network filters
            "main": {
                "geo_level": "county",
                "custom_filter": create_osm_highway_filter(list(set(osm_highways))),
                "buffer_zone_in_meters": 200
            },
            "residential": {
                "min_density_per_km2": 5500,
                "geo_level": "cbg",
                "custom_filter": create_osm_highway_filter(list(set(osm_highways) | set(osm_residential))),
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
        },
        "validation": {
            "npmrds": {
                "year": 2018,
                "geo": "validation/npmrds/California.shp",
                "data": "validation/npmrds/al_ca_oct2018_1hr_trucks_pax.csv"
            }
        }
    },

    # FastSim routee files
    "fastsim_routee_files": fastsim_routee_files,

    "freight": {
        "stops_data": "data/austin_cargo_operations.csv",
        "2018_Baseline" : {
            "carriers_file": f"beam-ft/2024-11-06/2018-Baseline/carriers--2018-Baseline.csv",
            "payloads_file": f"beam-ft/2024-11-06/2018-Baseline/payloads--2018-Baseline.csv",
            "tours_file": f"beam-ft/2024-11-06/2018-Baseline/tours--2018-Baseline.csv",
            "ft_vehicle_types_file": f"vehicle-tech/ft-vehicletypes--20241106--2018-Baseline.csv"
        }
    },

    "emissions": {
        "2018-Baseline" : {
            "override_rates": False,
            "override_fleet": True,
            "run": {
                "output_dir": "emissions/20240123",
                "events_file": "beam-runs/20240123/2018-Baseline/0.events.csv.gz",
                "emissions_skims_file": "beam-runs/20240123/2018-Baseline/0.events.csv.gz",
                "link_stats_file": "beam-runs/20240123/2018-Baseline/0.linkstats.csv.gz",
                "sample_portion": 0.1,
            },
            "rates": {
                "filters": {
                    "season_month": "Annual",
                    "calendar_year": 2018,
                    "temperature": 60.,
                    "relative_humidity": 40.,
                    "sub_area": ["SF"],
                    "include_nan": True
                },
                "emfac": {
                    "version": "EMFAC2021",
                    "emfac_rates_by_model_year_file": f"emissions/rates/emfac/imputed_MTC_emission_rate_agg_NH3_added_2018_2025_2030_2040_2050.csv",
                    "emfac_vmt_by_model_year_file": f"emissions/rates/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_vmt_20240612233346.csv",
                    "emfac_pop_by_model_year_file": f"emissions/rates/emfac/Default_Statewide_2018_2025_2030_2040_2050_Annual_population_20240612233346.csv"
                },
                "black_carbon": {
                    "version": "",
                    "black_carbon_rates_file": f"emissions/rates/black_carbon/emfac_bc_rate_three_ver_2018.csv",
                },
                "road_dust": {
                    "version": "",
                    "rainy_days_file": f"emissions/rates/road_dust/CA_input/rainy_days.csv",
                    "silt_loading_file": f"emissions/rates/road_dust/CA_input/silt_loading.csv",
                }
            },
            "beam" : {
                "carriers_file": f"beam-ft/20240123/2018-Baseline/carriers--2018-Baseline.csv",
                "payloads_file": f"beam-ft/20240123/2018-Baseline/payloads--2018-Baseline.csv",
                "tours_file": f"beam-ft/20240123/2018-Baseline/tours--2018-Baseline.csv",
                "ft_vehicle_types_file": f"vehicle-tech/vehicleTypes--frism--2018-Baseline.csv",
                "pax_vehicles_file": f"beam-pax/vehicles--atlas--2017-Baseline.csv.gz",
                "pax_vehicle_types_file": f"vehicle-tech/vehicleTypes--atlas--2017-Baseline.csv"
            },
            "mapping": {
                "fleet": {
                    "ignore_beam_passenger_distribution": False,
                    "ignore_beam_freight_distribution": False,
                    "model_year_bins": [1993, 2006, 2018]
                },
                "atlas":{
                    "enable_atlas_emfac_crosswalk": True,
                    "emfac": f"atlas/atlas-emfac-xwalk.csv",
                    "alternatives": {
                        "car": ['car'],
                        "suv": ['suv', 'car', 'truck'],
                        'truck': ['truck', 'suv', 'minvan'],
                        'van': ['minvan', 'truck'],
                        'minvan': ['minvan', 'truck', 'van']
                    }
                },
                "fuel": {
                    "beam": {
                        "hydrogen": 'Elec', # From emission pov, BEAM's hydrogen cars shall be electric
                        "electricity-only": 'Elec',
                        "electricity-hybrid": 'Phe',
                        "gasoline": 'Gas',
                        "diesel": 'Dsl',
                        "biodiesel": 'Dsl' # From emission pov, BEAM's biodiesel cars shall be diesel
                    },
                    "emfac-ft": {
                        "Elec": 'Elec',
                        "Phe": 'Phe',
                        "Gas": 'Dsl',
                        "Dsl": 'Dsl',
                        "NG": 'Dsl' # EMFAC NG cars will be mapped to BEAM's diesel cars
                    },
                    "emfac-pax": {
                        "Elec": 'Elec',
                        "Phe": 'Phe',
                        "Gas": 'Gas',
                        "Dsl": 'Gas',
                        "NG": 'Gas' # EMFAC NG cars will be mapped to BEAM's diesel cars
                    },
                    "emfac-bus": {
                        "Elec": 'Elec',
                        "Phe": 'Phe',
                        "Gas": 'Gas',
                        "Dsl": 'Dsl',
                        "NG": 'Dsl'
                    },
                    "alternatives": {
                        "Elec": ['Elec', 'Phe'],
                        'Phe': ['Phe', 'Elec'],
                        "Gas": ['Gas', 'Dsl'],
                        "Dsl": ['Dsl', 'Gas']
                    }
                },
                "class": {
                    "emfac-ft": {
                        "T6 CAIRP Class 4": "Class456Vocational",
                        "T6 CAIRP Class 5": "Class456Vocational",
                        "T6 CAIRP Class 6": "Class456Vocational",
                        "T6 CAIRP Class 7": "Class78Tractor",
                        "T6 Instate Delivery Class 4": "Class456Vocational",
                        "T6 Instate Delivery Class 5": "Class456Vocational",
                        "T6 Instate Delivery Class 6": "Class456Vocational",
                        "T6 Instate Delivery Class 7": "Class78Vocational",
                        "T6 Instate Other Class 4": "Class456Vocational",
                        "T6 Instate Other Class 5": "Class456Vocational",
                        "T6 Instate Other Class 6": "Class456Vocational",
                        "T6 Instate Other Class 7": "Class78Vocational",
                        "T6 Instate Tractor Class 6": "Class456Vocational",
                        "T6 Instate Tractor Class 7": "Class78Tractor",
                        "T6 OOS Class 4": "Class456Vocational",
                        "T6 OOS Class 5": "Class456Vocational",
                        "T6 OOS Class 6": "Class456Vocational",
                        "T6 OOS Class 7": "Class78Vocational",
                        "T7 CAIRP Class 8": "Class78Tractor",
                        "T7 NNOOS Class 8": "Class78Vocational",
                        "T7 NOOS Class 8": "Class78Vocational",
                        "T7 Single Concrete/Transit Mix Class 8": "Class78Vocational",
                        "T7 Single Dump Class 8": "Class78Vocational",
                        "T7 Single Other Class 8": "Class78Vocational",
                        "T7 Tractor Class 8": "Class78Tractor",
                        "T7IS": "Class78Tractor"
                    },
                    "emfac-pax": {
                        "LDA": "Car",
                        "LDT1": "Car",
                        "LDT2": "Car",
                        "MCY": "Bike",
                        "MDV": "Car"
                    },
                    "emfac-bus": {
                        "UBUS": "MediumDutyPassenger"
                    },
                    "alternatives": {
                        "Class456Vocational": ['Class456Vocational', 'Class78Vocational'],
                        'Class78Vocational': ['Class78Vocational', 'Class456Vocational', 'Class78Tractor'],
                        "Class78Tractor": ['Class78Tractor', 'Class78Vocational'],
                        "Car": ['Car'],
                        "Bike": ['Bike'],
                        "MediumDutyPassenger": ['MediumDutyPassenger']
                    }
                }
            }
        }
    }
}

########## Seattle Area #########

seattle_area_config = {
    # Base paths
    "work_dir": os.path.expanduser("~/Workspace/Simulation/seattle"),

    "area": {
        "name": "seattle",
        "state_fips": "53",
        "county_fips": ["061", "033", "035", "053"],
        "census_year": 2018,
    },

    "geo": {
        "utm_epsg": 32048,
        "taz_shp": "geo/shp/seattle-tazs-epsg-32048.shp",
        "taz_id": "taz_id",
        "cbg_id": "GEOID",
    },

    "network": {
        "osmnx_settings": osmnx_settings,
        "weight_limits": weight_limits,  # Vehicle weight classifications (FHWA)
        "download_enabled": True,  # if download isn't enabled, we read network from disk
        "tolerance": 2,
        "strongly_connected_components": False,
        "graph_layers": {  # Density thresholds and corresponding network filters
            "main": {
                "geo_level": "county",
                "custom_filter": create_osm_highway_filter(list(set(osm_highways))),
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
                "custom_filter": create_osm_highway_filter(list(set(osm_highways) | set(osm_residential))),
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
        },
        "validation": {
            "npmrds": {
                "year": 2018,
                "geo": "validation/npmrds/Washington.shp",
                "data": "validation/npmrds/al_wa_oct2018_1hr_trucks_pax.csv"
            }
        }
    },

    # FastSim routee files
    "fastsim_routee_files": fastsim_routee_files,

    "freight": {

    },

    "emissions": {

    }
}