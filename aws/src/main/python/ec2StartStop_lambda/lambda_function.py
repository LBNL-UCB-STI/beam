# coding=utf-8
import boto3
import time
import os
from botocore.errorfactory import ClientError

instance_operations = ['start', 'stop']

ec2 = None

def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2',region_name=region)

def get_dns(instance_id):
    host = None
    while host is None:
        time.sleep(2)
        instances = ec2.describe_instances(InstanceIds=[instance_id])
        for r in instances['Reservations']:
            for i in r['Instances']:
                dns = i['PublicDnsName']
                if dns != '':
                    host = dns
    return host

def check_instance_id(instance_ids):
    for reservation in ec2.describe_instances()['Reservations']:
        for instance in reservation['Instances']:
            if instance['InstanceId'] in instance_ids:
                instance_ids.remove(instance['InstanceId'])
    return instance_ids

def start_instance(instance_ids):
    return ec2.start_instances(InstanceIds=instance_ids)

def stop_instance(instance_ids):
    return ec2.stop_instances(InstanceIds=instance_ids)

def instance_handler(event):
    region = event.get('region', os.environ['REGION'])
    instance_ids = event.get('instance_ids')
    command_id = event.get('command')
    system_instances = os.environ['SYSTEM_INSTANCES']

    init_ec2(region)

    system_instances = system_instances.split(',')
    instance_ids = instance_ids.split(',')
    invalid_ids = check_instance_id(list(instance_ids))
    valid_ids = [item for item in instance_ids if item not in invalid_ids]
    allowed_ids = [item for item in valid_ids if item not in system_instances]

    if command_id == 'start':
        start_instance(allowed_ids)
        return "Started instance(s) {insts}.".format(insts=', '.join([': '.join(inst) for inst in zip(allowed_ids, list(map(get_dns, allowed_ids)))]))

    if command_id == 'stop':
        stop_instance(allowed_ids)

    return "Instantiated {command} request for instance(s) [ {ids} ]".format(command=command_id, ids=",".join(allowed_ids))

def lambda_handler(event, context):
    command_id = event.get('command', 'stop') # start | stop | terminate | log

    if command_id in instance_operations:
        return instance_handler(event)

    return "Operation {command} not supported, please specify one of the supported operations (start | stop | terminate | log). ".format(command=command_id)
