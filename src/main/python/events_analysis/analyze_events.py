#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Created on Tue Jul 23 09:59:20 2019

@author: zaneedell
"""

import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import sys

if __name__ == '__main__':
    #%%

    output = pd.DataFrame()

    filename = sys.argv[1]
    events = pd.read_csv(filename, index_col=None, header=0)  # , nrows = 50000
    modeChoice = events.loc[events['type'] == 'ModeChoice'].dropna(how='all', axis=1)
    pathTraversal = events.loc[events['type'] == 'PathTraversal'].dropna(how='all', axis=1)
    enterParking = events.loc[events['type'] == 'ParkEvent'].dropna(how='all', axis=1)
    leavingParking = events.loc[events['type'] == 'LeavingParkingEvent'].dropna(how='all', axis=1)
    refuelSessionEvents = events.loc[events['type'] == 'RefuelSessionEvent'].dropna(how='all', axis=1)
    del events

    #%%
    pathTraversal['miles'] = pathTraversal['length'] / 1609.34
    pathTraversal['gallons'] = (pathTraversal['primaryFuel'] + pathTraversal['secondaryFuel']) * 8.3141841e-9
    pathTraversal['mpg'] = pathTraversal['miles'] / pathTraversal['gallons']
    pathTraversal['startingPrimaryFuelLevel'] = pathTraversal['primaryFuelLevel'] + pathTraversal['primaryFuel']
    #vehicles = pathTraversal.groupby('vehicle')[['miles', 'gallons', 'vehicleType']].agg({'miles': 'sum',
    #                                                                                   'gallons': 'sum',
    #                                                                                   'primaryFuel': 'sum',
    #                                                                                   'secondaryFuel': 'sum',
    #                                                                                   'vehicleType': 'max'})
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


    #%%

    modeChoiceTotals = modeChoice.groupby('mode').agg({'person': 'count',
                                                       'length': 'sum'})

    columns = []
    values = []

    for mode in modeChoiceTotals.index:
        columns.append(mode+'_counts')
        values.append(modeChoiceTotals.loc[mode,'person'])

    pathTraversalModes = pathTraversal.groupby('mode_extended').agg({'vehicleMiles':'sum','primaryFuel': 'sum','secondaryFuel': 'sum', 'passengerMiles':'sum'})

    for mode in pathTraversalModes.index:
        columns.append('VMT_' + mode)
        values.append(pathTraversalModes.loc[mode,'vehicleMiles'])
        columns.append('PMT_' + mode)
        values.append(pathTraversalModes.loc[mode,'passengerMiles'])
        columns.append('Energy_' + mode)
        values.append(pathTraversalModes.loc[mode,'primaryFuel'] + pathTraversalModes.loc[mode,'secondaryFuel'])

    primaryFuelTypes = pathTraversal.groupby('primaryFuelType').agg({'primaryFuel': 'sum'})
    secondaryFuelTypes = pathTraversal.groupby('secondaryFuelType').agg({'secondaryFuel': 'sum'})

    for fueltype in primaryFuelTypes.index:
        columns.append('totalEnergy_' + fueltype)
        values.append(primaryFuelTypes.loc[fueltype,'primaryFuel'])

    for fuelType in secondaryFuelTypes.index:
        if fuelType != 'None':
            values[columns.index('totalEnergy_' + fuelType)] += secondaryFuelTypes.loc[fueltype,'secondaryFuel']

    columns.append('rh_empty_miles')
    values.append(pathTraversal.loc[pathTraversal['isRH'],'miles'].sum())

    columns.append('VMT_cav_empty')
    values.append(pathTraversal.loc[~pathTraversal['isRH'] & pathTraversal['isCAV'],'miles'].sum())

    columns.append('Low_VMT')
    values.append(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L1'),'miles'].sum())

    columns.append('High_VMT')
    values.append(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L3'),'miles'].sum())

    columns.append('CAV_VMT')
    values.append(pathTraversal.loc[pathTraversal['vehicleType'].str.contains('L5'),'miles'].sum())

    df = pd.DataFrame(values).transpose()
    df.columns = columns
    df.to_csv('{}/analyzed_events.csv'.format(sys.argv[1].rsplit('/', 1)[0]))
    #%%


    #vehicles = vehicles.loc[vehicles['gallons']>0]
    #vehicles['mpg'] = vehicles['miles']/vehicles['gallons']
    #vehicles['mpg'].hist(by=vehicles['vehicleType'],figsize=(18,18))
    #plt.savefig('plots/vehicles-mpg-a-ht.png', transparent=True,bbox_inches='tight',dpi=200, facecolor='white')
    ##%%
    #vehicleTypes = pathTraversal.groupby('vehicleType')[['miles', 'gallons', 'vehicle']].agg({'miles': 'sum',
    #                                                                                   'gallons': 'sum',
    #                                                                                   'mpg': 'std',
    #                                                                                   'vehicle': pd.Series.nunique})
    #vehicleTypes['mpg_new'] = vehicleTypes['miles']/vehicleTypes['gallons']