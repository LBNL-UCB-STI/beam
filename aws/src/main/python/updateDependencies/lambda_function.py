# coding=utf-8
import os
import boto3

initscript = (('''#cloud-config
runcmd:
  - echo "-------------------Updating Beam dependencies----------------------"
  - cd /home/ubuntu/git/beam
  - echo "send notification ..."
  - /home/ubuntu/git/glip.sh -i "http://icons.iconarchive.com/icons/uiconstock/socialmedia/32/AWS-icon.png" -a "Updating Dependencies" -b "Beam automated deployment image update started on $(ec2metadata --instance-id)."
  - echo "git checkout ..."
  - sudo git reset origin/HEAD
  - sudo git checkout -- .
  - sudo git clean -df
  - git fetch
  - for bn in $BRANCH
  -  do
  -    echo "-------------------checkout $bn----------------------"
  -    GIT_LFS_SKIP_SMUDGE=1 sudo git checkout $bn
  -    sudo git pull
  -    sudo git lfs pull
  -  done
  - echo "gradlew assemble ..."
  - ./gradlew assemble
  - ./gradlew clean
  - echo "invoke lambda ..."
  - sudo aws lambda invoke --invocation-type RequestResponse --function-name updateBeamAMI --region 'us-east-2' --payload '{"instance_id":"'"$(ec2metadata --instance-id)"'","region_id":"us-east-2"}' outputfile.txt
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''))


ec2 = None

def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2',region_name=region)

def deploy(script, instance_type, region_prefix, shutdown_behaviour, instance_name, en_vars):
    res = ec2.run_instances(ImageId=en_vars[region_prefix + 'IMAGE_ID'],
                            InstanceType=instance_type,
                            UserData=script,
                            KeyName=en_vars[region_prefix + 'KEY_NAME'],
                            MinCount=1,
                            MaxCount=1,
                            SecurityGroupIds=[en_vars[region_prefix + 'SECURITY_GROUP']],
                            IamInstanceProfile={'Name': en_vars['IAM_ROLE'] },
                            InstanceInitiatedShutdownBehavior=shutdown_behaviour,
                            TagSpecifications=[ {
                                'ResourceType': 'instance',
                                'Tags': [ {
                                    'Key': 'Name',
                                    'Value': instance_name
                                } ]
                            } ])
    return res['Instances'][0]['InstanceId']

def lambda_handler(event, context):

    lm = boto3.client('lambda')
    en_vars = lm.get_function_configuration(FunctionName='simulateBeam')['Environment']['Variables']

    region = 'us-east-2'
    instance_type = os.environ['INSTANCE_TYPE']
    shutdown_behaviour = 'stop'
    shutdown_wait = "1"
    runName = 'update-beam-dependencies'
    branches = os.environ['BRANCHES']

    script = initscript.replace('$BRANCH', branches).replace('$SHUTDOWN_WAIT', shutdown_wait)

    init_ec2(region)
    instance_id = deploy(script, instance_type, region.replace("-", "_")+'_', shutdown_behaviour, runName, en_vars)

    return instance_id
