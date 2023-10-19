#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd

import util.beam_data as bd
from util.data import find_last_created_dir
from util.data import meaningful


# In[2]:


root = "../beam_root"
pd.set_option('display.max_rows', 1000)
beam_out_path = None
# beam_out_path = "sfbay/gemini-scenario-5-calibrate5p__2022-12-30_12-46-10_nyq"
beam_out = find_last_created_dir(f"{root}/output", level = 1, num = 0) if beam_out_path is None else f"{root}/output/{beam_out_path}"
print(f"Using beam out dir: {beam_out}")
it = 0
ev = bd.load_events(f"{beam_out}/ITERS/it.{it}/{it}.events.csv.gz")

def fun1(df):
    return df['type'] == 'PersonEntersVehicle'

display(str(ev.shape[0]) + " " + str(ev[(ev['type'] == 'PersonEntersVehicle') & ev['vehicle'].str.startswith('body-')].shape[0]))


# In[3]:


pte = meaningful(ev[(ev['type'] == 'PathTraversal')])
pev = meaningful(ev[(ev['type'] == 'PersonEntersVehicle')])
plv = meaningful(ev[(ev['type'] == 'PersonLeavesVehicle')])
lpe = meaningful(ev[(ev['type'] == 'LeavingParkingEvent')])
pe = meaningful(ev[(ev['type'] == 'ParkingEvent')])
mc = meaningful(ev[(ev['type'] == 'ModeChoice')])
actstart = meaningful(ev[(ev['type'] == 'actstart')])
actend = meaningful(ev[(ev['type'] == 'actend')])
repl = meaningful(ev[(ev['type'] == 'Replanning')])
rh_confirm = meaningful(ev[(ev['type'] == 'RideHailReservationConfirmation')])
reserv_rh = meaningful(ev[(ev['type'] == 'ReserveRideHail')])
person_cost = meaningful(ev[(ev['type'] == 'PersonCost')])
# ev['type'].unique()


# In[20]:


# display(mc[mc['availableAlternatives'].str.contains("ride_hail_transit", case=False, na=False)])

mc[mc['mode'] == "ride_hail"]
# repl
# pte[(pte['numPassengers'] > 1 ) & pte['vehicle'].str.startswith('ride')]


# In[17]:


pte_pooled = pte[pte['vehicle'].str.startswith('ride') & (pte['numPassengers'] > 1)].copy()
# pte_pooled = pte[pte['vehicle'].str.startswith('ride') & (pte['vehicle'].str.contains('Uber', case=False)) & (pte['numPassengers'] > 0)].copy()
pte_pooled


# In[ ]:


ride_hail_mc = mc[mc['mode'].str.startswith("ride_hail")]
# display(ride_hail_mc) #& (mc['length'] > 0)
denied = repl[repl['reason'].str.contains('RIDE_HAIL')]
ride_hail_mc[~ride_hail_mc['person'].isin(denied['person'])]
# display(ride_hail_mc[ride_hail_mc['legVehicleIds'].str.contains('@Uber')]['mode'].value_counts())
# display(ride_hail_mc[ride_hail_mc['legVehicleIds'].str.contains('@file1')]['mode'].value_counts())
# ride_hail_mc[ride_hail_mc['person'].isin(negative_persons)]


# In[64]:


# Average waiting time of RH vehicles
# display(f"chooses = {len(mc[mc['mode'].str.startswith('ride_hail')])}")
# ev = pd.read_csv("../output/sf-light/rh-stops-reserve-long-walk__2023-05-30_17-11-22_imo/ITERS/it.0/0.events.csv.gz")
rh_result = ev[((ev['type'] == 'PathTraversal') & (ev['mode'] == 'walk') & (ev['length'] == 0))
               | ((ev['type'] == 'PersonEntersVehicle') & ev['vehicle'].str.startswith("rideHailVehicle-") & ~ev['person'].str.startswith('rideHailAgent-', na=False))
               # | ((ev['type'] == 'Replanning') & (repl['reason'].str.contains('RIDE_HAIL')))
]
# rh_result = meaningful(rh_result)
rh_result['person'].loc[rh_result['type'] == 'PathTraversal'] = rh_result['driver']
rh_result = rh_result.drop(columns=['startX', 'startY', 'endX', 'endY'])
# display(rh_result)
rh_result['walking_end'] = rh_result.groupby('person')['time'].shift(1)

