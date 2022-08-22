# coding=utf-8
import os
import logging
import re
import json
import urllib.parse
import http.client

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def lambda_handler(event, context):
    budget_message = json.loads(str(event.get('Records')[0]).replace("'",'"')).get('Sns').get('Message')
    budget_name = re.findall(".*Budget Name: (.*)", budget_message)[0]
    budget_limit = re.findall("Budgeted Amount: (.*)", budget_message)[0]
    budget_threshold = re.findall("Alert Threshold: (.*)", budget_message)[0]
    budget_actual = re.findall("ACTUAL Amount: (.*)", budget_message)[0]
    budget_name_url_encoded = urllib.parse.quote(budget_name)
    budget_link = f"https://console.aws.amazon.com/billing/home?#/budgets/details?name={budget_name_url_encoded}"
    slack_message = f"<!here> <{budget_link}|*Alert triggered for '{budget_name}'*>\n> The current amount spent so far this month is {budget_actual}\n> *Budget Threshold*:\n> {budget_threshold}\n> *Budget limit:*\n> {budget_limit}"
    headers = {'Content-type': 'application/json'}
    payload = {
        "blocks": [
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": slack_message
                }
            }
        ]
    }
    slack_hook = os.environ['SLACK_HOOK']
    logger.info('Slack notification for budget alert: ' + str(payload))
    conn = http.client.HTTPSConnection('hooks.slack.com')
    conn.request('POST', slack_hook, json.dumps(payload), headers)
    response = conn.getresponse()
    logger.info('Received response from slack notification: ' + response.read().decode())
    return 'Done'