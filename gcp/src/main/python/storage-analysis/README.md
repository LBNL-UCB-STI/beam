## How to use these scripts

### Python version

These scripts are tested with python version 3.10

### Library installation

Install the libraries listed in the `requirements.txt` file.

### Access to API and Authentication
One need to enable the Google Cloud Storage API first as described in the [Quick Start Guide](https://cloud.google.com/python/docs/reference/storage/latest#quick-start).

For a local development environment, you can [set up ADC with your user credentials](https://cloud.google.com/docs/authentication/provide-credentials-adc#local-dev) by using the gcloud CLI. For production environments, you set up ADC by [attaching a service account](https://cloud.google.com/docs/authentication/provide-credentials-adc#attached-sa). More info about authentication is in [this document](https://cloud.google.com/docs/authentication/client-libraries).

### Get the root objects of the bucket

Execute [google_storage_prefixes.py](google_storage_prefixes.py) scipt. You can change the bucket name at the beginning of the script.

### Find all the beam and pilates output folders

Script [google_storage_find_output.py](google_storage_find_output.py) finds all the beam and pilates output folder uploaded to the bucket. It prints the data out in a TSV format.

```bash
nohup python google_storage_find_output.py >> beam-pilates.tsv &
```

### Get data about storage usage

Script [storage_data_reader.py](storage_data_reader.py) gathers information about different locations in `beam-core-outputs` bucket. It prints data out in TSV format.

### Find files to delete

Script [google_storage_find_to_delete.py](google_storage_find_to_delete.py) finds files that contains the defined regexes on entire bucket. It prints their names out. One may redirect them in a file for future deletion.

### Deleting files on a google bucket

Script [google_storage_delete_tsv.py](google_storage_delete_tsv.py) deletes all the files which paths are in a provided file.
```bash
python google_storage_delete_tsv.py --force to_be_deleted.tsv
```

If the argument `--force` is missed the script asks the user to confirm deletion.