display(f"rh_result / 2 = {len(rh_result) / 2}")

success = rh_result[rh_result['type'] == 'PersonEntersVehicle'].copy()
display(f"success = {len(success)}")
success['waiting_time'] = success['time'] - success['walking_end']
success[["person", "waiting_time"]].sort_values('waiting_time', ascending=True)
success['waiting_time'].describe()
# negative_persons = success[success['waiting_time'] < 0][['person', 'waiting_time']]
# negative_persons


# In[7]:


# Average waiting time of RH vehicles (corrected)
display(f"chooses = {len(mc[mc['mode'].str.startswith('ride_hail')])}")
rh_result = ev[((ev['type'] == 'PathTraversal') & (ev['mode'] == 'walk'))
               | ((ev['type'] == 'PersonEntersVehicle') & ev['vehicle'].str.startswith("body-"))
               | ((ev['type'] == 'PathTraversal') & ev['vehicle'].str.startswith("rideHailVehicle-"))
    # | ((ev['type'] == 'Replanning') & (repl['reason'].str.contains('RIDE_HAIL')))
               ]
rh_result = meaningful(rh_result)
rh_result['person'].loc[(ev['type'] == 'PathTraversal') & (ev['mode'] == 'walk')] = rh_result['driver']
rh_result = rh_result.drop(columns=['startX', 'startY', 'endX', 'endY'])
display(rh_result[rh_result['riders'].str.contains('032601-2012001243911-0-7187444', na=False) | (rh_result['person'] == '032601-2012001243911-0-7187444')])
# display(rh_result)
rh_result['walking_end'] = rh_result.groupby('person')['time'].shift(1)

display(f"rh_result / 2 = {len(rh_result) / 2}")

success = rh_result[rh_result['type'] == 'PersonEntersVehicle'].copy()
display(f"success = {len(success)}")
success['waiting_time'] = success['time'] - success['walking_end']
success[["person", "waiting_time"]].sort_values('waiting_time', ascending=True)
success['waiting_time'].describe()
# negative_persons = success[success['waiting_time'] < 0]['person']


# In[5]:


#Which RH fleet is used most

# Jessica's function
def get_ridehail_stats(events):
    mc = events.loc[(events.type=='ModeChoice')&(events['mode'].isin(['ride_hail','ride_hail_pooled','ride_hail_transit']))][['time','mode','person']]
    e_v = events.loc[(events.type=='PersonEntersVehicle')&(events.vehicle.str.contains('rideHail'))][['time','vehicle','person']]
    # merge PersonEntersVehicle with ModeChoices
    mc_veh = mc.merge(e_v,how='left',left_on='person',right_on='person',suffixes=('_mc','_veh'))
    mc_veh['time_delta']=mc_veh['time_veh']-mc_veh['time_mc']
    mc_veh = mc_veh.loc[mc_veh.time_delta>=0]
    mc_veh = mc_veh.sort_values(['person','time_mc','time_delta']).groupby(['person','time_mc']).first().reset_index()
    # identify fleet ID
    mc_veh[['vehicleId','fleetId']] = mc_veh['vehicle'].str.split('@',expand=True).rename(columns={0:'vehicleId',1:'fleetId'})

    display(mc_veh.groupby(['fleetId','mode']).count()['person'])

    if sum(events.type.str.contains('PersonCost'))>1:
        costs = events.loc[(events.type=='PersonCost')&(events['mode'].str.contains('ride'))][['person','mode','time','netCost']]
        # merge PersonCost with ModeChoices
        mc_cost = mc_veh.merge(costs,how='left',left_on=['person','mode'],right_on=['person','mode'],suffixes=('_mc','_cost'))
        mc_cost['time_delta_cost'] = mc_cost['time']-mc_cost['time_mc']
        mc_cost=mc_cost.loc[mc_cost.time_delta_cost>=0]
        mc_cost = mc_cost.sort_values(['person','time_mc','time_delta_cost']).groupby(['person','time_mc']).first().reset_index()

        display(mc_cost.groupby(['fleetId','mode']).mean())

display(mc['mode'].value_counts(normalize=True))
rh_pte = pte[pte['vehicle'].str.startswith('rideHail') & (pte['numPassengers'] > 0)]
uber = rh_pte[rh_pte['vehicle'].str.contains('Uber')].shape[0]
cruise = rh_pte[rh_pte['vehicle'].str.contains('Cruise')].shape[0]
lyft = rh_pte[rh_pte['vehicle'].str.contains('Lyft')].shape[0]
print(f"uber = {uber}, cruise = {cruise}, lyft = {lyft}")
rh_pte.sort_values(['vehicle', 'time'])
rh_pte[rh_pte['vehicle'].str.contains('Cruise')]
# rh_confirm[rh_confirm['vehicle'].str.contains('Cruise', na=False) & (rh_confirm['reservationType'] == 'Solo')]
# rh_pte[rh_pte['vehicle'].isin(['rideHailVehicle-5715445@Cruise', 'rideHailVehicle-5686308@Cruise'])]
get_ridehail_stats(ev)
# rh_confirm[rh_confirm['cost'] > 10.0]
# person_cost

# rh_confirm


# In[15]:


# a particular person events
person_id = '016000-2015000256008-0-6543940'

meaningful(ev[(ev['person'] == person_id) | (ev['vehicle'] == f'body-{person_id}') | (ev['driver'] == person_id)
              | ev['riders'].str.contains(f'{person_id}:') | ev['riders'].str.contains(f':{person_id}') | (ev['riders'] == person_id)])# \
# [["time", "type", "mode", "vehicle", "vehicleType", "driver", "actType", "availableAlternatives", "personalVehicleAvailable", "link", "location"
#      , "links"
#      , "parkingTaz", "parkingType", "locationY", "locationX", "score", "cost"
#      , "person"]] \
    # meaningful(events[(events['person'] == '061100-2016001385848-0-6031565')])


# In[8]:


# pev[pev['vehicle'].str.startswith('rideHail')]
display(pte[pte['vehicle'].str.startswith('rideHail') & (pte['mode'] == 'car')].shape)
display(pte[(pte['vehicleType'].str.contains('freight'))  & (pte['mode'] == 'car')].shape)
display(pte[(pte['mode'] == 'car')].shape)
display(pte[(pte['vehicle'] == '')].shape)


# In[ ]:


# pte[pte['primaryFuelType'].str.startswith('Electricity')]['vehicle'].unique()
# pte[pte['vehicle'] == "rideHailVehicle-061500-2014000189243-0-4752174@GlobalRHM"]
display(pte[pte['vehicle'].str.startswith('rideHail') & (pte['numPassengers'])])
# pte[pte['vehicle'].str.contains('rideHailVehicle-016400-2015001364898-0-434884')]


# In[71]:


# long walking trips
# mc[(mc['availableAlternatives'] == 'WALK') & (mc['length'] > 4800)]
# pte[(pte['currentTourMode'] == 'walk') & (pte['length'] > 5000)]
# ev[ev['reason'] == 'ResourceUnavailable RIDE_HAIL_POOLED']
meaningful(ev[ev['person'] == '4'])


# In[5]:


choice = meaningful(ev[ev['type'].isin(['ModeChoice', 'Replanning'])]).drop(labels = ['type', 'location', 'hour'], axis = 1)
choice = choice.sort_values(['person', 'time'])
# choice_repl[choice_repl['person'] == '010200-2014001327621-0-7815204']
# meaningful(ev[ev['person'] == '010200-2014001327621-0-7815204'])
choice#.drop(['personalVehicleAvailable', 'legModes', 'legVehicleIds'], axis = 1)
# walking_persons = choice[choice['mode'] == 'walk']['person']
# choice[choice['person'].isin(walking_persons)].drop(['personalVehicleAvailable', 'legModes', 'legVehicleIds'], axis = 1).head(60)


# In[7]:


# person_activity = mc.groupby(['person', 'nextActivity'])['type'].count().reset_index()
# person_multi_work = person_activity[(person_activity['nextActivity'] == "Work") & (person_activity['type'] >= 2)]['person']
# person_other_activities = person_activity[(~person_activity['nextActivity'].isin(["Work", "Home"])) & person_activity['person'].isin(person_multi_work)]
# person_other_activities
persons3 = choice[(~choice['currentActivity'].str.startswith("Home", na=True)) & ~choice['nextActivity'].str.startswith("Home", na=True)]['person']
# choice[choice['person'].isin(persons3)].drop(['personalVehicleAvailable', 'legModes', 'legVehicleIds'], axis = 1)
choice[choice['person'].isin(persons3)]


# In[8]:


walking_persons = mc[(mc['mode'] == 'walk') & (mc['length'] > 5000) & (mc['personalVehicleAvailable'] == "true")]['person']
choice[choice['person'].isin(walking_persons)].sort_values(['person', 'time'])
# mc[(mc['mode'] == 'walk') & (mc['length'] > 5000) & (mc['personalVehicleAvailable'] == 'true')]


# In[ ]:


electric_vehicles = ['4']

electric_ev = meaningful(ev[ev['vehicle'].isin(electric_vehicles)])
electric_ev['startingFuelLevel'] = pd.to_numeric(electric_ev['primaryFuelLevel']) + pd.to_numeric(electric_ev['primaryFuel'])
electric_ev[['type', "vehicle", 'startingFuelLevel', 'primaryFuelLevel', 'primaryFuel']]
# electric_ev


# In[ ]:


# some person events
important_columns = ["time", "type", "currentTourMode", "mode", "vehicle", "vehicleType", "driver", "actType", "availableAlternatives",
                     # "personalVehicleAvailable", "link", "location", "links",
                     "startX", "startY", "endX", "endY",
                     "person"]

person_id = '030202-2016000025297-0-5404271'
# person_id = '5'
vehicle_ids = ev[(ev['person'] == person_id) | ev['driver'] == person_id]['vehicle'].unique()

ev[(ev['person'] == person_id)
   # | (ev['vehicle'].str.contains(person_id))
   | (ev['driver'] == person_id)
   | (ev['vehicle'].isin(vehicle_ids))
][important_columns ]
# ]['vehicle'].unique()
# ]


# In[18]:


# ev[ev['type'] == 'FleetStoredElectricityEvent']
# ev[ev['vehicle'].str.startswith('ride', na=False) & (~ev['primaryFuelLevel'].isnull())]
# ev[(ev['type'] == 'PersonEntersVehicle') & ~ev['person'].str.startswith('ride', na=False)]
# ev[~ev['mode'].isnull()]['type'].unique()
meaningful(ev[(ev['currentTourMode'] == 'last_resort_teleport')])


# In[ ]:


#most parked TAZes
num = pe.groupby('parkingTaz')['type'].count().rename('num_parking')
num.sort_values(ascending=False)
# num.loc['101111']


# In[ ]:


freight_ev = ev[(ev['vehicle'].str.startswith("freight", na=False)) | (ev['person'].str.startswith("freight", na=False))][['person', 'actType', 'time', 'type', 'vehicle', 'mode',
      'locationY', 'locationX', 'secondaryFuelLevel', 'startX',
      'startY', 'endX', 'endY', 'vehicleType',
      'arrivalTime', 'departureTime'
      ]]
