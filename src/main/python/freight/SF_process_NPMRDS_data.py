import geopandas as gpd
import pandas as pd
import os
from shapely.geometry import Point
from geopandas.tools import sjoin
import matplotlib.pyplot as plt

# import dask.dataframe as dd
import pyarrow.csv as pv


def fast_sjoin(df1, df2):
    import geopandas as gpd
    import pandas as pd
    from shapely.ops import nearest_points

    # Generate spatial index for the second DataFrame
    sindex_df2 = df2.sindex

    # Placeholder for results
    results = []

    # Iterate through each LineString in df_lines1
    for index1, row1 in df1.iterrows():
        # Use the spatial index to quickly find approximate nearest neighbors
        possible_matches_index = list(sindex_df2.nearest(row1.geometry.bounds, num_results=1))
        possible_matches = df2.iloc[possible_matches_index]

        # Compute the exact distance to the possible matches and find the closest
        closest_geom = None
        min_dist = float("inf")
        for index2, row2 in possible_matches.iterrows():
            # Calculate the distance
            dist = row1.geometry.distance(row2.geometry)
            if dist < min_dist:
                min_dist = dist
                closest_geom = row2.geometry

        # Store the result
        results.append({
            'geometry_from_df_lines1': row1.geometry,
            'closest_geometry_from_df_lines2': closest_geom,
            'distance': min_dist
        })

    # Convert the results into a DataFrame
    df_results = pd.DataFrame(results)

    # If you need this as a GeoDataFrame
    return gpd.GeoDataFrame(df_results, geometry='geometry_from_df_lines1')


work_dir = os.path.expanduser("~/Workspace/Data/SFBay")

# SELECT AUSTIN STATIONS

# Load California NPMRDS station shapefile
print("Load California NPMRDS station shapefile")
file_link = work_dir + "/NPMRDS_data/California.shp"
npmrds_station = gpd.read_file(file_link)

# Load SF boundaries
print("Load SF boundaries")
sf_boundary_link = work_dir + '/NPMRDS_data/SF_counties.geojson'
sf_boundary = gpd.read_file(sf_boundary_link)
sf_boundary = sf_boundary.to_crs(epsg=4326)

# Select TMC in SF
print("Select TMC in SF")
regional_npmrds_station = gpd.overlay(npmrds_station, sf_boundary, how='intersection')

# Drop geometry and get unique TMC
print("Drop geometry and get unique TMC")
regional_npmrds_station_df = regional_npmrds_station.drop(columns='geometry')
regional_npmrds_tmcs = regional_npmrds_station_df['Tmc'].unique()

# Load NPMRDS observations
print("Load NPMRDS observations")
data_link = work_dir + '/NPMRDS_data/al_ca_oct2018_1hr_trucks_pax.csv'
table = pv.read_csv(data_link)
npmrds_data = table.to_pandas()
# npmrds_data = dd.read_csv(data_link)
# result = npmrds_data.head()
# print(result.compute())

# Select NPMRDS data in SF
print("Select NPMRDS data in SF")
regional_npmrds_data = npmrds_data[npmrds_data['tmc_code'].isin(regional_npmrds_tmcs)]

# Write filtered NPMRDS data to CSV
print("Write filtered NPMRDS data to CSV")
regional_npmrds_data.to_csv(work_dir + '/regional_npmrds_data.csv', index=False)


# Get unique TMC codes with data
print("Get unique TMC codes with data")
regional_npmrds_data_tmcs = regional_npmrds_data['tmc_code'].unique()

# Filter sf_npmrds_station for those TMCs
print("Filter sf_npmrds_station for those TMCs")
regional_npmrds_station_filtered = regional_npmrds_station[regional_npmrds_station['Tmc'].isin(regional_npmrds_data_tmcs)]

# Plotting
print("Plotting")
fig, ax = plt.subplots()
sf_boundary.boundary.plot(ax=ax, color='blue')
regional_npmrds_station_filtered.plot(ax=ax, color='red')

# Save sf_npmrds_station to GeoJSON
print("Save sf_npmrds_station to GeoJSON")
regional_npmrds_station_filtered_geojson_path = work_dir + '/regional_NPMRDS_station.geojson'
regional_npmrds_station_filtered.to_file(regional_npmrds_station_filtered_geojson_path, driver='GeoJSON')

# Read the saved GeoJSON file
# print("Read the saved GeoJSON file")
# regional_npmrds_station_filtered = gpd.read_file(regional_npmrds_station_filtered_geojson_path)

# Load BEAM network
print("Load BEAM network")
# (Continuation depends on the specifics of how you load and handle the BEAM network data)
beam_network_link = work_dir + '/BEAM_output/beam_network_by_county.geojson'
beam_network = gpd.read_file(beam_network_link)

roadway_type = ['motorway_link', 'trunk', 'trunk_link',
                'primary_link', 'motorway', 'primary', 'secondary', 'secondary_link']
link_modes = ['car;bike', 'car;walk;bike']

# Filter BEAM network to only include highway and major roads
print("Filter BEAM network to only include highway and major roads")
beam_network_with_cars = beam_network[beam_network['attributeOrigType'].isin(roadway_type)]
beam_network_with_cars = beam_network_with_cars[beam_network_with_cars['linkModes'].isin(link_modes)]

# ## Find BEAM links close to NPMRDS TMCs (within 20 meters) ##
print("Find BEAM links close to NPMRDS TMCs (within 20 meters)")
# sf_npmrds_station_buffer = regional_npmrds_station_filtered.copy()
# sf_npmrds_station_buffer['geometry'] = sf_npmrds_station_buffer['geometry'].buffer(20)
# beam_network_with_tmc = sjoin(beam_network_with_cars, sf_npmrds_station_buffer, predicate='within')
beam_network_with_tmc = fast_sjoin(beam_network_with_cars, regional_npmrds_station_filtered)

beam_network_with_tmc.to_csv(work_dir + '/beam_network_with_tmc.csv', index=False)


# Format output
print("Format output")
selected_beam_network_out = pd.DataFrame()

for index, row in beam_network_with_tmc.iterrows():
    selected_beam_network = beam_network_with_cars.loc[[index]]
    selected_tmc = regional_npmrds_station_filtered.loc[[row['index_right']]]
    tmc_id = selected_tmc['Tmc'].values[0]
    print(tmc_id)
    selected_beam_network['Tmc'] = tmc_id
    selected_beam_network['dist_to_tmc'] = selected_beam_network.distance(selected_tmc.geometry.values[0])
    selected_beam_network_out = pd.concat([selected_beam_network_out, selected_beam_network])

# Remove duplicated links and keep closest TMC
print("Remove duplicated links and keep closest TMC")
selected_beam_network_filtered = selected_beam_network_out.groupby('linkId').apply(lambda x: x.loc[x['dist_to_tmc'].idxmin()])

fig, ax = plt.subplots()
selected_beam_network_filtered.plot(ax=ax, color='blue')
regional_npmrds_station_filtered.plot(ax=ax, color='red', linewidth=0.5)

# Write to file
print("Write to file")
selected_beam_network_filtered.to_file(work_dir + '/beam_network_npmrds_screenline.geojson', driver='GeoJSON')
selected_beam_network_filtered.drop(columns='geometry').to_csv(work_dir + '/beam_network_npmrds_screenline.csv', index=False)

print("END")