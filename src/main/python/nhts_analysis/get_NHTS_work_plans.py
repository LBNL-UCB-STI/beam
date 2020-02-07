import pandas as pd

import numpy as np
import scipy.ndimage

# %%
trips_all = pd.read_csv('Data/NHTS/trippub.csv',
                    usecols=[0, 1, 2, 3, 4, 5, 6, 7, 17, 26, 28, 58, 59, 60, 61, 64, 69, 70, 71, 72, 73, 74, 84, 89, 93,
                             102, 103])

persons_all = pd.read_csv('Data/NHTS/perpub.csv')


#%%
for cbsa in ['12420']:#persons_all.HH_CBSA.unique():
    trips = trips_all.loc[(trips_all['HH_CBSA'] == cbsa) , :]


    valid = (trips.TRPMILES > 0) & (trips.TDWKND == 2) & (trips.TRPTRANS != 19)
    
    
    
    trips = trips.loc[valid, :]
    trips['UniquePID'] = trips.HOUSEID * 100 + trips.PERSONID
    trips['startHour'] = np.floor(trips.STRTTIME / 100) + np.mod(trips.STRTTIME, 100) / 60
    trips['endHour'] = np.floor(trips.ENDTIME / 100) + np.mod(trips.ENDTIME, 100) / 60
    trips['toWork'] = (trips.WHYTO == 3) | (trips.WHYTO == 4)
    trips['fromWork'] = (trips.WHYFROM == 3) | (trips.WHYFROM == 4)
    
    
    workInvolved = (trips.TRIPPURP == 'HBW')
    workTrips = trips.loc[workInvolved, :]

    persons = persons_all.loc[(persons_all['HH_CBSA'] == cbsa), :]
    
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
    binProb.to_csv('outputs/work_activities_'+cbsa+'.csv')


#%%
trips = trips_all.copy()


valid = (trips.TRPMILES > 0) & (trips.TDWKND == 2) & (trips.TRPTRANS != 19)



trips = trips.loc[valid, :] 
trips['UniquePID'] = trips.HOUSEID * 100 + trips.PERSONID
trips['startHour'] = np.floor(trips.STRTTIME / 100) + np.mod(trips.STRTTIME, 100) / 60
trips['endHour'] = np.floor(trips.ENDTIME / 100) + np.mod(trips.ENDTIME, 100) / 60
trips['toWork'] = (trips.WHYTO == 3) | (trips.WHYTO == 4)
trips['fromWork'] = (trips.WHYFROM == 3) | (trips.WHYFROM == 4)


workInvolved = (trips.TRIPPURP == 'HBW')
workTrips = trips.loc[workInvolved, :]

persons = persons_all.copy()

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
binProb.to_csv('outputs/work_activities_all_us.csv')

