import pandas as pd

import numpy as np
import scipy.ndimage

# %%
trips_all = pd.read_csv('https://beam-outputs.s3.amazonaws.com/new_city/nhts/trippub.csv.gz',
                    usecols=[0, 1, 2, 3, 4, 5, 6, 7, 17, 26, 28, 58, 59, 60, 61, 64, 69, 70, 71, 72, 73, 74, 84, 89, 93,
                             102, 103])

persons_all = pd.read_csv('https://beam-outputs.s3.amazonaws.com/new_city/nhts/perpub.csv.gz')

#%%


actnames = {1:'Home',2:'Home',3:'Work',8:'Work',11:'Shopping',12:'Shopping',13:'Meal',15:'SocRec',16:'SocRec',17:'SocRec',19:'SocRec'}

modenames = {1:'Walk',2:'Bike',3:'Car',4:'Car',5:'Car',6:'Car',7:'Car',8:'Car',9:'Car',10:'Bus',11:'Bus',13:'Bus',14:'Bus',15:'Rail',16:'Subway',17:'Ridehail',18:'Car', 20:"Ferry"}

binnames = {1: "0-1", 2: "1-2", 3: "2-5", 4: "5-15", 5: "15+"}

def getModeShare(trips):
    modes = [modenames.get(val, 'Other') for val in trips.TRPTRANS]
    trips['modename'] = modes
    all_mode_share = trips.groupby('modename').agg({'WTTRDFIN':'sum'})/trips['WTTRDFIN'].sum()
    work_mode_share = trips.loc[trips.toWork | trips.fromWork].groupby('modename').agg({'WTTRDFIN':'sum'})/trips.loc[trips.toWork | trips.fromWork]['WTTRDFIN'].sum()
    return pd.concat([all_mode_share.rename(columns={"WTTRDFIN":"All Trips"}),work_mode_share.rename(columns={"WTTRDFIN":"Work Trips"})], axis=1)

def getDistanceModeShare(trips):
    modes = [modenames.get(val, 'Other') for val in trips.TRPTRANS]
    trips['modename'] = modes
    distanceBinsMiles = np.array([0, 1, 2, 5, 15, 500])
    trips['distanceBin'] = [binnames.get(val, 'Other') for val in np.digitize(trips['TRPMILES'], distanceBinsMiles)]
    all_mode_trips = trips.groupby(['modename', 'distanceBin']).agg({'WTTRDFIN': 'sum'}).unstack().fillna(0.0)
    tot = all_mode_trips.sum(axis=0)
    all_mode_trips = all_mode_trips / tot
    all_mode_trips.loc['total', :] = tot / tot.sum()
    return all_mode_trips

def getActivities(trips):
    locations = np.append(trips.WHYFROM.values,trips.WHYTO.values[-1])
    locations = [actnames.get(val,'Other') for val in locations]
    startTimes = np.append([0], trips.endHour.values)
    endTimes = np.append(trips.startHour.values, [24])
    durations = endTimes - startTimes
    weights = np.append(trips.WTTRDFIN.values, [trips.WTTRDFIN.values[0]])
    return pd.DataFrame({'location':locations,'startTime':startTimes,'endTime':endTimes,'duration':durations,'weight':weights})


def getEndTimes(activities):
    activities.reset_index(inplace=True)
    locations = activities.location.unique()
    intercepts = dict()
    nPeople = trips.drop_duplicates('UniquePID').WTTRDFIN.sum()
    for location in locations:
        counts, bins = np.histogram(activities.loc[activities.location == location,'endTime'],range(26), weights = activities.loc[activities.location == location,'weight'])
        counts = counts / nPeople
        intercepts[location] = counts
    df = pd.DataFrame(intercepts, columns=locations)
    df.index.name = 'Hour'
    return df
    
def getIntercepts(activities):
    activities.reset_index(inplace=True)
    locations = ['Other','Shopping','Meal','SocRec']
    intercepts = dict()
    nPeople = trips.drop_duplicates('UniquePID').WTTRDFIN.sum()
    for location in locations:
        counts, bins = np.histogram(activities.loc[activities.location == location,'startTime'],range(26), weights = activities.loc[activities.location == location,'weight'])
        counts = counts / nPeople *24
        counts[counts < 0.015] = 0.0
        intercepts[location] = counts
    df = pd.DataFrame(intercepts, columns=locations)
    df.index.name = 'Hour'
    return df


