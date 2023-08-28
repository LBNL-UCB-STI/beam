#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import pyrosm

import pandas as pd
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)

# after the first execution there will be a WARNING.
# one should ignore it for now, because using required library version leads to INCORRECT work of pyrosm

# /opt/conda/lib/python3.10/site-packages/geopandas/_compat.py:124: 
# UserWarning: The Shapely GEOS version (3.11.1-CAPI-1.17.1) is incompatible with the GEOS version 
# PyGEOS was compiled with (3.10.4-CAPI-1.16.2). Conversions between both will be slow


# In[ ]:


# reading OSM.PBF file
osm_map = pyrosm.OSM("../local_files/bay_area_simplified_tertiary_strongly_2_way_network.osm.pbf")
osm_map

# help(osm_map)


# In[ ]:


# get the network for all link types.
# this returns geopandas DataFrame.

osm_network = osm_map.get_network(network_type='all')
osm_network.head(2)


# In[ ]:


# filtering

slowest_speeds = set(['10 mph', '15 mph', '15 mph;20 mph', "['15 mph', '15 mph;20 mph']", "['15 mph', '30 mph', '35 mph']",
                      "['10 mph', '25 mph', '45 mph']",  "['15 mph', '20 mph']", "['15 mph', '25 mph']", "['15 mph', '35 mph']" ])

osm_nan = osm_network[osm_network['maxspeed'] == 'nan'].copy()
osm_slow = osm_network[(osm_network['maxspeed'] != 'nan') & (osm_network['maxspeed'].isin(slowest_speeds))].copy()
osm_fast = osm_network[(osm_network['maxspeed'] != 'nan') & ~(osm_network['maxspeed'].isin(slowest_speeds))].copy()

print(f"NAN len: {len(osm_nan)}, SLOW len: {len(osm_slow)}, the rest len: {len(osm_fast)}")


# In[ ]:


# plotting

additional_text = "slow_red.nan_green"

fig, ax = plt.subplots(1, 1, figsize=(20,20), dpi=300)

osm_fast.plot(color='blue', label=f"fast links [{len(osm_fast)}]", lw=0.2, ax=ax)
osm_slow.plot(color='red', label=f"slow links [{len(osm_slow)}]", lw=0.5, ax=ax)
osm_nan.plot(color='green', label=f'speed is NAN [{len(osm_nan)}]', lw=0.2, ax=ax)

ax.set_title(additional_text, fontsize=20)
ax.legend()

# plt.savefig(f'bay_area_simplified_tertiary_strongly_2_way_network.{additional_text}.png')


# In[ ]:


# save the filtered or whole network as shape file

osm_network.to_file("OSM_network.shp")


# In[ ]:




