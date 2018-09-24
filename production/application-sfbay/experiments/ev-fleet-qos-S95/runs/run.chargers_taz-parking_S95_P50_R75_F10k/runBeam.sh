#!/bin/bash
MAXRAM="140g"
BEAM_ROOT="$(git rev-parse --show-toplevel )"

pushd ${BEAM_ROOT}

    CONFIG_PATH="production/application-sfbay/experiments/ev-fleet-qos-S95/runs/run.chargers_taz-parking_S95_P50_R75_F10k/beam.conf"
    BEAM_OUTPUT="production/application-sfbay/experiments/ev-fleet-qos-S95/runs/run.chargers_taz-parking_S95_P50_R75_F10k/output"
    if [ -z ${BEAM_OUTPUT} ]; then
        echo "Output directory is undefined"
        exit 1
    fi
    S3_OUTPUT_PATH="2018-09"
    DROP_OUTPUT="true"
    BRANCH=""
#    if [ -z  "${BUILD_SHA}" ]; then
#        git pull
#    else
#        git checkout ${BUILD_SHA}
#    fi
    mkdir -p ${BEAM_OUTPUT}

    echo "Running experiment using config ${CONFIG_PATH} , output_dir:  ${BEAM_OUTPUT} "
    ./gradlew --stacktrace :run -PappArgs="['--config', '${CONFIG_PATH//\\//}']"
    exit_status=$?
    if [ "$exit_status" != "0" ]; then
        exit $exit_status
    fi

    echo "Simulation has been finished, statusCode=$exit_status"

    git rev-parse --verify HEAD > ${BEAM_OUTPUT}/version.txt
    MODE_CHOICE_COUNT=$(find ${BEAM_OUTPUT} -name '0.events.csv*' -exec zgrep -- 'ModeChoice' {} \; | grep -Eo "ModeChoice,,,\w*"	| sort | uniq -c)
    echo "$MODE_CHOICE_COUNT" > ${BEAM_OUTPUT}/ITERS/it.0/modeChoiceStat.txt

    RUN_NAME=$(basename "$(dirname  ${BEAM_OUTPUT} )")
    TAR_NAME=${RUN_NAME}__$(cat /proc/sys/kernel/random/uuid)
    tar -zcvf /tmp/${TAR_NAME}.tar.gz ${BEAM_OUTPUT}

    if [ "$1" == "cloud" ]; then
        sudo aws --region us-east-2 s3 cp /tmp/${TAR_NAME}.tar.gz s3://beam-outputs/  && rm -f /tmp/${TAR_NAME}.tar.gz

        if [ "$DROP_OUTPUT" == "true" ]; then
            rm -rf ${BEAM_OUTPUT}
        fi
    fi

popd
