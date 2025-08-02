import yaml
import os
from typing import Dict, Optional


class AVScenarioGenerator:
    def __init__(self):
        # Base defaults that will be used across all scenarios
        self.base_defaults = {
            'vmt_split': {
                'rural_interstate': 0.076,
                'rural_freeway': 0.009,
                'rural_principal_arterial': 0.062,
                'rural_minor_arterial': 0.046,
                'rural_major_collector': 0.052,
                'rural_minor_collector': 0.016,
                'rural_local': 0.041,
                'urban_interstate': 0.173,
                'urban_freeway': 0.075,
                'urban_principal_arterial': 0.155,
                'urban_minor_arterial': 0.129,
                'urban_major_collector': 0.064,
                'urban_minor_collector': 0.004,
                'urban_local': 0.097
            },
            'speeds_mph': {
                'rural_interstate': 72.6,
                'rural_freeway': 72.31,
                'rural_principal_arterial': 55.3,
                'rural_minor_arterial': 53.58,
                'rural_major_collector': 43.62,
                'rural_minor_collector': 43.04,
                'rural_local': 29.21,
                'urban_interstate': 57.60,
                'urban_freeway': 57.31,
                'urban_principal_arterial': 25.30,
                'urban_minor_arterial': 23.58,
                'urban_major_collector': 23.62,
                'urban_minor_collector': 23.04,
                'urban_local': 14.21
            },
            'utilization_rate': {
                'rural_interstate': 0.5,
                'rural_freeway': 0.55,
                'rural_principal_arterial': 0.6,
                'rural_minor_arterial': 0.65,
                'rural_major_collector': 0.7,
                'rural_minor_collector': 0.75,
                'rural_local': 0.8,
                'urban_interstate': 0.65,
                'urban_freeway': 0.7,
                'urban_principal_arterial': 0.75,
                'urban_minor_arterial': 0.8,
                'urban_major_collector': 0.85,
                'urban_minor_collector': 0.9,
                'urban_local': 0.95
            }
        }

        # Base vehicle configurations for each level
        self.vehicle_templates = {
            0: {
                'level': 0,
                'compute_tdp_watt': 0,
                'sensors_tdp_watt': 0
            },
            1: {
                'level': 1,
                'compute_tdp_watt': 2.5,
                'sensors_tdp_watt': 3.66
            },
            2: {
                'level': 2,
                'compute_tdp_watt': 10,
                'sensors_tdp_watt': 14.92
            },
            3: {
                'level': 3,
                'compute_tdp_watt': 110,
                'sensors_tdp_watt': 12,
                'sensors_data_Mbit_per_second': 13272.885,
                'training_tdp_watt': 1200000
            },
            4: {
                'level': 4,
                'simulation_vmt_daily': 40413333,
                'compute_tdp_watt': 800,
                'sensors_tdp_watt': 227.97,
                'sensors_data_Mbit_per_second': 33628.424,
                'training_tdp_watt': 2800000,
                'overrides': {
                    'vmt_split': {
                        'rural_interstate': 0.0014,
                        'rural_freeway': 0.0014,
                        'rural_principal_arterial': 0.0014,
                        'rural_minor_arterial': 0.0014,
                        'rural_major_collector': 0.0014,
                        'rural_minor_collector': 0.0014,
                        'rural_local': 0.0014,
                        'urban_interstate': 0.06,
                        'urban_freeway': 0.06,
                        'urban_principal_arterial': 0.174,
                        'urban_minor_arterial': 0.174,
                        'urban_major_collector': 0.174,
                        'urban_minor_collector': 0.174,
                        'urban_local': 0.174
                    }
                },
                'speeds_mph': {
                    'rural_interstate': 70,
                    'rural_freeway': 70,
                    'rural_principal_arterial': 55,
                    'rural_minor_arterial': 45,
                    'rural_major_collector': 43.62,
                    'rural_minor_collector': 43.04,
                    'rural_local': 25,
                    'urban_interstate': 55,
                    'urban_freeway': 55,
                    'urban_principal_arterial': 25.30,
                    'urban_minor_arterial': 23.58,
                    'urban_major_collector': 23.62,
                    'urban_minor_collector': 23.04,
                    'urban_local': 14.21
                }
            },
            5: {
                'level': 5,
                'driving_vmt_daily': 0,
                'simulation_vmt_daily': 0,
                'compute_tdp_watt': 1600,
                'sensors_tdp_watt': 500,
                'sensors_data_Mbit_per_second': 70000,
                'training_tdp_watt': 5600000
            }
        }

    def generate_scenario(self,
                          scenario_name: str,
                          total_vmt_trillion: float,
                          adoption_percentages: Dict[int, float],
                          custom_overrides: Optional[Dict] = None) -> Dict:
        """
        Generate a scenario configuration.

        Parameters:
        scenario_name (str): Name of the scenario
        total_vmt_trillion (float): Total VMT in trillions
        adoption_percentages (dict): Dictionary with level as key and percentage as value
        year (int): Year for the scenario
        custom_overrides (dict): Optional custom overrides for specific levels

        Returns:
        dict: Complete scenario configuration
        """

        # Validate adoption percentages
        total_percentage = sum(adoption_percentages.values())
        if abs(total_percentage - 100.0) > 0.01:
            raise ValueError(f"Adoption percentages must sum to 100%, got {total_percentage}%")

        # Convert trillion VMT to daily VMT
        total_daily_vmt = total_vmt_trillion * 1e12 / 365

        # Create scenario configuration
        scenario_config = {
            'scenario': scenario_name,
            'defaults': self.base_defaults.copy(),
            'fleet': []
        }

        # Generate fleet configurations
        for level in sorted(adoption_percentages.keys()):
            if adoption_percentages[level] > 0:
                # Calculate daily VMT for this level
                level_daily_vmt = int(total_daily_vmt * adoption_percentages[level] / 100)

                # Get base template
                vehicle_config = self.vehicle_templates[level].copy()
                vehicle_config['driving_vmt_daily'] = level_daily_vmt

                # Apply custom overrides if provided
                if custom_overrides and level in custom_overrides:
                    vehicle_config.update(custom_overrides[level])

                scenario_config['fleet'].append(vehicle_config)

        return scenario_config

    def save_scenario(self, scenario_config: Dict, filename: str, output_dir: str = "scenarios"):
        """
        Save scenario configuration to YAML file.

        Parameters:
        scenario_config (dict): Scenario configuration
        filename (str): Output filename
        output_dir (str): Output directory
        """
        # Create output directory if it doesn't exist
        os.makedirs(output_dir, exist_ok=True)

        # Full file path
        filepath = os.path.join(output_dir, filename)

        # Save to YAML
        with open(filepath, 'w') as file:
            yaml.dump(scenario_config, file, default_flow_style=False, sort_keys=False, indent=2)

        print(f"Scenario saved to: {filepath}")



def main():
    """Example usage of the scenario generator."""
    generator = AVScenarioGenerator()

    # Example: Generate a custom scenario
    print("\nGenerating custom scenario...")
    custom_scenario = generator.generate_scenario(
        scenario_name="2035 Custom High AV Adoption",
        total_vmt_trillion=3.76, #14% growth
        adoption_percentages={
            0: 82.6,
            1: 5.3,
            2: 9.7,
            3: 0.9,
            4: 1.5,
            5: 0
        }
    )

    generator.save_scenario(custom_scenario, "av_2035_projected_adoption.yaml")


if __name__ == "__main__":
    main()