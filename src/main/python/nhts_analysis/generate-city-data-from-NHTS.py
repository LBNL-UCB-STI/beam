import pandas as pd

import numpy as np
import scipy.ndimage
import ast
import sys

# %%


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


def getEndTimes(activities,trips):
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
    
def getIntercepts(activities,trips):
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

def getCBSAvalues(cbsa, trips_all, persons_all):
    if '-' in cbsa:
        trips = trips_all.loc[(trips_all['HH_CBSA'].str.isnumeric()) , :]
        persons = persons_all.loc[(persons_all['HH_CBSA'].str.isnumeric()), :]
    else:
        trips = trips_all.loc[(trips_all['HH_CBSA'] == cbsa) , :]
        persons = persons_all.loc[(persons_all['HH_CBSA'] == cbsa), :]
    valid = (trips.TRPMILES > 0) & (trips.TDWKND == 2) & (trips.TRPTRANS != 19) & (trips.ENDTIME > trips.STRTTIME)
    
    trips = trips.loc[valid, :]
    trips['UniquePID'] = trips.HOUSEID * 100 + trips.PERSONID
    trips['startHour'] = np.floor(trips.STRTTIME / 100) + np.mod(trips.STRTTIME, 100) / 60
    trips['endHour'] = np.floor(trips.ENDTIME / 100) + np.mod(trips.ENDTIME, 100) / 60
    trips['toWork'] = (trips.WHYTO == 3) | (trips.WHYTO == 4)
    trips['fromWork'] = (trips.WHYFROM == 3) | (trips.WHYFROM == 4)
    
    activities = trips.groupby('UniquePID').apply(getActivities)
    activities = activities[activities['startTime'] >= 0]

    out = dict()
    out['intercepts'] = getIntercepts(activities,trips)
    #intercepts.to_csv('outputs/activity-intercepts-'+cbsa+'.csv')
    out['endtimes'] = getEndTimes(activities,trips)
    #endtimes.to_csv('outputs/activity-end-times-'+cbsa+'.csv')
    out['params'] = getParams(activities)
    #params.to_csv('outputs/activity-params-'+cbsa+'.csv')
    out['calib'] = getCalibration(trips)
    out['respondents'] = activities.UniquePID.nunique()
    #calib.to_csv('outputs/activity-calibration-values-'+cbsa+'.csv')
    
    workInvolved = (trips.TRIPPURP == 'HBW')
    workTrips = trips.loc[workInvolved, :]

    
    
    valid = (persons.TRAVDAY > 1) & (persons.TRAVDAY < 7)
    
    persons = persons.loc[valid,:]
    persons['UniquePID'] = persons.HOUSEID * 100 + persons.PERSONID
    
    workPIDs = set(workTrips.UniquePID)
    workerTrips = trips.loc[trips['UniquePID'].isin(workPIDs),:]

    starts = workerTrips.loc[workerTrips.toWork].groupby('UniquePID')['startHour'].agg('first')
    ends = workerTrips.loc[workerTrips.fromWork].groupby('UniquePID')['startHour'].agg('last')
    
    workers = persons.loc[persons['UniquePID'].isin(workPIDs),:].set_index('UniquePID')
    toMerge = pd.concat([starts,ends],axis=1,join='inner')
    toMerge.columns = ['startWork','endWork']
    
    workers = workers.merge(toMerge,left_index=True, right_index=True)
    workers['workDuration'] = workers['endWork'] - workers['startWork']
    
    workers['startTimeIndex'] = pd.cut(workers.startWork,np.arange(4,21,0.25))
    workers['durationIndex'] = pd.cut(workers.workDuration,np.arange(0,15,0.25))
    
    binProb = workers.groupby(['startTimeIndex','durationIndex']).agg({'WTPERFIN':'sum'}).fillna(0)
    binProb = binProb / binProb.sum()
    
    mat = binProb.unstack()
    mat2 = pd.DataFrame(scipy.ndimage.filters.gaussian_filter(mat.values,[1.0,1.0]), index = mat.index, columns = mat.columns)
    binProb = mat2.stack().rename(columns={'WTPERFIN':'probability'})
    out['workDuration'] = binProb
    
    
    
    
    return out

if __name__ == '__main__':
    print("NHTS Input: " + sys.argv[1])
    path = sys.argv[2]
    rawarg = sys.argv[1]
    try:
        arg = ast.literal_eval(sys.argv[1])
    except:
        arg = sys.argv[1]

    if isinstance(arg,dict):
        cbsas = list(arg.keys())
        weights = list(arg.values())
    elif isinstance(arg,list):
        cbsas = arg
        weights = []
    elif isinstance(arg,str):
        cbsas = [arg]
        weights = [1.0]
    elif isinstance(arg,int):
        cbsas = [str(arg)]
        weights = [1.0]
        
    
    trips_all = pd.read_csv('https://beam-outputs.s3.amazonaws.com/new_city/nhts/trippub.csv.gz',
                    usecols=[0, 1, 2, 3, 4, 5, 6, 7, 17, 26, 28, 58, 59, 60, 61, 64, 69, 70, 71, 72, 73, 74, 84, 89, 93,
                             102, 103])

    persons_all = pd.read_csv('https://beam-outputs.s3.amazonaws.com/new_city/nhts/perpub.csv.gz')

    collected = dict()
    for cbsa in cbsas:
        print(cbsa)
        collected[cbsa] = getCBSAvalues(str(cbsa), trips_all, persons_all)
        if isinstance(arg,list):
            weights.append(collected[cbsa]['respondents'])
    
    intercepts = collected[cbsas[0]]['intercepts'] * weights[0] / np.sum(weights)
    endtimes = collected[cbsas[0]]['endtimes'] * weights[0] / np.sum(weights)
    params = collected[cbsas[0]]['params'] * weights[0] / np.sum(weights)
    calib = collected[cbsas[0]]['calib'] * weights[0] / np.sum(weights)
    workDuration = collected[cbsas[0]]['workDuration'] * weights[0] / np.sum(weights)
    if len(cbsas) > 1:
        for i in range(1, len(cbsas)):
            print(i)
            intercepts += intercepts * weights[i] / np.sum(weights)
            endtimes += collected[cbsas[i]]['endtimes']  * weights[i] / np.sum(weights)
            params += collected[cbsas[i]]['params'] * weights[i] / np.sum(weights)
            calib += collected[cbsas[i]]['calib'] * weights[i] / np.sum(weights)
            workDuration += collected[cbsas[i]]['workDuration'] * weights[i] / np.sum(weights)
    
    intercepts.to_csv(path+'activity-intercepts.csv',index=True)
    endtimes.to_csv(path+'activity-endtimes.csv',index=True)
    params.to_csv(path+'activity-params.csv',index=True)
    calib.to_csv(path+'activity-calib.csv',index=True)
    workDuration.to_csv(path+'activity-workDuration.csv',index=True)