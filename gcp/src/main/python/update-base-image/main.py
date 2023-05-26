import functions_framework
import os
from googleapiclient import discovery

initscript = (('''
echo "-------------------Updating Beam dependencies----------------------"
sudo dpkg --configure -a
sudo dpkg --remove --force-remove-reinstreq  unattended-upgrades
sudo apt-get install unattended-upgrades
sudo dpkg --configure -a
sudo apt update
sudo apt install npm -y
sudo apt install nodejs libnode72 -y
sudo apt install python3-pip -y
pip install --upgrade pip
sudo pip install pandas
sudo pip install plotly
sudo pip install psutil requests
sudo npm cache clean -f
sudo npm install -g n
sudo n stable
sudo npm install -g npm
sudo apt-get install curl -y
curl -sL https://deb.nodesource.com/setup_8.x | sudo -E bash -
sudo apt-get install nodejs -y
sudo apt-get update
sudo apt install apt-transport-https ca-certificates curl software-properties-common gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg -y
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch="$(dpkg --print-architecture)" signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian "$(. /etc/os-release && echo "$VERSION_CODENAME")" stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt update
sudo apt-get install docker-ce docker-ce-cli containerd.io -y
sudo curl -L "https://github.com/docker/compose/releases/download/1.23.2/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
sudo ln -s /usr/local/bin/docker-compose /usr/bin/docker-compose
sudo apt-get install libgtkextra-dev libgconf2-dev libnss3 libasound2 libxtst-dev -y
sudo npm install -g electron@1.8.4 orca --unsafe-perm=true --alow-root -y
sudo apt-get install xvfb -y
sudo apt-get update -y
sudo apt-get install build-essential software-properties-common -y && sudo apt-get update -y
#sudo apt-get install g++-10 gcc-10 -y
#sudo update-alternatives --install /usr/bin/gcc gcc /usr/bin/gcc-10 60 --slave /usr/bin/g++ g++ /usr/bin/g++-10
sudo apt install jq -y
#sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys A1715D88E1DF1F24
#sudo add-apt-repository ppa:git-core/ppa -y
sudo apt-get update
sudo apt-get upgrade -y
sudo apt-get install make libssl-dev libghc-zlib-dev libcurl4-gnutls-dev libexpat1-dev gettext unzip -y
sudo apt-get install git -y
echo "-------------------Adding ~/git/beam as safe directory----------------------"
sudo git config --global --add safe.directory '/home/clu/sources/beam'
echo "-------------------Finished updating Beam dependencies----------------------"
cd /home/clu/sources/beam
sudo ssh -vT -o "StrictHostKeyChecking no" git@github.com
echo "git checkout ..."
sudo git reset --hard origin/HEAD
sudo git checkout -- .
sudo git clean -df
sudo git checkout develop
sudo git reset --hard origin/develop
sudo git pull 
sudo git fetch
sudo git fetch --prune
sudo git remote prune origin
echo "-git remote prune origin is done-"
sudo chown -R clu:clu /home/clu/sources/beam
for bn in $BRANCHES
 do
   echo "-------------------checkout $bn----------------------"
   sudo GIT_LFS_SKIP_SMUDGE=1 git checkout $bn
   sudo git reset --hard origin/$bn
   for submodule in $SUBMODULES
     do
       sudo git submodule update --init --remote $submodule
       sudo git pull
       sudo git lfs pull
     done
 done
sudo chown -R clu:clu /home/clu/sources/beam
echo "gradlew assemble ..."
./gradlew assemble
./gradlew clean
echo "preparing for python analysis"
'echo resetting git to base: "$(date)"'
sudo git reset --hard 
'echo fetching the latest: "$(date)"'
sudo git fetch
'echo current git status: "$(date)"'
sudo git status
'echo invoke create image function after a 5 minute sleep to let the file system settle..."$(date)"'
sudo sleep 5m
read -r createSnapshotOperationId created_snapshot_name <<< $(gcloud functions call createSnapshot --region us-central1 --data '{"zone": "us-central1-a", "instance_name": "$RUNNAME"}' | tr -d '\n' | sed -e 's/^[[:space:]]*//')
while ! gcloud compute operations describe $createSnapshotOperationId --zone us-central1-a | grep "status: DONE"; do echo "Waiting 30 seconds for snapshot in us-central1 $created_snapshot_name from operation $createSnapshotOperationId ..."; sleep 30s; done
read -r createImageOperationId created_image_name <<< $(gcloud functions call createImage --region us-central1 --data '{"zone": "us-central1-a", "snapshot_name": "'"${created_snapshot_name}"'"}' | tr -d '\n' | sed -e 's/^[[:space:]]*//')
while ! gcloud compute operations describe $createImageOperationId | grep "status: DONE"; do echo "Waiting 30 seconds for image in us-central1 $created_image_name from operation $createImageOperationId ..."; sleep 30s; done
read -r deleteSnapshotOperationId <<< $(gcloud functions call delete-snapshot --region us-central1 --data '{"snapshot_name": "'"${created_snapshot_name}"'"}' | tr -d '\n' | sed -e 's/^[[:space:]]*//')
while ! gcloud compute operations describe $deleteSnapshotOperationId | grep "status: DONE"; do echo "Waiting 30 seconds for snapshot deletion in us-central1 for $created_snapshot_name from operation $deleteSnapshotOperationId ..."; sleep 30s; done
echo "invoke update provided cloud function environment variables..."
gcloud functions call updateEnvVarsForProvidedFunctionNames --region us-central1 --data '{"image_url": "projects/beam-core/global/images/'"${created_image_name}"'", "function_names":["deploy_beam"]}'
echo "setting up auto shutdown ..."
sudo shutdown -h +$SHUTDOWN_WAIT
echo "shutdown in $SHUTDOWN_WAIT ..."
'''))

