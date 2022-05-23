# coding=utf-8
import boto3
import os
from datetime import date
from calendar import monthrange
from decimal import Decimal
import json
import logging
from pkg_resources import resource_filename
from botocore.errorfactory import ClientError

ec2_client = None
budget_client = None
pricing_client = None
sns_client = None

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

def init_sns_client():
    global sns_client
    sns_client = boto3.client('sns')

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
    for tag in tags:
        key = tag.get("Key")
        if key == "GitUserEmail":
            instance_git_user_email = tag.get("Value")
        elif key == "Name":
            instance_name = tag.get("Value")
        elif key == "BudgetOverride":
            budget_override = tag.get("Value")
    return EC2Instance(instance_id, instance_type, operating_system, instance_name, instance_git_user_email, budget_override)

def send_to_sns_notification(message, subject):
    sns_client.publish(
        TopicArn='arn:aws:sns:us-east-2:340032650202:instance_notifier',
        Message=message,
        Subject=subject)

def stop_instance(instance_id):
    ec2_client.stop_instances(InstanceIds=[instance_id])

def set_override_tag_to_empty_for_(instance_id):
    ec2_client.create_tags(
        Resources=[instance_id],
        Tags=[
            {
                'Key': 'BudgetOverride',
                'Value': '',
            },
        ],
    )

# Expected format: {"instance-id": <instance-id>, "state": <state>, "region": <region>, "account-id": <account-id>}
def lambda_handler(event, context):
    state = event.get('state')
    if state != "running":
        return "Instance state change of {state} is not supported for this lambda - only 'running' is considered".format(state=state)
    region = event.get('region')
    instance_id = event.get('instance-id')
    account_id = event.get('account-id')

    init_budget_client()
    budget_response = budget_client.describe_budget(
        AccountId=account_id,
        BudgetName=os.environ["BUDGET_NAME"]
    )

    budget_details = budget_response.get('Budget')
    budget_limit_amount = Decimal(budget_details.get('BudgetLimit').get('Amount'))
    current_spent_amount = Decimal(budget_details.get('CalculatedSpend').get('ActualSpend').get('Amount'))
    percent_of_budget_used = (current_spent_amount / budget_limit_amount) * 100

    if percent_of_budget_used < 60:
        return "Budget is not over 60%. Stopping check."

    today = date.today()
    current_day = Decimal(today.strftime("%d"))
    current_month = today.strftime("%m")
    current_year = today.strftime("%Y")
    days_in_current_month = monthrange(int(current_year), int(current_month))[1]
    percent_of_month = (current_day / Decimal(days_in_current_month)) * 100

    if percent_of_budget_used <= percent_of_month:
        return "Budget does not exceed the current point of the month. Stopping check"

    # We are over 60% and over budget AND above percent of month remaining, so notifications must go out
    # LATER TODO: Add link to budget?
    # LATER TODO: Send to slack also?
    # LATER TODO: Add an override option that is not emptied (true_always?) or for # of starts (true_3? - decrement and update tag)
    init_ec2_client(region)
    instance_details = get_instance_details_for_(instance_id)

    instance_stopped = ""
    if percent_of_budget_used >= 100 and instance_details.budget_override != "true":
        stop_instance(instance_id)
        set_override_tag_to_empty_for_(instance_id)
        instance_stopped = " but was stopped"
    elif percent_of_budget_used >= 100 and instance_details.budget_override == "true":
        set_override_tag_to_empty_for_(instance_id)
        instance_stopped = " and was allowed via override"

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

    #LATER TODO: Add a link to trigger an automatic approval. Maybe link to instance is enough, since can tag there?

    message = "Instance with ID '" + instance_id + "' just started to run" + instance_stopped + " and the current amount spent this month ($" + str(current_spent_amount) + ") is " + str(round(percent_of_budget_used, 2)) + "% of the budgeted amount ($" + str(budget_limit_amount) + ").\r\nInstance Name: " + instance_name + "\r\nGit email of who created the instance: " + instance_git_user_email + "\r\nForecasted amount to be spent this month: $" + str(forecasted_spend_amount) + "\r\nPercent of month completed: " + str(round(percent_of_month, 2)) + "%\r\nInstance hourly cost: $" + str(price_per_hour) + "\r\nInstance daily cost: $" + str(price_per_day) + "\r\nInstance cost if ran through until the rest of the month: $" + str(round(instance_cost_for_rest_of_month, 2)) + "\r\nFinal AWS amount spent if instance is ran to the end of the month: $" + str(round(spend_amount_if_instance_rest_of_month, 2)) + "\r\nInstance link: " + instance_link + "\r\n*****If stopped then please verify it is allowed to run and add a tag 'BudgetOverride' set to 'True', then restart the instance.*****"
    subject = "Instance just started" + instance_stopped + " and BEAM is " + str(round(percent_of_budget_used, 2)) + "% of monthly budget"
    init_sns_client()
    logger.info(subject + "\r\n" + message)
    send_to_sns_notification(message, subject[:99])

    return "Done"