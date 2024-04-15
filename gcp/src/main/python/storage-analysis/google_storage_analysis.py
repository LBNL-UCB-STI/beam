from google.cloud import storage
from typing import List
from dataclasses import dataclass
from datetime import datetime
from google.cloud.storage.blob import Blob

# It prints out folder info in tab separated values format. One can redirect the output to a tsv file and import
# that file to google spreadsheet

bucket_name = 'beam-core-outputs'
storage_client = storage.Client()


@dataclass
class StoragePrefix:
    path: str
    # all prefixes start with the root folder and end with /
    # austin-060223-flwcap-0.06-rectfd-config-uptd-beam-pilates-ver/activitysim/
    prefixes: List[str]
    blobs: List[Blob]


def get_storage_prefix(prefix):
    blobObject = storage_client.list_blobs(bucket_name, prefix=prefix, delimiter='/')
    blobs = list(blobObject)
    prefixes = list(blobObject.prefixes)
    return StoragePrefix(prefix, prefixes, blobs)


def get_time_created(prefix: StoragePrefix) -> datetime:
    if len(prefix.blobs) > 0:
        return prefix.blobs[0].time_created
    else:
        return get_time_created(get_storage_prefix(prefix.prefixes[0]))


def total_info(prefix: str):
    blobs = storage_client.list_blobs(bucket_name, prefix=prefix)
    blob_list = list(blobs)
    size = sum(blob.size for blob in blob_list)
    created = max(blob.time_created for blob in blob_list)
    return size, created


def get_all_files(prefix):
    blobs = storage_client.list_blobs(bucket_name, prefix=prefix)
    return [(blob.name, blob.size, blob.time_created) for blob in blobs]


def print_info_for_level(prefix):
    storage_prefix = get_storage_prefix(prefix)
    for subPrefix in storage_prefix.prefixes:
        size, created = total_info(subPrefix)
        print(f'{subPrefix}\t{size}\t{created}')

    for blob in storage_prefix.blobs:
        print(f'{blob.name}\t{blob.size}\t{blob.time_created}')


def print_info_for_all_files(prefixes):
    for prefix in prefixes:
        for path, size, created in get_all_files(prefix):
            print(f'{path}\t{size}\t{created}')
