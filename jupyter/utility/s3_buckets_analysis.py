#!/usr/bin/env python
# coding: utf-8

# In[5]:


get_ipython().run_line_magic('pip', 'install boto3')
import boto3
import itertools
import pandas as pd

class StopExecution(Exception):
    def _render_traceback_(self):
        pass

aws_access_key_id = ""
aws_secret_access_key = ""


# In[13]:


bucket_name = 'beam-outputs'
search_path = 'output/beamville/beamville'

s3 = boto3.resource('s3', aws_access_key_id=aws_access_key_id, aws_secret_access_key=aws_secret_access_key)
# s3 = boto3.resource('s3')
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
        if (test_path in beam_folders_split):
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
file_name=search_path.split('/')[-1]
df.to_csv(f'/home/jovyan/local_files/{file_name}.csv', index=False)
df

