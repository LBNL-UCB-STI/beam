#Requires `pip install boto3`, `pip install python-dateutil`, and `pip install pytz`
#Requires `aws configure` to use IAM credentials that can make the necessary AWS changes
import boto3
import shutil
import datetime
import dateutil.relativedelta
import xml.etree.ElementTree as ET
import subprocess
import pytz
import os
import dateutil.parser
import time
import glob


#This script needs to be run on the main jenkins server so that it can update the cloud AMI to use the latest
#If there is ever a programmatic way to update the cloud config then this could be moved to a lambda
#Until then it should be setup as a cron job per below - to be executed once every 2 days at 2 AM
# sudo crontab -e
# 0 2 */2 * * /home/ubuntu/AMI_Cleanup.py >> /home/ubuntu/AMI_Cleanup.log 2>&1
#Jenkins is/should be set up to use beambox

utc=pytz.UTC
current_date_time = utc.localize(datetime.datetime.now())

def get_ec2_for(region):
    ec2 = boto3.client('ec2', region_name=region)
    return ec2

def backup_and_retrieve_config_and_delete_old_backup():
    today = datetime.datetime.today()
    shutil.copy('/var/lib/jenkins/config.xml','/var/lib/jenkins/config.xml.bak_'+str(today.month)+'_'+str(today.day))
    eight_weeks_ago = current_date_time - dateutil.relativedelta.relativedelta(weeks=8)
    for bak_file_path in glob.glob('/var/lib/jenkins/config.xml.bak_*'):
        bak_file_modified_time = utc.localize(dateutil.parser.parse(time.ctime(os.path.getmtime(bak_file_path))))
        if bak_file_modified_time < eight_weeks_ago:
            print('Deleting old backup config file:' + bak_file_path)
            os.remove(bak_file_path)
    return ET.parse('/var/lib/jenkins/config.xml')

def get_tagged_amis_along_with_most_recent(region):
    ec2 = get_ec2_for(region)
    amis = ec2.describe_images(Owners=['self'])
    allAMIs = []
    newestAMI = None
    for ami in amis['Images']:
        if newestAMI is None:
            newestAMI = ami
        if 'beam-automation-' in ami['Name']:
            ami_creation = dateutil.parser.parse(ami['CreationDate'])
            current_newest_creation = dateutil.parser.parse(newestAMI['CreationDate'])
            two_weeks_ago = current_date_time - dateutil.relativedelta.relativedelta(weeks=2)
            if ami_creation < two_weeks_ago:
                allAMIs.append((ami, True)) #mark for deletion
            else:
                allAMIs.append((ami, False)) #mark for keeping
            if ami_creation > current_newest_creation:
                newestAMI = ami
    return (allAMIs, newestAMI)

def replace_old_ami_with_new_using(oldAMIIds, newestAMIId, config):
    currentConfigAMIs = config.findall('.//hudson.plugins.ec2.SlaveTemplate/ami')
    for configAMI in currentConfigAMIs:
        if configAMI.text in oldAMIIds:
            configAMI.text = newestAMIId
    config.write('/var/lib/jenkins/config.xml')

def restart_jenkins():
    subprocess.call(['sudo', 'service', 'jenkins', 'restart'])

#NOTE: This is a dangerous method (as named), so make sure it is called while keeping at least 1 or 2 AMIs
def delete_older_amis_and_snapshots(allAMIsTagged, region):
    amisToKeep = map(lambda x: x[0]['ImageId'], filter(lambda x: x[1] == False, allAMIsTagged))
    amisAndSnapshotsToDelete = map(lambda x: (x[0]['ImageId'], map(lambda y: y['Ebs']['SnapshotId'], x[0]['BlockDeviceMappings'])), filter(lambda x: x[1] == True, allAMIsTagged))
    print('Keeping AMI IDs:' + str(amisToKeep))
    print('Deleting AMI IDs and correlated SnapshotIds for region ' + region + ':' + str(amisAndSnapshotsToDelete))
    ec2 = get_ec2_for(region)
    for amiIdAndSnapshotIds in amisAndSnapshotsToDelete:
        ec2.deregister_image(ImageId=amiIdAndSnapshotIds[0])
        for snapshotId in amiIdAndSnapshotIds[1]:
            ec2.delete_snapshot(SnapshotId=snapshotId)

def main():
    taggedAMIsAndMostRecent = get_tagged_amis_along_with_most_recent('us-east-2')
    config = backup_and_retrieve_config_and_delete_old_backup()
    replace_old_ami_with_new_using(map(lambda x: x[0]['ImageId'], taggedAMIsAndMostRecent[0]), taggedAMIsAndMostRecent[1]['ImageId'], config)
    restart_jenkins()
    if len(filter(lambda x: x[1] == False, taggedAMIsAndMostRecent[0])) >= 2: #Must keep >= 2 before can delete older ones
        delete_older_amis_and_snapshots(taggedAMIsAndMostRecent[0], 'us-east-2')
        #Now find and delete from the other regions (should be safe as the other regions are just a duplicate from the main us-east-2)
        for region in ['us-east-1','us-west-2']:
            secondaryRegionTaggedAMIsAndMostRecent = get_tagged_amis_along_with_most_recent(region)
            delete_older_amis_and_snapshots(secondaryRegionTaggedAMIsAndMostRecent[0], region)

if __name__ == "__main__":
    main()
