import pandas as pd
import os

def calculate_road_dust_emissions(silt_loading, rainy_days):
    """
    Calculate road dust emissions based on EPA AP-42 methodology.

    Parameters:
    silt_loading (float): Roadway-specific silt loading in grams/square meter
    rainy_days (int): Number of wet days in the year

    Returns:
    tuple: PM2.5, PM10, and total PM emission factors in grams/vehicle-mile
    """
    # Constants
    k = 0.0022  # particle size multiplier for PM10 in lb/VMT
    W = 2.4  # average weight of vehicles in tons
    N = 365  # number of days in annual averaging period

    # Fractions of pollutants among road dust
    pm_25_frac = 0.0686
    pm_10_frac = 0.4572
    pm_frac = 0.5428

    # Calculate PM10 emission factor in lb/VMT
    E_10 = k * (silt_loading ** 0.91) * (W ** 1.02) * (1 - rainy_days / N / 4)

    # Calculate total PM emission factor
    E_total = E_10 / pm_10_frac

    # Calculate PM2.5 emission factor
    E_25 = E_total * pm_25_frac

    # Convert from lb/VMT to g/VMT (1 lb = 453.592 g)
    E_25_g = E_25 * 453.592
    E_10_g = E_10 * 453.592
    E_total_g = E_total * 453.592

    return E_25_g, E_10_g, E_total_g


def process_road_dust(rainy_days_file, silt_loading_file, air_basin_region, output_file=None):
    """
    Process rainy days and silt loading data to create road dust emission rates.

    Parameters:
    rainy_days_file (str): Path to the rainy days CSV file
    silt_loading_file (str): Path to the silt loading CSV file
    air_basin_region (list): List of air basins to filter by
    output_file (str, optional): Path to save the output CSV file

    Returns:
    pd.DataFrame: DataFrame with road dust emission rates
    """
    # Map BEAM/OSM road types to CARB silt loading road categories
    silt_beam2carb_map = {
        'motorway': 'Freeway',
        'motorway_link': 'Freeway',
        'trunk': 'Freeway',
        'trunk_link': 'Major',
        'primary': 'Major',
        'primary_link': 'Major',
        'secondary': 'Collector',
        'secondary_link': 'Collector',
        'tertiary': 'Collector',
        'tertiary_link': 'Collector',
        'unclassified': 'Collector',
        'residential': 'Local Urban'
    }

    # Load silt loading data
    silt_loading_df = pd.read_csv(silt_loading_file)

    # Ensure consistent county names across datasets
    silt_loading_df['County'] = silt_loading_df['County'].str.strip().str.lower()
    silt_loading_df['Air Basin'] = silt_loading_df['Air Basin'].str.strip()
    silt_filtered_df = silt_loading_df[silt_loading_df['Air Basin'].isin(air_basin_region)]
    if silt_filtered_df.empty:
        raise ValueError(f"No data found in silt loading for the specified air basins: {air_basin_region}")
    road_categories = ['Freeway', 'Major', 'Collector', 'Local Urban', 'Local Rural']
    county_averages = silt_filtered_df.groupby('County')[road_categories].mean().reset_index()
    county_averages = county_averages.sort_values('County')

    # Load rainy days data
    rainy_days_df = pd.read_csv(rainy_days_file)
    rainy_days_df['County'] = rainy_days_df['County'].str.strip().str.lower()
    rainy_days_df['Air Basin'] = rainy_days_df['Air Basin'].str.strip()
    rainy_filtered_df = rainy_days_df[rainy_days_df['Air Basin'].isin(air_basin_region)]
    if rainy_filtered_df.empty:
        raise ValueError(f"No data found in rainy days for the specified air basins: {air_basin_region}")
    rainfall_averages = rainy_filtered_df.groupby('County')['Annual Rainfall Days'].mean().reset_index()
    rainfall_averages = rainfall_averages.sort_values('County')

    # Merge county silt loading with rainy days data
    merged_data = pd.merge(county_averages, rainfall_averages, on='County', how='inner')

    # Initialize lists to store emissions data for all BEAM/OSM road types
    all_rows = []

    # Calculate road dust emissions for each county and road type
    for _, row in merged_data.iterrows():
        county = row['County']
        rainy_days = row['Annual Rainfall Days']

        # Create a dictionary to map CARB road categories to their silt loading values for this county
        carb_road_to_silt = {road_type: row[road_type] for road_type in road_categories}

        # Process each BEAM/OSM road type
        for beam_road_type, carb_road_type in silt_beam2carb_map.items():
            silt_loading = carb_road_to_silt[carb_road_type]

            # Calculate emission factors
            pm25, pm10, pm_total = calculate_road_dust_emissions(silt_loading, rainy_days)

            # Create a dictionary for this row
            row_dict = {
                'county': county,
                'process': 'PRDUST',
                'rate_pm2_5_gram_float': pm25,
                'rate_pm10_gram_float': pm10,
                'rate_pm_gram_float': pm_total,
                'road_category': beam_road_type,
                'carb_road_category': carb_road_type,
                'silt_loading': silt_loading,
                'rainy_days': rainy_days
            }

            all_rows.append(row_dict)

    # Create emissions DataFrame
    emissions_df = pd.DataFrame(all_rows)

    # Reorder columns to match required format
    column_order = [
        'county',
        'road_category',
        'process',
        'rate_pm_gram_float',
        'rate_pm10_gram_float',
        'rate_pm2_5_gram_float'
    ]

    # Add additional columns at the end for reference/debugging
    extended_cols = column_order + ['carb_road_category', 'silt_loading', 'rainy_days']
    emissions_df = emissions_df[extended_cols]

    # Save to CSV if output file is specified
    if output_file:
        # Create a version with just the required columns
        emissions_df[column_order].to_csv(output_file, index=False)
        print(f"Road dust emission rates saved to {output_file}")

        # Also save an extended version with additional info
        extended_output = output_file.replace('.csv', '_extended.csv')
        emissions_df.to_csv(extended_output, index=False)
        print(f"Extended road dust emission rates saved to {extended_output}")

    return emissions_df

if __name__ == "__main__":
    # Example usage
    road_dust_dir = os.path.expanduser("~/Workspace/Models/emfac/road_dust/CA_input")
    rainy_days_file = f"{road_dust_dir}/rainy_days.csv"
    silt_loading_file = f"{road_dust_dir}/silt_loading.csv"
    air_basin_region = ["SF"]  # Example air basin

    # Create output file name based on air basin
    basin_str = "_".join([b.replace(" ", "") for b in air_basin_region])
    output_file = f"road_dust_emission_rates_{basin_str}.csv"

    # Process road dust emission rates
    emissions_df = process_road_dust(rainy_days_file, silt_loading_file, air_basin_region, output_file)

    print("\nSample of processed road dust emission rates:")
    print(emissions_df.head())