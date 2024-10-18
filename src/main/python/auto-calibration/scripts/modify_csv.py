import glob, csv, os
import pandas as pd
from config import shared, init_runs, parallel_run


def modify_csv(csv_name):
    reader = csv.reader(open(csv_name, "r"), delimiter='\t')
    writer = csv.writer(open(shared + '/output.csv', 'w'), delimiter=',')
    writer.writerows(reader)
    os.remove(csv_name)
    num = len(shared)  # if on develop branch else 64 if main branch '/home/ubuntu/kiran_thesis/beam'
    os.rename(shared + '/output.csv', shared + '/' + csv_name[num:])


def find_min_csv():
    op_folder = f'{shared}/'
    files = glob.glob(op_folder + '*')
    names = [file[len(op_folder):-4] for file in files]
    names.sort(key=lambda x: int(x.split('_')[1]))
    return shared + '/' + names[0] + '.csv'


def find_intercept(filename):
    df = pd.read_csv(filename)
    df.drop(['iterations'], axis='columns', inplace=True)
    return df[2:3].values.tolist()[0]