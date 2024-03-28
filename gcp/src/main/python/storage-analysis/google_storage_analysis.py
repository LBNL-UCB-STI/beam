from google.cloud import storage
import itertools
from dataclasses import dataclass

# It prints out folder info in tab separated values format. One can redirect the output to a tsv file and import
# that file to google spreadsheet

bucket_name = 'beam-core-outputs'
storage_client = storage.Client()


@dataclass
class StoragePrefix:
    path: str
    prefixes: []
    blobs: []


def get_storage_prefix(prefix):
    blobObject = storage_client.list_blobs(bucket_name, prefix=prefix, delimiter='/')
    blobs = list(blobObject)
    prefixes = list(blobObject.prefixes)
    return StoragePrefix(prefix, prefixes, blobs)


def total_info(prefix):
    blobs = storage_client.list_blobs(bucket_name, prefix=prefix)
    blob_list = list(blobs)
    size = sum(blob.size for blob in blob_list)
    created = max(blob.time_created for blob in blob_list)
    return size, created


def get_all_files(prefix):
    blobs = storage_client.list_blobs(bucket_name, prefix=prefix)
    return [(blob.name, blob.size, blob.time_created) for blob in blobs]


# It uses different approaches of getting enries for prefixes mentioned in the arrays below
second_level = ['/', 'beam-core-outputs/', 'pilates-outputs/', 'wheelchair-feb2023/']
third_level = ['output/', 'wheelchair/']
all_files = ['ActivitySimData/', 'beam-core-outputs/', 'debug/', 'nyc/', 'temp/', 'urbansim/']
this_level = ['input/pilates/', 's3://glacier-beam-outputs/pilates-outputs/']

all_different = list(itertools.chain(*[second_level, third_level, all_files, this_level]))


def print_info_for_prefixes(prefixes, skip=False):
    for prefix in prefixes:
        if skip & any(prefix.startswith(item) for item in all_different):
            continue
        size, created = total_info(prefix)
        print(f'{prefix}\t{size}\t{created}')


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


root_prefix = get_storage_prefix(prefix='')
print_info_for_prefixes(root_prefix.prefixes, skip=True)

second_level_prefixes = [prefix for x in second_level for prefix in get_storage_prefix(x).prefixes]
print_info_for_prefixes(second_level_prefixes)
third_level_prefixes = [prefix
                        for x in third_level
                        for y in get_storage_prefix(x).prefixes
                        for prefix in get_storage_prefix(y).prefixes]
print_info_for_prefixes(third_level_prefixes)
print_info_for_all_files(all_files)
for level in this_level:
    print_info_for_level(level)
