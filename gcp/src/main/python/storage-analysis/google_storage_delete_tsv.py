import csv
import datetime
import sys
from google.cloud import storage
from google.cloud.exceptions import NotFound

# It reads the first column of the provided tsv file and delete these paths from a bucket
# python google_storage_delete_tsv.py (--force) path/to/entries.tsv
# --force flag force deletion of the entries without asking user confirmation

bucket_name = 'beam-core-outputs'

tsv_file = sys.argv[1]
forceArg = ''
if len(sys.argv) > 2:
    forceArg = sys.argv[1]
    tsv_file = sys.argv[2]
else:
    tsv_file = sys.argv[1]
print(f'Reading {tsv_file}')

paths = []
with open(tsv_file) as tsvfile:
    tsvreader = csv.reader(tsvfile, delimiter="\t")
    for line in tsvreader:
        trim = line[0].strip()
        if trim:
            paths.append(trim)

print(f'Found {len(paths)} entries')
if forceArg != '--force':
    print(f'Type yes to delete them')
    userAnswer = sys.stdin.readline()[0:-1]
else:
    userAnswer = 'yes'
if userAnswer == 'yes':
    storage_client = storage.Client()
    bucket = storage_client.bucket(bucket_name)
    i = 0
    print(f'{datetime.datetime.now():%H:%M:%S} Starting')
    for path in paths:
        blob = bucket.blob(path)
        try:
            blob.delete()
        except NotFound:
            print(f'Not found {path}')
        i += 1
        if i % 100 == 0:
            print(f'{datetime.datetime.now():%H:%M:%S} Handled {i} entries')
    print(f'{datetime.datetime.now():%H:%M:%S} Deleted {i} entries')
else:
    print(f'Exiting')