def getParams(activities):
    #activities.reset_index(inplace=True)
    locations = ['Other','Shopping','Meal','SocRec']
    params = dict()
    for location in locations:
        VOT = 1.0
        DurationInHours = np.average(activities.loc[activities.location == location,'duration'], weights = activities.loc[activities.location == location,'weight'])
        params[location] = [VOT, DurationInHours * 3600]
    df = pd.DataFrame(params, columns=locations, index=['VOT','DurationInSeconds'])
    return df


def getCalibration(trips):
    trips['whyTo'] = [actnames.get(val,'Other') for val in trips.WHYTO]
    nPeople = trips.drop_duplicates('UniquePID').WTTRDFIN.sum()
    means = trips.groupby('whyTo').apply(lambda x: np.average(x.TRPMILES, weights=x.WTTRDFIN))
    nTrips = trips.groupby('whyTo').apply(lambda x: np.sum(x.WTTRDFIN) / nPeople)
    df = pd.concat([means,nTrips], axis=1).rename(columns={0:'Mean Distance (mi)',1:'Trips per Capita'})
    df.index.name = 'Trip Purpose'
    return df
#%%
for cbsa in ['12420','41860']:
    trips = trips_all.loc[(trips_all['HH_CBSA'] == cbsa) , :]


    valid = (trips.TRPMILES > 0) & (trips.TDWKND == 2) & (trips.TRPTRANS != 19) & (trips.ENDTIME > trips.STRTTIME)
    
    
    
    trips = trips.loc[valid, :]
    trips['UniquePID'] = trips.HOUSEID * 100 + trips.PERSONID
    trips['startHour'] = np.floor(trips.STRTTIME / 100) + np.mod(trips.STRTTIME, 100) / 60
    trips['endHour'] = np.floor(trips.ENDTIME / 100) + np.mod(trips.ENDTIME, 100) / 60
    trips['toWork'] = (trips.WHYTO == 3) | (trips.WHYTO == 4)
    trips['fromWork'] = (trips.WHYFROM == 3) | (trips.WHYFROM == 4)

    distModeShares = getDistanceModeShare(trips)
    distModeShares.to_csv('outputs/distance-mode-shares-' + cbsa + '.csv')
    
    modeshares = getModeShare(trips)
    modeshares.to_csv('outputs/mode-shares-'+cbsa+'.csv')
    
    out = trips.groupby('UniquePID').apply(getActivities)
    out = out[out['startTime'] >= 0]

    intercepts = getIntercepts(out)
    intercepts.to_csv('outputs/activity-intercepts-'+cbsa+'.csv')
    endtimes = getEndTimes(out)
    endtimes.to_csv('outputs/activity-end-times-'+cbsa+'.csv')
    params = getParams(out)
    params.to_csv('outputs/activity-params-'+cbsa+'.csv')
    calib = getCalibration(trips)
    calib.to_csv('outputs/activity-calibration-values-'+cbsa+'.csv')
    # mat2 = pd.DataFrame(scipy.ndimage.filters.gaussian_filter(mat.values,[1.0,1.0]), index = mat.index, columns = mat.columns)
    # binProb = mat2.stack().rename(columns={'WTPERFIN':'probability'})
    # binProb.to_csv('outputs/work_activities_'+cbsa+'.csv')


#%%
trips = trips_all.copy()

valid = (trips.TRPMILES > 0) & (trips.TDWKND == 2) & (trips.TRPTRANS != 19) & (trips.ENDTIME > trips.STRTTIME)



trips = trips.loc[valid, :]
trips['UniquePID'] = trips.HOUSEID * 100 + trips.PERSONID
trips['startHour'] = np.floor(trips.STRTTIME / 100) + np.mod(trips.STRTTIME, 100) / 60
trips['endHour'] = np.floor(trips.ENDTIME / 100) + np.mod(trips.ENDTIME, 100) / 60
trips['toWork'] = (trips.WHYTO == 3) | (trips.WHYTO == 4)
trips['fromWork'] = (trips.WHYFROM == 3) | (trips.WHYFROM == 4)

out = trips.groupby('UniquePID').apply(getActivities)
out = out[out['startTime'] >= 0]

intercepts = getIntercepts(out)
intercepts.to_csv('outputs/activity-intercepts-all-us.csv')
endtimes = getEndTimes(out)
endtimes.to_csv('outputs/activity-end-times-all-us.csv')

params = getParams(out)
params.to_csv('outputs/activity-params-all-us.csv')

calib = getCalibration(trips)
calib.to_csv('outputs/activity-calibration-values-all-us.csv')
