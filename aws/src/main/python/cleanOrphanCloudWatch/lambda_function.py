# coding=utf-8
import json
import boto3
import logging
import re

logger = logging.getLogger()
logger.setLevel(logging.INFO)

class CloudWatchAlarm:
    def __init__(self, alarm_name):
        self.alarm_name = alarm_name

    def __str__(self):
        return 'alarm_name: ' + self.alarm_name + '; instanceid: ' + str(self.instanceid())

    def instanceid(self):
        instance_regex_extractor = '(.*?)_.*'
        matches = re.findall(instance_regex_extractor, self.alarm_name)
        return safe_index(matches, 0)

#Expected format: {"regions": ["us-east-1", "us-west-1"]}
def lambda_handler(event, context):
    regions = event.get('regions')
    logger.info('Regions checking for alarms: ' + str(regions))
    for region in regions:
        cloudwatch_alarms = get_cloudwatch_alarms_for(region)
        logger.info('Cloudwatch alarms returned for region \'' + region + '\' = ' + ' '.join([str(cloudwatch_alarm) for cloudwatch_alarm in cloudwatch_alarms]))
        alarms_to_be_removed = []
        for cloudwatch_alarm in cloudwatch_alarms:
            if(is_no_active_instance_associated_with(cloudwatch_alarm, region)):
                alarms_to_be_removed.append(cloudwatch_alarm)
        delete_alarms(alarms_to_be_removed, region)
    return json.dumps('{}')

def get_cloudwatch_alarms_for(region):
    cloudwatch = boto3.client('cloudwatch', region_name=region)
    alarms_response = cloudwatch.describe_alarms()
    metric_alarms = safe_get(alarms_response, 'MetricAlarms')
    if(len(metric_alarms) > 0):
        return [convert_to_cloudwatch_alarm_from(metric_alarm) for metric_alarm in metric_alarms]
    else:
        return []

def is_no_active_instance_associated_with(cloudwatch_alarm, region):
    if cloudwatch_alarm.instanceid() is None:
        logger.info('Skipping probable non-generated alarm: ' + str(cloudwatch_alarm))
        return False # Assume that if an instanceid could not be gleaned then it was not auto-generated and should be manually handled
    ec2 = boto3.client('ec2', region_name=region)
    running_instance_response = ec2.describe_instances(
        Filters=[
            {
                'Name': 'instance-state-name',
                'Values': [
                    'pending',
                    'running'
                ]
            },
            {
                'Name': 'instance-id',
                'Values': [
                    cloudwatch_alarm.instanceid()
                ]
            }
        ]
    )
    if(len(safe_get_with_default(running_instance_response, 'Reservations', [])) > 0):
        logger.info('Still an active instance associated with ' + str(cloudwatch_alarm) + '. Response when checking for running instances: ' + str(running_instance_response))
        return False
    else:
        logger.info(' No active instance associated with ' + str(cloudwatch_alarm) + '. Response when checking for running instances: ' + str(running_instance_response))
        return True

def delete_alarms(alarms_to_be_removed, region):
    logger.info('Alarms to be deleted for region \'' + region + '\' = ' + ' '.join([str(cloudwatch_alarm) for cloudwatch_alarm in alarms_to_be_removed]))
    cloudwatch = boto3.client('cloudwatch', region_name=region)
    alarm_names = [alarm.alarm_name for alarm in alarms_to_be_removed]
    cloudwatch.delete_alarms(
        AlarmNames = alarm_names
    )

def convert_to_cloudwatch_alarm_from(response_instance):
    logger.info('Convert to CloudWatchAlarm from ' + str(response_instance))
    return CloudWatchAlarm(safe_get(response_instance, 'AlarmName'))

def safe_get(dict_obj, key):
    if dict_obj is not None:
        return dict_obj.get(key)
    return None

def safe_get_with_default(dict_obj, key, default):
    value = safe_get(dict_obj, key)
    if value is None:
        return default
    return value

def safe_index(list_obj, index):
    if list_obj is not None:
        if len(list_obj) >= index + 1:
            return list_obj[index]
    return None