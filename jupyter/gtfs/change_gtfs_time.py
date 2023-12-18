#!/usr/bin/env python
# coding: utf-8

# In[ ]:


import os
import csv
import sys
import zlib
import shutil
import zipfile

import pandas as pd

from pathlib import Path


def delete_folder_with_content(folder_to_delete):
    # Try to remove the tree; if it fails, throw an error using try...except.
    try:
        shutil.rmtree(folder_to_delete)
    except OSError as e:
        print("Error: %s - %s." % (e.filename, e.strerror))
    print(f"Directory {folder_to_delete} deleted.")


def compress(in_file_names, in_files_path, out_zip_path):
    
    # Select the compression mode ZIP_DEFLATED for compression
    # or zipfile.ZIP_STORED to just store the file
    compression = zipfile.ZIP_DEFLATED

    # create the zip file first parameter path/name, second mode
    zf = zipfile.ZipFile(out_zip_path, mode="w")
    try:
        for file_name in in_file_names:
            # Add file to the zip file
            # first parameter file to zip, second filename in zip
            zf.write(in_files_path + '/' + file_name, file_name, compress_type=compression)

    except FileNotFoundError:
        print("An error occurred")
    finally:
        # Don't forget to close the file!
        zf.close()


# In[ ]:


# base variables

# suffix to be added to new archives after changing
archive_suffix_for_changed_gtfs = '.scaled_to_2017-09-18'

# the date used as base for changes, need to be picked with regards to date configured in BEAM
base_date = pd.to_datetime("2017-09-18", format='%Y-%m-%d')
# the timezone should be the same as the one configured in BEAM
base_timezone = 'Etc/GMT+7'


base_date, base_timezone


# In[ ]:


# looking for all gtfs archives in the specified folder

gtfs_zip_files_location = "../local_files/GTFS"
gtfs_archives = []

# traverse all files and pick zip archives for further processing
# excluding already changed archives
for (dir_name, child_folders, files) in os.walk(gtfs_zip_files_location):
    for file in files:
        if file.endswith('.zip') and archive_suffix_for_changed_gtfs not in file:
            gtfs_archive = f'{gtfs_zip_files_location}/{file}'
            gtfs_archives.append(gtfs_archive)

gtfs_archives


# In[ ]:


# unpacking found gtfs archives

for gtfs_archive in gtfs_archives:

    gtfs_unpacked = f"{gtfs_archive}.unpacked"

    # making sure there is an empty folder to unpack
    if os.path.isdir(gtfs_unpacked):
        delete_folder_with_content(gtfs_unpacked)
        
    Path(gtfs_unpacked).mkdir(parents=True, exist_ok=True)

    # unpack files
    with zipfile.ZipFile(gtfs_archive, 'r') as zip_ref:
        zip_ref.extractall(gtfs_unpacked)
    
    print(f"GTFS archive {gtfs_unpacked} created.")


# In[ ]:


# changing date in all previously found gtfs archives

for gtfs_archive in gtfs_archives:
    gtfs_unpacked = f"{gtfs_archive}.unpacked"

    # changing calendar dates
    path_to_file = f'{gtfs_unpacked}/calendar.txt'
    df = pd.read_csv(path_to_file, parse_dates=['start_date', 'end_date'])
    min_date = df['start_date'].min()
    df['start_date'] = df['start_date'] - min_date + base_date
    df['end_date'] = df['end_date'] - min_date + base_date
    df.to_csv(path_to_file, header=True, index=False, sep=',', quoting=csv.QUOTE_ALL, date_format='%Y%m%d')


    # changing calendar_dates dates
    path_to_file = f'{gtfs_unpacked}/calendar_dates.txt'
    df = pd.read_csv(path_to_file, parse_dates=['date'])
    df['date'] = df['date'] - min_date + base_date
    df.to_csv(path_to_file, header=True, index=False, sep=',', quoting=csv.QUOTE_ALL, date_format='%Y%m%d')


    # changing agency time zone
    path_to_file = f'{gtfs_unpacked}/agency.txt'
    df = pd.read_csv(path_to_file)
    df['agency_timezone'] = base_timezone
    df.to_csv(path_to_file, header=True, index=False, sep=',', quoting=csv.QUOTE_ALL, date_format='%Y%m%d')
    
    print(f"GTFS archive '{gtfs_archive}' processed")
    print()
    
print('Done')


# In[ ]:


# pack changed on previous step files into new GTFS archive

for gtfs_archive in gtfs_archives:
    gtfs_unpacked = f"{gtfs_archive}.unpacked"

    for (dir_name, child_folders, files) in os.walk(gtfs_unpacked):
        if 'agency.txt' in files:
            out_archive_path = gtfs_archive.split('.zip')[0] + f'{archive_suffix_for_changed_gtfs}.zip'
            if os.path.isfile(out_archive_path):
                print(f"Pre-existing '{out_archive_path}' deleted.")
                os.remove(out_archive_path)

            compress(files, gtfs_unpacked, out_archive_path)
            print(f"Archive '{out_archive_path}' created.")

            delete_folder_with_content(gtfs_unpacked)
            print()

print('Done')


# In[ ]:


# code to view files during intermediate steps, not required to be run
for gtfs_archive in gtfs_archives:

    gtfs_unpacked = f"{gtfs_archive}.unpacked"

    path_to_unpacked_archive = gtfs_unpacked

    for (dir_name, child_folders, files) in os.walk(path_to_unpacked_archive):
        if 'agency.txt' in files:
            for file in files:
                full_path = f'{path_to_unpacked_archive}/{file}'
                df = pd.read_csv(full_path)
                display(full_path, f'with size {len(df)}', df.head(2))


# In[ ]:





# In[ ]:




