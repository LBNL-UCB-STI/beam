#!/usr/bin/env python
# coding: utf-8

# In[1]:

"""
Teng ZENG @ UC Berkeley for BEAM project and Transportation Research Part D paper.
"""
# from ggplot import *
# from pyproj import Proj, transform

import numpy as np
import pandas as pd
# import os
# import matplotlib.pyplot as plt
from sklearn.cluster import KMeans, MiniBatchKMeans
import warnings
warnings.filterwarnings("ignore")

import geopandas as gpd
from shapely.geometry import Point

# # Extract data for paper TRP:D

# In[ ]:

#
# folder_path = "/home/ubuntu/git/beam/output/"
scenario_name = "../../../../output/sf-light/urbansim-1k__2019-12-06_16-23-33/ITERS/it.0/"
scenario_path = scenario_name + "0.events.csv.gz"





#path directory
# folder_path = "../../../../output/sf-light/"
# scenario_name = "urbansim-1k__2019-05-27_16-13-51"
# scenario_path = scenario_name + "/ITERS/it.0/0.events.csv"
#read event file

all_events = pd.read_csv(scenario_path)#, low_memory=False)

# all_events = pd.read_csv(folder_path+scenario_path)
run = scenario_path





refuelSession = all_events.loc[(all_events['type'] == 'RefuelSessionEvent')].dropna(how='all', axis=1).reset_index()

del all_events



refuelSession["kwh"] = refuelSession["fuel"]/3.6e6
refuelSession["run"] = run
refuelSession["type"] = "Charge"
refuelSession["hour"] = np.floor(refuelSession["time"]/3600).astype("int")


locations = [Point(xy) for xy in zip(refuelSession['locationX'], refuelSession['locationY'])]

gdf = gpd.GeoDataFrame(refuelSession.loc[:,['time']], crs = {'init': 'epsg:4326'}, geometry = locations).to_crs(epsg=3857)

refuelSession['locationLon'] = refuelSession['locationX']
refuelSession['locationLat'] = refuelSession['locationY']
refuelSession['locationX'] = gdf.geometry.x
refuelSession['locationY'] = gdf.geometry.y


# the information in this table include index, depature time, in s (since midnight) ; trip duration, in s, vehicle 
# ID, number of passengers, length of trip, in mile, start.x, start.y, end.x, end.y, average speed (mile per hour), 
# reposition, any movement without a passenger that is not a "deadhead"
Time_interval_day=24*60*60


#%%


Power_rated = 50.0 # in kW
Drive_range = 35.0 # in mile
Fleet_size = 10.0 # in k
Battery_capacity = Drive_range*0.3 # in kWh, fuel efficiency is 0.3 kWh/mi
Time_charge = 5.0/60.0+Battery_capacity/Power_rated # in hour, assume each charge event costs extra 5 minutes for waiting & paying etc.
Demand_station_distance_meangap=0.25

#%%
def Geod_local_coor(Location_1, Location_2):
    # calculate geographical distance between two location with local coordinates 
    # inputs are in m, outputs are in miles
    return ((Location_1[0]-Location_2[0])**2+(Location_1[1]-Location_2[1])**2)**0.5/1000/1.60934
#%%

charging_xy = np.transpose(np.vstack([refuelSession['locationX'].values,refuelSession['locationY'].values]))
means = []
maxs = []

for n_clusters in range(5,1600,2):
    Charge_kmeans = KMeans(n_clusters).fit(charging_xy)
    Station_location = Charge_kmeans.cluster_centers_
    Charge_demand_label = Charge_kmeans.labels_
    Charge_demand_distance = np.zeros(np.size(Charge_demand_label))
    for i in range(0,n_clusters):
        temp_station_index = np.where(Charge_demand_label == i)[0]
        dataInCluster = charging_xy[temp_station_index,]
        Charge_demand_distance[temp_station_index]= np.linalg.norm(dataInCluster - Station_location[i], axis=1)/1609.34
    print("Number of charging station", n_clusters)
    print("maximum of distance", np.percentile(Charge_demand_distance,75), "mean of distance", np.mean(Charge_demand_distance))
    if np.mean(Charge_demand_distance)<=Demand_station_distance_meangap:
        break

refuelSession['distanceFromStation'] = Charge_demand_distance
refuelSession['chargingStationLabel'] = Charge_demand_label
refuelSession['chargingStationLocationX'] = Station_location[Charge_demand_label][:,0]
refuelSession['chargingStationLocationY'] = Station_location[Charge_demand_label][:,1]

clusters = refuelSession.groupby(['chargingStationLabel','hour']).agg(
        {'chargingStationLocationX':'first',
         'chargingStationLocationY':'first', 
         'kwh':'sum',
         'vehicle':'count',
         'distanceFromStation':'max'
         }).groupby(
                ['chargingStationLabel']).agg(
                        {'chargingStationLocationX':'first',
                         'chargingStationLocationY':'first', 
                         'kwh':'max',
                         'distanceFromStation':'max',
                         'vehicle':'max'})
#%%
import contextily as ctx
charger_gdf = gpd.GeoDataFrame(clusters, geometry = [Point(xy) for xy in zip(clusters['chargingStationLocationX'], clusters['chargingStationLocationY'])])

ax = gdf.plot(markersize=6)
charger_gdf.plot(ax=ax, markersize=12)
ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)