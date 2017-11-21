#!/bin/bash
MAXRAM="{{ EXPERIMENT_MAX_RAM }}"
BEAM_ROOT="$(git rev-parse --show-toplevel )"

pushd ${BEAM_ROOT}

    CONFIG_PATH="{{ BEAM_CONFIG_PATH }}"
    BEAM_OUTPUT="{{ BEAM_OUTPUT_PATH }}"
    if [ -z ${BEAM_OUTPUT} ]; then
        echo "Output directory is undefined"
        exit 1
    fi
    S3_OUTPUT_PATH="{{ S3_OUTPUT_PATH_SUFFIX }}"
    DROP_OUTPUT="{{ DROP_OUTPUT_ONCOMPLETE }}"
    BRANCH="{{ BRANCH }}"
#    if [ -z  "${BUILD_SHA}" ]; then
#        git pull
#    else
#        git checkout ${BUILD_SHA}
#    fi
    mkdir -p ${BEAM_OUTPUT}
    git rev-parse --verify HEAD > ${BEAM_OUTPUT}/version.txt

    echo "Running experiment using config ${CONFIG_PATH} , output_dir:  ${BEAM_OUTPUT} "
    ./gradlew --stacktrace run -PappArgs="['--config', '${CONFIG_PATH}']"
    exit_status=$?
    if [ "$exit_status" != "0" ]; then
        exit $exit_status
    fi

    echo "Simulation has been finished, statusCode=$exit_status"

    MODE_CHOICE_COUNT=$(find ${BEAM_OUTPUT} -name '0.events.csv.gz' -exec zgrep -- 'ModeChoice' {} \; | grep -Eo "ModeChoice,,\w*"	| sort | uniq -c)
    echo "$MODE_CHOICE_COUNT" > ${BEAM_OUTPUT}/ITERS/it.0/modeChoiceStat.txt

    RUN_NAME=$(basename "$(dirname  ${BEAM_OUTPUT} )")
    TAR_NAME=${RUN_NAME}__$(cat /proc/sys/kernel/random/uuid)
    tar -zcvf /tmp/${TAR_NAME}.tar.gz ${BEAM_OUTPUT}

    echo " sudo aws --region us-east-2 s3 cp /tmp/${TAR_NAME}.tar.gz s3://beam-outputs/${S3_BUCKET_NAME}/  && rm -f /tmp/${TAR_NAME}.tar.gz "

    if [ "$DROP_OUTPUT" == "true" ]; then
        echo " rm -rf ${BEAM_OUTPUT} "
    fi

popd