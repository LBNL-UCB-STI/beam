# coding=utf-8
import os
import os.path
import sys
from datetime import datetime

envLambdaTaskRoot = os.environ["LAMBDA_TASK_ROOT"]
print("LAMBDA_TASK_ROOT env var:" + os.environ["LAMBDA_TASK_ROOT"])
print("sys.path:" + str(sys.path))

sys.path.insert(0, envLambdaTaskRoot + "/package")
print("sys.path:" + str(sys.path))

import time
import json
import boto3

instance_operations = ['start', 'stop']
node_images = {'us-west-2': 'ami-0c28139856aaf9c3b',
               'us-east-1': 'ami-0eeeef929db40543c',
               'us-east-2': 'ami-0484545fe7d3da96f'
               }

eks = None
cf = None


def init_eks(region):
    global eks
    eks = boto3.client('eks', region_name=region)


def init_cloudformation(region):
    global cf
    cf = boto3.client('cloudformation', region_name=region)


def create_eks_stack(time_token, tag):
    response = cf.create_stack(
        StackName="beam-eks-{dt}".format(dt=time_token),
        TemplateURL='https://amazon-eks.s3-us-west-2.amazonaws.com/cloudformation/2019-02-11/amazon-eks-vpc-sample.yaml',
        RollbackConfiguration={
            'MonitoringTimeInMinutes': 0
        },
        RoleARN='arn:aws:iam::340032650202:role/BeamEksDeployment',
        TimeoutInMinutes=10,
        Capabilities=[
            'CAPABILITY_IAM',
        ],
        OnFailure='ROLLBACK',
        EnableTerminationProtection=False,
        Tags=[
            {
                'Key': 'creation',
                'Value': tag
            },
        ]
    )
    return response['StackId']


def create_workers_stack(time_token, ec2_instance, outputs, region, cluster_name, tag):
    response = cf.create_stack(
        StackName="beam-eks-workers-{dt}".format(dt=time_token),
        TemplateURL='https://amazon-eks.s3-us-west-2.amazonaws.com/cloudformation/2019-02-11/amazon-eks-nodegroup.yaml',
        Parameters=[
            {
                'ParameterKey': 'ClusterName',
                'ParameterValue': cluster_name
            },
            {
                'ParameterKey': 'ClusterControlPlaneSecurityGroup',
                'ParameterValue': outputs['SecurityGroups']
            },
            {
                'ParameterKey': 'NodeGroupName',
                'ParameterValue': "beam-eks-nodegroup-{t}".format(t=time_token)
            },
            {
                'ParameterKey': 'NodeInstanceType',
                'ParameterValue': ec2_instance
            },
            {
                'ParameterKey': 'NodeImageId',
                'ParameterValue': node_images[region]
            },
            {
                'ParameterKey': 'KeyName',
                'ParameterValue': 'scraper-key'
            },
            {
                'ParameterKey': 'VpcId',
                'ParameterValue': outputs['VpcId']
            },
            {
                'ParameterKey': 'Subnets',
                'ParameterValue': outputs['SubnetIds']
            }
        ],
        RollbackConfiguration={
            'MonitoringTimeInMinutes': 0
        },
        RoleARN='arn:aws:iam::340032650202:role/BeamEksDeployment',
        TimeoutInMinutes=10,
        Capabilities=[
            'CAPABILITY_IAM',
        ],
        OnFailure='ROLLBACK',
        EnableTerminationProtection=False,
        Tags=[
            {
                'Key': 'creation',
                'Value': tag
            },
        ]
    )
    return response['StackId']


def get_stack_outputs(stackName):
    status = None
    while status != 'CREATE_COMPLETE':
        time.sleep(10)
        stack_description = cf.describe_stacks(StackName=stackName)
        status = stack_description['Stacks'][0]['StackStatus']
    outputs = {}
    outputs_list = stack_description['Stacks'][0]['Outputs']
    for out in outputs_list:
        outputs[out['OutputKey']] = out['OutputValue']
    return outputs


def wait_cluster_outputs(cluster_name):
    status = None
    while status != 'ACTIVE':
        time.sleep(10)
        cluster_description = eks.describe_cluster(name=cluster_name)
        status = cluster_description['cluster']['status']


def create_cluster(outputs, time_token):
    cluster_details = eks.create_cluster(
        name="beam-cluster-{t}".format(t=time_token),
        version='1.11',
        roleArn='arn:aws:iam::340032650202:role/BeamEksDeployment',
        resourcesVpcConfig={
            'subnetIds': outputs['SubnetIds'].split(','),
            'securityGroupIds': outputs['SecurityGroups'].split(',')
        },
        logging={
            'clusterLogging': [
                {
                    'types': ['api', 'audit', 'authenticator', 'controllerManager', 'scheduler'],
                    'enabled': True
                },
            ]
        }
    )
    return cluster_details['cluster']['name']


def instance_handler(event):
    time_str = datetime.now().strftime('%Y-%m-%d-%H-%M-%S')
    region = event.get('region', os.environ['REGION'])
    instance_id = event.get('instance_id', os.environ['SYSTEM_INSTANCE'])
    tag = event.get('creation_tag')

    init_eks(region)
    init_cloudformation(region)

    stack_id = create_eks_stack(time_str, tag)
    otp = get_stack_outputs(stack_id)
    cluster_details = create_cluster(otp, time_str)
    wait_cluster_outputs(cluster_details)
    workers_stack_id = create_workers_stack(time_str, instance_id, otp, region, cluster_details, tag)
    workers_outputs = get_stack_outputs(workers_stack_id)

    return workers_outputs


def lambda_handler(event, context):
    print event
    res = instance_handler(event)
    return {
        'statusCode': 200,
        'body': json.dumps(res)
    }
