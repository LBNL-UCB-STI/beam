# coding=utf-8
import boto3
import time
import uuid
import os
from botocore.errorfactory import ClientError

CONFIG_SCRIPT = '''./gradlew --stacktrace run -PappArgs="['--config', '$cf']"
  -    sleep 10s
  -    for file in test/output/*; do sudo zip -r "${file%.*}_$UID.zip" "$file"; done;
  -    sudo aws --region "$S3_REGION" s3 cp test/output/*.zip s3://beam-outputs/'''

EXPERIMENT_SCRIPT = '''./bin/experiment.sh $cf cloud'''

BRANCH_DEFAULT = 'master'

COMMIT_DEFAULT = 'HEAD'

SHUTDOWN_DEFAULT = '30'

TRUE = 'true'

EXPERIMENT_DEFAULT = 'test/input/beamville/calibration/experiments.yml'

CONFIG_DEFAULT = 'production/application-sfbay/base.conf'

initscript = (('''#cloud-config
runcmd:
  - echo "-------------------Starting Beam Sim----------------------"
  - echo $(date +%s) > /tmp/.starttime
  - /home/ubuntu/git/glip.sh -i "http://icons.veryicon.com/256/Internet%20%26%20Web/Socialmedia/AWS.png" -a "$(ec2metadata --instance-type) instance $(ec2metadata --instance-id) started..." -b "An EC2 instance of type $(ec2metadata --instance-type) launched in $REGION to run the batch [$UID] on branch / commit [$BRANCH / $COMMIT]."
  - echo "notification sent..."
  - echo '0 * * * * /home/ubuntu/git/glip.sh -i "http://icons.veryicon.com/256/Internet%20%26%20Web/Socialmedia/AWS.png" -a "$(ec2metadata --instance-type) instance $(ec2metadata --instance-id) running..." -b "Batch [$UID] completed and instance of type $(ec2metadata --instance-type) is still running in $REGION since last $(($(($(date +%s) - $(cat /tmp/.starttime))) / 3600)) Hour $(($(($(date +%s) - $(cat /tmp/.starttime))) / 60)) Minute."' > /tmp/glip_notification
  - echo "notification saved..."
  - crontab /tmp/glip_notification
  - crontab -l
  - echo "notification scheduled..."
  - cd /home/ubuntu/git/beam
  - git fetch
  - echo "git checkout ..."
  - git checkout $BRANCH
  - git pull
  - echo "git checkout -qf ..."
  - git checkout -qf $COMMIT
  - echo "gradlew assemble ..."
  - ./gradlew assemble
  - echo "looping config ..."
  - for cf in $CONFIG
  -  do
  -    echo "-------------------running $cf----------------------"
  -    $RUN_SCRIPT
  -  done
  - /home/ubuntu/git/glip.sh -i "http://icons.veryicon.com/256/Internet%20%26%20Web/Socialmedia/AWS.png" -a "$(ec2metadata --instance-type) instance $(ec2metadata --instance-id) finished execution..." -b "An EC2 instance of type $(ec2metadata --instance-type) has just completed the run in $REGION for batch [$UID] on branch / commit [$BRANCH / $COMMIT]."
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''))

instance_types = ['t2.nano', 't2.micro', 't2.small', 't2.medium', 't2.large', 't2.xlarge', 't2.2xlarge',
                  'm4.large', 'm4.xlarge', 'm4.2xlarge', 'm4.4xlarge', 'm4.10xlarge', 'm4.16xlarge',
                  'c4.large', 'c4.xlarge', 'c4.2xlarge', 'c4.4xlarge', 'c4.8xlarge',
                  'g3.4xlarge', 'g3.8xlarge', 'g3.16xlarge',
                  'p2.xlarge', 'p2.8xlarge', 'p2.16xlarge',
                  'p3.2xlarge', 'p3.8xlarge', 'p3.16xlarge',
                  'r4.large', 'r4.xlarge', 'r4.2xlarge', 'r4.4xlarge', 'r4.8xlarge', 'r4.16xlarge',
                  'r3.large', 'r3.xlarge', 'r3.2xlarge', 'r3.4xlarge', 'r3.8xlarge',
                  'x1.16xlarge', 'x1.32xlarge',
                  'd2.xlarge', 'd2.2xlarge', 'd2.4xlarge', 'd2.8xlarge',
                  'i2.xlarge', 'i2.2xlarge', 'i2.4xlarge', 'i2.8xlarge',
                  'h1.2xlarge', 'h1.4xlarge', 'h1.8xlarge', 'h1.16xlarge',
                  'i3.large', 'i3.xlarge', 'i3.2xlarge', 'i3.4xlarge', 'i3.8xlarge', 'i3.16xlarge',
                  'c5.large', 'c5.xlarge', 'c5.2xlarge', 'c5.4xlarge', 'c5.9xlarge', 'c5.18xlarge']

regions = ['us-east-2', 'us-west-2']
shutdown_behaviours = ['stop', 'terminate']

s3 = boto3.client('s3')
ec2 = None

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

def validate(name):
    return True

def deploy(script, instance_type, region_prefix, shutdown_behaviour):
    res = ec2.run_instances(ImageId=os.environ[region_prefix + 'IMAGE_ID'],
                            InstanceType=instance_type,
                            UserData=script,
                            KeyName=os.environ[region_prefix + 'KEY_NAME'],
                            MinCount=1,
                            MaxCount=1,
                            SecurityGroupIds=[os.environ[region_prefix + 'SECURITY_GROUP']],
                            IamInstanceProfile={'Name': os.environ['IAM_ROLE'] },
                            InstanceInitiatedShutdownBehavior=shutdown_behaviour)
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
    branch = event.get('branch', BRANCH_DEFAULT)
    commit_id = event.get('commit', COMMIT_DEFAULT)
    configs = event.get('configs', CONFIG_DEFAULT)
    is_experiment = event.get('is_experiment', 'false')
    instance_type = event.get('instance_type', os.environ['INSTANCE_TYPE'])
    batch = event.get('batch', TRUE)
    shutdown_wait = event.get('shutdown_wait', SHUTDOWN_DEFAULT)
    region = event.get('region', os.environ['REGION'])
    shutdown_behaviour = event.get('shutdown_behaviour', os.environ['SHUTDOWN_BEHAVIOUR'])

    if instance_type not in instance_types:
        instance_type = os.environ['INSTANCE_TYPE']

    if shutdown_behaviour not in shutdown_behaviours:
        shutdown_behaviour = os.environ['SHUTDOWN_BEHAVIOUR']

    selected_script = CONFIG_SCRIPT
    if is_experiment == TRUE:
        selected_script = EXPERIMENT_SCRIPT

    if batch == TRUE:
        configs = [ configs.replace(',', ' ') ]
    else:
        configs = configs.split(',')

    txt = ''

    if region not in regions:
        return "Unable to start run, {region} region not supported.".format(region=region)

    global ec2
    ec2 = boto3.client('ec2',region_name=region)

    if validate(branch) and validate(commit_id):
        for arg in configs:
            uid = str(uuid.uuid4())[:8]
            script = initscript.replace('$RUN_SCRIPT',selected_script).replace('$REGION',region).replace('$S3_REGION',os.environ['REGION']).replace('$BRANCH',branch).replace('$COMMIT', commit_id).replace('$CONFIG', arg).replace('$IS_EXPERIMENT', is_experiment).replace('$UID', uid).replace('$SHUTDOWN_WAIT', shutdown_wait)
            instance_id = deploy(script, instance_type, region.replace("-", "_")+'_', shutdown_behaviour)
            host = get_dns(instance_id)
            txt = txt + 'Started batch: {batch} for branch/commit {branch}/{commit} at host {dns}. \n'.format(branch=branch, commit=commit_id, dns=host, batch=uid)
            # txt = txt + 'Script is {script}. \n'.format(script=script)
    else:
        txt = 'Unable to start bach for branch/commit {branch}/{commit}.'.format(branch=branch, commit=commit_id)

    return txt