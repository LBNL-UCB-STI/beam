import os

from flask import escape
import functions_framework
from googleapiclient import discovery
import re
import time
import random
import string
import uuid
from datetime import datetime
from datetime import timezone


def to_instance_name(run_name):
    no_spaces = re.sub(r'\s|_', '-', run_name.lower())
    clean = re.sub(r'[^a-z0-9\\-]', '', no_spaces)
    if not re.search(r'^[a-z]', clean): clean = 'name-' + clean
    date_time = datetime.fromtimestamp(time.time(), tz=timezone.utc)
    str_date_time = date_time.strftime("%Y-%m-%d-%H-%M-%S")
    rnd_str = ''.join(random.choices(string.ascii_lowercase, k=3))
    # name cannot exceed 63 chars
    clean = clean[:39]
    return clean + '-' + str_date_time + '-' + rnd_str


def parameter_is_not_specified(parameter_value):
    # in gradle if parameter wasn't specified then project.findProperty return 'null'
    return parameter_value is None or parameter_value == 'null'


@functions_framework.http
def create_beam_instance(request):
    json = request.get_json(silent=True)
    if not json: return escape("No valid json payload provided"), 400
    beam_config = json['config']
    if parameter_is_not_specified(beam_config): return escape("No beam config provided"), 400
    instance_type = json['instance_type']
    if parameter_is_not_specified(instance_type): return escape("No instance type provided"), 400
    max_ram = json['forced_max_ram']
    if parameter_is_not_specified(max_ram): max_ram = 32  # todo calculate max ram
    run_name = json.get('run_name', "not-set")
    beam_branch = json.get('beam_branch', "develop")
    beam_commit = json.get('beam_commit', "HEAD")
    data_branch = json.get('data_branch', "develop")
    data_commit = json.get('data_commit', "HEAD")
    shutdown_wait = json.get('shutdown_wait', "15")
    storage_size = json.get('storage_size', "100")
    shutdown_behaviour = json.get('shutdown_behaviour', "terminate")

    # project = requests.get("http://metadata/computeMetadata/v1/instance/id", headers={'Metadata-Flavor': 'Google'}).text
    project = 'beam-core'
    zone = 'us-central1-a'
    batch_uid = str(uuid.uuid4())[:8]
    name = to_instance_name(run_name)
    machine_type = f"zones/{zone}/machineTypes/{instance_type.strip()}"
    disk_image_name = f"projects/{project}/global/images/beam-box"
    startup_script = """
#!/bin/sh
sudo -u clu bash -c 'cd; wget https://gist.github.com/dimaopen/3e736f1ec1d49c7e162867b280736312/raw/cloud-init.sh'
sudo -u clu bash -c 'cd; chmod 755 cloud-init.sh'
sudo -u clu bash -c 'cd; ./cloud-init.sh &> cloud-init-output.log'
    """
    shutdown_script = """
#!/bin/bash
INSTANCE_NAME=$(curl http://metadata/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")
INSTANCE_ZONE=$(curl http://metadata/computeMetadata/v1/instance/zone -H "Metadata-Flavor: Google")
gcloud --quiet compute instances delete --zone="$INSTANCE_ZONE" "$INSTANCE_NAME"
    """

    metadata = [
        ('startup-script', startup_script),
        ('batch_uid', batch_uid),
        ('run_name', run_name),
        ('beam_config', beam_config),
        ('max_ram', max_ram),
        ('beam_branch', beam_branch),
        ('beam_commit', beam_commit),
        ('data_branch', data_branch),
        ('data_commit', data_commit),
        ('shutdown_wait', shutdown_wait),
        ('google_api_key', os.environ['GOOGLE_API_KEY']),
        ('slack_hook_with_token', os.environ['SLACK_HOOK_WITH_TOKEN']),
        ('slack_token', os.environ['SLACK_TOKEN']),
        ('slack_channel', os.environ['SLACK_CHANNEL']),
    ]
    if shutdown_behaviour.lower() == "terminate":
        metadata.append(('shutdown-script', shutdown_script))

    config = {
        'name': name,
        'machineType': machine_type,

        # Specify the boot disk and the image to use as a source.
        'disks': [
            {
                'boot': True,
                'autoDelete': True,
                'initializeParams': {
                    'sourceImage': disk_image_name,
                },
                # beam disk minimum size is 100 (Gb)
                "diskSizeGb": storage_size,
            }
        ],

        # Specify a network interface with NAT to access the public
        # internet.
        'networkInterfaces': [{
            'network': 'global/networks/default',
            "accessConfigs": [
                {
                    "name": "external-nat",
                    "type": "ONE_TO_ONE_NAT",
                    "kind": "compute#accessConfig",
                    "networkTier": "PREMIUM"
                }
            ]
        }],

        # Set beam-bot as the service account
        # permissions could be set via IAM roles assigned to this service account
        'serviceAccounts': [
            {
                'email': 'beam-bot@beam-core.iam.gserviceaccount.com',
                'scopes': [
                    'https://www.googleapis.com/auth/cloud-platform'
                ]
            }
        ],

        'metadata': {
            'items': [{'key': k, 'value': v} for k, v in metadata]
        }
    }

    service = discovery.build('compute', 'v1')
    result = service.instances() \
        .insert(project=project, zone=zone, body=config) \
        .execute()

    operation_id = result["id"]
    operation_status = result["status"]
    error = None
    if result.get("error", None):
        error_head = result["error"]["errors"][0]
        error = f"{error_head['code']}, {error_head['location']}, {error_head['message']}"

    if error:
        return escape(f"operation id: {operation_id}, status: {operation_status}, error: {error}")
    else:
        return escape(f"operation id: {operation_id}, status: {operation_status}")
