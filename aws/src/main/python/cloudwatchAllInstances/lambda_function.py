# coding=utf-8
import json
import boto3
import logging
import os
import dateutil.relativedelta
from datetime import datetime, timezone

logger = logging.getLogger()
logger.setLevel(logging.INFO)

class EC2Instance:
    def __init__(self, instance_id, monitoring_state, launch_datetime, owner_email):
        self.instance_id = instance_id
        self.monitoring_state = monitoring_state
        self.launch_datetime = launch_datetime
        self.owner_email = owner_email
        
    def __str__(self):
        return 'InstanceId: ' + self.instance_id + '; MonitoringState: ' + self.monitoring_state + '; LaunchTime: ' + str(self.launch_datetime)+ '; OwnerEmail: ' + str(self.owner_email) 

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
        create_cloudwatch_alarm_for(cloudwatch, ec2_instance.instance_id, ec2_instance.owner_email, region)

def notify_if_instance_up_multiple_of(time_interval_in_days, ec2_instance, region, run_frequency_in_minutes):
    aws_lambda = boto3.client('lambda', region_name=region)
    current_datetime = datetime.now(timezone.utc)
    instance_launch_datetime = ec2_instance.launch_datetime
    instance_launch_timedelta = current_datetime - instance_launch_datetime
    run_frequency_in_seconds = run_frequency_in_minutes * 60
    if instance_launch_timedelta.days != 0 and instance_launch_timedelta.days % time_interval_in_days == 0 and instance_launch_timedelta.seconds - run_frequency_in_seconds <= 0:
        logger.info('Notifying of long running instance = ' + str(ec2_instance))
        fake_alarm_name = ec2_instance.instance_id + '_' + region + '_' + "PSEUDOALARM"
        payload = {'Records':[{'Sns':{'Message':'{"AlarmName":"' + fake_alarm_name + '"}', 'OwnerEmail': ec2_instance.owner_email, 'Subject':f'Generated Notification: Instance \'{ec2_instance.instance_id}\' running since {str(instance_launch_datetime)}'}}]}
        logger.info("Payload being sent to the idleNotifier is " + str(payload))
        aws_lambda.invoke(
            FunctionName='idleNotifier',
            InvocationType='Event',
            Payload=json.dumps(payload)
        )

def create_cloudwatch_alarm_for(cloudwatch, instance_id, owner_email, region):
    logger.info('Creating cloudwatch alarm for instance id ' + instance_id)
    account_id=os.environ['AWS_ACCOUNT_ID']

    #https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/AlarmThatSendsEmail.html#alarm-evaluation
    cloudwatch.put_metric_alarm(
        AlarmName=instance_id + '_' + region + '_Idle_CPU_Notification',
        AlarmDescription='Monitor whether an instance has been running for too long and is idle. DO NOT REMOVE - Meta Information - OwnerEmail:' + str(owner_email) + ';',
        AlarmActions=['arn:aws:sns:'+region+':'+account_id+":EC2-Idle-Notifier"],
        MetricName='CPUUtilization',
        Namespace='AWS/EC2',
        Statistic='Average',
        Period=60, #How often it samples the data
        EvaluationPeriods=60, #Sliding window of periods for consideration - Do not set below 2 as it is too sensitive - a single dip triggers the warning
        DatapointsToAlarm=30, #How many of the evaluation periods must meet the threshold to cause an alarm - The above 3 settings mean it will test every 60 seconds and go into alarm if the AvgCPU is less than 1 30 times over the past 60 periods....so 30 minutes of 60 were idle
        Threshold=1.0,
        ComparisonOperator='LessThanThreshold',
        Dimensions=[
        {
          'Name': 'InstanceId',
          'Value': instance_id
        },
    ]
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
            instance_list += [convert_to_ec2_instance_from(instance, region) for instance in instances]
        return instance_list
    else:
        logger.info('0 running instances found in ' + region)
        return []

def convert_to_ec2_instance_from(response_instance, region):
    logger.info('Convert to EC2Instance from ' + str(response_instance))
    email = None
    tags = response_instance.get('Tags')
    if tags:
        for tag in tags:
            if tag['Key'] == 'GitUserEmail':
                email = tag['Value']
    instance_id = safe_get(response_instance, 'InstanceId')
    #Email not found in tags, so now check for directly started instances
    username=None
    if email is None:
        cloudtrail = boto3.client('cloudtrail', region_name=region)
        today=datetime.now()
        responses = cloudtrail.lookup_events(LookupAttributes=[ { 'AttributeKey': 'ResourceName','AttributeValue': instance_id }],
            StartTime=today - dateutil.relativedelta.relativedelta(months=1),
            EndTime=today
        )
        for event in responses['Events']:
	        if event['EventName'] == 'RunInstances':
                 username=event['Username']
    
    if username != None:
        user_email_ids=os.environ['USER_EMAIL_MAPPER']
        user_ids_as_dict = json.loads(user_email_ids)
        email=safe_value_with_default(user_ids_as_dict, username, None)
    logger.info("Email is " + str(email) + " from username " + str(username) + "for instance with ID " + str(instance_id))
     
    return EC2Instance(instance_id, safe_get(safe_get(response_instance, 'Monitoring'),'State'), safe_get(response_instance, 'LaunchTime'), email)

def safe_get(dict_obj, key):
    if dict_obj is not None:
        return dict_obj.get(key)
    return None

def safe_index(list_obj, index):
    if list_obj is not None:
        if len(list_obj) >= index + 1:
            return list_obj[index]
    return None

def safe_value_with_default(list_obj, key, default):
    for item in list_obj:
        value = safe_get(item, key)
        if value != None:
            return value
    return default
