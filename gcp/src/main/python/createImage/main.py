from googleapiclient import discovery
import time

def main(request):
    compute = discovery.build('compute', 'v1')
    data = request.get_json()
    zone = data.get('zone')
    snapshot_name = data.get('snapshot_name')
    image_name = 'beam-automation-'+time.strftime("%Y-%m-%d-%H%M%S", time.gmtime())
    response = compute.images().insert(project='beam-core', body={'name':image_name, 'sourceSnapshot': f'projects/beam-core/global/snapshots/{snapshot_name}'}).execute()
    return response['name'] + ' ' + image_name