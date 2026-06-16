Generated sampled SFBay emissions fixture.

This fixture is self-contained under:
- `test/input/sf-bay`
- `test/input/common`

Contents:
- 1% sampled UrbanSim parquet demand
- 10% sampled freight parquet demand
- copied static assets required by the local config stack
- local copied config stack for the emissions scenario

Sampling:
- Households: 1% deterministic sample by `household_id`
- Persons: filtered to sampled households
- Plans: filtered to sampled persons
- Vehicles: filtered to sampled households
- Blocks: filtered to sampled households' `block_id`
- Freight tours: 10% deterministic sample by `tourId`
- Freight payloads/carriers: filtered to sampled tours

Regenerate with:

```bash
python3 test/input/sf-bay/generate_sampled_inputs.py \
  --urbansim-src /path/to/urbansim/atlas-2019 \
  --freight-src /path/to/freight/20250730/2018-Baseline
```
