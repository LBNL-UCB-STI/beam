import os

from flask import escape
import functions_framework
from googleapiclient import discovery
from google.auth import jwt
import re
import time
import random
import string
import uuid
import json
from datetime import datetime
from datetime import timezone


def to_instance_name(run_name):
    no_spaces = re.sub(r'\s|_', '-', run_name.lower())
    clean = re.sub(r'[^a-z0-9\\-]', '', no_spaces)
    if not re.search(r'^[a-z]', clean):
        clean = 'name-' + clean
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
    user_email = get_user_email(request)
    if not user_email:
        return escape("Cannot extract user email from the auth token"), 403

    request_payload = request.get_json(silent=True)
    if not request_payload:
        return escape("No valid json payload provided"), 400

    run_jupyter = request_payload.get('run_jupyter', False)
    run_beam = request_payload.get('run_beam', True)

    log(f"run_beam: {run_beam}, run_jupyter: {run_jupyter}, request_json:{request_payload}")

    beam_config = request_payload.get('config', None)
    if parameter_is_not_specified(beam_config) and run_beam:
        return escape("No beam config provided"), 400

    instance_type = request_payload.get('instance_type', None)
    if parameter_is_not_specified(instance_type):
        return escape("No instance type provided"), 400
    instance_cores, instance_memory = find_instance_cores_and_memory(instance_type)
    if not instance_memory:
        return escape(f"Instance type '{instance_type}' is not supported"), 400

    max_ram = request_payload.get('forced_max_ram', None)
    if parameter_is_not_specified(max_ram):
        max_ram = calculate_heap_size(instance_cores, instance_memory)

    run_name = request_payload.get('run_name', "not-set")
    beam_branch = request_payload.get('beam_branch', "develop")
    beam_commit = request_payload.get('beam_commit', "HEAD")
    data_branch = request_payload.get('data_branch', "develop")
    data_commit = request_payload.get('data_commit', "HEAD")
    storage_publish = request_payload.get('storage_publish', "true")
    shutdown_wait = request_payload.get('shutdown_wait', "15")
    storage_size = request_payload.get('storage_size', "100")
    shutdown_behaviour = request_payload.get('shutdown_behaviour', "terminate")
    jupyter_token = request_payload.get('jupyter_token', '')
    jupyter_image = request_payload.get('jupyter_image', '')
    jabba_version = request_payload.get('jabba_version', '')

    project = 'beam-core'
    zone = 'us-central1-a'
    batch_uid = str(uuid.uuid4())[:8]
    instance_name = to_instance_name(run_name)
    machine_type = f"zones/{zone}/machineTypes/{instance_type.strip()}"

    disk_image_name = os.environ.get('DISK_IMAGE_NAME')
    if not disk_image_name:
        disk_image_name = f"projects/{project}/global/images/beam-box"
    log(f"disk image: {disk_image_name}")

    cloud_init_script_url = os.environ.get('CLOUD_INIT_SCRIPT_URL')
    if not cloud_init_script_url:
        cloud_init_script_url = "https://raw.githubusercontent.com/LBNL-UCB-STI/beam/develop/gcp/src/main/bash/cloud-init.sh"
    log(f"cloud_init_script_url: {cloud_init_script_url}")

    startup_script = """
#!/bin/sh
set -o xtrace
CLOUD_INIT_SCRIPT_URL=$(curl http://metadata/computeMetadata/v1/instance/attributes/cloud_init_script_url -H "Metadata-Flavor: Google")
sudo chown -R clu:clu /home/clu
sudo -u clu bash -c "cd; wget $CLOUD_INIT_SCRIPT_URL"
sudo -u clu bash -c "cd; chmod 755 cloud-init.sh"
REPLACE_FORJABBA
sudo -u clu bash -c "cd; ./cloud-init.sh &> cloud-init-output.log"
    """
    shutdown_script = """
#!/bin/bash
INSTANCE_NAME=$(curl http://metadata/computeMetadata/v1/instance/name -H "Metadata-Flavor: Google")
INSTANCE_ZONE=$(curl http://metadata/computeMetadata/v1/instance/zone -H "Metadata-Flavor: Google")
gcloud --quiet compute instances delete --zone="$INSTANCE_ZONE" "$INSTANCE_NAME"
    """

    if jabba_version != "":
        startup_script = startup_script.replace("REPLACE_FORJABBA", """sudo sed -i 's/#!\\/bin\\/bash/#!\\/bin\\/bash\\nset -o xtrace\\n\\/home\\/clu\\/.jabba\\/bin\\/jabba install {jabba}\\nexport JAVA_HOME="\\/home\\/clu\\/.jabba\\/jdk\\/{jabba}"\\nexport PATH="$JAVA_HOME\\/bin:$PATH"\\njava -version\\n/' /home/clu/cloud-init.sh""".format(jabba=jabba_version))
    else:
        startup_script = startup_script.replace("REPLACE_FORJABBA", "")

    metadata = [
        ('startup-script', startup_script),
        ('cloud_init_script_url', cloud_init_script_url),
        ('batch_uid', batch_uid),
        ('run_name', run_name),
        ('beam_config', beam_config),
        ('max_ram', max_ram),
        ('beam_branch', beam_branch),
        ('beam_commit', beam_commit),
        ('data_branch', data_branch),
        ('data_commit', data_commit),
        ('storage_publish', storage_publish),
        ('shutdown_wait', shutdown_wait),
        ('google_api_key', os.environ['GOOGLE_API_KEY']),
        ('slack_hook_with_token', os.environ['SLACK_HOOK_WITH_TOKEN']),
        ('slack_token', os.environ['SLACK_TOKEN']),
        ('slack_channel', os.environ['SLACK_CHANNEL']),
        ('user_email', user_email),
        ('run_beam', run_beam),
        ('run_jupyter', run_jupyter),
        ('jupyter_token', jupyter_token),
        ('jupyter_image', jupyter_image),
    ]

    if shutdown_behaviour.lower() == "terminate":
        metadata.append(('shutdown-script', shutdown_script))

    create_instance_request_body = create_instance_request(instance_name, machine_type, disk_image_name, storage_size,
                                                           metadata)

    service = discovery.build('compute', 'v1')
    result = service.instances() \
        .insert(project=project, zone=zone, body=create_instance_request_body) \
        .execute()

    log(result)

    instance_public_ip = ''

    def try_to_get_public_ip():
        instance_info_response = service.instances() \
            .get(project=project, zone=zone, instance=instance_name) \
            .execute()
        # expected 'accessConfigs[0]' to be something like that: "{'kind': 'compute#accessConfig',
        # 'type': 'ONE_TO_ONE_NAT', 'name': 'external-nat', 'natIP': '34.70.109.120', 'networkTier': 'PREMIUM'}" ,
        # but 'natIP' might be missing while instance is starting.
        access_configs = instance_info_response['networkInterfaces'][0]['accessConfigs'][0]
        return access_configs.get('natIP', '')

    attempts = 0
    while run_jupyter and not instance_public_ip and attempts < 10:
        attempts += 1
        time.sleep(1)
        instance_public_ip = try_to_get_public_ip()

    operation_id = result["id"]
    operation_status = result["status"]
    error = None
    if result.get("error", None):
        error_head = result["error"]["errors"][0]
        error = f"{error_head['code']}, {error_head['location']}, {error_head['message']}"

    if error:
        return escape(f"operation id: {operation_id}, status: {operation_status}, error: {error}"), 500
    else:
        response_text = f"Started instance {instance_name}"
        if run_beam:
            response_text += f", with run name: {run_name}"
        if run_beam or run_jupyter:
            response_text += f", for branch/commit {beam_branch}/{beam_commit}"
        if run_jupyter:
            jupyter_url = f"http://{instance_public_ip}:8888/lab?token={jupyter_token}"
            response_text += f", jupyter will be available at {jupyter_url} in few minutes"

        return escape(response_text + ".")


def log(msg, severity="NOTICE"):
    entry = dict(
        severity=severity,
        message=str(msg),
        component="deploy_beam_function"
    )
    print(json.dumps(entry))


def calculate_heap_size(instance_cores: int, instance_memory: float) -> int:
    max_remaining_memory = instance_cores # 1Gib per core
    percent_towards_remaining_memory = .25
    return round(instance_memory - min(instance_memory * percent_towards_remaining_memory, max_remaining_memory))

def find_instance_cores_and_memory(instance_type):
    instance_type_to_params = {
        'm1-megamem-96': (96, 1433.6),
        'm2-ultramem-208': (208, 5888),
        'm2-ultramem-416': (416, 11776),
        'm2-megamem-416': (416, 5888),
        'm2-hypermem-416': (416, 8832),
    }
    if instance_type in instance_type_to_params:
        return instance_type_to_params[instance_type]
    standard_multipliers = {"highcpu": 2, "standard": 4, "highmem": 8}
    family_to_multipliers = {
        "n2": standard_multipliers,
        "n2d": standard_multipliers,
        "c2": standard_multipliers,
        "c2d": standard_multipliers,
        "m3": {"megamem": 15.25, "ultramem": 30.5},
        "m1": {"ultramem": 24.025},
    }
    split = instance_type.split('-')
    if len(split) != 3:
        return None, None
    family, sub_type, num_cores_str = split
    num_cores = int(num_cores_str)
    multiplier = family_to_multipliers.get(family, {}).get(sub_type)
    return (num_cores, multiplier * num_cores) if multiplier else (num_cores, None)


def create_instance_request(instance_name, machine_type, disk_image_name, storage_size, metadata):
    return {
        'name': instance_name,
        'machineType': machine_type,
        'tags': {
            'items': ['http-server']
        },

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


def get_user_email(request):
    auth_header = request.headers.get("Authorization")
    if not auth_header:
        log("no Authorization header")
        return None
    token_start = auth_header.lower().find("bearer ")
    if token_start < 0:
        log(f"No bearer token")
        return None
    token = auth_header[token_start + 7:].strip()
    try:
        # decoding the token without verification; token should be verified before it gets to our function
        idinfo = jwt.decode(token, verify=False)
        return idinfo['email']
    except Exception as e:
        log(e)
        return None
