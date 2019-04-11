# coding=utf-8
import os
import os.path
import json
import boto3

eks = None
cf = None

def init_eks(region):
    global eks
    eks = boto3.client('eks', region_name=region)


def init_cloudformation(region):
    global cf
    cf = boto3.client('cloudformation', region_name=region)

def check_cluster(time_tag):
    clusters_response = eks.describe_cluster(name="beam-cluster-{t}".format(t=time_tag))
    return clusters_response.get('cluster') is not None

def describe_vpc(tag):
    vpc_response = cf.list_stacks(
        StackStatusFilter=['CREATE_COMPLETE']
    )
    vpcs = {}
    for vpc in vpc_response['StackSummaries']:
        stack_description = cf.describe_stacks(StackName=vpc['StackName'])
        vpc_tags = stack_description['Stacks'][0]['Tags']
        for vpc_tag in vpc_tags:
            if vpc_tag['Key'] == 'creation' and vpc_tag['Value'] == tag and ('workers' in vpc['StackName']):
                vpcs['time_tag'] = vpc['StackName'].replace('beam-eks-workers-', '')
                outputs_list = stack_description['Stacks'][0]['Outputs']
                for out in outputs_list:
                    vpcs[out['OutputKey']] = out['OutputValue']
    return vpcs


def instance_handler(event):
    region = event.get('region', os.environ['REGION'])
    tag = event.get('creation_tag')

    init_eks(region)
    init_cloudformation(region)

    out = describe_vpc(tag)

    if out and check_cluster(out['time_tag']):
        out['cluster'] = "beam-cluster-{t}".format(t=out['time_tag'])
        return out

    return {}

def lambda_handler(event, context):
    res = instance_handler(event)
    return {
        'statusCode': 200,
        'body': json.dumps(res)
    }
