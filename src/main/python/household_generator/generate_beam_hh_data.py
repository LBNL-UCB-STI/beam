import logging
import os
import pandas as pd

from doppelganger import Configuration, allocation, Preprocessor, PumsData, Accuracy
from doppelganger.scripts import create_bayes_net, download_tract_data, generate_synthetic_people_and_households

logging.basicConfig(filename='logs', filemode='a', level=logging.INFO)

### CONFIGURATION CONSTANTS ####:

# Load pumas (these will be based on the pumas you actually want to generate data for.)
puma_df = pd.read_csv('input/sfbay_puma_from_intersection.csv', dtype=str)
# Select 2010 PUMA column for filtering
puma_df_clean = puma_df.PUMACE10

STATE = '6'  # change to your state as appropriate
PUMA = puma_df_clean.iloc[0]
TABLENAME = 'acs_2015_5yr_pums'
output_dir = '/Users/sfeygin/current_code/python/examples/doppelganger/examples/output'
census_api_key = '4d1ff8f7278171c404244dbe3055addfb97757c7'
gen_pumas = ['state_{}_puma_{}_generated.csv'.format(STATE, puma) for puma in puma_df_clean]


def load_household_and_population_dfs(precomputed=True):
    if not precomputed:
        # Read in population level PUMS (may take a while...)
        person_pums_df = pd.read_csv('input/ss14pca.csv', na_values=['N.A'], na_filter=True)
        # Read in household level PUMS (may take a while...)
        household_pums_df = pd.read_csv('input/ss14hca.csv', na_values=['N.A'], na_filter=True)
        # filter household data and population data to AOI
        person_df_in_aoi = person_pums_df[person_pums_df['PUMA10'].isin(puma_df_clean.values)]
        person_df_in_aoi.loc[:, 'puma'] = person_df_in_aoi['PUMA10']
        household_df_in_aoi = household_pums_df[household_pums_df['PUMA10'].isin(puma_df_clean.values)]
        household_df_in_aoi.loc[:, 'puma'] = person_df_in_aoi['PUMA10']
        # Save for later use
        person_df_in_aoi.to_csv('input/sfbay_person_pums_data.csv', index_label='index')
        household_df_in_aoi.to_csv('input/sfbay_household_pums_data.csv', index_label='index')
    else:
        household_df_in_aoi = pd.read_csv('input/sfbay_household_pums_data.csv')
        person_df_in_aoi = pd.read_csv('input/sfbay_person_pums_data.csv')
    return household_df_in_aoi, person_df_in_aoi


configuration = Configuration.from_file('input/config.json')
household_fields = tuple(set(
    field.name for field in allocation.DEFAULT_HOUSEHOLD_FIELDS).union(
    set(configuration.household_fields)
))
persons_fields = tuple(set(
    field.name for field in allocation.DEFAULT_PERSON_FIELDS).union(
    set(configuration.person_fields)
))


def run():
    # households_data_dirty, person_data_dirty = load_household_and_population_dfs()
    pumas_to_go = set(puma_df_clean.values.tolist())

    for puma in puma_df_clean:
        gen_puma = 'state_6_puma_{}_households.csv'.format(puma)
        if gen_puma in os.listdir(output_dir):
            print(puma)
            pumas_to_go.remove(puma)

    state_id = '06'
    puma_tract_mappings = 'input/2010_puma_tract_mapping.txt'
    configuration = Configuration.from_file('input/config.json')
    preprocessor = Preprocessor.from_config(configuration.preprocessing_config)

    for puma_id in pumas_to_go:
        households_data = PumsData.from_csv('input/sfbay_household_pums_data.csv').clean(household_fields, preprocessor,
                                                                                         state=STATE, puma=str(int(puma_id)))
        persons_data = PumsData.from_csv('input/sfbay_person_pums_data.csv').clean(persons_fields, preprocessor,
                                                                                   state=STATE, puma=str(int(puma_id)))
        person_segmenter = lambda x: None
        household_segmenter = lambda x: None
        print("loaded")

        household_model, person_model = create_bayes_net(
            state_id, puma_id, output_dir,
            households_data, persons_data, configuration,
            person_segmenter, household_segmenter
        )

        marginals, allocator = download_tract_data(
            state_id, puma_id, output_dir, census_api_key, puma_tract_mappings,
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

        logging.info('Absolute Percent Error for state {}, and puma {}: {}'.format(state_id, puma_id,
                                                                                   accuracy.absolute_pct_error().mean()))


if __name__ == '__main__':
    run()