# Mapping between maps.googleapi.responses.json and  googleTravelTimeEstimation.csv
For every computed route in _maps.googleapi.responses.json_ (`responses`) there is an entry in _googleTravelTimeEstimation.csv_ (`requests`) that represents original routing request.  Our goal is to reconstruct one-to-one relationship.

[GoogleRouteRequest](https://github.com/LBNL-UCB-STI/beam/blob/aaltergot/google_routes_db/src/main/scala/beam/utils/google_routes_db/request/package.scala#L12) - structure of `request` 
[GoogleRoutes](https://github.com/LBNL-UCB-STI/beam/blob/aaltergot/google_routes_db/src/main/scala/beam/utils/google_routes_db/response/package.scala#L9) - structure of `response`

We compare Origin-Destination pairs to find the match between `request` and `response`. There are no more common properties to employ except coordinates. The cartesian product `requests x responses` is filtered keeping pairs with close coordinates. The proximity is calculated using "distance in meters" metric and a threshold - some `epsilon`. 

Using [new-york-200k-..._gpo](https://beam-outputs.s3.amazonaws.com/output/newyork/new-york-200k-flowCap_0.025__2020-08-09_23-27-02_gpo/ITERS/it.10/10.maps.googleapi.responses.json) data, calculating **for each request all matching responses**, we get the following result set sizes frequencies:

| epsilon | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| 1.0E-5 - 1.0E-1 | 2431 | 0 | 2 | 3 | 0 | 0 | 12 |
| 1 | 2202 | 0 | 4 | 3 | 56 | 15 | 168 |
| 10 | 132 | 0 | 90 | 15 | 332 | 25 | 1854 |
| 1100 | 4 | 0 | 112 | 15 | 360 | 25 | 1932 |

Calculating **for each response all matching** requests on the same data, the frequencies are:

| epsilon | 0 | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|---|
| 1.0E-5 - 1.0E-1 | 2429 | 0 | 1 | 3 | 0 | 0 | 12 |
| 1 | 2200 | 0 | 3 | 3 | 56 | 15 | 168 |
| 10 | 131 | 0 | 89 | 15 | 332 | 25 | 1853 |
| 1100 | 4 | 0 | 111 | 15 | 360 | 25 | 1930 |

Detailed results: [ReqRespMapping.csv](ReqRespMapping.csv)
