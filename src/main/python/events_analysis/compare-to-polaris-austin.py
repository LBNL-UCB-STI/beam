import pandas as pd
import matplotlib.pyplot as plt
import numpy as np
import geopandas as gpd

s3 = 'https://beam-outputs.s3.amazonaws.com/output/austin/austin-prod-200k-flowCap-0.1-speedScaling-1.0-new_vehicles__2020-08-30_19-22-52_lmi/'

#%%

incomebins = [-1,20000,35000,50000,75000,100000,150000,1e10]

urbansim = "../../../../test/input/texas/urbansim_v2/"
hh = pd.read_csv(urbansim+'households.csv.gz')
per = pd.read_csv(urbansim+'persons.csv.gz')

pwd = '/Users/zaneedell/Desktop/git/beam/src/main/python/events_analysis'

outfolder = "/polaris-out/austin/"

hh['incomeBin'] = np.digitize(hh['income'],incomebins)

income_counts = hh.incomeBin.value_counts()
income_counts = income_counts/income_counts.sum()
#%%
Household = pd.Series()
Household['Households'] = hh.shape[0]
Household['<$20k'] = income_counts[1]
Household['$20k - $35k'] = income_counts[2]
Household['$35k - $50k'] = income_counts[3]
Household['$50k - $75k'] = income_counts[4]
Household['$75k - $100k'] = income_counts[5]
Household['$100k - $150k'] = income_counts[6]
Household['>$150k'] = income_counts[7]

#%%
tenure_counts = hh.tenure.value_counts()
tenure_counts = tenure_counts/tenure_counts.sum()

Household['Own'] = tenure_counts[1]
Household['Rent'] = tenure_counts[2]

#%%
cars_counts = hh.cars.value_counts()
cars_counts = cars_counts / cars_counts.sum()
Household['0-Vehicle Household'] = cars_counts[0]
Household['1-Vehicle Household'] = cars_counts[1]
Household['2-Vehicle Household'] = cars_counts[2]
Household['3+ Vehicle Household'] = cars_counts[3] + cars_counts[4]
#%%
size_counts = hh.persons.value_counts()
size_counts = size_counts / size_counts.sum()
Household['1-Person Household'] = size_counts[1]
Household['2-Person Household'] = size_counts[2]
Household['3-Person Household'] = size_counts[3]
Household['4-Person Household'] = size_counts[4]
Household['5-Person Household'] = size_counts[5]
Household['6-Person Household'] = size_counts[6]
Household['7+ Person Household'] = sum([size_counts[key] for key in size_counts.keys() if key > 7])
Household.to_csv(pwd + outfolder + "Household.csv")
#%%
Person = pd.Series()
Person['Persons'] = per.shape[0]
agebins = [-1,15,25,35,45,55,65,200]
per['agebin'] = np.digitize(per['age'],agebins)
age_counts = per.agebin.value_counts()
age_counts = age_counts / age_counts.sum()

Person['<15 years'] = age_counts[1]
Person['15-24 years'] = age_counts[2]
Person['25-34 years'] = age_counts[3]
Person['35-44 years'] = age_counts[4]
Person['45-54 years'] = age_counts[5]
Person['55-64 years'] = age_counts[6]
Person['65+ years'] = age_counts[7]

#%%
race_counts = per.race_id.value_counts()
race_counts = race_counts / race_counts.sum()

Person['White'] = race_counts[1]
Person['Black'] = race_counts[2]
Person['NativeAmerican'] = race_counts[3] + race_counts[4] + race_counts[5]
Person['Asian'] = race_counts[6]
Person['Other'] = race_counts[7] + race_counts[8] + race_counts[9]

#%%
edu = dict()
for i in range(16):
    edu[i] = "<HS"
edu[16] = "HS"
edu[17] = "HS"
edu[18] = "<College"
edu[19] = "<College"
edu[20] = "<College"
edu[21] = "College"
edu[22] = "College"
edu[23] = "College"
edu[24] = "College"

