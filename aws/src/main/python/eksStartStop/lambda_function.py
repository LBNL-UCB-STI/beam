# coding=utf-8
import os
import time
from datetime import datetime

import boto3

instance_operations = ['start', 'stop']

eks = None
cf = None


def init_eks(region):
    global eks
    eks = boto3.client('eks', region_name=region)


def init_cloudformation(region):
    global cf
    cf = boto3.client('cloudformation', region_name=region)


def createEksStack():
    time_str = datetime.now().strftime('%Y-%m-%d-%H-%M-%S')
    response = cf.create_stack(
        StackName="beam-eks-{dt}".format(dt=time_str),
        TemplateURL='https://amazon-eks.s3-us-west-2.amazonaws.com/cloudformation/2019-02-11/amazon-eks-vpc-sample.yaml',
        Parameters=[
            {
                'ParameterKey': 'Description',
                'ParameterValue': "Amazon EKS VPC created at {dt}".format(dt=time_str)
            },
        ],
        RollbackConfiguration={
            'MonitoringTimeInMinutes': 0
        },
        TimeoutInMinutes=10,
        Capabilities=[
            'CAPABILITY_IAM',
        ],
        OnFailure='ROLLBACK',
        EnableTerminationProtection=False
    )
    return response['StackId']


def getEksStackOutputs(stackName):
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


def createCluster(outputs, time_token):
    cluster_details = eks.create_cluster(
        name="beam-cluster-{t}".format(t=time_token),
        version='1.11',
        roleArn='arn:aws:iam::340032650202:role/eksServiceRole',
        resourcesVpcConfig={
            'subnetIds': outputs['SubnetIds'].split(','),
            'securityGroupIds': outputs['SecurityGroups'].split(','),
            'vpcId': outputs['VpcId']
        }
    )
    return cluster_details['cluster']


def instance_handler(event):
    region = event.get('region', os.environ['REGION'])

    init_eks(region)
    init_cloudformation(region)

    stack_id = createEksStack()
    otp = getEksStackOutputs(stack_id)

    return "Return values {dict}".format(dict=otp)
