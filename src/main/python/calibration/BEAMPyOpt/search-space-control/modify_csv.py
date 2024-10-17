import glob, csv, os
from config import shared


def modify_csv(csv_name):
    reader = csv.reader(open(csv_name, "r"), delimiter='\t') 
    writer = csv.writer(open(shared+'/output.csv', 'w'), delimiter=',')   
    writer.writerows(reader) 
    os.remove(csv_name)  
    num = len(shared) # if on develop branch else 64 if main branch '/home/ubuntu/kiran_thesis/beam'
    os.rename(shared+'/output.csv', shared+'/'+csv_name[num:])  
