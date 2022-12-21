#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


# In[2]:


## reading exported csv

# to get csv - save 'BEAM Deploy Status and Run Data' as csv
# if there is not enough permissions - save a copy and then save as csv

data = pd.read_csv("../../../beam-production/jupyter/local_files/simulations_spreadsheet.csv", parse_dates=['Time'])

# using only runs from specific data 
min_time = pd.to_datetime("2022-12-01") # yyyy-mm-dd
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

all_columns = set(df.columns)
taken_columns = take_first_columns + ['Time Start', 'Time Stop']

df = df[taken_columns].copy()

print(f"removed columns: {list(sorted(all_columns - set(taken_columns)))}")

# fix for some wierd shift in the spreadsheet for few rows
for v in ['ec2-18-221-208-40.us-east-2.compute.amazonaws.com','ec2-3-144-69-95.us-east-2.compute.amazonaws.com','ec2-52-15-53-101.us-east-2.compute.amazonaws.com']:
    df.replace(to_replace=v, value='r5d.24xlarge', inplace=True)

df['duration_hours'] = (df['Time Stop'] - df['Time Start']).astype('timedelta64[h]')

df.head(3)


# In[4]:


## calculating a price in USD of each simulation

instance_to_price = {
    'c5d.24xlarge' : 4.608,
    'c6a.24xlarge' : 3.672,
    'hpc6a.48xlarge' : 2.88,
    'm4.16xlarge' : 3.2,
    'm5.12xlarge' : 2.304,
    'm5.24xlarge' : 4.608,
    'm5d.24xlarge' : 5.424,
    'r5.24xlarge' : 6.048,
    'r5.2xlarge' : 0.504,
    'r5.4xlarge' : 1.008,
    'r5.8xlarge' : 2.016,
    'r5.large' : 0.126,
    'r5.xlarge' : 0.252,
    'r5d.12xlarge' : 3.456,
    'r5d.16xlarge' : 4.608,
    'r5d.24xlarge' : 6.912,
    't2.medium' : 0.0464                 
}

# for instance_type in sorted(instance_to_price.keys()):
#     print(f"'{instance_type}' : {instance_to_price[instance_type]},")

missing_instance_types = set()
def get_price(row):
    instance_type = row['Instance type']
    if instance_type in instance_to_price :
        return instance_to_price[instance_type]

    missing_instance_types.add(instance_type)
    return 0.0

df['aws_price_cost'] = df.apply(get_price, axis=1)

if len(missing_instance_types) > 0:
    print(f"Can't find price for {len(missing_instance_types)} instance types.")
    for missing_instance in missing_instance_types:
        print(f"'{missing_instance}': ,")
    
df['cost'] = df['duration_hours'] * df['aws_price_cost']
total_cost = int(df['cost'].sum())

def print_total_info():
    dt_interval = f"from {min_time.strftime('%Y-%m-%d')} to {data['Time'].max().strftime('%Y-%m-%d')}"
    print(f"There are {len(df)} simulations {dt_interval}")
    print(f"The total cost of all instances time is ${total_cost}")

print_total_info()
    
df.head(3)


# In[5]:


## grouping simulations by projects

def get_owner(row):
    run_name = row['Run Name']
    if '/' in run_name:
        return run_name.split('/')[0]
    return run_name


def get_branch_owner(row):
    branch = row['Branch'].split('/')
    if len(branch) > 1:
        return branch[0]
    return branch


def get_project(row):
    owner = get_owner(row)
    branch_owner = get_branch_owner(row)
    project = f"{owner} | {branch_owner}".lower()
    
    if 'new-york' in project:
        return "NYC"
    if 'freight' in project:
        return "Freight"
    if 'micro-mobility' in project or 'micromobility' in project and 'j503440616atberkeley_edu' in project:
        return "micro-mobility by Xuan"
    if 'shared' in project and 'j503440616atberkeley_edu' in project:
        return "shared fleet by Xuan"
    if 'profiling' in project:
        return "profiling"
    
    return project
    
print_total_info()

df["project"] = df.apply(get_project, axis=1)
df_sum = (df.groupby("project")['cost'].sum() / total_cost).reset_index().sort_values("cost", ascending=False)
df_sum


# In[ ]:




