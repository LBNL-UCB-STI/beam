# coding=utf-8
import boto3
import time
import uuid
import os
import glob
from botocore.errorfactory import ClientError

CONFIG_SCRIPT = '''./gradlew --stacktrace :run -PappArgs="['--config', '$cf']" -PmaxRAM=$MAX_RAM'''

EXECUTE_SCRIPT = '''./gradlew --stacktrace :execute -PmainClass=$MAIN_CLASS -PappArgs="$cf" -PmaxRAM=$MAX_RAM'''

EXPERIMENT_SCRIPT = '''./bin/experiment.sh $cf cloud'''

S3_PUBLISH_SCRIPT = '''
  -    sleep 10s
  -    opth="output"
  -    echo $opth
  -    finalPath=""
  -    for file in $opth/*; do
  -       for path2 in $file/*; do
  -         finalPath="$path2";
  -       done;
  -    done;
  -    sudo cp /var/log/cloud-init-output.log "$finalPath"
  -    sudo aws --region "$S3_REGION" s3 cp "$finalPath" s3://beam-outputs/"$finalPath" --recursive;
  -    s3p="$s3p, https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#$finalPath"'''

END_SCRIPT_DEFAULT = '''echo "End script not provided."'''

BRANCH_DEFAULT = 'master'

COMMIT_DEFAULT = 'HEAD'

MAXRAM_DEFAULT = '2g'

SHUTDOWN_DEFAULT = '30'

TRUE = 'true'

EXECUTE_CLASS_DEFAULT = 'beam.sim.RunBeam'

EXECUTE_ARGS_DEFAULT = '''['--config', 'test/input/beamville/beam.conf']'''

EXPERIMENT_DEFAULT = 'test/input/beamville/calibration/experiments.yml'

CONFIG_DEFAULT = 'production/application-sfbay/base.conf'

initscript = (('''
#cloud-config
write_files:
    - content: |
          #!/bin/bash
          data="{\\"text\\":\\""
          data+=$1
          data+="\\"}"
          printf "%s" "$data" > /tmp/slack.json
          curl -X POST -H 'Content-type: application/json' --data-binary @/tmp/slack.json $SLACK_HOOK_WITH_TOKEN
      path: /tmp/slack.sh
    - content: |
          0 * * * * curl -X POST -H "Content-type: application/json" --data '"'"'{"$(ec2metadata --instance-type) instance $(ec2metadata --instance-id) running... \\n Batch [$UID] completed and instance of type $(ec2metadata --instance-type) is still running in $REGION since last $(($(($(date +%s) - $(cat /tmp/.starttime))) / 3600)) Hour $(($(($(date +%s) - $(cat /tmp/.starttime))) / 60)) Minute."}'"'"
      path: /tmp/slack_notification
runcmd:
  - echo "-------------------Starting Beam Sim----------------------"
  - echo $(date +%s) > /tmp/.starttime
  - cd /home/ubuntu/git/beam
  - rm -rf /home/ubuntu/git/beam/test/input/sf-light/r5/network.dat
  - ln -sf /var/log/cloud-init-output.log ./cloud-init-output.log
  - hello_msg=$(printf "Run Started \\n Run Name** $TITLED** \\n Instance ID %s \\n Instance type **%s** \\n Host name **%s** \\n Web browser ** http://%s:8000 ** \\n Region $REGION \\n Batch $UID \\n Branch **$BRANCH** \\n Commit $COMMIT" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname))
  - chmod +x /tmp/slack.sh
  - echo "notification sent..."
  - echo "notification saved..."
  - crontab /tmp/slack_notification
  - crontab -l
  - echo "notification scheduled..."
  - git fetch
  - 'echo "git checkout: $(date)"'
  - GIT_LFS_SKIP_SMUDGE=1 sudo git checkout $BRANCH
  - sudo git pull
  - sudo git lfs pull
  - echo "git checkout -qf ..."
  - GIT_LFS_SKIP_SMUDGE=1 sudo git checkout -qf $COMMIT

  - 'echo "gradlew assemble: $(date)"'
  - ./gradlew assemble
  - echo "looping config ..."
  - export MAXRAM=$MAX_RAM
  - export SIGOPT_CLIENT_ID="$SIGOPT_CLIENT_ID"
  - export SIGOPT_DEV_ID="$SIGOPT_DEV_ID"
  - echo $MAXRAM
  - /tmp/slack.sh "$hello_msg"
  - /home/ubuntu/git/glip.sh -i "http://icons.iconarchive.com/icons/uiconstock/socialmedia/32/AWS-icon.png" -a "Run Started" -b "Run Name** $TITLED** \\n Instance ID $(ec2metadata --instance-id) \\n Instance type **$(ec2metadata --instance-type)** \\n Host name **$(ec2metadata --public-hostname)** \\n Web browser **http://$(ec2metadata --public-hostname):8000** \\n Region $REGION \\n Batch $UID \\n Branch **$BRANCH** \\n Commit $COMMIT"
  - s3p=""
  - for cf in $CONFIG
  -  do
  -    echo "-------------------running $cf----------------------"
  -    $RUN_SCRIPT
  -  done
  - s3glip=""
  - if [ "$S3_PUBLISH" = "true" ]
  - then
  -   s3glip="\\n S3 output url ${s3p#","}"
  - fi
  - bye_msg=$(printf "Run Completed \\n Run Name** $TITLED** \\n Instance ID %s \\n Instance type **%s** \\n Host name **%s** \\n Web browser ** http://%s:8000 ** \\n Region $REGION \\n Batch $UID \\n Branch **$BRANCH** \\n Commit $COMMIT %s \\n Shutdown in $SHUTDOWN_WAIT minutes" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname) "$s3glip")
  - echo "$bye_msg"
  - /tmp/slack.sh "$bye_msg"
  - /home/ubuntu/git/glip.sh -i "http://icons.iconarchive.com/icons/uiconstock/socialmedia/32/AWS-icon.png" -a "Run Completed" -b "Run Name** $TITLED** \\n Instance ID $(ec2metadata --instance-id) \\n Instance type **$(ec2metadata --instance-type)** \\n Host name **$(ec2metadata --public-hostname)** \\n Web browser **http://$(ec2metadata --public-hostname):8000** \\n Region $REGION \\n Batch $UID \\n Branch **$BRANCH** \\n Commit $COMMIT $s3glip \\n Shutdown in $SHUTDOWN_WAIT minutes"
  - $END_SCRIPT
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''))

instance_types = ['t2.nano', 't2.micro', 't2.small', 't2.medium', 't2.large', 't2.xlarge', 't2.2xlarge',
                  'm4.large', 'm4.xlarge', 'm4.2xlarge', 'm4.4xlarge', 'm4.10xlarge', 'm4.16xlarge',
                  'm5.large', 'm5.xlarge', 'm5.2xlarge', 'm5.4xlarge', 'm5.12xlarge', 'm5.24xlarge',
                  'c4.large', 'c4.xlarge', 'c4.2xlarge', 'c4.4xlarge', 'c4.8xlarge',
                  'f1.2xlarge', 'f1.16xlarge',
                  'g2.2xlarge', 'g2.8xlarge',
                  'g3.4xlarge', 'g3.8xlarge', 'g3.16xlarge',
                  'p2.xlarge', 'p2.8xlarge', 'p2.16xlarge',
                  'p3.2xlarge', 'p3.8xlarge', 'p3.16xlarge',
                  'r4.large', 'r4.xlarge', 'r4.2xlarge', 'r4.4xlarge', 'r4.8xlarge', 'r4.16xlarge',
                  'r3.large', 'r3.xlarge', 'r3.2xlarge', 'r3.4xlarge', 'r3.8xlarge',
                  'x1.16xlarge', 'x1.32xlarge',
                  'x1e.xlarge', 'x1e.2xlarge', 'x1e.4xlarge', 'x1e.8xlarge', 'x1e.16xlarge', 'x1e.32xlarge',
                  'd2.xlarge', 'd2.2xlarge', 'd2.4xlarge', 'd2.8xlarge',
                  'i2.xlarge', 'i2.2xlarge', 'i2.4xlarge', 'i2.8xlarge',
                  'h1.2xlarge', 'h1.4xlarge', 'h1.8xlarge', 'h1.16xlarge',
                  'i3.large', 'i3.xlarge', 'i3.2xlarge', 'i3.4xlarge', 'i3.8xlarge', 'i3.16xlarge', 'i3.metal',
                  'c5.large', 'c5.xlarge', 'c5.2xlarge', 'c5.4xlarge', 'c5.9xlarge', 'c5.18xlarge',
                  'c5d.large', 'c5d.xlarge', 'c5d.2xlarge', 'c5d.4xlarge', 'c5d.9xlarge', 'c5d.18xlarge',
                  'r5.large', 'r5.xlarge', 'r5.2xlarge', 'r5.4xlarge', 'r5.12xlarge', 'r5.24xlarge',
                  'r5d.large', 'r5d.xlarge', 'r5d.2xlarge', 'r5d.4xlarge', 'r5d.12xlarge', 'r5d.24xlarge',
                  'm5d.large', 'm5d.xlarge', 'm5d.2xlarge', 'm5d.4xlarge', 'm5d.12xlarge', 'm5d.24xlarge',
                  'z1d.large', 'z1d.xlarge', 'z1d.2xlarge', 'z1d.3xlarge', 'z1d.6xlarge', 'z1d.12xlarge']

regions = ['us-east-1', 'us-east-2', 'us-west-2']
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

def deploy(script, instance_type, region_prefix, shutdown_behaviour, instance_name, volume_size):
    res = ec2.run_instances(BlockDeviceMappings=[
        {
            'DeviceName': '/dev/sda1',
            'Ebs': {
                'VolumeSize': volume_size,
                'VolumeType': 'gp2'
            }
        }
    ],
        ImageId=os.environ[region_prefix + 'IMAGE_ID'],
        InstanceType=instance_type,
        UserData=script,
        KeyName=os.environ[region_prefix + 'KEY_NAME'],
        MinCount=1,
        MaxCount=1,
        SecurityGroupIds=[os.environ[region_prefix + 'SECURITY_GROUP']],
        IamInstanceProfile={'Name': os.environ['IAM_ROLE'] },
        InstanceInitiatedShutdownBehavior=shutdown_behaviour,
        TagSpecifications=[ {
            'ResourceType': 'instance',
            'Tags': [ {
                'Key': 'Name',
                'Value': instance_name
            } ]
        } ])
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
    deploy_mode = event.get('deploy_mode', 'config')
    configs = event.get('configs', CONFIG_DEFAULT)
    experiments = event.get('experiments', EXPERIMENT_DEFAULT)
    execute_class = event.get('execute_class', EXECUTE_CLASS_DEFAULT)
    execute_args = event.get('execute_args', EXECUTE_ARGS_DEFAULT)
    batch = event.get('batch', TRUE)
    max_ram = event.get('max_ram', MAXRAM_DEFAULT)
    s3_publish = event.get('s3_publish', 'true')
    instance_type = event.get('instance_type', os.environ['INSTANCE_TYPE'])
    volume_size = event.get('storage_size', 64)
    shutdown_wait = event.get('shutdown_wait', SHUTDOWN_DEFAULT)
    region = event.get('region', os.environ['REGION'])
    shutdown_behaviour = event.get('shutdown_behaviour', os.environ['SHUTDOWN_BEHAVIOUR'])
    sigopt_client_id = event.get('sigopt_client_id', os.environ['SIGOPT_CLIENT_ID'])
    sigopt_dev_id = event.get('sigopt_dev_id', os.environ['SIGOPT_DEV_ID'])
    end_script = event.get('end_script', END_SCRIPT_DEFAULT)

    if instance_type not in instance_types:
        return "Unable to start run, {instance_type} instance type not supported.".format(instance_type=instance_type)
        #instance_type = os.environ['INSTANCE_TYPE']

    if shutdown_behaviour not in shutdown_behaviours:
        shutdown_behaviour = os.environ['SHUTDOWN_BEHAVIOUR']

    if volume_size < 64 or volume_size > 256:
        volume_size = 64

    selected_script = CONFIG_SCRIPT
    params = configs
    if s3_publish == TRUE:
        selected_script += S3_PUBLISH_SCRIPT

    if deploy_mode == 'experiment':
        selected_script = EXPERIMENT_SCRIPT
        params = experiments

    if batch == TRUE:
        params = [ params.replace(',', ' ') ]
    else:
        params = params.split(',')

    if deploy_mode == 'execute':
        selected_script = EXECUTE_SCRIPT
        params = [ '"{args}"'.format(args=execute_args) ]

    if end_script != END_SCRIPT_DEFAULT:
        end_script = '/home/ubuntu/git/beam/sec/main/bash/' + end_script

    txt = ''

    if region not in regions:
        return "Unable to start run, {region} region not supported.".format(region=region)

    init_ec2(region)

    if validate(branch) and validate(commit_id):
        runNum = 1
        for arg in params:
            uid = str(uuid.uuid4())[:8]
            runName = titled
            if len(params) > 1:
                runName += "-" + `runNum`
            script = initscript.replace('$RUN_SCRIPT',selected_script).replace('$REGION',region).replace('$S3_REGION',os.environ['REGION']) \
                .replace('$BRANCH',branch).replace('$COMMIT', commit_id).replace('$CONFIG', arg) \
                .replace('$MAIN_CLASS', execute_class).replace('$UID', uid).replace('$SHUTDOWN_WAIT', shutdown_wait) \
                .replace('$TITLED', runName).replace('$MAX_RAM', max_ram).replace('$S3_PUBLISH', s3_publish) \
                .replace('$SIGOPT_CLIENT_ID', sigopt_client_id).replace('$SIGOPT_DEV_ID', sigopt_dev_id).replace('$END_SCRIPT', end_script) \
                .replace('$SLACK_HOOK_WITH_TOKEN', os.environ['SLACK_HOOK_WITH_TOKEN'])
            instance_id = deploy(script, instance_type, region.replace("-", "_")+'_', shutdown_behaviour, runName, volume_size)
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
    system_instances = os.environ['SYSTEM_INSTANCES']

    if region not in regions:
        return "Unable to {command} instance(s), {region} region not supported.".format(command=command_id, region=region)

    init_ec2(region)

    system_instances = system_instances.split(',')
    instance_ids = instance_ids.split(',')
    invalid_ids = check_instance_id(list(instance_ids))
    valid_ids = [item for item in instance_ids if item not in invalid_ids]
    allowed_ids = [item for item in valid_ids if item not in system_instances]

    if command_id == 'start':
        start_instance(allowed_ids)
        return "Started instance(s) {insts}.".format(insts=', '.join([': '.join(inst) for inst in zip(allowed_ids, list(map(get_dns, allowed_ids)))]))

    if command_id == 'stop':
        stop_instance(allowed_ids)

    if command_id == 'terminate':
        terminate_instance(allowed_ids)

    return "Instantiated {command} request for instance(s) [ {ids} ]".format(command=command_id, ids=",".join(allowed_ids))

def lambda_handler(event, context):
    command_id = event.get('command', 'deploy') # deploy | start | stop | terminate | log

    if command_id == 'deploy':
        return deploy_handler(event)

    if command_id in instance_operations:
        return instance_handler(event)

    return "Operation {command} not supported, please specify one of the supported operations (deploy | start | stop | terminate | log). ".format(command=command_id)
