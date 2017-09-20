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
  - for file in test/output/*; do sudo zip "${file%.*}_$UID.zip" "$file"; done
  - sudo aws --region "$REGION" s3 cp test/output/*.zip s3://beam-outputs/
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''

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

def deploy(script):
    res = ec2.run_instances(ImageId=os.environ['IMAGE_ID'],
                            InstanceType=os.environ['INSTANCE_TYPE'],
                            UserData=script,
                            KeyName=os.environ['KEY_NAME'],
                            MinCount=1,
                            MaxCount=1,
                            SecurityGroupIds=[ os.environ['SECURITY_GROUP'] ],
                            IamInstanceProfile={'Name': os.environ['IAM_ROLE'] },
                            InstanceInitiatedShutdownBehavior='terminate')
    return res['Instances'][0]['InstanceId']

def get_dns(id):
    host = None
    while host is None:
        time.sleep(2)
        instances = ec2.describe_instances(InstanceIds=[id])
        for r in instances['Reservations']:
            for i in r['Instances']:
                dns = i['PublicDnsName']
                if dns != '':
                    host = dns
    return host

def lambda_handler(event, context):
    branch = event['branch']
    build_id = event['build']
    input = event['input']
    configs = event['configs']
    shutdown_wait = event['shutdown_wait']

    if build_id == 'latest' and check_branch(branch):
        build_id = get_latest_build(branch)

    txt = ''
    jar = branch + '/beam-' + build_id + '.jar'
    zip = input + '.zip'

    if check_resource('beam-builds', jar):
        if check_resource('beam-inputs', zip):
            for cfg in configs:
                uid = str(uuid.uuid4())[:8]
                script = initscript.replace('$REGION',os.environ['REGION']).replace('$BRANCH',branch).replace('$BUILD_ID', build_id).replace('$INPUTS', input).replace('$CONFIG', cfg).replace('$UID', uid).replace('$SHUTDOWN_WAIT', shutdown_wait)
                instance_id = deploy(script)
                host = get_dns(instance_id)
                txt = txt + 'Started build: {build} with config: {config} at host {dns}. Please locate outputs with prefix code [{prefix}], '.format(build=build_id, config=cfg, dns=host, prefix=uid)
        else:
            txt = 'Unable to find input with provided name: ' + input + '.'
    else:
        txt = 'Travis build on branch: [' + branch + '] and build# [' + build_id + '] not found.'
    return txt