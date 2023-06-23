#!/usr/bin/env python
# coding: utf-8

# In[2]:


import pyrosm

import pandas as pd
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[3]:


# reading OSM.PBF file
osm_map = pyrosm.OSM("../beam_root/test/input/sf-light/r5/sflight_muni.osm.pbf")
osm_map


# In[4]:


# get the network
# this returns geopandas DataFrame
osm_network = osm_map.get_network()
osm_network.head(2)


# In[5]:


# filtering and plotting

fig, axs = plt.subplots(1, 2, figsize=(10, 5), dpi=100)

filtered_1 = osm_network[(osm_network['id'] > 4 * 10e7)]
print(f"There are {len(filtered_1)} lines in filtered network.")

filtered_1.plot(ax=axs[0])
filtered_1['length'].hist(ax=axs[1])


# In[6]:


# save the filtered or whole network as shape file

osm_network.to_file("OSM_network.shp")


# In[ ]:




