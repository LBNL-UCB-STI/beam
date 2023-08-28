#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import shapefile as sh


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


# In[ ]:


shp_path = "../local_files/MTC-avgload5period/network_links.shp"

df1 = read_shapefile_as_dataframe(shp_path)
print(df1['A'].nunique(), df1['B'].nunique(), df1['CITYNAME'].nunique())
df1.head(2)


# In[ ]:


shp_path = "../local_files/VTA-model-network/HNETAM.shp"

df2 = read_shapefile_as_dataframe(shp_path)
print(df2['A'].nunique(), df2['B'].nunique())
df2.head(2)


# In[ ]:


df1_csv = pd.read_csv('../local_files/MTC-avgload5period/avgload5period.csv')
df1_csv.head()


# In[ ]:


s1 = set(df1.columns)
s2 = set(df2.columns)
len(s1), len(s2), len(s1 - s2), len(s2 - s1)


# In[ ]:


selected_cols = []

for c in df.columns:
    if not c.startswith("TOLL") and not c.startswith('VOL'):
        selected_cols.append(c)
len(selected_cols) #, selected_cols


# In[ ]:


df[selected_cols].head()


# In[ ]:


network = pd.read_csv("../local_files/network.2.csv.gz")
network.head(3)


# In[ ]:


# NOT matching

_, axs = plt.subplots(2,2, figsize=(15,6))
df['DISTANCE'].hist(bins=100, ax=axs[0][0])
network['linkLength'].hist(bins=100, ax=axs[0][1])
df['LANES'].hist(bins=100, ax=axs[1][0])
network['numberOfLanes'].hist(bins=100, ax=axs[1][1])


# In[ ]:


network_links = set(network['linkId'].unique())
network_osm_ids = set(network['attributeOrigId'].fillna(0).astype(int).unique())
len(network_links), len(network_osm_ids)


# In[ ]:


ids_shp = set(df['B'].unique())
len(ids_shp), len(ids_shp - network_osm_ids), len(network_osm_ids - ids_shp)


# In[ ]:





# In[ ]:




