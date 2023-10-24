#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import geopandas as gpd

from pyproj import CRS, Transformer
from geopandas import GeoDataFrame
from shapely.geometry import Point
from shapely.geometry.multilinestring import MultiLineString
from IPython.display import clear_output

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


unspecified_link_type_name = 'unspecified'


def get_direction(row):
    d_x = 'North'
    d_y = 'East'
    if row['fromLocationX'] > row['toLocationX']:
        d_x = 'South'
    if row['fromLocationY'] > row['toLocationY']:
        d_y = 'West'
    return f'{d_x} - {d_y}'


def read_network(file_path):
    df = pd.read_csv(file_path)

    df['attributeOrigType'].fillna(value=unspecified_link_type_name, inplace=True)

    df["attributeOrigId"] = df['attributeOrigId'].astype(str)
    df["attributeOrigId"] = pd.to_numeric(df["attributeOrigId"], errors='coerce').fillna(0).astype('Int64')
    df['direction'] = df.apply(get_direction, axis=1)
    
    return df


def read_linkstats(file_path):
    df = pd.read_csv(file_path)

    df['speed_ms'] = df['length'] / df['traveltime']
    df['speed_mph'] = df['speed_ms'] * 2.237
    df['freespeed_mph'] = df['freespeed'] * 2.237

    df['speed_delta_mph'] = df['freespeed_mph'] - df['speed_mph']
    
    return df


# sf-bay area CRS is 26910
def read_geo_network(network_path, network_crs):
    n_df = read_network(network_path)
    
    crs_to_id = 4326 # the LAT LON CRS
    crs_to = CRS.from_epsg(crs_to_id)
    crs_from = CRS.from_epsg(network_crs)
    transformer = Transformer.from_crs(crs_from, crs_to)

    def out_row_to_geometry(df_row):
        (from_x, from_y) = transformer.transform(df_row['fromLocationX'], df_row['fromLocationY'])
        (to_x, to_y) = transformer.transform(df_row['toLocationX'], df_row['toLocationY'])
        mls = MultiLineString([[[from_y, from_x], [to_y, to_x]]])
        return mls

    geometry = n_df.apply(out_row_to_geometry, axis=1)
    geo_df = gpd.GeoDataFrame(n_df, crs=f'epsg:{crs_to_id}', geometry=geometry)
    return geo_df
    

def read_full_network(linkstats_path, network_path):
    n_df = read_network(network_path)
    l_df = read_linkstats(linkstats_path)
    
    # simple sanity check
    links_ids_1 = set(l_df['link'].unique())
    links_ids_2 = set(n_df['linkId'].unique())
    if (len(links_ids_1 - links_ids_2) != 0):
        print(f"SOMETHING IS WRONG!! Links are not the same from both files!")

    full_df = l_df.merge(n_df, left_on='link', right_on='linkId', how='outer')
    return full_df


## to do sanity check after merging linkstats and network
## to ensure that the linkstats was produced from the network
def do_sanity_check(full_df):

    def row_sanity_check(row):
        return row['to'] == row['toNodeId'] \
           and row['from'] == row['fromNodeId'] \
           and row['freespeed'] == row['linkFreeSpeed']

    return full_df.apply(lambda r: 'equal' if row_sanity_check(r) else 'NOT equal', axis=1).value_counts()

'init complete'


# In[ ]:


## reading network file
# network = read_network("../local_files/vta-beam-network/network.csv.gz")
# print(f"there are {len(network)} records")
# display(network.head(2))

## reading linkstats file
# linkstats = read_linkstats("../local_files/vta-beam-network/1.0.linkstats.csv.gz")
# print(f"there are {len(linkstats)} records")
# display(linkstats.head(2))

# reading full network
full_network = read_full_network(
    linkstats_path = "../local_files/vta-beam-network/1.0.linkstats.csv.gz", 
    network_path = "../local_files/vta-beam-network/network.csv.gz",
)

display(full_network.head(2))


# In[ ]:


def avg_speed_vol(data):
    if data['volume'].sum() > 0:
        return np.average(data['speed_mph'], weights=data['volume'])
    return np.average(data['speed_mph'])

def total_volume(data):
    return np.sum(data['volume'])

def average_freespeed(data):
    return np.average(data['freespeed_mph'])

def speeds_analysis(data):
    return pd.Series({
        "average weighted speed" : avg_speed_vol(data),
        "total volume": total_volume(data),
        "average freespeed": average_freespeed(data),
        "5 percentile of speed": np.percentile(data['speed_mph'], 5),
        "95 percentile of speed": np.percentile(data['speed_mph'], 95),
    })


df = full_network #.head(100000)
df_g = df.groupby(['hour','attributeOrigType']).apply(speeds_analysis).reset_index()
df_g['total volume weighted'] = df_g['total volume'] / df_g['total volume'].max()

road_types_df = df_g.groupby('attributeOrigType')['total volume'].sum().reset_index().sort_values('total volume', ascending=False)
road_types_ordered_by_volume = list(road_types_df['attributeOrigType'])

hours = df_g['hour'].unique()

plt.figure(figsize=(15,7))
plt.ylim(0, 70)

N_road_types = 6

for road_type in road_types_ordered_by_volume[:6]:
    df_g_f = df_g[df_g['attributeOrigType'] == road_type]
    avg_speed = df_g_f['average weighted speed']
    
    size_multiplier =  df_g_f['total volume weighted'].sum() * 20
    size = df_g_f['total volume weighted'] * size_multiplier
    
    plt.scatter(x=hours, y=avg_speed, s=size, label=f"avg speed mph [{road_type}]", alpha=0.6)
    plt.plot(hours, avg_speed, alpha=0.2)

plt.legend()
plt.title(f"Average speed for road types weighted by volume (top {N_road_types} road types by volume)")
plt.show()


# In[ ]:


def avg_speed_vol(data):
    if data['volume'].sum() > 0:
        return np.average(data['speed_mph'], weights=data['volume'])
    return np.average(data['speed_mph'])

def total_volume(data):
    return np.sum(data['volume'])

def average_freespeed(data):
    return np.average(data['freespeed_mph'])

def speeds_analysis(data):
    return pd.Series({
        "average weighted speed" : avg_speed_vol(data),
        "total volume": total_volume(data),
        "average freespeed": average_freespeed(data),
        "5 percentile of speed": np.percentile(data['speed_mph'], 5),
        "95 percentile of speed": np.percentile(data['speed_mph'], 95),
    })


links_with_most_volume = set(full_network[full_network['volume'] > np.percentile(full_network['volume'], 99.5)]['link'].unique())
df = full_network[full_network['link'].isin(links_with_most_volume)]

df_g = df.groupby(['hour','attributeOrigType']).apply(speeds_analysis).reset_index()
df_g['total volume weighted'] = df_g['total volume'] / df_g['total volume'].max()

road_types_df = df_g.groupby('attributeOrigType')['total volume'].sum().reset_index().sort_values('total volume', ascending=False)
# display(road_types_df)

road_types_ordered_by_volume = list(road_types_df['attributeOrigType'])

hours = df_g['hour'].unique()

plt.figure(figsize=(15,4))
plt.ylim(0, 70)

N_road_types = 3

for road_type in road_types_ordered_by_volume[:N_road_types]:
    df_g_f = df_g[df_g['attributeOrigType'] == road_type]
    avg_speed = df_g_f['average weighted speed']
    min_speed = df_g_f["5 percentile of speed"]
    
    size_multiplier =  df_g_f['total volume weighted'].sum() * 20
    size = df_g_f['total volume weighted'] * size_multiplier
    
    scatterplot = plt.scatter(x=hours, y=avg_speed, s=size, label=f"avg speed mph [{road_type}, volume {df_g_f['total volume'].sum()}]", alpha=0.4)
    col = scatterplot.get_facecolors()[0].tolist()
    plt.plot(hours, avg_speed, alpha=0.2, c=col)
    plt.plot(hours, min_speed, alpha=0.2, label=f"5 percentile for speed mph [{road_type}]", c=col)

plt.legend()
plt.title(f"Average speed for top 0.5% links by volume, weighted by volume (top {N_road_types} road types by volume)")
plt.show()


# In[ ]:


# sf-bay area CRS is 26910
geo_network = read_geo_network(
    network_path = "../local_files/vta-beam-network/network.csv.gz", 
    network_crs=26910
)


# In[ ]:


# filtering and plotting

links_with_most_volume = set(full_network[full_network['volume'] > np.percentile(full_network['volume'], 99.5)]['link'].unique())

fig, ax = plt.subplots(1, 1, figsize=(20, 20), dpi=300, subplot_kw={'aspect': 1})

filtered_1 = geo_network[(geo_network['linkId'].isin(links_with_most_volume))]
filtered_2 = geo_network[(~geo_network['linkId'].isin(links_with_most_volume))]

filtered_1.plot(ax=ax, label=f'({len(filtered_1)} links) with 0.5% top volume', color='red', lw=1.2)
filtered_2.plot(ax=ax, label=f'the rest of ({len(filtered_2)} links)', color='blue', lw=0.2)

ax.legend()

# base_file_name = '../local_files/vta-beam-network/links_with_most_volume'
# network[(network['linkId'].isin(slow_links_ids))].to_csv(base_file_name + ".csv.gz")
# filtered_1.to_csv(base_file_name + ".geo.csv.gz")
# filtered_1.to_file(base_file_name + ".shp")
# plt.savefig(base_file_name + ".png")


# In[ ]:


## how many of each road type are there with speed less than threshold

speed_field = 'speed_mph'
threshold = 20
moreX_name = f"more than {threshold} mph"
lessX_name = f"less than {threshold} mph"

grouped_df = full_df.groupby('attributeOrigType')[[speed_field]].agg(
    less_than_X=(speed_field, lambda gr:gr[gr < threshold].count()),
    more_than_X=(speed_field, lambda gr:gr[gr >= threshold].count())
)

grouped_df.rename({'less_than_X':lessX_name, 'more_than_X':moreX_name}, axis='columns', inplace=True)

ax = grouped_df.plot(kind='bar', stacked=False, rot=0, figsize=(20,4))
ax.set_xlabel("")

plt.savefig('../local_files/vta-beam-network/link_speed_graph.png')


# In[ ]:


# an example of analysis of speed of selected links subset

east_bound_left = set([59118,80745]) # NE
west_bound_left = set([59119,80744]) # SW

east_bound_right = set([20062,34374]) # NE
west_bound_right = set([20063,34375]) # SW

display(network[network['linkId'].isin(east_bound_left)][['linkId','fromLocationX','fromLocationY','toLocationX','toLocationY','direction']])
display(network[network['linkId'].isin(west_bound_left)][['linkId','fromLocationX','fromLocationY','toLocationX','toLocationY','direction']])
display(network[network['linkId'].isin(east_bound_right)][['linkId','fromLocationX','fromLocationY','toLocationX','toLocationY','direction']])
display(network[network['linkId'].isin(west_bound_right)][['linkId','fromLocationX','fromLocationY','toLocationX','toLocationY','direction']])


# In[ ]:


# an example of analysis of speed of selected links subset
# here are two bridges analysis

left_road = set([59118,80745,59119,80744])
right_road = set([20062,34374,20063,34375]) 

fig,axs = plt.subplots(1,2,figsize=(18,4))
fig.subplots_adjust(wspace=0.1, hspace=0.4)

def plot_aggregated_speed(df, set_of_ids, label, axis, save_to_csv=False):
    df_filtered = df[(df['link'].isin(set_of_ids))]
    directions = df_filtered['direction'].unique()
    
    function_name = 'mean'
    for direction in sorted(directions):
        df_filtered_direction = df_filtered[df_filtered['direction'] == direction]
        df_grouped = df_filtered_direction.groupby('hour')[['speed_mph']].agg(function_name).reset_index()
        df_grouped.plot(x='hour', y='speed_mph', marker='o', linewidth=4, linestyle='-', label=f'{function_name} speed  {label}  [{direction}]', ax=axis, alpha=0.5)
    
    if save_to_csv:
        df_filtered.to_csv(f'../local_files/vta-beam-network/linkstats_1_filtered_links_{label}.csv'.replace(' ', '.'))

    
plot_aggregated_speed(full_network, left_road, 'left road', axs[0])
plot_aggregated_speed(full_network, right_road, 'right road', axs[1])

fig.suptitle("Full population simulation (linkstats #1 analysis)")

for ax in axs:
    ax.set_ylim(bottom=0)
    ax.legend(loc='lower right')
    ax.set_ylabel('speed mph')

# plt.savefig(f'../local_files/vta-beam-network/linkstats_1_filtered_links.png')


# In[ ]:





# In[ ]:


# overwriteLinkParam file generation
# for links with speed less than threshold

# expected file header:
# link_id,capacity,free_speed,length,lanes

threshold_mph = 20
threshold_ms = 10 # threshold_mph / 2.237

linkId_to_values = {}

links_with_speed_less_than_threshold = set(full_df[full_df['freespeed'] < threshold_ms]['link'].unique())
print(f"there are {len(links_with_speed_less_than_threshold)} links with free speed less than {threshold_ms} meters per second.")

selected_columns = ['linkId','linkCapacity','linkFreeSpeed','linkLength','numberOfLanes','attributeOrigType']
df = full_df[full_df['linkId'].isin(links_with_speed_less_than_threshold)][selected_columns]
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





# In[ ]:


# group by and apply example

import pandas as pd

df = pd.DataFrame({'x':[2, 3, -10, -10],
                   'y':[10, 13, 20, 30],
                   'id':['a', 'a', 'b', 'b']})

def mindist(data):
     return min(data['y'] - data['x'])

def maxdist(data):
    return max(data['y'] - data['x'])

def fun(data):
    return pd.Series({"maxdist":maxdist(data),
                      "mindist":mindist(data)})

df.groupby('id').apply(fun)


# In[ ]:





# In[ ]:





# In[ ]:




