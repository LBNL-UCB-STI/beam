import copy
from typing import Dict, Any

import yaml


class AVScenarioGenerator:
    def __init__(self):
        # Base template for the YAML structure
        self.base_template = {
            "scenario": "",
            "defaults": {
                "vmt_total": 3300000000000.0,
                "fleet_total": 298700000,
                "vmt_split": {
                    "rural_interstate": 0.076,
                    "rural_freeway": 0.009,
                    "rural_principal_arterial": 0.062,
                    "rural_minor_arterial": 0.046,
                    "rural_major_collector": 0.052,
                    "rural_minor_collector": 0.016,
                    "rural_local": 0.041,
                    "urban_interstate": 0.173,
                    "urban_freeway": 0.075,
                    "urban_principal_arterial": 0.155,
                    "urban_minor_arterial": 0.129,
                    "urban_major_collector": 0.064,
                    "urban_minor_collector": 0.004,
                    "urban_local": 0.097
                },
                "speeds_mph": {
                    "rural_interstate": 72.6,
                    "rural_freeway": 72.31,
                    "rural_principal_arterial": 55.3,
                    "rural_minor_arterial": 53.58,
                    "rural_major_collector": 43.62,
                    "rural_minor_collector": 43.04,
                    "rural_local": 29.21,
                    "urban_interstate": 57.60,
                    "urban_freeway": 57.31,
                    "urban_principal_arterial": 25.30,
                    "urban_minor_arterial": 23.58,
                    "urban_major_collector": 23.62,
                    "urban_minor_collector": 23.04,
                    "urban_local": 14.21
                },
                "utilization_rate": {
                    "rural_interstate": 0.5,
                    "rural_freeway": 0.55,
                    "rural_principal_arterial": 0.6,
                    "rural_minor_arterial": 0.65,
                    "rural_major_collector": 0.7,
                    "rural_minor_collector": 0.75,
                    "rural_local": 0.8,
                    "urban_interstate": 0.65,
                    "urban_freeway": 0.7,
                    "urban_principal_arterial": 0.75,
                    "urban_minor_arterial": 0.8,
                    "urban_major_collector": 0.85,
                    "urban_minor_collector": 0.9,
                    "urban_local": 0.95
                },
                "cooling_config": {
                    "cooling_overhead_factor": 0.77
                },
                "training_config": {
                    "gpu_utilization_rate": 0.75,
                    "failed_runs_overhead_factor": 1.2,
                    "model_iterations_per_year": 52,
                    "continuous_training_factor": 0.2,
                    "full_retraining_hours_per_iteration": 720,
                    "incremental_training_hours_per_iteration": 336,
                    "cooling_power_ratio": 0.77,
                    "networking_power_ratio": 0.1,
                    "storage_power_ratio": 0.05
                }
            },
            "fleet": []
        }

        # Base fleet configurations for each level
        self.fleet_configs = {
            0: {
                "level": 0,
                "fleet_share": 0.0,
                "driving_vmt_daily": 0,
                "fleet_size": 0
            },
            1: {
                "level": 1,
                "fleet_share": 0.0,
                "driving_vmt_daily": 0,
                "fleet_size": 0,
                "compute_tdp_watt": 2.5,
                "sensors_tdp_watt": 3.66
            },
            2: {
                "level": 2,
                "fleet_share": 0.0,
                "driving_vmt_daily": 0,
                "fleet_size": 0,
                "compute_tdp_watt": 10,
                "sensors_tdp_watt": 14.92
            },
            3: {
                "level": 3,
                "fleet_share": 0.0,
                "driving_vmt_daily": 0,
                "fleet_size": 0,
                "compute_tdp_watt": 110,
                "sensors_tdp_watt": 12,
                "sensors_data_Mbit_per_second": 13272,
                "training_tdp_watt": 1200000
            },
            4: {
                "level": 4,
                "fleet_share": 0.0,
                "driving_vmt_daily": 0,
                "fleet_size": 0,
                "simulation_vmt_daily": 0,
                "compute_tdp_watt": 800,
                "sensors_tdp_watt": 227.97,
                "sensors_data_Mbit_per_second": 33628,
                "training_tdp_watt": 2800000,
                "overrides": {
                    "vmt_split": {
                        "rural_interstate": 0.0014,
                        "rural_freeway": 0.0014,
                        "rural_principal_arterial": 0.0014,
                        "rural_minor_arterial": 0.0014,
                        "rural_major_collector": 0.0014,
                        "rural_minor_collector": 0.0014,
                        "rural_local": 0.0014,
                        "urban_interstate": 0.06,
                        "urban_freeway": 0.06,
                        "urban_principal_arterial": 0.174,
                        "urban_minor_arterial": 0.174,
                        "urban_major_collector": 0.174,
                        "urban_minor_collector": 0.174,
                        "urban_local": 0.174
                    }
                },
                "speeds_mph": {
                    "rural_interstate": 70,
                    "rural_freeway": 70,
                    "rural_principal_arterial": 55,
                    "rural_minor_arterial": 45,
                    "rural_major_collector": 43.62,
                    "rural_minor_collector": 43.04,
                    "rural_local": 25,
                    "urban_interstate": 55,
                    "urban_freeway": 55,
                    "urban_principal_arterial": 25.30,
                    "urban_minor_arterial": 23.58,
                    "urban_major_collector": 23.62,
                    "urban_minor_collector": 23.04,
                    "urban_local": 14.21
                }
            },
            5: {
                "level": 5,
                "fleet_share": 0.0,
                "driving_vmt_daily": 0,
                "fleet_size": 0,
                "simulation_vmt_daily": 0,
                "compute_tdp_watt": 0,
                "sensors_tdp_watt": 0,
                "sensors_data_Mbit_per_second": 0,
                "training_tdp_watt": 0
            }
        }

        # Base total fleet size (298.7 million vehicles)
        self.base_fleet_size = 298700000
        self.base_total_vmt = 3.3 * 1e12  # 3.3 trillion VMT

    def generate_scenario(self, scenario_name: str, demand_growth_rate: float,
                          adoption_percentages: Dict[int, float]) -> Dict[str, Any]:
        """
        Generate a scenario with specified parameters.

        Args:
            scenario_name: Name for the scenario
            adoption_percentages: Dict mapping AV level to adoption percentage

        Returns:
            Dictionary representing the complete scenario
        """
        scenario = copy.deepcopy(self.base_template)
        scenario["scenario"] = scenario_name
        self.base_fleet_size = int(self.base_fleet_size * demand_growth_rate)
        self.base_total_vmt = self.base_total_vmt * demand_growth_rate
        self.base_template["defaults"]["vmt_total"] = self.base_total_vmt
        self.base_template["defaults"]["fleet_total"] = self.base_fleet_size

        # Generate fleet configurations
        for level in range(6):
            if level in adoption_percentages and adoption_percentages[level] > 0:
                fleet_config = copy.deepcopy(self.fleet_configs[level])

                # Calculate fleet size and VMT for this level
                adoption_rate = adoption_percentages[level] / 100.0
                fleet_config["fleet_share"] = adoption_rate
                fleet_config["fleet_size"] = int(self.base_fleet_size * adoption_rate/365)
                fleet_config["driving_vmt_daily"] = int(self.base_total_vmt * adoption_rate/365)
                scenario["fleet"].append(fleet_config)

        return scenario

    def save_scenario(self, scenario: Dict[str, Any], filename: str):
        """Save scenario to YAML file."""
        with open(filename, 'w') as f:
            yaml.dump(scenario, f, default_flow_style=False, sort_keys=False, indent=2)
        print(f"Scenario saved to {filename}")

    def generate_multiple_scenarios(self, scenarios_config: list):
        """Generate multiple scenarios from a configuration list."""
        for config in scenarios_config:
            scenario = self.generate_scenario(
                config["name"],
                config["total_vmt_trillion"],
                config["adoption_percentages"]
            )
            self.save_scenario(scenario, config["filename"])


def main():
    """Generate various AV adoption scenarios."""
    generator = AVScenarioGenerator()

    print("\nGenerating custom scenario...")
    custom_scenario = generator.generate_scenario(
        scenario_name="2035 Projected Adoption",
        demand_growth_rate=1.14,  # 14% growth from 2025 to 2035
        adoption_percentages={
            0: 82.6,
            1: 5.3,
            2: 9.7,
            3: 0.9,
            4: 1.5,
            5: 0
        }
    )
    generator.save_scenario(custom_scenario, "scenarios/av_2035_projected_adoption.yaml")


    print("\nGenerating custom scenario...")
    custom_scenario = generator.generate_scenario(
        scenario_name="2050 Projected Adoption",
        demand_growth_rate=1.35,  # 35% growth from 2025 to 2050
        adoption_percentages={
            0: 0,
            1: 0.01,
            2: 0.1,
            3: 5.1,
            4: 7.3,
            5: 87.5
        }
    )
    generator.save_scenario(custom_scenario, "scenarios/av_2050_mass_adoption.yaml")


    print("\nAll scenarios generated successfully!")


if __name__ == "__main__":
    main()