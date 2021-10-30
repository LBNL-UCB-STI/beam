import pandas as pd

import numpy as np
import scipy.ndimage
import geopandas as gpd

# %%
trips_all = pd.read_csv('https://beam-outputs.s3.amazonaws.com/new_city/nhts/trippub.csv.gz',
                    usecols=[0, 1, 2, 3, 4, 5, 6, 7, 17, 26, 28, 58, 59, 60, 61, 64, 69, 70, 71, 72, 73, 74, 84, 89, 93,
                             102, 103])

persons_all = pd.read_csv('https://beam-outputs.s3.amazonaws.com/new_city/nhts/perpub.csv.gz')


#%%

modenames = {1:'Walk',2:'Bike',3:'Car',4:'Car',5:'Car',6:'Car',7:'Car',8:'Car',9:'Car',11:'Bus',
             13:'Bus', 14:'Bus', 15:'Rail', 16:'Subway',17:'Ridehail',20:'Ferry'}

for cbsa in ['35620']:#persons_all.HH_CBSA.unique():
    trips = trips_all.loc[(trips_all['HH_CBSA'] == cbsa) , :]


    valid = (trips.TRPMILES > 0) & (trips.TDWKND == 2) & (trips.TRPTRANS != 19)
    
    
    
    trips = trips.loc[valid, :]
    trips['UniquePID'] = trips.HOUSEID * 100 + trips.PERSONID
    trips['startHour'] = np.floor(trips.STRTTIME / 100) + np.mod(trips.STRTTIME, 100) / 60
    trips['endHour'] = np.floor(trips.ENDTIME / 100) + np.mod(trips.ENDTIME, 100) / 60
    trips['toWork'] = (trips.WHYTO == 3) | (trips.WHYTO == 4)
    trips['fromWork'] = (trips.WHYFROM == 3) | (trips.WHYFROM == 4)
    trips['mode'] = [modenames.get(val, 'Other') for val in trips['TRPTRANS']]
    trips['distbin'] = np.digitize(trips.TRPMILES,np.logspace(-1.5,2,30))
    
    
    workInvolved = (trips.TRIPPURP == 'HBW')
    workTrips = trips.loc[workInvolved, :]

    persons = persons_all.loc[(persons_all['HH_CBSA'] == cbsa), :]
    
    valid = (persons.TRAVDAY > 1) & (persons.TRAVDAY < 7)
    
    persons = persons.loc[valid,:]
    persons['UniquePID'] = persons.HOUSEID * 100 + persons.PERSONID
    
    workPIDs = set(workTrips.UniquePID)
    workerTrips = trips.loc[trips['UniquePID'].isin(workPIDs),:]

    
    bydist = trips[['mode','distbin','WTTRDFIN']].groupby(['distbin','mode']).sum().unstack().fillna(0)['WTTRDFIN']
    bydist['dist'] = ((np.append(np.logspace(-1.5,2,30),150)))
    bydist.set_index('dist', drop=True, inplace=True)

#%%

events = pd.read_csv('https://beam-outputs.s3.amazonaws.com/output/newyork/nyc-200k-baseline__2020-09-07_17-51-04_naj/ITERS/it.10/10.events.csv.gz', index_col=None)


pathTraversal = events.loc[(events['type'] == 'PathTraversal')].dropna(how='all', axis=1) #(events['mode'] == 'car')


modechoice = events.loc[events.type=='ModeChoice'].dropna(how='all', axis=1)

mccats = {'walk_transit':'transit', 'drive_transit':'transit','ride_hail_transit':'transit','ride_hail':'ridehail','ride_hail_pooled':'ridehail',
          'walk':'walk','car':'car','bike':'bike'}

modechoice['mode'] = [mccats.get(val, 'Other') for val in modechoice['mode']]

bydistbeam = modechoice[['mode','distbin','person']].groupby(['distbin','mode']).count().unstack().fillna(0)['person']

