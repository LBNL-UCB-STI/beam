#!/bin/bash

sudo mv git/beam/output git/beam/output-2019-08-27
mkdir git/beam/output
mkdir git/beam/output/sfbay
mkdir git/beam/output/urbansim-inputs
mkdir git/beam/output/urbansim-outputs
mkdir git/beam/output/urbansim-inputs/base
mkdir git/beam/output/urbansim-inputs/base/base
cp git/beam/production/sfbay/urbansim/2010/baseline/* git/beam/output/urbansim-outputs/
ls git/beam/output/urbansim-outputs/*.gz | xargs gunzip
cp git/beam/output-2019-08-27/urbansim-inputs/base/base/* git/beam/output/urbansim-inputs/base/base/

sudo rm -rf git/beam/output-2019-08-27/urbansim-inputs/test


tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-baseline-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-a-lt-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-a-ht-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-b-lt-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-b-ht-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-c-lt-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-c-ht-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-base-2030-lt-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-base-2030-ht-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-base-2045-lt-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27
tar --exclude="git/beam/output-2019-08-27/urbansim-output*" -zcvf smart-base-2045-ht-pilates-2019-09-27.tar.gz git/beam/output-2019-08-27

sudo docker run --name test-it0-5year -v ~/git/beam/:/beam-project -v ~/git/beam/output:/output -e AWS_ACCESS_KEY_ID=AKIAIZZTFTVROTUOT3MQ  -e AWS_SECRET_ACCESS_KEY=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 beammodel/pilates:latest 2010 30 15 5 base /beam-project/production/sfbay/smart/smart-baseline-pilates.conf
sudo docker run --name test-it2-5year -v ~/git/beam/:/beam-project -v ~/git/beam/output:/output -e AWS_ACCESS_KEY_ID=AKIAIZZTFTVROTUOT3MQ  -e AWS_SECRET_ACCESS_KEY=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 beammodel/pilates:latest 2010 30 15 5 base /beam-project/production/sfbay/smart/smart-baseline-pilates.conf
sudo docker run --name test-it0-15year -v ~/git/beam/:/beam-project -v ~/git/beam/output:/output -e AWS_ACCESS_KEY_ID=AKIAIZZTFTVROTUOT3MQ  -e AWS_SECRET_ACCESS_KEY=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 beammodel/pilates:latest 2010 30 15 5 base /beam-project/production/sfbay/smart/smart-baseline-pilates.conf
sudo docker run --name test-it2-15year -v ~/git/beam/:/beam-project -v ~/git/beam/output:/output -e AWS_ACCESS_KEY_ID=AKIAIZZTFTVROTUOT3MQ  -e AWS_SECRET_ACCESS_KEY=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 beammodel/pilates:latest 2010 30 15 5 base /beam-project/production/sfbay/smart/smart-baseline-pilates.conf

history | grep docker | tail

# tmux ctrl-b % to split then ctrl-b right left to move
