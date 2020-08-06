# R5 vs GraphHopper
Issue: [#2804](https://github.com/LBNL-UCB-STI/beam/issues/2804)  
PR: [#2841](https://github.com/LBNL-UCB-STI/beam/pull/2841) 

## How to run?
```
./gradlew :execute \
  -PmainClass=beam.utils.analysis.r5vsgh.R5vsGraphHopper \
  -PappArgs="['--config','test/input/beamville/beam.conf']" \
  -PlogbackCfg=logback.xml
```

Additional arguments may be specified:
* `'--plan','/path/to/plans.csv[.gz]'` to override the scenario plans
* `'--population-sampling-factor','0.05'` to limit the input size

See [runR5vsGraphHopper.sh](r5vsgh/runR5vsGraphHopper.sh) for guidance.

## What is the output?
Output directory (see `beam.outputs.baseOutputDirectory` config value) will be created as usual. It will contain `R5vsGraphHopper` directory:
* `ghLocation` - GraphHopper network cache (derived from R5 network)
* `gpx` - resulting routes per person in GPX format.
* `r5_routes.csv.gz` - routing results of R5 algorithm. `is_error` column indicates route computation success.
* `gh_routes.csv.gz` - routing results of GraphHopper. Same structure as `r5_routes.csv.gz`.

**GPX** files may be imported as Layers to [QGIS](https://qgis.org) or [My Google Maps](https://google.com/mymaps) for visualisation. **QGIS** Delimited Text Layer import: check `localCRS` value in BEAM config (`beam.spatial` group) for proper **Geometry CRS** setting.

It is convenient to publish results to `beam-outputs bucket`. See [publishToBeamOutputs.sh](../scripts/publishToBeamOutputs.sh) for guidance.

## How to analyze results?
Use [r5vsgh.ipynb](../scripts/r5vsgh.ipynb) to digest routing results and [QGIS](https://qgis.org) or [My Google Maps](https://google.com/mymaps) to visualise GPX files.

Check out some reports:
* [Austin](reports/austin.md)
* [Beamville](reports/beamville.md)

