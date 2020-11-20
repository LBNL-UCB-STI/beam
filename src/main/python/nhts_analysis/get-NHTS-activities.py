import pandas as pd

import numpy as np
import scipy.ndimage

# %%
trips_all = pd.read_csv('Data/NHTS/trippub.csv',
                    usecols=[0, 1, 2, 3, 4, 5, 6, 7, 17, 26, 28, 58, 59, 60, 61, 64, 69, 70, 71, 72, 73, 74, 84, 89, 93,
                             102, 103])

persons_all = pd.read_csv('Data/NHTS/perpub.csv')

#%%


actnames = {1:'Home',2:'Home',3:'Work',8:'Work',11:'Shopping',12:'Shopping',13:'Meal',15:'SocRec',16:'SocRec',17:'SocRec',19:'SocRec'}

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
        counts[counts < 0.025] = 0.0
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
for cbsa in persons_all.HH_CBSA.unique():#['12420']:
    trips = trips_all.loc[(trips_all['HH_CBSA'] == cbsa) , :]


    valid = (trips.TRPMILES > 0) & (trips.TDWKND == 2) & (trips.TRPTRANS != 19) & (trips.ENDTIME > trips.STRTTIME)
    
    
    
    trips = trips.loc[valid, :]
    trips['UniquePID'] = trips.HOUSEID * 100 + trips.PERSONID
    trips['startHour'] = np.floor(trips.STRTTIME / 100) + np.mod(trips.STRTTIME, 100) / 60
    trips['endHour'] = np.floor(trips.ENDTIME / 100) + np.mod(trips.ENDTIME, 100) / 60
    trips['toWork'] = (trips.WHYTO == 3) | (trips.WHYTO == 4)
    trips['fromWork'] = (trips.WHYFROM == 3) | (trips.WHYFROM == 4)
    
    out = trips.groupby('UniquePID').apply(getActivities)
    out = out[out['startTime'] > 0]

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
out = out[out['startTime'] > 0]

intercepts = getIntercepts(out)
intercepts.to_csv('outputs/activity-intercepts-all-us.csv')
endtimes = getEndTimes(out)
endtimes.to_csv('outputs/activity-end-times-all-us.csv')

params = getParams(out)
params.to_csv('outputs/activity-params-all-us.csv')

calib = getCalibration(trips)
calib.to_csv('outputs/activity-calibration-values-all-us.csv')