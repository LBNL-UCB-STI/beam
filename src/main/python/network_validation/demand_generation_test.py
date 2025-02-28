import cenpy
import pandas as pd
import matplotlib.pyplot as plt
import os
from cenpy.products import ACS


# Set up your Census API key (you'll need to register for one at https://api.census.gov/data/key_signup.html)
# Comment out the line below and replace with your actual API key
# os.environ['CENSUS_API_KEY'] = 'YOUR_API_KEY_HERE'

# If you already have an API key in your environment, you can skip this step
# If using a Jupyter notebook, you can use:
# import getpass
# os.environ['CENSUS_API_KEY'] = getpass.getpass("Enter your Census API key: ")

def get_sf_census_data():
    """
    Download general census data for San Francisco County
    """
    print("Downloading general census data for San Francisco...")

    # Connect to the 2019 5-year ACS data
    acs = ACS(2019, 5)

    # Get variables related to population, housing, income
    variables = [
        'B01001_001E',  # Total population
        'B01002_001E',  # Median age
        'B19013_001E',  # Median household income
        'B25077_001E',  # Median house value
        'B25064_001E',  # Median gross rent
        'B25003_001E',  # Total housing units
        'B25002_003E',  # Vacant housing units
    ]

    # Get data for San Francisco County (FIPS code 06075)
    # San Francisco County is the same as San Francisco City
    sf_data = acs.from_county(variables=variables, county_fips='06075')

    # Rename columns for clarity
    sf_data = sf_data.rename(columns={
        'B01001_001E': 'total_population',
        'B01002_001E': 'median_age',
        'B19013_001E': 'median_household_income',
        'B25077_001E': 'median_house_value',
        'B25064_001E': 'median_gross_rent',
        'B25003_001E': 'total_housing_units',
        'B25002_003E': 'vacant_housing_units'
    })

    return sf_data


def get_sf_travel_data():
    """
    Download travel and commuting data for San Francisco
    """
    print("Downloading travel and commuting data for San Francisco...")

    # Connect to the 2019 5-year ACS data
    acs = ACS(2019, 5)

    # Get variables related to commuting and transportation
    # B08301 - MEANS OF TRANSPORTATION TO WORK
    variables = [
        'B08301_001E',  # Total commuters
        'B08301_002E',  # Car, truck, or van - drove alone
        'B08301_003E',  # Car, truck, or van - carpooled
        'B08301_004E',  # Car, truck, or van - carpooled - in 2-person carpool
        'B08301_010E',  # Public transportation (excluding taxicab)
        'B08301_011E',  # Public transportation - bus
        'B08301_013E',  # Public transportation - subway or elevated rail
        'B08301_018E',  # Bicycle
        'B08301_019E',  # Walked
        'B08301_021E',  # Worked from home
        'B08303_001E',  # Total commuters (travel time)
        'B08303_013E',  # 30-34 min commute time
        'B08012_001E',  # Aggregate travel time to work (minutes)
    ]

    # Get data for San Francisco County
    sf_travel = acs.from_county(variables=variables, county_fips='06075')

    # Rename columns for clarity
    sf_travel = sf_travel.rename(columns={
        'B08301_001E': 'total_commuters',
        'B08301_002E': 'drive_alone',
        'B08301_003E': 'carpooled',
        'B08301_004E': 'carpooled_2person',
        'B08301_010E': 'public_transit',
        'B08301_011E': 'bus',
        'B08301_013E': 'subway_rail',
        'B08301_018E': 'bicycle',
        'B08301_019E': 'walked',
        'B08301_021E': 'worked_from_home',
        'B08303_001E': 'total_commuters_traveltime',
        'B08303_013E': 'commute_30_34_min',
        'B08012_001E': 'aggregate_travel_time_minutes'
    })

    return sf_travel


def get_sf_travel_by_tract():
    """
    Download census tract level commuting data for San Francisco
    """
    print("Downloading tract-level travel data for San Francisco...")

    # Connect to ACS
    acs = ACS(2019, 5)

    # Travel variables by census tract
    variables = [
        'B08301_001E',  # Total commuters
        'B08301_002E',  # Car, truck, or van - drove alone
        'B08301_010E',  # Public transportation
        'B08301_018E',  # Bicycle
        'B08301_019E',  # Walked
        'B08301_021E',  # Worked from home
    ]

    # Get data for all census tracts in San Francisco County
    sf_tracts = acs.from_county(variables=variables, county_fips='06075', level='tract')

    # Rename columns for clarity
    sf_tracts = sf_tracts.rename(columns={
        'B08301_001E': 'total_commuters',
        'B08301_002E': 'drive_alone',
        'B08301_010E': 'public_transit',
        'B08301_018E': 'bicycle',
        'B08301_019E': 'walked',
        'B08301_021E': 'worked_from_home'
    })

    # Calculate percentages
    for col in ['drive_alone', 'public_transit', 'bicycle', 'walked', 'worked_from_home']:
        sf_tracts[f'{col}_pct'] = (sf_tracts[col] / sf_tracts['total_commuters']) * 100

    return sf_tracts


def visualize_commute_modes(sf_travel):
    """
    Create a pie chart of commute modes
    """
    # Extract commute mode data
    commute_data = {
        'Drive Alone': sf_travel['drive_alone'].iloc[0],
        'Carpool': sf_travel['carpooled'].iloc[0] - sf_travel['carpooled_2person'].iloc[0],
        'Public Transit': sf_travel['public_transit'].iloc[0],
        'Bicycle': sf_travel['bicycle'].iloc[0],
        'Walk': sf_travel['walked'].iloc[0],
        'Work from Home': sf_travel['worked_from_home'].iloc[0],
        'Other': (sf_travel['total_commuters'].iloc[0] -
                  sf_travel['drive_alone'].iloc[0] -
                  sf_travel['carpooled'].iloc[0] -
                  sf_travel['public_transit'].iloc[0] -
                  sf_travel['bicycle'].iloc[0] -
                  sf_travel['walked'].iloc[0] -
                  sf_travel['worked_from_home'].iloc[0])
    }

    # Create pie chart
    plt.figure(figsize=(10, 8))
    plt.pie(commute_data.values(), labels=commute_data.keys(), autopct='%1.1f%%')
    plt.title('Commute Modes in San Francisco (2019 ACS)')
    plt.axis('equal')
    plt.tight_layout()
    plt.savefig('sf_commute_modes.png')
    print("Visualization saved as 'sf_commute_modes.png'")

    return commute_data


def main():
    print("Starting San Francisco Census and Travel Data Collection")

    try:
        # Get general census data
        sf_census = get_sf_census_data()
        print("General census data downloaded successfully")

        # Get travel/commuting data
        sf_travel = get_sf_travel_data()
        print("Travel data downloaded successfully")

        # Get tract-level data
        sf_tracts = get_sf_travel_by_tract()
        print("Tract-level data downloaded successfully")

        # Save data to CSV files
        sf_census.to_csv('sf_census_data.csv', index=False)
        sf_travel.to_csv('sf_travel_data.csv', index=False)
        sf_tracts.to_csv('sf_tract_travel_data.csv', index=False)
        print("Data saved to CSV files")

        # Create visualization
        commute_modes = visualize_commute_modes(sf_travel)

        # Print summary statistics
        print("\nSan Francisco Summary Statistics:")
        print(f"Total Population: {sf_census['total_population'].iloc[0]:,}")
        print(f"Median Household Income: ${sf_census['median_household_income'].iloc[0]:,}")
        print(f"Total Commuters: {sf_travel['total_commuters'].iloc[0]:,}")

        # Calculate average commute time
        avg_commute = sf_travel['aggregate_travel_time_minutes'].iloc[0] / sf_travel['total_commuters_traveltime'].iloc[
            0]
        print(f"Average Commute Time: {avg_commute:.1f} minutes")

        print("\nCommute Mode Percentages:")
        total = sf_travel['total_commuters'].iloc[0]
        print(f"Drive Alone: {sf_travel['drive_alone'].iloc[0] / total * 100:.1f}%")
        print(f"Public Transit: {sf_travel['public_transit'].iloc[0] / total * 100:.1f}%")
        print(f"Bicycle: {sf_travel['bicycle'].iloc[0] / total * 100:.1f}%")
        print(f"Walk: {sf_travel['walked'].iloc[0] / total * 100:.1f}%")
        print(f"Work from Home: {sf_travel['worked_from_home'].iloc[0] / total * 100:.1f}%")

    except Exception as e:
        print(f"An error occurred: {e}")
        if "Invalid API key" in str(e):
            print("Please make sure to set your Census API key correctly")
        elif "API key required" in str(e):
            print("You need a Census API key to use cenpy. Get one at https://api.census.gov/data/key_signup.html")


if __name__ == "__main__":
    main()