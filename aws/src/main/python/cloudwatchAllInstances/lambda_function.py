# coding=utf-8
import json
import boto3
import logging

logger = logging.getLogger()
logger.setLevel(logging.INFO)

#TODO: Add logging
class EC2InstanceMeta(type):
    def __repr__(self):
        return 'InstanceId: ' + instance_id + '; MonitoringState: ' + monitoring_state

class EC2Instance:
    __metaclass__ = EC2InstanceMeta
    def __init__(self, instance_id, monitoring_state):
        self.instance_id = instance_id
        self.monitoring_state = monitoring_state

#Expected format: {"regions": ["us-east-1", "us-west-1"]}
#This requires pre-setup: SNS Topic named EC2-Idle-Notifier with a Subscription created pointing to lambda = idleNotifier
def lambda_handler(event, context):
    regions = get_regions_from(event)
    logger.info('Regions checking for alarm existence: ' + str(regions))
    for region in regions:
        ec2_instances = get_running_instance_for(region)
        logger.info('Running instances returned = ' + str(ec2_instances))
        for ec2_instance in ec2_instances:
            add_watch_if_not_exists_using(ec2_instance, region)
    return json.dumps('{}')

def get_regions_from(event):
    return event.get('regions')

def add_watch_if_not_exists_using(ec2_instance, region):
    logger.info('Checking if instance ' + str(ec2_instance) + ' has a cloudwatch alarm')
    cloudwatch = boto3.client('cloudwatch', region_name=region)
    alarms_response = cloudwatch.describe_alarms(AlarmNamePrefix=ec2_instance.instance_id)
    # len(alarms_response['MetricAlarms'])
    # check monitoring status - log if cannot or turn on...
    if(len(alarms_response['MetricAlarms']) <= 0):
        create_cloudwatch_alarm_for(cloudwatch, ec2_instance.instance_id, region)

def create_cloudwatch_alarm_for(cloudwatch, instance_id, region):
    logger.info('Creating cloudwatch alarm for instance id ' + instance_id)
    account_id='340032650202'
    cloudwatch.put_metric_alarm(
        AlarmName=instance_id + '_' + region + '_Idle_CPU_Notification',
        AlarmDescription='Monitor whether an instance has been running for too long and is idle.',
        AlarmActions=['arn:aws:sns:'+region+':'+account_id+":EC2-Idle-Notifier"],
        MetricName='CPUUtilization',
        Namespace='AWS/EC2',
        Statistic='Average',
        Period=300,
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
    #todo: instance.has_key('InstanceId') - make sure length is 1
    if(len(running_instances_response.get('Reservations')) > 0):
        logger.info('List found')
        reservations = running_instances_response.get('Reservations')
        first_reservation = reservations[0]
        instances = first_reservation.get('Instances')
        logger.info('Instances: ' + str(type(instances)))
        x =  [convert_to_ec2_instance_from(instance) for instance in instances]
        logger.info(str(x))
        return x
    else:
        logger.info('0 running instances found in ' + region)
        return []

def convert_to_ec2_instance_from(response_instance):
    logger.info('Convert to EC2Instance from ' + str(response_instance))
    return EC2Instance(response_instance['InstanceId'], response_instance['Monitoring']['State'])