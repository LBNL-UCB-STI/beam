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

local_path = '../local_files/latest_all_runs.csv'
data = pd.read_csv(local_path, parse_dates=['Time'])
data['unique_key'] = data.apply(lambda r: f"{r['Host name']}|{r['Run Name']}|{r['Batch']}", axis=1)

# using only runs from specific data 
min_time = pd.to_datetime("2022-02-01") # yyyy-mm-dd
max_time = pd.to_datetime("2023-02-01") # data['Time'].max()
data = data[(data['Time'] > min_time) & (data['Time'] < max_time)].copy()

print(f"there are roughly {len(data) // 2} runs from {data['Time'].min().strftime('%Y-%m-%d')} to {data['Time'].max().strftime('%Y-%m-%d')}")
print(f"the latest run is from {data['Time'].max()}")

data['Month Period'] = data['Time'].dt.strftime('%Y-%m')
data.head(2)


# In[ ]:


## getting data frame with each row as one simulation

take_first_columns = ['Run Name','Month Period','Branch','Instance type']

df = data.groupby("unique_key").agg(list)
for col in take_first_columns:
    df[col] = df.apply(lambda r: r[col][0], axis=1)

df['Time Start'] = df.apply(lambda r: r['Time'][0], axis=1)
df['Time Stop'] = df.apply(lambda r: r['Time'][-1], axis=1)
df['Status'] = df.apply(lambda r: r['Status'][-1], axis=1)

all_columns = set(df.columns)
taken_columns = take_first_columns + ['Time Start', 'Time Stop', 'Status', 'Time']

df = df[taken_columns].copy()

removed_columns = list(sorted(all_columns - set(taken_columns)))
half_len = int(len(removed_columns)/2)
print(f"removed columns: {removed_columns}")

# fix for some wierd shift in the spreadsheet for few rows
for v in ['ec2-18-221-208-40.us-east-2.compute.amazonaws.com',
          'ec2-3-144-69-95.us-east-2.compute.amazonaws.com',
          'ec2-52-15-53-101.us-east-2.compute.amazonaws.com']:
    df.replace(to_replace=v, value='r5d.24xlarge', inplace=True)

df['duration_hours'] = (df['Time Stop'] - df['Time Start']).astype('timedelta64[h]')
print(f"duration in hours total: {df['duration_hours'].sum()}")
df.head(2)


# In[ ]:


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
    't2.medium' : 0.0464,
    't2.small': 0.023,
    'c5.18xlarge': 3.06,
    'c5.9xlarge': 1.53,
    'c5d.4xlarge': 0.768,
    'm5d.12xlarge': 2.712,
    'r5.12xlarge': 3.024,
    'r5a.16xlarge': 3.616,
    'r5a.4xlarge': 0.904,
    'r5d.2xlarge': 0.576,
    'r5d.4xlarge': 1.152,
    'z1d.12xlarge': 4.464,
    'n2d-standard-2': 0.0,    # a google cloud instance, actually 0.084492, but we are interested in AWS only,
    'n2d-standard-4': 0.0,    # a google cloud instance, actually 0.168984, but we are interested in AWS only,
    'n2d-standard-64' : 0.0,  # a google cloud instance, ignoring for now
}

instance_to_number_of_cores = {
    'c5d.24xlarge': 96,
    'c6a.24xlarge': 96,
    'hpc6a.48xlarge': 96,
    'm5.12xlarge': 48,
    'r5.24xlarge': 96,
    'r5.2xlarge': 8,
    'r5.4xlarge': 16,
    'r5.8xlarge': 32,
    'r5.large': 2,
    'r5.xlarge': 4,
    'r5d.12xlarge': 48,
    'r5d.16xlarge': 64,
    'r5d.24xlarge': 96,
    't2.medium': 2,
    't2.small': 1,
    'c5.18xlarge': 72,
    'c5.9xlarge': 36,
    'c5d.4xlarge': 16,
    'm4.16xlarge': 64,
    'm5.24xlarge': 96,
    'm5d.12xlarge': 48,
    'm5d.24xlarge': 96,
    'r5.12xlarge': 48,
    'r5a.16xlarge': 64,
    'r5a.4xlarge': 16,
    'r5d.2xlarge': 8,
    'r5d.4xlarge': 16,
    'z1d.12xlarge': 48,
    'n2d-standard-2': 0,     # a google cloud instance, ignoring for now
    'n2d-standard-4': 0,     # a google cloud instance, ignoring for now
    'n2d-standard-64' : 0,   # a google cloud instance, ignoring for now
}

"done"

inst2price2cores = []

for inst_type in instance_to_price.keys():
    price = instance_to_price[inst_type]
    cores = instance_to_number_of_cores[inst_type]
    inst2price2cores.append((inst_type, price, cores))

aws_info_df = pd.DataFrame(inst2price2cores, columns=['Instance', 'Price', 'Number of Cores'])
aws_info_df['price_to_core'] = aws_info_df['Price'] / aws_info_df['Number of Cores']
aws_info_df.sort_values('price_to_core').head()


# In[ ]:


## calculating a price in USD of each simulation

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
    for missing_instance in sorted(missing_instance_types):
        print(f"'{missing_instance}': ,")


missing_instance_types = set()
def get_number_of_cores(row):
    instance_type = row['Instance type']
    if instance_type in instance_to_number_of_cores:
        return instance_to_number_of_cores[instance_type]

    missing_instance_types.add(instance_type)
    return 0

df['aws_instance_number_of_cores'] = df.apply(get_number_of_cores, axis=1)

if len(missing_instance_types) > 0:
    print(f"Can't find number of cores for {len(missing_instance_types)} instance types.")
    for missing_instance in sorted(missing_instance_types):
        print(f"'{missing_instance}': ,")

def get_cores_hours(row):
    number_of_cores = row['aws_instance_number_of_cores']
    number_of_hours = row['duration_hours']
    return number_of_cores * number_of_hours

df['aws_corehours_per_simulation'] = df.apply(get_cores_hours, axis=1)
    
df['cost'] = df['duration_hours'] * df['aws_instance_hour_cost']
total_cost = int(df['cost'].sum())

def print_total_info(total_cost_fixed=None):
    print(f"There are {len(df)} simulations from {data['Time'].min().strftime('%Y-%m-%d')} to {data['Time'].max().strftime('%Y-%m-%d')}")
    if total_cost_fixed:
        print(f"The total cost of all instances time is ${total_cost_fixed}")
    else:
        print(f"The total cost of all instances time is ${total_cost}")

print_total_info()

print(f"aws core hours: {df['aws_corehours_per_simulation'].sum()}")
print(f"total cost: {df['cost'].sum()}")
df.groupby('Month Period').agg({'cost':['sum','count']})


# In[ ]:


## applying 'project' to the list of simulations based on simulation name and|or git branch name


def get_branch_owner(row):
    branch = row['Branch'].split('/')
    if len(branch) > 1:
        return branch[0]
    return branch


other_projects = set()

def get_project(row):
    run_name = row['Run Name']
    branch_owner = get_branch_owner(row)
    project = f"{branch_owner} | {run_name}".lower()

    if 'new-york' in project:
        return "NYC"
    if 'freight' in project:
        return "Freight"
    if 'gemini' in project:
        return "Gemini"
    if 'micro-mobility' in project or 'micromobility' in project:
        return "Micro-Mobility"
    if 'shared' in project:
        return "Shared Fleet"
    if 'profiling' in project:
        return "CPU profiling"
    
    other_projects.add(project)
    return 'other'


df["project"] = df.apply(get_project, axis=1)
list_of_all_projects = sorted(list(df['project'].unique()))
print(f"there are {len(list_of_all_projects)} projects ({len(other_projects)} runs classified as 'other'):")
for project_name in list_of_all_projects:
    print(f"\t{project_name}")


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

df_grouped["Duration Hours Total"] = df_grouped.apply(lambda r: sum(r['duration_hours']), axis=1)
df_grouped["Instance time cost"] = df_grouped.apply(lambda r: sum(r['cost']), axis=1)
df_grouped["Fraction of total cost"] = df_grouped.apply(lambda r: r['Instance time cost'] / total_cost, axis=1)
df_grouped["AWS Core-Hours"] = df_grouped.apply(lambda r: sum(r['aws_corehours_per_simulation']), axis=1)
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


columns_with_numbers = ["Instance time cost", "Failed runs time cost", "Fraction of total cost", 
                        "Completed runs", "Failed runs", "Maybe still running", 
                        "AWS Core-Hours", "Duration Hours Total"]
df_grouped.loc["Total"] = df_grouped[columns_with_numbers].sum()

selected_columns = ["project", "Instance types"] + columns_with_numbers

print_total_info()
df_grouped[selected_columns]


# In[ ]:


### a short version

print_total_info(total_cost_fixed="???")
df_grouped[["project", "Fraction of total cost", "AWS Core-Hours"]]


# In[ ]:


### grouped by instance type

df_grouped_by_instance = df.groupby('Instance type').agg(list).reset_index()


df_grouped_by_instance['cost_per_instance_type'] = df_grouped_by_instance.apply(lambda r: sum(r['cost']), axis=1)
total_cost = df_grouped_by_instance['cost_per_instance_type'].sum()
df_grouped_by_instance['Fraction of Total Cost'] = df_grouped_by_instance.apply(lambda r: r['cost_per_instance_type'] / total_cost, axis=1)

df_grouped_by_instance.sort_values('Fraction of Total Cost', ascending=False, inplace=True)
df_grouped_by_instance.reset_index(inplace=True)

# print_total_info()
df_grouped_by_instance[['Instance type', 'Fraction of Total Cost']].head(5)


# In[ ]:


df_grouped_by_instance.groupby


# In[ ]:





# In[ ]:





# In[ ]:




