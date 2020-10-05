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
  - result=$(aws s3 --region=$REGION ls s3://$BUCKET/$FOLDER --recursive | awk 'BEGIN {total=0}{total+=$3}END{print total/1024/1024" MB"}')
  - echo "invoke cloudwatch for updating metric..."
  - aws logs --region=$REGION create-log-stream --log-group-name S3-Bucket-Log --log-stream-name $BUCKET/$FOLDER
  - aws logs --region=$REGION describe-log-streams --log-group-name S3-Bucket-Log --log-stream-name-prefix=$BUCKET/$FOLDER > output.txt
  - token=$(jq -r '.logStreams[] | select(.logStreamName=="$BUCKET/$FOLDER") | .uploadSequenceToken' output.txt)
  - if [ $token = null ]; then
  -     aws logs --region=$REGION put-log-events --log-group-name S3-Bucket-Log --log-stream-name=$BUCKET/$FOLDER --log-events=timestamp=$(($(date +%s%N)/1000000)),message="$result"
  -   else
  -     aws logs --region=$REGION put-log-events --log-group-name S3-Bucket-Log --log-stream-name=$BUCKET/$FOLDER --log-events=timestamp=$(($(date +%s%N)/1000000)),message="$result" --sequence-token=$token
  - fi
  - echo "setting up auto shutdown ..."
  - sudo shutdown -h +$SHUTDOWN_WAIT
  - echo "shutdown in $SHUTDOWN_WAIT ..."
'''))

DEFAULT_REGION = 'us-east-2'
lm = boto3.client('lambda')

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
    bucket = event.get('bucket')
    prefix = event.get('folder')
    region = event.get('region', DEFAULT_REGION)
    if bucket is None:
        return "Bucket Name not provided!!!"
    if prefix is None:
        return "Directory Name not provided!!!"
    shutdown_behaviour = 'terminate'
    shutdown_wait = "10"
    init_ec2(region)
    script = initscript.replace("$BUCKET", bucket).replace("$FOLDER", prefix).replace('$REGION', region).replace('$SHUTDOWN_WAIT', shutdown_wait)

    instance_id = startInstance(script, shutdown_behaviour)
    return instance_id