per['edu2'] = per['edu'].apply(lambda x: edu[x])

edu_counts = per['edu2'].value_counts()
edu_counts = edu_counts / edu_counts.sum()


#%%
emp = {0:'NILF',1:'Employed'}
inc_emp = per[['edu2','worker','person_id']].groupby(['edu2','worker']).agg('nunique')
for row in inc_emp.iterrows():
    Person[row[0][0] + ' ' + emp[row[0][1]]] = row[1].person_id / inc_emp['person_id'].sum()
    
Person.to_csv(pwd + outfolder + "Person.csv")

#%%
# activities = pd.read_csv(urbansim+'plans.csv.gz')
# activities = activities.loc[activities.ActivityElement == 'activity']
# activities['arrival_time']=0
# #%%
# activities.iloc[1:,11]= activities.iloc[:-1,10].values
# activities['departure_time'].fillna(24,inplace=True)
# activities['arrival_time'].fillna(0,inplace=True)
# activities['duration_min'] = (activities['departure_time'] - activities['arrival_time']) * 60
# #%%
acts = {'home':'home', 'Home':'home', 'work':'Primary Work', 'othmaint':'Other', 'social':'Social', 'univ':'School', 'othdiscr':'Other', 'escort':'Pickup-Dropoff','eatout':'Eat Out', 'atwork':'Primary Work', 'Work':'Primary Work', 'shopping':'Shopping', 'school':'School'}

# activities['act'] = activities['ActivityType'].apply(lambda x: acts[x])
# act_dur = activities[['act','duration_min']].groupby('act').agg('mean')

#%%
plans = pd.read_csv(s3 + 'ITERS/it.10/10.plans.csv.gz')
#%%
plans = plans.loc[plans.planSelected,:]
legindex = np.where(plans.planElementType == "leg")[0]
#%%
legs = plans.loc[plans.planElementType == "leg",['personId','planElementIndex','legMode','legDepartureTime','legTravelTime','legRouteType','legRouteTravelTime','legRouteDistance']]
legs['startLocation'] = gpd.points_from_xy(plans.iloc[legindex-1,7],plans.iloc[legindex-1,8])
legs['endLocation'] = gpd.points_from_xy(plans.iloc[legindex+1,7],plans.iloc[legindex+1,8])
legs['prevActivityType'] = plans.iloc[legindex-1,6].values
legs['prevActivityType'] = legs['prevActivityType'].apply(lambda x: acts[x])


legs['nextActivityType'] = plans.iloc[legindex+1,6].values
legs['nextActivityType'] = legs['nextActivityType'].apply(lambda x: acts[x])

DistanceMean = legs.groupby('nextActivityType').agg('mean')['legRouteDistance']/1609.34

#%%

workCounts, bins = np.histogram(legs.loc[legs.tourType == 'HBW','legRouteTravelTime']/60, np.arange(0,62,2), density=True)
nonWorkCounts, bins = np.histogram(legs.loc[legs.tourType != 'HBW','legRouteTravelTime']/60, np.arange(0,62,2), density=True)
pd.DataFrame({"Time":bins[:-1],"Work":workCounts*2,"Discretionary":nonWorkCounts*2}).to_csv(pwd + outfolder + "TripDurationHist.csv",index=False)



#%%
activities = plans.loc[plans.planElementType == "activity",['personId','planElementIndex','activityType','activityLocationX','activityLocationY','activityEndTime']]
activities['activityStartTime'] = 0.0
activities.activityEndTime.replace(-np.inf, 24*3600, inplace=True)
activities.activityEndTime.replace(np.inf, 24*3600, inplace=True)
activities.activityStartTime.replace(-np.inf, 24*3600, inplace=True)
activities.loc[activities.planElementIndex > 0, 'activityStartTime'] = legs['legDepartureTime'].fillna(0).values
activities['duration'] = activities.activityEndTime - activities.activityStartTime
activities.loc[activities['duration'] < 0, 'duration'] = 0
activities['activityType'] = activities['activityType'].apply(lambda x: acts[x])
activities.duration.replace(np.inf, np.nan, inplace=True)

for act in activities['activityType'].unique():
    sub = activities.loc[activities.activityType == act,'activityStartTime']
    n = sub.size
    plt.hist(sub/3600, bins = np.arange(24), density=True)
    plt.title(act + " start time")
    
    plt.ylabel('Portion of Activities')
    plt.xlabel('Hour of Day')
    plt.savefig(pwd + outfolder + act + '_startTime.png')
    plt.clf()

ActivityDuration = activities.groupby('activityType').agg('mean')['duration']

actBinCounts, bins = np.histogram(activities.activityEndTime/3600,np.arange(0,24.5,0.5))
actBinCounts = np.round(actBinCounts / 0.136)
pd.DataFrame({"Time":bins[:-1],"Activities":actBinCounts}).to_csv(pwd + outfolder + "ActivityEndTimeHist.csv",index=False)


#%%
actCounts = activities.value_counts('activityType').iloc[1:]
actCounts = actCounts/(actCounts.sum())
actCounts.to_csv(pwd + outfolder + "ActivityGeneration.csv")
#%%
legs['tourType'] = "NHB"
legs.loc[legs.prevActivityType == 'home', 'tourType'] = 'HBO'
legs.loc[(legs.nextActivityType == 'home'), 'tourType'] = 'HBO'
legs.loc[(legs.nextActivityType == 'home') & (legs.prevActivityType == 'Primary Work'), 'tourType'] = 'HBW'
legs.loc[(legs.prevActivityType == 'home') & (legs.nextActivityType == 'Primary Work'), 'tourType'] = 'HBW'


#%%
ModeShare = dict()

for tourType in ["HBO", "NHB", "HBW"]:
    sub = legs.loc[legs.tourType == tourType,['personId','legMode']].groupby('legMode').agg('size')
    ModeShare[tourType] = sub / sub.sum()

sub = legs.loc[:,['personId','legMode']].groupby('legMode').agg('size') / legs.shape[0]
 
ModeShare["Total"] = sub / sub.sum()

#%%
for act in activities['activityType'].unique():
    sub = legs.loc[legs.nextActivityType == act,'legRouteDistance']
    n = sub.size
    plt.hist(sub/1609.34, bins = np.arange(0,40,2), density=True)
    plt.title(act + " distance")
    
    plt.ylabel('Portion of Activities')
    plt.xlabel('Distance (mi)')
    plt.savefig(pwd + outfolder + act + '_distance.png')
    plt.clf()

#%%
counties = gpd.read_file('data/cb_2018_us_county_5m/cb_2018_us_county_5m.shp')
counties.to_crs("epsg:26910", inplace=True)

joined = gpd.sjoin(gpd.GeoDataFrame(legs, geometry = 'startLocation', crs="epsg:26910"), counties.loc[counties.STATEFP == "48",['geometry','NAME','STATEFP']], op = "within").rename(columns={"NAME":"OriginCounty"})

joined = gpd.sjoin(joined.set_geometry('endLocation').drop(['index_right'], axis=1), counties.loc[counties.STATEFP == "48",['geometry','NAME','STATEFP']], op = "within").rename(columns={"NAME":"DestinationCounty"})

CountyToCountyFlow = joined.value_counts(['OriginCounty','DestinationCounty'])
CountyToCountyFlow =  CountyToCountyFlow.loc[CountyToCountyFlow > 10].unstack().fillna(0) / 0.136
CountyToCountyFlow = CountyToCountyFlow.round()

#%%
sub = joined.loc[joined.DestinationCounty == "Travis",['personId','legMode']].groupby('legMode').agg('size')
ModeShare["CBD"] = sub / sub.sum()

sub = joined.loc[joined.DestinationCounty != "Travis",['personId','legMode']].groupby('legMode').agg('size')
ModeShare["Non-CBD"] = sub / sub.sum()


#%%
DistanceMean.to_csv(pwd + outfolder + "DistanceMean.csv")
CountyToCountyFlow.to_csv(pwd + outfolder + "CountyToCountyFlow.csv")
ActivityDuration.to_csv(pwd + outfolder + "ActivityDuration.csv")
pd.DataFrame(ModeShare).to_csv(pwd + outfolder + "ModeShare.csv")

#%%

events = pd.read_csv(s3+ 'ITERS/it.10/10.events.csv.gz')


pathTraversal = events.loc[(events['type'] == 'PathTraversal')].dropna(how='all', axis=1) #(events['mode'] == 'car')
pathTraversal['mode_extended'] = pathTraversal['mode']
pathTraversal['isRH'] = ((pathTraversal['driver'].str.contains('rideHail')== True))
pathTraversal['isCAV'] = ((pathTraversal['vehicleType'].str.contains('CAV')==True))
pathTraversal.loc[pathTraversal['isRH'], 'mode_extended'] += '_RH'
pathTraversal.loc[pathTraversal['isCAV'], 'mode_extended'] += '_CAV'


pathTraversal['gallons'] = (pathTraversal['primaryFuel'] + pathTraversal['secondaryFuel']) * 8.3141841e-9
pathTraversal['MWH'] = (pathTraversal['primaryFuel'] + pathTraversal['secondaryFuel']) * 2.77778e-10


pathTraversal['trueOccupancy'] = pathTraversal['numPassengers']
pathTraversal.loc[pathTraversal['mode_extended'] == 'car', 'trueOccupancy'] += 1
pathTraversal.loc[pathTraversal['mode_extended'] == 'walk', 'trueOccupancy'] += 1
pathTraversal.loc[pathTraversal['mode_extended'] == 'bike', 'trueOccupancy'] += 1
pathTraversal['vehicleMiles'] = pathTraversal['length']/1609.34
pathTraversal['passengerMiles'] = (pathTraversal['length'] * pathTraversal['trueOccupancy'])/1609.34
pathTraversal['vehicleHours'] = (pathTraversal['arrivalTime'] - pathTraversal['departureTime'])/3600.
pathTraversal['passengerHours'] = pathTraversal['vehicleHours'] * pathTraversal['trueOccupancy']

gb_mode_fuel = pathTraversal[['mode_extended','vehicleMiles','vehicleHours','gallons','primaryFuelType','MWH']].groupby(['mode_extended','primaryFuelType']).agg('sum')
gb_mode = pathTraversal[['mode_extended','vehicleMiles','vehicleHours','gallons','primaryFuelType','passengerMiles','MWH']].groupby(['mode_extended']).agg('sum')

s = pathTraversal[['passengerMiles','passengerHours']].sum()
gb_mode_simple = pathTraversal[['mode','vehicleMiles','vehicleHours','gallons','primaryFuelType','passengerMiles','MWH']].groupby(['mode']).agg('sum')

