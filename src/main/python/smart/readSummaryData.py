# -*- coding: utf-8 -*-
import pandas as pd
import glob as glob
import re
import matplotlib.pyplot as plt
import numpy as np
import matplotlib.patches as mpatches
import matplotlib.lines as mlines
plt.style.use('seaborn-colorblind')
#plt.style.use('ggplot')
plt.rcParams['axes.edgecolor']='black'
plt.rcParams['axes.facecolor']='white'
plt.rcParams['savefig.facecolor']='white'
plt.rcParams['savefig.edgecolor']='black'

colors =  {'blue':'#377eb8','green':'#227222','orange':'#C66200','purple':'#470467','red':'#B30C0C','yellow':'#C6A600','light.green':'#C0E0C0','magenta':'#D0339D','dark.blue':'#23128F','brown':'#542D06','grey':'#8A8A8A','dark.grey':'#2D2D2D','light.yellow':'#FFE664','light.purple':'#9C50C0','light.orange':'#FFB164','black':'#000000'}
mode_colors = {'Ride Hail':colors['red'],'Car':colors['grey'],'Walk':colors['green'],'Transit':colors['blue'],'Ride Hail - Transit':colors['light.purple'],'Ride Hail - Pooled':colors['purple'],'CAV':colors['light.yellow'],'Bike':colors['light.orange']}
# %%

li = []

PV_types = ['BEV', 'ConventionalDiesel','ConventionalGas','ConventionalGas-48V','HEV','PHEV']
AutomationLevels = ['Low','High','CAV']
Modes = ['bike','car','drive_transit','onDemandRide','onDemandRide_pooled','onDemandRide_transit','walk','walk_transit','cav']

data = 'motorizedVehicleMilesTraveled'
delim = '_'

vmt = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vmt.append(data + delim + PV + delim + Auto)

data = 'motorizedVehicleMilesTraveled_RH'
delim = '_'

vmt_rh = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vmt_rh.append(data + delim + PV + delim + Auto)

data = 'vehicleHoursTraveled'      
vht = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vht.append(data + delim + PV + delim + Auto)
        
data = 'vehicleHoursTraveled_RH'      
vht_rh = []

for PV in PV_types:
    for Auto in AutomationLevels:
        vht.append(data + delim + PV + delim + Auto)
        
data = 'personTravelTime'
ptt = []

for mode in Modes:
    ptt.append(data+delim+mode)
    
data = 'totalCost'
totalcost = []

for mode in Modes:
    totalcost.append(data+delim+mode)

        
# %%        
all_files = sorted(glob.glob('Outputs/smart*.csv'))
keep_columns = ['Scenario','Technology','Iteration','agencyRevenue_BART','agentHoursOnCrowdedTransit',
                'averageOnDemandRideWaitTimeInMin','averageTripExpenditure','motorizedVehicleMilesTraveled_total']

all_columns = keep_columns + vmt + vmt_rh + vht + vht_rh + ptt + totalcost

li = []
for filename in all_files:
    df = pd.read_csv(filename, index_col=None, header=0)
    str = re.split('-|/',filename)
    print(str)
    df['Scenario'] = str[-2]
    df['Technology'] = str[-1][:2]
    for col in all_columns:
        if col not in df:
            df[col] = 0
    li.append(df[all_columns].tail(1))

df = pd.concat([li[i] for i in [4,1,0,3,2,6,5]], axis=0, ignore_index=True).replace(['ht','lt','ba'],['High Tech','Low Tech','Base'])
    
    
# %%        
all_files = sorted(glob.glob('Outputs/rh*.csv'))

li = []
for filename in all_files:
    rhdf = pd.read_csv(filename, index_col=None, header=0)
    str = re.split('-|/',filename)
    rhdf['Scenario'] = str[2]
    rhdf['Technology'] = str[3][:2]
    li.append(rhdf.tail(1))

rhdf = pd.concat([li[i] for i in [4,1,0,3,2,6,5]], axis=0, ignore_index=True).replace(['ht','lt','ba'],['High Tech','Low Tech','Base'])
#df = df.reindex([4,1,0,3,2,6,5])

    
df = pd.concat([df, rhdf],axis=1)
df = df.loc[:,~df.columns.duplicated()]



# %%
    
columns = df.columns.values
RH_VMT = columns[df.columns.str.contains('ehicleMilesTraveled_RH')].tolist()
PV_VMT = columns[np.logical_not(df.columns.str.contains('ehicleMilesTraveled_RH')) & df.columns.str.contains('ehicleMilesTraveled') & ~df.columns.str.contains('total')].tolist()

RH_VHT = columns[df.columns.str.contains('ehicleHoursTraveled_RH')].tolist()
PV_VHT = columns[np.logical_not(df.columns.str.contains('ehicleMilesTraveled_RH')) & df.columns.str.contains('ehicleHoursTraveled') & ~df.columns.str.contains('total')].tolist()

df['RH_VMT'] = df[RH_VMT].sum(axis=1)
df['PV_VMT'] = df[PV_VMT].sum(axis=1)

df['RH_VHT'] = df[RH_VHT].sum(axis=1)
df['PV_VHT'] = df[PV_VHT].sum(axis=1)

df['RH_MPH'] = df['RH_VMT'] / df['RH_VHT']
df['PV_MPH'] = df['PV_VMT'] / df['PV_VHT']

df['MPH'] = (df['RH_VMT'] + df['PV_VMT']) / (df['RH_VHT'] + df['PV_VHT'])

df['VMT'] = df['RH_VMT'] + df['PV_VMT']

columns = df.columns.values
Low_VMT = columns[df.columns.str.contains('ehicleMilesTraveled') & df.columns.str.contains('Low')].tolist()
High_VMT = columns[df.columns.str.contains('ehicleMilesTraveled') & df.columns.str.contains('High')].tolist()
CAV_VMT = columns[df.columns.str.contains('ehicleMilesTraveled') & df.columns.str.contains('CAV')].tolist()

PersonalCAV_VMT = columns[df.columns.str.contains('ehicleMilesTraveled') & df.columns.str.contains('CAV') & ~df.columns.str.contains('RH')].tolist()

df['Low_VMT'] = df[Low_VMT].sum(axis=1)
df['High_VMT'] = df[High_VMT].sum(axis=1)
df['CAV_VMT'] = df[CAV_VMT].sum(axis=1)
df['P_CAV_VMT'] = df[PersonalCAV_VMT].sum(axis=1)

#%%
order = [4,1,0,3,2,6,5]
df['cav_empty_miles'] = 0
df['cav_nonempty_miles'] = 0
df['motorized_miles'] = 0
df['passenger_miles'] = 0
all_files = sorted(glob.glob('Outputs/events*.csv.gz'))
rhDictionary = {True: '_RH', False: ''}
cavDictionary = {True: '_CAV', False: ''}

def filter_events(ind, all_files, order, df):
    filename = all_files[order[ind]]
    events = pd.read_csv(filename, index_col=None, header=0) #, nrows = 50000
    modeChoice = events.loc[events['type'] == 'ModeChoice'].dropna(how='all', axis=1)
    events = events.loc[events['type'] == 'PathTraversal'].dropna(how='all', axis=1)
    #events = events.loc[(events['mode'] != 'bike') & (events['mode'] != 'walk'),:]
    print(events['mode'].unique())
    events['isRideHail'] = events['vehicleType'].str.startswith('RH')
    events['isCAV'] = events['vehicleType'].str.endswith('CAV')
    if ((order[ind] == 3) | (order[ind] == 6)):
        print(filename)
        events.loc[(events['vehicleType'].str.contains('ConventionalGas')) & (~events['vehicleType'].str.contains('48V')), 'primaryFuel'] = events.loc[(events['vehicleType'].str.contains('ConventionalGas')) & (~events['vehicleType'].str.contains('48V')), 'length'] * 74736.4659209 / 55.0
    if order[ind] == 1:
        events.loc[events['vehicleType'].str.contains('48V'), 'primaryFuel'] = events.loc[events['vehicleType'].str.contains('48V') , 'length'] * 74736.4659209 / 60.0   
    events['gallons'] = (events['primaryFuel'] + events['secondaryFuel'])*8.3141841e-9
    events['joules'] = (events['primaryFuel'] + events['secondaryFuel'])
    events['miles'] = events['length']/1609.34
    cav = events['driver'].str.startswith('cav')
    df.loc[ind,'cav_empty_miles'] = events.loc[(events['numPassengers']==0) & cav,'length'].sum()/1609.34
    df.loc[ind,'cav_nonempty_miles'] = events.loc[(events['numPassengers']>0) & cav,'length'].sum()/1609.34
    events['numTravelers'] = events['numPassengers']
    events.loc[(events['mode']== 'car') & (events['driver'].str.isnumeric()),'numTravelers'] += 1
    events.loc[(events['mode']== 'walk') | (events['mode']== 'bike') ,'numTravelers'] += 1
    events['vehicle_miles'] = events['length']/1609.34
    events['passenger_miles'] = events['vehicle_miles'] * events['numTravelers']
    events.loc[(events['mode'] != 'car') & (events['mode'] != 'bike') & (events['mode'] != 'walk'),'vehicle_miles'] /= 5
    df.loc[ind,'vehicle_miles'] = events['vehicle_miles'].sum()
    df.loc[ind,'passenger_miles'] = events['passenger_miles'].sum()
    newdf = events.groupby(['mode','isRideHail','isCAV'])['vehicle_miles'].sum().reset_index()
    newdf.index = pd.Index('VMT_' + newdf['mode'] + newdf['isRideHail'].map(rhDictionary) + newdf['isCAV'].map(cavDictionary))
    a = newdf['vehicle_miles'].transpose()
    for col in a.index.values:
        if col not in df:
            df[col] = 0
#    a['VMT_car_RH_empty'] = 1.5
    df.loc[ind,a.index.values] = a.values
    newdf = events.groupby(['mode','isRideHail','isCAV'])['passenger_miles'].sum().reset_index()
    newdf.index = pd.Index('PMT_' + newdf['mode'] + newdf['isRideHail'].map(rhDictionary) + newdf['isCAV'].map(cavDictionary))
    a = newdf['passenger_miles'].transpose()
    for col in a.index.values:
        if col not in df:
            df[col] = 0
    df.loc[ind,a.index.values] = a.values
    newdf = events.groupby(['mode','isRideHail','isCAV'])['gallons'].sum().reset_index()
    newdf.index = pd.Index('Gallons_' + newdf['mode'] + newdf['isRideHail'].map(rhDictionary) + newdf['isCAV'].map(cavDictionary))
    a = newdf['gallons'].transpose()
    for col in a.index.values:
        if col not in df:
            df[col] = 0
