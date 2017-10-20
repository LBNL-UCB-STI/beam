# coding=utf-8
import boto3
import time
import uuid
import os
from botocore.errorfactory import ClientError

initscript = '''#cloud-config
runcmd:
  - cd /home/ubuntu/beam/test/input
  - sudo aws --region "$REGION" s3 cp s3://beam-inputs/$INPUTS.zip $INPUTS.zip
  - sudo aws --region "$REGION" s3 cp s3://beam-inputs/dtd.zip dtd.zip
  - sudo unzip $INPUTS.zip
  - sudo unzip dtd.zip
  - cd ../..
  - sudo aws --region "$REGION" s3 cp s3://beam-builds/$BRANCH/beam-$BUILD_ID.jar beam.jar
  - java -jar beam.jar --config test/input/$INPUTS/$CONFIG
  - sleep 10s
  - echo "---- BEAM Simulation completed. ----"
  - for file in test/output/*; do sudo zip -r "${file%.*}_$UID.zip" "$file"; done;
  - sudo aws --region "$REGION" s3 cp test/output/*.zip s3://beam-outputs/
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''
instance_types = ['t2.nano', 't2.micro', 't2.small', 't2.medium', 't2.large', 't2.xlarge', 't2.2xlarge',
                  'm4.large', 'm4.xlarge', 'm4.2xlarge', 'm4.4xlarge', 'm4.10xlarge', 'm4.16xlarge',
                  'm3.medium', 'm3.large', 'm3.xlarge', 'm3.2xlarge']

s3 = boto3.client('s3')
ec2 = boto3.client('ec2',region_name=os.environ['REGION'])

def check_resource(bucket, key):
    try:
        s3.head_object(Bucket=bucket, Key=key)
        return True
    except ClientError as ex:
        print 'error sending Slack response: ' + str(ex)
    return False

def check_branch(branch):
    try:
        s3.list_objects_v2(Bucket='beam-builds', Prefix=branch+'/')['Contents']
        return True
    except Exception:
        return False

def get_latest_build(branch):
    get_last_modified = lambda obj: int(obj['LastModified'].strftime('%s'))
    objs = s3.list_objects_v2(Bucket='beam-builds', Prefix=branch+'/')['Contents']
    last_added = [obj['Key'] for obj in sorted(objs, key=get_last_modified, reverse=True)][0]
    return last_added[last_added.rfind('-')+1:-4]

def deploy(script, instance_type):
    res = ec2.run_instances(ImageId=os.environ['IMAGE_ID'],
                            InstanceType=instance_type,
                            UserData=script,
                            KeyName=os.environ['KEY_NAME'],
                            MinCount=1,
                            MaxCount=1,
                            SecurityGroupIds=[ os.environ['SECURITY_GROUP'] ],
                            IamInstanceProfile={'Name': os.environ['IAM_ROLE'] },
                            InstanceInitiatedShutdownBehavior='terminate')
    return res['Instances'][0]['InstanceId']

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

def lambda_handler(event, context):
    branch = event.get('branch', 'master')
    build_id = event.get('build', 'latest')
    beam_input = event.get('input', 'beamville')
    configs = event.get('configs', ['beam.conf'])
    instance_type = event.get('instance_type')
    shutdown_wait = event.get('shutdown_wait', '30')

    if build_id == 'latest' and check_branch(branch):
        build_id = get_latest_build(branch)

    if instance_type is None or instance_type not in instance_types:
        instance_type = os.environ['INSTANCE_TYPE']

    txt = ''
    jar = branch + '/beam-' + build_id + '.jar'
    archive = beam_input + '.zip'

    if check_resource('beam-builds', jar):
        if check_resource('beam-inputs', archive):
            for cfg in configs:
                uid = str(uuid.uuid4())[:8]
                script = initscript.replace('$REGION',os.environ['REGION']).replace('$BRANCH',branch).replace('$BUILD_ID', build_id).replace('$INPUTS', beam_input).replace('$CONFIG', cfg).replace('$UID', uid).replace('$SHUTDOWN_WAIT', shutdown_wait)
                instance_id = deploy(script, instance_type)
                host = get_dns(instance_id)
                txt = txt + 'Started build: {build} with config: {config} at host {dns}. Please locate outputs with prefix code [{prefix}], '.format(build=build_id, config=cfg, dns=host, prefix=uid)
        else:
            txt = 'Unable to find input with provided name: ' + beam_input + '.'
    else:
        txt = 'Travis build on branch: [' + branch + '] and build# [' + build_id + '] not found.'
    return txt