def create_instance_request(instance_name, machine_type, disk_image_name, storage_size, metadata):
    return {
        'name': instance_name,
        'machineType': machine_type,
        'tags': {
            'items': ['http-server']
        },

        # Specify the boot disk and the image to use as a source.
        'disks': [
            {
                'boot': True,
                'autoDelete': True,
                'initializeParams': {
                    'sourceImage': disk_image_name,
                },
                # beam disk minimum size is 100 (Gb)
                "diskSizeGb": storage_size,
            }
        ],

        # Specify a network interface with NAT to access the public
        # internet.
        'networkInterfaces': [{
            'network': 'global/networks/default',
            "accessConfigs": [
                {
                    "name": "external-nat",
                    "type": "ONE_TO_ONE_NAT",
                    "kind": "compute#accessConfig",
                    "networkTier": "PREMIUM"
                }
            ]
        }],

        # Set beam-bot as the service account
        # permissions could be set via IAM roles assigned to this service account
        'serviceAccounts': [
            {
                'email': 'beam-bot@beam-core.iam.gserviceaccount.com',
                'scopes': [
                    'https://www.googleapis.com/auth/cloud-platform'
                ]
            }
        ],

        'metadata': {
            'items': [{'key': k, 'value': v} for k, v in metadata]
        }
    }

@functions_framework.http
def main(request):
    cloud_functions = discovery.build('cloudfunctions', 'v1')
    resource_name = f"projects/beam-core/locations/us-central1/functions/deploy_beam"
    response = cloud_functions.projects().locations().functions().get(
        name=resource_name
    ).execute()
    environment_variables = response.get('environmentVariables', {})
    disk_image_name = environment_variables.get('DISK_IMAGE_NAME')
    branches = os.environ['BRANCHES']
    shutdown_wait = "10"
    submodules = os.environ['SUBMODULES']
    run_name = 'beam-dependency-update-instance'
    startup_script = initscript.replace('$BRANCH', branches).replace('$SHUTDOWN_WAIT', shutdown_wait).replace('$SUBMODULES', submodules).replace('$RUNNAME', run_name)
    shutdown_script = 'gcloud compute instances delete ' + run_name + ' --zone=us-central1-a --quiet'
    metadata = [('startup-script', startup_script),('shutdown-script', shutdown_script)]
    create_instance_request_body = create_instance_request(run_name, 'zones/us-central1-a/machineTypes/c2-standard-4', disk_image_name, '100', metadata)
    compute = discovery.build('compute', 'v1')
    result = compute.instances().insert(project='beam-core', zone='us-central1-a', body=create_instance_request_body).execute()
    return result