# TravelTimeGoogleStatistic: Build database of the computed routes
Issue [#2872](https://github.com/LBNL-UCB-STI/beam/issues/2872)  
PR: [#2904](https://github.com/LBNL-UCB-STI/beam/pull/2904)

## BuildGoogleRoutesDB

[BuildGoogleRoutesDB.scala](../BuildGoogleRoutesDB.scala) populates a [PostgreSQL](https://www.postgresql.org/) database
set up with [PostGIS extension](https://postgis.net/).

A database must be prepared beforehand:
1. Create a database: `CREATE DATABASE google_routes_db WITH ENCODING 'UTF8';`
2. Create PostGIS extension: `CREATE EXTENSION postgis;` 

All necessary tables are created automatically.

### How to run
```
./gradlew :execute \
  -PmainClass=beam.utils.google_routes_db.build.BuildGoogleRoutesDB \
  -PappArgs="['--config','src/main/scala/beam/utils/google_routes_db/biuld/config/boild_google_routes_db.conf']" \
  -PlogbackCfg=logback.xml
```

### Output
1. `gr_route` table - some common data. Structure: [gr_route DDL](../sql/DDL.scala#L7), [Update.GoogleRouteItem](../sql/Update.scala#L14)
2. `gr_route_leg` table - route leg properties. Structure: [gr_route_leg DDL](../sql/DDL.scala#L22), [Update.GoogleRouteLegItem](../sql/Update.scala#L80)

_Note:_ our data contains 1 leg per route response.

