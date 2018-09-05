import geopandas as gpd
import pandas as pd
from shapely.geometry import Point

def text_to_points_gdf(path, x_col, y_col, sep=',', usecols=None, crs={'init' :'epsg:4326'}):
    """

    :param path:
    :param x_col:
    :param y_col:
    :param crs:
    :parma sep:
    :param usecols:
    :param crs:
    :return:
    """
    df = pd.read_csv(path, sep=sep, usecols=usecols)
    df['geometry'] = df.apply(lambda z: Point(z[x_col], z[y_col]), axis=1)
    gdf = gpd.GeoDataFrame(df)
    gdf.crs = crs
    return gdf


