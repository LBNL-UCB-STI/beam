# coding=utf-8
import boto3
import json
import os
import logging
import http.client

ec2_client = None

logger = logging.getLogger()
logger.setLevel(logging.INFO)

class EC2Instance:
    def __init__(self, instance_id, instance_type, instance_name, instance_region, instance_git_user_email):
        self.instance_id = instance_id
        self.instance_type = instance_type
        self.instance_name = instance_name
        self.instance_region = instance_region
        self.instance_git_user_email = instance_git_user_email
    
    def __str__(self):
        return 'InstanceId: ' + self.instance_id + '; Instance Type: ' + self.instance_type + '; Instance Name: ' + self.instance_name + '; Instance Region: ' + self.instance_region + '; Instance Git User Email: ' + self.instance_git_user_email

def init_ec2_client(region):
    global ec2_client
    ec2_client = boto3.client('ec2',region_name=region)
    
def get_instance_details_for_(instance_id, instance_region):
    instance_details = ec2_client.describe_instances(InstanceIds=[instance_id])
    instance = instance_details.get("Reservations")[0].get("Instances")[0]
    instance_type = instance.get("InstanceType")
    instance_git_user_email = ""
    instance_name = ""
    tags = safe_get_with_default(instance, "Tags", [])
    for tag in tags:
        key = tag.get("Key")
        if key == "GitUserEmail":
            instance_git_user_email = tag.get("Value")
        elif key == "Name":
            instance_name = tag.get("Value")
    return EC2Instance(instance_id, instance_type, instance_name, instance_region, instance_git_user_email)
    
def terminate_instance(instance_id, instance_region):
    logger.info(f"Terminating unexpected instance '{instance_id}' in region '{instance_region}'")
    ec2_client.terminate_instances(InstanceIds=[instance_id])
    
def notify_slack_using(instance_details):
    logger.info('Notifying about attempted instance use')
    instance_link = f"https://console.aws.amazon.com/ec2/home?region={instance_details.instance_region}#Instances:instanceId={instance_details.instance_id}"
    headers = {'Content-type': 'application/json'}
    payload = {
        "blocks": [
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": f"*Unexpected Instance Creation Was Immediately Terminated*\n> *Instance ID*\n> {instance_details.instance_id}\n> *Instance Region*\n> {instance_details.instance_region}\n> *Instance Name:*\n>{instance_details.instance_name}\n> *Instance Type:*\n>{instance_details.instance_type}\n> *Git email of who created the instance:*\n>{instance_details.instance_git_user_email}\n> *Instance Link:*\n>{instance_link}"
                }
            }
        ]
    }
    slack_hook = os.environ['SLACK_HOOK']
    logger.info('Slack notification for terminated instance: ' + str(payload))
    conn = http.client.HTTPSConnection('hooks.slack.com')
    conn.request('POST', slack_hook, json.dumps(payload), headers)
    response = conn.getresponse()
    logger.info('Received response from slack notification for attempted instance use: ' + response.read().decode())
    
# Minimum expected format: {"instance-id": <instance-id>, "region": <region>, "state": <state>}
def lambda_handler(event, context):
    region = event.get('region')
    instance_id = event.get('instance-id')
    state = event.get('state')
    
    init_ec2_client(region)
    instance_details = get_instance_details_for_(instance_id, region)
    terminate_instance(instance_id, region)
    if state == 'terminated':
        notify_slack_using(instance_details)

    return "Done"

def safe_get_with_default(dict_obj, key, default):
    if dict_obj is not None:
        got_value = dict_obj.get(key)
        if got_value:
            return got_value
        else: 
            return default
    return default