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
scenario_name = "../../../../output/sf-light/urbansim-1k__2019-12-09_15-41-24/ITERS/it.1/"
scenario_path = scenario_name + "1.events.csv.gz"





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

refuelSession_rhcav = refuelSession.loc[refuelSession['vehicleType'].str.contains('CAV') & refuelSession['vehicle'].str.startswith('rideHailVehicle')]
refuelSession_rhhuman = refuelSession.loc[~(refuelSession['vehicleType'].str.contains('CAV')) & refuelSession['vehicle'].str.startswith('rideHailVehicle')]
refuelSession_personal = refuelSession.loc[~refuelSession['vehicle'].str.startswith('rideHailVehicle')]

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
from math import factorial
def ErlangB (E, m):
    InvB = 1.0
    for j in range(1, m+1):
        InvB = 1.0 + InvB * (j/E)
    return (1.0 / InvB)

def ErlangC(A, N):
    L = (A**N / factorial(N)) * (N / (N - A))
    sum_ = 0
    for i in range(N):
        sum_ += (A**i) / factorial(i)
    return (L / (sum_ + L))

def getMinimumNumberOfChargers(rate, p_acceptable):
    n_chargers = 0
    p_full = 1
    while p_full > p_acceptable:
        n_chargers += 1
        p_full = ErlangC(rate,n_chargers)
    return n_chargers
#%%

charging_xy = np.transpose(np.vstack([refuelSession['locationX'].values,refuelSession['locationY'].values]))
means = []
maxs = []

nstations = np.unique(np.logspace(np.log10(1),np.log10(len(refuelSession.index)/25),num=20,dtype=int))
mean_dists = np.zeros(np.size(nstations),dtype=float)
max_dists = np.zeros(np.size(nstations),dtype=float)
n_plugs = np.zeros(np.size(nstations),dtype=float)

keepGoing = True

output = dict()

for ind, n_clusters in enumerate(nstations):
    iter_out = dict()
    Charge_kmeans = MiniBatchKMeans(n_clusters).fit(charging_xy)
    Station_location = Charge_kmeans.cluster_centers_
    Charge_demand_label = Charge_kmeans.labels_
    Charge_demand_distance = np.zeros(np.size(Charge_demand_label))
    for i in range(0,n_clusters):
        temp_station_index = np.where(Charge_demand_label == i)[0]
        dataInCluster = charging_xy[temp_station_index,]
        Charge_demand_distance[temp_station_index]= np.linalg.norm(dataInCluster - Station_location[i], axis=1)/1609.34
    print("Number of charging station", n_clusters)
    print("maximum of distance", np.percentile(Charge_demand_distance,75), "mean of distance", np.mean(Charge_demand_distance))
    iter_out['mean_dists'] = np.mean(Charge_demand_distance)
    iter_out['max_dists'] = np.percentile(Charge_demand_distance,95)
    iter_out['station_location'] = Station_location
    iter_out['charge_demand_label'] = Charge_demand_label
    if (np.mean(Charge_demand_distance)<=Demand_station_distance_meangap) & keepGoing:
        Charge_demand_distance_opt = Charge_demand_distance
        Charge_demand_label_opt = Charge_demand_label
        Station_location_opt = Station_location
        n_clusters_opt = n_clusters
        keepGoing = False

    refuelSession['distanceFromStation'] = Charge_demand_distance
    refuelSession['chargingStationLabel'] = Charge_demand_label
    refuelSession['chargingStationLocationX'] = Station_location[Charge_demand_label][:,0]
    refuelSession['chargingStationLocationY'] = Station_location[Charge_demand_label][:,1]
    
    clusters = refuelSession.groupby(['chargingStationLabel','hour']).agg(
            {'chargingStationLocationX':'first',
             'chargingStationLocationY':'first', 
             'kwh': (lambda kwh: getMinimumNumberOfChargers(np.sum(kwh) / Power_rated, 0.1)),
             'vehicle':'count',
             'distanceFromStation':'max',
             'chargingStationLabel':'first'
             }).rename(columns={'chargingStationLabel':'chargingStationLabel2', 'kwh':'numPlugsRequired'}).groupby(
                    ['chargingStationLabel2']).agg(
                            {'chargingStationLocationX':'first',
                             'chargingStationLocationY':'first', 
                             'numPlugsRequired': 'max',
                             'distanceFromStation':'max',
                             'vehicle':'max'})
    iter_out['n_plugs'] = clusters['numPlugsRequired'].sum()
    output[n_clusters] = iter_out
#%%
import contextily as ctx
charger_gdf = gpd.GeoDataFrame(clusters, geometry = [Point(xy) for xy in zip(clusters['chargingStationLocationX'], clusters['chargingStationLocationY'])])

ax = gdf.plot(markersize=6)
charger_gdf.plot(ax=ax, markersize=12)
ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)