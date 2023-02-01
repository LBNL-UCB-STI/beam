# coding=utf-8 
import boto3
import logging
import time
import uuid
import os
import glob
import base64
from botocore.errorfactory import ClientError

logger = logging.getLogger()
logger.setLevel(logging.INFO)


GRAFANA_RUN = '''sudo ./gradlew --stacktrace grafanaStart
  -    '''

CONFIG_SCRIPT = '''./gradlew --stacktrace :run -PappArgs="['--config', '$CONFIG']" -PmaxRAM=$MAX_RAM -Pprofiler_type=$PROFILER'''

EXECUTE_SCRIPT = '''./gradlew --stacktrace :execute -PmainClass=$MAIN_CLASS -PappArgs="$CONFIG" -PmaxRAM=$MAX_RAM -Pprofiler_type=$PROFILER'''

EXPERIMENT_SCRIPT = '''./bin/experiment.sh $CONFIG cloud'''

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
  -    for file in /home/ubuntu/git/beam/*.jfr; do
  -      echo "Zipping $file"
  -      zip "$file.zip" "$file"
  -      sudo cp "$file.zip" "$finalPath"
  -    done;
  -    if [ -d "$finalPath" ]; then
  -       sudo cp /home/ubuntu/git/beam/gc_* "$finalPath"
  -       sudo cp /var/log/cloud-init-output.log "$finalPath"
  -       sudo cp /home/ubuntu/git/beam/thread_dump_from_RunBeam.txt.gz "$finalPath"    

  -       sudo gzip /home/ubuntu/cpu_ram_usage.csv
  -       sudo cp /home/ubuntu/cpu_ram_usage* "$finalPath"

  -       cosimulationLogPath="/home/ubuntu/git/beam/src/main/python/gemini/cosimulation"
  -       cosimulationOutPath="${finalPath}/cosimulation_output"
  -       mkdir "$cosimulationOutPath"
  -       for f in $(find "$cosimulationLogPath/" -iname '*.log' -o -iname '*.txt'); do 
  -          cp "${f}" "$cosimulationOutPath/"; 
  -       done;
  -       sudo gzip "$cosimulationOutPath/*" 2>/dev/null

  -       s3p="$s3p, https://s3.us-east-2.amazonaws.com/beam-outputs/index.html#$finalPath"
  -    else
  -       finalPath="output/cloud-init-logs"
  -       mkdir -p "$finalPath"
  -       cloudInitName=$(echo "$(date '+%Y-%m-%d_%H-%M-%S')__$CONFIG__cloud-init-output.log" | tr '/' '_' ) 
  -       sudo cp /var/log/cloud-init-output.log "$finalPath/$cloudInitName"
  -       s3p="$s3p, https://beam-outputs.s3.amazonaws.com/$finalPath/$cloudInitName"
  -    fi
  -    echo "copy to s3 '$finalPath'"
  -    export finalPath=$finalPath
  -    sudo aws --region "$S3_REGION" s3 cp "$finalPath" s3://beam-outputs/"$finalPath" --recursive;'''

END_SCRIPT_DEFAULT = '''echo "End script not provided."'''

BRANCH_DEFAULT = 'master'

DATA_BRANCH_DEFAULT = 'develop'

DATA_COMMIT_DEFAULT = 'HEAD'

COMMIT_DEFAULT = 'HEAD'

MAXRAM_DEFAULT = '2g'

SHUTDOWN_DEFAULT = '30'

EXECUTE_CLASS_DEFAULT = 'beam.sim.RunBeam'

EXECUTE_ARGS_DEFAULT = '''['--config', 'test/input/beamville/beam.conf']'''

EXPERIMENT_DEFAULT = 'test/input/beamville/calibration/experiments.yml'

CONFIG_DEFAULT = 'production/application-sfbay/base.conf'

DEFAULT_STUCK_GUARD_MIN_CPU_USAGE = "5"

DEFAULT_STUCK_GUARD_MAX_INACTIVE_TIME_INTERVAL = "10"

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
          */10 * * * * /home/ubuntu/beam_stuck_guard.sh
          
      path: /tmp/cron_jobs
    - content: |
            #!/bin/bash
            timeout=$1
            echo "date,time,CPU usage,RAM used,RAM available"
            while sleep $timeout
            do
                    timestamp_CPU=$(vmstat 1 3 -SM -a -w -t | python3 -c 'import sys; ll=sys.stdin.readlines()[-1].split(); print(ll[-2] + ", " + ll[-1] + ", " + str(100 - int(ll[-5])))')
                    ram_used_available=$(free -g | python3 -c 'import sys; ll=sys.stdin.readlines()[-2].split(); print(ll[2] + ", " + ll[-1])')
                    echo $timestamp_CPU, $ram_used_available
            done
      path: /home/ubuntu/write-cpu-ram-usage.sh
    - content: |
            #!/bin/bash
            pgrep -f RunBeam || exit 0
            out_dir=$(find /home/ubuntu/git/beam/output -maxdepth 2 -mindepth 2 -type d -print -quit)
            if [[ -z "${out_dir}" ]]; then exit 0; fi
            log_file="$out_dir/beamLog.out"
            if [[ ! -f $log_file ]] ; then exit 0; fi
            last_completed=$(tac $log_file | grep -m 1 completed | grep -m 1 -Eo '^[0-9]{2}:[0-9]{2}:[0-9]{2}')
            if [[ -z "${last_completed}" ]]; then
              last_completed=$(tac $log_file | grep -m 1 -Eo '^[0-9]{2}:[0-9]{2}:[0-9]{2}')
            fi
            beam_status=$(python3 -c "import datetime as dt; diff = dt.datetime.now() - dt.datetime.combine(dt.datetime.today(), dt.time.fromisoformat('$last_completed')); diff = diff + dt.timedelta(days = 1) if diff < dt.timedelta(0) else diff; x = 'OK' if diff < dt.timedelta(hours=$STUCK_GUARD_MAX_INACTIVE_TIME_INTERVAL) else 'Bad'; print(x)")
            pid=$(pgrep -f RunBeam)
            cpu_usage=`grep 'cpu ' /proc/stat | awk '{usage=($2+$4)*100/($2+$4+$5)} END {print usage}'`
            if [ "$beam_status" == 'Bad' ] && [ "$pid" != "" ] && [$cpu_usage < $STUCK_GUARD_MIN_CPU_USAGE]; then
              jstack $pid | gzip > "$out_dir/kill_thread_dump.txt.gz"
              kill $pid
              sleep 5m
              kill -9 $pid
            fi
      path: /home/ubuntu/beam_stuck_guard.sh
    - content: |
            #!/bin/bash
            log_file="$(find /home/ubuntu/git/beam/output -maxdepth 2 -mindepth 2 -type d -print -quit)/beamLog.out"
            if [[ ! -f $log_file ]]; then
                echo "Unable to start"
                exit 0;
            fi
            last_line=$(tail $log_file -n 1)
            if [[ $last_line == *"Exiting BEAM"* ]]; then
                echo "Run Completed"
            else
                echo "Run Failed"
            fi
            exit 0;
      path: /home/ubuntu/check_simulation_result.sh