# freight_ev
# freight_pte = freight_ev[freight_ev['type'] == 'PathTraversal']
# freight_pte
# freight_pte[freight_pte['mode'] != "car"]
# tour_id = "vehicle-3"
# tour_ev = freight_ev[freight_ev['person'].str.contains(tour_id, na=False) | freight_ev['vehicle'].str.contains(tour_id, na=False)]
# tour_ev[tour_ev['type'].isin(['actstart', 'actend', 'PathTraversal'])]
freight_ev[freight_ev['type'].isin(['actstart', 'actend', 'PathTraversal'])]


# In[4]:


# event types
ev['type'].unique()


# In[ ]:


bikes = pte[(pte['mode'] == 'bike')][['vehicle', 'driver', 'time', 'length', 'departureTime', 'arrivalTime',
                                      'linkTravelTime', 'startX', 'startY', 'endY', 'endX', 'vehicleType', 'links']].copy()
# bikes[bikes['vehicle'].str.startswith('bay')]


# In[ ]:


walks = pte[(pte['mode'] == 'walk')][['vehicle', 'driver', 'time', 'length', 'departureTime', 'arrivalTime',
                                      'linkTravelTime', 'startX', 'startY', 'endY', 'endX', 'vehicleType', 'links']].copy()
# bikes[bikes['vehicle'].str.startswith('bay')]
walks['travel_time'] = walks["arrivalTime"] - walks["departureTime"]
walks["travel_time"].sum()


# In[ ]:


bikes = pte[(pte['mode'] == 'bike')][['vehicle', 'time', 'vehicleType']].copy()
bikes['hour'] = bikes['time'] // 3600
bikes['shared'] = bikes['vehicle'].str.startswith('bay_wheels')
g = bikes.groupby('hour')['shared'].mean()
display(g)
bikes[(bikes['hour'] == 3) & bikes['shared']]
# bikes.groupby('hour').count()['']


# In[4]:


# pev[(pev['person'].str.match("^\d+-\d+-\d+-\d+$")) & (pev['vehicle'].str.startswith('ride'))]


public_transport = pev[pev['vehicle'].str.contains(':')]
# shared = pev[(pev['vehicle'].str.contains('fleet')) & (pev['time'] < 36000)].groupby('person')['type'].count().reset_index()
# shared = shared[shared['type'] > 2]
shared = pev[(pev['vehicle'].str.contains('fleet'))]
pd.merge(public_transport, shared, on='person')


# In[10]:


personEnters = pev[(pev['vehicle'].str.contains('ride')) & (~pev['person'].str.startswith("rideHailAgent"))]
print(len(personEnters[personEnters['vehicle'].str.endswith('@Uber')]))
print(len(personEnters[personEnters['vehicle'].str.endswith('@file1')]))
personEnters


# In[ ]:


lppe = pd.concat([lpe, pe])
lppe = lppe[lppe['vehicle'].str.startswith('my-shared-fleet-')].sort_values('time')
lppe['prev_event'] = lppe.groupby('vehicle')['type'].shift(1)
lppe['prev_driver'] = lppe.groupby('vehicle')['driver'].shift(1)

only_parking = lppe[lppe['type'] == 'ParkingEvent']
only_parking[only_parking['driver'] != only_parking['prev_driver']]
only_parking[only_parking['prev_event'] != 'LeavingParkingEvent']
lppe[lppe['vehicle'] == 'my-shared-fleet-34'].iloc[0:60]


# In[ ]:


# lpe[(lpe['score'] < 0) & (lpe['vehicle'].str.startswith('my-'))]
drivers = set(["012602-2012001015491-0-7432236", "031400-2014000788156-0-569459", "033203-2016000471058-0-2222182", "012602-2012001015491-0-7432234", "026303-2016000613976-0-7696442", "012700-2012000543919-0-6285238", "033204-2013001005226-0-2603182", "030800-2015001150102-0-3366114", "026303-2016000613976-0-7696442", "025403-2014001324460-0-5136856", "026200-2014000664715-0-1076675", "600800-2014001325153-3-578677", "026004-2013001095891-0-5527523", "023003-2012000097895-0-7695136", "023003-2013000382737-0-1583441"])
vehicles = set(['my-fixed-non-reserving-fleet-49', 'my-fixed-non-reserving-fleet-11', 'my-fixed-non-reserving-fleet-283', 'my-fixed-non-reserving-fleet-135', 'my-fixed-non-reserving-fleet-157', 'my-fixed-non-reserving-fleet-36', 'my-fixed-non-reserving-fleet-344', 'my-fixed-non-reserving-fleet-109', 'my-fixed-non-reserving-fleet-198', 'my-fixed-non-reserving-fleet-79', 'my-fixed-non-reserving-fleet-371', 'my-fixed-non-reserving-fleet-135', 'my-fixed-non-reserving-fleet-123', 'my-fixed-non-reserving-fleet-168', 'my-fixed-non-reserving-fleet-111'])
pe[(pe['vehicle'].isin(vehicles)) & (pe['driver'].isin(drivers))]
pe[(pe['locationY'] > 37.75) & (pe['cost'] == 0)]


# In[ ]:


lpe[(lpe['vehicle'] == 'my-fixed-non-reserving-fleet-135') & (lpe['driver'] == '012602-2012001015491-0-7432234')]


# In[ ]:


# bike after transit
# person_id = '5839078'
# display(pev[pev['person'] == person_id])
df = pev.copy()
df['next_vehicle'] = df.groupby('person')['vehicle'].shift(-1)
df[(df['vehicle'].str.contains(":")) & (df['next_vehicle'].str.contains('fleet'))]


# In[ ]:


df = ev.copy()
df = df[~df['person'].str.startswith('ride', na=True)]
df = df[~df['person'].str.startswith('TransitDriverAgent', na=True)]
df['next_event_type'] = df.groupby('person')['type'].shift(-1)
df[(df['type'] != 'actstart') & (df['next_event_type'].isnull())]


# In[ ]:


df = meaningful(ev[(ev['type'].isin(['PersonEntersVehicle', 'PersonLeavesVehicle', 'PathTraversal']))]).copy()
df['person'].fillna(df['driver'], inplace=True)
df['prev_event_type'] =  df.groupby('person')['type'].shift(1)
df['prev_vehicle'] =  df.groupby('person')['vehicle'].shift(1)
# df[df['person'] == '033000-2014000265406-3-6294822']
df[(df['vehicle'].str.contains(':')) & (df['type'] == 'PersonEntersVehicle') & (df['prev_event_type'] == 'PathTraversal') & (df['prev_vehicle'].str.contains('body', na=False))]


# In[127]:


#columns of events
columns = pd.DataFrame()
columns['value'] = ev.columns
columns[columns['value'].str.contains('mode', case=False)]


# In[17]:


data_filtered = ev.loc[ev.type.isin(
    # ["RefuelSessionEvent", "ChargingPlugInEvent", "ChargingPlugOutEvent", "actstart"]
    ["RefuelSessionEvent"]
)]
# display(meaningful(data_filtered))
charging_events = data_filtered[
    ["vehicle", "time", "type", "parkingTaz", "chargingPointType", "parkingType",
     "locationY", "locationX", "duration", "vehicleType", "person", "fuel",
     "parkingZoneId", "pricingModel", "actType"]
]
def process_data(ev):
    # ev["fuel"] = pd.to_numeric(ev["fuel"])
    rse = meaningful(ev[(ev['type'] == "actstart")])
    rse['ahour'] = rse['hour'] % 24
    # grouped = rse.groupby('ahour')['fuel'].agg('count')
    # df = grouped.to_frame().reset_index()
    # display(df)
    # return df.plot.bar(x='ahour', y='fuel')
    return rse[rse['actType'] == 'Home']

# charging_events[charging_events['time'] > 72000]

display(process_data(ev))


# In[77]:


display(process_data(ev2))

