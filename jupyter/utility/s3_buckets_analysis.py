#!/usr/bin/env python
# coding: utf-8

# In[3]:


# %pip install boto3
import pip
import boto3
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

aws_access_key_id = ""
aws_secret_access_key = ""


# In[6]:


# Searches for beam folders on S3 bucket and saves path/last_modified/size to a csv file (local_files dir)
bucket_name = 'beam-outputs'
search_path = 'output/beamville/beamville'

s3 = boto3.resource('s3', aws_access_key_id=aws_access_key_id, aws_secret_access_key=aws_secret_access_key) if aws_access_key_id else boto3.resource('s3')

bucket = s3.Bucket(bucket_name)
all_found_objects = list(bucket.objects.filter(Prefix=search_path))
print(f"found {len(all_found_objects)} objects")

def find_beam_folders(objects):
    keys = [x.key for x in objects]
    entries1 = [x.split("/ITERS")[0] for x in keys if "/ITERS" in x]
    entries2 = [x.split("/beamOutput.log")[0] for x in keys if "/beamOutput.log" in x]
    distinct = set(entries1)
    distinct.update(entries2)
    return distinct

beam_folders = find_beam_folders(all_found_objects)

if len(beam_folders) == 0:
    raise StopExecution

beam_folders_split = [x.split('/') for x in beam_folders]

root_path_length = min([len(x) for x in beam_folders_split]) - 1
root_path = next(iter(beam_folders_split))[:root_path_length]

def get_beam_folder(path):
    path_split = path.split('/')
    relative_path = path_split[root_path_length:]
    test_path = root_path.copy()
    for element in relative_path:
        test_path.append(element)
        if test_path in beam_folders_split:
            return '/'.join(test_path)

    return "_not_beam_content_"

grouped = itertools.groupby(all_found_objects, lambda obj: get_beam_folder(obj.key))

result = []
for [key, objects] in grouped:
    hard_objects = list(objects)
    size = sum([x.size for x in hard_objects])
    date = min([x.last_modified for x in hard_objects])
    result.append([key, date, size])

df = pd.DataFrame(result, columns=["path", "date", "size"])
file_name=search_path.split('/')[-1] + ".csv"
docker_path = "/home/jovyan/local_files"
dir_to_save = docker_path if os.path.isdir(docker_path) else "../local_files"

display(df)
df.to_csv(os.path.join(dir_to_save, file_name), index=False)


# In[14]:


# move s3 folders (read from a csv file) to some location within the same bucket
bucket_name = 'beam-outputs'
destination = "archive/beamville"
file = "../local_files/beamville_move.csv"

import multiprocessing

if destination.endswith("/"): destination = destination[:-1]

paths = pd.read_csv(file)['path'].tolist()

print(f"Moving {paths} to {destination}")

s3 = boto3.resource('s3', aws_access_key_id=aws_access_key_id, aws_secret_access_key=aws_secret_access_key) if aws_access_key_id else boto3.resource('s3')



for path in paths:
    path = path.strip()
    if path.endswith("/"): path = path[:-1]
    if path == "": continue
    print(f"Moving {path} to {destination}")

    last_index = path.rfind('/')
    outer_folder = path[0:last_index]

    def move_obj(obj_key):
        copy_source = {'Bucket': bucket_name, 'Key': obj_key}
        new_key = destination + obj_key[len(outer_folder):]
        s3.meta.client.copy(copy_source, bucket_name, new_key)
        s3.meta.client.delete_object(Bucket=bucket_name, Key=obj_key)

    bucket = s3.Bucket(bucket_name)
    object_keys = [obj.key for obj in bucket.objects.filter(Prefix=path)]
    with multiprocessing.Pool(multiprocessing.cpu_count()) as p:
        p.map(move_obj, object_keys)

    print(f"Moved {path}")

print(f"Done")

