#!/usr/bin/env python
# coding: utf-8

# In[1]:

"""
Teng ZENG @ UC Berkeley for BEAM project and Transportation Research Part D paper.
"""
# from ggplot import *
# from pyproj import Proj, transform
import sys
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
import required_module_installer as required
#import contextily as ctx
import shutil
from matplotlib.colors import LinearSegmentedColormap
try:
    import contextily as ctx
except:
    print("Couldn't load Contextily -- no basemaps :(")

# In[ ]:

#
# folder_path = "/home/ubuntu/git/beam/output/"



def loadEvents(path, taz_path, outfolder):
    all_events = pd.read_csv(path)#, low_memory=False)

    refuelSession = all_events.loc[(all_events['type'] == 'RefuelSessionEvent')].dropna(how='all', axis=1).reset_index()

    del all_events

    refuelSession["kwh"] = refuelSession["fuel"]/3.6e6
    refuelSession["hour"] = np.floor(refuelSession["time"]/3600).astype("int")
    refuelSession = refuelSession.loc[refuelSession['kwh'] > 0,:]
    
    locations = [Point(xy) for xy in zip(refuelSession['locationX'], refuelSession['locationY'])]
    
    refuelSession['kwh_rhcav'] = 0.0
    refuelSession['kwh_noncav'] = 0.0
    rhcav = refuelSession['vehicleType'].str.contains('CAV') & refuelSession['vehicle'].str.startswith('rideHailVehicle')
    refuelSession.loc[rhcav, 'kwh_rhcav'] = refuelSession.loc[rhcav, 'kwh']
    noncav = ~(refuelSession['vehicleType'].str.contains('CAV'))
    refuelSession.loc[noncav, 'kwh_noncav'] = refuelSession.loc[noncav, 'kwh']
    
    gdf = gpd.GeoDataFrame(refuelSession.loc[:,['time','kwh','kwh_rhcav','kwh_noncav']], crs = {'init': 'epsg:4326'}, geometry = locations).to_crs(epsg=3857)
    
    refuelSession['locationLon'] = refuelSession['locationX']
    refuelSession['locationLat'] = refuelSession['locationY']
    refuelSession['locationX'] = gdf.geometry.x
    refuelSession['locationY'] = gdf.geometry.y
    refuelSession['geometry'] = gdf.geometry
    
    
    # the information in this table include index, depature time, in s (since midnight) ; trip duration, in s, vehicle 
    # ID, number of passengers, length of trip, in mile, start.x, start.y, end.x, end.y, average speed (mile per hour), 
    # reposition, any movement without a passenger that is not a "deadhead"
    Time_interval_day=24*60*60
    
    refuelSession_rhcav = refuelSession.loc[refuelSession['vehicleType'].str.contains('CAV') & refuelSession['vehicle'].str.startswith('rideHailVehicle')]
    refuelSession_noncav = refuelSession.loc[~(refuelSession['vehicleType'].str.contains('CAV'))]    
    
    taz = gpd.read_file(taz_path)
    taz['taz_id'] = taz['taz']
    #taz['simp'] = taz.index.astype(str).str[:-1]
    #taz = taz.dissolve(by='simp')
    #taz = taz.set_index('simp', drop=False).to_crs(epsg=3857)
    taz = taz.set_index('taz', drop=True).to_crs(epsg=3857)
    
    taz_with_cluster = gpd.GeoDataFrame(gpd.sjoin(taz,gdf,how='left',op='intersects').groupby('taz_id').agg({'kwh':'sum','kwh_rhcav':'sum','kwh_noncav':'sum','geometry':'first','taz_id':'first'}))

    taz_with_cluster['simp_id'] = taz_with_cluster.index.astype(str).str[:-1]
    taz_with_cluster_simplified = taz_with_cluster.dissolve(by='simp_id',aggfunc='sum')
    taz_with_cluster_simplified['kwh_per_km2'] = taz_with_cluster_simplified['kwh'] / taz_with_cluster_simplified.geometry.area * 1e6
    taz_with_cluster_simplified['kwh_rhcav_per_km2'] = taz_with_cluster_simplified['kwh_rhcav'] / taz_with_cluster_simplified.geometry.area * 1e6
    taz_with_cluster_simplified['kwh_noncav_per_km2'] = taz_with_cluster_simplified['kwh_noncav'] / taz_with_cluster_simplified.geometry.area * 1e6

    
    ncolors = 256
    color_array = plt.get_cmap('Reds')(range(ncolors))
    color_array[:,-1] = np.linspace(0.0,1.0,ncolors)
    map_object = LinearSegmentedColormap.from_list(name='rainbow_alpha',colors=color_array)

    print(taz_with_cluster_simplified['kwh_noncav_per_km2'].sum())
    
    n_bins_rhcav = np.min([np.sum(taz_with_cluster_simplified['kwh_rhcav'] > 0), 7])
    
    fmap = plt.figure(figsize=(7,8))
    ax = plt.gca()
    taz_with_cluster_simplified.plot(column='kwh_rhcav_per_km2',legend=True, ax=ax, cmap = map_object, k=n_bins_rhcav, scheme='fisher_jenks')
    try:
        ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)
    except:
        print(' -- ')
    ax.set_title('Ride-hail CAV demand (kWh/km^2)')
    plt.savefig(outfolder+'/plots/charging_demand_map_depot.png')
    
    n_bins_noncav = np.min([np.sum(taz_with_cluster_simplified['kwh_noncav'] > 0), 7])
    
    fmap = plt.figure(figsize=(7,8))
    ax = plt.gca()
    taz_with_cluster_simplified.plot(column='kwh_noncav_per_km2',legend=True, ax=ax, cmap = map_object, k=n_bins_noncav, scheme='fisher_jenks')
    try:
        ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)
    except:
        print(' -- ')
    ax.set_title('Public Charging demand (kWh/km^2)')
    plt.savefig(outfolder+'/plots/charging_demand_map_public.png')
    
    
    maxsize=500
    maxval = maxsize / np.max([taz_with_cluster['kwh_rhcav'].max(), taz_with_cluster['kwh_noncav'].max()])
    
    
    
    taz_with_cluster['centroid'] = taz_with_cluster.geometry.centroid
    fmap = plt.figure(figsize=(7,8))
    ax = plt.gca()
    taz_with_cluster.plot(legend=False, ax=ax, alpha = 0.1)
    taz_with_cluster2 = taz_with_cluster.set_geometry('centroid')

    taz_with_cluster2.plot(legend=True, ax=ax, markersize = taz_with_cluster2['kwh_rhcav']*maxval, color='b', alpha=0.5, label = 'Depot')
    taz_with_cluster2.plot(legend=True, ax=ax, markersize = taz_with_cluster2['kwh_noncav']*maxval, color='r', alpha=0.7, label = 'Public')
    plt.legend()
    try:
        ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)
    except:
        print(' -- ')
    plt.savefig(outfolder+'/plots/charging_demand_map_both.png')
    
    
    
    return refuelSession_rhcav, refuelSession_noncav, taz_with_cluster
#%%

def Geod_local_coor(Location_1, Location_2):
    # calculate geographical distance between two location with local coordinates 
    # inputs are in m, outputs are in miles
    return ((Location_1[0]-Location_2[0])**2+(Location_1[1]-Location_2[1])**2)**0.5/1000/1.60934


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
    print("Number of charging stations: ", n_clusters)
    print("Max distance", np.percentile(Charge_demand_distance,75), "Mean distance", np.mean(Charge_demand_distance))


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





def generateParking(refuelSessions, tag, Power_rated, n_stations, Max_queuing_probability, taz, outfolder, parking_input = None):
    output_val = dict()
    output_array = dict()
    
    for power in Power_rated:
        output_val[power] = dict()
        output_array[power] = dict()

    for power in Power_rated:
        for prob in Max_queuing_probability:
            output_val[power][prob] = dict()
            output_array[power][prob] = dict()
    
    for ind, n_clusters in enumerate(n_stations):
        iter_out_val, iter_out_array = assignChargingLocations(refuelSessions, n_clusters, Power_rated, Max_queuing_probability)
        for power in Power_rated:
            for prob in Max_queuing_probability:
                output_val[power][prob][n_clusters] = iter_out_val[power][prob]
                output_array[power][prob][n_clusters] = iter_out_array[power][prob]
                new_parking = assignStallsToTaz(iter_out_array[power][prob]['gdf'], taz, 'Public', power, 2.*power, parking_input)
                new_parking['numStalls'] = new_parking['numStalls'].astype('int')
                new_parking.to_csv(outfolder + '/parking_input_files/sf-'+ tag +'-parking-'+str(n_clusters)+'-clusters-'+str(power)[:-2]+'-kW-'+str(prob)+'-prob.csv', index=False)
    return output_val, output_array

