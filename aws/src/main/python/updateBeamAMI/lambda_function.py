# coding=utf-8
import time
import json
import boto3
from botocore.errorfactory import ClientError

def lambda_handler(event, context):
    instance_id = event.get('instance_id')
    region_id = event.get('region_id', 'us-east-2')
    
    image_name = 'beam-automation-'+time.strftime("%Y-%m-%d-%H%M%S", time.gmtime())
    image_ids = {}
    
    image_ids['us-east-2'] = create_ami(image_name, instance_id)
    image_ids['us-east-1'] = copy_ami(image_name, image_ids['us-east-2'], 'us-east-1')
    image_ids['us-west-2'] = copy_ami(image_name, image_ids['us-east-2'], 'us-west-2')
    update_lambda(image_ids)
    
    return json.dumps(image_ids)


def create_ami(image_name, instance_id):
    ec2 = boto3.client('ec2',region_name='us-east-2')
    res = ec2.create_image(InstanceId=instance_id,
                            Name=image_name)
    wait4image(ec2, res['ImageId'])
    ec2.terminate_instances(InstanceIds=[instance_id]) 
    return res['ImageId']
    
def copy_ami(image_name, image_id, region):
    ec2 = boto3.client('ec2',region_name=region)
    res = ec2.copy_image(Name=image_name,
                    SourceImageId=image_id,
                    SourceRegion='us-east-2')
    # wait4image(ec2, res['ImageId'])
    return res['ImageId']
    
def wait4image(ec2, image_id):
    waiter = ec2.get_waiter('image_available')
    waiter.wait(Filters=[{'Name': 'state', 'Values': ['available']}],
                ImageIds=[image_id])
                
def update_lambda(image_ids):
    lm = boto3.client('lambda')
    en_var = lm.get_function_configuration(FunctionName='simulateBeam')['Environment']['Variables']
    en_var.update({
                    'us_east_2_IMAGE_ID': image_ids['us-east-2'],
                    'us_east_1_IMAGE_ID': image_ids['us-east-1'],
                    'us_west_2_IMAGE_ID': image_ids['us-west-2'],
                })
    lm.update_function_configuration(
            FunctionName='simulateBeam',
            Environment={
                'Variables': en_var
            }
        )

                
def check_instance_id(instance_ids):
    for reservation in ec2.describe_instances()['Reservations']:
        for instance in reservation['Instances']:
            if instance['InstanceId'] in instance_ids:
                instance_ids.remove(instance['InstanceId'])
    return instance_ids

def stop_instance(instance_ids):
    return ec2.stop_instances(InstanceIds=instance_ids)

def terminate_instance(instance_ids):
    return ec2.terminate_instances(InstanceIds=instance_ids)
