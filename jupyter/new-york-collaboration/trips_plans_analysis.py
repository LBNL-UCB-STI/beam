#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import numpy as np
import pandas as pd
import matplotlib.pyplot as plt

#Compare pathtraversal share per different scenario

nRows = None
filepath = '/Users/cpoliziani/Downloads/EPI/Data'
filepath_res = '/Users/cpoliziani/Downloads/EPI/Trips_Plans'

datas = [pd.read_csv(filepath+'/generatedPlans_13M_v1.csv.gz',
                     nrows=nRows, 
                     # index_col = eventColumns, 
                     # compression='gzip',
                    ), 
         pd.read_csv(filepath+'/generatedTrips_13M_v3.csv.gz', 
                     nrows=nRows, 
                     # index_col = eventColumns, 
                     # compression='gzip',
                    )]

generatedPlans = datas[0][datas[0]['planSelected'] == True]
generatedTrips = datas[1]

print('len plans =', len(generatedPlans['personId']))
print('len trips =', len(generatedTrips['personId']))
print('max pers ID =', max(generatedPlans['personId']))

generatedPlans.sort_values(by=['personId','planElementIndex'])
generatedTrips['durations'] = generatedTrips['endTime']-generatedTrips['startTime']

vehicles_2 = []
vehicles = generatedTrips['transitVehicle']
for vehicle in vehicles:
	vehicles_2.append(vehicle[:9])
vehicles_2 = np.array(vehicles_2)

#--------------------------------------------------------------------------------------------------------------
print('-+-+-+-+-+-+-+-+-+-+-+-+--------- PLAN RESULTS: \n')
print('----------------LENGTH OF THE DATABASE: \n', len(generatedPlans['personId']))
print('----------------NUMBER OF PERSONS WITH A PLAN: \n', len(pd.unique(generatedPlans['personId'])))
print('----------------NUMBER OF ACTIVITIES: \n',len(generatedPlans['activityType'][(generatedPlans['planElementType'] =='activity')]))
print('----------------AVERAGE ACTIVITIES/PERSON: \n',len(generatedPlans['activityType'][(generatedPlans['planElementType'] =='activity')])/len(pd.unique(generatedPlans['personId'])))
print('----------------DISTRIBUTION ACTIVITIES: \n',generatedPlans['activityType'][(generatedPlans['planElementType'] =='activity')].value_counts(normalize = False))
print('----------------NUMBER OF LEGS (trips between activity pairs): \n',len(generatedPlans['activityType'][(generatedPlans['planElementType'] =='leg')]))
print('----------------AVERAGE LEGS/PERSON: \n',len(generatedPlans['activityType'][(generatedPlans['planElementType'] =='leg')])/len(pd.unique(generatedPlans['personId'])))
print('----------------NUMBER OF WALK-TRANSIT LEGS: \n',len(generatedPlans['personId'][generatedPlans['legMode'] =='walk_transit']))
print('----------------NUMBER OF PERSONS WITH A WALK-TRANSIT LEG: \n',len(pd.unique(generatedPlans['personId'][generatedPlans['legMode'] =='walk_transit'])))
print('----------------MODE SPLIT PER LEG:  \n',generatedPlans['legMode'].value_counts(normalize = False),generatedPlans['legMode'].value_counts(normalize = True))
print('----------------DISTRIBUTION LEGS INDEXES: \n  (1) means leg between act 0 and 2, (3) means leg between act 2 and 4 \n',generatedPlans['planElementIndex'][(generatedPlans['planElementType'] =='leg')].value_counts(normalize = False))
# ~ print('----------------DISTRIBUTION WALK TRANSIT LEGS INDEXES: \n',generatedPlans['planElementIndex'][(generatedPlans['legMode'] =='walk_transit')].value_counts(normalize = False))

#------------------------------ACTIVITY patterns
print('----------------ACTIVITY patterns')
activityPatterns = []
activityPattern = []
person_pre = 0
for plan in generatedPlans[['personId','planElementType','activityType']].iterrows():
	# ~ print(plan[0])
	person = plan[1]['personId']
	
	if person != person_pre:
		activityPatterns.append(activityPattern)
		activityPattern = []
		if plan[1]['planElementType'] == 'activity':
			activityPattern.append(plan[1]['activityType'])
	else:
		if plan[1]['planElementType'] == 'activity':
			activityPattern.append(plan[1]['activityType'])
	if person%100000 == 0:
		print(person)
	person_pre = person
a,d = np.unique(activityPatterns, return_counts = True)
# ~ print(pd.DataFrame([a,d]).transpose())
pd.DataFrame([a,d]).transpose().to_csv(filepath_res+'/ACTIVITY PATTERNS.csv')


print('-+-+-+-+-+-+-+-+-+-+-+-+--------- WALK TRANSIT TRIPS RESULTS \n')

print('----------------LENGTH OF THE DATABASE: \n', len(generatedTrips['personId']))
print('----------------NUMBER OF PERSONS: \n',len(pd.unique(generatedTrips['personId'])))
print('----------------AVERAGE TRIPS/PERSONS - with transfer:\n',len(generatedTrips['personId'])/len(pd.unique(generatedTrips['personId'])))
print('----------------TOTAL TRANSIT TRIPS  - with transfer: \n',len(generatedTrips['personId']))
# ~ print('----------------ROUTE ID SPLIT - with transfer: \n',generatedTrips['transitRouteId'].value_counts(normalize = False))
generatedTrips['transitRouteId'].value_counts(normalize = False).to_csv(filepath_res+'/RIDERSHIP - with transfer.csv')
print('----------------TRANSIT SPLIT - with transfer: \n',generatedTrips['mode'].value_counts(normalize = False))
print('----------------AGENCY SPLIT - with transfer: \n',generatedTrips['transitAgency'].value_counts(normalize = False))
print('----------------AGENCY SPLIT2 - with transfer: \n',pd.DataFrame(vehicles_2,columns = ['vehicles'])['vehicles'].value_counts(normalize = False))
# ~ print('----------------DISTRIBUTION ALTERNATIVES - with transfer: \n',generatedTrips['alternatives'].value_counts(normalize = False))
generatedTrips['alternatives'].value_counts(normalize = False).to_csv(filepath_res+'/DISTRIBUTION ALTERNATIVES - with transfer.csv')
# ~ print('----------------DISTRIBUTION NUMBER OF STOPS PER TRIP - with transfer: \n',(generatedTrips['transitStopEnd']-generatedTrips['transitStopStart']).value_counts(normalize = False))
(generatedTrips['transitStopEnd']-generatedTrips['transitStopStart']).value_counts(normalize = False).to_csv(filepath_res+'/DISTRIBUTION NUMBER OF TRAVELED STOPS PER TRANSIT TRIP - with transfer.csv')
print('----------------DISTRIBUTION LEGS INDEXES - with transfer:  \n (1) means leg between act 0 and 2, (3) means leg between act 2 and 4 \n',generatedTrips['planElementIndexOfLeg'].value_counts(normalize = False))
# ~ print('----------------DISTRIBUTION LEGS INDEXES Buses - with transfer:  \n',generatedTrips['planElementIndexOfLeg'][generatedTrips['mode']=='BUS'].value_counts(normalize = False))
# ~ print('----------------DISTRIBUTION LEGS INDEXES Subway - with transfer:  \n',generatedTrips['planElementIndexOfLeg'][generatedTrips['mode']=='SUBWAY'].value_counts(normalize = False))

#------------------------------MULTIMODAL TRANSIT SPLIT

tripType = []
tripType_current = []
person_pre = 0
leg_pre = 0
for trip in generatedTrips[['personId','mode','planElementIndexOfLeg']].iterrows():
	person = trip[1]['personId']
	leg = trip[1]['planElementIndexOfLeg']
	if person != person_pre or leg != leg_pre:
		
		tripType.append(list(np.unique(tripType_current)))
		tripType_current = []
		tripType_current.append(trip[1]['mode'])

	else:
		tripType_current.append(trip[1]['mode'])

	if person%100000 == 0:
		print(person)
	person_pre = person
	leg_pre = leg
print('----------------MULTIMODAL TRANSIT SPLIT: \n') 
a,d = np.unique(tripType, return_counts = True)
pd.DataFrame([a,d]).transpose().to_csv(filepath_res+'/MULTIMODAL TRANSIT SPLIT.csv')


#------------------------------PER PERSON without transfer ANALYSIS

LegIndexes = []
LegIndexes_current = []
# ~ LegIndexes_bus = []
# ~ LegIndexes_subway = []
LegIndexes_current_bus = []
LegIndexes_current_subway = []
transfersPerPerson = []
transfersPerPerson_bus = []
transfersPerPerson_subway = []
person_pre = 0
for trip in generatedTrips[['personId','mode','planElementIndexOfLeg']].iterrows():
	person = trip[1]['personId']

	if person != person_pre:
		if len(np.unique(LegIndexes_current)) > 0: 
			for leg in np.unique(LegIndexes_current):
				transfersPerPerson.append(LegIndexes_current.count(leg)-1)
				
		if len(np.unique(LegIndexes_current_bus)) > 0:
			for leg in np.unique(LegIndexes_current_bus):
				transfersPerPerson_bus.append(LegIndexes_current_bus.count(leg)-1)
				
		if len(np.unique(LegIndexes_current_subway)) > 0: 
			for leg in np.unique(LegIndexes_current_subway):
				transfersPerPerson_subway.append(LegIndexes_current_subway.count(leg)-1)
				
		for LegIndexe in np.unique(LegIndexes_current):
			LegIndexes.append(LegIndexe)
			
		LegIndexes_current = []
		LegIndexes_current_bus = []
		LegIndexes_current_subway = []
		if trip[1]['mode'] == 'BUS':
			LegIndexes_current_bus.append(trip[1]['planElementIndexOfLeg'])
		elif trip[1]['mode'] == 'SUBWAY':
			LegIndexes_current_subway.append(trip[1]['planElementIndexOfLeg'])
		LegIndexes_current.append(trip[1]['planElementIndexOfLeg'])
	else:
		if trip[1]['mode'] == 'BUS':
			LegIndexes_current_bus.append(trip[1]['planElementIndexOfLeg'])
		elif trip[1]['mode'] == 'SUBWAY':
			LegIndexes_current_subway.append(trip[1]['planElementIndexOfLeg'])
		LegIndexes_current.append(trip[1]['planElementIndexOfLeg'])

	if person%100000 == 0:
		print(person)
	person_pre = person

