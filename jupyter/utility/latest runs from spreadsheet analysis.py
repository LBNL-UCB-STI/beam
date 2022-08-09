#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[2]:


## reading exported csv

# to get csv - save 'BEAM Deploy Status and Run Data' as csv
# if there is not enough permissions - save a copy and then save as csv

data = pd.read_csv("../../../beam-production/jupyter/local_files/BEAM Deploy Status and Run Data - BEAM Instances.csv", parse_dates=['Time'])

# using only runs from specific data 
min_time = pd.to_datetime("2022-07-01") # yyyy-mm-dd
data = data[data['Time'] > min_time].copy()

print(f"there are roughly {len(data) / 2} runs since {min_time}")
print(f"the latest run is from {data['Time'].max()}")

data['Month Period'] = data['Time'].dt.strftime('%Y-%m')
print(f"following data periods are included: {sorted(data['Month Period'].unique())}")

data.head(3)


# In[3]:


## getting data frame with each row as one simulation

take_first_columns = ['Run Name','Month Period','Branch','Instance type']

df = data.groupby("Host name").agg(list)
for col in take_first_columns:
    df[col] = df.apply(lambda r: r[col][0], axis=1)
    
df['Time Start'] = df.apply(lambda r: r['Time'][0], axis=1)
df['Time Stop'] = df.apply(lambda r: r['Time'][-1], axis=1)

df['Time Start'] = df['Time Start'].dt.floor('min')
df['Time Stop'] = df['Time Stop'].dt.floor('min')

all_columns = set(df.columns)
taken_columns = take_first_columns + ['Time Start','Time Stop']
df = df[taken_columns].copy()

print(f"removed columns: {list(sorted(all_columns - set(taken_columns)))}")

# fix for some wierd shift in the spreadsheet for few rows
for v in ['ec2-18-221-208-40.us-east-2.compute.amazonaws.com','ec2-3-144-69-95.us-east-2.compute.amazonaws.com','ec2-52-15-53-101.us-east-2.compute.amazonaws.com']:
    df.replace(to_replace=v, value='r5d.24xlarge', inplace=True)

print(f"there are following instance types used: {list(df['Instance type'].unique())}")
    
df['duration_minutes'] = (df['Time Stop'] - df['Time Start']).astype('timedelta64[m]')
df['duration_minutes'] = df.apply(lambda r: max(r['duration_minutes'], 1.0), axis=1)

df.head(3)


# In[4]:


## calculating a price in USD of each simulation

instance_to_price = {'r5d.24xlarge':6.912, 
                     'm5d.24xlarge':5.424, 
                     'r5.xlarge':0.252, 
                     'r5.24xlarge':6.048,
                     'r5.8xlarge':2.016, 
                     'm5.24xlarge':4.608, 
                     'r5.2xlarge':0.504, 
                     'm4.16xlarge':3.20,
                     'c5.9xlarge':1.53
                    }

missing_instance_types = set()
def get_price(row):
    instance_type = row['Instance type']
    if instance_type in instance_to_price :
        return instance_to_price[instance_type]

    missing_instance_types.add(instance_type)
    return 0.0

df['aws_cost'] = df.apply(get_price, axis=1)

if len(missing_instance_types) > 0:
    print(f"Can't find price for following instances: {missing_instance_types}")
    
df['cost'] = df['duration_minutes'] * df['aws_cost'] / 60.0
total_cost = int(df['cost'].sum())
print(f"The total cost of all instances from df: {total_cost}")
df.head(3)


# In[5]:


## grouping simulations by something

def get_owner(row):
    run_name = row['Run Name']
    if '/' in run_name:
        return run_name.split('/')[0]
    return "??"

# df['owner of run'] = df.apply(get_owner, axis=1)

def get_branch_owner(row):
    branch = row['Branch'].split('/')
    if len(branch) > 1:
        return branch[0]
    return "??"

def get_project(row):
    owner = get_owner(row)
    branch_owner = get_branch_owner(row)
    return f"{owner} | {branch_owner}"
    

df["project"] = df.apply(get_project, axis=1)
df.reset_index(inplace=True)
df_sum = (df.groupby("project")['cost'].sum() / total_cost).reset_index().sort_values("cost", ascending=False)
df_sum


# In[6]:


def get_intervals(row):
    d_from = row['Time Start']
    d_to = row['Time Stop']
    return pd.date_range(start=d_from, end=d_to, freq='min')
    
df['intervals'] = df.apply(get_intervals, axis=1)
df1 = df.explode('intervals').reset_index(drop=True)
df1.rename(columns={'intervals': 'Time'}, inplace=True)
df1['Date'] = df1['Time'].dt.floor('d')
print(df.shape, df1.shape)
df1.head(3)


# In[7]:


date_col = 'Date'
acc_col_name = 'Accumulated Sum of Instances Cost'
cost_col_name = 'Instances Cost'

df2 = df1.groupby(date_col)['aws_cost'].agg(sum).reset_index()

# because minutes intervals are used
df2['aws_cost'] = df2['aws_cost'] / 60.0

df2.rename(columns={'aws_cost': cost_col_name}, inplace=True)

df2[acc_col_name] = np.cumsum(df2[cost_col_name])
ax = df2.plot(x=date_col, y=acc_col_name, figsize=(10,5))
df2.plot(x=date_col, y=cost_col_name, ax = ax)


# In[ ]:




