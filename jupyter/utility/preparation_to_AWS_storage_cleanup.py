#!/usr/bin/env python
# coding: utf-8

# In[1]:


import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)


# In[6]:


## define a method to check if beam log exists
import requests

def beam_log_exists(path):
    if(not isinstance(path, str)):
        return False
    s3url_fixed = path
    if path and ("#output/*/*" not in path):
        s3url_fixed = path.replace("s3.us-east-2.amazonaws.com/beam-outputs/index.html#output","beam-outputs.s3.amazonaws.com/output").strip()
    else:
        return False
    
    beam_log_path = f"{s3url_fixed}/beamLog.out"
    # print(beam_log_path)
    try:
        r = requests.head(beam_log_path)
        return r.status_code == requests.codes.ok
    except Exception:
        return False

# output_path = "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#output/*/*"
# print(beam_log_exists(output_path))


# In[9]:


## reading exported csv
from datetime import datetime

csv_data = pd.read_csv("../local_files/BEAM Deploy Status and Run Data - BEAM Instances.csv", parse_dates=['Time'])

exist_rows = []
non_exist_rows = []
total_row_length=len(csv_data['S3 Url'])

index = 0
for s3url in csv_data['S3 Url'].copy():
    index=index+1
    if index % 500 == 1:
        now = datetime.now()
        current_time = now.strftime("%H:%M:%S")
        print('Current Time = %s, %d out of %d' % (current_time, index, total_row_length))
    # if index > 360:
    #     break

    if(not isinstance(s3url, str)):
        continue
    # if 'https://' not in s3url:
    #     s3url=csv_data['S3 output path'][index-1]
    # if 'https://' not in s3url:
    #     s3url=csv_data['Commit'][index-1]
    
    branch = csv_data['Branch'][index-1]
    time = csv_data['Time'][index-1]
    
    if 'https://' not in s3url:
        non_exist_rows.append((branch, time, s3url))
        continue

    #csv_data['Time'][index-1]
    if(beam_log_exists(s3url)):
        exist_rows.append((branch, time, s3url))
    else:
        non_exist_rows.append((branch, time, s3url))

print("found rows:" + str(len(exist_rows)))


# In[10]:


## sorting the rows which still in S3 bucket

# https://www.programiz.com/python-programming/methods/list/sort
exist_rows.sort(key=lambda x: x[0])


# In[11]:


## generate output.csv
pd.DataFrame(exist_rows).to_csv("../local_files/preparation_to_AWS_storage_cleanup.csv", header=["branch", "time", "s3url"], index=None)

output_data = pd.read_csv("../local_files/preparation_to_AWS_storage_cleanup.csv")

print(output_data.head(10))


# In[ ]:




