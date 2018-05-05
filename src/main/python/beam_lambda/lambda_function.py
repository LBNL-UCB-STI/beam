# coding=utf-8
import boto3
import time
import uuid
import os
from botocore.errorfactory import ClientError

CONFIG_SCRIPT = '''./gradlew --stacktrace :run -PappArgs="['--config', '$cf']" -PmaxRAM=$MAX_RAM
  -    sleep 10s
  -    for file in test/output/*; do sudo cp /var/log/cloud-init-output.log "$file"; done;
  -    for file in test/output/*; do sudo zip -r "${file%.*}_$UID.zip" "$file"; done;
  -    sudo aws --region "$S3_REGION" s3 cp test/output/*.zip s3://beam-outputs/'''

EXPERIMENT_SCRIPT = '''./bin/experiment.sh $cf cloud'''

BRANCH_DEFAULT = 'master'

COMMIT_DEFAULT = 'HEAD'

MAXRAM_DEFAULT = '2g'

SHUTDOWN_DEFAULT = '30'

TRUE = 'true'

EXPERIMENT_DEFAULT = 'test/input/beamville/calibration/experiments.yml'

CONFIG_DEFAULT = 'production/application-sfbay/base.conf'

initscript = (('''#cloud-config
runcmd:
  - echo "-------------------Starting Beam Sim----------------------"
  - echo $(date +%s) > /tmp/.starttime
  - /home/ubuntu/git/glip.sh -i "http://icons.iconarchive.com/icons/uiconstock/socialmedia/32/AWS-icon.png" -a "Run [$TITLED] started..." -b "An ec2 instance $(ec2metadata --instance-id) of type **$(ec2metadata --instance-type)** having host name **$(ec2metadata --public-hostname)** is launched in **$REGION** to run the batch [$UID] on branch / commit [**$BRANCH** / $COMMIT]."
  - echo "notification sent..."
  - echo '0 * * * * /home/ubuntu/git/glip.sh -i "http://icons.iconarchive.com/icons/uiconstock/socialmedia/32/AWS-icon.png" -a "$(ec2metadata --instance-type) instance $(ec2metadata --instance-id) running..." -b "Batch [$UID] completed and instance of type $(ec2metadata --instance-type) is still running in $REGION since last $(($(($(date +%s) - $(cat /tmp/.starttime))) / 3600)) Hour $(($(($(date +%s) - $(cat /tmp/.starttime))) / 60)) Minute."' > /tmp/glip_notification
  - echo "notification saved..."
  - crontab /tmp/glip_notification
  - crontab -l
  - echo "notification scheduled..."
  - cd /home/ubuntu/git/beam
  - git fetch
  - echo "git checkout ..."
  - GIT_LFS_SKIP_SMUDGE=1 git checkout $BRANCH
  - echo "git checkout -qf ..."
  - GIT_LFS_SKIP_SMUDGE=1 git checkout -qf $COMMIT
  - git pull
  - git lfs pull
  - echo "gradlew assemble ..."
  - ./gradlew assemble
  - echo "looping config ..."
  - export MAXRAM=$MAX_RAM
  - echo $MAXRAM
  - for cf in $CONFIG
  -  do
  -    echo "-------------------running $cf----------------------"
  -    $RUN_SCRIPT
  -  done
  - /home/ubuntu/git/glip.sh -i "http://icons.iconarchive.com/icons/uiconstock/socialmedia/32/AWS-icon.png" -a "Run [$TITLED] completed..." -b "An ec2 instance $(ec2metadata --instance-id) of type **$(ec2metadata --instance-type)** having host name **$(ec2metadata --public-hostname)** has just completed the run in **$REGION** for batch [$UID] on branch / commit [**$BRANCH** / $COMMIT]. Instanse will remain available for next $SHUTDOWN_WAIT minutes."
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''))

instance_types = ['t2.nano', 't2.micro', 't2.small', 't2.medium', 't2.large', 't2.xlarge', 't2.2xlarge',
                  'm4.large', 'm4.xlarge', 'm4.2xlarge', 'm4.4xlarge', 'm4.10xlarge', 'm4.16xlarge',
                  'm5.large', 'm5.xlarge', 'm5.2xlarge', 'm5.4xlarge', 'm5.12xlarge', 'm5.24xlarge',
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
instance_operations = ['start', 'stop', 'terminate']

s3 = boto3.client('s3')
ec2 = None

def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2',region_name=region)

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

def check_instance_id(instance_ids):
    for reservation in ec2.describe_instances()['Reservations']:
        for instance in reservation['Instances']:
            if instance['InstanceId'] in instance_ids:
                instance_ids.remove(instance['InstanceId'])
    return instance_ids

def start_instance(instance_ids):
    return ec2.start_instances(InstanceIds=instance_ids)

def stop_instance(instance_ids):
    return ec2.stop_instances(InstanceIds=instance_ids)

def terminate_instance(instance_ids):
    return ec2.terminate_instances(InstanceIds=instance_ids)

def deploy_handler(event):
    titled = event.get('title', 'hostname-test')
    if titled is None:
        return "Unable to start the run, runName is required. Please restart with appropriate runName."

    branch = event.get('branch', BRANCH_DEFAULT)
    commit_id = event.get('commit', COMMIT_DEFAULT)
    configs = event.get('configs', CONFIG_DEFAULT)
    max_ram = event.get('max_ram', MAXRAM_DEFAULT)
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

    init_ec2(region)

    if validate(branch) and validate(commit_id):
        runNum = 0
        for arg in configs:
            uid = str(uuid.uuid4())[:8]
            runName = titled
            if runNum > 0:
                runName += "-" + `runNum`
            script = initscript.replace('$RUN_SCRIPT',selected_script).replace('$REGION',region).replace('$S3_REGION',os.environ['REGION']).replace('$BRANCH',branch).replace('$COMMIT', commit_id).replace('$CONFIG', arg).replace('$IS_EXPERIMENT', is_experiment).replace('$UID', uid).replace('$SHUTDOWN_WAIT', shutdown_wait).replace('$TITLED', runName).replace('$MAX_RAM', max_ram)
            instance_id = deploy(script, instance_type, region.replace("-", "_")+'_', shutdown_behaviour)
            host = get_dns(instance_id)
            txt = txt + 'Started batch: {batch} with run name: {titled} for branch/commit {branch}/{commit} at host {dns} (InstanceID: {instance_id}). '.format(branch=branch, titled=runName, commit=commit_id, dns=host, batch=uid, instance_id=instance_id)
            runNum += 1
    else:
        txt = 'Unable to start bach for branch/commit {branch}/{commit}. '.format(branch=branch, commit=commit_id)

    return txt

def instance_handler(event):
    region = event.get('region', os.environ['REGION'])
    instance_ids = event.get('instance_ids')
    command_id = event.get('command')

    if region not in regions:
        return "Unable to {command} instance(s), {region} region not supported.".format(command=command_id, region=region)

    init_ec2(region)

    instance_ids = instance_ids.split(',')
    invalid_ids = check_instance_id(list(instance_ids))
    valid_ids = [item for item in instance_ids if item not in invalid_ids]

    if command_id == 'start':
        start_instance(valid_ids)

    if command_id == 'stop':
        stop_instance(valid_ids)

    if command_id == 'terminate':
        terminate_instance(valid_ids)

    return "Instantiated {command} request for instance(s) [ {ids} ]".format(command=command_id, ids=",".join(valid_ids))

def lambda_handler(event, context):
    command_id = event.get('command', 'deploy') # deploy | start | stop | terminate | log

    if command_id == 'deploy':
        return deploy_handler(event)

    if command_id in instance_operations:
        return instance_handler(event)

    return "Operation {command} not supported, please specify one of the supported operations (deploy | start | stop | terminate | log). ".format(command=command_id)
