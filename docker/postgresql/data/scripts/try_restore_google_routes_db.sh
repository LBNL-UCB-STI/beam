#!/bin/bash

GOOGLE_ROUTES_DB=google_routes_db

GOOGLE_ROUTES_DB_EXISTS=0
GOOGLE_ROUTES_DB_CHECK_EXIT_CODE=-1

GOOGLE_ROUTES_DB_CHECK_MAX_RETRIES=5
GOOGLE_ROUTES_DB_CHECK_TIMEOUT=10
GOOGLE_ROUTES_DB_CHECK_RETRIES=0

while [ $GOOGLE_ROUTES_DB_CHECK_RETRIES -lt $GOOGLE_ROUTES_DB_CHECK_MAX_RETRIES ] && [ $GOOGLE_ROUTES_DB_CHECK_EXIT_CODE -ne 0 ]
do
    GOOGLE_ROUTES_DB_EXISTS=$(psql -lqt -U postgres -d postgres)
    GOOGLE_ROUTES_DB_CHECK_EXIT_CODE=$?

    if [ $GOOGLE_ROUTES_DB_CHECK_EXIT_CODE -ne 0 ]; then
      if [ $GOOGLE_ROUTES_DB_CHECK_RETRIES -lt $GOOGLE_ROUTES_DB_CHECK_MAX_RETRIES ]; then
          echo "Retrying $GOOGLE_ROUTES_DB existence check"
          sleep $GOOGLE_ROUTES_DB_CHECK_TIMEOUT
      fi
    else
      GOOGLE_ROUTES_DB_EXISTS=$(echo "$GOOGLE_ROUTES_DB_EXISTS" | cut -d \| -f 1 | grep -cw "$GOOGLE_ROUTES_DB")
    fi

    let GOOGLE_ROUTES_DB_CHECK_RETRIES=$GOOGLE_ROUTES_DB_CHECK_RETRIES+1
done

if [ $GOOGLE_ROUTES_DB_CHECK_EXIT_CODE -ne 0 ]; then
    echo "Failed to check $GOOGLE_ROUTES_DB existence"
    exit 1
fi

if [ $GOOGLE_ROUTES_DB_EXISTS -eq 1 ]; then
    echo "$GOOGLE_ROUTES_DB exists, exiting"
else
    echo "Restoring $GOOGLE_ROUTES_DB from dump"
    psql -U postgres -d postgres -c "CREATE DATABASE $GOOGLE_ROUTES_DB WITH ENCODING 'UTF8';"
    psql -U postgres -d $GOOGLE_ROUTES_DB -f /data/google_routes_db_dump.sql
fi
