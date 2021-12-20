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

# JSON notification fields:
# "status": text field which contains status of run;
# "name": s2 instance name;
# "instance_id": s2 instance id;
# "instance_type": s2 instance type;
# "host_name": s2 host name;
# "browser": s2 browsable path;
# "s3_URL": s3 output URL;
# "instance_region": s2 instance region;
# "data_region": s3 data region which is used when copying from s3 and writing to s3 buckets;
# "branch": git branch;
# "commit": git commit;
# "data_branch": data branch;
# "s3_path": s3 path to output folder started with double slash;
# "title": used as instance name only;
# "start_year": pilates simulation start year;
# "count_of_years": pilates simulation count of years;
# "beam_it_len": pilates simulation delta year;
# "urbansim_it_len": parameter for urbansim simulation run;
# "in_year_output": generate in year output for pilates;
# "config": path to BEAM config file;
# "pilates_image_version": version of pilates image;
# "pilates_image_name": fully specified pilates image name;
# "pilates_scenario_name": used to name output folder like;
# "initial_urbansim_input": initial data for first urbansim run;
# "initial_urbansim_output": initial data for first BEAM run. If first BEAM run is not skipped;
# "initial_skims_path": initial skim file path. If first beam run is skipped;
# "s3_output_bucket": path to s3 output bucket;
# "s3_output_base_path": base path inside s3 output bucket;
# "max_ram": max ram;
# "storage_size": storage size;
# "shutdown_wait": wait timeout in minutes before shutdown after simulation end;
# "shutdown_behaviour": shutdown behaviour.

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
  - instID=$(ec2metadata --instance-id)
  - instTYPE=$(ec2metadata --instance-type)
  - instPHN=$(ec2metadata --public-hostname)
  - spreadsheetMSGBODY=$(printf "
        \\"name\\":\\"$TITLED\\",
        \\"instance_id\\":\\"%s\\",
        \\"instance_type\\":\\"%s\\",
        \\"host_name\\":\\"%s\\",
        \\"browser\\":\\"http://%s:8000\\",
        \\"s3_URL\\":\\"$HTTP_OUTPUT_PATH\\",
        \\"s3_path\\":\\"$S3_OUTPUT_PATH\\",
        \\"instance_region\\":\\"$INSTANCE_REGION\\",
        \\"data_region\\":\\"$S3_DATA_REGION\\",
        \\"branch\\":\\"$BRANCH\\",
        \\"commit\\":\\"$COMMIT\\",
        \\"data_branch\\":\\"$DATA_BRANCH\\",
        \\"title\\":\\"$TITLED\\",
        \\"start_year\\":\\"$START_YEAR\\",
        \\"count_of_years\\":\\"$COUNT_OF_YEARS\\",
        \\"beam_it_len\\":\\"$BEAM_IT_LEN\\",
        \\"urbansim_it_len\\":\\"$URBANSIM_IT_LEN\\",
        \\"in_year_output\\":\\"$IN_YEAR_OUTPUT\\",
        \\"config\\":\\"$CONFIG\\",
        \\"pilates_image_version\\":\\"$PILATES_IMAGE_VERSION\\",
        \\"pilates_image_name\\":\\"$PILATES_IMAGE_NAME\\",
        \\"pilates_scenario_name\\":\\"$PILATES_SCENARIO_NAME\\",
        \\"initial_urbansim_input\\":\\"$INITIAL_URBANSIM_INPUT\\",
        \\"initial_urbansim_output\\":\\"$INITIAL_URBANSIM_OUTPUT\\",
        \\"initial_skims_path\\":\\"$INITIAL_SKIMS_PATH\\",
        \\"s3_output_bucket\\":\\"$S3_OUTPUT_BUCKET\\",
        \\"s3_output_base_path\\":\\"$S3_OUTPUT_BASE_PATH\\",
        \\"max_ram\\":\\"$MAX_RAM\\",
        \\"storage_size\\":\\"$STORAGE_SIZE\\",
        \\"shutdown_wait\\":\\"$SHUTDOWN_WAIT\\",
        \\"shutdown_behaviour\\":\\"$SHUTDOWN_BEHAVIOUR\\"
        " "$instID" "$instTYPE" "$instPHN" "$instPHN" )
  - instance_description=$(printf "
        Run name      $TITLED \\n 
        Instance ID      %s \\n 
        Instance type      %s \\n 
        Host name      %s \\n 
        Region      $INSTANCE_REGION \\n 
        Branch      $BRANCH \\n 
        Commit      $COMMIT" "$(ec2metadata --instance-id)" "$(ec2metadata --instance-type)" "$(ec2metadata --public-hostname)" )
  - instance_resources=$(printf "
        Web browser      http://%s:8000 \\n 
        S3 url      $HTTP_OUTPUT_PATH" $(ec2metadata --public-hostname))
  - hello_msg="PILATES Started \\n$instance_description \\n$instance_resources \\nPARAMS \\t\\t $RUN_PARAMS"
  - start_json="{
          \\"command\\":\\"add\\",
          \\"type\\":\\"pilates\\",
          \\"sheet_id\\":\\"$SHEET_ID\\",
          \\"run\\":{
            \\"status\\":\\"PILATES Started\\",
            $spreadsheetMSGBODY
            }
        }"
  - chmod +x /tmp/slack.sh
  - echo $hello_msg > /tmp/msg_hello
  - echo $start_json > /tmp/msg_spreadsheet_start
  - crontab /tmp/slack_notification
  - crontab -l
  - echo "notification scheduled..."
  - git fetch
  - echo "git checkout for branch $BRANCH ..."
  - GIT_LFS_SKIP_SMUDGE=1 sudo git checkout $BRANCH
  
  - production_data_submodules=$(git submodule | awk '{ print $2 }')
  - for i in $production_data_submodules
  -  do
  -    for cf in $CONFIG
  -      do
  -        case $cf in
  -         '*$i*)'
  -            echo "Loading remote production data for $i"
  -            git config submodule.$i.branch $DATA_BRANCH
  -            git submodule update --init --remote $i
  -        esac
  -      done
  -  done
  
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
  - export GOOGLE_API_KEY="$GOOGLE_API_KEY"
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
  - stop_json="{
      \\"command\\":\\"add\\",
      \\"type\\":\\"pilates\\",
      \\"sheet_id\\":\\"$SHEET_ID\\",
      \\"run\\":{
        \\"status\\":\\"$status\\",
        $spreadsheetMSGBODY
      }
    }" 
  - echo $stop_json > /tmp/msg_spreadsheet_end
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
max_system_ram = 15
percent_towards_system_ram = .25


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
    iteration = 1
    host = None
    while host is None:
        time.sleep(2)

        # 40 seconds limit
        if iteration > 20:
            host = 'unknown dns'

        instances = ec2.describe_instances(InstanceIds=[instance_id])
        for r in instances['Reservations']:
            for i in r['Instances']:
                dns = i['PublicDnsName']
                if dns != '':
                    host = dns

        iteration = iteration + 1

    return host

instance_type_to_memory = {
    't2.nano': 0.5, 't2.micro': 1, 't2.small': 2, 't2.medium': 4, 't2.large': 8, 't2.xlarge': 16, 't2.2xlarge': 32,
    'm4.large': 8, 'm4.xlarge': 16, 'm4.2xlarge': 32, 'm4.4xlarge': 64, 'm4.10xlarge': 160, 'm4.16xlarge': 256,
    'm5.large': 8, 'm5.xlarge': 16, 'm5.2xlarge': 32, 'm5.4xlarge': 64, 'm5.12xlarge': 192, 'm5.24xlarge': 384,
    'c4.large': 3.75, 'c4.xlarge': 7.5, 'c4.2xlarge': 15, 'c4.4xlarge': 30, 'c4.8xlarge': 60,
    'f1.2xlarge': 122, 'f1.16xlarge': 976,
    'g2.2xlarge': 15.25, 'g2.8xlarge': 61,
    'g3.4xlarge': 122, 'g3.8xlarge': 244, 'g3.16xlarge': 488,
    'p2.xlarge': 61, 'p2.8xlarge': 488, 'p2.16xlarge': 732,
    'p3.2xlarge': 61, 'p3.8xlarge': 244, 'p3.16xlarge': 488,
    'r4.large': 15.25, 'r4.xlarge': 30.5, 'r4.2xlarge': 61, 'r4.4xlarge': 122, 'r4.8xlarge': 244, 'r4.16xlarge': 488,
    'r3.large': 15, 'r3.xlarge': 30.5, 'r3.2xlarge': 61, 'r3.4xlarge': 122, 'r3.8xlarge': 244,
    'x1.16xlarge': 976, 'x1.32xlarge': 1952,
    'x1e.xlarge': 122, 'x1e.2xlarge': 244, 'x1e.4xlarge': 488, 'x1e.8xlarge': 976, 'x1e.16xlarge': 1952, 'x1e.32xlarge': 3904,
    'd2.xlarge': 30.5, 'd2.2xlarge': 61, 'd2.4xlarge': 122, 'd2.8xlarge': 244,
    'i2.xlarge': 30.5, 'i2.2xlarge': 61, 'i2.4xlarge': 122, 'i2.8xlarge': 244,
    'h1.2xlarge': 32, 'h1.4xlarge': 64, 'h1.8xlarge': 128, 'h1.16xlarge': 256,
    'i3.large': 15.25, 'i3.xlarge': 30.5, 'i3.2xlarge': 61, 'i3.4xlarge': 122, 'i3.8xlarge': 244, 'i3.16xlarge': 488, 'i3.metal': 512,
    'c5.large': 4, 'c5.xlarge': 8, 'c5.2xlarge': 16, 'c5.4xlarge': 32, 'c5.9xlarge': 72, 'c5.18xlarge': 96,
    'c5d.large': 4, 'c5d.xlarge': 8, 'c5d.2xlarge': 16, 'c5d.4xlarge': 32, 'c5d.9xlarge': 72, 'c5d.18xlarge': 144, 'c5d.24xlarge': 192,
    'r5.large': 16, 'r5.xlarge': 32, 'r5.2xlarge': 64, 'r5.4xlarge': 128, 'r5.8xlarge': 256, 'r5.12xlarge': 384, 'r5.24xlarge': 768,
    'r5d.large': 16, 'r5d.xlarge': 32, 'r5d.2xlarge': 64, 'r5d.4xlarge': 128, 'r5d.12xlarge': 384, 'r5d.24xlarge': 768,
    'm5d.large': 8, 'm5d.xlarge': 16, 'm5d.2xlarge': 32, 'm5d.4xlarge': 64, 'm5d.12xlarge': 192, 'm5d.24xlarge': 384,
    'z1d.large': 2, 'z1d.xlarge': 4, 'z1d.2xlarge': 8, 'z1d.3xlarge': 12, 'z1d.6xlarge': 24, 'z1d.12xlarge': 48
}

def calculate_max_ram(instance_type):
    ram = instance_type_to_memory[instance_type]
    return ram - min(ram * percent_towards_system_ram, max_system_ram)

def lambda_handler(event, context):
    all_run_params_comma = ''
    all_run_params_comma_le = ''
    for key, value in event.items():
        all_run_params_comma += str(key) + ":\'" + str(value) + "\', "
        all_run_params_comma_le += str(key) + ":\'" + str(value) + "\',\\n"

    all_run_params_comma = all_run_params_comma[:-2]
    all_run_params_comma_le = all_run_params_comma_le[:-4]

    missing_parameters = []

    def parameter_wasnt_specified(parameter_value):
        # in gradle if parameter wasn't specified then project.findProperty return 'null'
        return parameter_value is None or parameter_value == 'null'

    def get_param(param_name):
        param_value = event.get(param_name)
        if parameter_wasnt_specified(param_value):
            missing_parameters.append(param_name)
        return param_value

    shutdown_behaviour = get_param('shutdownBehaviour')
    run_name = get_param('runName') + '_' + shutdown_behaviour.toUpperCase()
    initial_urbansim_input = get_param('initialS3UrbansimInput')
    branch = get_param('beamBranch')
    commit_id = get_param('beamCommit')
    data_branch = get_param('dataBranch')

    start_year = get_param('startYear')
    count_of_years = get_param('countOfYears')
    beam_iteration_length = get_param('beamItLen')
    urbansim_iteration_length = get_param('urbansimItLen')
    pilates_image_version = get_param('pilatesImageVersion')
    pilates_image_name = get_param('pilatesImageName')
    pilates_scenario_name = get_param('pilatesScenarioName')
    in_year_output = get_param('inYearOutput')
    s3_output_bucket = get_param('s3OutputBucket')

    s3_data_region = get_param('dataRegion')

    config = get_param('beamConfig')
    shutdown_wait = get_param('shutdownWait')

    instance_type = get_param('instanceType')
    volume_size = get_param('storageSize')
    region = get_param('region')

    if missing_parameters:
        return "Unable to start, missing parameters: " + ", ".join(missing_parameters)

    s3_output_base_path = event.get('s3OutputBasePath')
    if parameter_wasnt_specified(s3_output_base_path):
        s3_output_base_path = ""

    initial_urbansim_output = event.get('initialS3UrbansimOutput')
    initial_skims_path = event.get('initialSkimPath')
    if parameter_wasnt_specified(initial_urbansim_output) and parameter_wasnt_specified(initial_skims_path):
        return "Unable to start, initialS3UrbansimOutput (initial beam data) or initialSkimPath (initial skim file) should be specified."

    if parameter_wasnt_specified(initial_urbansim_output):
        initial_urbansim_output = ""
    if parameter_wasnt_specified(initial_skims_path):
        initial_skims_path = ""

    if instance_type not in instance_types:
        return "Unable to start, {instance_type} instance type not supported.".format(instance_type=instance_type)

    if shutdown_behaviour not in shutdown_behaviours:
        return "Unable to start, {shutdown_behaviour} shutdown behaviour not supported.".format(shutdown_behaviour=shutdown_behaviour)

    if region not in regions:
        return "Unable to start, {region} region not supported.".format(region=region)

    max_ram = event.get('forced_max_ram')
    if parameter_wasnt_specified(max_ram):
        max_ram = calculate_max_ram(instance_type)

    if volume_size < 64:
        volume_size = 64
    if volume_size > 256:
        volume_size = 256

    sigopt_client_id = os.environ['SIGOPT_CLIENT_ID']
    sigopt_dev_id = os.environ['SIGOPT_DEV_ID']
    google_api_key = event.get('google_api_key', os.environ['GOOGLE_API_KEY'])

    scenario_with_date = pilates_scenario_name + '_' + time.strftime("%Y-%m-%d_%H-%M-%S")
    relative_output_path = s3_output_base_path + '/' + scenario_with_date
    full_output_bucket_path = s3_output_bucket + relative_output_path
    full_http_output_path = "https://s3." + s3_data_region + ".amazonaws.com" + s3_output_bucket[1:] \
                            + '/index.html#' + relative_output_path[1:]

    init_ec2(region)

    prepare_urbansim_output_script = PREPARE_URBANSIM_OUTPUT_SCRIPT
    if initial_urbansim_output is None:
        prepare_urbansim_output_script = " "

    script = initscript.replace('$RUN_SCRIPT', RUN_PILATES_SCRIPT) \
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
        .replace('$DATA_BRANCH', data_branch) \
        .replace('$SHUTDOWN_WAIT', shutdown_wait) \
        .replace('$SHUTDOWN_BEHAVIOUR', shutdown_behaviour) \
        .replace('$STORAGE_SIZE', str(volume_size)) \
        .replace('$S3_OUTPUT_BUCKET', s3_output_bucket) \
        .replace('$S3_OUTPUT_BASE_PATH', s3_output_base_path) \
        .replace('$PILATES_SCENARIO_NAME', pilates_scenario_name) \
        .replace('$TITLED', run_name).replace('$MAX_RAM', str(max_ram)) \
        .replace('$SIGOPT_CLIENT_ID', sigopt_client_id).replace('$SIGOPT_DEV_ID', sigopt_dev_id) \
        .replace('$GOOGLE_API_KEY', google_api_key) \
        .replace('$SLACK_HOOK_WITH_TOKEN', os.environ['SLACK_HOOK_WITH_TOKEN']) \
        .replace('$SHEET_ID', os.environ['SHEET_ID']) \
        .replace('$RUN_PARAMS_FOR_FILE', all_run_params_comma_le) \
        .replace('$RUN_PARAMS', all_run_params_comma)

    instance_id = deploy(script, instance_type, region.replace("-", "_") + '_', shutdown_behaviour, run_name, volume_size)
    host = get_dns(instance_id)

    return 'Started with run name: {titled} for branch/commit {branch}/{commit} at host {dns} (InstanceID: {instance_id}). '\
        .format(branch=branch, titled=run_name, commit=commit_id, dns=host, instance_id=instance_id)