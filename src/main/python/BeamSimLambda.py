# coding=utf-8
import boto3
import time
import uuid
import os
from botocore.errorfactory import ClientError

initscript = '''#cloud-config
runcmd:
  - echo "-------------------Starting Beam Sim----------------------"
  - echo $(date +%s) > /tmp/.starttime
  - /home/ubuntu/git/glip.sh -i "http://icons.veryicon.com/256/Internet%20%26%20Web/Socialmedia/AWS.png" -a "$(ec2metadata --instance-type) instance $(ec2metadata --instance-id) started..." -b "An EC2 instance started to run the batch [$UID] on branch / commit [$BRANCH / $COMMIT]."
  - echo "notification sent..."
  - echo '*/2 * * * * /home/ubuntu/git/glip.sh -i "http://icons.veryicon.com/256/Internet%20%26%20Web/Socialmedia/AWS.png" -a "$(ec2metadata --instance-type) instance $(ec2metadata --inance-id) running..." -b "Batch [$UID] completed and instance still running since last $(($(($(date +%s) - $(cat /tmp/.starttime))) / 3600)) Hour $(($(($(date +%s) - $(cat /tmp/.starttime))) / 60)) Minute."' > /tmp/glip_notification
  - echo "notification sved..."
  - crontab /tmp/glip_notification
  - crontab -l
  - echo "notification scheduled..."
  - cd /home/ubuntu/git/beam
  - git checkout $BRANCH
  - git fetch --all
  - git checkout -qf $COMMIT
  - for cf in $CONFIG
  -  do
  -    echo "-------------------running $cf----------------------"
  -    ./gradlew --stacktrace run -PappArgs="['--config', '$cf']" 
  -    sleep 10s
  -    for file in test/output/*; do sudo zip -r "${file%.*}_$UID.zip" "$file"; done;
  -    sudo aws --region "$REGION" s3 cp test/output/*.zip s3://beam-outputs/
  -    rm -rf test/output/*
  -  done
  - sudo shutdown -h +$SHUTDOWN_WAIT
'''

instance_types = ['t2.nano', 't2.micro', 't2.small', 't2.medium', 't2.large', 't2.xlarge', 't2.2xlarge',
                  'm4.large', 'm4.xlarge', 'm4.2xlarge', 'm4.4xlarge', 'm4.10xlarge', 'm4.16xlarge',
                  'm3.medium', 'm3.large', 'm3.xlarge', 'm3.2xlarge']

# webhook_url = 'https://hooks.glip.com/webhook/c6624e26-846b-4146-8cf8-acde9c43240c'
# msg_payload = '''{
#   "icon": "http://icons.veryicon.com/256/Internet%20%26%20Web/Socialmedia/AWS.png",
#   "activity": "EC2 Status",
#   "title": "Batch started",
#   "body": "Instance started to run the batch."
# }'''

s3 = boto3.client('s3')
ec2 = boto3.client('ec2',region_name=os.environ['REGION'])

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

def deploy(script, instance_type):
    res = ec2.run_instances(ImageId=os.environ['IMAGE_ID'],
                            InstanceType=instance_type,
                            UserData=script,
                            KeyName=os.environ['KEY_NAME'],
                            MinCount=1,
                            MaxCount=1,
                            SecurityGroupIds=[ os.environ['SECURITY_GROUP'] ],
                            IamInstanceProfile={'Name': os.environ['IAM_ROLE'] },
                            InstanceInitiatedShutdownBehavior='terminate')
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
    branch = event.get('branch', 'master')
    commit_id = event.get('commit', 'HEAD')
    configs = event.get('configs', 'production/application-sfbay/base.conf')
    instance_type = event.get('instance_type')
    batch = event.get('batch', 'true')
    shutdown_wait = event.get('shutdown_wait', '30')

    if instance_type is None or instance_type not in instance_types:
        instance_type = os.environ['INSTANCE_TYPE']

    if batch == 'true':
        configs = [ configs ]
    else:
        configs = configs.split(' ')

    txt = ''

    if validate(branch) and validate(commit_id):
        for cfg in configs:
            uid = str(uuid.uuid4())[:8]
            script = initscript.replace('$REGION',os.environ['REGION']).replace('$BRANCH',branch).replace('$COMMIT', commit_id).replace('$CONFIG', cfg).replace('$UID', uid).replace('$SHUTDOWN_WAIT', shutdown_wait)
            instance_id = deploy(script, instance_type)
            host = get_dns(instance_id)
            txt = txt + 'Started batch: {batch} for branch/commit {branch}/{commit} at host {dns}. \n'.format(branch=branch, commit=commit_id, dns=host, batch=uid)
            # response = requests.post(
            #     webhook_url, data=msg_payload,
            #     headers={'Content-Type': 'application/json'}
            # )
            # if response.status_code != 200:
            #     raise ValueError(
            #         'Request to slack returned an error %s, the response is:\n%s'
            #         % (response.status_code, response.text)
            #     )
    else:
        txt = 'Unable to start bach for branch/commit {branch}/{commit}.'.format(branch=branch, commit=commit_id)

    return txt