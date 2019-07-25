# -*- coding: utf-8 -*-
import pandas as pd
import glob as glob
import re
import matplotlib.pyplot as plt
import numpy as np

# %%
#filepath = '~/Desktop/SMART_plots_2/'

li = []

PV_types = ['BEV', 'Conventional', 'HEV', 'PHEV']
AutomationLevels = ['L1', 'L3', 'L5']
BodyTypes = ['Car', 'Truck']
Modes = ['bike', 'car', 'drive_transit', 'onDemandRide', 'onDemandRide_pooled', 'onDemandRide_transit', 'walk',
         'walk_transit', 'cav']

data = 'motorizedVehicleMilesTraveled'
delim = '_'

vmt = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vmt.append(data + delim + PV + '-Truck_' + Auto)
        vmt.append(data + delim + PV + '-Car_' + Auto)
        vmt.append(data + delim + PV + delim + Auto)

data = 'motorizedVehicleMilesTraveled_RH'
delim = '_'

vmt_rh = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vmt_rh.append(data + delim + PV + '-Truck_' + Auto)
        vmt_rh.append(data + delim + PV + '-Car_' + Auto)
        vmt_rh.append(data + delim + PV + delim + Auto)

data = 'vehicleHoursTraveled'
vht = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vht.append(data + delim + PV + '-Truck_' + Auto)
        vht.append(data + delim + PV + '-Car_' + Auto)
        vht.append(data + delim + PV + delim + Auto)

data = 'vehicleHoursTraveled_RH'
vht_rh = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vht.append(data + delim + PV + '-Truck_' + Auto)
        vht.append(data + delim + PV + '-Car_' + Auto)
        vht.append(data + delim + PV + delim + Auto)

data = 'numberOfVehicles'
num = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vht.append(data + delim + PV + '-Truck_' + Auto)
        vht.append(data + delim + PV + '-Car_' + Auto)

data = 'numberOfVehicles_RH'
num_rh = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vht.append(data + delim + PV + '-Truck_' + Auto)
        vht.append(data + delim + PV + '-Car_' + Auto)

data = 'personTravelTime'
ptt = []

for mode in Modes:
    ptt.append(data + delim + mode)

data = 'totalCost'
totalcost = []

for mode in Modes:
    totalcost.append(data + delim + mode)

# %%
all_files = sorted(glob.glob('data/smart*.csv'))
keep_columns = ['Scenario', 'Technology', 'Iteration', 'agencyRevenue_BART', 'agentHoursOnCrowdedTransit',
                'averageOnDemandRideWaitTimeInMin', 'averageTripExpenditure', 'motorizedVehicleMilesTraveled_total']

all_columns = keep_columns + vmt + vmt_rh + vht + vht_rh + ptt + num + num_rh + totalcost

li = []
for filename in all_files:
    df = pd.read_csv(filename, index_col=None, header=0)
    str = re.split('-|/', filename)
    print(str)
    df['Scenario'] = str[-2]
    df['Technology'] = str[-1][:2]
    for col in all_columns:
        if col not in df:
            df[col] = 0
    li.append(df[all_columns].tail(1))

df = pd.concat([li[i] for i in [4, 1, 0, 3, 2, 6, 5]], axis=0, ignore_index=True).replace(['ht', 'lt', 'ba'],
                                                                                          ['High Tech', 'Low Tech',
                                                                                           'Base'])
# %%        
all_files = sorted(glob.glob('data/rh*.csv'))

li = []
for filename in all_files:
    rhdf = pd.read_csv(filename, index_col=None, header=0)
    str = re.split('-|/', filename)
    rhdf['Scenario'] = str[2]
    rhdf['Technology'] = str[3][:2]
    li.append(rhdf.tail(1))

rhdf = pd.concat([li[i] for i in [4, 1, 0, 3, 2, 6, 5]], axis=0, ignore_index=True).replace(['ht', 'lt', 'ba'],
                                                                                            ['High Tech', 'Low Tech',


df = pd.concat([df, rhdf], axis=1)
df = df.loc[:, ~df.columns.duplicated()]

# %%

columns = df.columns.values
RH_VMT = columns[df.columns.str.contains('ehicleMilesTraveled_RH')].tolist()
PV_VMT = columns[np.logical_not(df.columns.str.contains('ehicleMilesTraveled_RH')) & df.columns.str.contains(
    'ehicleMilesTraveled') & ~df.columns.str.contains('total')].tolist()

RH_VHT = columns[df.columns.str.contains('ehicleHoursTraveled_RH')].tolist()
PV_VHT = columns[np.logical_not(df.columns.str.contains('ehicleMilesTraveled_RH')) & df.columns.str.contains(
    'ehicleHoursTraveled') & ~df.columns.str.contains('total')].tolist()

df['RH_VMT'] = df[RH_VMT].sum(axis=1)
df['PV_VMT'] = df[PV_VMT].sum(axis=1)

df['RH_VHT'] = df[RH_VHT].sum(axis=1)
df['PV_VHT'] = df[PV_VHT].sum(axis=1)

df['RH_MPH'] = df['RH_VMT'] / df['RH_VHT']
df['PV_MPH'] = df['PV_VMT'] / df['PV_VHT']

df['MPH'] = (df['RH_VMT'] + df['PV_VMT']) / (df['RH_VHT'] + df['PV_VHT'])

df['VMT'] = df['RH_VMT'] + df['PV_VMT']

columns = df.columns.values
Low_VMT = columns[df.columns.str.contains('ehicleMilesTraveled') & (
        df.columns.str.contains('L1') | df.columns.str.contains('Low'))].tolist()
High_VMT = columns[df.columns.str.contains('ehicleMilesTraveled') & df.columns.str.contains('L3')].tolist()
CAV_VMT = columns[df.columns.str.contains('ehicleMilesTraveled') & df.columns.str.contains('L5')].tolist()

PersonalCAV_VMT = columns[
    df.columns.str.contains('ehicleMilesTraveled') & df.columns.str.contains('L5') & ~df.columns.str.contains(
        'RH')].tolist()

df['Low_VMT'] = df[Low_VMT].sum(axis=1)
df['High_VMT'] = df[High_VMT].sum(axis=1)
df['CAV_VMT'] = df[CAV_VMT].sum(axis=1)
df['P_CAV_VMT'] = df[PersonalCAV_VMT].sum(axis=1)


#%%
count = df.columns.str.contains('numberOfVehicles')
RH = df.columns.str.contains('RH_') 
CAV = df.columns.str.contains('L5')
BEV = df.columns.str.contains('BEV')
PHEV = df.columns.str.contains('PHEV')
#df.loc[:,~CAV&RH&count] /= 1.152 Downsample multiple shifts per human driven RH
df['Personal_BEV_NonCAV_Count'] = df.loc[:,~CAV&~RH&count&BEV].sum(axis=1)
df['Personal_BEV_CAV_Count'] = df.loc[:,CAV&~RH&count&BEV].sum(axis=1)
df['RH_BEV_NonCAV_Count'] = np.floor(df.loc[:,~CAV&RH&count&BEV].sum(axis=1))
df['RH_BEV_CAV_Count'] = df.loc[:,CAV&RH&count&BEV].sum(axis=1)
df['Personal_PHEV_NonCAV_Count'] = df.loc[:,~CAV&~RH&count&PHEV].sum(axis=1)
df['Personal_PHEV_CAV_Count'] = df.loc[:,CAV&~RH&count&PHEV].sum(axis=1)
df['RH_PHEV_NonCAV_Count'] = np.floor(df.loc[:,~CAV&RH&count&PHEV].sum(axis=1))
df['RH_PHEV_CAV_Count'] = df.loc[:,CAV&RH&count&PHEV].sum(axis=1)
df['Personal_Other_NonCAV_Count'] = df.loc[:,~CAV&~RH&count&~PHEV&~BEV].sum(axis=1)
df['Personal_Other_CAV_Count'] = df.loc[:,CAV&~RH&count&~PHEV&~BEV].sum(axis=1)
df['RH_Other_NonCAV_Count'] = np.floor(df.loc[:,~CAV&RH&count&~PHEV&~BEV].sum(axis=1))
df['RH_Other_CAV_Count'] = df.loc[:,CAV&RH&count&~PHEV&~BEV].sum(axis=1)
df.iloc[:,-12:].to_csv('vehicle_counts.csv', index=False)
pd.concat([df.iloc[:,:2], df.iloc[:,-12:]],axis=1).to_csv('vehicle_counts.csv', index=False)
# %%
order = [4, 1, 0, 3, 2, 6, 5]
df['cav_empty_miles'] = 0
df['cav_nonempty_miles'] = 0
df['motorized_miles'] = 0
df['passenger_miles'] = 0
all_files = sorted(glob.glob('data/events*.csv.gz'))
rhDictionary = {True: '_RH', False: ''}
cavDictionary = {True: '_CAV', False: ''}


def filter_events(ind, all_files, order, df):
    filename = all_files[order[ind]]
    events = pd.read_csv(filename, index_col=None, header=0)  # , nrows = 50000
    modeChoice = events.loc[events['type'] == 'ModeChoice'].dropna(how='all', axis=1)
    events = events.loc[events['type'] == 'PathTraversal'].dropna(how='all', axis=1)
    # events = events.loc[(events['mode'] != 'bike') & (events['mode'] != 'walk'),:]
    print(events['mode'].unique())
    events['isRideHail'] = events['vehicleType'].str.startswith('RH')
    events['isCAV'] = events['vehicleType'].str.endswith('L5')
    events['gallons'] = (events['primaryFuel'] + events['secondaryFuel']) * 8.3141841e-9
    events['miles'] = events['length'] / 1609.34
    cav = events['driver'].str.startswith('cav')
    df.loc[ind, 'cav_empty_miles'] = events.loc[(events['numPassengers'] == 0) & cav, 'length'].sum() / 1609.34
    df.loc[ind, 'cav_nonempty_miles'] = events.loc[(events['numPassengers'] > 0) & cav, 'length'].sum() / 1609.34
    events['numTravelers'] = events['numPassengers']
    events.loc[(events['mode'] == 'car') & (events['driver'].str.isnumeric()), 'numTravelers'] += 1
    events.loc[(events['mode'] == 'walk') | (events['mode'] == 'bike'), 'numTravelers'] += 1
    events['vehicle_miles'] = events['length'] / 1609.34
    events['passenger_miles'] = events['vehicle_miles'] * events['numTravelers']
    events.loc[
        (events['mode'] != 'car') & (events['mode'] != 'bike') & (events['mode'] != 'walk'), 'vehicle_miles'] /= 5
    df.loc[ind, 'vehicle_miles'] = events['vehicle_miles'].sum()
    df.loc[ind, 'passenger_miles'] = events['passenger_miles'].sum()
    newdf = events.groupby(['mode', 'isRideHail', 'isCAV'])['vehicle_miles'].sum().reset_index()
    newdf.index = pd.Index(
        'VMT_' + newdf['mode'] + newdf['isRideHail'].map(rhDictionary) + newdf['isCAV'].map(cavDictionary))
    df.loc[ind, 'totalEnergy_gasoline'] = events.loc[
        (events['mode'] == 'car') & (events['primaryFuelType'] == 'Gasoline'), 'primaryFuel'].sum()
    df.loc[ind, 'totalEnergy_gasoline'] += events.loc[
        (events['mode'] == 'car') & (events['secondaryFuelType'] == 'Gasoline'), 'secondaryFuel'].sum()
    df.loc[ind, 'totalEnergy_electricity'] = events.loc[
        (events['mode'] == 'car') & (events['primaryFuelType'] == 'Electricity'), 'primaryFuel'].sum()
    a = newdf['vehicle_miles'].transpose()
    for col in a.index.values:
        if col not in df:
            df[col] = 0
    #    a['VMT_car_RH_empty'] = 1.5
    df.loc[ind, a.index.values] = a.values
    newdf = events.groupby(['mode', 'isRideHail', 'isCAV'])['passenger_miles'].sum().reset_index()
    newdf.index = pd.Index(
        'PMT_' + newdf['mode'] + newdf['isRideHail'].map(rhDictionary) + newdf['isCAV'].map(cavDictionary))
    a = newdf['passenger_miles'].transpose()
    for col in a.index.values:
        if col not in df:
            df[col] = 0
    df.loc[ind, a.index.values] = a.values
    newdf = events.groupby(['mode', 'isRideHail', 'isCAV'])['gallons'].sum().reset_index()
    newdf.index = pd.Index(
        'Gallons_' + newdf['mode'] + newdf['isRideHail'].map(rhDictionary) + newdf['isCAV'].map(cavDictionary))
    a = newdf['gallons'].transpose()
    for col in a.index.values:
        if col not in df:
            df[col] = 0
    #    a['VMT_car_RH_empty'] = 1.5
    df.loc[ind, a.index.values] = a.values
    if 'VMT_car_RH_empty' not in df:
        df['VMT_car_RH_empty'] = 0
    df.loc[ind, 'VMT_car_RH_empty'] = events.loc[
        (events['mode'] == 'car') & events['isRideHail'] & (events['numTravelers'] == 0), 'vehicle_miles'].sum()
    if 'VMT_car_RH_pooled' not in df:
        df['VMT_car_RH_pooled'] = 0
    df.loc[ind, 'VMT_car_RH_pooled'] = events.loc[
        (events['mode'] == 'car') & events['isRideHail'] & (events['numTravelers'] > 1), 'vehicle_miles'].sum()
    if 'VMT_cav_empty' not in df:
        df['VMT_cav_empty'] = 0
    df.loc[ind, 'VMT_cav_empty'] = events.loc[(events['mode'] == 'car') & ~events['isRideHail'] & events['isCAV'] & (
            events['numTravelers'] == 0), 'vehicle_miles'].sum()
    if 'VMT_cav_shared' not in df:
        df['VMT_cav_shared'] = 0
    df.loc[ind, 'VMT_cav_shared'] = events.loc[(events['mode'] == 'car') & ~events['isRideHail'] & events['isCAV'] & (
            events['numTravelers'] > 1), 'vehicle_miles'].sum()
    modeChoiceCounts = modeChoice.groupby('mode')['person'].count()
    print(modeChoiceCounts)
    for col in modeChoiceCounts.index.values:
        if col + '_counts' not in df:
            df[col + '_counts'] = 0
        df.loc[ind, col + '_counts'] = modeChoiceCounts[col]
    vehicles = events.groupby('vehicle')[['miles', 'gallons', 'vehicleType']].agg({'miles': 'sum',
                                                                                   'gallons': 'sum',
                                                                                   'vehicleType': 'max'})
    vehicles = vehicles.loc[
        ~vehicles['vehicleType'].str.startswith('BUS') & ~vehicles['vehicleType'].str.startswith('Bi')]
    return df


for ind in np.arange(7):
    df = filter_events(ind, all_files, order, df)
    
df['rh_empty_miles'] = df['deadHeadingVKT'] / 1.60934
df['rh_nonempty_miles'] = df['RH_VMT'] - df['rh_empty_miles']
#%%

df.to_csv('final_output.csv')