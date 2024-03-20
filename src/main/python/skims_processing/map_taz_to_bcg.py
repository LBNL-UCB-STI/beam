import geopandas as gpd
import pandas as pd
import os

work_dir = os.path.expanduser("~/Workspace/Models/GIS/SF Bay Area")
taz_file = work_dir + "/TAZ/Transportation_Analysis_Zones.shp"
census_file = work_dir + "/CBG/SFBAY_bg.geojson"

# Define the target projection (EPSG:4326)
target_crs = 'EPSG:4326'

# Load the TAZ and census block group shapefiles
taz_gdf = gpd.read_file(taz_file)
census_gdf = gpd.read_file(census_file)

# Perform projection transformation
taz_gdf = taz_gdf.to_crs(target_crs)
census_gdf = census_gdf.to_crs(target_crs)


# Create a custom spatial join function
def custom_spatial_join(taz, census):
    result = []
    for census_row in census.iterrows():
        census_geom = census_row[1]['geometry']
        census_id = census_row[1]['GEOID']
        intersected_taz = taz[taz.intersects(census_geom)]
        if not intersected_taz.empty:
            #df = intersected_taz.loc[intersected_taz.intersection(census_geom)]
            #print(df.area)
            intersected_taz['area_intersection'] = intersected_taz.intersection(census_geom).area
            intersected_taz = intersected_taz.sort_values(by='area_intersection', ascending=False)
            max_area_taz = intersected_taz.iloc[0]['taz1454']
            result.append((census_id, max_area_taz))
    return result


# Perform the custom spatial join
taz_to_cbg_mapping = custom_spatial_join(taz_gdf, census_gdf)

# Create a DataFrame from the result
result_df = pd.DataFrame(taz_to_cbg_mapping, columns=['GEOID', 'taz1454'])

# You can now access the mapping information
print(result_df)

# Save the mapping to a new CSV file if needed
result_df.to_csv(work_dir + '/cbg_taz_mapping.csv', index=False)
