# -*- coding: utf-8 -*-
"""
Created on Sun Apr  2 12:47:35 2017

@author: mygreencar
"""

from collections import OrderedDict    # For recording the model specification 

from datetime import datetime
from dateutil.parser import parse

import pandas as pd                    # For file input/output
import numpy as np                     # For vectorized math operations

import pylogit as pl                   # For MNL model estimation and
                                       # conversion from wide to long format
import matplotlib.pyplot as plt

#================================== EXPORS ==================================
# load data for three scenarios: no-ev, inflexible-ev, and smartcharge-ev
month = '08'
day = '10'

print("loading data...")
noEvFile = '/Users/mygreencar/Downloads/drive-download-20170402T185936Z-001/2024 40PCTRPS ProdCost_BaseCase0_No_EVs_WECC_Regional_Outputs_and_Renew/M' + str(month) + ' 40PCTRPS ProdCost_BaseCase0_No_EVs_WECC_Regional_Outputs.csv'
inflexEvFile = '/Users/mygreencar/Downloads/drive-download-20170402T185936Z-001/2024 40PCTRPS ProdCost_BaseC1_Inflexible_EVs_Mid13_Outputs/M' + str(month) + ' 40PCTRPS ProdCost_BaseC1_Inflexible_EVs_Mid13_WECC_Regional_Outputs.csv'
smartEvFile = '/Users/mygreencar/Downloads/drive-download-20170402T185936Z-001/2024 40PCTRPS ProdCost_BaseC1_SmartCharge_EVs_Mid13_WECC_Ren_EVs_Storage/M' + str(month) + ' 40PCTRPS ProdCost_BaseC1_SmartCharge_EVs_Mid13_WECC_Regional_Output.csv'

dataNo = pd.read_csv(noEvFile, parse_dates=['Datetime'])
dataInflex = pd.read_csv(inflexEvFile, parse_dates=['Datetime'])
dataSmart = pd.read_csv(smartEvFile, parse_dates=['Datetime'])

print("filtering data...")

# Filter data
propertyFilter = 'Generation Capacity Curtailed'
dayDataNo = dataNo[(dataNo.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataNo.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataNo.Category == 'CA') & (dataNo.Property == propertyFilter)]
dayDataInflex = dataInflex[(dataInflex.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataInflex.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataInflex.Category == 'CA') & (dataInflex.Property == propertyFilter)]
dayDataSmart = dataSmart[(dataSmart.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataSmart.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataSmart.Category == 'CA') & (dataSmart.Property == propertyFilter)]

export = pd.DataFrame(
   {
       'date':dayDataInflex.Datetime,
       'time':dayDataInflex.Datetime.apply(lambda x:x.hour),
       'utilities':dayDataInflex['Child Name'],
       'noEV':dayDataNo.Value,
       'inflexEV':dayDataInflex.Value,
       'smartEV':dayDataSmart.Value
   })
export.to_csv('export_ca_utilities.csv',index=False)
print("file saved.")
    
#================================== EXPORTS - IMPORTS ==================================
propertyFilter1 = 'Exports'
propertyFilter2 = 'Imports'

dayDataNoFilter1 = dataNo[(dataNo.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataNo.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataNo.Category == 'CA') & (dataNo.Property == propertyFilter1)]
dayDataInflexFilter1 = dataInflex[(dataInflex.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataInflex.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataInflex.Category == 'CA') & (dataInflex.Property == propertyFilter1)]
dayDataSmartFilter1 = dataSmart[(dataSmart.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataSmart.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataSmart.Category == 'CA') & (dataSmart.Property == propertyFilter1)]

dayDataNoFilter2 = dataNo[(dataNo.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataNo.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataNo.Category == 'CA') & (dataNo.Property == propertyFilter2)]
dayDataInflexFilter2 = dataInflex[(dataInflex.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataInflex.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataInflex.Category == 'CA') & (dataInflex.Property == propertyFilter2)]
dayDataSmartFilter2 = dataSmart[(dataSmart.Datetime >= datetime.strptime(str(month)+'/'+str(day)+'/2024 0:00', '%m/%d/%Y %H:%M')) & (dataSmart.Datetime <= datetime.strptime(str(month)+'/'+str(day)+'/2024 23:00', '%m/%d/%Y %H:%M')) & (dataSmart.Category == 'CA') & (dataSmart.Property == propertyFilter2)]

netLoad = pd.DataFrame(
   {
       'date':dayDataInflexFilter1.Datetime,
       'time':dayDataInflexFilter1.Datetime.apply(lambda x:x.hour),
       'utilities':dayDataInflexFilter1['Child Name'],
       'noEV':[float(val1)-float(val2) for val1, val2 in zip(dayDataNoFilter1.Value, dayDataNoFilter2.Value)],
       'inflexEV':[float(val1)-float(val2) for val1, val2 in zip(dayDataInflexFilter1.Value, dayDataInflexFilter2.Value)],
       'smartEV':[float(val1)-float(val2) for val1, val2 in zip(dayDataSmartFilter1.Value, dayDataSmartFilter2.Value)]
   })

utilities = dayDataInflex['Child Name'].unique()
netLoad.to_csv('net_load_ca_utilities.csv',index=False)
print("file saved.")

#================================== NET LOAD ==================================
month = 8;
day = 3;

# Get load
loadFile = '/Users/mygreencar/Downloads/all-loads.csv'
loadData = pd.read_csv(loadFile)

# Get renewable & generation
noEvFile = '/Users/mygreencar/Downloads/drive-download-20170402T185936Z-001/2024 40PCTRPS ProdCost_BaseCase0_No_EVs_WECC_Regional_Outputs_and_Renew/M08 40PCTRPS ProdCost_BaseCase0_No_EVs_Renew_Gen_and_Curtailment.csv'
inflexEvFile = '/Users/mygreencar/Downloads/drive-download-20170402T185936Z-001/2024 40PCTRPS ProdCost_BaseC1_Inflexible_EVs_Mid13_Outputs/M08 40PCTRPS ProdCost_BaseC1_Inflexible_EVs_Mid13_Renew_Gen_and_Curtailment.csv'
smartEvFile = '/Users/mygreencar/Downloads/M08 40PCTRPS ProdCost_BaseC1_SmartCharge_EVs_Mid13_Renew_Gen_and_Curtailment.csv'

dataNo = pd.read_csv(noEvFile, parse_dates=['Datetime'])
dataInflex = pd.read_csv(inflexEvFile, parse_dates=['Datetime'])
dataSmart = pd.read_csv(smartEvFile, parse_dates=['Datetime'])

utilities = loadData.Utility.unique()
utilities[utilities=="PGE_VALLEY"] = "PGE_VLY"
times = [str(month)+'/'+str(day)+'/2024 '+str(hour)+':00' for hour in range(24)]
columns = ['utility','time','value']
addNo = pd.DataFrame(columns=columns) 
addInflex = pd.DataFrame(columns=columns)
addSmart = pd.DataFrame(columns=columns)
for utility in utilities:
    for hour,time in zip(range(24),times):
        addNo=addNo.append(pd.DataFrame([[utility,hour,sum(dataNo.Value[(dataNo.Datetime == datetime.strptime(time,'%m/%d/%Y %H:%M')) & (dataNo['Child Name'].str.contains(utility))])]],columns=columns),ignore_index=True)
        addInflex=addInflex.append(pd.DataFrame([[utility,hour,sum(dataInflex.Value[(dataInflex.Datetime == datetime.strptime(time,'%m/%d/%Y %H:%M')) & (dataInflex['Child Name'].str.contains(utility))])]],columns=columns),ignore_index=True)
        addSmart=addSmart.append(pd.DataFrame([[utility,hour,sum(dataSmart.Value[(dataSmart.Datetime == datetime.strptime(time,'%m/%d/%Y %H:%M')) & (dataSmart['Child Name'].str.contains(utility))])]],columns=columns),ignore_index=True)
    

# Net load dataframe
netLoad = pd.DataFrame(
   {
       'date':'',
       'time':addNo.time,
       'utilities':addNo.utility,
       'noEV':(np.array(loadData.Load[(loadData.Month==month)&(loadData.Day==day)&(loadData.pevs=='None')])-np.array(addNo.value))/1000, #GW
       'inflexEV':(np.array(loadData.Load[(loadData.Month==month)&(loadData.Day==day)&(loadData.pevs=='Unmanaged')])-np.array(addInflex.value))/1000, #GW
       'smartEV':(np.array(loadData.Load[(loadData.Month==month)&(loadData.Day==day)&(loadData.pevs=='Managed')])-np.array(addSmart.value))/1000 #GW
   })

# utilities = dayDataInflex['Child Name'].unique()
netLoad.to_csv('net_load_ca_utilities.csv',index=False)
print("file saved.")

#==============================================================================