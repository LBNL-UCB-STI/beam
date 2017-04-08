import psycopg2
import csv

"""
This script is to insert charging site data into local database to get advantage of using postgis.
YOU NEED TO RUN THIS ONLY ONCE!
"""

#============================== Functions =================================#
def connectDB(dbname = 'sbdb', user='mygreencar',host='localhost'):
	url =  "dbname=%s user=%s host=%s" % (dbname,user,host)
	try:
		conn = psycopg2.connect(url)
	except:
		print "I am unable to connect to the database"
	return conn

def pushQuery(query):
	cur.execute(query)

def pullQuery(query):
	cur.execute(query)
	return cur.fetchall()


#============================== Main script ===============================#
# Connect to db
conn = connectDB()
# Get the curser
cur = conn.cursor()

# Read csv file here
filePath 	= "/Users/mygreencar/Google Drive/beam-developers/model-inputs/development/charging-sites-1k.csv"
idArr 		= [] # id array
latArr 		= [] # latitude array 
lngArr 		= [] # longitude array 
pidArr 		= [] # policy id array
nidArr 		= [] # network id array
with open(filePath) as csvfile:
	reader = csv.DictReader(csvfile)
	for row in reader:
		idArr.append(row["id"])
		latArr.append(row["latitude"])
		lngArr.append(row["longitude"])
		pidArr.append(row["policyID"])
		nidArr.append(row["networkOperatorID"])


# Check if we already have charging site data in th db
query = """SELECT id FROM charging_sites"""
ids = pullQuery(query)
if(len(ids) > 1):
	raise ValueError('WE ALREADY HAVE THE DATA INSERTED IN THE DATABASE')
else: 
	# Insert charging site locations into database
	for iid, lat, lng, pid, nid in zip(idArr, latArr, lngArr, pidArr, nidArr):
		query = """INSERT INTO charging_sites(id, latitude, longitude, policy_id, network_operator_id, geom) VALUES(%s, %s, %s, %s, %s, ST_GeomFromText('Point(%s %s)',4326));""" %(iid,lat,lng,pid,nid,lng,lat)
		pushQuery(query)
	conn.commit()

cur.close()
conn.close()
