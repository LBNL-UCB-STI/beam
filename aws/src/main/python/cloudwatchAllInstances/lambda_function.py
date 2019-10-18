# coding=utf-8
import json
import boto3
import logging
import os
from datetime import datetime, timezone

logger = logging.getLogger()
logger.setLevel(logging.INFO)

class EC2Instance:
    def __init__(self, instance_id, monitoring_state, launch_datetime):
        self.instance_id = instance_id
        self.monitoring_state = monitoring_state
        self.launch_datetime = launch_datetime

    def __str__(self):
        return 'InstanceId: ' + self.instance_id + '; MonitoringState: ' + self.monitoring_state + '; LaunchTime: ' + str(self.launch_datetime)

#Expected format: {"regions": ["us-east-1", "us-west-1"], "run_frequency_in_minutes": 30}
#This requires pre-setup: SNS Topic named EC2-Idle-Notifier with a Subscription created pointing to lambda = idleNotifier
def lambda_handler(event, context):
    uptime_interval_to_trigger_notification_in_days = int(os.environ['UPTIME_INTERVAL_TO_TRIGGER_NOTIFICATION_IN_DAYS'])
    regions = event.get('regions')
    run_frequency_in_minutes = event.get('run_frequency_in_minutes')
    logger.info('Regions checking for alarm existence: ' + str(regions))
    excluded_instances = os.environ['EXCLUDED_INSTANCES']
    for region in regions:
        ec2_instances = get_running_instance_for(region)
        logger.info('Running instances returned for region \'' + region + '\' = ' + ' '.join([str(ec2_instance) for ec2_instance in ec2_instances]))
        for ec2_instance in ec2_instances:
            if ec2_instance.instance_id not in excluded_instances:
                add_watch_if_not_exists_using(ec2_instance, region)
                notify_if_instance_up_multiple_of(uptime_interval_to_trigger_notification_in_days, ec2_instance, region, run_frequency_in_minutes)
            else:
                logger.info('Skipping instance ' + str(ec2_instance) + ' as it is part of the excluded instances list')
    return json.dumps({})

def add_watch_if_not_exists_using(ec2_instance, region):
    cloudwatch = boto3.client('cloudwatch', region_name=region)
    if ec2_instance.monitoring_state == 'disabled':
        logger.info('Enabling monitoring for ' + str(ec2_instance) + ' since it is disabled')
        ec2 = boto3.client('ec2', region_name=region)
        ec2.monitor_instances(
            InstanceIds=[ec2_instance.instance_id],
            DryRun=False
        )
    alarms_response = cloudwatch.describe_alarms(AlarmNamePrefix=ec2_instance.instance_id)
    if(len(safe_get(alarms_response, 'MetricAlarms')) <= 0):
        create_cloudwatch_alarm_for(cloudwatch, ec2_instance.instance_id, region)

def notify_if_instance_up_multiple_of(time_interval_in_days, ec2_instance, region, run_frequency_in_minutes):
    aws_lambda = boto3.client('lambda', region_name=region)
    current_datetime = datetime.now(timezone.utc)
    instance_launch_datetime = ec2_instance.launch_datetime
    instance_launch_timedelta = current_datetime - instance_launch_datetime
    run_frequency_in_seconds = run_frequency_in_minutes * 60
    if instance_launch_timedelta.days != 0 and instance_launch_timedelta.days % time_interval_in_days == 0 and instance_launch_timedelta.seconds - run_frequency_in_seconds <= 0:
        logger.info('Notifying of long running instance = ' + str(ec2_instance))
        fake_alarm_name = ec2_instance.instance_id + '_' + region + '_' + "PSEUDOALARM"
        payload = {'Records':[{'Sns':{'Message':'{"AlarmName":"' + fake_alarm_name + '"}','Subject':f'Generated Notification: Instance \'{ec2_instance.instance_id}\' running since {str(instance_launch_datetime)}'}}]}
        aws_lambda.invoke(
            FunctionName='idleNotifier',
            InvocationType='Event',
            Payload=json.dumps(payload)
        )

def create_cloudwatch_alarm_for(cloudwatch, instance_id, region):
    logger.info('Creating cloudwatch alarm for instance id ' + instance_id)
    account_id=os.environ['AWS_ACCOUNT_ID']
    seconds_to_trigger_idle_notification = int(os.environ['SECONDS_TO_TRIGGER_IDLE_NOTIFICATION'])
    cloudwatch.put_metric_alarm(
        AlarmName=instance_id + '_' + region + '_Idle_CPU_Notification',
        AlarmDescription='Monitor whether an instance has been running for too long and is idle.',
        AlarmActions=['arn:aws:sns:'+region+':'+account_id+":EC2-Idle-Notifier"],
        MetricName='CPUUtilization',
        Namespace='AWS/EC2',
        Statistic='Average',
        Period=seconds_to_trigger_idle_notification,
        EvaluationPeriods=1,
        Threshold=1.0,
        ComparisonOperator='LessThanThreshold'
    )

def get_running_instance_for(region):
    ec2 = boto3.client('ec2', region_name=region)
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
            instance_list += [convert_to_ec2_instance_from(instance) for instance in instances]
        return instance_list
    else:
        logger.info('0 running instances found in ' + region)
        return []

def convert_to_ec2_instance_from(response_instance):
    logger.info('Convert to EC2Instance from ' + str(response_instance))
    return EC2Instance(safe_get(response_instance, 'InstanceId'), safe_get(safe_get(response_instance, 'Monitoring'),'State'), safe_get(response_instance, 'LaunchTime'))

def safe_get(dict_obj, key):
    if dict_obj is not None:
        return dict_obj.get(key)
    return None

def safe_index(list_obj, index):
    if list_obj is not None:
        if len(list_obj) >= index + 1:
            return list_obj[index]
    return None