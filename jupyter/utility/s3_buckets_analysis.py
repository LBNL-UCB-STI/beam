#!/usr/bin/env python
# coding: utf-8

# In[34]:


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


# In[37]:


# Searches for beam folders on S3 bucket and saves path/last_modified/size to a csv file (local_files dir)
# using low level API to get only the first level of subfolders
bucket_name = 'beam-outputs'
search_prefix = 'output/beamville'

s3 = boto3.resource('s3', aws_access_key_id=aws_access_key_id,
                    aws_secret_access_key=aws_secret_access_key) if aws_access_key_id else boto3.resource('s3')
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

