#!/usr/bin/env python
# coding: utf-8

# In[1]:


# %pip install boto3
import pip
import itertools
import pandas as pd
import os

class StopExecution(Exception):
    def _render_traceback_(self):
        pass


def import_or_install(package):
    try:
        __import__(package)
        print(f'{package} is already installed')
    except ImportError:
        print(f'Installing {package}')
        pip.main(['install', package])


import_or_install("boto3")
import boto3

aws_access_key_id = ""
aws_secret_access_key = ""


# In[4]:


bucket_name = 'beam-outputs'



s3 = boto3.client('s3', aws_access_key_id=aws_access_key_id,
                  aws_secret_access_key=aws_secret_access_key) if aws_access_key_id else boto3.client('s3')
paginator = s3.get_paginator('list_objects_v2')

from dataclasses import dataclass
from datetime import datetime


@dataclass
class PrefixContent:
    prefix: str
    objects: []
    folders: []
    last_modified: datetime
@dataclass
class BeamFolder:
    path: str
    last_modified: datetime


def find_content(prefix: str):
    pages = paginator.paginate(Bucket=bucket_name, Prefix=prefix, Delimiter="/")
    objects = []
    folders = []
    last_modified = None
    for page in pages:
        if "Contents" in page:
            for obj in page["Contents"]:
                objects.append(obj["Key"])
                if not last_modified:
                    last_modified = obj["LastModified"]
        if "CommonPrefixes" in page:
            for obj in page["CommonPrefixes"]:
                folders.append(obj["Prefix"])

    return PrefixContent(prefix, objects, folders, last_modified)


# 

# In[6]:


# Searches for beam folders on S3 bucket and saves path/last_modified/size to a csv file (local_files dir)
# using low level API to get only the first level of subfolders
search_prefix = 'back'

from dataclasses import dataclass
from datetime import datetime

@dataclass
class BeamFolder:
    path: str
    last_modified: datetime


def find_beam_folders(prefix: str):
    content = find_content(prefix)
    if any(x for x in content.folders if x.endswith("/ITERS/")) | any(
            x for x in content.objects if x.endswith("/beamLog.out")):
        beam_folder = BeamFolder(content.prefix, content.last_modified)
        print(beam_folder)
        return [beam_folder]
    else:
        return [x for folder in content.folders for x in find_beam_folders(folder)]


beam_folders = find_beam_folders(search_prefix)

result = [[x.path, x.last_modified] for x in beam_folders]

df = pd.DataFrame(result, columns=["path", "date"])
file_name = search_prefix.split('/')[-1] + ".csv"
docker_path = "/home/jovyan/local_files"
dir_to_save = docker_path if os.path.isdir(docker_path) else "../local_files"

display(df)
df.to_csv(os.path.join(dir_to_save, file_name), index=False)


# pages = paginator.paginate(Bucket=bucket_name, Prefix=search_prefix, Delimiter="/")
# for page in pages:
#     print("Sub-folders:", list(obj["Prefix"] for obj in page["CommonPrefixes"]))
#     print("Objects:", list(obj["Key"] for obj in page["Contents"]))


# In[23]:


# for a list of aws folders
# it finds a file within a folder and get lastModified attribute
input = "beam_output_url"
input_folders = pd.read_csv("../local_files/%s.csv" % input, names=['path', 'date', 'url'], parse_dates=['date'])
# input_folders['date'] = input_folders['date'].dt.tz_convert("UTC")

def get_last_modified(prefix: str):
    print(prefix)
    content = find_content(prefix)
    if content.last_modified:
        return content.last_modified
    elif len(content.folders) > 0:
        return get_last_modified(content.folders[0])
    else:
        return None

mask = input_folders['date'].isnull()

input_folders.loc[mask, 'date'] = input_folders.loc[mask]['path'].apply(get_last_modified)
# input_folders['url'] = "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#" + input_folders['path']
input_folders.to_csv("../local_files/%s_last_modified.csv" % input, index=False, header=False)
input_folders


# In[5]:


# concat 3 data files and make a list for deep archiving;
# everything before 2023 and not in the keep list
to_keep = pd.read_csv("../local_files/to_keep.csv")
to_keep_t = tuple(to_keep['path'].tolist())

beam_output = pd.read_csv("../local_files/beam_output_url.csv", names=['path', 'date', 'url'], parse_dates=['date'])
pilates_output = pd.read_csv("../local_files/pilates_output_url_last_modified.csv", names=['path', 'date', 'url'], parse_dates=['date'])
others = pd.read_csv("../local_files/other_data_last_modified.csv", names=['path', 'size', 'date', 'url'], parse_dates=['date'])
others = others.drop(columns=['size'])
all = pd.concat([beam_output, pilates_output, others])
display(len(all))

display(all[all['path'] == "output/austin/austin-200k-gh-car-only__2020-09-11_13-40-41_slo/"])

time_thres = pd.Timestamp("2023-01-01 00:00:00+00:00")

archive = all[(all['date'] < time_thres)].copy()
display(len(archive))


archive['to_keep'] = archive.apply(lambda row: row['path'].startswith(to_keep_t), axis=1)
archive = archive[archive['to_keep'] == False].drop(columns="to_keep")
display(len(archive))
display(archive[archive['path'] == "output/austin/austin-200k-gh-car-only__2020-09-11_13-40-41_slo/"])
# display(archive)
# archive.drop(columns=['date', 'url']).to_csv("../local_files/archive.csv", index=False, header=False)


