# Author: Kiran Chhatre
# Implementation 2 related
import numpy as np
import pandas as pd
import itertools, glob, fnmatch, os, random, csv, math, pickle
from natsort import natsorted
import warnings
from config import *

warnings.simplefilter(action='ignore', category=FutureWarning)

def getNudges(whichCounter):

    input_vector = []
    ##rel_nudge_stages = list(range(init_runs,total_rel_nudge_trials+1, 4))
    rel_nudge_stages = list(range(init_runs,total_rel_nudge_trials+1, parallel_run)) # total init random runs = 8 [8, 12, 16, 20, 24, 28, 32, 36]

    if whichCounter == init_runs:                                        # total init random runs = 8
        last_needed_csv = 1
    else:
        quotient = (whichCounter - init_runs - 1)//parallel_run                         # total init random runs = NEW SETUP! old=17 for 16
        #last_needed_csv = rel_nudge_stages[rel_nudge_stages.index(whichCounter)-1] 
        last_needed_csv = rel_nudge_stages[quotient]               # total init random runs = NEW SETUP!

    last_needed_csv_path = glob.glob(shared+'/'+str(last_needed_csv)+'_*.csv')[0]
    validate = any([len(fnmatch.filter(os.listdir(shared), '*.csv')) == last_needed_csv, len(fnmatch.filter(os.listdir(shared), '*.csv')) > last_needed_csv-1])
    while not os.path.exists(last_needed_csv_path):                       # Condition 1 to verify the file exists
        time.sleep(5)
        print('In relativeFactoredNudges: waiting for the last BEAM output csv...')
    while not validate:                                                   # Condition 2 to be true
        time.sleep(5)
        print('Waiting to validate required number of csv files for nudge compuations...')

    if whichCounter == init_runs:                                       # total init random runs = 8
        print('Creating nudges for stage 1...')
        csv_name = glob.glob(shared+'/1_*.csv')[0]
        df = pd.read_csv(csv_name)
        for j in range(init_runs-1):                                      # total init random runs = 7
            vector_4_gradients = []
            for i in range(1,len(df.loc[5])):
                if df.loc[5][i] == 1:
                    vector_4_gradients.append([np.random.uniform(0,20)])           # modified limits to 1.5 11.5 
                else:
                    vector_4_gradients.append([np.random.uniform(-20,0)])             # modified limits to -14 -1
            vector_4_gradients = list(itertools.chain(*vector_4_gradients))
            input_vector.append(vector_4_gradients)  # 15 vector for first 16 runs based on the directionality

    else:

        '''
        CSV FETCHING METHODS
        methodA : predetermined CSV selection
        methodB : Chooses the best csv from either group and create 4 pairs from two groups where one side its only one csv and other side 4
        methodC : CSV selection based on pair who's L1 sum is least
        methodD : CSV selection based on selection two best from the whole lot, 4 such pair, one side one csv, other side 2nd-5th best 4 csv
        methodE : 1 best from the whole lot with combination of 2 best and 2 worst to acheive larger dL1/dm
        methodF : 1 best vs 4 worst from all batch
        methodG : advanced methodD
        methodH : MNL Inspired
        '''

        def methodB():
            prev_list, next_list, names = ([] for i in range(3))
            files = glob.glob(shared+'/*')
            for i in range(len(files)):
                names.append(files[i][77:-4])  # extract file names only
            names_sorted = natsorted(names, key=lambda x: x.split('_')[0]) # sort with iteration number

            if whichCounter == 12: # select best 5 CSVs
                names_sorted.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values
                next_list = [names_sorted[0]] * 4
                prev_list = names_sorted[1:5]

            else: # find best CSV from either stage and create 4 pair accordingly
                if whichCounter == 16:
                    list_one = names_sorted[whichCounter-16:whichCounter-8] # leveraging to look at all 8 outputs so as to choose the best four CSVs
                else:
                    list_one = names_sorted[whichCounter-12:whichCounter-8]
                list_two = names_sorted[whichCounter-8:whichCounter-4]
                list_one_min = min(list_one, key=lambda x: int(x.split('_')[1]))
                list_two_min = min(list_two, key=lambda x: int(x.split('_')[1]))
                if int(list_one_min.split('_')[1]) < int(list_two_min.split('_')[1]):
                    next_list = [list_one_min] * 4
                    prev_list = list_two
                else:
                    next_list = [list_two_min] * 4
                    list_one.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values
                    prev_list = list_one[0:4]
            return prev_list, next_list

        def methodC():
            prev_list, next_list, names = ([] for i in range(3))
            files = glob.glob(shared+'/*')
            for i in range(len(files)):
                names.append(files[i][77:-4])  # extract file names only
            names_sorted = natsorted(names, key=lambda x: x.split('_')[0]) # sort with iteration number

            if whichCounter == 12: # select best 5 CSVs
                names_sorted.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values
                next_list = [names_sorted[0]] * 4
                prev_list = names_sorted[1:5]

            else:
                if whichCounter == 16:
                    list_one = names_sorted[whichCounter-16:whichCounter-8] # leveraging to look at all 8 outputs so as to choose the best four CSVs
                else:
                    list_one = names_sorted[whichCounter-12:whichCounter-8]
                list_two = names_sorted[whichCounter-8:whichCounter-4] # SIZE 4
                list_one.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values
                list_one_trunc = list_one[0:4] # SIZE 4
                combined = [list(zip(x,list_one_trunc)) for x in itertools.permutations(list_two,len(list_one_trunc))]
                combined_merged = list(itertools.chain(*combined))
                unique_combined_merged = list(set(combined_merged))         # for final comparison
                tmp_L1 = []
                for i in range(len(unique_combined_merged)):
                    tmp_L1.append((int(unique_combined_merged[i][0].split('_')[1]),int(unique_combined_merged[i][1].split('_')[1])))
                L1_sum_sorted= sorted(tmp_L1, key=lambda x: sum(x))
                top_4_L1 = L1_sum_sorted[0:4]                                # for final comparison
                for i in range(len(unique_combined_merged)):
                    if tuple((int(unique_combined_merged[i][0].split('_')[1]), int(unique_combined_merged[i][1].split('_')[1]))) in top_4_L1:
                        next_list.append(unique_combined_merged[i][0])
                        prev_list.append(unique_combined_merged[i][1])
            return prev_list, next_list

        def methodD():
            print('Fetch method is D')
            prev_list, next_list, names = ([] for i in range(3))
            files = glob.glob(shared+'/*')
            for i in range(len(files)):
                names.append(files[i][77:-4])  # extract file names only
            names_sorted = natsorted(names, key=lambda x: x.split('_')[0]) # sort with iteration number
            names_sorted.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values

            # Comparison loop to avoid duplicate stage runs which starts with 12,16,20,24...
            start = 0
            if whichCounter != 12:
                csv_4_comparison = natsorted(names, key=lambda x: x.split('_')[0])[0:whichCounter-8] # sort with iteration number upto whichCounter-8
                csv_4_comparison.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values
                if csv_4_comparison[0:5] == names_sorted[0:5]:      # checking similarity at first batch
                    start = 1

            # set df lists for computation
            next_list = [names_sorted[0]] * 4
            prev_list = names_sorted[start+1:start+5]
            return prev_list, next_list

        def methodE():
            prev_list, next_list, names = ([] for i in range(3))
            files = glob.glob(shared+'/*')
            for i in range(len(files)):
                names.append(files[i][77:-4])  # extract file names only
            names_sorted = natsorted(names, key=lambda x: x.split('_')[0]) # sort with iteration number
            names_sorted.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values

            # Comparison loop to avoid duplicate stage runs which starts with 12,16,20,24...
            start = 0
            end = 0
            if whichCounter != 12:
                csv_4_comparison = natsorted(names, key=lambda x: x.split('_')[0])[0:whichCounter-8] # sort with iteration number upto whichCounter-8
                csv_4_comparison.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values
                if csv_4_comparison[0:3] == names_sorted[0:3]:      # checking similarity at first batch
                    start = 1
                    if csv_4_comparison[1:4] == names_sorted[1:4]:  # checking similarity at second batch
                        start = 2
                if csv_4_comparison[-2:len(csv_4_comparison)] == names_sorted[-2:len(names_sorted)]:
                    end = -1
                    if csv_4_comparison[-3:len(csv_4_comparison)-1] == names_sorted[-3:len(names_sorted)-1]:
                        end = -2

            # set df lists for computation
            next_list = [names_sorted[start]] * 4
            prev_list = names_sorted[start+1:start+3] + names_sorted[-2+end:len(names_sorted)+end]
            return prev_list, next_list

        def methodF():
            prev_list, next_list, names = ([] for i in range(3))
            files = glob.glob(shared+'/*')
            for i in range(len(files)):
                names.append(files[i][77:-4])  # extract file names only
            names_sorted = natsorted(names, key=lambda x: x.split('_')[0]) # sort with iteration number
            names_sorted.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values

            # Comparison loop to avoid duplicate stage runs which starts with 12,16,20,24...
            end = 0
            start = 0
            if whichCounter != 12:
                csv_4_comparison = natsorted(names, key=lambda x: x.split('_')[0])[0:whichCounter-8] # sort with iteration number upto whichCounter-8
                csv_4_comparison.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values
                if csv_4_comparison[-4:len(csv_4_comparison)] == names_sorted[-4:len(names_sorted)]:
                    end = -1
                    if csv_4_comparison[-3:len(csv_4_comparison)-1] == names_sorted[-3:len(names_sorted)-1]:
                        end = -2

            # set df lists for computation
            next_list = [names_sorted[start]] * 4
            prev_list = names_sorted[-4+end:len(names_sorted)+end]
            return prev_list, next_list

        def methodG():
            print('Fetch method is G')
            prev_list, next_list, names, old_compared_csv = [] = ([] for i in range(4))
            files = glob.glob(shared+'/*')
            for i in range(len(files)):
                names.append(files[i][77:-4])  # extract file names only
            names_sorted = natsorted(names, key=lambda x: x.split('_')[0]) # sort with iteration number
            names_sorted.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values

            # Comparison loop to avoid duplicate stage runs which starts with 12,16,20,24...
            try:
                validate = pickle.load(open("fetched_files.txt","rb"))
            except EOFError:
                validate = []

            if not validate:
                prev_list = names_sorted[1:5]
            else:
                for i in range(len(validate)):
                    if names_sorted[0] == validate[i][0]:
                        old_compared_csv.append(validate[i][1:5])
                old_compared_csv = list(itertools.chain(*old_compared_csv)) # flatten
                old_compared_csv = list(set(old_compared_csv)) # remove duplicates

                for i in range(len(names_sorted)-1):
                    if names_sorted[i+1] in old_compared_csv:
                        pass
                    else:
                        prev_list.append(names_sorted[i+1])
                prev_list.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values

            # set df lists for computation
            next_list = [names_sorted[0]] * 4
            prev_list = prev_list[0:4]

            if not validate:
                updated_fetched_list = [[names_sorted[0]] + prev_list]
            else:
                what_to_append = [names_sorted[0]] + prev_list
                validate.append(what_to_append)
                updated_fetched_list = validate

            with open("fetched_files.txt", "wb") as fp: #how to save in format [[56], [55],[66]]
                pickle.dump(updated_fetched_list, fp)

            return prev_list, next_list

        def methodH():
            print('Fetch method is H')
            prev_list1, prev_list, next_list, names, old_compared_csv = [] = ([] for i in range(5))
            files = glob.glob(shared+'/*')

            # sorting all memory bank according to their L1 norm
            for i in range(len(files)):
                names.append(files[i][len(shared)+1:-4])  # extract file names only
            names_sorted = natsorted(names, key=lambda x: x.split('_')[0]) # sort with iteration number
            names_sorted.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values

            # Comparison loop to avoid duplicate stage runs which starts with 12,16,20,24...
            try:
                validate = pickle.load(open("fetched_files.txt","rb"))
            except EOFError:
                validate = []

            if validate:
                # checking if least error from the current stage was seen before,
                # if so collecting all csv list that were compared to this least error
                for i in range(len(validate)):
                    if names_sorted[0] == validate[i][0]:  # validate is [ [a,b]  , [v,r] ... ]
                        old_compared_csv.append(validate[i][1:5])
                old_compared_csv = list(itertools.chain(*old_compared_csv)) # flatten
                old_compared_csv = list(set(old_compared_csv)) # remove duplicates

                # removing all previously compared least errors from originally sorted memory bank
                for i in range(len(names_sorted)-1):
                    if names_sorted[i+1] in old_compared_csv:
                        pass
                    else:
                        prev_list1.append(names_sorted[i+1])
                prev_list1.sort(key=lambda x: int(x.split('_')[1])) # sort with L1 norm values

            min_err = names_sorted[0].split('_')[1]
            ''' OTHER EXPERIMENTED STUFF FROM MNL, DIDNT VALIDATE
            best=[]
            for i in range(len(names_sorted)):
                if names_sorted[i].split('_')[1] == min_err:
                    best.append(names_sorted[i])
            random.shuffle(best) 
            next_list = best[0] 
            '''
            # Creating primary next list
            next_list = [names_sorted[0]] * 4
            # created list of csv which dont have same least err as best least err
            for_prev=[]
            for i in range(len(prev_list1)):
                if prev_list1[i].split('_')[1] != min_err:
                    for_prev.append(prev_list1[i])
            for_prev.sort(key=lambda x: int(x.split('_')[1]))

            if validate:
                prev_list = for_prev[0:4]
            else:
                prev_list = names_sorted[1:5]

                # only for 5 and 6 error: take last two best err CSVs, if num of files is less than 4 then execute the following two loops:
            if len(names_sorted[0].split('_')[1]) == 1:
                if names_sorted[0].split('_')[1] == '5' or names_sorted[0].split('_')[1] == '6':
                    # arrange next list
                    best_n=[]
                    for i in range(len(names_sorted)):
                        if names_sorted[i].split('_')[1] == min_err:
                            best_n.append(names_sorted[i])
                    random.shuffle(best_n)
                    if len(best_n) < 4:
                        next_list = [best_n[0]] * 4
                    else:
                        next_list = best_n[0:4]
                    # arrange prev list
                    best_p = [item for item in names_sorted if item not in best_n]
                    best_p.sort(key=lambda x: int(x.split('_')[1]))
                    nex_min_err = best_p[0].split('_')[1]
                    leaving_one_set = []
                    for i in range(len(best_p)):
                        if best_p[i].split('_')[1] == nex_min_err:
                            leaving_one_set.append(best_p[i])
                    if len(leaving_one_set) < 4:
                        how_many_to_add = 4-len(leaving_one_set)
                        add_more_from = list(set(best_p) - set(leaving_one_set))
                        add_more_from.sort(key=lambda x: int(x.split('_')[1]))
                        for i in range(how_many_to_add):
                            leaving_one_set.append(add_more_from[:i+1][0])
                    random.shuffle(leaving_one_set)
                    prev_list = leaving_one_set

                    # Add Gaussian error for 1 stage (4 iters) if min error has not improved for last 5 stage length (20iters) after 56 total iters
            if validate:
                checker = []
                if len(validate) > 10:
                    recent_err = validate[-1][0].split('_')[1]
                    check_repetition = validate[-5:]
                    for i in range(len(check_repetition)):
                        if check_repetition[i][0].split('_')[1] == recent_err:
                            checker.append(1)
                        else:
                            pass
                    if len(checker) == 5:
                        print('The best CSV in memory bank has been repeated for continous last 5 stage length! Adjusting fetches...')
                        names_sorted.sort(key=lambda x: int(x.split('_')[1]))
                        exclude_best_err = []
                        for i in range(len(names_sorted)):
                            if names_sorted[i].split('_')[1] != recent_err:
                                exclude_best_err.append(names_sorted[i])
                        exclude_best_err.sort(key=lambda x: int(x.split('_')[1]))
                        next_list = [exclude_best_err[0]] * 4 # second best
                        third_best = []
                        for i in range(len(exclude_best_err)):
                            if exclude_best_err[i].split('_')[1] != exclude_best_err[0].split('_')[1]:
                                third_best.append(exclude_best_err[i])
                        third_best.sort(key=lambda x: int(x.split('_')[1]))
                        prev_list = third_best[0:4] # third best
                    else:
                        print('The optimizer has past 11 stages and has been improving since last 5 stages!')

            if not validate:
                updated_fetched_list = [[next_list[0]] + prev_list]
            else:
                what_to_append = [next_list[0]] + prev_list
                validate.append(what_to_append)
                updated_fetched_list = validate


            with open("fetched_files.txt", "wb") as fp: #how to save in format [[56], [55],[66]]
                pickle.dump(updated_fetched_list, fp)

            return prev_list, next_list


        #for 'methodA'
        # example if last needed csv = 12 (say 'i'), we will compute nudges for 9,10,11,12 trails from (1,5),(2,6),(3,7), and (4,8) pairs.
        # input_vector = i-3 i-2 i-1 i-0   |@16| 13 14 15 16  |@12| 9 10 11 12
        # prev         = i-11 i-10 i-9 i-8 |   | 5 6 7 8      |   | 1 2 3 4
        # next         = i-7 i-6 i-5 i-4   |   | 9 10 11 12   |   | 5 6 7 8
        # BOTTOM LINE IS BROKEN FOR INIT 16 RANDOM RUNS, only works for 8 random runs
        #iterators_ip_vec, iterators_prev, iterators_next = list(range(3,-1,-1)), list(range(11,7,-1)), list(range(7,3,-1))

        prev_list, next_list = methodH()
        print('Prev list is ', prev_list)
        print('Next list is ', next_list)

        for i in range(parallel_run):
            print('Computing nudges for '+str(i+1)+' substage...')

            #for 'methodA'
            #df_prev =  pd.read_csv(glob.glob(shared+'/'+str(whichCounter-iterators_prev[i])+'_*.csv')[0])
            #df_next =  pd.read_csv(glob.glob(shared+'/'+str(whichCounter-iterators_next[i])+'_*.csv')[0])

            # otherwise for methodB methodC methodD
            df_prev =  pd.read_csv(shared+'/'+prev_list[i]+'.csv')
            df_next =  pd.read_csv(shared+'/'+next_list[i]+'.csv')
            # Create a separate df from df_next with 2 cols: L1 and L1_rank
            Rank_L1_df = df_next.loc[3:4].T
            # Compute relative ratios of top 4 worst performing mode choices wrt to observed L1 norms
            # Example: {'ride_hail': 1.0, 'walk_transit': 0.45877255803345796, 'walk': 0.40894045161300785, 'ride_hail_transit': 0.3808596172941896}
            fetch_ratios = {}
            ''' modifying as inpired from MNL
            for i in range(1,9): # finding ratios for all 8 choices
                fetch_ratios[list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().keys())[0]] = abs(list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().values())[0] / list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == 1,3].to_dict().values())[0])
            '''

            if len(next_list[0].split('_')[1]) == 2 and next_list[0].split('_')[1] in ['10','11','12','13']:
                for i in range(1,4): # finding ratios for 3 choices
                    fetch_ratios[list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().keys())[0]] = abs(list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().values())[0]*1 / list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == 1,3].to_dict().values())[0])
            elif len(next_list[0].split('_')[1]) == 1 and next_list[0].split('_')[1] in ['7','8','9']:
                for i in range(1,3): # finding ratios for 2 choices
                    fetch_ratios[list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().keys())[0]] = abs(list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().values())[0]*1 / list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == 1,3].to_dict().values())[0])
            elif len(next_list[0].split('_')[1]) == 1 and next_list[0].split('_')[1] in ['3','4','5','6']:
                for i in range(1,2): # finding ratios for 1 choice
                    fetch_ratios[list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().keys())[0]] = abs(list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().values())[0]*1 / list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == 1,3].to_dict().values())[0])
            else:
                for i in range(1,9): # finding ratios for all 8 choices
                    fetch_ratios[list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().keys())[0]] = abs(list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == int('{i}'.format(i=i)),3].to_dict().values())[0]*1 / list(Rank_L1_df.loc[Rank_L1_df.iloc[:, 1] == 1,3].to_dict().values())[0])

            relative_variation_factor = df_next.loc[3].to_dict()
            del relative_variation_factor['iterations']
            relative_variation_factor = dict.fromkeys(relative_variation_factor, 0)
            # update relative variation factors for all mode choices
            # Example: {'bike': 0, 'car': 0, 'drive_transit': 0, 'ride_hail': 1.0, 'ride_hail_pooled': 0, 'ride_hail_transit': 0.3808596172941896, 'walk': 0.40894045161300785, 'walk_transit': 0.45877255803345796}
            relative_variation_factor.update(fetch_ratios)
            # create a df of rows: intercepts_now and L1 for first 2 rows from df_prev and next 2 from df_next
            compute_df = pd.concat([df_prev.loc[list(range(0,1)) + list(range(3,4))],df_next.loc[list(range(0,1)) + list(range(3,4))]], ignore_index=True, sort =False)
            del compute_df['iterations']
            # compute d_L1/d_m = (L1_next-L1_prev)/(m_next-m_prev) where m: intercept and this value is an !!! ABSOLUTE VALUE
            # required addition or substraction is taken care of by other variables
            compute_df.loc['d_L1/d_m'] = abs(compute_df.loc[3].astype(float) - compute_df.loc[1].astype(float)) / abs(compute_df.loc[2].astype(float) - compute_df.loc[0].astype(float))
            # check if L1 has decreased or not: 1 yes, 0 no
            compute_df.loc['L1_progress'] = [ 1 if min([compute_df.loc[1][x], compute_df.loc[3][x]], key=abs) == compute_df.loc[3][x]  else 0 for x in range(len(compute_df.loc[3]))]
            # check if modes were incremented or decremented: 1 decremented, 0 incremented
            compute_df.loc['m_progress'] = [ 1 if compute_df.loc[0][x] > compute_df.loc[2][x] else 0 for x in range(len(compute_df.loc[3]))]
            # compute direction for the nudge: 1 negative, 0 positive
            # Analogy: if L1 has decreased -> follow the same pattern in modes(increments remains increment and so on), otherwise reverse the sign (increments changes to decrement and so on)
            compute_df.loc['nudge_direction'] = [ compute_df.loc['m_progress'][x] if compute_df.loc['L1_progress'][x].astype(int) == 1  else 1-compute_df.loc['m_progress'][x].astype(int) for x in range(len(compute_df.loc[3]))]
            # append previously computed relative factors
            compute_df.loc['factor'] = relative_variation_factor
            # finally calculate the input vector for the subsequent stage
            # Formula: m_(t+1) = m_(t) +or- (relative_factor) * (d_L1/d_m)
            # !!! REMINDER: we are only optimizing the worst performing first four mode choices !!!
            # However the top 4 worst performing mode choices are recalculated at each stage, so once the higher worse performing choices are optimized, the model will automatically shift the optimization towards the lesser worse performing mode choices
            compute_df.loc['m_(t+1)'] = [compute_df.loc[2][x]-compute_df.loc['factor'][x]*compute_df.loc['d_L1/d_m'][x] if compute_df.loc['nudge_direction'][x].astype(int) == 1 else compute_df.loc[2][x]+compute_df.loc['factor'][x]*compute_df.loc['d_L1/d_m'][x] for x in range(len(compute_df.loc[3]))]
            # Final test to check inf or -inf or nan returned values due to unforeseen computations
            inf_test = [ 1 if abs(compute_df.loc['m_(t+1)'][x]) == math.inf or math.isnan(compute_df.loc['m_(t+1)'][x]) else 0 for x in range(len(compute_df.loc['m_(t+1)']))]
            # rolling back such intercept values to the values found in df_next as a temporary solution!!
            for i in range(len(inf_test)):
                if inf_test[i] == 1:
                    compute_df.loc['m_(t+1)'][i] = compute_df.loc[2][i]
                else:
                    pass
            input_vector.append(compute_df.loc['m_(t+1)'].tolist())

            # at the end of this loop, it will return 4 input vectors

    return input_vector
