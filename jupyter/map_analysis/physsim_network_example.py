#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import pyrosm

import numpy as np
import pandas as pd
import geopandas as gpd
import matplotlib.pyplot as plt

from pyproj import CRS, Transformer
from geopandas import GeoDataFrame
from shapely.geometry import Point
from shapely.geometry.multilinestring import MultiLineString

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


unspecified_link_type_name = 'unspecified'

def get_direction(row):
    d_x = 'N'
    d_y = 'E'
    if row['fromLocationX'] > row['toLocationX']:
        d_x = 'S'
    if row['fromLocationY'] > row['toLocationY']:
        d_y = 'W'
    return d_x + d_y

def read_network(file_path):
    df = pd.read_csv(file_path)

    df['direction'] = df.apply(get_direction, axis=1)
    df['freespeed_mph'] = df['linkFreeSpeed'] * 2.237
    df['attributeOrigType'].fillna(value=unspecified_link_type_name, inplace=True)

    df["attributeOrigId"] = df['attributeOrigId'].astype(str)
    df["attributeOrigId"] = pd.to_numeric(df["attributeOrigId"], errors='coerce').fillna(0).astype('Int64')
    
    return df


# In[ ]:


# reading network.csv.gz file from simulation output folder

network = read_network("../local_files/sfbay-baseline-20230815-30pct-convergence_beam_year-2018-iteration--1_network.csv.gz")
network.head(2)


# In[ ]:


# converting CRS of output network to lat-lon CRS epsg:4326 and creating a geometry for new geopandas data frame

new_crs = 4326

crs_to = CRS.from_epsg(new_crs) # the lat lon CRS
crs_from = CRS.from_epsg(26910) # sf crs
transformer = Transformer.from_crs(crs_from, crs_to)

def out_row_to_geometry(df_row):
    (from_x, from_y) = transformer.transform(df_row['fromLocationX'], df_row['fromLocationY'])
    (to_x, to_y) = transformer.transform(df_row['toLocationX'], df_row['toLocationY'])
    mls = MultiLineString([[[from_y, from_x], [to_y, to_x]]])
    return mls
    
geometry = network.apply(out_row_to_geometry, axis=1)
geometry.head(2)


# In[ ]:


# creating geopandas data frame

geo_df = gpd.GeoDataFrame(network, crs=f'epsg:{new_crs}', geometry=geometry)
display(geo_df.head(2))

# saving GeoDataFrame as shape file
# geo_df.to_file("../local_files/sfbay-baseline-20230815-30pct-convergence_beam_year-2018-iteration--1_network.shp")


# In[ ]:


## how many of each road type are there with speed less than threshold

speed_field = 'freespeed_mph'
threshold = 20
moreX_name = f"more than {threshold} mph"
lessX_name = f"less than {threshold} mph"

grouped_df = network.groupby('attributeOrigType')[[speed_field]].agg(
    less_than_X=(speed_field, lambda gr:gr[gr < threshold].count()),
    more_than_X=(speed_field, lambda gr:gr[gr >= threshold].count())
)

grouped_df.rename({'less_than_X':lessX_name, 'more_than_X':moreX_name}, axis='columns', inplace=True)

ax = grouped_df.plot(kind='bar', stacked=False, rot=0, figsize=(20,4))
ax.set_xlabel("")

# plt.savefig('../local_files/vta-beam-network/link_speed_graph.png')


# In[ ]:


## description of speeds for each road type

dfs = []

road_types = network['attributeOrigType'].unique()
for road_type in road_types:
    filtered_network = network[network['attributeOrigType'] == road_type]
        
    df = filtered_network[['freespeed_mph']].describe()
    df.rename(columns={'freespeed_mph':f'{road_type}'}, inplace=True)
    dfs.append(df.transpose())
    
speed_df = pd.concat(dfs)

speed_description = grouped_df.merge(speed_df[['mean']], left_index=True, right_index=True, how='outer')
speed_description.sort_values('mean', ascending=False, inplace=True)
speed_description.rename(columns={'mean':'mean speed in mph'}, inplace=True)

speed_description.style.set_properties(**{'width': '150px'})


# In[ ]:


# filtering and plotting

fig, ax = plt.subplots(1, 1, figsize=(20, 20), dpi=300, subplot_kw={'aspect': 1})

filtered_1 = out_network[(out_network['attributeOrigType'] == unspecified_link_type_name)]
filtered_2 = out_network[(out_network['attributeOrigType'] != unspecified_link_type_name)]

filtered_1.plot(ax=ax, label=f'unspecified ({len(filtered_1)} links)', color='red', lw=0.6)
filtered_2.plot(ax=ax, label=f'the rest of ({len(filtered_2)} links)', color='blue', lw=0.2)

ax.legend()
plt.savefig('../local_files/vta-beam-network/specified_vs_unspecified_links.png')


# In[ ]:


# filtering and plotting

fig, ax = plt.subplots(1, 1, figsize=(20, 20), dpi=300, subplot_kw={'aspect': 1})

filtered_1 = out_network[(out_network['attributeOrigType'] != unspecified_link_type_name) & (out_network['freespeed_mph'] < 20)]
filtered_2 = out_network[(out_network['attributeOrigType'] != unspecified_link_type_name) & (out_network['freespeed_mph'] >= 20)]

filtered_1.plot(ax=ax, label=f'specified link types and free speed < 20 mph ({len(filtered_1)} links)', color='red', lw=2.6)
filtered_2.plot(ax=ax, label=f'specified link types and free speed > 20 mph ({len(filtered_2)} links)', color='blue', lw=0.2)

ax.legend()
plt.savefig('../local_files/vta-beam-network/specified_links_slow_vs_fast.png')


# In[ ]:


# filtering and plotting

link_type = 'secondary'

fig, ax = plt.subplots(1, 1, figsize=(20, 20), dpi=300, subplot_kw={'aspect': 1})

filtered_1 = out_network[(out_network['attributeOrigType'] == link_type) & (out_network['freespeed_mph'] < 20)]
filtered_2 = out_network[(out_network['attributeOrigType'] == link_type) & (out_network['freespeed_mph'] >= 20)]

filtered_1.plot(ax=ax, label=f'{link_type} and free speed < 20 mph ({len(filtered_1)} links)', color='red', lw=2.6)
filtered_2.plot(ax=ax, label=f'{link_type} and free speed > 20 mph ({len(filtered_2)} links)', color='blue', lw=0.2)

ax.legend()
# plt.savefig('../local_files/vta-beam-network/specified_links_slow_vs_fast.png')

