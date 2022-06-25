# coding=utf-8
import os
import logging
import re
import json
import boto3
import urllib.parse
import http.client
from datetime import datetime
from dateutil.relativedelta import relativedelta

logger = logging.getLogger()
logger.setLevel(logging.INFO)

class EC2Instance:
    def __init__(self, instance_id, launch_datetime, owner_email, username, is_budget_overrode):
        self.instance_id = instance_id
        self.launch_datetime = launch_datetime
        self.owner_email = owner_email
        self.username = username
        self.is_budget_overrode = is_budget_overrode

    def __str__(self):
        return 'InstanceId: ' + self.instance_id + '; LaunchTime: ' + str(self.launch_datetime) + '; OwnerEmail: ' + str(self.owner_email) + '; UserName: ' + str(self.username) + '; IsBudgetOverrode: ' + str(self.is_budget_overrode)

def shutdown_all_except_necessary_with(to_not_keep_filter, budget_threshold):
    regions = json.loads(os.environ['MONITORED_REGIONS'])
    excluded_instances = json.loads(os.environ['EXCLUDED_INSTANCES'])
    excluded_instances_filter = lambda instance_details: instance_details.instance_id not in excluded_instances
    if to_not_keep_filter:
        logger.info("Shutting down all instances except necessary and budgeted override")
    else:
        logger.info("Shutting down all instances except necessary")
    for region in regions:
        logger.info(f"Region {region}")
        ec2 = boto3.client('ec2', region_name=region)
        instances = get_running_instance_for(region, ec2)
        instances_to_stop = list(filter(excluded_instances_filter, filter(to_not_keep_filter, instances)))
        instance_ids_being_stopped = list(map(lambda instance_details: instance_details.instance_id, instances_to_stop))
        if len(instance_ids_being_stopped) > 0:
            notify_on_slack_using(instance_ids_being_stopped, budget_threshold, region)
            stop(instance_ids_being_stopped, ec2)
        else:
            logger.info(f"No instances to be stopped in region {region}")
    return

def stop(instance_ids_to_stop, ec2):
    logger.info(f"Stopping {instance_ids_to_stop}")
    ec2.stop_instances(InstanceIds=instance_ids_to_stop)

def notify_on_slack_using(instance_ids_being_stopped, budget_threshold_passed, region):
    slack_message = f"<!here> Budget threshold of {str(budget_threshold_passed)}% passed, so, in region {region}, stopping instance ID's: {instance_ids_being_stopped}"
    headers = {'Content-type': 'application/json'}
    payload = {
        "blocks": [
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": slack_message
                }
            }
        ]
    }
    slack_hook = os.environ['SLACK_HOOK']
    logger.info('Slack notification for budget alert: ' + str(payload))
    conn = http.client.HTTPSConnection('hooks.slack.com')
    conn.request('POST', slack_hook, json.dumps(payload), headers)
    response = conn.getresponse()
    logger.info('Received response from slack notification: ' + response.read().decode())
    return

def get_running_instance_for(region, ec2):
    running_instances_response = ec2.describe_instances(
        Filters=[
            {
                'Name': 'instance-state-name',
                'Values': [
                    'pending',
                    'running'
                ]
            }
        ]
    )
    if len(safe_get(running_instances_response, 'Reservations')) > 0:
        reservations = safe_get(running_instances_response, 'Reservations')
        instance_list = []
        for reservation in reservations:
            instances = safe_get(reservation, 'Instances')
            instance_list += [convert_to_ec2_instance_from(instance, region) for instance in instances]
        return instance_list
    else:
        logger.info('0 running instances found in ' + region)
        return []

def convert_to_ec2_instance_from(response_instance, region):
    # logger.info('Convert to EC2Instance from ' + str(response_instance))
    email = None
    is_budget_overrode = False
    tags = response_instance.get('Tags')
    if tags:
        for tag in tags:
            if tag['Key'] == 'GitUserEmail':
                email = tag['Value']
            elif tag['Key'] == 'BudgetOverride':
                is_budget_overrode = tag.get('Value').lower() == 'true'

    instance_id = safe_get(response_instance, 'InstanceId')
    #Email not found in tags, so now check for directly started instances
    username=None
    if email is None:
        cloudtrail = boto3.client('cloudtrail', region_name=region)
        today=datetime.now()
        responses = cloudtrail.lookup_events(LookupAttributes=[ { 'AttributeKey': 'ResourceName','AttributeValue': instance_id }],
            StartTime=today - relativedelta(months=1),
            EndTime=today
        )
        for event in responses['Events']:
	        if event['EventName'] == 'RunInstances':
                 username=event['Username']

    logger.info("Email is " + str(email) + " and username is " + str(username) + " for instance with ID " + str(instance_id))
    return EC2Instance(instance_id, safe_get(response_instance, 'LaunchTime'), email, username, is_budget_overrode)

def convert_to_float_from(budget_string):
    number_extractor = "\$(.*)"
    extracted_number_array = re.findall(number_extractor, budget_string)
    cleaned_float_string = extracted_number_array[0].replace(',', '')
    return float(cleaned_float_string)

def lambda_handler(event, context):
    logger.info(f"Received event: {event}")
    budget_message = json.loads(str(event.get('Records')[0]).replace("'",'"')).get('Sns').get('Message')
    budget_name = re.findall(".*Budget Name: (.*)", budget_message)[0]
    budget_limit_as_str = re.findall("Budgeted Amount: (.*)", budget_message)[0]
    budget_threshold_as_str = re.findall("Alert Threshold: (.*)", budget_message)[0]
    budget_actual_as_str = re.findall("ACTUAL Amount: (.*)", budget_message)[0]

    budget_limit = convert_to_float_from(budget_limit_as_str)
    budget_threshold = convert_to_float_from(budget_threshold_as_str)
    budget_actual = convert_to_float_from(budget_actual_as_str)
    budget_triggered_percentage = int((budget_threshold / budget_limit) * 100)

    if budget_triggered_percentage == 150:
        budget_overrode_removal_filter = lambda instance_details: instance_details.is_budget_overrode != True
        shutdown_all_except_necessary_with(budget_overrode_removal_filter, "150")
    elif budget_triggered_percentage == 300:
        shutdown_all_except_necessary_with(None, "300")

    return 'Done'

def safe_get(dict_obj, key):
    if dict_obj is not None:
        return dict_obj.get(key)
    return None