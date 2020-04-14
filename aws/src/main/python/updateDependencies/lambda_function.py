# coding=utf-8
import os
import boto3
from botocore.errorfactory import ClientError

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
  - sudo git checkout develop
  - sudo git pull
  - sudo git fetch
  - sudo git fetch --prune
  - sudo git remote prune origin
  - echo "-git remote prune origin is done-"
  - sudo chown -R ubuntu:ubuntu /home/ubuntu/git/beam
  - for bn in $BRANCH
  -  do
  -    echo "-------------------checkout $bn----------------------"
  -    sudo GIT_LFS_SKIP_SMUDGE=1 git checkout $bn
  -    sudo git reset --hard origin/$bn
  -    sudo git pull
  -    sudo git lfs pull
  -  done
  - sudo chown -R ubuntu:ubuntu /home/ubuntu/git/beam
  - echo "gradlew assemble ..."
  - ./gradlew assemble
  - ./gradlew clean
  - echo "preparing for python analysis"
  - sudo dpkg --configure -a
  - sudo dpkg --remove --force-remove-reinstreq  unattended-upgrades
  - sudo apt-get install unattended-upgrades
  - sudo dpkg --configure -a
  - sudo apt update
  - sudo apt install npm -y
  - sudo apt install nodejs-legacy -y
  - sudo apt install python-pip -y
  - pip install --upgrade pip
  - sudo pip install pandas
  - sudo pip install plotly
  - sudo pip install psutil requests
  - sudo npm cache clean -f
  - sudo npm install -g n
  - sudo n stable
  - sudo npm install -g npm
  - sudo apt-get install curl
  - curl -sL https://deb.nodesource.com/setup_8.x | sudo -E bash -
  - sudo apt-get install nodejs -y
  - sudo apt-get install docker-ce docker-ce-cli containerd.io -y
  - sudo curl -L "https://github.com/docker/compose/releases/download/1.23.2/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
  - sudo chmod +x /usr/local/bin/docker-compose
  - sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose
  - sudo apt-get install libgtkextra-dev libgconf2-dev libnss3 libasound2 libxtst-dev -y
  - sudo npm install -g electron@1.8.4 orca --unsafe-perm=true --alow-root -y
  - sudo apt-get install xvfb -y
  - 'echo resetting git to base: "$(date)"'
  - sudo git reset --hard 
  - 'echo fetching the latest: "$(date)"'
  - sudo git fetch
  - 'echo current git status: "$(date)"'
  - sudo git status
  - sudo apt install jq -y
  - 'echo invoke create ami lambda after a 5 minute sleep to let the file system settle..."$(date)"'
  - sudo sleep 5m
  - sudo aws lambda invoke --invocation-type RequestResponse --function-name createAMI --region 'us-east-2' --payload '{"instance_id":"'"$(ec2metadata --instance-id)"'","region_id":"us-east-2"}' outputfile.txt
  - export created_ami_id=$(sed -r 's/"(.*)\|.*/\\1/' outputfile.txt)
  - export ami_filename=$(sed -r 's/.*\|(.*)"/\\1/' outputfile.txt)
  - while ! aws ec2 describe-images --image-ids $created_ami_id --region us-east-2 | grep "available"; do echo "Waiting 30 seconds for AMI in us-east-2 $created_ami_id ..."; sleep 30s; done
  - echo "invoke copy ami lambda to us-east-1 ..."
  - sudo aws lambda invoke --invocation-type RequestResponse --function-name cloneAMI --region 'us-east-2' --payload '{"ami_id":"'"$created_ami_id"'","ami_name":"'"$ami_filename"'","region":"us-east-1"}' outputfile.txt
  - export created_ami_id_us_east_1=$(sed -r 's/"(.*)"/\\1/' outputfile.txt)
  - echo "invoke copy ami lambda to us-west-2 ..."
  - sudo aws lambda invoke --invocation-type RequestResponse --function-name cloneAMI --region 'us-east-2' --payload '{"ami_id":"'"$created_ami_id"'","ami_name":"'"$ami_filename"'","region":"us-west-2"}' outputfile.txt
  - export created_ami_id_us_west_2=$(sed -r 's/"(.*)"/\\1/' outputfile.txt)
  - while ! aws ec2 describe-images --image-ids $created_ami_id_us_east_1 --region us-east-1 | grep "available"; do echo "Waiting 30 seconds for AMI in us-east-1 $created_ami_id_us_east_1 ..."; sleep 30s; done
  - while ! aws ec2 describe-images --image-ids $created_ami_id_us_west_2 --region us-west-2 | grep "available"; do echo "Waiting 30 seconds for AMI in us-west-2 $created_ami_id_us_west_2 ..."; sleep 30s; done
  - echo "invoke update provided lambda functions environment variables..."
  - sudo aws lambda invoke --invocation-type RequestResponse --function-name updateEnvVarsForProvidedFunctionNames --region 'us-east-2' --payload '{"function_names":["simulateBeam","runPilates"], "ami_id":"'"$created_ami_id"'","ami_id_us_east_1":"'"$created_ami_id_us_east_1"'","ami_id_us_west_2":"'"$created_ami_id_us_west_2"'"}' outputfile.txt
  - echo "setting up auto shutdown ..."
  - sudo shutdown -h +$SHUTDOWN_WAIT
  - echo "shutdown in $SHUTDOWN_WAIT ..."
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
    shutdown_behaviour = 'terminate'
    shutdown_wait = "10"
    runName = 'update-beam-dependencies'
    branches = os.environ['BRANCHES']

    script = initscript.replace('$BRANCH', branches).replace('$SHUTDOWN_WAIT', shutdown_wait)

    init_ec2(region)
    instance_id = deploy(script, instance_type, region.replace("-", "_")+'_', shutdown_behaviour, runName, en_vars)

    return instance_id
