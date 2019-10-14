# coding=utf-8
from __future__ import print_function
import pickle
import os.path
import boto3
import time
import os
from datetime import datetime
from googleapiclient.discovery import build
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
from botocore.errorfactory import ClientError

# If modifying these scopes, delete the file token.pickle.
SCOPES = ['https://www.googleapis.com/auth/drive.file', 'https://www.googleapis.com/auth/spreadsheets']
SHEET_NAME_PREFIX = 'Run BEAM instances and results'
INSTANCE_OPERATIONS = ['start', 'stop']
REGIONS = ['us-east-1', 'us-east-2', 'us-west-2']

ec2 = None


def init_ec2(region):
    global ec2
    ec2 = boto3.client('ec2', region_name=region)


def create_spreadsheet(sheet_api, title):
    spreadsheet = {
        'properties': {
            'title': title
        }
    }
    spreadsheet = sheet_api.create(body=spreadsheet,
                                   fields='spreadsheetId').execute()
    sheet_id = spreadsheet.get('spreadsheetId')
    print('Spreadsheet ID: {0}'.format(sheet_id))
    body = {
        'requests': [
            {
                'updateSheetProperties': {
                    'properties': {
                        'sheetId': '0',
                        'title': 'BEAM Instances',
                        "gridProperties": {
                            "frozenRowCount": 1
                        }
                    },
                    'fields': '(title,gridProperties.frozenRowCount)'
                }
            },
            {
                "updateDimensionProperties": {
                    "range": {
                        "sheetId": 0,
                        "dimension": "COLUMNS",
                        "startIndex": 0,
                        "endIndex": 5
                    },
                    "properties": {
                        "pixelSize": 160
                    },
                    "fields": "pixelSize"
                }
            },
            {
                "repeatCell": {
                    "range": {
                        "sheetId": 0,
                        "startRowIndex": 0,
                        "endRowIndex": 1
                    },
                    "cell": {
                        "userEnteredFormat": {
                            "backgroundColor": {
                                "red": 0.5,
                                "green": 0.5,
                                "blue": 0.5
                            },
                            "horizontalAlignment": "CENTER",
                            "textFormat": {
                                "foregroundColor": {
                                    "red": 1.0,
                                    "green": 1.0,
                                    "blue": 1.0
                                },
                                "fontSize": 10,
                                "bold": 'true'
                            }
                        }
                    },
                    "fields": "userEnteredFormat(backgroundColor,textFormat,horizontalAlignment)"
                }
            }
        ]
    }
    sheet_api.batchUpdate(spreadsheetId=sheet_id, body=body).execute()
    header = {
        'values': [
            ["Run Name", "Date", "Branch", "EC2 Instance", "S3 Link"]
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="BEAM Instances!A1:E1",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=header).execute()

    empty = {
        'values': [
            []
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="BEAM Instances!A2:E2",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=empty).execute()

    return sheet_id


def find_actual_sheet(creds):
    datem = datetime.now().strftime('%h.%Y')
    drive_service = build('drive', 'v3', credentials=creds)
    page_token = None
    response = drive_service.files().list(
        q="mimeType='application/vnd.google-apps.spreadsheet' and name='{0} {1}'".format(SHEET_NAME_PREFIX, datem),
        spaces='drive',
        fields='nextPageToken, files(id, name)',
        pageToken=page_token).execute()
    items = response.get('files', [])
    if not items:
        return False
    else:
        return True


def add_row(sheet_id, row_data, sheet_api):
    empty = {
        'values': [
            [
                row_data.get('name'),
                row_data.get('date'),
                row_data.get('branch'),
                row_data.get('instance'),
                row_data.get('s3_link')
            ]
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="BEAM Instances!A2:E2",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=empty).execute()


def load_creds():
    creds = None
    # The file token.pickle stores the user's access and refresh tokens, and is
    # created automatically when the authorization flow completes for the first
    # time.
    if os.path.exists('token.pickle'):
        with open('token.pickle', 'rb') as token:
            creds = pickle.load(token)
    # If there are no (valid) credentials available, let the user log in.
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
        else:
            flow = InstalledAppFlow.from_client_secrets_file(
                'credentials.json', SCOPES)
            creds = flow.run_local_server(port=0)
        # Save the credentials for the next run
        with open('token.pickle', 'wb') as token:
            pickle.dump(creds, token)
    return creds


def create_handler(event):
    datem = datetime.now().strftime('%h.%Y')
    return create_spreadsheet(event.get('spreadsheet_id'), '{0} {1}'.format(SHEET_NAME_PREFIX, datem))


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


def instance_handler(event):
    region = event.get('region', os.environ['REGION'])
    instance_ids = event.get('instance_ids')
    command_id = event.get('command')
    system_instances = os.environ['SYSTEM_INSTANCES']

    if region not in REGIONS:
        return "Unable to {command} instance(s), {region} region not supported.".format(command=command_id,
                                                                                        region=region)

    init_ec2(region)
    system_instances = system_instances.split(',')
    instance_ids = instance_ids.split(',')
    invalid_ids = check_instance_id(list(instance_ids))
    valid_ids = [item for item in instance_ids if item not in invalid_ids]
    allowed_ids = [item for item in valid_ids if item not in system_instances]

    if command_id == 'start':
        ec2.start_instances(InstanceIds=allowed_ids)
        return "Started instance(s) {insts}.".format(
            insts=', '.join([': '.join(inst) for inst in zip(allowed_ids, list(map(get_dns, allowed_ids)))]))

    if command_id == 'stop':
        ec2.stop_instances(InstanceIds=allowed_ids)

    return "Instantiated {command} request for instance(s) [ {ids} ]".format(command=command_id,
                                                                             ids=",".join(allowed_ids))


def lambda_handler(event, context):
    command_id = event.get('command', 'create')  # start | stop | add | create

    if command_id == 'create':
        return create_handler(event)

    if command_id == INSTANCE_OPERATIONS:
        return instance_handler(event)

    if command_id in INSTANCE_OPERATIONS:
        return 'instance_handler(event)'

    return "Operation {command} not supported, please specify one of the supported operations (deploy | start | stop | terminate | log). ".format(
        command=command_id)


def main():
    creds = load_creds()
    service = build('sheets', 'v4', credentials=creds)
    # Call the Sheets API
    sheet = service.spreadsheets()
    datem = datetime.now().strftime('%h.%Y')
    sheet_id = create_spreadsheet(sheet, '{0} {1}'.format(SHEET_NAME_PREFIX, datem))
    for i in range(10):
        add_row(sheet_id, {
            'name': 'test_name',
            'date': datetime.now().replace(microsecond=0).isoformat(),
            'branch': 'test_branch',
            'instance': 'i13123qw',
            's3_link': 'https://www.googleapis.com/auth/drive.file'
        }, sheet)


if __name__ == '__main__':
    main()
