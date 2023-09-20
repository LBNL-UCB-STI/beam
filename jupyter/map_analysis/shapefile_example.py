#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import shapefile as sh
import geopandas as gpd

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


def read_shapefile_as_dataframe(shp_path):
    """
    Read a shapefile into a Pandas dataframe with a 'coords' 
    column holding the geometry information. This uses the pyshp
    package
    """

    sf_shape = sh.Reader(shp_path) #, encoding="latin1")
    fields = [x[0] for x in sf_shape.fields][1:]
    records = [y[:] for y in sf_shape.records()]
    
    shps = [s.points for s in sf_shape.shapes()]
    df = pd.DataFrame(columns=fields, data=records)
    df = df.assign(coords=shps)
    return df


# ## approach to read #1

# In[ ]:


shp_path = "../local_files/ForWSP06132023/ForWSP06132023.zip"

df1 = read_shapefile_as_dataframe(shp_path)
display(df1.head(2))


# ## approach to read #2

# In[ ]:


shp_path = "../local_files/ForWSP06132023/ForWSP06132023/VTATAZ06132023.shp"

# reading the shape file
shp_df_original = gpd.read_file(shp_path)
display(shp_df_original.head(2))


# ## creating TAZ-centers file out of shape file

# In[ ]:


# preparing the shape file to create taz-centers out of it

# renaming columns and sorting by taz id
shp_df_original.rename(columns={'TAZ':'taz'}, inplace=True)
shp_df_original.sort_values('taz', inplace=True)
shp_df_original.reset_index(drop=True, inplace=True)

display(shp_df_original.head(2))

# adding necessary fields for transforming shape file to TAZ centers
shp_df_original['area'] = shp_df_original['geometry'].area
shp_df_original['centroid'] = shp_df_original['geometry'].centroid

# this one for sanity check
shp_df_original['geometry_contains_point'] = shp_df_original['geometry'].contains(shp_df_original['centroid'])

# overriding 'geometry' field in order to be able to transform CRS
shp_df_original['geometry'] = shp_df_original['centroid']


# changing CRS
# 4326 is the lat\lon CRS
# 26910 is the sfbay area CRS
shp_df = shp_df_original.to_crs(26910)[['taz','county','area','geometry_contains_point','geometry']]

shp_df['coord-x'] = shp_df['geometry'].x
shp_df['coord-y'] = shp_df['geometry'].y

display(shp_df.head(2))
display(shp_df['geometry_contains_point'].value_counts())


# In[ ]:


# creating taz-centers file out of shape file fields

# TAZ centers file format:
# taz,coord-x,coord-y,area

csv_path="../local_files/ForWSP06132023/ForWSP06132023-taz-centers.csv"
shp_df[['taz','coord-x','coord-y','area']].to_csv(csv_path, encoding='utf-8', index=False)


# In[ ]:


# saving a filtered-out shape file for investigation

selected_tazs = set([1491.0, 1492.0])
shp_df[shp_df['taz'].isin(selected_tazs)].to_file("../local_files/ForWSP06132023/ForWSP06132023_points_selected_tazs")


# ## creating file with x,y points out of shape file

# In[ ]:


import pandas as pd
import geopandas as gpd

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)

shp_path = "../local_files/FLEXSTOPS/FLEXSTOPS/FLEXServiceStops.shp"

# reading the shape file in original CRS
shp_df_original = gpd.read_file(shp_path)
display(shp_df_original.head(2))

# changing CRS
# 4326 is the lat\lon CRS
shp_df = shp_df_original.to_crs(4326)
display(shp_df.head(2))

# getting points out of geometries (if geometry include multiple points - they will be multiple separate rows)
points = shp_df.get_coordinates()
print(f"there are {len(points)} points")
display(points.head(2))

# changing column names
points.rename(columns={"x": "coord-x", "y": "coord-y"}, errors="raise", inplace=True)

# saving to file
csv_path = "../local_files/FLEXSTOPS/FLEXSTOPS.points.csv"
points.to_csv(csv_path, encoding='utf-8', index=False)


# In[ ]:




