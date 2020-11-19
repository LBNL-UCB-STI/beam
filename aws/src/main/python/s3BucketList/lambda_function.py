import os
import json
import boto3
import datetime

initscript = (('''#cloud-config
runcmd:
  - echo "-------------------checking bucket size----------------------"
  - sudo dpkg --configure -a
  - sudo dpkg --remove --force-remove-reinstreq unattended-upgrades
  - sudo apt-get install unattended-upgrades
  - sudo dpkg --configure -a
  - sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 6B05F25D762E3157
  - sudo apt update
  - buckets=$(aws s3 ls | awk '{print $3}')
  - for bucket in $buckets; do
  - bucket=$(echo $bucket | sed "s,/$,,")
  - aws s3 ls --region=us-east-2 s3://$bucket --human-readable --summarize | while read -r line; do
  - case "$line" in PRE* )
  - key=$(echo $bucket/$line | sed 's/PRE //g')
  - result=$(aws s3 ls --region=us-east-2 s3://$key --recursive | awk 'BEGIN {total=0}{total+=$3}END{print total}')
  - echo $key, $result >> "result.csv"
  - aws s3 ls --region=us-east-2 s3://$key --human-readable --summarize | while read -r nested_key; do
  - case "$nested_key" in PRE* )
  - key=$(echo $key | sed "s,/$,,")
  - inner_nested_key=$(echo $key/$nested_key | sed 's/PRE //g')
  - result=$(aws s3 ls --region=us-east-2 s3://$inner_nested_key --recursive | awk 'BEGIN {total=0}{total+=$3}END{print total}')
  - echo $inner_nested_key, $result >> "result.csv"
  - esac
  - done
  - esac
  - done
  - done
  - sort -o s3-result-out.csv -g -t , -k 2 -r result.csv
  - echo "folder, size(MB)" > output.csv
  - head -$N s3-result-out.csv >> s3-buckets-output.csv
  - aws s3 --region=$REGION cp s3-buckets-output.csv s3://beam-outputs/s3-buckets-output.csv
  - echo "setting up auto shutdown ..."
  - sudo shutdown -h +$SHUTDOWN_WAIT
  - echo "shutdown in $SHUTDOWN_WAIT ..."
'''))


DEFAULT_REGION = 'us-east-2'

ec2 = None

def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2',region_name=region)

def startInstance(script, shutdown_behaviour):
    res = ec2.run_instances(ImageId=os.environ['IMAGE_ID'],
                            InstanceType=os.environ['INSTANCE_TYPE'],
                            UserData=script,
                            KeyName=os.environ['KEY'],
                            MinCount=1,
                            MaxCount=1,
                            SecurityGroupIds=[os.environ['SECURITY_GROUP']],
                            IamInstanceProfile={'Name': os.environ['IAM_INSTANACE_PROFILE'] },
                            InstanceInitiatedShutdownBehavior=shutdown_behaviour,
                            TagSpecifications=[ {
                                'ResourceType': 'instance',
                                'Tags': [ {
                                    'Key': 'Name',
                                    'Value': 'checkS3Bucket'
                                 }]
                            } ])
    return res['Instances'][0]['InstanceId']

def lambda_handler(event, context):
    region = event.get('region', DEFAULT_REGION)
    size = event.get('size', 100)
    shutdown_behaviour = 'terminate'
    shutdown_wait = "10"
    init_ec2(region)
    script = initscript.replace("$N", size).replace('$REGION', region).replace('$SHUTDOWN_WAIT', shutdown_wait)
        
    instance_id = startInstance(script, shutdown_behaviour)
    return instance_id
