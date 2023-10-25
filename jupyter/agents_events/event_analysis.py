#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd

import util.beam_data as bd
from util.data import find_last_created_dir
from util.data import meaningful


# In[2]:


#Loading beam events from beam_out. It searches for the last created dir in beam output directory
root = "../beam_root"
pd.set_option('display.max_rows', 1000)
beam_out_path = None
# beam_out_path = "sfbay/gemini-scenario-5-calibrate5p__2022-12-30_12-46-10_nyq"
beam_out = find_last_created_dir(f"{root}/output", level = 1, num = 0) if beam_out_path is None else f"{root}/output/{beam_out_path}"
print(f"Using beam out dir: {beam_out}")
it = 0
ev = bd.load_events(f"{beam_out}/ITERS/it.{it}/{it}.events.csv.gz")

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


display(mc[mc['availableAlternatives'].str.contains("ride_hail_transit", case=False, na=False)])
mc[mc['mode'] == "ride_hail"]


# In[ ]:


pte_pooled = pte[pte['vehicle'].str.startswith('ride') & (pte['numPassengers'] > 1)].copy()
pte_pooled


# In[ ]:


# a particular person events
person_id = '016000-2015000256008-0-6543940'

meaningful(ev[(ev['person'] == person_id) | (ev['vehicle'] == f'body-{person_id}') | (ev['driver'] == person_id)
              | ev['riders'].str.contains(f'{person_id}:') | ev['riders'].str.contains(f':{person_id}') | (ev['riders'] == person_id)])


# In[ ]:


# events related to freight fleets
freight_ev = ev[(ev['vehicle'].str.startswith("freight", na=False)) | (ev['person'].str.startswith("freight", na=False))][['person', 'actType', 'time', 'type', 'vehicle', 'mode',
      'locationY', 'locationX', 'secondaryFuelLevel', 'startX',
      'startY', 'endX', 'endY', 'vehicleType',
      'arrivalTime', 'departureTime'
      ]]

freight_ev[freight_ev['type'].isin(['actstart', 'actend', 'PathTraversal'])]


# In[4]:


# event types
ev['type'].unique()


# In[ ]:


# bike PTE
bikes = pte[(pte['mode'] == 'bike')][['vehicle', 'driver', 'time', 'length', 'departureTime', 'arrivalTime',
                                      'linkTravelTime', 'startX', 'startY', 'endY', 'endX', 'vehicleType', 'links']].copy()
# bikes[bikes['vehicle'].str.startswith('bay')]


# In[ ]:


# WALK pte and walk travel time
walks = pte[(pte['mode'] == 'walk')][['vehicle', 'driver', 'time', 'length', 'departureTime', 'arrivalTime',
                                      'linkTravelTime', 'startX', 'startY', 'endY', 'endX', 'vehicleType', 'links']].copy()
walks['travel_time'] = walks["arrivalTime"] - walks["departureTime"]
walks["travel_time"].sum()

