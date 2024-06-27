from google.cloud import storage
import re


# It prints all the objects that contain any of the regex,

bucket_name = 'beam-core-outputs'
# What to delete
patterns = [
    '/ITERS/it\.\d+/\d+\.physSimEvents\.xml\.gz$',
    '/activitysim/pipeline\.h5$',
    '/ITERS/it\.\d+/\d+\.plans\.xml\.gz$',
    '/ITERS/it\.\d+/\d+\.expectedMaxUtilityHeatMap\.csv$',
]
expressions = [re.compile(x) for x in patterns]


storage_client = storage.Client()
blobs = storage_client.list_blobs(bucket_name, prefix='')
for blob in blobs:
    if any(r.search(blob.name) for r in expressions):
        print(f'{blob.name}\t{blob.time_created}\t{blob.size}')

