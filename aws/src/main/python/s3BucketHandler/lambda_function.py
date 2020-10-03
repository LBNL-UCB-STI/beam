import os
import json
import boto3
import base64
import datetime

lm = boto3.client('lambda')
#cw = boto3.client('cloudwatch')
#now = datetime.datetime.now()

initscript = (('''#cloud-config
runcmd:
  - echo "-------------------checking bucket size----------------------"
  - sudo dpkg --configure -a
  - sudo dpkg --remove --force-remove-reinstreq unattended-upgrades
  - sudo apt-get install unattended-upgrades
  - sudo dpkg --configure -a
  - sudo apt update
  - export AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY
  - export AWS_SECRET_ACCESS_KEY=$AWS_ACCESS_SECRET
  - result=$(aws s3 ls s3://$BUCKET/$FOLDER --recursive | awk 'BEGIN {total=0}{total+=$3}END{print total/1024/1024" MB"}')
  - echo "invoke cloudwatch for updating matric..."
  - aws logs --region=$REGION create-log-stream --log-group-name S3-Bucket-Log --log-stream-name $BUCKET/$FOLDER
  - aws logs --region=$REGION describe-log-streams --log-group-name S3-Bucket-Log --order-by=LastEventTime > output.txt
  - token=$(jq .logStreams[0].uploadSequenceToken output.txt)
  - temp="${token%\\"}"
  - temp="${temp#\\"}"
  - aws logs --region=$REGION put-log-events --log-group-name S3-Bucket-Log --log-stream-name=$BUCKET/$FOLDER --log-events=timestamp=$(($(date +%s%N)/1000000)),message="$result" --sequence-token=$temp
  - echo "setting up auto shutdown ..."
  - sudo shutdown -h +$SHUTDOWN_WAIT
  - echo "shutdown in $SHUTDOWN_WAIT ..."
'''))


ec2 = None

def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2',region_name=region)

def startInstance(script, instance_type, shutdown_behaviour):
    res = ec2.run_instances(ImageId=os.environ['IMAGE_ID'],
                            InstanceType=instance_type,
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
    bucket = event['bucket']
    prefix = event['folder']
    if bucket is None:
        return "Bucket Name not provided"
    if event.get('aws_access_key') is None or event.get('aws_access_secret') is None:
        return "AWS credentials not set"
    region = 'us-east-2'
    instance_type = os.environ['INSTANCE_TYPE']
    shutdown_behaviour = 'terminate'
    shutdown_wait = "10"
    init_ec2(region)
    script = initscript.replace("$BUCKET", bucket).replace("$FOLDER", prefix).replace('$REGION', region).replace('$SHUTDOWN_WAIT', shutdown_wait).replace('$AWS_ACCESS_KEY', event['aws_access_key']).replace('$AWS_ACCESS_SECRET',event['aws_access_secret'])

    instance_id = startInstance(script, instance_type, shutdown_behaviour)
    return instance_id