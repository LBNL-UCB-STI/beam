# coding=utf-8
import json
import boto3
import logging
import re

logger = logging.getLogger()
logger.setLevel(logging.INFO)

#TODO: Add logging
class CloudWatchAlarmMeta(type):
    def __repr__(self):
        return 'alarm_name: ' + alarm_name

class CloudWatchAlarm:
    __metaclass__ = CloudWatchAlarmMeta
    def __init__(self, alarm_name):
        self.alarm_name = alarm_name

    def instanceid(self):
        instance_regex_extractor = '(.*?)_.*'
        matches = re.findall(instance_regex_extractor, self.alarm_name)
        if(len(matches) > 0):
            return matches[0][0]
        else:
            return None

#Expected format: {"regions": ["us-east-1", "us-west-1"]}
def lambda_handler(event, context):
    regions = get_regions_from(event)
    logger.info('Regions checking for alarms: ' + str(regions))
    for region in regions:
        cloudwatch_alarms = get_cloudwatch_alarms_for(region)
        logger.info('Cloudwatch alarms returned = ' + str([cloudwatch_alarm.alarm_name for cloudwatch_alarm in cloudwatch_alarms]))
        alarms_to_be_removed = []
        for cloudwatch_alarm in cloudwatch_alarms:
            if(is_no_active_instance_associated_with(cloudwatch_alarm, region)):
                alarms_to_be_removed.append(cloudwatch_alarm)
        logger.info('Alarms to be deleted:' + str([cloudwatch_alarm.alarm_name for cloudwatch_alarm in alarms_to_be_removed]))
        delete_alarms(alarms_to_be_removed, region)
    return json.dumps('{}')

def get_regions_from(event):
    return event.get('regions')

def get_cloudwatch_alarms_for(region):
    cloudwatch = boto3.client('cloudwatch', region_name=region)
    alarms_response = cloudwatch.describe_alarms()
    metric_alarms = alarms_response.get('MetricAlarms')
    if(len(metric_alarms) > 0):
        return [convert_to_cloudwatch_alarm_from(metric_alarm) for metric_alarm in metric_alarms]
    else:
        return []

def is_no_active_instance_associated_with(cloudwatch_alarm, region):
    if(cloudwatch_alarm.instanceid() is None):
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
    if(len(running_instance_response.get('Reservations')) > 0):
        return False
    else:
        return True

def delete_alarms(alarms_to_be_removed, region):
    cloudwatch = boto3.client('cloudwatch', region_name=region)
    alarm_names = [alarm.alarm_name for alarm in alarms_to_be_removed]
    cloudwatch.delete_alarms(
        AlarmNames = alarm_names
    )

def convert_to_cloudwatch_alarm_from(response_instance):
    logger.info('Convert to CloudWatchAlarm from ' + str(response_instance))
    return CloudWatchAlarm(response_instance.get('AlarmName'))