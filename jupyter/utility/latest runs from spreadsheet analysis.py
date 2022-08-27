#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[3]:


## reading exported csv

# to get csv - save 'BEAM Deploy Status and Run Data' as csv
# if there is not enough permissions - save a copy and then save as csv

data = pd.read_csv("../../../beam-production/jupyter/local_files/Copy of BEAM Deploy Status and Run Data - BEAM Instances.csv", parse_dates=['Time'])

# using only runs from specific data 
min_time = pd.to_datetime("2022-03-01") # yyyy-mm-dd
data = data[data['Time'] > min_time].copy()

print(f"there are roughly {len(data) / 2} runs since {min_time}")
print(f"the latest run is from {data['Time'].max()}")

data['Month Period'] = data['Time'].dt.strftime('%Y-%m')
print(f"following data periods are included: {sorted(data['Month Period'].unique())}")

data.head(3)


# In[4]:


## getting data frame with each row as one simulation

take_first_columns = ['Run Name','Month Period','Branch','Instance type']

df = data.groupby("Host name").agg(list)
for col in take_first_columns:
    df[col] = df.apply(lambda r: r[col][0], axis=1)

df['Time Start'] = df.apply(lambda r: r['Time'][0], axis=1)
df['Time Stop'] = df.apply(lambda r: r['Time'][-1], axis=1)

all_columns = set(df.columns)
taken_columns = take_first_columns + ['Time Start', 'Time Stop']

df = df[taken_columns].copy()

print(f"removed columns: {list(sorted(all_columns - set(taken_columns)))}")

# fix for some wierd shift in the spreadsheet for few rows
for v in ['ec2-18-221-208-40.us-east-2.compute.amazonaws.com','ec2-3-144-69-95.us-east-2.compute.amazonaws.com','ec2-52-15-53-101.us-east-2.compute.amazonaws.com']:
    df.replace(to_replace=v, value='r5d.24xlarge', inplace=True)

df['duration_hours'] = (df['Time Stop'] - df['Time Start']).astype('timedelta64[h]')

df.head(3)


# In[5]:


## just a check
df['Instance type'].unique()


# In[6]:


## calculating a price in USD of each simulation

instance_to_price = {'r5d.24xlarge':6.912, 
                     'm5d.24xlarge':5.424, 
                     'r5.xlarge':0.252, 
                     'r5.24xlarge':6.048,
                     'r5.8xlarge':2.016, 
                     'm5.24xlarge':4.608, 
                     'r5.2xlarge':0.504, 
                     'm4.16xlarge':3.20
                    }

missing_instance_types = set()
def get_price(row):
    instance_type = row['Instance type']
    if instance_type in instance_to_price :
        return instance_to_price[instance_type]

    missing_instance_types.add(instance_type)
    return 0.0

df['aws_price_cost'] = df.apply(get_price, axis=1)

if len(missing_instance_types) > 0:
    print(f"Can't find price for following instances: {missing_instance_types}")
    
df['cost'] = df['duration_hours'] * df['aws_price_cost']
print(f"The total cost of all instances from df: {int(df['cost'].sum())}")
df.head(3)


# In[17]:


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
df_sum = (df.groupby("project")['cost'].sum() / 32355).reset_index().sort_values("cost", ascending=False)
df_sum


# In[ ]:





# In[ ]:




