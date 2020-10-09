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
  - sudo apt update
  - buckets=$(aws s3 ls | awk '{print $3}')
  - for bucket in $buckets; do
  - lines=$(aws s3 ls --region=$REGION s3://$bucket --recursive --human-readable --summarize)
  - for line in $lines; do
  - echo $line | grep -qE "/$"
  - if [ $? = 0 ]; then 
  - token_count=$(echo $line | awk -F'/' '{ print NF }')
  - if [ $token_count -eq 2 -o $token_count -eq 3 ]; then
  - result=$(aws s3 ls --region=$REGION s3://$bucket/$line --recursive | awk 'BEGIN {total=0}{total+=$3}END{print total/1024/1024}')
  - echo $bucket/$line, $result >> "result.csv"
  - fi
  - fi 
  - done
  - done
  - sort -o s3-result-out.csv -n -t , -k 2 -r result.csv
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
