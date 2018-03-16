"""
Instructions:
    This file is used to generate synthetic household data for BEAM inputs. It has been
    tested for the SF Bay Area.

     >> Note that the census files are too large to keep in the sample file directory, but all other sample inputs are
    already provided.

    >> In order to use this script for population synthesis for the SF Bay area, you will need to download the
    appropriate census data.

    >> Raw data may be downloaded here: https://www.census.gov/programs-surveys/acs/data/pums.html.

    >> Place the files in sample_data (or wherever you are keeping __all__ input files).

    >> Note that some fields may not match those expected by doppelganger. You may need to edit the census files
    (i.e., ssXXhYY.csv and ssXXpYY.csv) to ensure that column headers match expected doppelganger inputs. Typically,
    this involves just lower-casing the column names.

"""

import logging
import os
import pandas as pd
import dask.dataframe as dd
import random
from shapely.geometry import Polygon, Point
import geopandas as gpd
import numpy as np
from tqdm import tnrange, tqdm_notebook

from doppelganger import Configuration, allocation, Preprocessor, PumsData, Accuracy



logging.basicConfig(filename='logs', filemode='a', level=logging.INFO)

### CONFIGURATION CONSTANTS ####:

# Load pumas (these will be based on the pumas you actually want to generate data for...
# You can use a GIS to find these.
puma_df = pd.read_csv('input/sample_data/sfbay_puma_from_intersection.csv', dtype=str)
# Select 2010 PUMA column for filtering
puma_df_clean = puma_df.PUMACE10
PUMA = puma_df_clean.iloc[0]
STATE = '06'  # change to your state as appropriate
AOI_NAME = 'sfbay'  # change to your area of interest name (preferably short).
STATE_ABBREVIATION = 'CA'  # two letter postal code abbreviation

# Directory you will use for outputs
output_dir = 'output'

# Directory you will use for inputs
input_dir = 'input'

# We grab some Census data using the Census API. Enter your Census key below (if you need one, you can get one for free here).
census_api_key = ''
gen_pumas = ['state_{}_puma_{}_generated.csv'.format(STATE, puma) for puma in puma_df_clean]


def create_household_and_population_dfs():
    # Read __downloaded___ (see link above) population level PUMS (may take a while...)
    person_pums_df = pd.read_csv('input/ss14p{}.csv'.format(STATE_ABBREVIATION.lower()), na_values=['N.A'],
                                 na_filter=True)
    # Read __downloaded__ (see link above) household level PUMS (may take a while...)
    household_pums_df = pd.read_csv('input/ss14h{}.csv'.format(STATE_ABBREVIATION.lower()), na_values=['N.A'],
                                    na_filter=True)
    # filter household data and population data to AOI
    person_df_in_aoi = person_pums_df[person_pums_df['PUMA10'].isin(puma_df_clean.values)]
    person_df_in_aoi.loc[:, 'puma'] = person_df_in_aoi['PUMA10']
    household_df_in_aoi = household_pums_df[household_pums_df['PUMA10'].isin(puma_df_clean.values)]
    household_df_in_aoi.loc[:, 'puma'] = person_df_in_aoi['PUMA10']
    # Save for later use
    person_df_in_aoi.to_csv('input/{}_person_pums_data.csv'.format(AOI_NAME), index_label='index')
    household_df_in_aoi.to_csv('input/{}_household_pums_data.csv'.format(AOI_NAME), index_label='index')
    return household_df_in_aoi, person_df_in_aoi


configuration = Configuration.from_file('./input/sample_data/config.json')
household_fields = tuple(set(
    field.name for field in allocation.DEFAULT_HOUSEHOLD_FIELDS).union(
    set(configuration.household_fields)
))
persons_fields = tuple(set(
    field.name for field in allocation.DEFAULT_PERSON_FIELDS).union(
    set(configuration.person_fields)
))


def population_generator():
    # households_data_dirty, person_data_dirty = load_household_and_population_dfs()
    pumas_to_go = set(puma_df_clean.values.tolist())

    for puma in puma_df_clean:
        gen_puma = 'state_{}_puma_{}_households.csv'.format(STATE, puma)
        if gen_puma in os.listdir(output_dir):
            print(puma)
            pumas_to_go.remove(puma)

    puma_tract_mappings = 'input/2010_puma_tract_mapping.txt'
    configuration = Configuration.from_file('input/config.json')
    preprocessor = Preprocessor.from_config(configuration.preprocessing_config)

    for puma_id in pumas_to_go:
        households_data = PumsData.from_csv('input/{}_household_pums_data.csv'.format(AOI_NAME)).clean(household_fields,
                                                                                                       preprocessor,
                                                                                                       state=str(
                                                                                                           int(STATE)),
                                                                                                       puma=str(int(
                                                                                                           puma_id)))
        persons_data = PumsData.from_csv('input/{}_person_pums_data.csv'.format(AOI_NAME)).clean(persons_fields,
                                                                                                 preprocessor,
                                                                                                 state=str(int(STATE)),
                                                                                                 puma=str(int(puma_id)))
        person_segmenter = lambda x: None
        household_segmenter = lambda x: None
        print("loaded")

        household_model, person_model = create_bayes_net(
            STATE, puma_id, output_dir,
            households_data, persons_data, configuration,
            person_segmenter, household_segmenter
        )

        marginals, allocator = download_tract_data(
            STATE, puma_id, output_dir, census_api_key, puma_tract_mappings,
            households_data, persons_data
        )

        print('Allocated {}'.format(puma_id))
        population = generate_synthetic_people_and_households(
            STATE, puma_id, output_dir, allocator,
            person_model, household_model
        )

        print('Generated {}'.format(puma_id))
        accuracy = Accuracy.from_doppelganger(
            cleaned_data_persons=persons_data,
            cleaned_data_households=households_data,
            marginal_data=marginals,
            population=population
        )

        logging.info('Absolute Percent Error for state {}, and puma {}: {}'.format(STATE, puma_id,
                                                                                   accuracy.absolute_pct_error().mean()))
    _combine_and_synthesize()


def get_random_point_in_polygon(poly):
    (minx, miny, maxx, maxy) = poly.bounds
    while True:
        p = Point(random.uniform(minx, maxx), random.uniform(miny, maxy))
        if poly.contains(p):
            return p


def _combine_and_synthesize():
    df_comb = dd.read_csv('state_{}_puma_*_generated.csv'.format(STATE))
    df_next = df_comb[['tract', 'num_people', 'num_vehicles', 'household_id_x', 'serial_number', 'repeat_index']]
    df_next.compute().to_csv('combined_pop.csv')
    df = pd.read_csv('combined_pop.csv')
    df.num_people = df.num_people.replace('4+', 4).astype(int)
    tract_sd = pd.concat([df['num_people']['std'], df['num_vehicles']['std']], axis=1)
    tract_sd.columns = ['hh_sd', 'car_sd']

    tract_gdf = gpd.read_file("input/sample_data/tl_2016_{}_tract/tl_2016_{}_tract.shp".format(STATE, STATE))

    tract_sd_df = pd.DataFrame(tract_sd)
    tract_sd_df['tract'] = tract_sd_df.index
    df = df.drop(['Unnamed: 0', 'serial_number', 'repeat_index', 'person_id'], axis=1)
    df.drop_duplicates(inplace=True)

    def compute_row(i):
        row = df.iloc[i]
        tract_no = row.tract
        pt = get_random_point_in_polygon(tract_gdf[(tract_no == tract_gdf.tract)].geometry.values[0])
        return np.array([str(row.household_id_x), int(row.num_people), int(row.num_vehicles), float(pt.x), float(pt.y)])

    res = []
    for i in tnrange(df.shape[0], desc='1st loop'):
        res.append(compute_row(i))
    out_df = dd.from_array(res).compute()
    out_df.to_csv("output/hhOut.csv", header=False, index=False)


if __name__ == '__main__':

    from sys import path
    import os.path as osp
    path.append(osp.join(__file__, 'scripts'))
    path.append(osp.dirname(osp.dirname(osp.abspath(__file__))))
    from download_allocate_generate import create_bayes_net, download_tract_data, \
        generate_synthetic_people_and_households


    create_household_and_population_dfs()
    population_generator()
