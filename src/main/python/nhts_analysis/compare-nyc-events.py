import pandas as pd
import numpy as np
binnames = {1: "0-1", 2: "1-2", 3: "2-5", 4: "5-15", 5: "15+"}
distanceBinsMiles = np.array([0, 1, 2, 5, 15, 500])
path = "Data/nyc-baseline.events.csv.gz"
events = pd.read_csv(path)

def fixPathTraversals(PTs):
    PTs['duration'] = PTs['arrivalTime'] - PTs['departureTime']
    PTs['mode_extended'] = PTs['mode']
    PTs['isRH'] = PTs['vehicle'].str.contains('rideHail')
    PTs['isCAV'] = PTs['vehicleType'].str.contains('L5')
    PTs.loc[PTs['isRH'], 'mode_extended'] += '_RideHail'
    PTs.loc[PTs['isCAV'], 'mode_extended'] += '_CAV'
    PTs['occupancy'] = PTs['numPassengers']
    PTs.loc[PTs['mode_extended'] == 'car', 'occupancy'] += 1
    PTs.loc[PTs['mode_extended'] == 'walk', 'occupancy'] = 1
    PTs.loc[PTs['mode_extended'] == 'bike', 'occupancy'] = 1
    PTs['vehicleMiles'] = PTs['length'] / 1609.34
    PTs['passengerMiles'] = (PTs['length'] * PTs['occupancy']) / 1609.34
    PTs['totalEnergyInJoules'] = PTs['primaryFuel'] + PTs['secondaryFuel']
    PTs['gallonsGasoline'] = 0
    PTs.loc[PTs['primaryFuelType'] == 'gasoline',
            'gallonsGasoline'] += PTs.loc[PTs['primaryFuelType'] == 'gasoline', 'primaryFuel'] * 8.3141841e-9
    PTs.loc[PTs['secondaryFuelType'] == 'gasoline',
            'gallonsGasoline'] += PTs.loc[PTs['secondaryFuelType'] == 'gasoline', 'secondaryFuel'] * 8.3141841e-9
    PTs.drop(columns=['numPassengers', 'length'], inplace=True)
    return PTs

def updateTransitMode(MC):
    transit = MC['mode'] == 'walk_transit'
    railTrips = transit & MC['legModes'].str.contains("RAIL")
    ferryTrips = transit & ~railTrips & MC['legModes'].str.contains("FERRY")
    subwayTrips = transit & ~railTrips & ~ferryTrips & MC['legModes'].str.contains("SUBWAY")
    busTrips = transit & ~railTrips & ~ferryTrips & ~subwayTrips
    MC.loc[subwayTrips, 'mode'] = "subway"
    MC.loc[railTrips, 'mode'] = "rail"
    MC.loc[ferryTrips, 'mode'] = "ferry"
    MC.loc[busTrips, 'mode'] = "bus"
    MC['miles'] = MC['length'] / 1609.34
    MC['distBin'] = [binnames.get(val, 'Other') for val in np.digitize(MC['miles'], distanceBinsMiles)]
    return MC

PT = events.loc[(events['type'] == 'PathTraversal') & (events['length'] > 0)].dropna(how='all', axis=1)
MC = events.loc[(events['type'] == 'ModeChoice') & (events['length'] > 0)].dropna(how='all', axis=1)
AS = events.loc[(events['type'] == 'actstart')].dropna(how='all', axis=1)

del events

MC = updateTransitMode(MC)

PT['departureTime'] = PT['departureTime'].astype(int)
PT['arrivalTime'] = PT['arrivalTime'].astype(int)
PT = fixPathTraversals(PT)

gb = MC[['mode','person', 'distBin']].groupby(['mode', 'distBin']).agg('size').unstack().fillna(0.0)
tot = gb.sum(axis=0)
gb = gb / gb.sum(axis=0)
gb.loc['total',:] = tot/tot.sum()
gb.to_csv('outputs/mode-shares-nyc-baseline.csv')
tripsPerPerson = AS[['person','actType']].groupby('actType').agg('size') / 300424
tripsPerPerson.to_csv('outputs/trips-percapita-nyc-baseline.csv')
print("done")