#%%
Energy = pd.Series()
Energy['Fuel_Auto'] = gb_mode_fuel.loc['car','gallons']['Gasoline'] / 0.136 / 1000000
Energy['Fuel_TNC'] = gb_mode_fuel.loc['car_RH','gallons']['Gasoline'] / 0.136 / 1000000
Energy['Fuel_Transit'] = gb_mode.loc['bus','vehicleMiles'] / 1000000 / 3.71
Energy['Electricity_Auto'] = gb_mode_fuel.loc['car','MWH']['Electricity'] / 0.136
Energy['Electricity_TNC'] = gb_mode_fuel.loc['car_RH','MWH']['Electricity'] / 0.136 
Energy['Total_Auto'] = gb_mode_fuel.loc['car','MWH']['Gasoline'] / 0.136 / 1000 + gb_mode_fuel.loc['car','MWH']['Electricity'] / 0.136 / 1000
Energy['Total_TNC'] = gb_mode_fuel.loc['car_RH','MWH']['Gasoline'] / 0.136 / 1000 + gb_mode_fuel.loc['car_RH','MWH']['Electricity'] / 0.136 / 1000
Energy['Total_Transit'] = gb_mode.loc['bus','MWH'] / 1000
Energy['EperMile_Auto'] = gb_mode['MWH']['car'] / gb_mode['vehicleMiles']['car'] * 1000
Energy['EperMile_TNC'] = gb_mode['MWH']['car_RH'] / gb_mode['vehicleMiles']['car_RH'] * 1000
Energy['EperMile_Transit'] = (gb_mode.loc['bus','MWH'] + gb_mode.loc['tram','vehicleMiles'] / 1.125 * 0.0407) / (gb_mode.loc['bus','vehicleMiles'] + gb_mode.loc['tram','vehicleMiles']) * 1000
Energy['MPGe_Auto'] = gb_mode['vehicleMiles']['car'] / gb_mode['gallons']['car']
Energy['MPGe_TNC'] = gb_mode['vehicleMiles']['car_RH'] / gb_mode['gallons']['car_RH']
Energy['MPGe_Transit'] = 33.410133412853945 / Energy['EperMile_Transit']
Energy.to_csv(pwd + outfolder + "Energy.csv")


#%%
Summary = pd.Series()
Summary['TotalTrips_Auto'] = np.sum(legs.legMode.isin(['car','drive_transit','ride_hail','ride_hail_pooled','ride_hail_transit'])) / 0.136
Summary['PMT'] = s['passengerMiles'] / 0.136 / 1e6
Summary['PHT'] = s['passengerHours'] / 0.136 / 1e6
Summary['VMT'] = gb_mode_simple.loc['car','vehicleMiles'] / 0.136 / 1e6
Summary['VHT'] = gb_mode_simple.loc['car','vehicleHours'] / 0.136 / 1e6
Summary['AverageVehicleSpeed'] = gb_mode_simple.loc['car','vehicleMiles'] / gb_mode_simple.loc['car','vehicleHours']
Summary['AveragePersonSpeed'] = s['passengerMiles'] / s['passengerHours']
Summary['TotalEnergy'] = Energy['Total_Auto'] + Energy['Total_TNC'] + Energy['Total_Transit']
Summary['TravelEfficiency'] = s['passengerMiles'] / Summary['TotalEnergy'] / 1e6
Summary['PerCapitaPMT'] = s['passengerMiles'] / 0.136 / per.shape[0]
Summary['PerCapitaPHT'] = s['passengerHours'] / 0.136 / per.shape[0]
Summary['PerCapitaVMT'] = (gb_mode_simple.loc['car','vehicleMiles'] / 0.136 + gb_mode_simple.loc['bus','vehicleMiles'] + gb_mode_simple.loc['tram','vehicleMiles']) / per.shape[0]
Summary['PerCapitaVHT'] = (gb_mode_simple.loc['car','vehicleHours'] / 0.136 + gb_mode_simple.loc['bus','vehicleHours'] + gb_mode_simple.loc['tram','vehicleMiles']) / per.shape[0]
Summary.to_csv(pwd + outfolder + "Summary.csv")

#%%
skims = pd.read_csv(s3 + 'ITERS/it.10/10.skimsODExcerpt.csv.gz')
#%%
skims = skims.loc[skims.energy > 0]
skims['weightedTime'] = skims['travelTimeInS'] * skims['observations']
skims['weightedEnergy'] = skims['energy'] * skims['observations']
skims['weightedCost'] = skims['generalizedCost'] * skims['observations']
skims['weightedDistance'] = skims['distanceInM'] * skims['observations']

sums = skims[['weightedTime', 'weightedEnergy','weightedCost','weightedDistance','mode']].groupby('mode').sum()


#%%

network = pd.read_csv(s3 + 'network.csv.gz')