pathTraversal['mode_extended'] = pathTraversal['mode']
pathTraversal['isRH'] = ((pathTraversal['driver'].str.contains('rideHail')== True))
pathTraversal['isCAV'] = ((pathTraversal['vehicleType'].str.contains('CAV')==True))
pathTraversal.loc[pathTraversal['isRH'], 'mode_extended'] += '_RH'
pathTraversal.loc[pathTraversal['isCAV'], 'mode_extended'] += '_CAV'
# pathTraversal.loc[pathTraversal.mode == "tram","mode"] = "rail"

pathTraversal['trueOccupancy'] = pathTraversal['numPassengers']
pathTraversal.loc[pathTraversal['mode_extended'] == 'car', 'trueOccupancy'] += 1
pathTraversal.loc[pathTraversal['mode_extended'] == 'walk', 'trueOccupancy'] += 1
pathTraversal.loc[pathTraversal['mode_extended'] == 'bike', 'trueOccupancy'] += 1
pathTraversal['vehicleMiles'] = pathTraversal['length']/1609.34
pathTraversal['passengerMiles'] = (pathTraversal['length'] * pathTraversal['trueOccupancy'])/1609.34
pathTraversal['vehicleHours'] = (pathTraversal['arrivalTime'] - pathTraversal['departureTime'])/3600.
pathTraversal['passengerHours'] = pathTraversal['vehicleHours'] * pathTraversal['trueOccupancy']

pathTraversal['hour'] = np.floor(pathTraversal.time / 3600.0)


#%%
mcd = pd.read_csv('https://beam-outputs.s3.amazonaws.com/output/newyork/nyc-200k-baseline__2020-09-07_17-51-04_naj/ITERS/it.10/10.modeChoiceDetailed.csv.gz')
mcdg = mcd[['altType','personId','altTime']].groupby(['personId','altTime']).agg('first')


#%%
from gtfspy import import_gtfs
from gtfspy import gtfs
from gtfspy import osm_transfers
import os

imported_database_path = "test_db_kuopio.sqlite"
if not os.path.exists(imported_database_path):  # reimport only if the imported database does not already exist
    print("Importing gtfs zip file")
    import_gtfs.import_gtfs(["../../../../test/input/newyork/r5-prod/NYC_Subway_20200109.zip",
                             "../../../../test/input/newyork/r5-prod/MTA_Manhattan_20200123.zip",
                             "../../../../test/input/newyork/r5-prod/MTA_Bronx_20200121.zip",
                             "../../../../test/input/newyork/r5-prod/MTA_Brooklyn_20200118.zip",
                             "../../../../test/input/newyork/r5-prod/MTA_Queens_20200118.zip",
                             "../../../../test/input/newyork/r5-prod/MTA_Staten_Island_20200118.zip"],
                             # "../../../../test/input/newyork/r5-prod/NJ_Transit_Bus_20200210.zip"],  # input: list of GTFS zip files (or directories)
                            imported_database_path,  # output: where to create the new sqlite3 database
                            print_progress=True,  # whether to print progress when importing data
                            location_name="New York")
                            
G = gtfs.GTFS(imported_database_path)
route_shapes = G.get_all_route_shapes()
gdf = pd.DataFrame(route_shapes).set_index('name')


#%%

occ = pd.read_csv("https://beam-outputs.s3.amazonaws.com/output/newyork/nyc-200k-baseline__2020-09-07_17-51-04_naj/ITERS/it.10/10.transitOccupancyByStop.csv")

#%%
goodroute = ""
goodcol = ""
for col in occ.columns:
    if col.startswith("MTA") | col.startswith("NYC_Subway"):
        route = col.split('_')[-2]
        if route in gdf.index:
            print(route)
            goodroute = route
            goodcol = col
            print(occ[col])
        else:
            print("BAD")

#%%
occ2 = occ.unstack().dropna(how='all').to_frame()
occ2['line'] = occ2.index.get_level_values(0).str.split('_').str[-2]
occ2['stop'] = occ2.index.get_level_values(1)

routeridership = occ2.groupby(['line','stop']).agg(['sum','size'])


#%%
from zipfile import ZipFile

gtfs = dict()
for zipname in os.listdir('../../../../test/input/newyork/r5-prod/'):
    if zipname.endswith(".zip"): 
        zip_file = ZipFile('../../../../test/input/newyork/r5-prod/' + zipname)
        files = dict()
        text_files = zip_file.infolist()
        for text_file in text_files:
            files[text_file.filename.split('.')[0]] = pd.read_csv(zip_file.open(text_file.filename))
        gtfs[zipname.split('.')[0]] = files

#%%
rows = []
for col in occ.columns:
    if ':' in col:
        agency = col.split(':')[0]
        trip = col.split(':')[1]
        if agency in gtfs:
            val = gtfs[agency]['trips'].loc[gtfs[agency]['trips'].trip_id == trip]
            if val.size > 0:
                val = val.iloc[0].to_dict()
                val['occ'] = occ.loc[~occ[col].isna(),col].values
                val['nStops'] = len(val['occ'])
                val['agency'] = agency
                rows.append(val)

b = pd.DataFrame(rows)


#%%
collected = dict()

for name, group in b.groupby(['route_id','direction_id','shape_id','nStops']):
    try:
        collected[name] = {'occupancy': group['occ'].mean(), 'headsign': group.iloc[0]['trip_headsign'], 'trips':group.shape[0]}
    except:
        bad = group
        print("BAD "+ group.iloc[0]['trip_headsign'])

collected = pd.DataFrame(collected).transpose()
collected.index.set_names(['route_id','direction_id','shape_id','nStops'],  inplace=True).reset_index()
#%%
shapes = gtfs[agency]['shapes'].groupby('shape_id')

#%%

shape = 'M010235'
gdf = gpd.GeoDataFrame(shapes.get_group(shape), geometry = gpd.points_from_xy(shapes.get_group(shape).shape_pt_lon,shapes.get_group(shape).shape_pt_lat))


#%%
campoModes = {1:"Walk",2:"Car",3:"Car",4:"Car",5:"Car",6:"Car",7:"Car",8:"Car",9:"Car",10:"Car",11:"Car",12:"Transit",14:"Ridehail",15:"Bike"}
campo = pd.read_csv('/Users/zaneedell/Downloads/austin-trips.csv')
date = pd.to_datetime({"year": campo['5. Year'], "month": campo['3. Month'], "day": campo['4. Day']})
campo['date'] = date
campo['weekday'] = campo.date.dt.weekday
campo['mode'] = campo.loc[:,'26. Mode of Travel'].fillna(0).apply(lambda x: campoModes.get(int(x),'Other'))
campo.loc[campo['27. Other Mode'] == "RIDE AUSTIN", 'mode'] = "Ridehail"
campo.loc[campo['27. Other Mode'] == "UBER ", 'mode'] = "Ridehail"
campo.loc[campo['27. Other Mode'] == "UBER", 'mode'] = "Ridehail"
campo.loc[campo['27. Other Mode'] == "TRANSIT/TRAIN", 'mode'] = "Transit"
campo.loc[campo['27. Other Mode'] == "BUS/TRANSIT", 'mode'] = "Transit"
campo.loc[campo['27. Other Mode'] == "TRAIN", 'mode'] = "Transit"
campo.loc[campo['27. Other Mode'] == "Tram", 'mode'] = "Transit"

campo['isWeekday'] = campo.date.dt.dayofweek <= 4
campo['29. HH Members'].fillna(1.0, inplace=True)


campo['weight'] = 1
campo.loc[campo['mode'] == "Car", "weight"] = campo.loc[campo['mode'] == "Car",'28. Number of People']
campo.loc[campo['weight'] > 50, 'weight'] = 1
campo.loc[(campo['mode'] == "Car") & (campo['weight'] == 2), 'mode'] = 'HOV2'
campo.loc[(campo['mode'] == "Car") & (campo['weight'] > 2), 'mode'] = 'HOV3'
modesplitCampo = campo.loc[campo.isWeekday, :].groupby('mode')['weight'].sum()
(modesplitCampo / modesplitCampo.loc[modesplitCampo.index != 'Other'].sum()).to_csv('/Users/zaneedell/Desktop/austin-gpra-pipeline/modesplit-pass.csv')
modesplitCampo2 = campo.loc[campo.isWeekday, :].groupby('mode')['29. HH Members'].sum()
(modesplitCampo2 / modesplitCampo2.loc[modesplitCampo2.index != 'Other'].sum()).to_csv('/Users/zaneedell/Desktop/austin-gpra-pipeline/modesplit-hh.csv')
(campo.loc[campo.isWeekday, 'mode'].value_counts() / sum((campo['mode'] != "Other") & campo.isWeekday)).to_csv('/Users/zaneedell/Desktop/austin-gpra-pipeline/modesplit-vehicleweighted.csv')

campo.to_csv('/Users/zaneedell/Desktop/austin-gpra-pipeline/survey.csv.gz', compression='gzip')





#%%
from shapely import geometry

def combineRows(rows):
    out = [{'personId': rows['6. Person Number'].iloc[0],
            'purpose': rows['25. Purpose'].iloc[0],
            'startLoc': geometry.Point(rows['21. Longitude'].iloc[0], rows['22. Latitude'].iloc[0]),
            'startCity': rows['12. Location City'].iloc[0],
            'prevPurpose': 1,
            'departureTime': rows['53. Departure Hour'].iloc[0] + rows['54. Departure Minute'].iloc[0] / 60
            }]
    ind = 1
    for idx in range(len(rows)-1):
        trip = dict()
        trip['personId'] = rows['6. Person Number'].iloc[ind]
        trip['startLoc'] = geometry.Point(rows['21. Longitude'].iloc[ind], rows['22. Latitude'].iloc[ind])
        trip['startCity'] = rows['12. Location City'].iloc[ind]
        tripPurp = rows['25. Purpose'].iloc[ind]
        out[-1]['purpose'] = tripPurp
        trip['prevPurpose'] = tripPurp
        if tripPurp == 11:
            out[-1]['mode'] = out[-1]['mode'] = rows['mode'].iloc[ind]
        else:
            if 'mode' in out[-1]:
                out[-1]['mode'] += ('->' + rows['mode'].iloc[ind])
            else:
                out[-1]['mode'] = rows['mode'].iloc[ind]
            trip['departureTime'] = rows['53. Departure Hour'].iloc[ind] + rows['54. Departure Minute'].iloc[ind] / 60
            out[-1]['endLoc'] = geometry.Point(rows['21. Longitude'].iloc[ind], rows['22. Latitude'].iloc[ind])
            out[-1]['endCity'] = rows['12. Location City'].iloc[ind]
            out[-1]['arrivalTime'] = rows['51. Arrival Hour'].iloc[ind] + rows['52. Arrival Minute'].iloc[ind] / 60
            if ind < (len(rows) -1):
                out.append(trip)
        ind += 1
    return pd.DataFrame(out)


out = campo.groupby(['2. Sample Number', '6. Person Number']).apply(combineRows)
out['tourType'] = "NHB"
out.loc[out.prevPurpose == 1, "tourType"] = "HBO"
out.loc[out.purpose == 1, "tourType"] = "HBO"
out.loc[(out.prevPurpose == 1) & (out.purpose == 3), "tourType"] = "HBW"
out.loc[(out.prevPurpose == 3) & (out.purpose == 1), "tourType"] = "HBW"
modeSplitByPurpose = out.value_counts(['tourType','mode']).unstack(level=0).fillna(0)