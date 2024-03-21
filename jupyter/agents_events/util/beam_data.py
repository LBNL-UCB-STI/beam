import time
import pandas as pd
import xml.etree.ElementTree as ET
from shapely.geometry import LineString
from shapely.geometry import Point


def load_events(events_path, chunk_filter = lambda df: [True] * len(df.index), chunksize=100000):
    start_time = time.time()
    # Read first 20 rows in order to get all columns
    columns = pd.read_csv(events_path, low_memory=False, nrows=20).columns
    schema = {}
    # Make all of them `str` at first
    for col in columns:
        schema[col] = str
    # Assign correct type for specific columns
    schema["time"] = int
    schema["numPassengers"] = pd.Int64Dtype()
    schema["capacity"] = pd.Int64Dtype()
    schema["seatingCapacity"] = pd.Int64Dtype()
    schema["length"] = float
    schema["startX"] = float
    schema["startY"] = float
    schema["endX"] = float
    schema["endY"] = float
    schema["fuel"] = float
    schema["cost"] = float
    schema["primaryFuelLevel"] = float
    schema["secondaryFuelLevel"] = float
    schema['departureTime'] = pd.Int64Dtype()
    schema['arrivalTime'] = pd.Int64Dtype()
    schema["departTime"] = pd.Int64Dtype()

    df = pd.concat(
        [df[chunk_filter(df)] for df in pd.read_csv(events_path, low_memory=False, chunksize=chunksize, dtype=schema)])
    df['hour'] = (df['time'] / 3600).astype(int)
    print("events file url:", events_path)
    print("loading took %s seconds" % (time.time() - start_time))
    # only columns that contains values
    return df[df.columns[~df.isnull().all()]]

def read_links(name):
    #     f=gzip.open(name,'rb')
    f = open(name, 'rb')
    root = ET.parse(f).getroot()
    nodes = {node.get('id'): Point(float(node.get('x')), float(node.get('y'))) for node in root.find('nodes')}
    links = root.find('links')
    return {int(link.get('id')):
                {'length': float(link.get('length')),
                 'capacity': float(link.get('capacity')),
                 'freespeed': float(link.get('freespeed')),
                 'from': nodes[link.get('from')],
                 'to': nodes[link.get('to')],
                 'line': LineString([nodes[link.get('from')], nodes[link.get('to')]]),
                 'centroid': LineString([nodes[link.get('from')], nodes[link.get('to')]]).centroid,
                 } for link in links}