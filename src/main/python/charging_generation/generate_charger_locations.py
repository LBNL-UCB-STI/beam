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
from matplotlib.lines import Line2D
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

gdf = gpd.GeoDataFrame(refuelSession.loc[:,['time','kwh']], crs = {'init': 'epsg:4326'}, geometry = locations).to_crs(epsg=3857)

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
taz_with_cluster = gpd.GeoDataFrame(gpd.sjoin(taz,gdf,how='inner',op='intersects').groupby('taz_id').agg({'kwh':'sum','geometry':'first'}))
taz_with_cluster['kwh_per_km2'] = taz_with_cluster['kwh'] / taz_with_cluster.geometry.area * 1e6
#%%
fmap = plt.figure(figsize=(7,8))
ax = plt.gca()
taz_with_cluster.plot(column='kwh_per_km2',legend=True, ax=ax, alpha=0.85, cmap = 'Reds', k=7, scheme='fisher_jenks')
ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)
plt.savefig(outfolder+'/plots/chargind_demand_map.png')
#%%
def assignStallsToTaz(stalls, taz, parkingType, chargingType, feeInCents, inputParking = None):
    chargingString = 'Custom(' + str(chargingType) + '|DC)'
    output = []
    taz_with_cluster = gpd.sjoin(taz,stalls,how='inner',op='intersects').groupby('taz_id').agg({'numPlugsRequired':'sum','geometry':'first'})
    for row in taz_with_cluster.iterrows():
        newrow = {'taz':row[0],'parkingType':parkingType,'pricingModel':'Block','chargingType':chargingString,'numStalls':np.ceil(row[1].numPlugsRequired),'feeInCents':feeInCents,'ReservedFor':'Any'}
        output.append(newrow)
    output = pd.DataFrame(output, columns = ['taz','parkingType','pricingModel','chargingType','numStalls','feeInCents','ReservedFor'])
    if ~(inputParking is None):
        output = pd.concat([output,inputParking])
    return output
#%%

def assignChargingLocations(refuelSessions, n_clusters, powers, probs):
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


    refuelSessions['distanceFromStation'] = Charge_demand_distance
    refuelSessions['chargingStationLabel'] = Charge_demand_label
    refuelSessions['chargingStationLocationX'] = Station_location[Charge_demand_label][:,0]
    refuelSessions['chargingStationLocationY'] = Station_location[Charge_demand_label][:,1]
    
    for power in powers:
        iter_out_val[power] = dict()
        iter_out_array[power] = dict()
        for prob in probs:
            iter_out_val[power][prob] = dict()
            iter_out_array[power][prob] = dict()
            iter_out_val[power][prob]['mean_dists'] = np.mean(Charge_demand_distance)
            iter_out_val[power][prob]['max_dists'] = np.percentile(Charge_demand_distance,95)
            iter_out_array[power][prob]['station_location'] = Station_location
            iter_out_array[power][prob]['charge_demand_label'] = Charge_demand_label
            clusters = refuelSessions.groupby(['chargingStationLabel','hour']).agg(
                    {'chargingStationLocationX':'first',
                     'chargingStationLocationY':'first', 
                     'kwh': (lambda kwh: getMinimumNumberOfChargers(np.sum(kwh) / power, prob)),
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
            iter_out_val[power][prob]['n_plugs'] = clusters['numPlugsRequired'].sum()    
            clusters = gpd.GeoDataFrame(clusters, crs = {'init': 'epsg:3857'}, geometry = [Point(xy) for xy in zip(clusters['chargingStationLocationX'], clusters['chargingStationLocationY'])])
            iter_out_array[power][prob]['gdf'] = clusters
    return iter_out_val, iter_out_array


#%%



Power_rated = [50.0, 150.0] # in kW
Max_queuing_probability = [0.1, 0.25, 0.5] # Chance that someone would find their nearest charger full


nstations_human = np.unique(np.logspace(np.log10(2),np.log10(len(refuelSession_rhhuman.index)/10),num=20,dtype=int))
nstations_cav = np.unique(np.logspace(np.log10(2),np.log10(len(refuelSession_rhcav.index)/400),num=20,dtype=int))


output_val_human = dict()
output_array_human = dict()

output_val_cav = dict()
output_array_cav = dict()
for power in Power_rated:
    output_val_human[power] = dict()
    output_array_human[power] = dict()
    output_val_cav[power] = dict()
    output_array_cav[power] = dict()
    
for power in Power_rated:
    for prob in Max_queuing_probability:
        output_val_human[power][prob] = dict()
        output_array_human[power][prob] = dict()
        output_val_cav[power][prob] = dict()
        output_array_cav[power][prob] = dict()

for ind, n_clusters in enumerate(nstations_human):
    
    iter_out_val, iter_out_array = assignChargingLocations(refuelSession_rhhuman, n_clusters, Power_rated, Max_queuing_probability)
    for power in Power_rated:
        for prob in Max_queuing_probability:
            output_val_human[power][prob][n_clusters] = iter_out_val[power][prob]
            output_array_human[power][prob][n_clusters] = iter_out_array[power][prob]
            new_parking = assignStallsToTaz(iter_out_array[power][prob]['gdf'], taz, 'Public', power, 2.*power, parking_input)
            new_parking['numStalls'] = new_parking['numStalls'].astype('int')
            new_parking.to_csv(outfolder + 'sf-taz-parking-'+str(n_clusters)+'-clusters-'+str(power)[:-2]+'-kW-'+str(prob)+'-prob.csv', index=False)

for ind, n_clusters in enumerate(nstations_cav):
    iter_out_val, iter_out_array = assignChargingLocations(refuelSession_rhcav, n_clusters, Power_rated, Max_queuing_probability)
    for power in Power_rated:
        for prob in Max_queuing_probability:
            output_val_cav[power][prob][n_clusters] = iter_out_val[power][prob]
            output_array_cav[power][prob][n_clusters] = iter_out_array[power][prob]
            new_parking = assignStallsToTaz(iter_out_array[power][prob]['gdf'], taz, 'Public', power, 2.*power)
            new_parking['numStalls'] = new_parking['numStalls'].astype('int')
            new_parking.to_csv(outfolder + 'sf-depot-parking-'+str(n_clusters)+'-clusters-'+str(power)[:-2]+'-kW-'+str(prob)+'-prob.csv', index=False)

#%%
            
colors = [(0.894117, 0.101960, 0.10980),(0.215686, 0.494117, 0.7215686),(0.30196078, 0.68627450, 0.29019607)]
lines = ['-','--',':']

f, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(10,8))
for ind_power, power in enumerate(Power_rated):
    for ind_prob, prob in enumerate(Max_queuing_probability):
        collected = pd.DataFrame(output_val_human[power][prob]).transpose()
        ax3.plot(collected.index, collected.n_plugs, color = colors[ind_power], linestyle = lines[ind_prob])
ax1.plot(collected.index, collected.mean_dists)
ax3.set_xlabel('Number of Charging Stations')
ax3.set_ylabel('Number of Plugs')
ax1.set_ylabel('Mean Distance To Charge (mi)')
#ax3.set_xscale('log')
#ax1.set_xscale('log')
ax1.set_title('Human Ride Hail')

custom_lines_color = [Line2D([0], [0], color=colors[0]),
                Line2D([0], [0], color=colors[1]),
                Line2D([0], [0], color=colors[2])]

ax3.legend(custom_lines_color, ['50 kW', '150 kW'])

for ind_power, power in enumerate(Power_rated):
    for ind_prob, prob in enumerate(Max_queuing_probability):
        collected = pd.DataFrame(output_val_cav[power][prob]).transpose()
        ax4.plot(collected.index, collected.n_plugs, color = colors[ind_power], linestyle = lines[ind_prob])
ax2.plot(collected.index, collected.mean_dists)
ax4.set_xlabel('Number of Charging Stations')
ax4.set_ylabel('Number of Plugs')
ax2.set_ylabel('Mean Distance To Charge (mi)')
#ax2.set_xscale('log')
#ax4.set_xscale('log')
ax2.set_title('Automated Ride Hail')

custom_lines_style = [Line2D([0], [0], color='k', linestyle = lines[0]),
                Line2D([0], [0], color='k', linestyle = lines[1]),
                Line2D([0], [0], color='k', linestyle = lines[2])]
ax4.legend(custom_lines_style, ['0.1 Occupied', '0.25 Occupied', '0.5 Occupied'])

plt.savefig(outfolder+'/plots/tradeoffs.png')
