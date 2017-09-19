# coding=utf-8
import boto3
import time
from botocore.errorfactory import ClientError

initscript = '''#cloud-config
runcmd:
  - cd /home/ubuntu/beam/test/input
  - sudo aws --region "us-east-2" s3 cp s3://beam-inputs/$INPUTS.zip $INPUTS.zip;
  - sudo aws --region "us-east-2" s3 cp s3://beam-inputs/dtd.zip dtd.zip
  - sudo unzip $INPUTS.zip
  - sudo unzip dtd.zip
  - cd ../..
  - sudo aws --region "us-east-2" s3 cp s3://beam-builds/$BRANCH/beam-$BUILD_ID.jar beam.jar
  - java -jar beam.jar --config test/input/beamville/beam.conf
  - sleep 10s
  - echo "---- BEAM Simulation completed. ----"
  - for file in test/output/*; do sudo zip "${file%.*}.zip" "$file"; done
  - sudo aws --region "us-east-2" s3 cp test/output/*.zip s3://beam-outputs/
  - sudo shutdown -h +1
'''

def check_resource(bucket, key):
    s3 = boto3.client('s3')
    try:
        s3.head_object(Bucket=bucket, Key=key)
        return True
    except ClientError as ex:
        print 'error sending Slack response: ' + str(ex)
    return False

ec2 = boto3.client('ec2',region_name='us-east-2')

def deploy(branch, build_id, input):
    script = initscript.replace('$BRANCH',branch).replace('$BUILD_ID', build_id).replace('$INPUTS', input)
    res = ec2.run_instances(ImageId='ami-6ca68409',
                            InstanceType='t2.micro',
                            UserData=script,
                            KeyName='beam-box01',
                            MinCount=1,
                            MaxCount=1,
                            SecurityGroupIds=[ 'sg-bb469dd3' ],
                            IamInstanceProfile={'Name': 'BeamCodeDeployEC2' },
                            InstanceInitiatedShutdownBehavior='terminate')
    return res['Instances'][0]

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
    txt = None
    jar = branch + '/beam-' + build_id + '.jar'
    zip = input + '.zip'

    if check_resource('beam-builds', jar):
        if check_resource('beam-inputs', zip):
            instance = deploy(branch, build_id, input)
            host = get_dns(instance['InstanceId'])
            txt = 'Started ' + branch + ' with DNS: ' + host;
        else:
            txt = 'Unable to find input with provided name: ' + input + '.'
    else:
        txt = 'Travis build on branch: [' + branch + '] and build# [' + build_id + '] not found.'
    return txt