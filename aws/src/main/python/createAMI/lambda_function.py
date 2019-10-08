# coding=utf-8
import time
import json
import boto3
import logging
from botocore.errorfactory import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def lambda_handler(event, context):
    instance_id = event.get('instance_id')
    terminate_instance = event.get('terminate_instance', "false")

    image_name = 'beam-automation-'+time.strftime("%Y-%m-%d-%H%M%S", time.gmtime())
    ami_id = create_ami(image_name, instance_id, terminate_instance)

    return ami_id + "|" + image_name


def create_ami(image_name, instance_id, terminate_instance):
    ec2 = boto3.client('ec2',region_name='us-east-2')
    logger.info('creating ami of instance' + instance_id)
    res = ec2.create_image(InstanceId=instance_id,
                           Name=image_name, NoReboot=True)
    logger.info('ami creation started for ' + res['ImageId'])
    if terminate_instance == "true":
        logger.info('shutting down instance' + instance_id)
        ec2.terminate_instances(InstanceIds=[instance_id])
        logger.info('shutting down complete for instance' + instance_id)
    return res['ImageId']