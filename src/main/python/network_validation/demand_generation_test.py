from validation_utils import download_census_data
from validation_utils import download_tract_boundaries
from validation_utils import collect_tract_boundaries_ppsk
import os
import requests
import pandas as pd
import zipfile
import time
import json

# San Francisco County information
state_fips_code = "06"  # California
county_fips_codes = ["075"]  # San Francisco County FIPS code
year = 2018  # Or whatever year you need
projected_coordinate_system = "26910"  #

# File paths for saving data
data_dir = "sf_data"
os.makedirs(data_dir, exist_ok=True)
census_data_file = os.path.join(data_dir, "sf_census_data.csv")
tract_boundaries_geo_file = os.path.join(data_dir, "sf_tract_boundaries.geojson")
nhts_data = "nhts_data"
os.makedirs(nhts_data, exist_ok=True)
nhts_file = os.path.join(nhts_data, f"nhts_data_{year}.zip")


def download_nhts_data(nhts_output_file, area_name, state_fips_code=None, county_fips_codes=None, year=2017,
                       download=True, extract=True, process=True):
    """
    Download, extract, and process NHTS data with filtering by state FIPS code and county FIPS codes.
    Stores filtered data under directory with area name: data_nhts_dir/area_name/

    Parameters:
    - nhts_output_file: Path to save the downloaded NHTS zip file
    - area_name: Name of the area for organizing filtered data
    - state_fips_code: String representing the state FIPS code (e.g., '06' for California)
    - county_fips_codes: List of county FIPS codes without state prefix (e.g., ['037', '075'] for LA and SF counties)
    - year: NHTS survey year (default: 2017)
    - download: Boolean to control if download should occur
    - extract: Boolean to control if extraction should occur
    - process: Boolean to control if processing should occur

    Returns:
    - Dictionary of filtered DataFrames
    """
    # Set URL based on year
    if year >= 2016:
        url = "https://nhts.ornl.gov/assets/2016/download/csv.zip"
    else:
        print(f"Error: NHTS data for year {year} is not supported.")
        return None

    data_nhts_dir = os.path.dirname(nhts_output_file)

    # Create area-specific directory
    area_dir = os.path.join(data_nhts_dir, area_name)
    os.makedirs(area_dir, exist_ok=True)
    print(f"Created directory for area: {area_dir}")

    # Format full FIPS codes (state + county)
    full_fips_codes = []
    if state_fips_code and county_fips_codes:
        full_fips_codes = [f"{state_fips_code}{county}" for county in county_fips_codes]

    # Create a filter description for file naming
    filter_desc = f"fips_{state_fips_code}"
    if county_fips_codes:
        filter_desc += f"_counties_{'_'.join(county_fips_codes)}"

    # Save filter information to a JSON file for reference
    filter_info = {
        "area_name": area_name,
        "state_fips_code": state_fips_code,
        "county_fips_codes": county_fips_codes,
        "full_fips_codes": full_fips_codes,
        "year": year,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S")
    }

    with open(os.path.join(area_dir, "filter_info.json"), "w") as f:
        json.dump(filter_info, f, indent=2)

    # Check if the file already exists
    if os.path.exists(nhts_output_file):
        file_size = os.path.getsize(nhts_output_file) / (1024 * 1024)  # Size in MB
        print(f"File {nhts_output_file} already exists ({file_size:.1f} MB)")
        if not download:
            print("Skipping download.")
        else:
            download = input("Do you want to download it again? (y/n): ").lower() == 'y'

    if download:
        print(f"Downloading NHTS {year} data...")
        # Download the file with progress reporting
        response = requests.get(url, stream=True)
        if response.status_code == 200:
            total_size = int(response.headers.get('content-length', 0))
            downloaded = 0
            start_time = time.time()

            with open(nhts_output_file, "wb") as file:
                for chunk in response.iter_content(chunk_size=1024 * 1024):  # 1MB chunks
                    if chunk:
                        file.write(chunk)
                        downloaded += len(chunk)

                        # Calculate and display progress
                        percent = int(100 * downloaded / total_size) if total_size > 0 else 0
                        elapsed = time.time() - start_time
                        rate = downloaded / (1024 * 1024 * elapsed) if elapsed > 0 else 0

                        print(
                            f"\rDownloading: {percent}% ({downloaded / (1024 * 1024):.1f}MB of {total_size / (1024 * 1024):.1f}MB) at {rate:.1f} MB/s",
                            end="")

            print(f"\nDownloaded {nhts_output_file}")
        else:
            print(f"Failed to download. Status code: {response.status_code}")
            print(f"Response: {response.text[:500]}...")
            return None

    # Create a temporary directory for extraction
    temp_extract_dir = os.path.join(data_nhts_dir, "temp_extract")
    os.makedirs(temp_extract_dir, exist_ok=True)

    # Check if data has already been extracted to temp directory
    extracted_files_exist = os.path.exists(f"{temp_extract_dir}/hhpub.csv") or os.path.exists(
        f"{temp_extract_dir}/trippub.csv")

    if not extracted_files_exist and extract:
        # Extract the downloaded ZIP file to temp directory
        print("\nExtracting files to temporary directory...")
        try:
            with zipfile.ZipFile(nhts_output_file, "r") as zip_ref:
                zip_ref.extractall(temp_extract_dir)
            print("Files extracted successfully")
        except zipfile.BadZipFile:
            print("Error: The downloaded file is not a valid ZIP file.")
            print("The file may be corrupted. Please try downloading again.")
            return None
        except Exception as e:
            print(f"Error extracting files: {str(e)}")
            return None
    elif extract:
        extract_again = input("Data files already exist in temp directory. Extract again? (y/n): ").lower() == 'y'
        if extract_again:
            print("\nExtracting files to temporary directory...")
            try:
                with zipfile.ZipFile(nhts_output_file, "r") as zip_ref:
                    zip_ref.extractall(temp_extract_dir)
                print("Files extracted successfully")
            except Exception as e:
                print(f"Error extracting files: {str(e)}")
                return None
        else:
            print("Skipping extraction.")
    else:
        print("Skipping extraction.")

    # List the extracted files
    files = os.listdir(temp_extract_dir)
    print(f"\nFiles in temporary extraction directory: {len(files)} files")

    # Process key datasets with focus on filtered areas
    datasets = {
        "Households": "hhpub.csv",
        "Persons": "perpub.csv",
        "Trips": "trippub.csv",
        "Vehicles": "vehpub.csv"
    }

    filtered_dfs = {}

    if not process:
        print("Skipping data processing as requested.")
        return None

    for dataset_name, filename in datasets.items():
        # Define output path in the area-specific directory
        area_output_file = os.path.join(area_dir, filename)

        # Check if filtered file already exists in area directory
        if os.path.exists(area_output_file):
            process_this = input(
                f"Filtered {dataset_name} data already exists in {area_name} directory. Process again? (y/n): ").lower() == 'y'
            if not process_this:
                filtered_dfs[dataset_name] = pd.read_csv(area_output_file)
                print(f"Loaded existing filtered {dataset_name} data from {area_name} directory.")
                continue

        if filename in files:
            print(f"\nProcessing {dataset_name} dataset...")
            file_path = os.path.join(temp_extract_dir, filename)

            # Load the CSV file
            df = pd.read_csv(file_path)
            print(f"Total records: {len(df)}")

            # Apply filters
            filtered_df = df.copy()

            # Find any column containing the word "FIPS"
            fips_column = None
            fips_columns = [col for col in df.columns if 'FIPS' in col]

            if fips_columns:
                fips_column = fips_columns[0]  # Use the first column containing "FIPS"
                print(f"Found FIPS column: {fips_column}")
            else:
                # Fallback to other common county identifiers if no FIPS column found
                for col in ['HHCOUNTY', 'COUNTY']:
                    if col in df.columns:
                        fips_column = col
                        print(f"No FIPS column found, using {fips_column} instead")
                        break

            # Filter by FIPS code
            if fips_column and full_fips_codes:
                # Ensure FIPS codes are strings with leading zeros preserved
                filtered_df[fips_column] = filtered_df[fips_column].astype(str).str.zfill(5)
                filtered_df = filtered_df[filtered_df[fips_column].isin(full_fips_codes)]
                print(f"Records after FIPS filter: {len(filtered_df)}")
            elif state_fips_code:
                # If only state FIPS is provided, filter by first two digits of FIPS code
                if fips_column:
                    filtered_df[fips_column] = filtered_df[fips_column].astype(str).str.zfill(5)
                    filtered_df = filtered_df[filtered_df[fips_column].str[:2] == state_fips_code]
                    print(f"Records after state FIPS filter: {len(filtered_df)}")
                else:
                    print("Warning: No FIPS or county code column found for filtering")
                    print(f"Available columns: {', '.join(df.columns[:10])}...")

            # Save filtered data to area-specific directory
            filtered_df.to_csv(area_output_file, index=False)
            print(f"Filtered data saved to {area_output_file}")

            # Store in dictionary
            filtered_dfs[dataset_name] = filtered_df

            # Display sample data
            print("\nSample data (first 3 rows):")
            print(filtered_df.head(3))

            # Display column information
            print(f"\nNumber of columns: {len(filtered_df.columns)}")
            print(f"Sample columns: {filtered_df.columns[:5].tolist()}")
        else:
            print(f"\nWarning: {filename} not found in extracted files")

    # Optionally clean up temporary extraction directory
    cleanup = input("Clean up temporary extraction directory? (y/n): ").lower() == 'y'
    if cleanup:
        import shutil
        shutil.rmtree(temp_extract_dir)
        print(f"Removed temporary directory: {temp_extract_dir}")

    return filtered_dfs


# Example usage:
if __name__ == "__main__":
    # # Download census data for San Francisco
    # sf_pop_data = download_census_data(
    #     state_fips_code,
    #     county_fips_codes,
    #     year,
    #     census_data_file
    # )
    # print(f"Downloaded census data for {len(sf_pop_data)} tracts in San Francisco County")
    #
    # # Download tract boundaries for San Francisco
    # sf_geo_data = download_tract_boundaries(
    #     state_fips_code,
    #     county_fips_codes,
    #     year,
    #     tract_boundaries_geo_file
    # )
    # print(f"Downloaded boundary data for {len(sf_geo_data)} tracts in San Francisco County")
    #
    # # If you want to process the data as well, you can use the full function:
    # sf_tracts_with_pop = collect_tract_boundaries_ppsk(
    #     state_fips_code,
    #     county_fips_codes,
    #     year,
    #     projected_coordinate_system,
    #     census_data_file,
    #     tract_boundaries_geo_file
    # )
    #
    # # You can now work with the data
    # print("\nSample of census data:")
    # print(sf_pop_data.head())
    #
    # print("\nSample of boundary data:")
    # print(sf_geo_data.head())
    #
    # # If you processed the data, you can also examine the combined dataset
    # if 'sf_tracts_with_pop' in locals():
    #     print("\nSample of processed data with population density:")
    #     print(sf_tracts_with_pop[['GEOID', 'population', 'area_sqkm', 'density_per_km2']].head())
    #
    #     # You can also save the processed data to a file if needed
    #     processed_file = os.path.join(data_dir, "sf_processed_tracts.geojson")
    #     sf_tracts_with_pop.to_file(processed_file, driver='GeoJSON')
    #     print(f"\nSaved processed data to {processed_file}")

    # Download 2017 NHTS data for California (FIPS code 06)
    nhts_data = download_nhts_data(
        nhts_output_file=nhts_file,
        area_name="sf",
        state_fips_code='06',  # California
        county_fips_codes=['075'],  # Los Angeles County
        year=year
    )

    # Print summary of downloaded data
    for dataset_name, dataset in nhts_data.items():
        print(f"\n{dataset_name.upper()} Dataset Summary:")
        print(f"Number of records: {len(dataset)}")
        print("Sample columns:", list(dataset.columns)[:5])
        print("Sample data:")
        print(dataset.head(3))