print('----------------AVERAGE NUMBER OF TRANSFERS PER LEG: \n') 
print(np.mean(transfersPerPerson))
# ~ print('----------------DISTRIBUTION NUMBER OF TRANSFERS PER LEG: \n') 
a,d = np.unique(transfersPerPerson, return_counts = True)
pd.DataFrame([a,d]).transpose().to_csv(filepath_res+'/TRANSFER DISTRIBUTION.csv')
print('----------------AVERAGE NUMBER OF TRANSFERS PER LEG with bus: \n') 
print(np.mean(transfersPerPerson_bus))
# ~ print('----------------DISTRIBUTION NUMBER OF TRANSFERS PER LEG with bus: \n') 
a,d = np.unique(transfersPerPerson_bus, return_counts = True)
pd.DataFrame([a,d]).transpose().to_csv(filepath_res+'/TRANSFER DISTRIBUTION _bus.csv')
print('----------------AVERAGE NUMBER OF TRANSFERS PER LEG with subway: \n') 
print(np.mean(transfersPerPerson_subway))
# ~ print('----------------DISTRIBUTION NUMBER OF TRANSFERS PER LEG with subway: \n') 
a,d = np.unique(transfersPerPerson_subway, return_counts = True)
pd.DataFrame([a,d]).transpose().to_csv(filepath_res+'/TRANSFER DISTRIBUTION _subway.csv')
print('----------------TOTAL NUMBER OF SIMULATED LEGS - without transfers:  \n') 
print(len(LegIndexes))
print('----------------DISTRIBUTION LEGS INDEXES - without transfers:  \n') 
a,d = np.unique(LegIndexes, return_counts = True)
print(pd.DataFrame([a,d]).transpose())
# ~ print('----------------TOTAL NUMBER OF SIMULATED LEGS including bus (e.g. bus, bus+subway..)- without transfers:  \n') 
# ~ print(len(LegIndexes_bus))
# ~ print('----------------DISTRIBUTION LEGS INDEXES for legs including Bus trips (e.g. bus, bus+subway..) - without transfer on same mode:  \n') 
# ~ a,d = np.unique(LegIndexes_bus, return_counts = True)
# ~ print(pd.DataFrame([a,d]).transpose())
# ~ print('----------------TOTAL NUMBER OF SIMULATED LEGS including subway (e.g. subway, bus+subway..)- without transfers:  \n') 
# ~ print(len(LegIndexes_subway))
# ~ print('----------------DISTRIBUTION LEGS INDEXES for legs including Subway trips (e.g. subway, bus+subway..) - without transfer on same mode:  \n') 
# ~ a,d = np.unique(LegIndexes_subway, return_counts = True)
# ~ print(pd.DataFrame([a,d]).transpose())








# ~ print('------------------SAVE TRIPS AND PLANS FOR SPECIFIC PERSONS')
# ~ generatedPlans[generatedPlans['personId'] == 8].to_csv(filepath_res+'plansPerson8.csv')
# ~ generatedTrips[generatedTrips['personId'] == 8].to_csv(filepath_res+'tripsPerson8.csv')
# ~ generatedPlans[generatedPlans['personId'] == 10].to_csv(filepath_res+'plansPerson10.csv')
# ~ generatedTrips[generatedTrips['personId'] == 10].to_csv(filepath_res+'tripsPerson10.csv')
# ~ generatedPlans[generatedPlans['personId'] == 20].to_csv(filepath_res+'plansPerson20.csv')
# ~ generatedTrips[generatedTrips['personId'] == 20].to_csv(filepath_res+'tripsPerson20.csv')

# ~ print(generatedPlans.pivot_table(generatedTrips, index = ['transitAgency','mode'], aggfunc=len))

# ~ datas[0].to_csv('/Users/cpoliziani/Downloads/EPI/Trips_Plans/generatedPlans_13M.csv')
# ~ datas[1].to_csv('/Users/cpoliziani/Downloads/EPI/Trips_Plans/generatedTrips_13M.csv')

'''
OBSERVATIONS
-SECONDARY ACTIVITIES NOT GOOD (WORK-SHOPPING-WORK) TOO MUCH
-TOO MANY BUS TRANSERS
-ONLY 6.5M PERSONS
-NOT CORRECT BUS VS SUBWAY IN THE TRIPS
-MODAL SPLIT IN PLANS SEEMS OK
-5.665.455 PERSONS CHOOSED WALK-TRANSIT; 5.063.545 PERSONS HAVE A TRIP WITH TRANSIT...ABOUT 600K DIDN'T FOUND A VALID TRANSIT



So far I've seen some problems that cause wrong results for 100% pop:

Secondary activities are always inserted in the middle of another activity. For example home-work-home becomes home-work-shopping-work-home. I think that does not make sense on most cases, people do secondary activities while coming back from work or before work, unless they go for a meal somewhere. This create a lot of trips for each person
Moreover, for each trip many transit legs are created. For example people change many vehicles for going from point a to b.

This lead to have people that use even 30 different vehicles during the day.

I think that can definitely affect also the bus vs subway split.

'''


'''
DURTIONS TRIPS AND LEGS
'''