#%%
def makeMapPlots(output_array_human, output_array_cav, Power_rated, Max_queuing_probability, outfolder):
    dict_human = output_array_human[Power_rated[0]][Max_queuing_probability[0]]
    dict_cav = output_array_cav[Power_rated[0]][Max_queuing_probability[0]]
    human_keys = list(dict_human.keys())
    maxsize=500
    maxval = maxsize / dict_human[human_keys[0]]['gdf']['vehicle'].max()
    fmap = plt.figure(figsize=(7,8))
    ax = plt.gca()
    ax.set_xlim((-1.3640e7,-1.362e7))
    ax.set_ylim((4537500,4560000))
    keep_bg = False
    circles = [0]
    ax.set_title('Peak Hour Human Charging Events')
    for n_clusters in human_keys:
        clusters = dict_human[n_clusters]['gdf']
        #clusters['centroid'] = clusters.geometry.centroid
        
        if keep_bg:
            circles.get_children().pop(0).remove()
        circles = clusters.plot(legend=True, ax=ax, markersize = clusters['vehicle']*maxval, color='r', alpha=0.75, label = 'Human')
        try:
            if keep_bg == False:
                print('getting bg')
                ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)
        except:
            clusters.plot(legend=False, ax=ax, alpha = 0.1)
        try:
            plt.legend(*circles.get_children()[0].legend_elements("sizes", num=6))
        except:
            print("Couldn't make fancy legend--right version of matplotlib not installed")
        plt.savefig(outfolder+'/plots/public-'+str(n_clusters)+'-stations.png')
        keep_bg = True
    plt.close()
    
    cav_keys = list(dict_cav.keys())
    maxsize=500
    maxval = maxsize / dict_cav[cav_keys[0]]['gdf']['vehicle'].max()
    fmap = plt.figure(figsize=(7,8))
    ax = plt.gca()
    ax.set_xlim((-1.3640e7,-1.362e7))
    ax.set_ylim((4537500,4560000))
    keep_bg = False
    circles = [0]
    ax.set_title('Peak Hour CAV Charging Events')
    for n_clusters in cav_keys:
        clusters = dict_cav[n_clusters]['gdf']
        #clusters['centroid'] = clusters.geometry.centroid
        
        if keep_bg:
            circles.get_children().pop(0).remove()
        circles = clusters.plot(legend=True, ax=ax, markersize = clusters['vehicle']*maxval, color='b', alpha=0.75, label = 'Depot')
        try:
            if keep_bg == False:
                print('getting bg')
                ctx.add_basemap(ax, url=ctx.providers.Stamen.TonerBackground)
        except:
            clusters.plot(legend=False, ax=ax, alpha = 0.1)
        try:
            plt.legend(*circles.get_children()[0].legend_elements("sizes", num=6))
        except:
            print("Couldn't make fancy legend--right version of matplotlib not installed")
        plt.savefig(outfolder+'/plots/depot-'+str(n_clusters)+'-stations.png')
        keep_bg = True
    plt.close()
    
    
    
#%%
  
def makePlots(output_val_human, output_val_cav, Power_rated, Max_queuing_probability, outfolder):
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
    ax1.set_title('Human Ride Hail + Personal')
    
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


if __name__ == '__main__':
    required.installAll()

    inputfile = sys.argv[1]
    try:
        run_purpose = sys.argv[2].lower()
    except:
        run_purpose = "evaluate"
    basefolder = inputfile.rsplit('/', 3)[0]
    parking_out_folder = basefolder + '/parking_output'
    try:
        shutil.rmtree(parking_out_folder)
    except:
        print('No folder exists--creating one')
        
    os.mkdir(parking_out_folder)
    os.mkdir(parking_out_folder+'/plots')
    os.mkdir(parking_out_folder+'/parking_input_files')
    taz_path= inputfile.rsplit('/', 6)[0]+'/test/input/sf-light-demo/shape/sf-light-tazs.shp'
    parking_input = pd.read_csv(inputfile.rsplit('/', 6)[0]+'/test/input/sf-light-demo/sf-taz-parking-base-slow.csv')
    
    print('Loading events file: ' + inputfile)
    print('Loading taz file: ' + taz_path)
    refuelSession_rhcav, refuelSession_noncav, taz = loadEvents(inputfile, taz_path, parking_out_folder)
    if run_purpose == "generate":
        Power_rated = [50.0, 150.0] # in kW
        Max_queuing_probability = [0.1, 0.25, 0.5] # Chance that someone would find their nearest charger full
        nstations_human = np.unique(np.logspace(np.log10(2),np.log10(taz.shape[0]/6),num=20,dtype=int))
        nstations_cav = np.unique(np.logspace(np.log10(2),np.log10(taz.shape[0]/6),num=20,dtype=int))
        
        output_val_human, output_array_human = generateParking(refuelSession_noncav, 'taz', Power_rated, nstations_human, Max_queuing_probability, taz, parking_out_folder, parking_input)
        output_val_cav, output_array_cav = generateParking(refuelSession_rhcav, 'depot', Power_rated, nstations_cav, Max_queuing_probability, taz, parking_out_folder)
        
        makePlots(output_val_human, output_val_cav, Power_rated, Max_queuing_probability, parking_out_folder)
        makeMapPlots(output_array_human, output_array_cav, Power_rated, Max_queuing_probability, parking_out_folder)
    print("done")
