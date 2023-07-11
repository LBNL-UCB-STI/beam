from googleapiclient import discovery
import time

def main(request):
    compute = discovery.build('compute', 'v1')
    data = request.get_json()
    snapshot_name = data.get('snapshot_name')
    response = compute.snapshots().delete(project='beam-core', snapshot=snapshot_name).execute()
    return response['name']