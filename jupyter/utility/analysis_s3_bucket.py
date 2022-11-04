#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import requests
import xml.etree.ElementTree as ET
import pandas as pd
import math
from datetime import datetime

directory_depth=2

xmlns='{http://s3.amazonaws.com/doc/2006-03-01/}'

base_beam_s3_url='https://beam-outputs.s3.amazonaws.com/?list-type=2&delimiter=%2F'
# resp = requests.get(base_beam_s3_url)

# print(resp)
# print(resp.content)


# In[ ]:


def bytesToSize(bytes):
    sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB']
    if (bytes == 0):
        return '0 Bytes'
    ii = int(math.floor(math.log(bytes) / math.log(1024)))
    num = round(bytes / math.pow(1024, ii), 2)
    return '%d %s' % (num, sizes[ii])


# In[ ]:



def add_s3_object_to_array(prefix, result_array):
    
#     if len(result_array)>1000:
#         return

    now = datetime.now()
    current_time = now.strftime("%H:%M:%S")
    print('Current Time = %s, result_array size is %d' % (current_time, len(result_array)) )
    print('calling add_s3_object_to_array with prefix:' + prefix)
    print('')

    s3_url = base_beam_s3_url
    s3_prefix = 'beam-outputs/'
    if prefix != '':
        s3_url = f'{base_beam_s3_url}&prefix={prefix}%2F'
        s3_prefix = f'beam-outputs/{prefix}/'
    resp = requests.get(s3_url)
    root = ET.fromstring(resp.content)
    
    try:
        prefix_of_node=root.find(f'{xmlns}Prefix').text
        folder_names=[]
        second_round_sub_folders=[]
        for common_prefix in root.iter(f'{xmlns}CommonPrefixes'):
            sub_prefix=common_prefix.find(f'{xmlns}Prefix').text
            if sub_prefix != prefix_of_node and "index.html#pilates-outputs" not in sub_prefix:
                folder_names.append(sub_prefix[:-1])

        for content in root.iter(f'{xmlns}Contents'):
            key=content.find(f'{xmlns}Key').text
            size=content.find(f'{xmlns}Size').text
            LastModified=content.find(f'{xmlns}LastModified').text
            if key in folder_names:
                second_round_sub_folders.append(key)
            elif prefix_of_node != key and size.isnumeric():
                result_array.append((f'{s3_prefix}{key}', LastModified, bytesToSize(int(size)), size))

        # print('folder_names size is %d' % len(folder_names) )
        if(len(folder_names) > 0):
            print(folder_names)
            for sub_folder in folder_names:
                if sub_folder != '':
                    add_s3_object_to_array(sub_folder, result_array)
    except Exception as e:
        print(root)
        print(root.find(f'{xmlns}Prefix'))
        print(e)  
    
result_array_out=[]
add_s3_object_to_array('', result_array_out)
   
print(len(result_array_out))   
# print(result_array)


# In[4]:



## sorting the rows which still in S3 bucket
print(len(result_array_out)) 
# https://www.programiz.com/python-programming/methods/list/sort
result_array_out.sort(key=lambda x: x[0])

# rows_order_by_file_size=result_array_out.sort(key=lambda x: x[2])


# In[6]:


## generate output.csv
pd.DataFrame(result_array_out).to_csv("../local_files/rows_order_by_path.csv", header=["path", "time", "size", "size"], index=None)

rows_order_by_path_csv = pd.read_csv("../local_files/rows_order_by_path.csv")

print(rows_order_by_path_csv.head(10))


# In[ ]:




