# coding=utf-8
import json
import requests
import logging
import os
import re

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def lambda_handler(event, context):
    headers = {'Content-type': 'application/json'}

    instance_region_regex_extractor = '(.*?)_(.*?)_.*'
    alarm_name = get_alarm_name_from(event)
    instance_and_region = safe_index_with_default(re.findall(instance_region_regex_extractor, alarm_name), 0, ('NA', 'NA'))
    logger.info('instance_and_region: ' + str(instance_and_region))
    instance = safe_index_with_default(instance_and_region, 0, 'NA')
    region = safe_index_with_default(instance_and_region, 1, 'NA')
    subject = get_subject_from(event)
    logger.info('instance: ' + instance + '; region: ' + region + '; subject:' + subject)
    payload = {
        "blocks": [
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": f"*EC2 Idle Alarm Triggered*\n> *Alarm Name*\n> {alarm_name}\n> *Trigger Subject*\n> {subject}\n> *Link to Alarm*\n> https://console.aws.amazon.com/cloudwatch/home?region={region}#alarmsV2:alarm/{alarm_name}\n> *Link to Instance*\n> https://console.aws.amazon.com/ec2/home?region={region}#Instances:instanceId={instance}"
                }
            }
        ]
    }
    slack_hook = os.environ['SLACK_HOOK']
    logger.info('Sending slack notification about idle instance with payload: ' + str(payload))
    response = requests.post(slack_hook, data=json.dumps(payload), headers=headers)
    logger.info('Received response from slack notification about idle instance with status code: ' + str(response.status_code) + ' and content: ' + str(response.content))

    return json.dumps({})

def get_alarm_name_from(event):
    records = safe_get(event, 'Records')
    first_record = safe_index(records, 0)
    sns = safe_get(first_record, 'Sns')
    message = safe_get_with_default(sns, 'Message', '{}')
    message_as_dict = json.loads(message)
    return safe_get_with_default(message_as_dict, 'AlarmName', 'NA_NA_')

def get_subject_from(event):
    records = safe_get(event, 'Records')
    first_record = safe_index(records, 0)
    sns = safe_get(first_record, 'Sns')
    return safe_get_with_default(sns, 'Subject', 'NA')

def safe_get(dict_obj, key):
    if dict_obj is not None:
        return dict_obj.get(key)
    return None

def safe_get_with_default(dict_obj, key, default):
    value = safe_get(dict_obj, key)
    if value is None:
        return default
    return value

def safe_index(list_obj, index):
    if list_obj is not None:
        if len(list_obj) >= index + 1:
            return list_obj[index]
    return None

def safe_index_with_default(list_obj, index, default):
    value = safe_index(list_obj, index)
    if value is None:
        return default
    return value