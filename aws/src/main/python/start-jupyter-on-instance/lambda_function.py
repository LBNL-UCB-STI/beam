# coding=utf-8
import boto3
import time
import logging
import os
from botocore.errorfactory import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

regions = ['us-east-1', 'us-east-2', 'us-west-2']

s3 = boto3.client('s3')
ec2 = None

initscript = (('''
#cloud-config
runcmd:
  - pip install setuptools
  - pip install jupyter
  - JUPYTER_TOKEN=$TOKEN jupyter-notebook --allow-root --no-browser --ip=$(ec2metadata --public-hostname)
  - sudo shutdown -h +30
'''))

instance_types = ['t2.nano', 't2.micro', 't2.small', 't2.medium', 't2.large', 't2.xlarge', 't2.2xlarge',
                  'm4.large', 'm4.xlarge', 'm4.2xlarge', 'm4.4xlarge', 'm4.10xlarge', 'm4.16xlarge',
                  'm5.large', 'm5.xlarge', 'm5.2xlarge', 'm5.4xlarge', 'm5.12xlarge', 'm5.24xlarge',
                  'c4.large', 'c4.xlarge', 'c4.2xlarge', 'c4.4xlarge', 'c4.8xlarge',
                  'f1.2xlarge', 'f1.16xlarge',
                  'g2.2xlarge', 'g2.8xlarge',
                  'g3.4xlarge', 'g3.8xlarge', 'g3.16xlarge',
                  'p2.xlarge', 'p2.8xlarge', 'p2.16xlarge',
                  'p3.2xlarge', 'p3.8xlarge', 'p3.16xlarge',
                  'r4.large', 'r4.xlarge', 'r4.2xlarge', 'r4.4xlarge', 'r4.8xlarge', 'r4.16xlarge',
                  'r3.large', 'r3.xlarge', 'r3.2xlarge', 'r3.4xlarge', 'r3.8xlarge',
                  'x1.16xlarge', 'x1.32xlarge',
                  'x1e.xlarge', 'x1e.2xlarge', 'x1e.4xlarge', 'x1e.8xlarge', 'x1e.16xlarge', 'x1e.32xlarge',
                  'd2.xlarge', 'd2.2xlarge', 'd2.4xlarge', 'd2.8xlarge',
                  'i2.xlarge', 'i2.2xlarge', 'i2.4xlarge', 'i2.8xlarge',
                  'h1.2xlarge', 'h1.4xlarge', 'h1.8xlarge', 'h1.16xlarge',
                  'i3.large', 'i3.xlarge', 'i3.2xlarge', 'i3.4xlarge', 'i3.8xlarge', 'i3.16xlarge', 'i3.metal',
                  'c5.large', 'c5.xlarge', 'c5.2xlarge', 'c5.4xlarge', 'c5.9xlarge', 'c5.18xlarge',
                  'c5d.large', 'c5d.xlarge', 'c5d.2xlarge', 'c5d.4xlarge', 'c5d.9xlarge', 'c5d.18xlarge', 'c5d.24xlarge',
                  'r5.large', 'r5.xlarge', 'r5.2xlarge', 'r5.4xlarge', 'r5.8xlarge', 'r5.12xlarge', 'r5.24xlarge',
                  'r5d.large', 'r5d.xlarge', 'r5d.2xlarge', 'r5d.4xlarge', 'r5d.12xlarge', 'r5d.24xlarge',
                  'm5d.large', 'm5d.xlarge', 'm5d.2xlarge', 'm5d.4xlarge', 'm5d.12xlarge', 'm5d.24xlarge',
                  'z1d.large', 'z1d.xlarge', 'z1d.2xlarge', 'z1d.3xlarge', 'z1d.6xlarge', 'z1d.12xlarge',
                  'x2gd.metal', 'x2gd.16xlarge']

def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2', region_name=region)

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

def deploy(script, instance_type, region_prefix, instance_name, volume_size, budget_override):
    res = ec2.run_instances(BlockDeviceMappings=[
        {
            'DeviceName': '/dev/sda1',
            'Ebs': {
                'VolumeSize': volume_size,
                'VolumeType': 'gp2'
            }
        }
    ],
        ImageId=os.environ[region_prefix + 'IMAGE_ID'],
        InstanceType=instance_type,
        UserData=script,
        KeyName=os.environ[region_prefix + 'KEY_NAME'],
        MinCount=1,
        MaxCount=1,
        SecurityGroupIds=[os.environ[region_prefix + 'SECURITY_GROUP']],
        IamInstanceProfile={'Name': os.environ['IAM_ROLE'] },
        InstanceInitiatedShutdownBehavior='terminate',
        TagSpecifications=[
            {
                'ResourceType': 'instance',
                'Tags': [
                    {
                        'Key': 'Name',
                        'Value': instance_name
                    }, {
                        'Key': 'BudgetOverride',
                        'Value': str(budget_override)
                    } ]
            } ])
    return res['Instances'][0]['InstanceId']

def deploy_handler(event, context):
    missing_parameters = []

    def parameter_wasnt_specified(parameter_value):
        # in gradle if parameter wasn't specified then project.findProperty return 'null'
        return parameter_value is None or parameter_value == 'null'

    def get_param(param_name):
        param_value = event.get(param_name)
        if parameter_wasnt_specified(param_value):
            missing_parameters.append(param_name)
        return param_value

    budget_override = event.get('budget_override', False)
    runName = get_param('title')
    instance_type = get_param('instance_type')
    region = get_param('region')
    volume_size = event.get('storage_size', 64)
    token = event.get('token')

    if missing_parameters:
        return "Unable to start, missing parameters: " + ", ".join(missing_parameters)

    if region not in regions:
        return "Unable to start run, {region} region not supported.".format(region=region)

    if instance_type not in instance_types:
        return "Unable to start run, {instance_type} instance type not supported.".format(instance_type=instance_type)

    if volume_size < 64 or volume_size > 256:
        volume_size = 64

    init_ec2(region)

    script = initscript.replace("$TOKEN", token)

    instance_id = deploy(script, instance_type, region.replace("-", "_")+'_', runName, volume_size, budget_override)
    host = get_dns(instance_id)
    txt = 'Started Jupyter with run name: {titled} (InstanceID: {instance_id}), url: http://{dns}:8888/?token={token}'.format(titled=runName, dns=host, instance_id=instance_id, token=token)

    return txt

def lambda_handler(event, context):
    logger.info("Incoming event: " + str(event))

    return deploy_handler(event, context)
