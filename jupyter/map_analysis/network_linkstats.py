#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import math

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


# In[ ]:


## reading files

network = pd.read_csv("../local_files/network.1.csv.gz")
network['attributeOrigType'].fillna(value='UNKNOWN', inplace=True)

linkstats = pd.read_csv("../local_files/linkstats.1.csv.gz")

print(network.shape, linkstats.shape)
# network['attributeOrigType'].value_counts()


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


## calculating speed, free speed, speed in km/h, speed in mph

full_df['speed'] = full_df['length'] / full_df['traveltime']

full_df['freespeed_km_h'] = full_df['freespeed'] * 3.6
full_df['speed_km_h'] = full_df['speed'] * 3.6

full_df['freespeed_mph'] = full_df['freespeed'] * 2.237
full_df['speed_mph'] = full_df['speed'] * 2.237

full_df.head(2)


# In[ ]:


## speed distribution

max_speed = int(full_df['speed_km_h'].max())
full_df['speed_km_h'].hist(bins=max_speed, figsize=(15,2))
print(f'max speed and number of buckets is {max_speed}')


# In[ ]:


## description of speeds for each road type

# dfs = []
dfs_free = []

road_types = network['attributeOrigType'].unique()
for road_type in road_types:
    filtered_network = full_df[full_df['attributeOrigType'] == road_type]
    
    # df = filtered_network[['speed']].describe()
    # df.rename(columns={'speed':f'{road_type} speed'}, inplace=True)
    # dfs.append(df.transpose())
    
    df = filtered_network[['freespeed']].describe()
    df.rename(columns={'freespeed':f'{road_type} free speed'}, inplace=True)
    dfs_free.append(df.transpose())
    
# speed_df = pd.concat(dfs)
# display(speed_df)

free_speed_df = pd.concat(dfs_free)
display(free_speed_df)


# In[ ]:


## how many of each road type are there with speed less than threshold

grouped_df = full_df.groupby('attributeOrigType')[['linkFreeSpeed']].agg(
    less_than_20=('linkFreeSpeed', lambda gr:gr[gr < 20].count()),
    more_than_20=('linkFreeSpeed', lambda gr:gr[gr >= 20].count())
)

grouped_df.rename({'less_than_20':"less than 20", 'more_than_20':"more than 20"}, axis='columns', inplace=True)

ax = grouped_df.plot(kind='bar', stacked=True, rot=20, figsize=(20,4))

# if numbers are required on top of bars:
#
# for (container, pdd, color) in zip(ax.containers, [0,10], ['blue', 'orange']):
#     ax.bar_label(container, padding=pdd, color=color)


# In[ ]:


grouped_df.sort_values('more than 20')


# In[ ]:


# overwriteLinkParam file generation
# for links with speed less than threshold

# expected file header:
# link_id,capacity,free_speed,length,lanes

threshold_mph = 20
threshold_ms = 10 # threshold_mph / 2.237

linkId_to_values = {}

links_with_speed_less_than_threshold = set(linkstats2[linkstats2['freespeed'] < threshold_ms]['link'].unique())
print(f"there are {len(links_with_speed_less_than_threshold)} links with free speed less than {threshold_ms} meters per second.")

selected_columns = ['linkId','linkCapacity','linkFreeSpeed','linkLength','numberOfLanes','attributeOrigType']
df = network2[network2['linkId'].isin(links_with_speed_less_than_threshold)][selected_columns]
df.rename(columns={'linkId':'link_id', 
                   'linkCapacity':'capacity', 
                   'linkFreeSpeed':'free_speed', 
                   'linkLength':'length',
                   'numberOfLanes':'lanes',
                   'attributeOrigType': 'road_type'}, inplace=True)


def get_mean_speed(row):
    road_type = row['road_type']
     
    if road_type and str(road_type) != 'nan':
        mean_speed = road_type2speed.get(road_type)
        if mean_speed:
            return mean_speed
        else:
            print(road_type)
    else:
        return road_type2speed.get('unclassified')

    
# ax = df['free_speed'].hist(bins=30, label='before', alpha=0.4)
df['free_speed'] = df.apply(get_mean_speed, axis=1)
# df['free_speed'].hist(bins=30, label='after', ax=ax, alpha=0.4)
# ax.legend()

display(df['road_type'].value_counts(dropna=False))
display(df.head())
df[['link_id','capacity','free_speed','length','lanes']].to_csv('../local_files/overwriteLinkParamFile.csv')


# In[ ]:





# In[ ]:





# In[ ]:


dd = {
    'l1' : [1,2,3] * 10,
    'l2' : list(range(12,12 + 15)) * 2
}

df = pd.DataFrame.from_dict(dd)
display(df.head())

df.groupby('l1')[['l2']].agg(l3=('l2', lambda gr: gr[gr >= 16].count()), l4=('l2', lambda gr: gr[gr < 16].count()) )


# In[ ]:




