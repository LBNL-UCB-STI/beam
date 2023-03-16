import base64
import json
import os
import http.client
from googleapiclient import discovery

project = 'beam-core'
zone = 'us-central1-a'


def log(msg, severity="NOTICE"):
    entry = dict(
        severity=severity,
        message=str(msg),
        component="deploy_beam_function"
    )
    print(json.dumps(entry))


def message_handler(event, context):
    """Triggered from a message on a Cloud Pub/Sub topic.
    Args:
        event (dict): Event payload.
        context (google.cloud.functions.Context): Metadata for the event.
    """
    json_str = base64.b64decode(event['data']).decode('utf-8')
    pubsub_message = json.loads(json_str)

    incident_summary = get_json_reference('incident.summary', pubsub_message, 'NA')
    log(incident_summary)
    incident_link = get_json_reference('incident.url', pubsub_message, 'NA')
    instance_name = get_json_reference('incident.metric.labels.instance_name', pubsub_message, 'unknown')
    instance_link = f"https://console.cloud.google.com/compute/instancesDetail/zones/{zone}/instances/{instance_name}" \
                    f"?project={project}"
    metadata = get_instance_metadata(instance_name)
    email = get_custom_metadata_value(metadata, "user_email")
    log(f"Instance user email {email}")
    email_escaped = email.replace("@", "AT").replace(".", "_") if email else None
    run_name = get_custom_metadata_value(metadata, "run_name")
    if run_name is None: run_name = "No run"
    user_slacks_ids = os.environ['USER_SLACK_IDS']
    slack_ids_array = json.loads(user_slacks_ids)
    channel_id = safe_value_with_default(slack_ids_array, email_escaped, 'here')
    log('channel_id = ' + channel_id)
    if channel_id == "here":
        channel_id = "!here"
    else:
        channel_id = "@" + channel_id
    payload = {
        "blocks": [
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": f"*<{channel_id}> GCE Idle Incident *\n"
                            f"> {incident_summary}\n"
                            f"> *Run Name*\n"
                            f"> {run_name}\n"
                            f"> *Incident <{incident_link}|(Link)>*\n"
                            f"> *Instance <{instance_link}|(Link)>*\n"
                            f"> {instance_name}\n"
                }
            }
        ]
    }

    slack_hook = os.environ['SLACK_HOOK']
    log('Sending slack notification about idle instance with payload: ' + str(payload))
    conn = http.client.HTTPSConnection('hooks.slack.com')
    conn.request('POST', url=slack_hook, body=json.dumps(payload), headers={'Content-type': 'application/json'})
    response = conn.getresponse()
    log('Received response from slack notification about idle instance with response: ' + response.read().decode())

    return json.dumps({})


def get_custom_metadata_value(metadata, key):
    try:
        items = next(x[1] for x in metadata.items() if x[0] == 'items')
        value = next(x['value'] for x in items if x['key'] == key)
    except StopIteration:
        value = None
    return value


def get_json_reference(ref: str, json_document, default=None):
    for i in ref.split("."):
        if i in json_document:
            json_document = json_document[i]
        else:
            return default
    return default if json_document is None else json_document


def get_instance_metadata(instance_name):
    service = discovery.build('compute', 'v1')
    instance_data = service.instances() \
        .get(project=project, zone=zone, instance=instance_name) \
        .execute()
    return instance_data["metadata"]


def safe_get(dict_obj, key):
    if dict_obj is not None:
        return dict_obj.get(key)
    return None


def safe_value_with_default(list_obj, key, default):
    for item in list_obj:
        value = safe_get(item, key)
        if value is not None:
            return value
    return default
