#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


# In[ ]:


## reading files

network = pd.read_csv("../local_files/network.csv.gz")
linkstats = pd.read_csv("../local_files/b.6.linkstats.csv.gz")

network.shape, linkstats.shape


# In[ ]:


network.head(2)


# In[ ]:


linkstats.head(2)


# In[ ]:


## merging together based on link Id 
## assuming files are for the same network and has the same number of links

full_df = linkstats.merge(network, left_on='link', right_on='linkId', how='outer')
full_df.head(2)


# In[ ]:


## doing sanity check

def row_sanity_check(row):
    return row['to'] == row['toNodeId'] \
       and row['from'] == row['fromNodeId'] \
       and row['freespeed'] == row['linkFreeSpeed']

print(f"the shape of df is {full_df.shape}")
full_df.apply(lambda r: 'equal' if row_sanity_check(r) else 'NOT equal', axis=1).value_counts()


# In[ ]:


## calculating the speed and free speed and speed in km/h

full_df['speed'] = full_df['length'] / full_df['traveltime']
full_df['speed_km_h'] = full_df['speed'] * 3.6
full_df['freespeed_km_h'] = full_df['freespeed'] * 3.6
full_df.head(2)


# In[ ]:


## speed distribution

max_speed = int(full_df['speed_km_h'].max())
full_df['speed_km_h'].hist(bins=max_speed, figsize=(15,2))
print(f'max speed and number of buckets is {max_speed}')


# In[ ]:


## description of speeds for each road type

dfs = []

road_types = network['attributeOrigType'].unique()
for road_type in road_types:
    filtered_network = full_df[full_df['attributeOrigType'] == road_type]
    df = filtered_network[['speed', 'freespeed']].describe()
    df.rename(columns={'speed':f'{road_type} speed', 'freespeed':f'{road_type} free speed'}, inplace=True)
    dfs.append(df.transpose())
    
speed_df = pd.concat(dfs)
speed_df


# In[ ]:





# In[ ]:




