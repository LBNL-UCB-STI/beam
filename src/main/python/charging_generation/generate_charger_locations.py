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
import os
# import os
import matplotlib.pyplot as plt
from sklearn.cluster import KMeans, MiniBatchKMeans
import warnings
warnings.filterwarnings("ignore")

import geopandas as gpd
from shapely.geometry import Point

# # Extract data for paper TRP:D

# In[ ]:

#
# folder_path = "/home/ubuntu/git/beam/output/"
base_file = "../../../../output/sf-light/urbansim-1k__2019-12-10_14-22-03/"
scenario_name = base_file + "ITERS/it.1/"
scenario_path = scenario_name + "1.events.csv.gz"
outfolder = base_file + "parking_output/"
#os.mkdir(outfolder)
#os.mkdir(outfolder + "plots/")




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
    
taz = gpd.read_file('../../../../../sf-parking/data/BEAM/taz/sf-light-tazs.shp')
taz['taz_id'] = taz['taz']
taz = taz.set_index('taz', drop=True).to_crs(epsg=3857)
parking_input = pd.read_csv('../../../../../sf-parking/output/sf-taz-parking-base-slow.csv')

#%%
def assignStallsToTaz(stalls, taz, parkingType, chargingType, feeInCents, inputParking = None):
    output = []
    taz_with_cluster = gpd.sjoin(taz,stalls,how='inner',op='intersects').groupby('taz_id').agg({'numPlugsRequired':'sum','geometry':'first'})
    for row in taz_with_cluster.iterrows():
        newrow = {'taz':row[0],'parkingType':parkingType,'pricingModel':'Block','chargingType':chargingType,'numStalls':np.ceil(row[1].numPlugsRequired),'feeInCents':feeInCents,'ReservedFor':'Any'}
        output.append(newrow)
    output = pd.DataFrame(output, columns = ['taz','parkingType','pricingModel','chargingType','numStalls','feeInCents','ReservedFor'])
    if ~(inputParking is None):
        output = pd.concat([output,inputParking])
    return output
#%%

def assignChargingLocations(refuelSessions, n_clusters, queuing_probability):
    charging_xy = np.transpose(np.vstack([refuelSessions['locationX'].values,refuelSessions['locationY'].values]))
    iter_out_val = dict()
    iter_out_array = dict()
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
    iter_out_val['mean_dists'] = np.mean(Charge_demand_distance)
    iter_out_val['max_dists'] = np.percentile(Charge_demand_distance,95)
    iter_out_array['station_location'] = Station_location
    iter_out_array['charge_demand_label'] = Charge_demand_label

    refuelSessions['distanceFromStation'] = Charge_demand_distance
    refuelSessions['chargingStationLabel'] = Charge_demand_label
    refuelSessions['chargingStationLocationX'] = Station_location[Charge_demand_label][:,0]
    refuelSessions['chargingStationLocationY'] = Station_location[Charge_demand_label][:,1]
    
    clusters = refuelSessions.groupby(['chargingStationLabel','hour']).agg(
            {'chargingStationLocationX':'first',
             'chargingStationLocationY':'first', 
             'kwh': (lambda kwh: getMinimumNumberOfChargers(np.sum(kwh) / Power_rated, queuing_probability)),
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
    iter_out_val['n_plugs'] = clusters['numPlugsRequired'].sum()    
    clusters = gpd.GeoDataFrame(clusters, crs = {'init': 'epsg:3857'}, geometry = [Point(xy) for xy in zip(clusters['chargingStationLocationX'], clusters['chargingStationLocationY'])])
    iter_out_array['gdf'] = clusters
    return iter_out_val, iter_out_array


#%%



Power_rated = 50.0 # in kW
Max_queuing_probability = 0.25 # Chance that someone would find their nearest charger full


nstations_human = np.unique(np.logspace(np.log10(2),np.log10(len(refuelSession_rhhuman.index)/10),num=20,dtype=int))
nstations_cav = np.unique(np.logspace(np.log10(2),np.log10(len(refuelSession_rhcav.index)/150),num=20,dtype=int))


output_val_human = dict()
output_array_human = dict()

output_val_cav = dict()
output_array_cav = dict()

for ind, n_clusters in enumerate(nstations_human):
    iter_out_val, iter_out_array = assignChargingLocations(refuelSession_rhhuman, n_clusters, Max_queuing_probability)
    output_val_human[n_clusters] = iter_out_val
    output_array_human[n_clusters] = iter_out_array
    new_parking = assignStallsToTaz(iter_out_array['gdf'], taz, 'Public', 'Custom(150.0|DC)', 2.*Power_rated, parking_input)
    new_parking['numStalls'] = new_parking['numStalls'].astype('int')
    new_parking.to_csv(outfolder + 'sf-taz-parking-'+str(n_clusters)+'-clusters.csv', index=False)

for ind, n_clusters in enumerate(nstations_cav):
    iter_out_val, iter_out_array = assignChargingLocations(refuelSession_rhcav, n_clusters, Max_queuing_probability)
    output_val_cav[n_clusters] = iter_out_val
    output_array_cav[n_clusters] = iter_out_array
    new_parking = assignStallsToTaz(iter_out_array['gdf'], taz, 'Public', 'Custom(150.0|DC)', 2.*Power_rated)
    new_parking['numStalls'] = new_parking['numStalls'].astype('int')
    new_parking.to_csv(outfolder + 'sf-depot-parking-'+str(n_clusters)+'-clusters.csv', index=False)

f, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(8,8))
collected = pd.DataFrame(output_val_human).transpose()
ax1.plot(collected.index, collected.mean_dists)
ax3.plot(collected.index, collected.n_plugs)
ax3.set_xlabel('Number of Charging Stations')
ax3.set_ylabel('Number of Plugs')
ax1.set_ylabel('Mean Distance To Charge (mi)')
ax3.set_xscale('log')
ax1.set_xscale('log')
ax1.set_title('Human Ride Hail')

collected = pd.DataFrame(output_val_cav).transpose()
ax2.plot(collected.index, collected.mean_dists)
ax4.plot(collected.index, collected.n_plugs)
ax4.set_xlabel('Number of Charging Stations')
ax4.set_ylabel('Number of Plugs')
ax2.set_ylabel('Mean Distance To Charge (mi)')
ax2.set_xscale('log')
ax4.set_xscale('log')
ax2.set_title('Automated Ride Hail')
#%%
import contextily as ctx
charger_gdf = gpd.GeoDataFrame(clusters, geometry = [Point(xy) for xy in zip(clusters['chargingStationLocationX'], clusters['chargingStationLocationY'])])

ax = gdf.plot(markersize=6)
charger_gdf.plot(ax=ax, markersize=12)
ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)