import pandas as pd
import geopandas as gpd
import os
import os.path
import matplotlib.pyplot as plt
import pyarrow.csv as pv
projected_crs_epsg = 26910


def find_nearest(df1, df2, max_distance):
    # Ensure both GeoDataFrames are in an appropriate planar projection
    df1 = df1.to_crs(epsg=projected_crs_epsg)
    df2 = df2.to_crs(epsg=projected_crs_epsg)

    results = []

    # Prepare a spatial index on the second DataFrame for efficient querying
    sindex_df2 = df2.sindex

    for index1, row1 in df1.iterrows():
        # Find the indices of the geometries in df2 that are within max_distance meters of the current geometry in df1
        possible_matches_index = list(sindex_df2.query(row1.geometry.buffer(max_distance), predicate="intersects"))
        possible_matches = df2.iloc[possible_matches_index]

        # Calculate and filter distances
        distances = possible_matches.distance(row1.geometry)
        close_matches = distances[distances <= max_distance]

        for index2, dist in close_matches.items():
            results.append({
                'df1_index': index1,
                'df2_index': index2,
                'distance': dist,
                # Add additional fields as needed
            })

    # Convert results to a GeoDataFrame
    results_gdf = gpd.GeoDataFrame(pd.DataFrame(results))
    # Optionally join additional attributes from df1 and df2

    # Convert indices in results_gdf to the original indices in df1 and df2
    # results_gdf = results_gdf.set_index('df1_index').join(df1.drop(columns='geometry'), rsuffix='_df1')
    results_gdf = results_gdf.set_index('df1_index').join(df1, rsuffix='_df1')
    results_gdf = results_gdf.reset_index().set_index('df2_index').join(df2.drop(columns='geometry'), rsuffix='_df2')

    # Reset index to make sure we don't lose track of it
    results_gdf = results_gdf.reset_index().rename(columns={'index': 'df1_index'})

    # Assuming 'geometry' was retained from df1 during the initial creation of results_gdf
    results_gdf = gpd.GeoDataFrame(results_gdf, geometry='geometry', crs=df1.crs)
    return results_gdf


def find_nearest_old(df1_crs, df2_crs, max_distance):
    # Ensure spatial indexing is generated
    df1 = df1_crs.to_crs(epsg=projected_crs_epsg)
    df2 = df2_crs.to_crs(epsg=projected_crs_epsg)
    df2_index = df2.sindex

    nearest_geoms = []
    for geometry in df1.geometry:
        # Query the spatial index of df2 to find close geometries
        possible_matches_index = list(df2_index.query(geometry, predicate="intersects"))
        possible_matches = df2.iloc[possible_matches_index]

        # Calculate distances to the possible matches and find the nearest
        distances = possible_matches.distance(geometry)
        filtered_distances = distances[distances < max_distance]
        if not filtered_distances.empty:
            nearest_geom = filtered_distances.idxmin()
            nearest_geoms.append(df2.loc[nearest_geom])

    return gpd.GeoDataFrame(pd.concat(nearest_geoms, axis=1).T.reset_index(drop=True))


def load_or_process_regional_npmrds_station(npmrds_geo_file, region_boundary_geo_file, regional_npmrds_station_output_file):
    if not os.path.isfile(regional_npmrds_station_output_file):
        # Load California NPMRDS station shapefile
        print("Load NPMRDS station geographic data file")
        npmrds_station = gpd.read_file(npmrds_geo_file)
        npmrds_station_proj = npmrds_station.to_crs(epsg=4326)

        # Load SF boundaries
        print("Load regional map boundaries")
        region_boundary = gpd.read_file(region_boundary_geo_file)
        region_boundary_proj = region_boundary.to_crs(epsg=4326)

        # Select TMC within region boundaries
        print("Select TMC within region boundaries")
        regional_npmrds_station_out = gpd.overlay(npmrds_station_proj, region_boundary_proj, how='intersection')
        regional_npmrds_station_out.to_file(regional_npmrds_station_output_file, driver='GeoJSON')
        return regional_npmrds_station_out
    else:
        return gpd.read_file(regional_npmrds_station_output_file)


def load_or_process_regional_npmrds_data(npmrds_data_csv_file, regional_npmrds_station_tmcs, regional_npmrds_data_output_file):
    if not os.path.isfile(regional_npmrds_data_output_file):
        # Load NPMRDS observations
        print(">> raw NPMRDS file")
        table = pv.read_csv(npmrds_data_csv_file)
        npmrds_data = table.to_pandas()

        # Select NPMRDS data in SF
        print(">> Select NPMRDS data within regional boundaries")
        regional_npmrds_data = npmrds_data[npmrds_data['tmc_code'].isin(regional_npmrds_station_tmcs)]

        # Write filtered NPMRDS data to CSV
        print(">> Write filtered NPMRDS data to CSV")
        regional_npmrds_data.to_csv(regional_npmrds_data_output_file, index=False)
        return regional_npmrds_data
    else:
        print(">> Filtered NPMRDS file")
        table = pv.read_csv(regional_npmrds_data_output_file)
        return table.to_pandas()


def load_or_filter_beam_network_roadway_and_car_link_modes(beam_network_geo_file, beam_network_filtered_geo_output_file):
    if not os.path.isfile(beam_network_filtered_geo_output_file):
        # Load BEAM network
        print("Load BEAM network")
        # (Continuation depends on the specifics of how you load and handle the BEAM network data)
        beam_network = gpd.read_file(beam_network_geo_file)

        roadway_type = ['motorway_link', 'trunk', 'trunk_link', 'primary_link', 'motorway', 'primary', 'secondary', 'secondary_link']
        link_modes = ['car', 'car;bike', 'car;walk;bike']

        # Filter BEAM network to only include highway and major roads
        print("Filter BEAM network to only include highway and major roads")
        beam_network_filtered = beam_network[beam_network['attributeOrigType'].isin(roadway_type)]
        beam_network_filtered = beam_network_filtered[beam_network_filtered['linkModes'].isin(link_modes)]
        beam_network_filtered_proj = beam_network_filtered.to_crs(epsg=4326)
        beam_network_filtered_proj.to_file(beam_network_filtered_geo_output_file, driver='GeoJSON')
        return beam_network_filtered_proj
    else:
        return gpd.read_file(beam_network_filtered_geo_output_file)


work_dir = os.path.expanduser("~/Workspace/Data/Scenarios/")

npmrds_geo_file = work_dir + "/SFBay/NPMRDS_data/California.shp"
region_boundary_file = work_dir + '/SFBay/NPMRDS_data/SF_counties.geojson'
regional_npmrds_station_output_file = work_dir + '/SFBay/regional_npmrds_station.geojson'

regional_npmrds_station = load_or_process_regional_npmrds_station(npmrds_geo_file, region_boundary_file, regional_npmrds_station_output_file)

# Drop geometry and get unique TMC
print("Drop geometry and get unique TMC")
regional_npmrds_station_df = regional_npmrds_station.drop(columns='geometry')
regional_npmrds_tmcs = regional_npmrds_station_df['Tmc'].unique()

# Load NPMRDS observations
print("Load NPMRDS observations")
npmrds_data_csv_file = work_dir + '/SFBay/NPMRDS_data/al_ca_oct2018_1hr_trucks_pax.csv'
regional_npmrds_data_output_file = work_dir + '/SFBay/regional_npmrds_data.csv'
regional_npmrds_data = load_or_process_regional_npmrds_data(npmrds_data_csv_file, regional_npmrds_tmcs, regional_npmrds_data_output_file)

# Get unique TMC codes with data
print("Get unique TMC codes with data")
regional_npmrds_data_tmcs = regional_npmrds_data['tmc_code'].unique()

# Filter sf_npmrds_station for those TMCs
print("Filter sf_npmrds_station for those TMCs")
regional_npmrds_station_filtered = regional_npmrds_station[regional_npmrds_station['Tmc'].isin(regional_npmrds_data_tmcs)]
regional_npmrds_station_filtered_geojson_path = work_dir + '/SFBay/regional_npmrds_station_filtered.geojson'
regional_npmrds_station_filtered.to_file(regional_npmrds_station_filtered_geojson_path, driver='GeoJSON')

# Plotting
plot_output_file = work_dir + '/SFBay/regional_npmrds_station_filtered.png'
if not os.path.isfile(plot_output_file):
    print("Plotting")
    # Load regional boundaries
    region_boundary = gpd.read_file(region_boundary_file)
    region_boundary_proj = region_boundary.to_crs(epsg=4326)
    fig, ax = plt.subplots()
    region_boundary_proj.boundary.plot(ax=ax, color='blue')
    regional_npmrds_station_filtered.plot(ax=ax, color='red')
    fig.savefig(plot_output_file, dpi=300)  # Adjust dpi for resolution, if necessary

# Read the saved GeoJSON file
# print("Read the saved GeoJSON file")
# regional_npmrds_station_filtered = gpd.read_file(regional_npmrds_station_filtered_geojson_path)

beam_network_link = work_dir + '/SFBay/BEAM_output/beam_network_by_county.geojson'
beam_network_filtered_geo_output_file = work_dir + '/SFBay/beam_network_filtered.geojson'
beam_network_with_cars = load_or_filter_beam_network_roadway_and_car_link_modes(beam_network_link, beam_network_filtered_geo_output_file)

# ## Find BEAM links close to NPMRDS TMCs (within 20 meters) ##
print("Find BEAM links close to NPMRDS TMCs (within 20 meters)")
# sf_npmrds_station_buffer = regional_npmrds_station_filtered.copy()
# sf_npmrds_station_buffer['geometry'] = sf_npmrds_station_buffer['geometry'].buffer(20)
# beam_network_with_tmc = sjoin(beam_network_with_cars, sf_npmrds_station_buffer, predicate='within')
#beam_network_with_tmc = fast_sjoin(beam_network_with_cars, regional_npmrds_station_filtered)

beam_network_with_tmc_geojson_path = work_dir + '/SFBay/beam_network_with_tmc.geojson'
beam_network_with_tmc = None
if not os.path.isfile(beam_network_with_tmc_geojson_path):
    beam_network_with_tmc_proj = find_nearest(beam_network_with_cars, regional_npmrds_station_filtered, 20)
    beam_network_with_tmc_proj = beam_network_with_tmc_proj.set_crs('EPSG:'+str(projected_crs_epsg), allow_override=True)
    beam_network_with_tmc = beam_network_with_tmc_proj.to_crs(epsg=4326)
    beam_network_with_tmc.to_csv(beam_network_with_tmc_geojson_path+".csv", index=False)
    beam_network_with_tmc.to_file(beam_network_with_tmc_geojson_path, driver='GeoJSON')
else:
    #table = pv.read_csv(beam_network_with_tmc_geojson_path)
    #beam_network_with_tmc = table.to_pandas()
    beam_network_with_tmc = gpd.read_file(beam_network_with_tmc_geojson_path)

plot_output_file = work_dir + '/SFBay/beam_network_with_tmc.png'
fig, ax = plt.subplots()
beam_network_with_tmc.plot(ax=ax, color='blue')
regional_npmrds_station_filtered.plot(ax=ax, color='red', linewidth=0.5)
fig.savefig(plot_output_file, dpi=300)







#######
# # Format output
# print("Format output")
# selected_beam_network_out = pd.DataFrame()
#
# for index, row in beam_network_with_tmc.iterrows():
#     selected_beam_network = beam_network_with_cars.loc[[index]]
#     selected_tmc = regional_npmrds_station_filtered.loc[[row['index_right']]]
#     tmc_id = selected_tmc['Tmc'].values[0]
#     print(tmc_id)
#     selected_beam_network['Tmc'] = tmc_id
#     selected_beam_network['dist_to_tmc'] = selected_beam_network.distance(selected_tmc.geometry.values[0])
#     selected_beam_network_out = pd.concat([selected_beam_network_out, selected_beam_network])
#
# # Remove duplicated links and keep closest TMC
# print("Remove duplicated links and keep closest TMC")
# selected_beam_network_filtered = selected_beam_network_out.groupby('linkId').apply(lambda x: x.loc[x['dist_to_tmc'].idxmin()])
#
# fig, ax = plt.subplots()
# selected_beam_network_filtered.plot(ax=ax, color='blue')
# regional_npmrds_station_filtered.plot(ax=ax, color='red', linewidth=0.5)
#
# # Write to file
# print("Write to file")
# selected_beam_network_filtered.to_file(work_dir + '/beam_network_npmrds_screenline.geojson', driver='GeoJSON')
# selected_beam_network_filtered.drop(columns='geometry').to_csv(work_dir + '/beam_network_npmrds_screenline.csv', index=False)

print("END")