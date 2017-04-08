import psycopg2
import csv
import pandas as pd
import os
import collections

"""
This script is to find the county associated to a charging site
YOU NEED TO RUN THIS ONLY ONCE!
"""

#============================== Functions =================================#
def connectDB(dbname = 'sbdb', user='mygreencar',host='localhost'):
	"""
	Connect to postgreSQL db
	"""
	url =  "dbname=%s user=%s host=%s" % (dbname,user,host)
	try:
		conn = psycopg2.connect(url)
	except:
		print "I am unable to connect to the database"
	return conn, conn.cursor()

def pushQuery(query):
	"""
	Query that does not need return values
	"""
	cur.execute(query)


def pullQuery(query):
	"""
	Query that has return values
	"""
	cur.execute(query)
	return cur.fetchall()

#============================== Main script ===============================#
# Connect to db and get connection manager: conn and cursor: cur
conn, cur = connectDB()

filePath 	= "/Users/mygreencar/Google Drive/beam-developers/model-inputs/calibration-v2/charging-sites.csv"
outputPath  = "charging-sites-with-spatial-group.csv"
dfData = pd.read_csv(inputPath)
lngArr = dfData["longitude"]
latArr = dfData["latitude"]

counties = []
# Get the list of CA counties
for lng,lat in zip(lngArr, latArr):
    county = pullQuery("select name from ca_counties where st_contains(ca_counties.geom, ST_GeomFromText('Point(%s %s)',4326));"%(lng,lat))
    if(len(county)>0):
        counties.append(county[0][0])
    else:
        county = pullQuery("select name from ca_counties where st_dwithin(ca_counties.geom, ST_GeomFromText('Point(%s %s)',4326), 1) ORDER BY ST_Distance(ca_counties.geom, ST_GeomFromText('Point(%s %s)',4326)) limit 1;"%(lng,lat,lng,lat))
        counties.append(county[0][0])
cur.close()
conn.close()

dfCounties = pd.DataFrame(counties,columns=['spatialGroup'])
df = pd.concat([dfData, dfCounties],axis=1)
df.to_csv(outputPath, index=False)