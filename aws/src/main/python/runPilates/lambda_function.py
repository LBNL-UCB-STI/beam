# coding=utf-8
import boto3
import os
import time

PILATES_INPUT_DATA_PATH = '/output/urbansim-inputs'

PILATES_OUTPUT_DATA_PATH = '/output/urbansim-outputs'

RUN_PILATES_SCRIPT = '''sudo docker run --name pilatesRun 
        -v /home/ubuntu/git/beam:/beam-project 
        -v /home/ubuntu/git/beam/output:/output 
        $PILATES_IMAGE_NAME:$PILATES_IMAGE_VERSION 
        $START_YEAR $COUNT_OF_YEARS $BEAM_IT_LEN $URBANSIM_IT_LEN 
        $OUTPUT_FOLDER 
        /beam-project/$CONFIG 
        $S3_OUTPUT_PATH 
        $S3_DATA_REGION 
        $PILATES_INPUT_DATA_PATH
        $PILATES_OUTPUT_DATA_PATH
        $IN_YEAR_OUTPUT 
        $INITIAL_SKIMS_PATH'''

PREPARE_URBANSIM_INPUT_SCRIPT = 'aws --region "$S3_DATA_REGION" s3 cp --recursive s3:$INITIAL_URBANSIM_INPUT output/urbansim-inputs/initial/'

PREPARE_URBANSIM_OUTPUT_SCRIPT = 'aws --region "$S3_DATA_REGION" s3 cp --recursive s3:$INITIAL_URBANSIM_OUTPUT output/urbansim-outputs/initial/'

PILATES_IMAGE_VERSION_DEFAULT = 'latest'
PILATES_IMAGE_NAME_DEFAULT = 'pilates'
PILATES_SCENARIO_NAME_DEFAULT = 'pilates'
S3_OUTPUT_BUCKET_DEFAULT = '//pilates-outputs'
S3_OUTPUT_BASE_PATH_DEFAULT = ''
IN_YEAR_OUTPUT_DEFAULT = 'off'
START_YEAR_DEFAULT = '2010'
COUNT_OF_YEARS_DEFAULT = '30'
BEAM_IT_LEN_DEFAULT = '5'
URBANSIM_IT_LEN_DEFAULT = '5'
BRANCH_DEFAULT = 'master'
COMMIT_DEFAULT = 'HEAD'
MAXRAM_DEFAULT = '2g'
SHUTDOWN_DEFAULT = '30'
TRUE = 'true'

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
          0 * * * * curl -X POST -H "Content-type: application/json" --data '"'"'{"$(ec2metadata --instance-type) instance $(ec2metadata --instance-id) running... \\n Instance of type $(ec2metadata --instance-type) is still running in $INSTANCE_REGION since last $(($(($(date +%s) - $(cat /tmp/.starttime))) / 3600)) Hour $(($(($(date +%s) - $(cat /tmp/.starttime))) / 60)) Minute."}'"'"
      path: /tmp/slack_notification 
