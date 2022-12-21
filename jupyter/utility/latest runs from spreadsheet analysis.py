#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('max_colwidth', None)


# In[ ]:


## reading exported csv

# to get csv - save 'BEAM Deploy Status and Run Data' as csv
# if there is not enough permissions - save a copy and then save as csv

local_path = 'beam-production/jupyter/local_files/latest_all_runs.csv'
data = pd.read_csv(f"../../../{local_path}", parse_dates=['Time'])

# using only runs from specific data 
min_time = pd.to_datetime("2022-12-01") # yyyy-mm-dd
data = data[data['Time'] > min_time].copy()

print(f"there are roughly {len(data) / 2} runs since {min_time.strftime('%Y-%m-%d')}")
print(f"the latest run is from {data['Time'].max()}")

data['Month Period'] = data['Time'].dt.strftime('%Y-%m')
print(f"following data periods are included: {sorted(data['Month Period'].unique())}")

data.head(3)


# In[ ]:


## getting data frame with each row as one simulation

take_first_columns = ['Run Name','Month Period','Branch','Instance type']

df = data.groupby("Host name").agg(list)
for col in take_first_columns:
    df[col] = df.apply(lambda r: r[col][0], axis=1)

df['Time Start'] = df.apply(lambda r: r['Time'][0], axis=1)
df['Time Stop'] = df.apply(lambda r: r['Time'][-1], axis=1)
df['Status'] = df.apply(lambda r: r['Status'][-1], axis=1)

all_columns = set(df.columns)
taken_columns = take_first_columns + ['Time Start', 'Time Stop', 'Status']

df = df[taken_columns].copy()

removed_columns = list(sorted(all_columns - set(taken_columns)))
half_len = int(len(removed_columns)/2)
print(f"removed columns: {removed_columns}")

# fix for some wierd shift in the spreadsheet for few rows
for v in ['ec2-18-221-208-40.us-east-2.compute.amazonaws.com','ec2-3-144-69-95.us-east-2.compute.amazonaws.com','ec2-52-15-53-101.us-east-2.compute.amazonaws.com']:
    df.replace(to_replace=v, value='r5d.24xlarge', inplace=True)

df['duration_hours'] = (df['Time Stop'] - df['Time Start']).astype('timedelta64[h]')

df.head(3)


# In[ ]:


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
def get_instance_hour_cost(row):
    instance_type = row['Instance type']
    if instance_type in instance_to_price :
        return instance_to_price[instance_type]

    missing_instance_types.add(instance_type)
    return 0.0

df['aws_instance_hour_cost'] = df.apply(get_instance_hour_cost, axis=1)

if len(missing_instance_types) > 0:
    print(f"Can't find price for {len(missing_instance_types)} instance types.")
    for missing_instance in missing_instance_types:
        print(f"'{missing_instance}': ,")
    
df['cost'] = df['duration_hours'] * df['aws_instance_hour_cost']
total_cost = int(df['cost'].sum())


budget_amount_used_from_aws = 35268.65
def print_total_info():
    dt_interval = f"from {min_time.strftime('%Y-%m-%d')} to {data['Time'].max().strftime('%Y-%m-%d')}"
    print(f"There are {len(df)} simulations {dt_interval}")
    delta = int(budget_amount_used_from_aws - total_cost)
    print(f"The total cost of all instances time is ${total_cost}, amount of calculated by AWS: ${budget_amount_used_from_aws} [${delta} unrecorded on our side]")

print_total_info()
    
df.head(3)


# In[ ]:


## applying 'project' to the list of simulations based on simulation name and|or git branch name

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
        return f"NYC"
    if 'freight' in project:
        return "Freight"
    if 'gemini' in project:
        return "Gemini"
    if 'micro-mobility' in project or 'micromobility' in project:
        return "micro-mobility"
    if 'shared' in project:
        return "shared fleet"
    if 'profiling' in project:
        return "CPU profiling"
    
    return project


df["project"] = df.apply(get_project, axis=1)
df.head(2)


# In[ ]:


### processing simulations in unknown state, i.e. with 'Run Started' status
def get_fixed_status(row):
    status = row["Status"]
    if status != 'Run Started':
        return status
    inactive_time = pd.Timestamp.now() - row['Time Start']
    if inactive_time.days > 2:
        return 'Run Failed'
    return 'Maybe Running'

df['Status Fixed'] = df.apply(get_fixed_status, axis=1)


### grouping dataframe by project
df_grouped = df.groupby("project").agg(list).reset_index()

df_grouped["Instance time cost"] = df_grouped.apply(lambda r: sum(r['cost']), axis=1)
df_grouped["Fraction of total cost"] = df_grouped.apply(lambda r: r['Instance time cost'] / total_cost, axis=1)
df_grouped = df_grouped.sort_values("Fraction of total cost", ascending=False).reset_index()


def failed_runs_time_cost(project_row):
    runs_state = project_row['Status Fixed']
    runs_cost = project_row['cost']
    failed_cost_sum = 0.0
    for (state, cost) in zip(runs_state, runs_cost):
        if state == 'Run Failed':
            failed_cost_sum += cost
    return failed_cost_sum

df_grouped["Failed runs time cost"] = df_grouped.apply(failed_runs_time_cost, axis=1)


df_grouped["Instance types"] = df_grouped.apply(lambda r: list(set(r["Instance type"])), axis=1)

# 'Run Failed', 'Run Completed', 'Run Started', 'Unable to start'
df_grouped["Failed runs"] = df_grouped.apply(lambda r: r['Status Fixed'].count('Run Failed')+r['Status'].count('Unable to start'), axis=1)
df_grouped["Completed runs"] = df_grouped.apply(lambda r: r['Status Fixed'].count('Run Completed'), axis=1)
df_grouped["Maybe still running"] = df_grouped.apply(lambda r: r['Status Fixed'].count('Maybe Running'), axis=1)


columns_with_numbers = ["Instance time cost", "Failed runs time cost", "Fraction of total cost", "Completed runs", "Failed runs", "Maybe still running"]
df_grouped.loc["Total"] = df_grouped[columns_with_numbers].sum()

selected_columns = ["project", "Instance types"] + columns_with_numbers

print_total_info()
df_grouped[selected_columns]


# In[ ]:


df_grouped_by_instance = df.groupby('Instance type').agg(list).reset_index()
# ['Instance type', 'Run Name', 'Month Period', 'Branch', 'Time Start', 'Time Stop', 'Status', 'duration_hours', 'aws_instance_hour_cost', 'cost', 'project', 'Status Fixed']

df_grouped_by_instance['Total cost per instance'] = df_grouped_by_instance.apply(lambda r: sum(r['cost']), axis=1)
df_grouped_by_instance = df_grouped_by_instance.sort_values('Total cost per instance', ascending=False).reset_index()

print_total_info()
df_grouped_by_instance[['Instance type', 'Total cost per instance']]


# In[ ]:





# In[ ]:





# In[ ]:





# In[ ]:




