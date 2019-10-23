# coding=utf-8
from __future__ import print_function
import pickle
import os.path
import boto3
import time
import os
import sys, getopt
from datetime import datetime
from googleapiclient.discovery import build
from google.oauth2 import service_account
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
    apply_formatting(sheet_api, sheet_id)
    return sheet_id


def apply_formatting(sheet_api, sheet_id):
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
                        "endIndex": 11
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
            ["Status", "Run Name", "Instance ID", "Instance type", "Host name", "Web browser", "Region", "Batch",
             "Branch", "Commit", "S3 Url"]
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="BEAM Instances!A1:K1",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=header).execute()
    empty = {
        'values': [
            []
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="BEAM Instances!A2:K2",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=empty).execute()
    row_range = {
        'sheetId': 0,
        'startRowIndex': 1,
        'startColumnIndex': 0,
        'endColumnIndex': 1
    }
    requests = [{
        'addConditionalFormatRule': {
            'rule': {
                'ranges': [row_range],
                'booleanRule': {
                    'condition': {
                        'type': 'TEXT_EQ',
                        'values': [{
                            'userEnteredValue': 'Run Started'
                        }]
                    },
                    'format': {
                        'backgroundColor': {
                            'red': 0.4,
                            'green': 1,
                            'blue': 0.4
                        }
                    }
                }
            },
            'index': 0
        }
    }, {
        'addConditionalFormatRule': {
            'rule': {
                'ranges': [row_range],
                'booleanRule': {
                    'condition': {
                        'type': 'TEXT_EQ',
                        'values': [{
                            'userEnteredValue': 'Run Completed'
                        }]
                    },
                    'format': {
                        'backgroundColor': {
                            'red': 1,
                            'green': 0.4,
                            'blue': 0.4
                        }
                    }
                }
            },
            'index': 0
        }
    }]
    body = {
        'requests': requests
    }
    sheet_api.batchUpdate(spreadsheetId=sheet_id, body=body).execute()


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
                row_data.get('status'),
                row_data.get('name'),
                row_data.get('instance_id'),
                row_data.get('instance_type'),
                datetime.now().strftime('%m/%d/%Y, %H:%M:%S'),
                row_data.get('host_name'),
                row_data.get('browser'),
                row_data.get('region'),
                row_data.get('batch'),
                row_data.get('branch'),
                row_data.get('commit'),
                row_data.get('s3_link', '')
            ]
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="BEAM Instances!A2:F2",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=empty).execute()


def load_creds():
    creds = None
    # The file token.pickle stores the user's access and refresh tokens, and is
    # created automatically when the authorization flow completes for the first
    # time.
    if os.path.exists('token.pickle'):
        with open('token.pickle', 'rb') as token:
            creds = pickle.load(token)

    if os.path.exists('/tmp/token.pickle'):
        with open('/tmp/token.pickle', 'rb') as token:
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
        with open('/tmp/token.pickle', 'wb') as token:
            pickle.dump(creds, token)
    return creds


def load_service_creds():
    creds = service_account.Credentials.from_service_account_file('beam-simulation.json', scopes=SCOPES)
    return creds


def add_handler(event):
    creds = load_service_creds()
    service = build('sheets', 'v4', credentials=creds)
    sheet = service.spreadsheets()
    add_row(event.get('sheet_id'), event.get('run'), sheet)


def create_handler(event):
    creds = load_creds()
    service = build('sheets', 'v4', credentials=creds)
    sheet = service.spreadsheets()
    datem = datetime.now().strftime('%h.%Y')
    return create_spreadsheet(sheet, event.get('spreadsheet_id'), '{0} {1}'.format(SHEET_NAME_PREFIX, datem))


def lambda_handler(event, context):
    command_id = event.get('command', 'create')  # start | stop | add | create

    if command_id == 'create':
        return create_handler(event)

    if command_id == 'add':
        return add_handler(event)

    return "Operation {command} not supported, please specify one of the supported operations (create | add). ".format(
        command=command_id)


def main(sheet_id):
    creds = load_service_creds()
    service = build('sheets', 'v4', credentials=creds)
    # Call the Sheets API
    sheet = service.spreadsheets()
    apply_formatting(sheet, sheet_id)


if __name__ == '__main__':
    main(sys.argv[1])
