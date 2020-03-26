import sys
import json
import plotly.graph_objects as go
import pandas as pd
import numpy as np
import required_module_installer as required
import matplotlib.pyplot as plt
import collections


def assignVehicleDayToLocationMatrix(day, timeBins, keys):
    timeUtilization = np.zeros((np.size(timeBins),1+max(keys.values())))
    distanceUtilization = np.zeros((np.size(timeBins),1+max(keys.values())))
    isRH = np.any(day['isRH'].values == True)
    if isRH:
        if np.any(day['isCAV'].values == True):
            timeUtilization[:,keys['idle']] += 1
        else:
            timeUtilization[:,keys['offline']] += 1
    else:
        timeUtilization[:,keys['parked']] += 1
    for idx, event in day.iterrows():
        lastEvent = (idx == (len(day.index) - 1))
        if lastEvent:
            chargingNext = False
            pickupNext = False
        else:
            chargingDirectlyNext = (day.iloc[idx + 1]['type'] == 'RefuelSessionEvent')
            if (idx == (len(day.index) - 2)):
                chargingOneAfter = False
            else:
                chargingOneAfter = (day.iloc[idx + 1]['type'] == 'ParkingEvent') & (day.iloc[idx + 2]['type'] == 'RefuelSessionEvent')
            chargingNext = chargingDirectlyNext | chargingOneAfter
            pickupNext = (day.iloc[idx + 1]['type'] == 'PathTraversal') & (day.iloc[idx + 1]['numPassengers'] >= 1)
        eventCharacteristics = classifyEventLocation(event, lastEvent, chargingNext, pickupNext, isRH)

        afterEventStart = (timeBins >= eventCharacteristics['start'])
        timeUtilization[afterEventStart,:] = 0.0

        duringEvent =  afterEventStart & (timeBins < eventCharacteristics['end'])
        timeUtilization[duringEvent,keys[eventCharacteristics['type']]] += 1.0

        if (event['type'] == 'PathTraversal'):
            #firstIndex = np.argmax(afterEventStart)
            if np.sum(duringEvent) > 0:
                meanDistancePerTime = event['length']/np.sum(duringEvent)
                distanceUtilization[duringEvent,keys[eventCharacteristics['type']]] += meanDistancePerTime/1609.34
            else:
                firstIndex = np.argmax(afterEventStart)
                distanceUtilization[firstIndex,keys[eventCharacteristics['type']]] += event['length']/1609.34

        if 'next-type' in eventCharacteristics:
            afterEventEnd = (timeBins >= eventCharacteristics['end'])
            timeUtilization[afterEventEnd,keys[eventCharacteristics['next-type']]] += 1.0
    return (timeUtilization, distanceUtilization)

def classifyEventLocation(event, lastEvent, chargingNext, pickupNext, isRH):
    if event.type == 'PathTraversal':
        if isRH == True:
            if event.numPassengers >= 1:
                if lastEvent:
                    if event.isCAV == True:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-full', 'next-type': 'idle'}
                    else:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-full', 'next-type': 'offline'}
                else:
                    if chargingNext:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-full', 'next-type': 'queuing'}
                    else:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-full', 'next-type': 'idle'}
            else:
                if lastEvent:
                    if event.isCAV:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-reposition', 'next-type': 'idle'}
                    else:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-reposition', 'next-type': 'offline'}
                else:
                    if chargingNext:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-tocharger', 'next-type': 'queuing'}
                    elif pickupNext:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-topickup', 'next-type': 'idle'}
                    else:
                        return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-reposition', 'next-type': 'idle'}
        else:
            if chargingNext:
                return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-tocharger', 'next-type': 'queuing'}
            else:
                return {'start': event.departureTime, 'end': event.arrivalTime, 'type': 'driving-full', 'next-type': 'queuing'}
    elif event.type == 'RefuelSessionEvent':
        if isRH == True:
            if event.primaryFuelLevel < 0.0:
                return {'start': event.time, 'end': event.time + event.duration, 'type': 'charging', 'next-type': 'offline'}
                #return {'start': event.time - event.duration, 'end': 30*3600, 'type': 'offline'}
            else:
                if lastEvent:
                    if event.isCAV == True:
                        return {'start': event.time, 'end': event.time + event.duration, 'type': 'charging', 'next-type': 'idle'}
                    else:
                        return {'start': event.time, 'end': event.time + event.duration, 'type': 'charging', 'next-type': 'offline'}
                else:
                    return {'start': event.time, 'end': event.time + event.duration, 'type': 'charging', 'next-type': 'idle'}
        else:
            if event.primaryFuelLevel < 0.0:
                return {'start': event.time, 'end': event.time + event.duration, 'type': 'charging', 'next-type': 'parked'}
                #return {'start': event.time - event.duration, 'end': 30*3600, 'type': 'offline'}
            else:
                return {'start': event.time, 'end': event.time + event.duration, 'type': 'charging', 'next-type': 'parked'}
    elif event.type == 'ParkingEvent':
        if isRH == True:
            return {'start': event.time, 'end': 30*3600, 'type': 'idle'}
        else:
            return {'start': event.time, 'end': 30*3600, 'type': 'parked'}

def get_pooling_metrics(filename):
    data = pd.read_csv(filename, sep=",", index_col=None, header=0)


    relevantEvents = data.loc[((data['type'] == 'RefuelSessionEvent') & (data['fuel'] > 0)) | (data['type'] == 'PathTraversal') | (data['type'] == 'ParkingEvent')].dropna(how='all', axis=1)
    cars = set(relevantEvents.loc[(relevantEvents['type'] == 'PathTraversal') & (relevantEvents['mode'] == 'car'), 'vehicle'])

    relevantEvents = relevantEvents.loc[relevantEvents['vehicle'].isin(cars), :]

    relevantEvents.loc[relevantEvents['type'] == 'PathTraversal','isRH'] = relevantEvents.loc[relevantEvents['type'] == 'PathTraversal','vehicle'].str.contains('rideHail')
    relevantEvents.loc[relevantEvents['type'] == 'PathTraversal','isCAV'] = relevantEvents.loc[relevantEvents['type'] == 'PathTraversal','vehicleType'].str.contains('L5')

    relevantEvents.loc[relevantEvents['type'] == 'PathTraversal','time'] = relevantEvents.loc[relevantEvents['type'] == 'PathTraversal','departureTime']  - 0.5
    relevantEvents.loc[relevantEvents['type'] == 'RefuelSessionEvent','time'] = relevantEvents.loc[relevantEvents['type'] == 'RefuelSessionEvent','time']  - relevantEvents.loc[relevantEvents['type'] == 'RefuelSessionEvent','duration'] + 0.5

    relevantEvents = relevantEvents.sort_values(by='time')
    modeChoice = data.loc[data['type'] == 'ModeChoice'].dropna(how='all', axis=1)

    ride_hail_mc = modeChoice[modeChoice['mode'].str.startswith('ride_hail')]
    ride_hail_mc_users = set(ride_hail_mc['person'])
    data2 = data[(data['type'].isin(['PathTraversal']) & data['vehicle'].str.startswith('rideHailVehicle')) |
                 (data['type'].isin(['ModeChoice', 'PersonEntersVehicle', 'PersonLeavesVehicle']) &
                  data['person'].isin(ride_hail_mc_users))]


    del data
    count_of_multi_passenger_pool_trips = 0
    count_of_one_passenger_pool_trips = 0
    count_of_solo_trips = 0
    count_of_unmatched_pool_requests = 0
    count_of_unmatched_solo_requests = 0
    sum_deadheading_distance_traveled = 0.0
    sum_ride_hail_distance_traveled = 0.0
    empty_distance = {}
    relocation = 0
    deadheading = 0
    startWT = {}
    countWT = 0
    sumWT = {}
    startTT = {}
    countTT = 0
    sumTT = {}
    mode_choice_attempt = {}
    person_has_shared_a_trip = {}
    passengers_in_veh = {}

    ct_nb_requests = {}
    chained_trips_requests = 0
    chained_trips_count = 0
    for row in data2.itertuples():
        person = row.person
        vehicle = row.vehicle
        mode = row.mode
        event = row.type
        passengers = row.numPassengers
        distance = row.length
        if event == "ModeChoice":
            if str(person).startswith("rideHailAgent"):
                print("ride hail driver with mode choice, does it ever occur !?")
            elif mode.startswith("ride_hail"):
                mode_choice_attempt[person] = mode
                startWT[person] = row.time
            elif person in mode_choice_attempt and not mode_choice_attempt[person].endswith("unmatched"):
                mode_choice_attempt[person] = mode_choice_attempt[person] + "_unmatched"
        elif event == "PersonEntersVehicle":
            if person not in mode_choice_attempt:
                continue
            chosen_mode = mode_choice_attempt[person]
            if chosen_mode.endswith("unmatched"):
                if chosen_mode.startswith("ride_hail_pooled"):
                    count_of_unmatched_pool_requests += 1
                else:
                    count_of_unmatched_solo_requests += 1
                del mode_choice_attempt[person]
            elif not vehicle.startswith("rideHailVehicle"):
                i = 0
                # agent started walking towards ride hail vehicle
            elif chosen_mode.startswith("ride_hail"):
                if chosen_mode == "ride_hail_pooled":
                    if vehicle not in passengers_in_veh:
                        passengers_in_veh[vehicle] = []
                    passengers_in_veh[vehicle].append(person)
                    for p in passengers_in_veh[vehicle]:
                        person_has_shared_a_trip[p] = (len(passengers_in_veh[vehicle]) > 1)
                    # chained trips metrics
                    if len(passengers_in_veh[vehicle]) == 1:
                        ct_nb_requests[vehicle] = 0
                    ct_nb_requests[vehicle] += 1
                else:
                    count_of_solo_trips += 1
                if person not in sumWT:
                    sumWT[person] = 0
                sumWT[person] = sumWT[person] + (row.time - startWT[person])
                del startWT[person]
                countWT = countWT + 1
                startTT[person] = row.time
        elif event == "PersonLeavesVehicle":
            if person not in mode_choice_attempt:
                print("agent cannot leave a vehicle if it did not go through a mode choice in the first place")
                continue
            chosen_mode = mode_choice_attempt[person]
            if not vehicle.startswith("rideHailVehicle"):
                i = 0
                # agent ended walking towards the ride hail vehicle
            elif chosen_mode.startswith("ride_hail"):
                if chosen_mode == "ride_hail_pooled":
                    passengers_in_veh[vehicle].remove(person)
                    if person_has_shared_a_trip[person] is True:
                        count_of_multi_passenger_pool_trips += 1
                    else:
                        count_of_one_passenger_pool_trips += 1
                    del person_has_shared_a_trip[person]
                    # chained trips metrics
                    if len(passengers_in_veh[vehicle]) == 0:
                        chained_trips_requests = (chained_trips_requests * chained_trips_count + ct_nb_requests[vehicle])/(chained_trips_count+1)
                        chained_trips_count += 1
                if person not in sumTT:
                    sumTT[person] = 0
                sumTT[person] = sumTT[person] + (row.time - startTT[person])
                del startTT[person]
                countTT = countTT + 1
            del mode_choice_attempt[person]
        elif event == "PathTraversal":
            if not vehicle.startswith("rideHailVehicle"):
                continue
            sum_ride_hail_distance_traveled += float(distance)
            if int(passengers) == 0:
                if vehicle in empty_distance:
                    relocation += empty_distance[vehicle]
                empty_distance[vehicle] = float(distance)
                sum_deadheading_distance_traveled += float(distance)
            elif vehicle in empty_distance:
                deadheading += empty_distance[vehicle]
                del empty_distance[vehicle]

    del data2
    tot_pool_trips = count_of_multi_passenger_pool_trips + count_of_one_passenger_pool_trips
    tot_solo_trips = count_of_solo_trips
    tot_rh_trips = tot_pool_trips + tot_solo_trips
    tot_rh_unmatched = count_of_unmatched_pool_requests + count_of_unmatched_solo_requests
    multi_passengers_trips_per_pool_trips = 0 if tot_pool_trips == 0 \
        else count_of_multi_passenger_pool_trips / tot_pool_trips
    multi_passengers_trips_per_ride_hail_trips = 0 if tot_rh_trips == 0 \
        else count_of_multi_passenger_pool_trips / tot_rh_trips
    unmatched_per_ride_hail_requests = 0 if (tot_rh_trips + tot_rh_unmatched) == 0 \
        else tot_rh_unmatched / (tot_rh_trips + tot_rh_unmatched)
    deadheading_per_ride_hail_trips = 0 if sum_ride_hail_distance_traveled == 0 \
        else sum_deadheading_distance_traveled / sum_ride_hail_distance_traveled

    out = {
        "ride_hail_requests": tot_rh_trips + tot_rh_unmatched,
        "ride_hail_solo_requests": tot_solo_trips + count_of_unmatched_solo_requests,
        "ride_hail_pool_requests": tot_pool_trips + count_of_unmatched_pool_requests,
        "multi_passenger_pool_trips": count_of_multi_passenger_pool_trips,
        "one_passenger_pool_trips": count_of_one_passenger_pool_trips,
        "solo_trips": count_of_solo_trips,
        "unmatched_pool_requests": count_of_unmatched_pool_requests,
        "unmatched_solo_requests": count_of_unmatched_solo_requests,
        "deadheading_distance_traveled": sum_deadheading_distance_traveled,
        "ride_hail_distance_traveled": sum_ride_hail_distance_traveled,
        "multi_passengers_trips_per_pool_trips": multi_passengers_trips_per_pool_trips,
        "multi_passengers_trips_per_ride_hail_trips": multi_passengers_trips_per_ride_hail_trips,
        "unmatched_per_ride_hail_requests": unmatched_per_ride_hail_requests,
        "deadheading_per_ride_hail_trips": deadheading_per_ride_hail_trips,
        "chained_trips_requests": chained_trips_requests,
        "chained_trips_count": chained_trips_count,
        "ridehail_wait_time": sum(sumWT.values())/countWT,
        "ridehail_travel_time": sum(sumTT.values())/countTT,
        "ridehail_distance_deadheading": deadheading,
        "ridehail_distance_relocation": relocation
    }

    resolutionInSeconds = 300
    timeBins = np.arange(start=0,stop=30*3600,step=resolutionInSeconds)

    keys = collections.OrderedDict({'driving-full':0,
                                'driving-reposition':1,
                                'driving-topickup':2,
                                'driving-tocharger':3,
                                'queuing':4,
                                'charging':5,
                                'idle':6,
                                'offline':7,
                                'parked':8})


    electricVehicleSet = set(relevantEvents.loc[relevantEvents['vehicleType'].str.startswith('ev') == True, 'vehicle'])
    pluginHybridVehicleSet = set(relevantEvents.loc[relevantEvents['vehicleType'].str.startswith('phev') == True, 'vehicle'])
    electricVehicles = relevantEvents.loc[relevantEvents['vehicle'].isin(electricVehicleSet),:].groupby('vehicle')


    ridehailVehicleSet = set(relevantEvents.loc[relevantEvents.isRH == True, 'vehicle'])
    ridehailVehicles = relevantEvents.loc[relevantEvents['vehicle'].isin(ridehailVehicleSet),:].groupby('vehicle')

    ridehailElectricVehicles = relevantEvents.loc[relevantEvents['vehicle'].isin(ridehailVehicleSet) & relevantEvents['vehicle'].isin(electricVehicleSet),:].groupby('vehicle')
    ridehailConventionalVehicles = relevantEvents.loc[relevantEvents['vehicle'].isin(ridehailVehicleSet) & ~relevantEvents['vehicle'].isin(electricVehicleSet),:].groupby('vehicle')
    personalElectricVehicles = relevantEvents.loc[relevantEvents['vehicle'].isin(cars) & ~relevantEvents['vehicle'].isin(ridehailVehicleSet) & relevantEvents['vehicle'].isin(electricVehicleSet.union(pluginHybridVehicleSet)),:].groupby('vehicle')

    timeUtilizationPV = np.zeros((np.size(timeBins),1+max(keys.values())))
    distanceUtilizationPV = np.zeros((np.size(timeBins),1+max(keys.values())))
    for vehicle, day in personalElectricVehicles:
        now = day.reset_index(drop=True)
        (timeUtilizationVehicle,distanceUtilizationVehicle) = assignVehicleDayToLocationMatrix(now, timeBins, keys)
        timeUtilizationPV += timeUtilizationVehicle
        distanceUtilizationPV += distanceUtilizationVehicle

    timeUtilizationEV = np.zeros((np.size(timeBins),1+max(keys.values())))
    distanceUtilizationEV = np.zeros((np.size(timeBins),1+max(keys.values())))
    for vehicle, day in ridehailElectricVehicles:
        now = day.reset_index(drop=True)
        (timeUtilizationVehicle,distanceUtilizationVehicle) = assignVehicleDayToLocationMatrix(now, timeBins, keys)
        timeUtilizationEV += timeUtilizationVehicle
        distanceUtilizationEV += distanceUtilizationVehicle

    timeUtilizationCV = np.zeros((np.size(timeBins),1+max(keys.values())))
    distanceUtilizationCV = np.zeros((np.size(timeBins),1+max(keys.values())))
    for vehicle, day in ridehailConventionalVehicles:
        now = day.reset_index(drop=True)
        (timeUtilizationVehicle,distanceUtilizationVehicle) = assignVehicleDayToLocationMatrix(now, timeBins, keys)
        timeUtilizationCV += timeUtilizationVehicle
        distanceUtilizationCV += distanceUtilizationVehicle



    key_names = list(keys.keys())
    utilizationOutput = dict()
    timeUtilizationEVsum = np.sum(timeUtilizationEV, axis=0)
    distanceUtilizationEVsum = np.sum(distanceUtilizationEV, axis=0)
    timeUtilizationCVsum = np.sum(timeUtilizationCV, axis=0)
    distanceUtilizationCVsum = np.sum(distanceUtilizationCV, axis=0)
    timeUtilizationPVsum = np.sum(timeUtilizationPV, axis=0)
    distanceUtilizationPVsum = np.sum(distanceUtilizationPV, axis=0)
    for idx, key in enumerate(key_names):
        utilizationOutput['time-RH-EV-'+key] = timeUtilizationEVsum[idx]/timeUtilizationEV.shape[0]
    for idx, key in enumerate(key_names[:4]):
        utilizationOutput['miles-RH-EV-'+key] = distanceUtilizationEVsum[idx]
    for idx, key in enumerate(key_names):
        utilizationOutput['time-RH-CV-'+key] = timeUtilizationCVsum[idx]/timeUtilizationCV.shape[0]
    for idx, key in enumerate(key_names[:4]):
        utilizationOutput['miles-RH-CV-'+key] = distanceUtilizationCVsum[idx]
    for idx, key in enumerate(key_names):
        utilizationOutput['time-Personal-EV-'+key] = timeUtilizationPVsum[idx]/timeUtilizationPV.shape[0]
    for idx, key in enumerate(key_names[:4]):
        utilizationOutput['miles-Personal-EV-'+key] = distanceUtilizationPVsum[idx]

    collected = pd.DataFrame(utilizationOutput, index=[filename.rsplit('/', 1)[0]])
    print(collected)
    print(utilizationOutput)
    collected.to_csv("{}/utilization_stats.csv".format(filename.rsplit('/', 1)[0]))


    f, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(14,10), sharex=True,gridspec_kw={'hspace': 0.06, 'wspace': 0.15})
    ax1.stackplot(timeBins/3600,np.transpose(timeUtilizationEV))
    ax2.stackplot(timeBins/3600,np.transpose(timeUtilizationCV))
    ax3.stackplot(timeBins/3600,np.transpose(distanceUtilizationEV)*12./1000)
    ax4.stackplot(timeBins/3600,np.transpose(distanceUtilizationCV)*12./1000)

    ax1.set_title('BEV Ridehail')
    ax2.set_title('Other Ridehail')

    ax3.legend(('driving-full','driving-reposition','driving-pickup','driving-charge','queuing','charging','idle','offline'))
    ax3.set_xlabel('Hour of Day')
    ax4.set_xlabel('Hour of Day')
    ax3.set_ylabel('Distance per hour (1000 mi)')
    ax1.set_ylabel('Vehicles')

    plt.savefig("{}/ridehail_utilization.png".format(filename.rsplit('/', 1)[0]), transparent=True, facecolor='white' )
    plt.close(f)
    f, (ax1, ax2) = plt.subplots(2, 1, figsize=(10,6), sharex=True,gridspec_kw={'hspace': 0.06, 'wspace': 0.15})
    ax1.stackplot(timeBins/3600,np.transpose(timeUtilizationPV[:,[0,3,4,5,8]]))
    ax2.stackplot(timeBins/3600,np.transpose(distanceUtilizationPV[:,[0,3,4,5,8]])*12./1000)
    ax1.set_title('Personal EVs')
    ax1.set_ylabel('Vehicles')
    ax2.set_xlabel('Hour of Day')
    ax2.set_ylabel('Distance per hour (1000 mi)')
    ax2.legend(('driving-full','driving-charge','queuing','charging','parked'))
    plt.savefig("{}/personal_utilization.png".format(filename.rsplit('/', 1)[0]), transparent=True, facecolor='white' )
    plt.close(f)

    pathTraversal = relevantEvents.loc[relevantEvents['type'] == 'PathTraversal'].dropna(how='all', axis=1)
    del relevantEvents

    pathTraversal['miles'] = pathTraversal['length'] / 1609.34
    pathTraversal['gallons'] = (pathTraversal['primaryFuel'] + pathTraversal['secondaryFuel']) * 8.3141841e-9
    pathTraversal['mpg'] = pathTraversal['miles'] / pathTraversal['gallons']
    pathTraversal['startingPrimaryFuelLevel'] = pathTraversal['primaryFuelLevel'] + pathTraversal['primaryFuel']
    pathTraversal['mode_extended'] = pathTraversal['mode']
    pathTraversal['isRH'] = pathTraversal['vehicle'].str.contains('rideHail')
    pathTraversal['isCAV'] = pathTraversal['vehicleType'].str.contains('L5')
    pathTraversal.loc[pathTraversal['isRH'], 'mode_extended'] += '_RH'
    pathTraversal.loc[pathTraversal['isCAV'], 'mode_extended'] += '_CAV'
    pathTraversal['trueOccupancy'] = pathTraversal['numPassengers']
    pathTraversal.loc[pathTraversal['mode_extended'] == 'car', 'trueOccupancy'] += 1
    pathTraversal.loc[pathTraversal['mode_extended'] == 'walk', 'trueOccupancy'] = 1
    pathTraversal.loc[pathTraversal['mode_extended'] == 'bike', 'trueOccupancy'] = 1
    pathTraversal['vehicleMiles'] = pathTraversal['length']/1609.34
    pathTraversal['passengerMiles'] = (pathTraversal['length'] * pathTraversal['trueOccupancy'])/1609.34

    modeChoiceTotals = modeChoice.groupby('mode').agg({'person': 'count', 'length': 'sum'})
    for mode in modeChoiceTotals.index:
        out[mode+'_counts'] = int(modeChoiceTotals.loc[mode,'person'])

    pathTraversalModes = pathTraversal.groupby('mode_extended').agg({'vehicleMiles':'sum','primaryFuel': 'sum','secondaryFuel': 'sum', 'passengerMiles':'sum'})

    for mode in pathTraversalModes.index:
        out['VMT_' + mode] = float(pathTraversalModes.loc[mode,'vehicleMiles'])
        out['PMT_' + mode] = float(pathTraversalModes.loc[mode,'passengerMiles'])
        out['Energy_' + mode] = float(pathTraversalModes.loc[mode,'primaryFuel'] + pathTraversalModes.loc[mode,'secondaryFuel'])

    primaryFuelTypes = pathTraversal.groupby('primaryFuelType').agg({'primaryFuel': 'sum'})
    secondaryFuelTypes = pathTraversal.groupby('secondaryFuelType').agg({'secondaryFuel': 'sum'})

    for fueltype in primaryFuelTypes.index:
        out['totalEnergy_' + fueltype] = float(primaryFuelTypes.loc[fueltype,'primaryFuel'])

    for fuelType in secondaryFuelTypes.index:
        if fuelType != 'None':
            out['totalEnergy_' + fuelType] += float(secondaryFuelTypes.loc[fueltype,'secondaryFuel'])

    out['rh_empty_miles'] = float(pathTraversal.loc[pathTraversal['isRH'] & (pathTraversal['trueOccupancy'] == 0),'miles'].sum())

    out['VMT_cav_empty'] = float(pathTraversal.loc[~pathTraversal['isRH'] & pathTraversal['isCAV'] & (pathTraversal['trueOccupancy'] == 0),'miles'].sum())

    out['Low_VMT'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L1'),'miles'].sum())

    out['High_VMT'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L3'),'miles'].sum())

    out['CAV_VMT'] = float(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L5'),'miles'].sum())
    return out


def get_pooling_sankey_diagram(_df, _name, _unit=1000.0):
    pool_tot_share = _df["multi_passengers_trips_per_ride_hail_trips"]
    pool_share = _df["multi_passengers_trips_per_pool_trips"]
    solo_share = (_df["solo_trips"]+_df["one_passenger_pool_trips"])/_df["ride_hail_requests"]
    unmatched_share = (_df["unmatched_pool_requests"]+_df["unmatched_solo_requests"])/_df["ride_hail_requests"]
    labels = ["pool requests: {:.1f}K".format(_df["ride_hail_pool_requests"]/_unit),
              "solo requests: {:.1f}K".format(_df["ride_hail_solo_requests"]/_unit),
              "pool: {:.1%} ({:.1%})".format(pool_tot_share, pool_share),
              "solo: {:.1%}".format(solo_share),
              "unmatched: {:.1%}".format(unmatched_share)]
    fig = go.Figure(data=[go.Sankey(
        # Define nodes
        node=dict(
            pad=15,
            thickness=15,
            line=dict(color="black", width=0.5),
            label=labels
        ),
        # Add links
        link=dict(
            source=[0, 0, 0, 1, 1],
            target=[2, 3, 4, 3, 4],
            value=[_df["multi_passenger_pool_trips"],
                   _df["one_passenger_pool_trips"],
                   _df["unmatched_pool_requests"],
                   _df["solo_trips"],
                   _df["unmatched_solo_requests"]]
        ))])
    fig.update_layout(title_text="Sankey Diagram For Pooling", font_size=10)
    fig.write_image("{}/pooling-metrics-sankey.png".format(_name))


if __name__ == '__main__':
    required.installAll()
    print("getting pooling metrics from events file: " + sys.argv[1])
    pooling_metrics = get_pooling_metrics(sys.argv[1])
    name = sys.argv[1].rsplit('/', 1)[0]
    with open('{}/pooling-metrics.json'.format(name), 'w') as f:
        json.dump(pooling_metrics, f)
    unit = sys.argv[2] if len(sys.argv) == 3 else 1000.0
    get_pooling_sankey_diagram(pooling_metrics, name, unit)
    print(json.dumps(pooling_metrics, indent=4))
    print("done")