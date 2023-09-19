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


# ## approach #1

# In[ ]:


shp_path = "../local_files/FLEXSTOPS/FLEXSTOPS/FLEXServiceStops.shp"

df1 = read_shapefile_as_dataframe(shp_path)
display(df1.head(2))


# ## approach #2

# In[ ]:


shp_path = "../local_files/FLEXSTOPS/FLEXSTOPS/FLEXServiceStops.shp"

# reading the shape file
shp_df = gpd.read_file(shp_path)
display(shp_df.head(2))


# In[ ]:


# getting points out of geometries (if geometry include multiple points - they will be multiple separate rows)

points = shp_df.get_coordinates()
print(f"there are {len(points)} points")
points.rename(columns={"x": "coord-x", "y": "coord-y"}, errors="raise", inplace=True)
points.head(2)


# In[ ]:


# saving csv

csv_path = "../local_files/FLEXSTOPS/FLEXSTOPS.points.csv"
points.to_csv(csv_path, encoding='utf-8', index=False)


# In[ ]:





# In[ ]:





# In[ ]:




