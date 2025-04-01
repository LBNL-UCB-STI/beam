import os

import geopandas as gpd
import pandas as pd
from cenpy import products


def collect_census_data(state_fips_code, county_fips_codes, year, census_data_file, geo_level='county'):
    """
    Collect census data at specified geographic level (county, tract, or CBG).

    Parameters
    ----------
    state_fips_code : str
        FIPS code for the state
    county_fips_codes : list or str
        List of county FIPS codes or comma-separated string
    year : int
        Census year
    census_data_file : str
        Path to save the CSV output
    geo_level : str
        Geographic level for data collection: 'county', 'tract', or 'cbg'
        Default is 'county'

    Returns
    -------
    pandas.DataFrame
        DataFrame containing population data for the specified geographic level
    """
    # Validate geo_level parameter
    valid_levels = ['county', 'tract', 'cbg']
    if geo_level.lower() not in valid_levels:
        raise ValueError(f"Invalid geo_level '{geo_level}'. Must be one of: {', '.join(valid_levels)}")

    geo_level = geo_level.lower()

    # Check if the output file already exists
    if os.path.exists(census_data_file):
        print(f"Loading existing {geo_level} data from {census_data_file}")
        return pd.read_csv(census_data_file, dtype={'GEOID': str})

    # Get Census API key from file
    api_key_path = os.path.expanduser("~/.census_api_key")
    try:
        with open(api_key_path, 'r') as f:
            census_api_key = f.read().strip()
            print(f"Your Census API key is [{census_api_key}]")
    except FileNotFoundError:
        raise FileNotFoundError(
            f"Census API key file not found at {api_key_path}. Please create this file with your API key.")

    if not census_api_key:
        raise ValueError("Census API key is empty. Please check your API key file.")

    print(f"Collecting {geo_level.upper()} data for year {year}...")

    # Initialize the Census API
    from census import Census
    c = Census(census_api_key, year=year)

    # Convert list of county FIPS to comma-separated string if it's a list
    if isinstance(county_fips_codes, list):
        county_fips_string = ','.join(county_fips_codes)
    else:
        county_fips_string = county_fips_codes

    print(f"Downloading population data for {geo_level}s...")

    try:
        # Different API calls based on geographic level
        if geo_level == 'county':
            census_data = c.acs5.state_county(
                fields=('NAME', 'B01003_001E'),  # B01003_001E is total population
                state_fips=state_fips_code,
                county_fips=county_fips_string
            )
        elif geo_level == 'tract':
            census_data = c.acs5.state_county_tract(
                fields=('NAME', 'B01003_001E'),
                state_fips=state_fips_code,
                county_fips=county_fips_string,
                tract='*'  # Request all tracts
            )
        elif geo_level == 'cbg':
            census_data = c.acs5.state_county_blockgroup(
                fields=('NAME', 'B01003_001E'),
                state_fips=state_fips_code,
                county_fips=county_fips_string,
                blockgroup='*'  # Request all block groups
            )
        else:
            raise ValueError(f"Invalid geo_level '{geo_level}'. Must be one of: {', '.join(valid_levels)}")

        # Create a DataFrame from the census data
        df = pd.DataFrame(census_data)

        # Rename columns for clarity
        df = df.rename(columns={'B01003_001E': 'population', 'NAME': 'name'})

        # Create GEOID based on geographic level
        if geo_level == 'county':
            df['GEOID'] = df['state'] + df['county']
        elif geo_level == 'tract':
            df['GEOID'] = df['state'] + df['county'] + df['tract']
        elif geo_level == 'cbg':
            df['GEOID'] = df['state'] + df['county'] + df['tract'] + df['block group']

        # Convert population to numeric
        df['population'] = pd.to_numeric(df['population'], errors='coerce')

        # Save the raw census data
        if census_data_file:
            df.to_csv(census_data_file, index=False)
            print(f"{geo_level.capitalize()} population data saved to {census_data_file}")

        return df

    except Exception as e:
        print(f"Error downloading Census data: {e}")
        raise


def download_tract_census_data(state_fips_code, county_fips_codes, year, census_data_file):
    """
    Download census tract population data from the Census Bureau's ACS 5-year estimates.

    Parameters
    ----------
    state_fips_code : str
        FIPS code for the state
    county_fips_codes : list
        List of county FIPS codes
    year : int
        Reference year for population estimates (July 1st reference date)
    census_data_file: str
        Path to the CSV file where population data will be saved

    Returns
    -------
    pandas.DataFrame
        DataFrame containing population data for census tracts
    """
    if not os.path.exists(census_data_file):
        # Connect to Census API
        try:
            conn = products.APIConnection(f"ACSDT5Y{year}")
            # Get population data for tracts
            pop_data = None
            for county_fips in county_fips_codes:
                tract_data = conn.query(
                    ['B01003_001E'],  # Total population estimate
                    geo_unit='tract',
                    geo_filter={
                        "state": state_fips_code,
                        "county": county_fips
                    }
                )
                pop_data = pd.concat([pop_data, tract_data]) if pop_data is not None else tract_data

            # Rename columns
            pop_data = pop_data.rename(columns={'B01003_001E': 'population'})

            # Create GEOID by combining state, county, and tract
            pop_data['GEOID'] = (pop_data['state'] + pop_data['county'] + pop_data['tract']).astype(str)

            # Convert population to numeric
            pop_data['population'] = pd.to_numeric(pop_data['population'], errors='coerce')
            pop_data.to_csv(census_data_file, index=False)

        except Exception as e:
            print(f"Failed to retrieve population data: {e}")
            raise
    else:
        pop_data = pd.read_csv(census_data_file, dtype={'GEOID': str})

    return pop_data


def collect_tract_boundaries(state_fips_code, county_fips_codes, year):
    """
    Download census tract boundaries from TIGER/Line shapefiles.

    Parameters
    ----------
    state_fips_code : str
        FIPS code for the state
    county_fips_codes : list
        List of county FIPS codes
    year : int
        Reference year for boundaries

    Returns
    -------
    geopandas.GeoDataFrame
        GeoDataFrame containing tract boundaries
    """
    try:
        # Download geographic boundaries
        geo_url = f"https://www2.census.gov/geo/tiger/TIGER{year}/TRACT/tl_{year}_{state_fips_code}_tract.zip"
        geo_data = gpd.read_file(geo_url)

        # Filter for counties of interest
        geo_data = geo_data[geo_data['COUNTYFP'].isin(county_fips_codes)]
    except Exception as e:
        print(f"Failed to retrieve geographic boundaries: {e}")
        raise
    return geo_data

def collect_geographic_boundaries(state_fips_code, county_fips_codes, year, area_name, geo_level, work_dir):
    study_area_boundary_geo_path = f"{work_dir}/{area_name}_{geo_level}_{year}_wgs84.geojson"
    if os.path.exists(study_area_boundary_geo_path):
        return gpd.read_file(study_area_boundary_geo_path)
    else:
        from pygris import counties, block_groups

        if geo_level == 'county':
            # Define fips code for selected counties
            geo_data = counties(state=state_fips_code, year=year, cb=True, cache=True)
        elif geo_level == 'cbg':
            # Define fips code for selected counties
            geo_data = block_groups(state=state_fips_code, year=year, cb=True, cache=True)
        elif geo_level == 'taz':
            geo_data = collect_taz_boundaries(state_fips_code, year, os.path.dirname(study_area_boundary_geo_path))
        elif geo_level == "tract":
            geo_data = collect_tract_boundaries(state_fips_code, county_fips_codes, year)
        else:
            raise ValueError("Unsupported geographic level. Choose 'counties' or 'cbgs'.")

        countyfp_columns = [col for col in geo_data.columns if col.startswith('COUNTYFP')]
        mask = geo_data[countyfp_columns].apply(lambda x: x.isin(county_fips_codes)).any(axis=1)
        selected_geo = geo_data[mask]

        # def string_to_double(s):
        #     return float(s if s != "" else "0")
        #
        # # Prepare columns and mask
        # aland_columns = [col for col in selected_geo.columns if col.startswith('ALAND')]
        # awater_columns = [col for col in selected_geo.columns if col.startswith('AWATER')]
        # for col in aland_columns + awater_columns:
        #     selected_geo.loc[:, col] = selected_geo[col].apply(string_to_double)
        # mask = pd.Series([False] * len(selected_geo), index=selected_geo.index)
        #
        # for aland_col, awater_col in zip(aland_columns, awater_columns):
        #     # AWATER should not be more than three times ALAND
        #     mask |= (selected_geo[aland_col] > 0) & (selected_geo[awater_col] < 3 * selected_geo[aland_col])
        #
        # # Apply the mask to filter selected_geo
        # selected_geo = selected_geo[mask]
        # study_area_geo_projected_path = base_name + "_epsg" + str(projected_coordinate_system) + extension
        # selected_geo.to_crs(epsg=projected_coordinate_system).to_file(study_area_geo_projected_path, driver="GeoJSON")

        selected_geo_wgs84 = selected_geo.to_crs(epsg=4326)
        selected_geo_wgs84.to_file(study_area_boundary_geo_path, driver="GeoJSON")
        return selected_geo_wgs84

def collect_taz_boundaries(state_fips_code, year, output_dir):
    from zipfile import ZipFile
    state_geo_zip = output_dir + f"/tl_{year}_{state_fips_code}_taz10.zip"
    if not os.path.exists(state_geo_zip):
        state_geo_zip = download_taz_shapefile(state_fips_code, year, output_dir)
    """
    Read a shapefile from a ZIP archive, filter geometries by county FIPS codes,
    and write the result to a GeoJSON file.

    Parameters:
    - zip_file_path: Path to the ZIP file containing the shapefile.
    - county_fips_codes: List of county FIPS codes to filter by.
    - output_geojson_path: Path to save the filtered data as a GeoJSON file.
    """
    # Extract the shapefile from the ZIP archive
    with ZipFile(state_geo_zip, 'r') as zip_ref:
        # Extract all files to a temporary directory
        temp_dir = "temp_shp"
        zip_ref.extractall(temp_dir)

        # Find the .shp file in the extracted files
        shapefile_name = [f for f in os.listdir(temp_dir) if f.endswith('.shp')][0]
        shapefile_path = os.path.join(temp_dir, shapefile_name)

        # Read the shapefile into a GeoDataFrame
        gdf = gpd.read_file(shapefile_path)

        # Clean up the temporary directory
        for filename in os.listdir(temp_dir):
            os.remove(os.path.join(temp_dir, filename))
        os.rmdir(temp_dir)

        return gdf

def download_taz_shapefile(state_fips_code, year, output_dir):
    import requests
    """
    Download TAZ shapefiles for a given state-level FIPS code.

    Parameters:
    - fips_code: String or integer representing the state-level FIPS code.
    - output_dir: Directory to save the downloaded ZIP file.
    """
    # Ensure the FIPS code is a string, padded to 2 characters
    fips_code_str = str(state_fips_code).zfill(2)

    # Construct the download URL
    base_url = f"https://www2.census.gov/geo/tiger/TIGER2010/TAZ/2010/"
    filename = f"tl_{year}_{fips_code_str}_taz10.zip"
    download_url = base_url + filename

    # Make the output directory if it doesn't exist
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    # Full path for saving the file
    output_path = os.path.join(output_dir, filename)

    # Start the download
    print(f"Downloading TAZ shapefile for FIPS code {state_fips_code} from {download_url}")
    try:
        response = requests.get(download_url)
        response.raise_for_status()  # This will check for errors

        # Write the content of the response to a ZIP file
        with open(output_path, 'wb') as file:
            file.write(response.content)

        print(f"File saved to {output_path}")

    except requests.RequestException as e:
        print(f"Error downloading the file: {e}")

    return output_path

def filter_boundaries_by_density(geo_data, pop_data, utm_epsg, geo_level, min_density_per_km2, density_geo_file):
    """
    Collect census boundaries, calculate population density, and filter by density threshold.

    Parameters
    ----------
    geo_data: GeoDataFrame
        Boundaries
    pop_data: Dataframe
        census
    utm_epsg: int
        EPSG code for the projected coordinate system
    geo_level: str
        Geographic level ('tract', 'cbg', etc.)
    min_density_per_km2: float, optional
        Minimum population density threshold (people per km²)
    density_geo_file: str
        Path to the CSV file containing density based geometry

    Returns
    -------
    geopandas.GeoDataFrame
        Filtered geographic boundaries based on density threshold
    """
    # Check if filtered boundaries already exist
    if os.path.exists(density_geo_file):
        print(f"Loading existing {geo_level} boundaries...")
        filtered_geo = gpd.read_file(density_geo_file)
        print(f"✓ Loaded {len(filtered_geo)} {geo_level}s from existing file")
        return filtered_geo

    # If not, we need to collect and process the data
    print(f"Processing {geo_level} boundaries for density analysis...")

    # Ensure GEOID column has consistent type for merging
    geo_data['GEOID'] = geo_data['GEOID'].astype(str)

    # Merge boundaries with population data
    geo_with_pop = geo_data.merge(pop_data, on='GEOID')

    # Calculate area and density
    geo_with_pop['area_sqkm'] = (
            geo_with_pop.to_crs(epsg=utm_epsg)
            .geometry.area / 1000000  # Convert m² to km²
    )
    geo_with_pop['density_per_km2'] = geo_with_pop['population'] / geo_with_pop['area_sqkm']

    # Calculate percentile ranks
    geo_with_pop['density_percentile'] = (
            geo_with_pop['density_per_km2'].rank(pct=True) * 100
    ).round(1)

    # Print density analysis summary
    print("\nPopulation Density Analysis:")
    print("==========================")

    # Density summary
    print(f"\n{geo_level.capitalize()} Density Summary (people/km²):")
    print("--------------------------------")
    stats = geo_with_pop['density_per_km2'].describe()
    print(f"Mean density:     {stats['mean']:,.1f}")
    print(f"Median density:   {stats['50%']:,.1f}")
    print(f"Standard deviation:  {stats['std']:,.1f}")
    print(f"Minimum density:  {stats['min']:,.1f}")
    print(f"Maximum density:  {stats['max']:,.1f}")

    # Density distribution
    print("\nDensity Distribution Quartiles:")
    print("----------------------------")
    for q in [0.25, 0.5, 0.75]:
        print(f"{int(q * 100)}th percentile: {geo_with_pop['density_per_km2'].quantile(q):,.1f}")

    # Filter by minimum density if specified
    if min_density_per_km2 > 0:
        print(f"\nFiltering {geo_level}s by minimum density: {min_density_per_km2:,.1f} people/km²")

        # Apply density filter
        filtered_geo = geo_with_pop[geo_with_pop["density_per_km2"] >= min_density_per_km2]

        # Print selection results
        print(f"\nSelection Results:")
        print("----------------")
        print(f"Selected {len(filtered_geo)} out of {len(geo_with_pop)} {geo_level}s")
        print(f"Total population in selected {geo_level}s: {filtered_geo['population'].sum():,}")

        # Calculate percentage of total population
        total_population = geo_with_pop['population'].sum()
        if total_population > 0:
            population_percentage = (filtered_geo['population'].sum() / total_population * 100)
            print(f"Percentage of total population: {population_percentage:.1f}%\n")
        else:
            print(f"Warning: Total population is zero, cannot calculate percentage\n")
    else:
        # If no density filter, use all areas
        filtered_geo = geo_with_pop
        print(f"\nUsing all {len(filtered_geo)} {geo_level}s (no density filter applied)")

    # Ensure output is in WGS84 for consistency
    filtered_geo = filtered_geo.to_crs(epsg=4326)

    # Save to file for future use
    filtered_geo.to_file(density_geo_file, driver="GeoJSON")
    print(f"✓ Saved filtered {geo_level} boundaries to {density_geo_file}")

    return filtered_geo