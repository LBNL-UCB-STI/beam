from collections import defaultdict
from xml.etree import cElementTree as CET

import geopandas as gpd
import numpy as np
from shapely.geometry import Point

__author__ = 'Andrew A Campbell'



def links_2_counties(net_path, county_path, net_crs={'init' :'epsg:26910'}, county_crs={'init' :'epsg:4326'}):
    '''

    :param net_path:
    :param shp_path:
    :return: (DataFrame) 2 columns: LINK_ID, COUNTY
    '''


    itree = CET.iterparse(net_path)
    event, elem = itree.next()
    # Iterate until whole network and build up the lookup dictionaries
    node_map = defaultdict(Point)
    link_ids = []
    link_midpoints = []
    while elem.tag != 'network':
        ##
        # Build the node map
        ##
        if elem.tag != 'node':
            break  # return null to throw an error.
        while elem.tag == 'node':
            id = elem.attrib['id']
            x = float(elem.attrib['x'])
            y = float(elem.attrib['y'])
            p = Point(x, y)
            node_map[elem.attrib['id']] = p
            elem.clear()
            event, elem = itree.next()
        # Burn off all non-link elems (should only be one nodes elem)
        while elem.tag != 'link':
            elem.clear()
            event, elem = itree.next()
        ##
        # Get the link midpoints
        ##
        while elem.tag == 'link':
            # Find the link's midpoint
            fid = elem.attrib['from']
            tid = elem.attrib['to']
            fp = node_map[fid]
            tp = node_map[tid]
            mid_array = np.true_divide(np.add(fp.xy, tp.xy), 2)
            mid_p = Point(mid_array[0], mid_array[1])  # midp
            link_ids.append(elem.attrib['id'])
            link_midpoints.append(mid_p)
            elem.clear()
            event, elem = itree.next()
        event, elem = itree.next()
    ##
    # Assemble geodataframes and spatial join to the get the counties
    ##
    net_gdf = gpd.GeoDataFrame({'LINK_ID': link_ids, 'geometry': link_midpoints})
    net_gdf.crs = net_crs
    # project to County crs
    net_gdf.to_crs(county_crs, inplace=True)
    # load county gdf
    county_gdf = gpd.read_file(county_path)
    # spatial join to map link midpoints to counties
    # val_total_gdf_2 = gpd.sjoin(val_total_gdf, county_gdf, how='left', op='within')
    joined = gpd.sjoin(net_gdf, county_gdf, how='left', op='within')
    return joined








