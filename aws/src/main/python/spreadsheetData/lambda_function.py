# coding=utf-8
from __future__ import print_function
import pickle
import os.path
import os
from datetime import datetime
from googleapiclient.discovery import build
from google.oauth2 import service_account
from google_auth_oauthlib.flow import InstalledAppFlow
from google.auth.transport.requests import Request
import urllib.parse
import logging
import json

if len(logging.getLogger().handlers) > 0:
    # The Lambda environment pre-configures a handler logging to stderr. If a handler is already configured,
    # `.basicConfig` does not execute. Thus we set the level directly.
    logging.getLogger().setLevel(logging.WARN)
else:
    logging.basicConfig(level=logging.INFO)

logging.getLogger('googleapiclient.discovery_cache').setLevel(logging.ERROR)
logger = logging.getLogger()

# If modifying these scopes, delete the file token.pickle.
SCOPES = ['https://www.googleapis.com/auth/drive.file', 'https://www.googleapis.com/auth/spreadsheets']
SHEET_NAME_PREFIX = 'Run BEAM instances and results'

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
    apply_beam_formatting(sheet_api, sheet_id)
    return sheet_id


def apply_pilates_formatting(sheet_api, sheet_id):
    body = {
        'requests': [
            {
                "updateDimensionProperties": {
                    "range": {
                        "sheetId": 1,
                        "dimension": "COLUMNS",
                        "startIndex": 0,
                        "endIndex": 32
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
                        "sheetId": 1,
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
            ["Status", "Run Name", "Instance ID", "Instance type", "Time", "Host name", "Web browser",
             "Instance region", "Data region", "Branch", "Commit", "S3 URL", "S3 Path", "Title", "Start year",
             "Count of years", "BEAM it len", "Urbansim it len", "In year output", "Config File",
             "PILATES Image Version", "PILATES Image Name", "PILATES Scenario Name", "UrbanSim Input",
             "UrbanSim Output",
             "Skim Path", "S3 Output", "S3 Base Path", "Max RAM", "Storage Size", "Shutdown Wait", "Shutdown Behavior"]
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="PILATES Instances!A1:K1",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=header).execute()
    empty = {
        'values': [
            []
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range="PILATES Instances!A2:K2",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=empty).execute()
    row_range = {
        'sheetId': 1,
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
                        'type': 'TEXT_CONTAINS',
                        'values': [{
                            'userEnteredValue': 'Started'
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
                        'type': 'TEXT_CONTAINS',
                        'values': [{
                            'userEnteredValue': 'Completed'
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


def add_pilates_sheet(sheet_api, sheet_id):
    body = {
        'requests': [
            {
                "addSheet": {
                    "properties": {
                        "sheetId": 1,
                        "title": "PILATES Instances",
                        "gridProperties": {
                            "frozenRowCount": 1
                        },
                        "tabColor": {
                            "red": 1.0,
                            "green": 0.3,
                            "blue": 0.4
                        }
                    }
                }
            }
        ]
    }
    sheet_api.batchUpdate(spreadsheetId=sheet_id, body=body).execute()


def apply_beam_formatting(sheet_api, sheet_id):
    body = {
        'requests': [
            {
                'updateSheetProperties': {
                    'properties': {
                        'sheetId': 0,
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
                        "endIndex": 26
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
            ["Status", "Run Name", "Instance ID", "Instance type", "Time", "Host name", "Web browser", "Region",
             "Batch", "Branch", "Commit", "Data Branch", "Data Commit", "S3 Url", "Config File"]
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


def add_beam_row_archive(sheet_api, sheet_id, sheet_name, row_data):
    empty = {
        'values': [
            [
                row_data.get('status'),
                row_data.get('name'),
                row_data.get('s3_link', ''),
                row_data.get('instance_type'),
                datetime.now().strftime('%m/%d/%Y, %H:%M:%S'),
                row_data.get('host_name'),
                row_data.get('browser'),
                row_data.get('region'),
                row_data.get('batch'),
                row_data.get('branch'),
                row_data.get('commit'),
                row_data.get('data_branch'),
                row_data.get('data_commit', ''),
                row_data.get('instance_id'),
                row_data.get('config_file', ''),
                row_data.get('max_ram', ''),
                row_data.get('stacktrace', ''),
                row_data.get('died_actors', ''),
                row_data.get('error', ''),
                row_data.get('warning', ''),
                row_data.get('sigopt_client_id', ''),
                row_data.get('sigopt_dev_id', ''),
                row_data.get('profiler_type', '')
            ]
        ]
    }
    sheet_api.values().append(
        spreadsheetId=sheet_id, range=f"{sheet_name}!A2:F2",
        valueInputOption="USER_ENTERED", insertDataOption='OVERWRITE', body=empty).execute()

def add_beam_row(sheet_api, spreadsheet_id, sheet_name, row_data, max_rows):
    timestamp = datetime.now().strftime('%m/%d/%Y, %H:%M:%S')

    def to_value(str):
        stripped = str.strip()
        if stripped.startswith("http://") or stripped.startswith("https://"):
            return { "userEnteredValue": {"stringValue": str.strip() },
                     "textFormatRuns": [ {"startIndex": 0, "format": {"link": {"uri": stripped}}} ]}
        else:
            return { "userEnteredValue": {"stringValue": stripped } }

    def to_values(columns):
        return list(map(lambda col : to_value(row_data.get(col, '')), columns))

    def get_run_id(items):
        try:
            return f"{items[hostname_index]}-{items[instance_id_index]}"
        except IndexError:
            return ""

    def new_row(sheet_id, is_completed):
        columns1 = ['status', 'name', 's3_link', 'instance_type']
        columns2 = [
            'host_name',
            'instance_id',
            'browser',
            'region',
            'batch',
            'branch',
            'commit',
            'data_branch',
            'data_commit',
            'config_file',
            'max_ram',
            'stacktrace',
            'died_actors',
            'error',
            'warning',
            'sigopt_client_id',
            'sigopt_dev_id',
            'profiler_type'
        ]

        timestamps = [to_value(timestamp), to_value('')]

        if is_completed:
            timestamps.reverse()

        values = to_values(columns1) + timestamps + to_values(columns2)

        last_index = row_count + 1

        dimension_request = None
        # Adjusting number of rows to match the max_rows target (either adding or deleting)
        if row_count < max_rows:
            dimension_request = {
                "insertDimension": {
                    "inheritFromBefore": True,
                    "range": {
                        "dimension": "ROWS",
                        "sheetId": sheet_id,
                        "startIndex": last_index,
                        "endIndex": max_rows + 1
                    },
                }
            }
        elif row_count > max_rows:
            dimension_request = {
                "deleteDimension": {
                    "range": {
                        "dimension": "ROWS",
                        "sheetId": sheet_id,
                        "startIndex": max_rows + 1,
                        "endIndex": last_index
                    },
                }
            }

        requests = [{
            "insertDimension": {
                "inheritFromBefore": False,
                "range": {
                    "dimension": "ROWS",
                    "sheetId": sheet_id,
                    "startIndex": 1,
                    "endIndex": 2
                },
            }
        }, {
            "updateCells": {
                "rows": [{
                    "values": values
                }],
                "fields": "*",
                "start": {
                    "sheetId": sheet_id,
                    "rowIndex": 1,
                    "columnIndex": 0
                }
            }
        }]

        if dimension_request != None:
            requests.append(dimension_request)

        body = {
            "includeSpreadsheetInResponse": False,
            "requests": requests
        }
        sheet_api.batchUpdate(spreadsheetId=spreadsheet_id, body=body).execute()


    # Fetching row count and sheet id
    res = sheet_api.get(spreadsheetId=spreadsheet_id).execute()
    row_count = res['sheets'][0]['properties']['gridProperties']['rowCount']
    sheet_id =  res['sheets'][0]['properties']['sheetId']

    if row_data.get('status') == 'Run Started':
        new_row(sheet_id, is_completed=False)
    else:
        run_id = f"{row_data.get('host_name')}-{row_data.get('instance_id')}"
        result = sheet_api.values().get(spreadsheetId=spreadsheet_id, range=f'{sheet_name}!A1:Z{max_rows}').execute()
        columns = result['values'][0]

        try:
            hostname_index = columns.index('Host name')
            instance_id_index = columns.index('Instance ID')
            time_completed_index = columns.index('Time completed')
            s3_url_index = columns.index('S3 Url')

            run_ids = list(map(lambda items: get_run_id(items), result['values'][1:]))

            # find row number by run id
            index = run_ids.index(run_id) + 1

            # update status and completed timestamp of that row
            body = {
                "includeSpreadsheetInResponse": False,
                "requests": [{
                    "updateCells": {
                        "rows": [{
                            "values": [ to_value(row_data.get('status')) ]
                        }],
                        "fields": "*",
                        "start": {
                            "sheetId": sheet_id,
                            "rowIndex": index,
                            "columnIndex": 0
                        }
                    }
                }, {
                   "updateCells": {
                       "rows": [{
                           "values": [ to_value(row_data.get('s3_link')) ]
                       }],
                       "fields": "*",
                       "start": {
                           "sheetId": sheet_id,
                           "rowIndex": index,
                           "columnIndex": s3_url_index
                       }
                   }
               },{
                    "updateCells": {
                        "rows": [{
                            "values": [ to_value(timestamp) ]
                        }],
                        "fields": "*",
                        "start": {
                            "sheetId": sheet_id,
                            "rowIndex": index,
                            "columnIndex": time_completed_index
                        }
                    }
                }]
            }
            sheet_api.batchUpdate(spreadsheetId=spreadsheet_id, body=body).execute()
        except ValueError as e:
            logger.warning(f"Could not find the existing run with id {run_id} for update, inserting a new row\n" +
                           f"original error: {e}")
            new_row(sheet_id, is_completed=True)


def add_pilates_row(sheet_api, spreadsheet_id, row_data):
    empty = {
        'values': [
            [
                row_data.pop('status'),
                row_data.pop('name'),
                row_data.pop('instance_id'),
                row_data.pop('instance_type'),
                datetime.now().strftime('%m/%d/%Y, %H:%M:%S'),
                row_data.pop('host_name'),
                row_data.pop('browser'),
                row_data.pop('instance_region'),
                row_data.pop('data_region'),
                row_data.pop('branch'),
                row_data.pop('commit'),
                row_data.pop('data_branch'),
                row_data.pop('data_commit'),
                row_data.pop('s3_URL'),
                row_data.pop('s3_path', ''),
                row_data.pop('title', ''),
                row_data.pop('start_year', ''),
                row_data.pop('count_of_years', ''),
                row_data.pop('beam_it_len', ''),
                row_data.pop('urbansim_it_len', ''),
                row_data.pop('in_year_output', ''),
                row_data.pop('config', ''),
                row_data.pop('pilates_image_version', ''),
                row_data.pop('pilates_image_name', ''),
                row_data.pop('pilates_scenario_name', ''),
                row_data.pop('initial_urbansim_input', ''),
                row_data.pop('initial_urbansim_output', ''),
                row_data.pop('initial_skims_path', ''),
                row_data.pop('s3_output_bucket', ''),
                row_data.pop('s3_output_base_path', ''),
                row_data.pop('max_ram', ''),
                row_data.pop('storage_size', ''),
                row_data.pop('shutdown_wait', ''),
                row_data.pop('shutdown_behaviour', ''),
                *row_data.values()
            ]
        ]
    }
    sheet_api.values().append(
        spreadsheetId=spreadsheet_id, range="PILATES Instances!A2:F2",
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
    key_path = os.environ.get('key_path', None)
    if key_path == None:
        creds_data = dict(
            type = "service_account",
            project_id = os.environ["project_id"],
            private_key_id = os.environ["private_key_id"],
            private_key = os.environ["private_key"].replace('\\n', '\n'),
            client_email = os.environ["client_email"],
            client_id = os.environ["client_id"],
            auth_uri = "https://accounts.google.com/o/oauth2/auth",
            token_uri = "https://oauth2.googleapis.com/token",
            auth_provider_x509_cert_url = "https://www.googleapis.com/oauth2/v1/certs",
            client_x509_cert_url = "https://www.googleapis.com/robot/v1/metadata/x509/" + urllib.parse.quote(os.environ["client_email"])
        )
    else:
        f = open(key_path)
        creds_data = json.load(f)
        f.close()

    creds = service_account.Credentials.from_service_account_info(creds_data, scopes=SCOPES)
    return creds


def add_handler(event):
    logger.info(event)
    creds = load_service_creds()
    service = build('sheets', 'v4', credentials=creds)
    sheet_api = service.spreadsheets()

    spreadsheet_id = os.environ['spreadsheet_id']
    current_sheet_name = os.environ.get('current_sheet_name', 'latest 500')
    archive_sheet_name = os.environ.get('archive_sheet_name', 'all runs')
    max_rows = int(os.environ.get('max_rows', '500'))

    json = event.get('run')
    row_type = event.get('type')

    if row_type == 'beam':
        add_beam_row_archive(sheet_api, spreadsheet_id, archive_sheet_name, json)
        return add_beam_row(sheet_api, spreadsheet_id, current_sheet_name, json, max_rows)
    if row_type == 'pilates':
        return add_pilates_row(sheet_api, spreadsheet_id, json)


def create_handler(event):
    creds = load_creds()
    service = build('sheets', 'v4', credentials=creds)
    sheet = service.spreadsheets()
    datem = datetime.now().strftime('%h.%Y')
    return create_spreadsheet(sheet, event.get('spreadsheet_id'), '{0} {1}'.format(SHEET_NAME_PREFIX, datem))


def lambda_handler(event, context):
    command_id = event.get('command', 'create')

    if command_id == 'create':
        return create_handler(event)

    if command_id == 'add':
        return add_handler(event)

    return "Operation {command} not supported, please specify one of the supported operations (create | add). ".format(
        command=command_id)


def main():
    # For local testing you will need to export these env vars, adjust values to point to copy of deployment spreadsheet:

    # This is a API key file in json format which you can create and download from Google API console
    # export key_path=~/.rugged-diagram-368211-d5a37637dcec.json
    # export spreadsheet_id=1gnvfNT8IksOzHzuLzjCSjQ10gefnwahkvstvFW7U0is

    # Sample json
    event = {
        "command": "add",
        "type": "beam",
        "run": {
            "status": "Run Started",
            "name": "test name",
            "instance_id": "13",
            "instance_type": "r5.2xlarge",
            "host_name": "ec2-18-224-214-6.us-east-2.compute.amazonaws.com",
            "browser": "http://test_url",
            "branch": "branch",
            "commit": "commit",
            "data_branch": "data_branch",
            "data_commit": "data_commit",
            "region": "region",
            "batch": "batch",
            "s3_link": " https://test_url",
            "max_ram": "max_ram",
            "profiler_type": "profiler_type",
            "config_file": "config_file",
            "stacktrace": "stacktrace",
            "died_actors": "died_actors",
            "error": "error",
            "warning": "warning",
            "sigopt_client_id": "sigopt_client_id",
            "sigopt_dev_id": "sigopt_dev_id"
        }
    }
    lambda_handler(event, None)

if __name__ == '__main__':
    main()
