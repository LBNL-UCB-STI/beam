from googleapiclient import discovery
import time

def main(request):
    compute = discovery.build('compute', 'v1')
    data = request.get_json()
    zone = data.get('zone')
    instance_name = data.get('instance_name')
    snapshot_name = 'beam-automation-'+time.strftime("%Y-%m-%d-%H%M%S", time.gmtime())
    source_disk = f'projects/beam-core/zones/{zone}/disks/{instance_name}'
    snapshot_body = {
        'name': snapshot_name
    }
    response = compute.disks().createSnapshot(project='beam-core', zone=zone, disk=instance_name, body=snapshot_body).execute()
    return response['name'] + ' ' + snapshot_name