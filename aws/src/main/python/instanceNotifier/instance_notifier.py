# coding=utf-8
import boto3
import os
from datetime import date
from calendar import monthrange
from decimal import Decimal
import json
import logging
import http.client
from pkg_resources import resource_filename
from botocore.errorfactory import ClientError

ec2_client = None
budget_client = None
pricing_client = None

logger = logging.getLogger()
logger.setLevel(logging.INFO)

PRODUCT_OPERATING_SYSTEMS = ["Linux","RHEL","Windows","SUSE","Red Hat Enterprise Linux with HA","Windows"]

# Search product filter. This will reduce the amount of data returned by the
# get_products function of the Pricing API
FLT = '[{{"Field": "tenancy", "Value": "shared", "Type": "TERM_MATCH"}},' \
      '{{"Field": "preInstalledSw", "Value": "NA", "Type": "TERM_MATCH"}},' \
      '{{"Field": "operatingSystem", "Value": "{o}", "Type": "TERM_MATCH"}},' \
      '{{"Field": "instanceType", "Value": "{t}", "Type": "TERM_MATCH"}},' \
      '{{"Field": "location", "Value": "{r}", "Type": "TERM_MATCH"}},' \
      '{{"Field": "capacitystatus", "Value": "Used", "Type": "TERM_MATCH"}}]'

class EC2Instance:
    def __init__(self, instance_id, instance_type, operating_system, instance_name, instance_git_user_email, budget_override):
        self.instance_id = instance_id
        self.instance_type = instance_type
        self.operating_system = operating_system
        self.instance_name = instance_name
        self.instance_git_user_email = instance_git_user_email
        self.budget_override = budget_override.lower()

    def __str__(self):
        return 'InstanceId: ' + self.instance_id + '; Instance Type: ' + self.instance_type + '; Operating System: ' + self.operating_system + '; Instance Name: ' + self.instance_name + '; Instance Git User Email: ' + self.instance_git_user_email + '; Budget Override: ' + self.budget_override

def init_ec2_client(region):
    global ec2_client
    ec2_client = boto3.client('ec2',region_name=region)

def init_budget_client():
    global budget_client
    budget_client = boto3.client('budgets')

def init_pricing_client(region):
    global pricing_client
    pricing_client = boto3.client('pricing', region_name=region)

def convert_to_pricing_operating_system_from(ec2_operating_system):
    for pricing_os in PRODUCT_OPERATING_SYSTEMS:
        if pricing_os in ec2_operating_system:
            return pricing_os
    return "Linux" # Default to Linux since that is mostly what we use anyway

# Translate region code to region name. Even though the API data contains
# regionCode field, it will not return accurate data. However using the location
# field will, but then we need to translate the region code into a region name.
# You could skip this by using the region names in your code directly, but most
# other APIs are using the region code.
def get_region_name(region_code):
    default_region = 'US East (N. Virginia)'
    endpoint_file = resource_filename('botocore', 'data/endpoints.json')
    try:
        with open(endpoint_file, 'r') as f:
            data = json.load(f)
        # Botocore is using Europe while Pricing API using EU...sigh...
        return data['partitions'][0]['regions'][region_code]['description'].replace('Europe', 'EU')
    except IOError:
        return default_region

def get_price(region, instance, os):
    f = FLT.format(r=region, t=instance, o=os)
    data = pricing_client.get_products(ServiceCode='AmazonEC2', Filters=json.loads(f))
    priceList = data.get('PriceList')
    od = json.loads(data['PriceList'][0])['terms']['OnDemand']
    id1 = list(od)[0]
    id2 = list(od[id1]['priceDimensions'])[0]
    return od[id1]['priceDimensions'][id2]['pricePerUnit']['USD']

def get_instance_details_for_(instance_id):
    instance_details = ec2_client.describe_instances(InstanceIds=[instance_id])
    instance = instance_details.get("Reservations")[0].get("Instances")[0]
    instance_type = instance.get("InstanceType")
    operating_system = instance.get("PlatformDetails")
    instance_git_user_email = ""
    instance_name = ""
    budget_override = ""
    tags = instance.get("Tags")
    logger.info(f"Tags from instance: {tags}")
    for tag in tags:
        key = tag.get("Key")
        if key == "GitUserEmail":
            instance_git_user_email = tag.get("Value")
        elif key == "Name":
            instance_name = tag.get("Value")
        elif key == "BudgetOverride":
            budget_override = tag.get("Value")
    return EC2Instance(instance_id, instance_type, operating_system, instance_name, instance_git_user_email, budget_override)

def notify_slack_using_(message):
    logger.info('Notifying about attempted instance use')
    headers = {'Content-type': 'application/json'}
    payload = {
        "blocks": [
            {
                "type": "section",
                "text": {
                    "type": "mrkdwn",
                    "text": message
                }
            }
        ]
    }
    slack_hook = os.environ['SLACK_HOOK']
    logger.info('Slack notification for stopped instance: ' + str(payload))
    conn = http.client.HTTPSConnection('hooks.slack.com')
    conn.request('POST', slack_hook, json.dumps(payload), headers)
    response = conn.getresponse()
    logger.info('Received response from slack notification for attempted instance use: ' + response.read().decode())

def stop_instance(instance_id):
    logger.info(f"Stopping {instance_id}")
    ec2_client.stop_instances(InstanceIds=[instance_id])

def delete_override_tag__for_(instance_id):
    logger.info(f"Removing BudgetOverride tag for {instance_id}")
    ec2_client.delete_tags(
        Resources=[instance_id],
        Tags=[
            {
                'Key': 'BudgetOverride'
            }
        ]
    )

# Expected format: {"instance-id": <instance-id>, "state": <state>, "region": <region>, "account-id": <account-id>}
def lambda_handler(event, context):
    logger.info(f"Received event: {event}")
    state = event.get('state')
    if state != "running" and state != "stopped":
        return "Instance state change of {state} is not supported for this lambda - only 'running' or 'stopped' is considered".format(state=state)
    region = event.get('region')
    instance_id = event.get('instance-id')
    account_id = event.get('account-id')

    init_ec2_client(region)
    if state == "stopped":
        delete_override_tag_for_(instance_id)
        return "Stopped instance had any budget override tags removed"

    allowed_instances = os.environ['ALLOWED_INSTANCES']
    if instance_id in allowed_instances:
        logger.info(f"Allowing {instance_id} to run")
        return "Done"

    init_budget_client()
    budget_response = budget_client.describe_budget(
        AccountId=account_id,
        BudgetName=os.environ["BUDGET_NAME"]
    )

    budget_details = budget_response.get('Budget')
    budget_limit_amount = Decimal(budget_details.get('BudgetLimit').get('Amount'))
    current_spent_amount = Decimal(budget_details.get('CalculatedSpend').get('ActualSpend').get('Amount'))
    percent_of_budget_used = (current_spent_amount / budget_limit_amount) * 100

    # 150%+ require tag - notify slack if blocked
    if percent_of_budget_used <= 150:
        return "Budget is not over 150%. Stopping check."

    today = date.today()
    current_day = Decimal(today.strftime("%d"))
    current_month = today.strftime("%m")
    current_year = today.strftime("%Y")
    days_in_current_month = monthrange(int(current_year), int(current_month))[1]
    percent_of_month = (current_day / Decimal(days_in_current_month)) * 100

    # LATER TODO: Add link to budget?
    # LATER TODO: Add an override option that is not emptied (true_always?) or for # of starts (true_3? - decrement and update tag)
    # LATER TODO: Add a link to trigger an automatic approval. Maybe link to instance is enough, since can tag there?
    # LATER TODO: Create a report of all usages and cost and send as a daily/weekly? email
    instance_details = get_instance_details_for_(instance_id)

    logger.info(f"Instance details: {instance_details}")

    instance_stopped = False
    if percent_of_budget_used >= 300:
        stop_instance(instance_id)
        instance_stopped = True
    elif percent_of_budget_used >= 150 and instance_details.budget_override.lower() != "true":
        stop_instance(instance_id)
        instance_stopped = True

    forecasted_spend_amount = Decimal(budget_details.get('CalculatedSpend').get('ForecastedSpend').get('Amount'))
    init_pricing_client("us-east-1")
    price_per_hour = Decimal(get_price(get_region_name("us-east-1"), instance_details.instance_type, convert_to_pricing_operating_system_from(instance_details.operating_system)))
    price_per_day = price_per_hour * 24
    instance_name = instance_details.instance_name
    instance_git_user_email = instance_details.instance_git_user_email
    instance_link = f"https://console.aws.amazon.com/ec2/home?region={region}#Instances:instanceId={instance_id}"

    number_of_days_left_in_month = days_in_current_month - current_day
    instance_cost_for_rest_of_month = price_per_day * number_of_days_left_in_month
    spend_amount_if_instance_rest_of_month = current_spent_amount + instance_cost_for_rest_of_month

    if instance_stopped:
        slack_message = f"*Instance with ID '{instance_id}' immediately stopped*\nThe current amount spent this month (${str(round(current_spent_amount, 2))}) is {str(round(percent_of_budget_used, 2))}% of the budgeted amount (${str(round(budget_limit_amount, 2))}).\n*Instance Name*:\n{instance_name}\n*Git email of who created the instance*:\n{instance_git_user_email}\n*Forecasted amount to be spent this month*: \n${str(round(forecasted_spend_amount, 2))}\n*Percent of month completed*:\n{str(round(percent_of_month, 2))}%\n*Instance hourly cost*:\n${str(round(price_per_hour, 2))}\n*Instance daily cost*:\n${str(round(price_per_day, 2))}\n*Instance cost if ran through until the rest of the month*:\n${str(round(instance_cost_for_rest_of_month, 2))}\n*Final AWS amount spent if instance is ran to the end of the month*:\n${str(round(spend_amount_if_instance_rest_of_month, 2))}\n*Instance link*:\n{instance_link}\n*****If stopped then please verify it is allowed to run and add a tag `BudgetOverride` set to `True`, then restart the instance. Or terminate it and re-deploy through gradle using `-PbudgetOverride=true`*****"
        notify_slack_using_(slack_message)

    return "Done"