runcmd:
  - sudo chmod +x /home/ubuntu/write-cpu-ram-usage.sh
  - sudo chmod +x /home/ubuntu/beam_stuck_guard.sh
  - sudo chmod +x /home/ubuntu/check_simulation_result.sh
  - cd /home/ubuntu
  - ./write-cpu-ram-usage.sh 20 > cpu_ram_usage.csv &
  - cd /home/ubuntu/git
  - sudo rm -rf beam
  - sudo git clone https://github.com/LBNL-UCB-STI/beam.git
  - ln -sf /home/ubuntu/cpu_ram_usage.csv /home/ubuntu/git/beam/cpu_ram_usage.csv
  - ln -sf /var/log/cloud-init-output.log /home/ubuntu/git/beam/cloud-init-output.log
  - sudo chmod 644 /var/log/cloud-init-output.log
  - sudo chmod 644 /home/ubuntu/git/beam/cloud-init-output.log
  - cd /home/ubuntu/git/beam

  - 'echo "sudo git fetch"'
  - sudo git fetch
  - 'echo "GIT_LFS_SKIP_SMUDGE=1 sudo git checkout $BRANCH $(date)"'
  - GIT_LFS_SKIP_SMUDGE=1 sudo git checkout $BRANCH
  - 'echo "sudo git pull"'
  - sudo git pull
  - 'echo "sudo git lfs pull"'
  - sudo git lfs pull
  
  - if [ "$COMMIT" = "HEAD" ]
  - then
  -   RESOLVED_COMMIT=$(git log -1 --pretty=format:%H)
  - else
  -   RESOLVED_COMMIT=$COMMIT
  - fi
  - echo "Resolved commit is $RESOLVED_COMMIT"
  
  - echo "sudo git checkout -qf ..."
  - GIT_LFS_SKIP_SMUDGE=1 sudo git checkout -qf $COMMIT

  - production_data_submodules=$(git submodule | awk '{ print $2 }')
  - for i in $production_data_submodules
  -  do
  -    case $CONFIG in
  -     '*$i*)'
  -        echo "Loading remote production data for $i"
  -        git config submodule.$i.branch $DATA_BRANCH
  -        git submodule update --init --remote $i
  -        cd $i
  -        if [ "$DATA_COMMIT" = "HEAD" ]
  -        then
  -          RESOLVED_DATA_COMMIT=$(git log -1 --pretty=format:%H)
  -        else
  -          RESOLVED_DATA_COMMIT=$DATA_COMMIT
  -        fi
  -        echo "Resolved data commit is $RESOLVED_DATA_COMMIT"
  -        git checkout $DATA_COMMIT
  -        cd -
  -    esac
  -  done

  - if [ "$RUN_JUPYTER" = "True" ]
  - then
  -   echo "Starting Jupyter"
  -   sudo ./gradlew jupyterStart -Puser=root -PjupyterToken=$JUPYTER_TOKEN -PjupyterImage=$JUPYTER_IMAGE
  - fi
  
  - if [ "$RUN_BEAM" = "True" ]
  - then

  -   echo "-------------------Starting Beam Sim----------------------"
  -   echo $(date +%s) > /tmp/.starttime
  -   rm -rf /home/ubuntu/git/beam/test/input/sf-light/r5/network.dat  
  -   hello_msg=$(printf "Run Started \\n Run Name** $TITLED** \\n Instance ID %s \\n Instance type **%s** \\n Host name **%s** \\n Web browser ** http://%s:8000 ** \\n Region $REGION \\n Branch **$BRANCH** \\n Commit $COMMIT" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname))
  -   start_json=$(printf "{
        \\"command\\":\\"add\\",
        \\"type\\":\\"beam\\",
        \\"run\\":{
          \\"status\\":\\"Run Started\\",
          \\"name\\":\\"$TITLED\\",
          \\"instance_id\\":\\"%s\\",
          \\"instance_type\\":\\"%s\\",
          \\"host_name\\":\\"%s\\",
          \\"browser\\":\\"http://%s:8000\\",
          \\"branch\\":\\"$BRANCH\\",
          \\"commit\\":\\"$RESOLVED_COMMIT\\",
          \\"data_branch\\":\\"$DATA_BRANCH\\",
          \\"data_commit\\":\\"$RESOLVED_DATA_COMMIT\\",
          \\"region\\":\\"$REGION\\",
          \\"batch\\":\\"\\",
          \\"s3_link\\":\\"%s\\",
          \\"max_ram\\":\\"$MAX_RAM\\",
          \\"profiler_type\\":\\"$PROFILER\\",
          \\"config_file\\":\\"$CONFIG\\",
          \\"sigopt_client_id\\":\\"$SIGOPT_CLIENT_ID\\",
          \\"sigopt_dev_id\\":\\"$SIGOPT_DEV_ID\\"
        }
      }" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname))
  -   echo $start_json
  -   curl -X POST "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet" -H "Content-Type:application/json" --data "$start_json"
  -   chmod +x /tmp/slack.sh
  -   echo "notification sent..."
  -   echo "notification saved..."
  -   crontab /tmp/cron_jobs
  -   crontab -l
  -   echo "notification scheduled..."

  -   'echo "gradlew assemble: $(date)"'
  -   ./gradlew assemble
  -   'echo "sudo chown -R ubuntu:ubuntu ."'
  -   sudo chown -R ubuntu:ubuntu .
  -   echo "looping config ..."
  -   export MAXRAM=$MAX_RAM
  -   export SIGOPT_CLIENT_ID="$SIGOPT_CLIENT_ID"
  -   export SIGOPT_DEV_ID="$SIGOPT_DEV_ID"
  -   export GOOGLE_API_KEY="$GOOGLE_API_KEY"
  -   echo $MAXRAM
  -   /tmp/slack.sh "$hello_msg"

  -   s3p=""
  -   echo "-------------------running $CONFIG----------------------"
  -   $RUN_SCRIPT

  -   echo "-------------------running Health Analysis Script----------------------"
  -   simulation_health_analysis_output_file="simulation_health_analysis_result.txt"
  -   python3 src/main/python/general_analysis/simulation_health_analysis.py $simulation_health_analysis_output_file
  -   health_metrics=""
  -   while IFS="," read -r metric count
  -   do
  -      export $metric=$count
  -      health_metrics="$health_metrics, $metric:$count"
  -   done < $simulation_health_analysis_output_file
  -   health_metrics=\\{$(echo $health_metrics | cut -c3-)\\}
  -   echo $health_metrics
  -   sudo aws --region $S3_REGION s3 cp $simulation_health_analysis_output_file s3://beam-outputs/$finalPath/$simulation_health_analysis_output_file

  -   s3glip=""
  -   if [ "$S3_PUBLISH" = "True" ]
  -   then
  -     s3glip="\\n S3 output url ${s3p#","}"
  -   fi
  -   cd /home/ubuntu
  -   final_status=$(./check_simulation_result.sh)
  -   bye_msg=$(printf "Run Completed \\n Run Name** $TITLED** \\n Instance ID %s \\n Instance type **%s** \\n Host name **%s** \\n Web browser ** http://%s:8000 ** \\n Region $REGION \\n Branch **$BRANCH** \\n Commit $COMMIT %s \\n Health Metrics %s \\n Shutdown in $SHUTDOWN_WAIT minutes" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname) "$s3glip" "$health_metrics")
  -   echo "$bye_msg"
  -   stop_json=$(printf "{
        \\"command\\":\\"add\\",
        \\"type\\":\\"beam\\",
        \\"run\\":{
          \\"status\\":\\"$final_status\\",
          \\"name\\":\\"$TITLED\\",
          \\"instance_id\\":\\"%s\\",
          \\"instance_type\\":\\"%s\\",
          \\"host_name\\":\\"%s\\",
          \\"browser\\":\\"http://%s:8000\\",
          \\"branch\\":\\"$BRANCH\\",
          \\"commit\\":\\"$RESOLVED_COMMIT\\",
          \\"data_branch\\":\\"$DATA_BRANCH\\",
          \\"data_commit\\":\\"$RESOLVED_DATA_COMMIT\\",
          \\"region\\":\\"$REGION\\",
          \\"batch\\":\\"\\",
          \\"s3_link\\":\\"%s\\",
          \\"max_ram\\":\\"$MAX_RAM\\",
          \\"profiler_type\\":\\"$PROFILER\\",
          \\"config_file\\":\\"$CONFIG\\",
          \\"stacktrace\\":\\"$stacktrace\\",
          \\"died_actors\\":\\"$actorDied\\",
          \\"error\\":\\"$error\\",
          \\"warning\\":\\"$warn\\",
          \\"sigopt_client_id\\":\\"$SIGOPT_CLIENT_ID\\",
          \\"sigopt_dev_id\\":\\"$SIGOPT_DEV_ID\\"
        }
      }" $(ec2metadata --instance-id) $(ec2metadata --instance-type) $(ec2metadata --public-hostname) $(ec2metadata --public-hostname) "${s3p#","}")
  -   /tmp/slack.sh "$bye_msg"
  -   curl -X POST "https://ca4ircx74d.execute-api.us-east-2.amazonaws.com/production/spreadsheet" -H "Content-Type:application/json" --data "$stop_json"
  -   $END_SCRIPT
  - fi
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''))

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
    'x1e.xlarge': 122, 'x1e.2xlarge': 244, 'x1e.4xlarge': 488, 'x1e.8xlarge': 976, 'x1e.16xlarge': 1952,
    'x1e.32xlarge': 3904,
    'd2.xlarge': 30.5, 'd2.2xlarge': 61, 'd2.4xlarge': 122, 'd2.8xlarge': 244,
    'i2.xlarge': 30.5, 'i2.2xlarge': 61, 'i2.4xlarge': 122, 'i2.8xlarge': 244,
    'h1.2xlarge': 32, 'h1.4xlarge': 64, 'h1.8xlarge': 128, 'h1.16xlarge': 256,
    'i3.large': 15.25, 'i3.xlarge': 30.5, 'i3.2xlarge': 61, 'i3.4xlarge': 122, 'i3.8xlarge': 244, 'i3.16xlarge': 488,
    'i3.metal': 512,
    'c5.large': 4, 'c5.xlarge': 8, 'c5.2xlarge': 16, 'c5.4xlarge': 32, 'c5.9xlarge': 72, 'c5.18xlarge': 96,
    'c5d.large': 4, 'c5d.xlarge': 8, 'c5d.2xlarge': 16, 'c5d.4xlarge': 32, 'c5d.9xlarge': 72, 'c5d.18xlarge': 144,
    'c5d.24xlarge': 192,
    'r5.large': 16, 'r5.xlarge': 32, 'r5.2xlarge': 64, 'r5.4xlarge': 128, 'r5.8xlarge': 256, 'r5.12xlarge': 384,
    'r5.24xlarge': 768,
    'r5d.large': 16, 'r5d.xlarge': 32, 'r5d.2xlarge': 64, 'r5d.4xlarge': 128, 'r5d.12xlarge': 384, 'r5d.16xlarge': 480,
    'r5d.24xlarge': 768,
    'm5d.large': 8, 'm5d.xlarge': 16, 'm5d.2xlarge': 32, 'm5d.4xlarge': 64, 'm5d.12xlarge': 192, 'm5d.24xlarge': 384,
    'z1d.large': 2, 'z1d.xlarge': 4, 'z1d.2xlarge': 8, 'z1d.3xlarge': 12, 'z1d.6xlarge': 24, 'z1d.12xlarge': 48,
    'r5a.16xlarge': 480, 'r5a.4xlarge': 100,
    'x2gd.16xlarge': 1024, 'x2gd.8xlarge': 512, 'x2gd.metal': 1024, 'hpc6a.48xlarge': 384, 'c6a.24xlarge': 192
}

regions = ['us-east-1', 'us-east-2', 'us-west-2']
shutdown_behaviours = ['stop', 'terminate']
instance_operations = ['start', 'stop', 'terminate']

s3 = boto3.client('s3')
ec2 = None


def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2', region_name=region)


def calculate_max_ram(instance_type):
    # on r5.24xlarge there used to be problems for big simulations with less than 100 Gb left for system
    max_system_ram = 120
    percent_towards_system_ram = .25

    ram = instance_type_to_memory[instance_type]
    return int(ram - min(ram * percent_towards_system_ram, max_system_ram))


def check_resource(bucket, key):
    try:
        s3.head_object(Bucket=bucket, Key=key)
        return True
    except ClientError as clientError:
        print(f"error sending Slack response: {clientError}")
    return False


def check_branch(branch):
    try:
        s3.list_objects_v2(Bucket='beam-builds', Prefix=branch + '/')['Contents']
        return True
    except Exception:
        return False


def get_latest_build(branch):
    get_last_modified = lambda obj: int(obj['LastModified'].strftime('%s'))
    objs = s3.list_objects_v2(Bucket='beam-builds', Prefix=branch + '/')['Contents']
    last_added = [obj['Key'] for obj in sorted(objs, key=get_last_modified, reverse=True)][0]
    return last_added[last_added.rfind('-') + 1:-4]


def validate(name):
    return True


class AWS_Instance_Spec(object):
    name = ""
    cpu = 0
    mem_in_gb = ""

    def __init__(self, name, cpu, mem_in_gb):
        self.name = name
        self.cpu = cpu
        self.mem_in_gb = mem_in_gb


spot_specs = [
    AWS_Instance_Spec('t2.small', 1, 2),
    AWS_Instance_Spec('t2.micro', 1, 1),
    AWS_Instance_Spec('r5dn.large', 2, 16),
    AWS_Instance_Spec('m4.large', 2, 8),
    AWS_Instance_Spec('t3a.medium', 2, 4),
    AWS_Instance_Spec('t3.small', 2, 2),
    AWS_Instance_Spec('t3a.small', 2, 2),
    AWS_Instance_Spec('r5d.large', 2, 16),
    AWS_Instance_Spec('c5n.large', 2, 5.25),
    AWS_Instance_Spec('m5d.large', 2, 8),
    AWS_Instance_Spec('m5.xlarge', 4, 16),
    AWS_Instance_Spec('c5d.xlarge', 4, 8),
    AWS_Instance_Spec('r5d.xlarge', 4, 32),
    AWS_Instance_Spec('r5d.16xlarge', 64, 512),
    AWS_Instance_Spec('m5dn.xlarge', 4, 16),
    AWS_Instance_Spec('c5.xlarge', 4, 8),
    AWS_Instance_Spec('g4dn.xlarge', 4, 16),
    AWS_Instance_Spec('r5.xlarge', 4, 32),
    AWS_Instance_Spec('r4.xlarge', 4, 30.5),
    AWS_Instance_Spec('c5d.xlarge', 4, 8),
    AWS_Instance_Spec('t2.2xlarge', 8, 32),
    AWS_Instance_Spec('t3a.2xlarge', 8, 32),
    AWS_Instance_Spec('g4dn.2xlarge', 8, 32),
    AWS_Instance_Spec('r3.2xlarge', 8, 61),
    AWS_Instance_Spec('i3en.2xlarge', 8, 64),
    AWS_Instance_Spec('m4.2xlarge', 8, 32),
    AWS_Instance_Spec('r5d.2xlarge', 8, 64),
    AWS_Instance_Spec('m5.2xlarge', 8, 32),
    AWS_Instance_Spec('t3.2xlarge', 8, 32),
    AWS_Instance_Spec('r4.2xlarge', 8, 61),
    AWS_Instance_Spec('r5.2xlarge', 8, 64),
    AWS_Instance_Spec('m5d.4xlarge', 16, 64),
    AWS_Instance_Spec('c5d.4xlarge', 16, 32),
    AWS_Instance_Spec('a1.metal', 16, 32),
    AWS_Instance_Spec('g4dn.4xlarge', 16, 64),
    AWS_Instance_Spec('r5.4xlarge', 16, 128),
    AWS_Instance_Spec('c5.4xlarge', 16, 32),
    AWS_Instance_Spec('m5n.4xlarge', 16, 64),
    AWS_Instance_Spec('r5dn.4xlarge', 16, 128),
    AWS_Instance_Spec('i3en.6xlarge', 24, 192),
    AWS_Instance_Spec('r3.8xlarge', 32, 244),
    AWS_Instance_Spec('r5.8xlarge', 32, 256),
    AWS_Instance_Spec('m5.8xlarge', 32, 128),
    AWS_Instance_Spec('r4.8xlarge', 32, 244),
    AWS_Instance_Spec('m5dn.8xlarge', 32, 128),
    AWS_Instance_Spec('r5d.8xlarge', 32, 256),
    AWS_Instance_Spec('m5n.8xlarge', 32, 128),
    AWS_Instance_Spec('r5dn.8xlarge', 32, 256),
    # AWS_Instance_Spec('g4dn.8xlarge',32,128),
    AWS_Instance_Spec('c5d.9xlarge', 36, 72),
    AWS_Instance_Spec('c5n.9xlarge', 36, 96),
    AWS_Instance_Spec('m5dn.12xlarge', 48, 192),
    # AWS_Instance_Spec('i3en.12xlarge',48,384),
    AWS_Instance_Spec('c5.12xlarge', 48, 96),
    AWS_Instance_Spec('r5.12xlarge', 48, 384),
    # AWS_Instance_Spec('g4dn.12xlarge',48,192),
    AWS_Instance_Spec('m5ad.12xlarge', 48, 192),
    AWS_Instance_Spec('r4.16xlarge', 64, 488),
    AWS_Instance_Spec('m5.16xlarge', 64, 256),
    AWS_Instance_Spec('r5dn.16xlarge', 64, 512),
    AWS_Instance_Spec('r5.16xlarge', 64, 512),
    AWS_Instance_Spec('m5dn.16xlarge', 64, 256),
    # AWS_Instance_Spec('g4dn.16xlarge',64,256),
    AWS_Instance_Spec('c5n.18xlarge', 72, 192),
    AWS_Instance_Spec('c5d.18xlarge', 72, 144),
    # AWS_Instance_Spec('c5.18xlarge',72,144),
    AWS_Instance_Spec('r5ad.24xlarge', 96, 768),
    AWS_Instance_Spec('r5.24xlarge', 96, 768),
    AWS_Instance_Spec('m5n.24xlarge', 96, 384),
    # AWS_Instance_Spec('i3en.24xlarge',96,768),
    AWS_Instance_Spec('m5dn.24xlarge', 96, 384),
    # AWS_Instance_Spec('i3en.metal',96,768),
    AWS_Instance_Spec('m5ad.24xlarge', 96, 384),
    AWS_Instance_Spec('x2gd.16xlarge', 64, 1024),
    AWS_Instance_Spec('x2gd.8xlarge', 32, 512),
    AWS_Instance_Spec('x2gd.metal', 64, 1024),
    AWS_Instance_Spec('r5a.16xlarge', 64, 512),
    AWS_Instance_Spec('r5a.4xlarge', 16, 128)
]