# In[3]:


# move s3 folders (read from a csv file) to some location within the same bucket
bucket_name = 'beam-outputs'
destination = "archive/root"
file = "../local_files/test_moved.csv"

import multiprocessing

if destination.endswith("/"): destination = destination[:-1]

paths = pd.read_csv(file)['path'].tolist()

print(f"Moving {paths} to {destination}")

s3 = boto3.resource('s3', aws_access_key_id=aws_access_key_id,
                    aws_secret_access_key=aws_secret_access_key) if aws_access_key_id else boto3.resource('s3')

not_to_delete = pd.read_csv("../local_files/not_to_delete.csv")['path'].tolist()

for path in paths:
    path = path.strip()
    if path.endswith("/"): path = path[:-1]
    if path == "": continue
    if any(x for x in not_to_delete if path.startswith(x)):
        print(f"NOT DELETE {path}")
        continue
    print(f"Moving {path} to {destination}")

    last_index = path.rfind('/')
    outer_folder = path[0:last_index] if last_index >= 0 else ""
    print(outer_folder)

    def move_obj(obj_key):
        copy_source = {'Bucket': bucket_name, 'Key': obj_key}
        new_folder = obj_key[len(outer_folder):]
        if  not new_folder.startswith("/"):
            new_folder = "/" + new_folder
        new_key = destination + new_folder
        # print(new_key)
        s3.meta.client.copy(copy_source, bucket_name, new_key)
        s3.meta.client.delete_object(Bucket=bucket_name, Key=obj_key)

    bucket = s3.Bucket(bucket_name)
    object_keys = [obj.key for obj in bucket.objects.filter(Prefix=path)]
    with multiprocessing.Pool(multiprocessing.cpu_count()) as p:
        p.map(move_obj, object_keys)

    print(f"Moved {path}")

print(f"Done")


# In[62]:


# keep data that is after 2022 and the data that is in to_keep.csv

to_keep = pd.read_csv("../local_files/to_keep.csv")
to_keep_t = tuple(to_keep['path'].tolist())
input = "other_data"
all_data = pd.read_csv("../local_files/%s.csv" % input, names=['path', 'size', 'date'], parse_dates=['date'])
# display(all_data)
time_thres = pd.Timestamp("2022-01-01 00:00:00+00:00")

tbd = all_data[(all_data['date'] < time_thres)
                | (all_data['date'].isna() & all_data['path'].str.contains('20((15)|(16)|(17)|(18)|(19)|(20)|(21))'))
                | (all_data['path'].str.startswith('archive'))].copy()
# display(tbd)


tbd['to_keep'] = tbd.apply(lambda row: row['path'].startswith(to_keep_t), axis=1)
tbd = tbd[tbd['to_keep'] == False].drop(columns="to_keep")

tbd['url'] = "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#" + tbd['path']

tbd.to_csv("../local_files/%s_tbd.csv" % input, index=False, header=False)
tbd


# In[66]:


# Add url column

input = "beam_output"
all_data = pd.read_csv("../local_files/%s.csv" % input, names=['path', 'date'], parse_dates=['date'])

all_data['url'] = "https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#" + all_data['path']

all_data.to_csv("../local_files/%s_url.csv" % input, index=False, header=False)
all_data


# In[8]:


# check if all the data is safe to delete

safe_to_del = pd.read_csv("../local_files/latest_beam_output_tbd.csv", names=['path', 'date', 'url'], parse_dates=['date'])

input = "atd_beam"
all_data = pd.read_csv("../local_files/%s.csv" % input, names=['path'])

all_data[~all_data['path'].isin(safe_to_del['path'])]


# In[7]:


# check if all the data is safe to delete

to_keep = pd.read_csv("../local_files/beam_del_exclude.csv", names=['path'])

input = "atd_beam"
all_data = pd.read_csv("../local_files/%s.csv" % input, names=['path'])

tbd = all_data[~all_data['path'].isin(to_keep['path'])]
tbd.to_csv("../local_files/approved_to_delete.csv", index=False, header=False)
tbd


# In[10]:


# remove deleted entries
beam_out_tbd = pd.read_csv("../local_files/latest_beam_output_tbd.csv", names=['path', 'date', 'url'], parse_dates=['date'])
atd = pd.read_csv("../local_files/approved_to_delete.csv", names=['path'])

cleaned_removed_entries = beam_out_tbd[~beam_out_tbd['path'].isin(atd['path'])]
cleaned_removed_entries.to_csv("../local_files/beam_output_tbd_cleaned.csv", index=False, header=False)
cleaned_removed_entries


# In[4]:


# filter out big entries
big_entries = pd.read_csv("../local_files/big_entries.csv", names=['path'])
to_archive = pd.read_csv("../local_files/prod_archive.csv", names=['path'])

big_entries_to_delete = big_entries[big_entries['path'].isin(to_archive['path'])]
to_archive_small_entries = to_archive[~to_archive['path'].isin(big_entries_to_delete['path'])]
to_archive_small_entries.to_csv("../local_files/to_archive_small_entries.csv", index=False, header=False)
big_entries_to_delete.to_csv("../local_files/big_entries_to_delete.csv", index=False, header=False)