#    a['VMT_car_RH_empty'] = 1.5
    df.loc[ind,a.index.values] = a.values
    if 'VMT_car_RH_empty' not in df:
        df['VMT_car_RH_empty'] = 0
    df.loc[ind,'VMT_car_RH_empty'] = events.loc[(events['mode']== 'car') & events['isRideHail'] & (events['numTravelers'] == 0),'vehicle_miles'].sum()
    if 'VMT_car_RH_pooled' not in df:
        df['VMT_car_RH_pooled'] = 0
    df.loc[ind,'VMT_car_RH_pooled'] = events.loc[(events['mode']== 'car') & events['isRideHail'] & (events['numTravelers'] >1),'vehicle_miles'].sum()
    if 'VMT_cav_empty' not in df:
        df['VMT_cav_empty'] = 0
    df.loc[ind,'VMT_cav_empty'] = events.loc[(events['mode']== 'car') & ~events['isRideHail'] & events['isCAV'] & (events['numTravelers'] == 0),'vehicle_miles'].sum()
    modeChoiceCounts = modeChoice.groupby('mode')['person'].count()
    print(modeChoiceCounts)
    for col in modeChoiceCounts.index.values:
        if col + '_counts' not in df:
            df[col + '_counts'] = 0
        df.loc[ind,col + '_counts'] = modeChoiceCounts[col]
    vehicles = events.groupby('vehicle')[['vehicleType','miles','gallons','primaryFuel','primaryFuelType','secondaryFuel','secondaryFuelType']].agg({'miles':'sum', 
                         'gallons':'sum',
                         'vehicleType':'max',
                         'primaryFuel':'sum',
                         'primaryFuelType':'max',
                         'secondaryFuel':'sum',
                         'secondaryFuelType':'max'})
    print(vehicles.columns.values)
    vehicles = vehicles.loc[~vehicles['vehicleType'].str.startswith('BUS') & ~vehicles['vehicleType'].str.startswith('Bi') & ~vehicles['vehicleType'].str.startswith('Bo') & ~vehicles['vehicleType'].str.contains('DEFAULT')& ~vehicles['vehicleType'].str.contains('FERRY')& ~vehicles['vehicleType'].str.contains('RAIL')]
    if np.any(vehicles['gallons']>0):
        vehicles = vehicles.loc[vehicles['gallons']>0]
        vehicles['mpg'] = vehicles['miles']/vehicles['gallons']
        vehicles['mpg'].hist(by=vehicles['vehicleType'],figsize=(18,18))
        plt.savefig('Plots/vehicles-'+filename[15:-8]+'.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
    vehicleTypes = vehicles.groupby('vehicleType')[['miles','gallons','primaryFuel','primaryFuelType','secondaryFuel','secondaryFuelType']].agg({'miles':['sum','count'], 
                         'gallons':'sum',
                         'primaryFuel':'sum',
                         'primaryFuelType':'max',
                         'secondaryFuel':'sum',
                         'secondaryFuelType':'max'})
    vehicleTypes.columns = [' '.join(col).strip() for col in vehicleTypes.columns.values]
    for row in vehicleTypes.index.values:
        for col in vehicleTypes.columns:
            if row + '_' + col not in df:
                df[row + '_' + col] = 0
            df.loc[ind,row + '_' + col] = vehicleTypes.loc[row,col]
    return df

for ind in np.arange(7):
    df = filter_events(ind,all_files,order,df)

df['rh_empty_miles'] = df['deadHeadingVKT']/1.60934
df['rh_nonempty_miles'] = df['RH_VMT'] - df['rh_empty_miles'] 

# %%
columns = df.columns

df['totalMiles_gasoline'] = 0
df['totalMiles_diesel'] = 0
df['totalMiles_electricity'] = 0
df['totalEnergy_gasoline'] = 0
df['totalEnergy_diesel'] = 0
df['totalEnergy_electricity'] = 0
df['totalMiles_Low'] = 0
df['totalMiles_High'] = 0
df['totalMiles_CAV'] = 0
df['totalEnergy_Low'] = 0
df['totalEnergy_High'] = 0
df['totalEnergy_CAV'] = 0
df['totalVehicles_Gas'] = 0
df['totalVehicles_Diesel'] = 0
df['totalVehicles_48V'] = 0
df['totalVehicles_PHEV'] = 0
df['totalVehicles_HEV'] = 0
df['totalVehicles_BEV'] = 0

for column in columns:
    if column.startswith('motorizedVehicleMilesTraveled'):
        try:
            vehicleName = column.split('_',1)[1]
            
            if 'ConventionalGas' in vehicleName:
                print(vehicleName)
                df.loc[:,'totalMiles_gasoline'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_gasoline'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
            elif 'PHEV' in vehicleName:
                df.loc[:,'totalMiles_gasoline'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_gasoline'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
            elif 'HEV' in vehicleName:
                df.loc[:,'totalMiles_electricity'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_electricity'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
                df.loc[:,'totalEnergy_gasoline'] += df.loc[:,'Energy_'+vehicleName+'_secondaryFuel_sum']
            elif 'Diesel' in vehicleName:
                df.loc[:,'totalMiles_diesel'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_diesel'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
            elif 'BEV' in vehicleName:
                df.loc[:,'totalMiles_electricity'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_electricity'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
            if 'Low' in vehicleName:
                df.loc[:,'totalMiles_Low'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_Low'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
                df.loc[:,'totalEnergy_Low'] += df.loc[:,'Energy_'+vehicleName+'_secondaryFuel_sum']
            if 'High' in vehicleName:
                df.loc[:,'totalMiles_High'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_High'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
                df.loc[:,'totalEnergy_High'] += df.loc[:,'Energy_'+vehicleName+'_secondaryFuel_sum']
            if 'CAV' in vehicleName:
                df.loc[:,'totalMiles_CAV'] += df.loc[:,'Energy_'+vehicleName+'_miles_sum']
                df.loc[:,'totalEnergy_CAV'] += df.loc[:,'Energy_'+vehicleName+'_primaryFuel_sum']
                df.loc[:,'totalEnergy_CAV'] += df.loc[:,'Energy_'+vehicleName+'_secondaryFuel_sum']
            print(columns[columns.str.startswith('Energy_' + vehicleName)])
            if '48V' in vehicleName:
                print(vehicleName)
                df.loc[:,'totalVehicles_48V'] += df.loc[:,vehicleName+'_miles count']
            elif 'ConventionalGas' in vehicleName:
                print(vehicleName)
                df.loc[:,'totalVehicles_Gas'] += df.loc[:,vehicleName+'_miles count']
            elif 'PHEV' in vehicleName:
                df.loc[:,'totalVehicles_PHEV'] += df.loc[:,vehicleName+'_miles count']
            elif 'HEV' in vehicleName:
                df.loc[:,'totalVehicles_HEV'] += df.loc[:,vehicleName+'_miles count']
            elif 'Diesel' in vehicleName:
                df.loc[:,'totalVehicles_Diesel'] += df.loc[:,vehicleName+'_miles count']
            elif 'BEV' in vehicleName:
                df.loc[:,'totalVehicles_BEV'] += df.loc[:,vehicleName+'_miles count']
        except:
            print('oops')
            
df['totalEnergy_LDV'] = df['totalEnergy_Low'] + df['totalEnergy_High'] + df['totalEnergy_CAV']
df['totalMiles_LDV'] = df['totalMiles_Low'] + df['totalMiles_High'] + df['totalMiles_CAV']
# %% END READING DATA

#df.to_csv('all_data.csv', index=False)
#df = pd.read_csv('all_data.csv') #UNCOMMENT THIS TO START WITH ALREADY-COMPILED DATA
    
#%% BEGIN MAKE PLOTS
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
populations = [612597,612598,612598,755015,755006,755022,754962]
names= ['Base','Low','High','Low','High','Low','High']
height_Low = df['totalMiles_Low'].values/populations
height_High = df['totalMiles_High'].values/populations
height_CAV = df['totalMiles_CAV'].values/populations
height_all = height_Low + height_High + height_CAV
plt_Low = plt.bar(x=xpos,height=height_Low,color=colors['blue'])
plt_High = plt.bar(x=xpos,height=height_High,bottom=height_Low,color=colors['green'])
plt_CAV = plt.bar(x=xpos,height=height_CAV,bottom=height_Low + height_High,color=colors['red'])
plt.xticks([1,3,5.5,8],['base','a','b','c'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch = '///')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 1,names[ind],ha='center')
ax.set_ylim((0,40))
plt.ylabel('Light Duty Vehicle Miles per Capita')
plt.legend((plt_CAV,plt_High,plt_Low),('CAV','Partial Automation','Low Automation'),bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/vmt_percapita_tech.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
populations = [612597,612598,612598,755015,755006,755022,754962]
names= ['Base','Low','High','Low','High','Low','High']
height_Low = df['totalMiles_Low'].values/50000
height_High = df['totalMiles_High'].values/50000
height_CAV = df['totalMiles_CAV'].values/50000
height_all = height_Low + height_High + height_CAV
plt_Low = plt.bar(x=xpos,height=height_Low,color=colors['blue'])
plt_High = plt.bar(x=xpos,height=height_High,bottom=height_Low,color=colors['green'])
plt_CAV = plt.bar(x=xpos,height=height_CAV,bottom=height_Low + height_High,color=colors['red'])
plt.xticks([1,3,5.5,8],['base','a','b','c'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch = '///')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 2,names[ind],ha='center')
#ax.set_ylim((0,40))
plt.ylabel('Light Duty Vehicle Miles (millions)')
plt.legend((plt_CAV,plt_High,plt_Low),('CAV','Partial Automation','Low Automation'),bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/vmt_tech.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_RH = df['RH_VMT'].values/50000
height_RH_Empty = df['rh_empty_miles'].values/50000
height_PV = df['PV_VMT'].values/50000
height_PV_Empty = df['cav_empty_miles'].values/50000
height_all = height_RH + height_PV
plt_rh = plt.bar(x=xpos,height=height_RH,color=mode_colors['Ride Hail'])
rh_empty = plt.bar(x=xpos,height=height_RH_Empty,hatch='///',fill=False)
plt_pv = plt.bar(x=xpos,height=height_PV,bottom=height_RH,color=mode_colors['Car'])
pv_empty = plt.bar(x=xpos,height=height_PV_Empty,bottom=height_RH,hatch='///',fill=False)
plt.xticks([1,3,5.5,8],['base','a','b','c'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch = '///')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 2,names[ind],ha='center')
ax.set_ylim((0,550))
plt.ylabel('Light Duty Vehicle Miles Traveled (millions)')
plt.legend((plt_pv,plt_rh,empty),('Personal Vehicle','Ridehail','Empty'),bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/vmt_rh_empty.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Gas = df['totalEnergy_gasoline'].values*20/1000000000000
height_Diesel = df['totalEnergy_diesel'].values*20/1000000000000
height_Electricity = df['totalEnergy_electricity'].values*20/1000000000000
height_all = height_Gas + height_Diesel + height_Electricity
plt_Gas = plt.bar(x=xpos,height=height_Gas,color=colors['purple'])
plt_Diesel = plt.bar(x=xpos,height=height_Diesel,bottom = height_Gas, color=colors['magenta'])
plt_Electricity = plt.bar(x=xpos,height=height_Electricity,bottom = height_Gas + height_Diesel, color=colors['yellow'])
plt.xticks([1,3,5.5,8],['base','a','b','c'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)

ax = plt.gca()
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 15,names[ind],ha='center')
plt.ylabel('Light Duty Vehicle Energy (TJ)')
ax.set_ylim((0,1300))
plt.legend((plt_Electricity,plt_Diesel,plt_Gas),('Electricity','Diesel','Gasoline'),bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/energy_fuelsource.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')


#%%
populations = [612597,612598,612598,755015,755006,755022,754962]
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Gas = df['totalEnergy_gasoline'].values*20/1000000000/populations
height_Diesel = df['totalEnergy_diesel'].values*20/1000000000/populations
height_Electricity = df['totalEnergy_electricity'].values*20/1000000000/populations
height_all = height_Gas + height_Diesel + height_Electricity
plt_Gas = plt.bar(x=xpos,height=height_Gas,color=colors['purple'])
plt_Diesel = plt.bar(x=xpos,height=height_Diesel,bottom = height_Gas, color=colors['magenta'])
plt_Electricity = plt.bar(x=xpos,height=height_Electricity,bottom = height_Gas + height_Diesel, color=colors['yellow'])
plt.xticks([1,3,5.5,8],['base','a','b','c'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)

ax = plt.gca()
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.02,names[ind],ha='center')
plt.ylabel('Light Duty Vehicle Energy per Capita (GJ)')
#ax.set_ylim((0,310))
plt.legend((plt_Electricity,plt_Diesel,plt_Gas),('Electricity','Diesel','Gasoline'),bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/energy_fuelsource_percapita.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
populations = [612597,612598,612598,755015,755006,755022,754962]
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Gas = df['totalMiles_gasoline'].values/populations
height_Diesel = df['totalMiles_diesel'].values/populations
height_Electricity = df['totalMiles_electricity'].values/populations
height_all = height_Gas + height_Diesel + height_Electricity
plt_Gas = plt.bar(x=xpos,height=height_Gas,color=colors['purple'])
plt_Diesel = plt.bar(x=xpos,height=height_Diesel,bottom = height_Gas, color=colors['magenta'])
plt_Electricity = plt.bar(x=xpos,height=height_Electricity,bottom = height_Gas + height_Diesel, color=colors['yellow'])
plt.xticks([1,3,5.5,8],['base','a','b','c'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)

ax = plt.gca()
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 1,names[ind],ha='center')
plt.ylabel('Light Duty Vehicle Miles per Capita')
ax.set_ylim((0,40))
plt.legend((plt_Electricity,plt_Diesel,plt_Gas),('Electricity','Diesel','Gasoline'),bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/vmt_fuelsource_percapita.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['personTravelTime_drive_transit'].values/50000/60 + df['personTravelTime_onDemandRide_transit'].values/50000/60 + df['personTravelTime_walk_transit'].values/50000/60
height_Car = df['personTravelTime_car'].values/50000/60 
height_Cav = df['personTravelTime_cav'].values/50000/60
height_RideHail = df['personTravelTime_onDemandRide'].values/50000/60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/50000/60
height_nonMotorized = df['personTravelTime_walk'].values/50000/60 + df['personTravelTime_bike'].values/50000/60
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=xpos,height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav, color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit+ height_Cav, color=mode_colors['Ride Hail - Transit'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_RideHailPooled + height_Cav, color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_rhp,plt_nm),('Car','CAV','Transit','Ridehail','Ridehail (Pooled)','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.3,names[ind],ha='center')
ax.set_ylim((0,45))
plt.ylabel('Person Hours Traveled (millions)')
plt.savefig('Plots/pht.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
populations = [612597,612598,612598,755015,755006,755022,754962]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['personTravelTime_drive_transit'].values/populations/60 + df['personTravelTime_onDemandRide_transit'].values/populations/60 + df['personTravelTime_walk_transit'].values/populations/60
height_Car = df['personTravelTime_car'].values/populations/60 
height_Cav = df['personTravelTime_cav'].values/populations/60
height_RideHail = df['personTravelTime_onDemandRide'].values/populations/60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/populations/60
height_nonMotorized = df['personTravelTime_walk'].values/populations/60 + df['personTravelTime_bike'].values/populations/60
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav

plt_car = plt.bar(x=xpos,height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav, color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit+ height_Cav, color=mode_colors['Ride Hail - Transit'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_RideHailPooled + height_Cav, color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_rhp,plt_nm),('Car','CAV','Transit','Ridehail','Ridehail (Pooled)','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.025,names[ind],ha='center')
ax.set_ylim((0,3.2))
plt.ylabel('Person Hours Traveled (per capita)')
plt.savefig('Plots/pht_percapita.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
populations = [612597,612598,612598,755015,755006,755022,754962]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['personTravelTime_drive_transit'].values/populations/60 + df['personTravelTime_onDemandRide_transit'].values/populations/60 + df['personTravelTime_walk_transit'].values/populations/60
height_Car = df['personTravelTime_car'].values/populations/60 
height_Cav = df['personTravelTime_cav'].values/populations/60
height_RideHail = df['personTravelTime_onDemandRide'].values/populations/60
height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/populations/60
height_nonMotorized = df['personTravelTime_walk'].values/populations/60 + df['personTravelTime_bike'].values/populations/60
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav

plt_transit = plt.bar(x=xpos, height=height_Transit, color=mode_colors['Transit'])
plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit, color=mode_colors['Ride Hail - Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_RideHailPooled, color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Transit + height_RideHailPooled + height_RideHail, color=mode_colors['CAV'])
plt_car = plt.bar(x=xpos,height=height_Car,bottom=height_Transit + height_RideHailPooled + height_RideHail + height_Cav, color=mode_colors['Car'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_RideHailPooled + height_Cav, color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_transit,plt_rhp,plt_rh,plt_cav,plt_car,plt_nm),('Transit','Ride Hail (Pooled)','Ride Hail','CAV','Car','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.025,names[ind],ha='center')
ax.set_ylim((0,3.0))
plt.ylabel('Person Hours Traveled (per capita)')
plt.savefig('Plots/pht_percapita_reorder.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['drive_transit_counts'].values/50000 + df['ride_hail_transit_counts'].values/50000 + df['walk_transit_counts'].values/50000
height_Car = df['car_counts'].values/50000
height_Cav = df['cav_counts'].values/50000
height_RideHail = df['ride_hail_counts'].values/50000
height_RideHailPooled = df['ride_hail_pooled_counts'].values/50000
height_nonMotorized = df['walk_counts'].values/50000/60 + df['bike_counts'].values/50000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav
height_Transit /= height_all
height_Car /= height_all
height_Cav /= height_all
height_RideHail /= height_all
height_RideHailPooled /= height_all
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000/60
height_nonMotorized /= height_all


plt_car = plt.bar(x=xpos,height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav, color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit+ height_Cav, color=mode_colors['Ride Hail - Pooled'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom=height_RideHailPooled+ height_Car + height_Transit + height_RideHail+ height_Cav, color=mode_colors['Bike'])
#plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit + height_Car+ height_Cav,hatch='///',fill=False)

plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_rhp,plt_nm),('Car','CAV','Transit','Ridehail','Ridehail Pooled','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],1.02,names[ind],ha='center')
ax.set_ylim((0,1.0))
plt.ylabel('Portion of Trips')
plt.savefig('Plots/modesplit.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['drive_transit_counts'].values/50000 + df['ride_hail_transit_counts'].values/50000 + df['walk_transit_counts'].values/50000
height_Car = df['car_counts'].values/50000
height_Cav = df['cav_counts'].values/50000
height_RideHail = df['ride_hail_counts'].values/50000
height_RideHailPooled = df['ride_hail_pooled_counts'].values/50000
height_nonMotorized = df['walk_counts'].values/50000/60 + df['bike_counts'].values/50000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_RideHailPooled + height_Cav
height_Transit /= height_all
height_Car /= height_all
height_Cav /= height_all
height_RideHail /= height_all
height_RideHailPooled /= height_all
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000/60
height_nonMotorized /= height_all

plt_transit = plt.bar(x=xpos,height=height_Transit, color=mode_colors['Transit'])
plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit, color=mode_colors['Ride Hail - Pooled'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_RideHailPooled, color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Transit + height_RideHailPooled + height_RideHail, color=mode_colors['CAV'])
plt_car = plt.bar(x=xpos,height=height_Car,bottom=height_Transit + height_RideHailPooled + height_RideHail + height_Cav  , color=mode_colors['Car'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom=height_RideHailPooled+ height_Car + height_Transit + height_RideHail+ height_Cav, color=mode_colors['Bike'])
#plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit + height_Car+ height_Cav,hatch='///',fill=False)

plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_transit,plt_rhp,plt_rh,plt_cav,plt_car,plt_nm),('Transit','Ride Hail (Pooled)','Ride Hail','CAV','Car','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],1.02,names[ind],ha='center')
ax.set_ylim((0,1.0))
plt.ylabel('Portion of Trips')
plt.savefig('Plots/modesplit_reorder.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')


#%%
plt.figure(figsize=(5,6)) 
populations = [612597,612598,612598,755015,755006,755022,754962]
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['drive_transit_counts'].values/populations + df['ride_hail_transit_counts'].values/populations + df['walk_transit_counts'].values/populations
height_Car = df['car_counts'].values/populations
height_Cav = df['cav_counts'].values/populations
height_RideHail = df['ride_hail_counts'].values/populations
height_RideHailPooled = df['ride_hail_pooled_counts'].values/populations
height_nonMotorized = df['walk_counts'].values/populations + df['bike_counts'].values/populations
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHailPooled+ height_RideHail + height_Cav
#height_Transit /= height_all
#height_Car /= height_all
#height_Cav /= height_all
#height_RideHail /= height_all
#height_RideHailPooled /= height_all
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000/60
#height_nonMotorized /= height_all


plt_car = plt.bar(x=xpos,height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav, color=mode_colors['Ride Hail'])
plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit + height_Car+ height_Cav + height_RideHail, color=mode_colors['Ride Hail - Pooled'])
#plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit+ height_Cav)
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_RideHailPooled + height_Cav, color=mode_colors['Bike'])

plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_rhp,plt_nm),('Car','CAV','Transit','Ridehail','Ride Hail - Pooled','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.025,names[ind],ha='center')
ax.set_ylim((0,2.4))
plt.ylabel('Daily Trips per Capita')
plt.savefig('Plots/trips_percapita.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
populations = [612597,612598,612598,755015,755006,755022,754962]
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['drive_transit_counts'].values/populations + df['ride_hail_transit_counts'].values/populations + df['walk_transit_counts'].values/populations
height_Car = df['car_counts'].values/populations
height_Cav = df['cav_counts'].values/populations
height_RideHail = df['ride_hail_counts'].values/populations
height_RideHailPooled = df['ride_hail_pooled_counts'].values/populations
height_nonMotorized = df['walk_counts'].values/populations + df['bike_counts'].values/populations
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHailPooled+ height_RideHail + height_Cav
#height_Transit /= height_all
#height_Car /= height_all
#height_Cav /= height_all
#height_RideHail /= height_all
#height_RideHailPooled /= height_all
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000/60
#height_nonMotorized /= height_all

plt_transit = plt.bar(x=xpos,height=height_Transit, color=mode_colors['Transit'])
plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_Transit, color=mode_colors['Ride Hail - Pooled'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_RideHailPooled, color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Transit + height_RideHailPooled + height_RideHail, color=mode_colors['CAV'])
plt_car = plt.bar(x=xpos,height=height_Car,bottom=height_Transit + height_RideHailPooled + height_RideHail + height_Cav, color=mode_colors['Car'])
#plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit+ height_Cav)
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_RideHailPooled + height_Cav, color=mode_colors['Bike'])

plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_transit,plt_rhp,plt_rh,plt_cav,plt_car,plt_nm),('Transit','Ride Hail (Pooled)','Ride Hail','CAV','Car','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.025,names[ind],ha='center')
ax.set_ylim((0,2.4))
plt.ylabel('Daily Trips per Capita')
plt.savefig('Plots/trips_percapita_reorder.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Transit = df['VMT_cable_car'].values/50000 
height_Transit += df['VMT_bus'].values/50000 
height_Transit += df['VMT_ferry'].values/50000
height_Transit += df['VMT_rail'].values/50000
height_Transit += df['VMT_subway'].values/50000
height_Transit += df['VMT_tram'].values/50000
height_Car = df['VMT_car'].values/50000
height_Cav = df['VMT_car_CAV'].values/50000
height_RideHail = df['VMT_car_RH'].values/50000
height_RideHail += df['VMT_car_RH_CAV'].values/50000
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000
#height_nonMotorized = df['VMT_walk'].values/200000 
height_nonMotorized = df['VMT_bike'].values/50000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_car = plt.bar(x=xpos,height=height_Car, color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car, color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav, color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav, color=mode_colors['Ride Hail'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_Cav, color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt_cav_emprt = plt.bar(x=xpos,height=df['VMT_cav_empty'].values/50000,bottom=height_Car,hatch='///',fill=False,linewidth=0)
plt_rh_empty = plt.bar(x=xpos,height=df['VMT_car_RH_empty'].values/50000,bottom=height_Transit + height_Car+ height_Cav,hatch='///',fill=False,linewidth=0)
plt_rh_pooled = plt.bar(x=xpos,height=-df['VMT_car_RH_pooled'].values/50000,bottom=height_Transit + height_Car+ height_Cav + height_RideHail,hatch="xx",fill=False,linewidth=0)

ax = plt.gca()
empty = mpatches.Patch(facecolor='white', label='The white data', hatch = '///')
shared = mpatches.Patch(facecolor='white', label='The white data', hatch = 'xx')
ax.grid('off')
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_nm,empty,shared),('Car','CAV','Transit','Ridehail','NonMotorized','Empty','Shared'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 5,names[ind],ha='center')
ax.set_ylim((0,550))
plt.ylabel('Vehicle Miles Traveled (millions)')
plt.savefig('Plots/vmt_mode.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
populations = [612597,612598,612598,755015,755006,755022,754962]
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']

height_Transit = df['PMT_bus'].values/populations 
height_Transit += df['PMT_ferry'].values/populations
height_Transit += df['PMT_rail'].values/populations
height_Transit += df['PMT_subway'].values/populations
height_Transit += df['PMT_tram'].values/populations
#height_Transit += df['PMT_cable_car'].values/200000 
height_Car = df['PMT_car'].values/populations
height_Cav = df['PMT_car_CAV'].values/populations
height_RideHail = df['PMT_car_RH'].values/populations
height_RideHail += df['PMT_car_RH_CAV'].values/populations
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000
height_nonMotorized = df['PMT_walk'].values/populations 
height_nonMotorized += df['PMT_bike'].values/populations
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_car = plt.bar(x=xpos,height=height_Car,color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car,color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav,color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav,color=mode_colors['Ride Hail'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_Cav,color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_nm),('Car','CAV','Transit','Ridehail','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.5,names[ind],ha='center')
#ax.set_ylim((0,160))
plt.ylabel('Person Miles Traveled per Capita')
plt.savefig('Plots/pmt_percapita_mode.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']

totalEnergy = df['totalEnergy_LDV']/1000000

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalEnergy/totalPMT

plt_e_pmt = plt.bar(x=xpos,height=height,color=colors['grey'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind],height[ind] + 0.025,names[ind],ha='center')
plt.ylabel('Energy per Light Duty Vehicle Passenger Mile (MJ/mi)')
plt.savefig('Plots/energy_per_pmt.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']

totalEnergy = df['totalEnergy_LDV']/1000000

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalEnergy/totalVMT

plt_e_pmt = plt.bar(x=xpos,height=height,color=colors['grey'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind],height[ind] + 0.025,names[ind],ha='center')
plt.ylabel('Energy per Light Duty Vehicle Mile (MJ/mi)')
plt.savefig('Plots/energy_per_vmt.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']

totalEnergy = df['totalEnergy_LDV']/1000000

totalPMT = df['PMT_car'].values
totalPMT += df['PMT_car_CAV'].values
totalPMT += df['PMT_car_RH'].values
totalPMT += df['PMT_car_RH_CAV'].values

totalVMT = df['VMT_car'].values
totalVMT += df['VMT_car_CAV'].values
totalVMT += df['VMT_car_RH'].values
totalVMT += df['VMT_car_RH_CAV'].values

height = totalPMT/totalVMT

plt_e_pmt = plt.bar(x=xpos,height=height,color=colors['grey'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
ax = plt.gca()
ax.grid(False)
for ind in range(7):
    plt.text(xpos[ind],height[ind] + 0.025,names[ind],ha='center')
plt.ylabel('Mean Occupancy')
plt.savefig('Plots/occupancy.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
populations = [612597,612598,612598,755015,755006,755022,754962]
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']

height_Transit = df['PMT_bus'].values/populations 
height_Transit += df['PMT_ferry'].values/populations
height_Transit += df['PMT_rail'].values/populations
height_Transit += df['PMT_subway'].values/populations
height_Transit += df['PMT_tram'].values/populations
#height_Transit += df['PMT_cable_car'].values/200000 
height_Car = df['PMT_car'].values/populations
height_Cav = df['PMT_car_CAV'].values/populations
height_RideHail = df['PMT_car_RH'].values/populations
height_RideHail += df['PMT_car_RH_CAV'].values/populations
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000
height_nonMotorized = df['PMT_walk'].values/populations 
height_nonMotorized += df['PMT_bike'].values/populations
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_transit = plt.bar(x=xpos,height=height_Transit,color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit,color=mode_colors['Ride Hail'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Transit + height_RideHail,color=mode_colors['CAV'])
plt_car = plt.bar(x=xpos,height=height_Car,bottom=height_Transit + height_RideHail + height_Cav,color=mode_colors['Car'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_Cav,color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_transit,plt_rh,plt_cav,plt_car,plt_nm),('Transit','Ride Hail','CAV','Car','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.5,names[ind],ha='center')
#ax.set_ylim((0,160))
plt.ylabel('Person Miles Traveled per Capita')
plt.savefig('Plots/pmt_percapita_mode_reorder.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
populations = [612597,612598,612598,755015,755006,755022,754962]
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']

height_Gas = df['totalVehicles_Gas'].values/50000 
height_48V = df['totalVehicles_48V'].values/50000
height_Diesel = df['totalVehicles_Diesel'].values/50000
height_PHEV = df['totalVehicles_PHEV'].values/50000
height_HEV = df['totalVehicles_HEV'].values/50000
#height_Transit += df['PMT_cable_car'].values/200000 
height_BEV = df['totalVehicles_BEV'].values/50000

height_all = height_Gas + height_48V + height_Diesel + height_HEV + height_PHEV + height_BEV

plt_gas = plt.bar(x=xpos,height=height_Gas,color=mode_colors['Car'])
plt_48v = plt.bar(x=xpos,height=height_48V,bottom=height_Gas,color=mode_colors['CAV'])
plt_diesel = plt.bar(x=xpos,height=height_Diesel,bottom=height_Gas + height_48V,color=mode_colors['Transit'])
plt_phev = plt.bar(x=xpos,height=height_PHEV,bottom=height_Gas + height_48V+ height_Diesel,color=mode_colors['Ride Hail'])
plt_hev = plt.bar(x=xpos,height=height_HEV,bottom= height_Gas + height_48V + height_Diesel+ height_PHEV,color=mode_colors['Bike'])
plt_bev = plt.bar(x=xpos,height=height_BEV,bottom= height_Gas + height_48V + height_Diesel+ height_PHEV + height_HEV, color=mode_colors['Walk'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_gas,plt_48v,plt_diesel,plt_phev,plt_hev,plt_bev),('Gasoline','Gasoline - 48V','Diesel','PHEV','HEV', 'BEV'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 0.15,names[ind],ha='center')
#ax.set_ylim((0,160))
plt.ylabel('Total Vehicles (Millions)')
plt.savefig('Plots/vehicle_type_count.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']

height_Transit = df['PMT_bus'].values/50000 
height_Transit += df['PMT_ferry'].values/50000
height_Transit += df['PMT_rail'].values/50000
height_Transit += df['PMT_subway'].values/50000
height_Transit += df['PMT_tram'].values/50000
#height_Transit += df['PMT_cable_car'].values/200000 
height_Car = df['PMT_car'].values/50000
height_Cav = df['PMT_car_CAV'].values/50000
height_RideHail = df['PMT_car_RH'].values/50000
height_RideHail += df['PMT_car_RH_CAV'].values/50000
#height_RideHailPooled = df['personTravelTime_onDemandRide_pooled'].values/200000
height_nonMotorized = df['PMT_walk'].values/50000 
height_nonMotorized += df['PMT_bike'].values/50000
height_all = height_nonMotorized + height_Car + height_Transit + height_RideHail + height_Cav

plt_car = plt.bar(x=xpos,height=height_Car,color=mode_colors['Car'])
plt_cav = plt.bar(x=xpos,height=height_Cav,bottom=height_Car,color=mode_colors['CAV'])
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car + height_Cav,color=mode_colors['Transit'])
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car+ height_Cav,color=mode_colors['Ride Hail'])
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom= height_Car + height_Transit + height_RideHail+ height_Cav,color=mode_colors['Bike'])
plt.xticks([1,3,5.5,8],['Base','Sharing is Caring','Technology Takeover',"All About Me"],rotation=10)
plt.legend((plt_car,plt_cav,plt_transit,plt_rh,plt_nm),('Car','CAV','Transit','Ridehail','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_all[ind] + 2.5,names[ind],ha='center')
#ax.set_ylim((0,160))
plt.ylabel('Person Miles Traveled (millions)')
plt.savefig('Plots/pmt_mode.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Gas = df['totalEnergy_gasoline'].values/200000000
height_Diesel = df['totalEnergy_diesel'].values/200000000
height_Electricity = df['totalEnergy_electricity'].values/200000000
height_all = height_Gas + height_Diesel + height_Electricity

plt_g = plt.bar(x=xpos,height=height_Gas)
plt_d = plt.bar(x=xpos,height=height_Diesel,bottom=height_Gas)
plt_e = plt.bar(x=xpos,height=height_Electricity,bottom=height_Diesel + height_Gas)

plt.xticks([1,3,5.5,8],['Baseline 2019','A: Sharing is caring','B: Technology takes over',"C: We're in trouble"],rotation=10)
plt.legend((plt_g,plt_d,plt_e),('Gasoline','Diesel','Electricity'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
ax.grid('off')
#for ind in range(7):
#    plt.text(xpos[ind],height_all[ind] + 5,names[ind],ha='center')
#ax.set_ylim((0,400))
plt.ylabel('Light duty vehicle energy use (GJ)')
plt.savefig('Plots/energy_source.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
names= ['Base','Low','High','Low','High','Low','High']
height_Low = df['Low_VMT'].values/200000 
height_High = df['High_VMT'].values/200000
height_CAV = df['CAV_VMT'].values/200000
height_All = height_Low + height_High + height_CAV
height_RH_Empty = df['rh_empty_miles'].values/200000
height_PV_Empty = df['VMT_cav_empty'].values/200000

plt_Low = plt.bar(x=xpos,height=height_Low)
plt_High = plt.bar(x=xpos,height=height_High,bottom=height_Low)
plt_CAV = plt.bar(x=xpos,height=height_CAV,bottom=height_High + height_Low)
plt_empty_car = plt.bar(x=xpos,height=height_RH_Empty,hatch='/',fill=False)
plt_empty_cav = plt.bar(x=xpos,height=height_PV_Empty,bottom = height_High + height_Low,hatch='/',fill=False)
plt.xticks([1,3,5.5,8],['Baseline 2019','A: Sharing is caring','B: Technology takes over',"C: We're in trouble"],rotation=10)
plt.legend((plt_Low,plt_High,plt_CAV),('Low','High','CAV'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
ax = plt.gca()
plt.grid(b=None)
#ax.set_ylim((0,10))
ax.grid('off')
for ind in range(7):
    plt.text(xpos[ind],height_All[ind] + 2,names[ind],ha='center')
ax.set_ylim((0,140))
plt.ylabel('Light duty vehicle miles traveled (millions)')
plt.savefig('Plots/vmt_tech.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
plt.figure(figsize=(5,6)) 
xpos = [1,2.5,3.5,5,6,7.5,8.5]
height_Transit = df['totalCost_drive_transit'].values*5 + df['totalCost_onDemandRide_transit'].values*5 + df['totalCost_walk_transit'].values*5
height_Car = df['totalCost_car'].values*5 
height_Cav = df['totalCost_cav'].values*5
height_RideHail = df['totalCost_onDemandRide'].values*5 + df['totalCost_onDemandRide_pooled'].values*5
#height_RideHailPooled = df['totalCost_onDemandRide_pooled'].values*5
height_nonMotorized = df['totalCost_walk'].values*5 + df['totalCost_bike'].values*5
height_All = height_Transit + height_Car + height_RideHail  + height_nonMotorized

plt_car = plt.bar(x=xpos,height=height_Car)
plt_transit = plt.bar(x=xpos,height=height_Transit,bottom=height_Car)
plt_rh = plt.bar(x=xpos,height=height_RideHail,bottom=height_Transit + height_Car)
#plt_rhp = plt.bar(x=xpos,height=height_RideHailPooled,bottom=height_RideHail + height_Car + height_Transit)
plt_nm = plt.bar(x=xpos,height=height_nonMotorized,bottom=height_Car + height_Transit + height_RideHail)
plt.xticks([1,3,5.5,8],['Baseline 2019','A: Sharing is caring','B: Technology takes over',"C: We're in trouble"],rotation=10)
for ind in range(7):
    plt.text(xpos[ind],height_All[ind] + 10,names[ind],ha='center')
ax = plt.gca()
#ax.set_ylim((0,600))
plt.ylabel('Total Cost')
plt.legend((plt_car,plt_transit,plt_rh,plt_rhp,plt_nm),('Car','Transit','Ridehail','Pooled Ridehail','NonMotorized'),labelspacing=-2.5, bbox_to_anchor=(1.05, 0.5), frameon=False)
plt.savefig('Plots/cost.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
xa = np.array([2019,2025])
xbc = np.array([2019,2040])
key = 'averageOnDemandRideWaitTimeInMin'

A_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)

fig, ax = plt.subplots()
#ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa,A_LT[key] , color = 'black', marker = 'o')
line_aht = ax.plot(xa,A_HT[key] , color = 'black', marker = 'v')
line_lt = ax.plot(xbc,C_LT[key] ,xbc,B_LT[key] , color = 'black', marker = 'o')
line_ht = ax.plot(xbc,C_HT[key] ,xbc,B_HT[key], color = 'black', marker = 'v')
area_A = ax.fill_between(xa,A_LT[key],A_HT[key],alpha=0.5)
area_C = ax.fill_between(xbc,C_LT[key],C_HT[key],alpha=0.5)
area_B = ax.fill_between(xbc,B_LT[key],B_HT[key],alpha=0.5)
tech_legend = plt.legend((area_A,area_B,area_C),('A: Sharing is Caring','B: Technology takes over',"C: We're in trouble"), loc=3)
ax.add_artist(tech_legend)
ax.axis('on')
circles = mlines.Line2D([], [], color='black', marker='o',
                          markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=1)
plt.xlabel('Year')
plt.ylabel('Average on demand wait time (min)')
#ax.spines['bottom'].set_color('green')
#ax.spines['left'].set_color('green')
plt.savefig('Plots/OD_Wait_Time.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
xa = np.array([2019,2025])
xbc = np.array([2019,2040])
key = 'totalEnergy'

A_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)

fig, ax = plt.subplots()
#ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa,A_LT[key]/200000000 , color = 'black', marker = 'o')
line_aht = ax.plot(xa,A_HT[key]/200000000 , color = 'black', marker = 'v')
line_lt = ax.plot(xbc,C_LT[key]/200000000 ,xbc,B_LT[key]/200000000 , color = 'black', marker = 'o')
line_ht = ax.plot(xbc,C_HT[key]/200000000 ,xbc,B_HT[key]/200000000, color = 'black', marker = 'v')
area_A = ax.fill_between(xa,A_LT[key]/200000000,A_HT[key]/200000000,alpha=0.5)
area_C = ax.fill_between(xbc,C_LT[key]/200000000,C_HT[key]/200000000,alpha=0.5)
area_B = ax.fill_between(xbc,B_LT[key]/200000000,B_HT[key]/200000000,alpha=0.5)
tech_legend = plt.legend((area_A,area_B,area_C),('A: Sharing is Caring','B: Technology takes over',"C: We're in trouble"), loc=3)
ax.add_artist(tech_legend)
ax.axis('on')
circles = mlines.Line2D([], [], color='black', marker='o',
                          markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=1)
plt.xlabel('Year')
plt.ylabel('Total light duty vehicle energy (GJ)')
#ax.spines['bottom'].set_color('green')
#ax.spines['left'].set_color('green')
plt.savefig('Plots/Energy.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
xa = np.array([2019,2025])
xbc = np.array([2019,2040])
key = 'totalMiles'

A_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)

fig, ax = plt.subplots()
#ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa,A_LT[key]/200000 , color = 'black', marker = 'o')
line_aht = ax.plot(xa,A_HT[key]/200000 , color = 'black', marker = 'v')
line_lt = ax.plot(xbc,C_LT[key]/200000 ,xbc,B_LT[key]/200000 , color = 'black', marker = 'o')
line_ht = ax.plot(xbc,C_HT[key]/200000 ,xbc,B_HT[key]/200000, color = 'black', marker = 'v')
area_A = ax.fill_between(xa,A_LT[key]/200000,A_HT[key]/200000,alpha=0.5)
area_C = ax.fill_between(xbc,C_LT[key]/200000,C_HT[key]/200000,alpha=0.5)
area_B = ax.fill_between(xbc,B_LT[key]/200000,B_HT[key]/200000,alpha=0.5)
tech_legend = plt.legend((area_A,area_B,area_C),('A: Sharing is Caring','B: Technology takes over',"C: We're in trouble"), loc=4)
ax.add_artist(tech_legend)
ax.axis('on')
circles = mlines.Line2D([], [], color='black', marker='o',
                          markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=2)
plt.xlabel('Year')
plt.ylabel('Light duty vehicle miles traveled (millions)')
#ax.spines['bottom'].set_color('green')
#ax.spines['left'].set_color('green')
plt.savefig('Plots/vmt.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')

#%%
xa = np.array([2019,2025])
xbc = np.array([2019,2040])
key = 'MPH'

A_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
A_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'a') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
B_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
B_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'b') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)
C_LT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'Low Tech')]], axis=0, ignore_index=True)
C_HT = pd.concat([df[df['Scenario'] == 'base'],df[(df['Scenario'] == 'c') & (df['Technology'] == 'High Tech')]], axis=0, ignore_index=True)

fig, ax = plt.subplots()
#ax.plot(x,B_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,B_HT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_LT['motorizedVehicleMilesTraveled_total'])
#ax.plot(x,C_HT['motorizedVehicleMilesTraveled_total'])
line_alt = ax.plot(xa,A_LT[key] , color = 'black', marker = 'o')
line_aht = ax.plot(xa,A_HT[key] , color = 'black', marker = 'v')
line_lt = ax.plot(xbc,C_LT[key] ,xbc,B_LT[key] , color = 'black', marker = 'o')
line_ht = ax.plot(xbc,C_HT[key] ,xbc,B_HT[key], color = 'black', marker = 'v')
area_A = ax.fill_between(xa,A_LT[key],A_HT[key],alpha=0.5)
area_C = ax.fill_between(xbc,C_LT[key],C_HT[key],alpha=0.5,hatch = '-')
area_B = ax.fill_between(xbc,B_LT[key],B_HT[key],alpha=0.5,hatch = '|')
tech_legend = plt.legend((area_A,area_B,area_C),('A: Sharing is Caring','B: Technology takes over',"C: We're in trouble"), loc=3)
ax.add_artist(tech_legend)
ax.axis('on')
circles = mlines.Line2D([], [], color='black', marker='o',
                          markersize=5, label='Low Tech')
triangles = mlines.Line2D([], [], color='black', marker='v',
                          markersize=5, label='High Tech')
plt.legend(handles=[circles, triangles], loc=4)
plt.xlabel('Year')
plt.ylabel('Road network average speed (MPH)')
#ax.spines['bottom'].set_color('green')
#ax.spines['left'].set_color('green')
#ax.set_ylim((16,32))
plt.savefig('Plots/Speed.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')