runcmd:
  - echo "-------------------Starting Pilates----------------------"
  - echo $(date +%s) > /tmp/.starttime
  - cd /home/ubuntu/git/beam
  - rm -rf /home/ubuntu/git/beam/test/input/sf-light/r5/network.dat
  - ln -sf /var/log/cloud-init-output.log ./cloud-init-output.log
  - instance_description=$(printf "
        Run name      $TITLED \\n 
        Instance ID      %s \\n 
        Instance type      %s \\n 
        Host name      %s \\n 
        Region      $INSTANCE_REGION \\n 
        Branch      $BRANCH \\n 
        Commit      $COMMIT" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname))
  - instance_resources=$(printf "
        Web browser      http://%s:8000 \\n 
        S3 url      $HTTP_OUTPUT_PATH" $(ec2metadata --public-hostname))
  - hello_msg="PILATES Started \\n$instance_description \\n$instance_resources \\nPARAMS \\t\\t $RUN_PARAMS"
  - start_json=$(printf "{
      \\"command\\":\\"add\\",
      \\"sheet_id\\":\\"$SHEET_ID\\",
      \\"run\\":{
        \\"status\\":\\"PILATES Started\\",
        \\"name\\":\\"$TITLED\\",
        \\"instance_id\\":\\"%s\\",
        \\"instance_type\\":\\"%s\\",
        \\"host_name\\":\\"%s\\",
        \\"browser\\":\\"http://%s:8000\\",
        \\"branch\\":\\"$BRANCH\\",
        \\"region\\":\\"$INSTANCE_REGION\\",
        \\"commit\\":\\"$COMMIT\\",
        \\"s3_link\\":\\"$HTTP_OUTPUT_PATH\\"
     }
    }" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname))
  - chmod +x /tmp/slack.sh
  - echo $hello_msg > /tmp/msg_hello
  - echo $start_json > /tmp/msg_start_json
  - crontab /tmp/slack_notification
  - crontab -l
  - echo "notification scheduled..."
  - git fetch
  - echo "git checkout for branch $BRANCH ..."
  - GIT_LFS_SKIP_SMUDGE=1 sudo git checkout $BRANCH
  - sudo git pull
  - sudo git lfs pull
  - echo "git checkout -qf for commit $COMMIT ..."
  - GIT_LFS_SKIP_SMUDGE=1 sudo git checkout -qf $COMMIT
  - echo "prepare urbansim input and output"
  - rm -rf /home/ubuntu/git/beam$PILATES_INPUT_DATA_PATH
  - rm -rf /home/ubuntu/git/beam$PILATES_OUTPUT_DATA_PATH
  - mkdir -p /home/ubuntu/git/beam$PILATES_INPUT_DATA_PATH
  - mkdir -p /home/ubuntu/git/beam$PILATES_OUTPUT_DATA_PATH/$OUTPUT_FOLDER
  - run_params="$RUN_PARAMS_FOR_FILE"
  - echo $run_params > /home/ubuntu/git/beam$PILATES_OUTPUT_DATA_PATH/$OUTPUT_FOLDER/run-params.log
  - echo "$PREPARE_URBANSIM_INPUT_SCRIPT"
  - $PREPARE_URBANSIM_INPUT_SCRIPT
  - echo "$PREPARE_URBANSIM_OUTPUT_SCRIPT"
  - $PREPARE_URBANSIM_OUTPUT_SCRIPT
  - sudo ls output/urbansim-outputs/initial/*.gz | sudo xargs gunzip
  - sudo ls output/urbansim-inputs/initial/*.gz | sudo xargs gunzip 
  - echo "installing dependencies ..."
  - sudo apt-get install curl
  - curl -sL https://deb.nodesource.com/setup_8.x | sudo -E bash -
  - sudo apt-get install nodejs -y
  - sudo apt-get install docker.io -y
  - sudo apt-get install libgtkextra-dev libgconf2-dev libnss3 libasound2 libxtst-dev -y
  - sudo npm install -g electron@1.8.4 orca --unsafe-perm=true --alow-root -y
  - sudo apt-get install xvfb -y
  - echo "gradlew assemble ..."
  - ./gradlew assemble
  - export MAXRAM=$MAX_RAM
  - export SIGOPT_CLIENT_ID="$SIGOPT_CLIENT_ID"
  - export SIGOPT_DEV_ID="$SIGOPT_DEV_ID"
  - echo "MAXRAM is $MAXRAM"
  - sudo docker pull $PILATES_IMAGE_NAME:$PILATES_IMAGE_VERSION
  - /tmp/slack.sh "$hello_msg"
  - curl -X POST "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet" -H "Content-Type:application/json" --data "$start_json"
  - echo "Running $RUN_SCRIPT"
  - bye_msg="PILATES Completed \\n$instance_description \\n$instance_resources \\nShutdown in $SHUTDOWN_WAIT minutes"
  - $RUN_SCRIPT
  - returnCode=$?
  - status="PILATES completed"
  - if [ $returnCode -ne 0 ]; then
  -     bye_msg="PILATES completed with ERROR!\\nDOCKER RETURN CODE $returnCode \\n$instance_description \\n$instance_resources \\nShutdown in $SHUTDOWN_WAIT minutes"
  -     status="PILATES completed with ERROR (docker return code $returnCode)"
  - fi
  - stop_json=$(printf "{
      \\"command\\":\\"add\\",
      \\"sheet_id\\":\\"$SHEET_ID\\",
      \\"run\\":{
        \\"status\\":\\"%s\\",
        \\"name\\":\\"$TITLED\\",
        \\"instance_id\\":\\"%s\\",
        \\"instance_type\\":\\"%s\\",
        \\"host_name\\":\\"%s\\",
        \\"browser\\":\\"http://%s:8000\\",
        \\"branch\\":\\"$BRANCH\\",
        \\"region\\":\\"$INSTANCE_REGION\\",
        \\"commit\\":\\"$COMMIT\\",
        \\"s3_link\\":\\"$HTTP_OUTPUT_PATH\\"
      }
    }" $status $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname))
  - echo $stop_json > /tmp/msg_stop_json
  - echo $bye_msg > /tmp/msg_bye
  - curl -X POST "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet" -H "Content-Type:application/json" --data "$stop_json"
  - /tmp/slack.sh "$bye_msg"
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

s3 = boto3.client('s3')
ec2 = None


def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2', region_name=region)


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
        IamInstanceProfile={'Name': os.environ['IAM_ROLE']},
        InstanceInitiatedShutdownBehavior=shutdown_behaviour,
        TagSpecifications=[{
            'ResourceType': 'instance',
            'Tags': [{
                'Key': 'Name',
                'Value': instance_name
            }]
        }])
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
    all_run_params_comma = ''
    all_run_params_comma_le = ''
    for key, value in event.items():
        all_run_params_comma += str(key) + ":\'" + str(value) + "\', "
        all_run_params_comma_le += str(key) + ":\'" + str(value) + "\',\\n"

    all_run_params_comma = all_run_params_comma[:-2]
    all_run_params_comma_le = all_run_params_comma_le[:-5]

    titled = event.get('title', 'hostname-test')
    if titled is None:
        return "Unable to start the run, runName is required. Please restart with appropriate runName."

    initial_urbansim_input = event.get('initial_urbansim_input', None)
    if initial_urbansim_input is None:
        return "Unable to start the run, initialS3UrbansimInput is required. Please restart with appropriate arguments."

    initial_urbansim_output = event.get('initial_urbansim_output', None)

    branch = event.get('branch', BRANCH_DEFAULT)
    commit_id = event.get('commit', COMMIT_DEFAULT)

    start_year = event.get('start_year', START_YEAR_DEFAULT)
    count_of_years = event.get('count_of_years', COUNT_OF_YEARS_DEFAULT)
    beam_iteration_length = event.get('beam_it_len', BEAM_IT_LEN_DEFAULT)
    urbansim_iteration_length = event.get('urbansim_it_len', URBANSIM_IT_LEN_DEFAULT)
    pilates_image_version = event.get('pilates_image_version', PILATES_IMAGE_VERSION_DEFAULT)
    pilates_image_name = event.get('pilates_image_name', PILATES_IMAGE_NAME_DEFAULT)
    pilates_scenario_name = event.get('pilates_scenario_name', PILATES_SCENARIO_NAME_DEFAULT)
    initial_skims_path = event.get('initial_skims_path', '')
    in_year_output = event.get('in_year_output', IN_YEAR_OUTPUT_DEFAULT)
    s3_output_bucket = event.get('s3_output_bucket', S3_OUTPUT_BUCKET_DEFAULT)
    s3_output_base_path = event.get('s3_output_base_path', S3_OUTPUT_BASE_PATH_DEFAULT)

    s3_data_region = event.get('pilates_data_region', os.environ['REGION'])

    scenario_with_date = pilates_scenario_name + '_' + time.strftime("%Y-%m-%d_%H-%M-%S")
    relative_output_path = s3_output_base_path + '/' + scenario_with_date
    full_output_bucket_path = s3_output_bucket + relative_output_path
    full_http_output_path = "https://s3." + s3_data_region + ".amazonaws.com" + s3_output_bucket[1:] + '/index.html#' + relative_output_path[1:]

    config = event.get('config', CONFIG_DEFAULT)
    max_ram = event.get('max_ram', MAXRAM_DEFAULT)
    instance_type = event.get('instance_type', os.environ['INSTANCE_TYPE'])
    volume_size = event.get('storage_size', 64)
    shutdown_wait = event.get('shutdown_wait', SHUTDOWN_DEFAULT)
    region = event.get('region', os.environ['REGION'])
    shutdown_behaviour = event.get('shutdown_behaviour', os.environ['SHUTDOWN_BEHAVIOUR'])
    sigopt_client_id = event.get('sigopt_client_id', os.environ['SIGOPT_CLIENT_ID'])
    sigopt_dev_id = event.get('sigopt_dev_id', os.environ['SIGOPT_DEV_ID'])

    if initial_urbansim_output is None and initial_skims_path is '':
        return "Unable to start run, initialS3UrbansimOutput (initial beam data) or initialSkimsPath (initial skims file) should be specified."

    if instance_type not in instance_types:
        return "Unable to start run, {instance_type} instance type not supported.".format(instance_type=instance_type)

    if shutdown_behaviour not in shutdown_behaviours:
        shutdown_behaviour = os.environ['SHUTDOWN_BEHAVIOUR']

    if volume_size < 64 or volume_size > 256:
        volume_size = 64

    if region not in regions:
        return "Unable to start run, {region} region not supported.".format(region=region)

    init_ec2(region)

    prepare_urbansim_output_script = PREPARE_URBANSIM_OUTPUT_SCRIPT
    if initial_urbansim_output is None:
        prepare_urbansim_output_script = " "

    run_name = titled

    script = initscript.replace('$RUN_SCRIPT', RUN_PILATES_SCRIPT) \
        .replace('$RUN_PARAMS_FOR_FILE', all_run_params_comma_le) \
        .replace('$RUN_PARAMS', all_run_params_comma) \
        .replace('$PREPARE_URBANSIM_INPUT_SCRIPT', PREPARE_URBANSIM_INPUT_SCRIPT) \
        .replace('$PREPARE_URBANSIM_OUTPUT_SCRIPT', prepare_urbansim_output_script) \
        .replace('$INSTANCE_REGION', region) \
        .replace('$S3_DATA_REGION', s3_data_region) \
        .replace('$INITIAL_URBANSIM_INPUT', initial_urbansim_input) \
        .replace('$INITIAL_URBANSIM_OUTPUT', initial_urbansim_output) \
        .replace('$PILATES_INPUT_DATA_PATH', PILATES_INPUT_DATA_PATH) \
        .replace('$PILATES_OUTPUT_DATA_PATH', PILATES_OUTPUT_DATA_PATH) \
        .replace('$START_YEAR', start_year).replace('$COUNT_OF_YEARS', count_of_years) \
        .replace('$BEAM_IT_LEN', beam_iteration_length).replace('$URBANSIM_IT_LEN', urbansim_iteration_length) \
        .replace('$PILATES_IMAGE_VERSION', pilates_image_version) \
        .replace('$OUTPUT_FOLDER', scenario_with_date) \
        .replace('$INITIAL_SKIMS_PATH', initial_skims_path) \
        .replace('$S3_OUTPUT_PATH', full_output_bucket_path) \
        .replace('$HTTP_OUTPUT_PATH', full_http_output_path) \
        .replace('$IN_YEAR_OUTPUT', in_year_output) \
        .replace('$PILATES_IMAGE_NAME', pilates_image_name) \
        .replace('$BRANCH', branch).replace('$COMMIT', commit_id).replace('$CONFIG', config) \
        .replace('$SHUTDOWN_WAIT', shutdown_wait) \
        .replace('$TITLED', run_name).replace('$MAX_RAM', max_ram) \
        .replace('$SIGOPT_CLIENT_ID', sigopt_client_id).replace('$SIGOPT_DEV_ID', sigopt_dev_id) \
        .replace('$SLACK_HOOK_WITH_TOKEN', os.environ['SLACK_HOOK_WITH_TOKEN']) \
        .replace('$SHEET_ID', os.environ['SHEET_ID'])

    instance_id = deploy(script, instance_type, region.replace("-", "_") + '_', shutdown_behaviour, run_name, volume_size)
    host = get_dns(instance_id)

    return 'Started with run name: {titled} for branch/commit {branch}/{commit} at host {dns} (InstanceID: {instance_id}). '\
        .format(branch=branch, titled=run_name, commit=commit_id, dns=host, instance_id=instance_id)