def get_spot_fleet_instances_based_on(min_cores, max_cores, min_memory, max_memory, preferred_instance_type):
    output_instance_types = []
    for spec in spot_specs:
        if spec.cpu >= min_cores and spec.cpu <= max_cores and spec.mem_in_gb >= min_memory and spec.mem_in_gb <= max_memory:
            output_instance_types.append(spec.name)
    try:
        if preferred_instance_type:
            output_instance_types.append(preferred_instance_type)
    except NameError:
        print('No preferred spot instance type provided')
    if not output_instance_types:
        raise Exception('0 spot instances matched min_cores: ' + str(min_cores) + ' - max_cores: ' + str(
            max_cores) + 'and min_mem: ' + str(min_memory) + ' - max_mem: ' + str(max_memory))
    return list(dict.fromkeys(output_instance_types))


def deploy_spot_fleet(context, script, instance_type, region_prefix, shutdown_behaviour, instance_name, volume_size,
                      git_user_email, deploy_type_tag, min_cores, max_cores, min_memory, max_memory, budget_override):
    security_group_id_array = (os.environ[region_prefix + 'SECURITY_GROUP']).split(',')
    security_group_ids = []
    for security_group_id in security_group_id_array:
        security_group_ids.append({'GroupId': security_group_id})
    spot_instances = get_spot_fleet_instances_based_on(min_cores, max_cores, min_memory, max_memory, instance_type)
    launch_specifications = []
    for spot_instance in spot_instances:
        specification = {
            'BlockDeviceMappings': [
                {
                    'DeviceName': '/dev/sda1',
                    'Ebs': {
                        'VolumeSize': volume_size,
                        'VolumeType': 'gp2',
                        'DeleteOnTermination': False
                    }
                }
            ],
            'SecurityGroups': security_group_ids,
            'ImageId': os.environ[region_prefix + 'IMAGE_ID'],
            'InstanceType': spot_instance,
            'KeyName': os.environ[region_prefix + 'KEY_NAME'],
            'UserData': base64.b64encode(script.encode("ascii")).decode('ascii'),
            'IamInstanceProfile': {'Name': os.environ['IAM_ROLE']},
            'TagSpecifications': [
                {
                    'ResourceType': 'instance',
                    'Tags': [
                        {
                            'Key': 'Name',
                            'Value': instance_name
                        }, {
                            'Key': 'GitUserEmail',
                            'Value': git_user_email
                        }, {
                            'Key': 'DeployType',
                            'Value': deploy_type_tag
                        }, {
                            'Key': 'BudgetOverride',
                            'Value': budget_override
                        }
                    ]
                }
            ]
        }
        launch_specifications.append(specification)
    spot_fleet_req = ec2.request_spot_fleet(
        SpotFleetRequestConfig={
            'AllocationStrategy': 'lowestPrice',
            'TargetCapacity': 1,
            'IamFleetRole': 'arn:aws:iam::340032650202:role/aws-ec2-spot-fleet-tagging-role',
            'Type': 'request',
            'InstanceInterruptionBehavior': shutdown_behaviour,
            'LaunchSpecifications': launch_specifications
        }
    )
    status = 'pending_fulfillment'
    state = 'submitted'
    spot_fleet_req_id = spot_fleet_req.get('SpotFleetRequestId')
    print('SpotFleetRequestId is ' + spot_fleet_req_id)
    # Flow as far as I know is that state goes to submitted, then active, but isn't done until status is out of pending_fulfillment
    while status == 'pending_fulfillment' or state == 'submitted':
        remaining_time = context.get_remaining_time_in_millis()
        print(
            'Waiting for spot fleet request id to finish pending_fulfillment - Status: ' + status + ' and State: ' + state + ' and Remaining Time (ms): ' + str(
                remaining_time))
        if remaining_time <= 60000:
            ec2.cancel_spot_fleet_requests(
                DryRun=False,
                SpotFleetRequestIds=[spot_fleet_req_id],
                TerminateInstances=True
            )
            print(
                'Waiting 30 seconds to let spot fleet cancel and then shutting down due to getting too close to lambda timeout')
            time.sleep(30)
            exit(123)
        else:
            time.sleep(30)
            spot = ec2.describe_spot_fleet_requests(SpotFleetRequestIds = [spot_fleet_req_id]).get('SpotFleetRequestConfigs')[0]
            status = spot.get('ActivityStatus')
            state = spot.get('SpotFleetRequestState')
    if state != 'active' or status != "fulfilled":
        ec2.cancel_spot_fleet_requests(
            DryRun=False,
            SpotFleetRequestIds=[spot_fleet_req_id],
            TerminateInstances=True
        )
        # TODO: This situation should be ?IMPOSSIBLE? but if it does occur then it could orphan a volume - not worth it unless it becomes an issue
        print(
            'Waiting 30 seconds to let spot fleet cancel and then shutting down due to reaching this point and the state is ' + state + ' and status is ' + status + ' - maybe double check for orphaned volume?')
        time.sleep(30)
        exit(1)
    print('Getting spot fleet instances')
    fleet_instances = ec2.describe_spot_fleet_instances(SpotFleetRequestId=spot_fleet_req_id)
    fleet_instance = fleet_instances.get('ActiveInstances')[0]  # TODO: Check if InstanceHealth is healthy vs unhealthy?
    bd_count = 0
    instance_id = fleet_instance.get('InstanceId')
    while bd_count < 1:
        instance = ec2.describe_instances(InstanceIds=[instance_id]).get('Reservations')[0].get('Instances')[0]
        bd_count = len(instance.get('BlockDeviceMappings'))
        if bd_count < 1:
            remaining_time = context.get_remaining_time_in_millis()
            print(
                'Spot request state now ' + state + ' and status ' + status + ' so getting instance using ' + instance_id + ' and Remaining Time (ms): ' + str(
                    remaining_time))
            if remaining_time <= 60000:
                ec2.cancel_spot_fleet_requests(
                    DryRun=False,
                    SpotFleetRequestIds=[spot_fleet_req_id],
                    TerminateInstances=True
                )
                # TODO: Since there is no block device yet then we cannot terminate that instance - this COULD result in orphaned volumes - but they would be named at least...handle with a cloud watch if it becomes an issue
                print(
                    'Waiting 30 seconds to let spot fleet cancel and then shutting down due to getting too close to lambda timeout')
                time.sleep(30)
                exit(123)
            else:
                print('Sleeping 30 seconds to let instance volumes spin up (most likely this will never occur)')
                time.sleep(30)
    print('Instance up with block device ready')
    volume_id = instance.get('BlockDeviceMappings')[0].get('Ebs').get('VolumeId')
    ec2.create_tags(
        Resources=[volume_id],
        Tags=[
            {
                'Key': 'Name',
                'Value': instance_name
            }, {
                'Key': 'GitUserEmail',
                'Value': git_user_email
            }, {
                'Key': 'DeployType',
                'Value': deploy_type_tag
            }])
    print('Created tags on volume')
    while instance.get('State') == 'pending':
        instance = ec2.describe_instances(InstanceIds=[instance_id]).get('Reservations')[0].get('Instances')[0]
        state = instance.get('State')
        if state == 'pending':
            remaining_time = context.get_remaining_time_in_millis()
            print(
                'Spot instance state now ' + state + ' and instance id is ' + instance_id + ' and Remaining Time (ms): ' + str(
                    remaining_time))
            if remaining_time <= 45000:
                print(
                    'Returning the instance id because about to timeout and the instance is spinning up - just not fully - no need to cancel')
                return instance_id
            else:
                print('Waiting for instance to leave pending')
                time.sleep(30)
    print('Spot instance ready to go!')
    return instance_id


def deploy(script, instance_type, region_prefix, shutdown_behaviour, instance_name, volume_size, git_user_email,
           deploy_type_tag, budget_override):
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
        TagSpecifications=[
            {
                'ResourceType': 'instance',
                'Tags': [
                    {
                        'Key': 'Name',
                        'Value': instance_name
                    }, {
                        'Key': 'GitUserEmail',
                        'Value': git_user_email
                    }, {
                        'Key': 'DeployType',
                        'Value': deploy_type_tag
                    }, {
                        'Key': 'BudgetOverride',
                        'Value': str(budget_override)
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


def deploy_handler(event, context):
    missing_mandatory_parameters = []

    def parameter_wasnt_specified(parameter_value):
        # in gradle if parameter wasn't specified then project.findProperty return 'null'
        return parameter_value is None or parameter_value == 'null'

    def get_mandatory_param(param_name):
        param_value = event.get(param_name)
        if parameter_wasnt_specified(param_value):
            missing_mandatory_parameters.append(param_name)
        return param_value

    branch = event.get('branch', BRANCH_DEFAULT)
    commit_id = event.get('commit', COMMIT_DEFAULT)
    data_branch = event.get('data_branch', DATA_BRANCH_DEFAULT)
    data_commit = event.get('data_commit', DATA_COMMIT_DEFAULT)
    deploy_mode = event.get('deploy_mode', 'config')
    configs = event.get('configs', CONFIG_DEFAULT)
    experiments = event.get('experiments', EXPERIMENT_DEFAULT)
    execute_class = event.get('execute_class', EXECUTE_CLASS_DEFAULT)
    execute_args = event.get('execute_args', EXECUTE_ARGS_DEFAULT)
    s3_publish = event.get('s3_publish', True)
    volume_size = event.get('storage_size', 64)
    shutdown_wait = event.get('shutdown_wait', SHUTDOWN_DEFAULT)
    sigopt_client_id = event.get('sigopt_client_id', os.environ['SIGOPT_CLIENT_ID'])
    sigopt_dev_id = event.get('sigopt_dev_id', os.environ['SIGOPT_DEV_ID'])
    google_api_key = event.get('google_api_key', os.environ['GOOGLE_API_KEY'])
    end_script = event.get('end_script', END_SCRIPT_DEFAULT)
    run_grafana = event.get('run_grafana', False)
    cosimulation_shell_script = event.get('cosimulation_shell_script', '')
    run_jupyter = event.get('run_jupyter', False)
    jupyter_token = event.get('jupyter_token', '')
    jupyter_image = event.get('jupyter_image', '')

    profiler_type = event.get('profiler_type', 'null')
    budget_override = event.get('budget_override', False)

    git_user_email = get_mandatory_param('git_user_email')
    deploy_type_tag = event.get('deploy_type_tag', '')
    titled = get_mandatory_param('title')
    instance_type = event.get('instance_type')
    region = get_mandatory_param('region')
    shutdown_behaviour = get_mandatory_param('shutdown_behaviour')
    is_spot = event.get('is_spot', False)
    run_beam = event.get('run_beam', True)

    # for beam stuck guard shell script
    stuck_guard_min_cpu_usage = event.get('stuck_guard_min_cpu_usage', DEFAULT_STUCK_GUARD_MIN_CPU_USAGE)
    stuck_guard_max_inactive_time_interval = event.get('stuck_guard_max_inactive_time_interval', DEFAULT_STUCK_GUARD_MAX_INACTIVE_TIME_INTERVAL)

    if missing_mandatory_parameters:
        return "Unable to start, missing parameters: " + ", ".join(missing_mandatory_parameters)

    if not instance_type and not is_spot:
        return "Unable to start, missing instance_type AND is NOT a spot request"

    if not is_spot and instance_type not in instance_type_to_memory:
        return "Unable to start run, {instance_type} instance type not supported.".format(instance_type=instance_type)

    if shutdown_behaviour not in shutdown_behaviours:
        return "Unable to start run, {shutdown_behaviour} shutdown behaviour not supported.".format(
            shutdown_behaviour=shutdown_behaviour)

    if region not in regions:
        return "Unable to start run, {region} region not supported.".format(region=region)

    if volume_size < 64 or volume_size > 256:
        volume_size = 64

    max_ram = event.get('forced_max_ram')
    if parameter_wasnt_specified(max_ram):
        max_ram = calculate_max_ram(instance_type)

    selected_script = CONFIG_SCRIPT
    if run_grafana:
        selected_script = GRAFANA_RUN + selected_script

    if cosimulation_shell_script:
        for start_path in ['src/main/bash','main/bash', 'bash']:
            if cosimulation_shell_script.startswith(start_path):
                cosimulation_shell_script = cosimulation_shell_script[len(start_path):]

        full_path_to_cosimulation_script = f"/home/ubuntu/git/beam/src/main/bash/{cosimulation_shell_script}"
        selected_script = f'sudo chmod +x {full_path_to_cosimulation_script}; sudo {full_path_to_cosimulation_script}; {selected_script}'

    params = configs
    if s3_publish:
        selected_script += S3_PUBLISH_SCRIPT

    if deploy_mode == 'experiment':
        selected_script = EXPERIMENT_SCRIPT
        params = experiments

    # split the beamConfigs into an array
    params = params.split(',')

    if deploy_mode == 'execute':
        selected_script = EXECUTE_SCRIPT
        params = ['"{args}"'.format(args=execute_args)]

    if end_script != END_SCRIPT_DEFAULT:
        end_script = '/home/ubuntu/git/beam/sec/main/bash/' + end_script

    txt = ''

    init_ec2(region)

    if validate(branch) and validate(commit_id):
        runNum = 1
        for arg in params:
            runName = titled
            if len(params) > 1:
                runName += "-" + str(runNum)
            script = initscript.replace('$RUN_SCRIPT', selected_script) \
                .replace('$REGION', region) \
                .replace('$S3_REGION', os.environ['REGION']) \
                .replace('$BRANCH', branch) \
                .replace('$COMMIT', commit_id) \
                .replace('$DATA_BRANCH', data_branch) \
                .replace('$DATA_COMMIT', data_commit) \
                .replace('$CONFIG', arg) \
                .replace('$MAIN_CLASS', execute_class) \
                .replace('$SHUTDOWN_WAIT', str(shutdown_wait)) \
                .replace('$TITLED', runName) \
                .replace('$MAX_RAM', str(max_ram)) \
                .replace('$S3_PUBLISH', str(s3_publish)) \
                .replace('$SIGOPT_CLIENT_ID', sigopt_client_id).replace('$SIGOPT_DEV_ID', sigopt_dev_id) \
                .replace('$GOOGLE_API_KEY', google_api_key) \
                .replace('$PROFILER', profiler_type) \
                .replace('$END_SCRIPT', end_script) \
                .replace('$SLACK_HOOK_WITH_TOKEN', os.environ['SLACK_HOOK_WITH_TOKEN']) \
                .replace('$SLACK_TOKEN', os.environ['SLACK_TOKEN']) \
                .replace('$SLACK_CHANNEL', os.environ['SLACK_CHANNEL']) \
                .replace('$STUCK_GUARD_MAX_INACTIVE_TIME_INTERVAL', str(stuck_guard_max_inactive_time_interval)) \
                .replace('$STUCK_GUARD_MIN_CPU_USAGE', str(stuck_guard_min_cpu_usage)) \
                .replace('$RUN_JUPYTER', str(run_jupyter)) \
                .replace('$RUN_BEAM', str(run_beam)) \
                .replace('$JUPYTER_TOKEN', jupyter_token) \
                .replace('$JUPYTER_IMAGE', jupyter_image)
            if is_spot:
                min_cores = event.get('min_cores', 0)
                max_cores = event.get('max_cores', 0)
                min_memory = event.get('min_memory', 0)
                max_memory = event.get('max_memory', 0)
                instance_id = deploy_spot_fleet(context, script, instance_type, region.replace("-", "_") + '_',
                                                shutdown_behaviour, runName, volume_size, git_user_email,
                                                deploy_type_tag, min_cores, max_cores, min_memory, max_memory,
                                                budget_override)
            else:
                instance_id = deploy(script, instance_type, region.replace("-", "_") + '_', shutdown_behaviour, runName,
                                     volume_size, git_user_email, deploy_type_tag, budget_override)
            host = get_dns(instance_id)

            if run_beam:
                txt += 'Started simulation with run name: {titled} for branch/commit {branch}/{commit} at host {dns} (InstanceID: {instance_id}). '.format(
                    branch=branch, titled=runName, commit=commit_id, dns=host, instance_id=instance_id)

            if run_grafana:
                txt += ' Grafana will be available at http://{dns}:3003/d/dvib8mbWz/beam-simulation-global-view.'.format(
                    dns=host)

            if cosimulation_shell_script:
                txt += f' Cosimulation shell script ({cosimulation_shell_script}) will be run in parallel with BEAM.'

            if run_jupyter and run_beam:
                txt += ' Jupyter will be run in parallel with BEAM. Url: http://{dns}:8888/?token={token}'.format(
                    dns=host, token=jupyter_token)

            if run_jupyter and not run_beam:
                txt += ' Jupyter is starting. Url: http://{dns}:8888/?token={token}'.format(dns=host,
                                                                                            token=jupyter_token)

            runNum += 1
    else:
        txt = 'Unable to start bach for branch/commit {branch}/{commit}. '.format(branch=branch, commit=commit_id)

    return txt


def instance_handler(event):
    region = event.get('region')
    instance_ids = event.get('instance_ids')
    command_id = event.get('command')
    system_instances = os.environ['SYSTEM_INSTANCES']

    if region not in regions:
        return "Unable to {command} instance(s), {region} region not supported.".format(command=command_id,
                                                                                        region=region)

    init_ec2(region)

    system_instances = system_instances.split(',')
    instance_ids = instance_ids.split(',')
    invalid_ids = check_instance_id(list(instance_ids))
    valid_ids = [item for item in instance_ids if item not in invalid_ids]
    allowed_ids = [item for item in valid_ids if item not in system_instances]

    if command_id == 'start':
        start_instance(allowed_ids)
        return "Started instance(s) {insts}.".format(
            insts=', '.join([': '.join(inst) for inst in zip(allowed_ids, list(map(get_dns, allowed_ids)))]))

    if command_id == 'stop':
        stop_instance(allowed_ids)

    if command_id == 'terminate':
        terminate_instance(allowed_ids)

    return "Instantiated {command} request for instance(s) [ {ids} ]".format(command=command_id,
                                                                             ids=",".join(allowed_ids))


def lambda_handler(event, context):
    command_id = event.get('command', 'deploy')  # deploy | start | stop | terminate | log

    logger.info("Incoming event: " + str(event))
    logger.info("Lambda function ARN:" + str(context.invoked_function_arn))
    logger.info("Lambda function ARN:" + str(context.identity))

    if command_id == 'deploy':
        return deploy_handler(event, context)

    if command_id in instance_operations:
        return instance_handler(event)

    return "Operation {command} not supported, please specify one of the supported operations (deploy | start | stop | terminate | log). ".format(
        command=command_id)
