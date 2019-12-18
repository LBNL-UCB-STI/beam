# coding=utf-8
import json
import boto3
import logging
import http.client
import os
from datetime import datetime, timezone

logger = logging.getLogger()
logger.setLevel(logging.INFO)

#Expected format: {"regions": ["us-east-1", "us-west-1"], "max_storage_size_in_gb": 5000}
#Must be triggered - using a CloudWatch rule currently
def lambda_handler(event, context):
    function_name = context.function_name
    regions = event.get('regions')
    max_storage_size_in_gb = int(event.get('max_storage_size_in_gb'))
    logger.info('Regions summing for EBS size: ' + str(regions))
    total_size_in_gb = 0
    for region in regions:
        region_ebs_size_in_gb = get_total_ebs_volume_size_for(region)
        total_size_in_gb = total_size_in_gb + region_ebs_size_in_gb
    logger.info('Total AWS volume size in GB is ' + str(total_size_in_gb))
    notify_if_volume_size_over_max_and_not_recently_triggered(total_size_in_gb, max_storage_size_in_gb, function_name, regions)
    return json.dumps({})

def get_total_ebs_volume_size_for(region):
    ec2 = boto3.client('ec2', region_name=region)
    active_volumes_response = ec2.describe_volumes(
        Filters = [
            {
                'Name': 'status',
                'Values': [
                    'available',
                    'creating',
                    'in-use'
                ]
            }
        ]
    )
    if len(safe_get(active_volumes_response, 'Volumes')) > 0:
        volumes = safe_get(active_volumes_response, 'Volumes')
        region_total_volume_size_in_gb = 0
        for volume in volumes:
            volume_size_in_gb = safe_get(volume, 'Size')
            region_total_volume_size_in_gb = region_total_volume_size_in_gb + volume_size_in_gb
        logger.info('Region \'' + region + '\' total volume size in GB is ' + str(region_total_volume_size_in_gb))
        return region_total_volume_size_in_gb
    else:
        logger.info('0 volumes found in ' + region)
        return 0

def notify_if_volume_size_over_max_and_not_recently_triggered(total_size_in_gb, max_storage_size_in_gb, current_function_name, regions):
    if(total_size_in_gb <= max_storage_size_in_gb):
        return
    interval_between_triggered_notification_in_days = int(os.environ['INTERVAL_BETWEEN_TRIGGERED_NOTIFICATION_IN_DAYS'])
    last_triggered_notification_datetime = datetime.strptime(os.environ['LAST_TRIGGERED_NOTIFICATION_DATETIME'], '%Y-%m-%d %H:%M:%S.%f')
    current_datetime = datetime.now()
    last_notification_timedelta = current_datetime - last_triggered_notification_datetime
    if last_notification_timedelta.days > interval_between_triggered_notification_in_days or (last_notification_timedelta.days != 0 and last_notification_timedelta.days % interval_between_triggered_notification_in_days == 0 and instance_launch_timedelta.seconds - run_frequency_in_seconds <= 0):
        logger.info('Notifying about oversize ebs volume = ' + str(total_size_in_gb))
        headers = {'Content-type': 'application/json'}
        payload = {
            "blocks": [
                {
                    "type": "section",
                    "text": {
                        "type": "mrkdwn",
                        "text": f"*Oversize EBS Notification*\n> *Maximum EBS Size Expected*\n> {max_storage_size_in_gb} GB\n> *Current Total EBS Size*\n> {total_size_in_gb} GB\n> *Regions checked*\n>{regions}"
                    }
                }
            ]
        }
        slack_hook = os.environ['SLACK_HOOK']
        logger.info('Slack notification for oversize EBS: ' + str(payload))
        conn = http.client.HTTPSConnection('hooks.slack.com')
        conn.request('POST', slack_hook, json.dumps(payload), headers)
        response = conn.getresponse()
        logger.info('Received response from slack notification for oversize EBS: ' + response.read().decode())
        update_notification_environment_with(current_datetime, current_function_name)

def update_notification_environment_with(notification_datetime, current_function_name):
    lm = boto3.client('lambda')
    logger.info('Updating environment to new notification datetime of \'' + str(notification_datetime) + '\'')
    env_var = lm.get_function_configuration(FunctionName=current_function_name)['Environment']['Variables']
    env_var.update({
        'LAST_TRIGGERED_NOTIFICATION_DATETIME':str(notification_datetime)
    })
    lm.update_function_configuration(
            FunctionName=current_function_name,
            Environment={
                'Variables': env_var
            }
        )
    logger.info(current_function_name + ' last notification datetime updated')

def safe_get(dict_obj, key):
    if dict_obj is not None:
        return dict_obj.get(key)
    return None