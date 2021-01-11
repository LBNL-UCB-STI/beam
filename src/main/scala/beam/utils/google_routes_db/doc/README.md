# TravelTimeGoogleStatistic: Build database of the computed routes
Issue [#2872](https://github.com/LBNL-UCB-STI/beam/issues/2872)  
PR: [#2904](https://github.com/LBNL-UCB-STI/beam/pull/2904)

Beam calls Google DirectionsAPI to build `googleTravelTimeEstimation.csv` output.
To save some of these calls we built GoogleRoutesDB.
GoogleRoutesDB is basically a cache of earlier calculated Google routes.
It is implemented on top of [PostgreSQL](https://www.postgresql.org/) with [PostGIS extension](https://postgis.net/).

GoogleRoutesDB feature consists of
1. [GoogleRoutesDB facade](../GoogleRoutesDB.scala) with all required use cases
1. Gradle tasks to start/stop PostgresSQL and restore/dump data during Beam runs (see _guild.gradle_)
1. [BuildGoogleRoutesDBApp utility](../build/BuildGoogleRoutesDBApp.scala) that can build GoogleRoutesDB using `maps.googleapi.responses.json` outputs
1. Integration with [TravelTimeGoogleStatistic](../../../agentsim/events/handling/TravelTimeGoogleStatistic.scala) listener

## Beam config
A new section `beam.calibration.google.travelTimes.googleRoutesDb` was added to Beam config (see _beam-template.conf_).
* `enable` - turn on/off usage of GoogleRoutesDB in [TravelTimeGoogleStatistic]() logic
* `postgres.[url, username, password]` - database connection settings

## How to run Beam with GoogleRoutesDB
We run Beam with either `run` or `execute` gradle tasks. Whenever `-PwithGoogleRoutesDB=true` argument is supplied, `run` and `execute` tasks get "surrounded" by `googleRoutesDBStart` and `googleRoutesDBStop`.

The whole tasks chain will be the following:
1. `dockerPullPostgresImage` pulls PostgreSQL (postgis/postgis:12-3.0-alpine) image
1. `dockerCreatePostgresContainer` creates a new container from PostgreSQL image with some necessary setup, i.e. _beam/docker/postgres/data_ is mounted as volume to _/data_.
1. `dockerStartPostgresContainer` starts a container
1. `googleRoutesDBTryRestore` creates `google_routes_db` database in PostgreSQL and restores pre-built cache from _beam/docker/postgres/data/google_routes_db_dump.sql_. It'll skip if `google_routes_db` already exists. Under the hood `beam/docker/postgres/data/scripts/try_restore_google_routes_db.sh` is executed inside PostgreSQL containter.
1. `googleRoutesDBStart` does nothing, just enables the above chain up to `run`/`execute`
1. `run` or `execute` go here
1. `googleRoutesDBTryDump` dumps `google_routes_db` to _beam/docker/posgtresql/data/google_routes_db_dump.sql_ (note: repo will have a local change). `beam/docker/postgres/data/scripts/try_dump_google_routes_db.sh` is executed inside PostgreSQL containter.
1. `dockerStopPostgresContainer` stops the started container
1. `googleRoutesDBStart` does nothing, enables dump and container stop after `run`/`execute`

Example:
```
./gradlew run -PwithGoogleRoutesDB=true
```

## Build GoogleRoutesDB
[BuildGoogleRoutesDBApp](../build/BuildGoogleRoutesDBApp.scala) populates the database from `googleTravelTimeEstimation.csv` and `maps.googleapi.responses.json` files.

A database must be created beforehand, `postgis` extension must be enabled:
```
-- In "postgres"
CREATE DATABASE google_routes_db WITH ENCODING 'UTF8';

-- In "google_routes_db"
CREATE EXTENSION postgis;
```

BuildGoogleRoutesDBApp creates all necessary tables automatically:
1. `gr_route` table - some common data. Structure: [gr_route DDL](../sql/DDL.scala#L7), [Update.GoogleRouteItem](../sql/Update.scala#L14)
1. `gr_route_leg` table - route leg properties. Structure: [gr_route_leg DDL](../sql/DDL.scala#L22), [Update.GoogleRouteLegItem](../sql/Update.scala#L80)

#### How to run BuildGoogleRoutesDBApp
Config file `build_google_routes_db.conf` ([example](../build/config/build_google_routes_db.conf)) must be provided. See [build_google_routes_db-template.conf](../build/config/build_google_routes_db-template.conf) for configuration structure.

```
./gradlew :execute \
  -PmainClass=beam.utils.google_routes_db.build.BuildGoogleRoutesDBApp \
  -PappArgs="['--config','src/main/scala/beam/utils/google_routes_db/build/config/build_google_routes_db.conf']" \
  -PlogbackCfg=logback.xml
```

## Pre-built GoogleRoutesDB dump
Pre-built GoogleRoutesDB dump is stored in repo as _beam/docker/postgresql/data/google_routes_db_dump.sql_.

The dump is updated once in a while.

**TODO:** dump must be moved to LFS storage.
