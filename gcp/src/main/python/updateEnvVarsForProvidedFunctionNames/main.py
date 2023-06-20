import time
import logging
from googleapiclient import discovery

logger = logging.getLogger()
logger.setLevel(logging.INFO)

def main(request):
    data = request.get_json()
    image_url = data.get('image_url')
    function_names = data.get('function_names')
    for function_name in function_names:
        update_function(image_url, function_name)
    return "Update complete"

def update_function(image_url, function_name):
    logger.info('updating image ids ' + image_url)
    cloud_functions = discovery.build('cloudfunctions', 'v1')
    resource_name = f"projects/beam-core/locations/us-central1/functions/{function_name}"
    function_response = cloud_functions.projects().locations().functions().get(
        name=resource_name
    ).execute()
    if 'environmentVariables' not in function_response:
        function_response['environmentVariables'] = {}
    function_response['environmentVariables']['DISK_IMAGE_NAME'] = image_url
    updated_function = cloud_functions.projects().locations().functions().patch(
        name=function_response['name'],
        updateMask='environmentVariables',
        body=function_response
    ).execute()
    logger.info(function_name + ' image ids updated')