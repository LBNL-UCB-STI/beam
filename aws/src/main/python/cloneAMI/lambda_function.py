# coding=utf-8
import time
import json
import boto3
import logging
from botocore.errorfactory import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def lambda_handler(event, context):
    ami_id = event.get('ami_id')
    image_name = event.get('ami_name')
    region = event.get('region')

    return copy_ami(image_name, ami_id, region)

def copy_ami(image_name, image_id, region):
    ec2 = boto3.client('ec2',region_name=region)
    res = ec2.copy_image(Name=image_name,
                         SourceImageId=image_id,
                         SourceRegion='us-east-2')
    logger.info('ami [' + image_id + '] coppied to region ' + region + ' with id ' + res['ImageId'])
    return res['ImageId']
