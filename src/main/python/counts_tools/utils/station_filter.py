from collections import defaultdict
from functools import partial
import os
import sys
import traceback
import os.path as osp
import geopandas as gpd
import numpy as np
import pandas as pd
from shapely.geometry import Point
from sklearn import preprocessing, svm
from joblib import Parallel, delayed
import random
import counts

class StationFilter(object):
    """
    Class to filter PeMS Sensor Stations. Maintains a state variable of qualifying Station IDs. This list should get
    progressively smaller as more filtering methods are called.

    The general workflow of using this class is:
    1) Initialize
    2) Add filters
    3) Run filters

    Many of the filters involve looping through the station time series. Adding all the filters first allows us to only
    do this loop once. The third step will populate the cleand_station_ids list.
    """

    #TODO adding stations to the cleaned list w/ each hidden filter is redundant and should be removed. Simply take the
    #TODO difference of all the stations and the removed list

    def __init__(self, ts_path, meta_path):
        """

        :param ts_path: (str) Path to the Station Time Series director created by PeMS_Tools. See
        PeMS_Tools.utilities.station.generate_time_series_V2.
        :param meta_path: (str) Path to the joined and filtered PeMS station metadata file.
        """
        self.ts_path = ts_path
        self.meta_path = meta_path
        self.meta_df = pd.read_csv(self.meta_path, index_col=0)
        self.filters = []   # List of filters to be applied during run_filters.
        self.ts_df = None  # DataFrame of single station time series

        # Initialize the Station ID lists
        self.all_station_ids = np.unique(self.meta_df['ID'])  # All unique IDs in the meta_df
        self.stations = [d for d in os.listdir(self.ts_path) if (osp.exists(osp.join(self.ts_path, d, 'time_series.csv')) and d.isdigit())]
        self.cleaned_station_ids = set()  # final, cleaned, output ID list
        self.removed_station_ids = set()  # List of Station IDs that have been removed
        self.removed_stats_reasons = defaultdict(list)

        # Flags
        self.iter_time_seris = False  # Flag to indicate whether we need to iterate through all the time series files,
        # which is expensive

    def date_range(self, start_date, end_date):
        """
        Checks if station was active between start_date and end_date.
        :param start_date: (datetime.datetime)
        :param end_date: (datetime.datetime)
        :return:
        """
        self.iter_time_seris = True
        partial_date_range = partial(self.__date_range, start_date=start_date, end_date=end_date)
        self.filters.append(partial_date_range)

    def __date_range(self, stat_ID, ts_df, start_date, end_date):
        """
        Hidden implementation of the date_range filter.
        :param stat_ID: (str, int)
        :param start_date:
        :param end_date:
        :return:
        """
        start, end = counts.get_TS_summary_dates('./%s/summary.csv' % stat_ID)
        if (start < start_date) & (end_date < end):  # Check if this station was active during target periods
            self.cleaned_station_ids.add(stat_ID)
        else:
            self.removed_station_ids.add(stat_ID)
            self.removed_stats_reasons[stat_ID].append('date_range')

    def link_mapping(self, stat_link_map_path):
        """
        Checks if station was successfully matched with a link in the MATSim network.
        :param stat_link_map_path: (str) Path to the csv file defining the mapping between station ID and link ID
        :return:
        """
        id_map = pd.read_csv(stat_link_map_path, index_col='ID', dtype='string')
        id_map.index = [str(i) for i in id_map.index]  # convert index back to string for easy lookups below
        # We only have to open stat_link_map_path once by passing in the DataFrame instead of the path.
        partial_link_mapping = partial(self.__link_mapping, stat_link_map=id_map)
        self.filters.append(partial_link_mapping)
    
    def __link_mapping(self, stat_ID, ts_df, stat_link_map):
        """
        Hidden implementation of link_mapping filter.
        :param stat_ID:
        :param stat_link_map: (pd.DataFrame)
        :return:
        """
        if stat_ID in stat_link_map.index:
            self.cleaned_station_ids.add(stat_ID)
        else:
            self.removed_station_ids.add(stat_ID)
            self.removed_stats_reasons[stat_ID].append('link_mapping')

    def missing_data(self, date_list):
        """
        Filters outWriter any stations for which an average hourly flow cannot be calculated. This occurs when there are no
        observations for that hour over all of the dates in date_list. A weakness is that a single hourly observation
        over many days will satisfy this filter.
        :param date_list: ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
        :return:
        """
        self.iter_time_seris = True
        partial_missing_data = partial(self.__missing_data, date_list=date_list)
        self.filters.append(partial_missing_data)

    def __missing_data(self, stat_ID, ts_df, date_list):
        """
        Hidden implementation of missing_data filter.
        :param stat_ID:
        :param date_list:  ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
        :return:
        """
        # Check if the self.ts_df is synced with the current stat_ID being called
        if str(ts_df['Station'][0]) != str(stat_ID):
            sys.exit("ERROR: current self.ts_df does not match stat_ID")

        # Make a small df of only days matching the target day date. It is much much faster to resample the smaller one
        vol_5min = ts_df[ts_df['date'].isin(date_list)][['Total_Flow', 'hour']] # all 5-min observations on desired dates
        vol_5min.index = pd.to_datetime(vol_5min.index)
        #TODO is this really the best way to resample? What if we have one hour with only one 5-minute reading?
        vol_hourly = vol_5min.resample('1h').sum()  # Rollup the 5-minute counts to 1-hr
        vol_hourly['hour'] = [d.hour for d in vol_hourly.index]
        # Now we need to groupby the hour and take the mean
        hr_means = vol_hourly.groupby('hour').mean()  # Mean hourly flows for all days in date_list!!!
        #TODO perhaps we should run this filter earlier. Imagine we have one hour with one observation on one day and the sensor is off for all others.
        if not hr_means['Total_Flow'].isnull().any():
            self.cleaned_station_ids.add(stat_ID)
        else:
            self.removed_station_ids.add(stat_ID)
            self.removed_stats_reasons[stat_ID].append('missing_data')

    def boundary_buffer(self, poly_path, epsg_poly=None, epsg_sensors=4326, buffer_dist=10):
        """
        Removes all sensor stations that are within buffer_dist of the study boundary. This is to avoid edge effects

        :param poly_path: (str) Path to a polygon shapefile defining the study area. This polygon must be "roughly
        convex"! For example, if we used a polygon defining the landmass of the SF Bay Area, the boundary buffer would
        include areas near the the water that we do not want to exclude.
        :param epsg_poly (str | int) EPSG code defining the CRS the polygon uses.
        :param epsg_sensors (str | int) EPSG code defining the CRS the sensor stations are using. Defaults to 4326,
        which is what PeMS uses in the metadata files.
        :param buffer_dist: (float) Distance in KM from study are border to filter outWriter stations. i.e. Any stations
        within buffer_dist of the boundary will be removed.
        :return:
        """

        # NOTE: For SF SmartBay, use the shapefile at:
        # /GoogleDrive/ucb_smartcities_data/2. SF Smart Bay/b. Shapefiles/Dissolve/CA_TAZ_9_counties_Dissolved.shp

        # Load the polygon and convert it to the CRS of the stations
        #TODO this filter should also check that points lie with in the shape defined by poly_path. In D4 we have some
        #TODO outliers in Santa Cruz that are beyond the buffer and totally outWriter of the study area (AAC 16/07/27).
        gdf_poly = gpd.read_file(poly_path)
        if not bool(gdf_poly.crs) | bool(epsg_poly):
            sys.exit('ERROR boundary_buffer: must specify epsg_poly OR shapefile at poly_path must have CRS defined')
        elif not gdf_poly.crs:  # If no CRS specified in shapefile
            gdf_poly.crs = {'makeFacilities': 'epsg:' + str(epsg_poly).strip()}
        elif not epsg_poly:  # If no CRS specified in call to boundary_buffer
            epsg_poly = gdf_poly.crs  # Set equal to the shapefile's CRS

        if str(epsg_poly['init'].lstrip('epsg:')) != str(epsg_sensors):  # Reproject the polygon if not equal
            # NOTE: if poly_path shapefile has no CRS, then this line will fail
            gdf_poly.to_crs({'makeFacilities': 'epsg:' + str(epsg_sensors).strip()}, inplace=True)
        
        # Load the station locations
        df_stat = self.meta_df[['ID', 'Latitude', 'Longitude']].drop_duplicates()
        df_stat.set_index('ID', inplace=True)  # Index is int64
        df_stat['geometry'] = df_stat.apply(lambda x: Point(x['Longitude'], x['Latitude']), axis=1)
        gdf_stat = gpd.GeoDataFrame(df_stat, crs={'makeFacilities': 'epsg:' + str(epsg_sensors).strip()})  # Convert to GeoDataFrame

        #Convert the buff_dist from KM to applicable CRS units
        #TODO We assume WGS84 for now!
        buffer_degrees = buffer_dist * 0.01  # roughly

        #Create the boundary buffer
        boundary = gdf_poly.boundary
        gdf_buffer = gpd.GeoDataFrame(crs=gdf_poly.crs['init'],
                                       geometry=boundary.buffer(buffer_degrees))

        partial_boundary_buffer = partial(self.__boundary_buffer,
                                          buffer_geo_df=gdf_buffer,
                                          stat_gdf=gdf_stat)
        self.filters.append(partial_boundary_buffer)

    def __boundary_buffer(self, stat_ID, ts_df, buffer_geo_df, stat_gdf):
        """
        Hiden implementation of boundary_buffer. geo_df has already been projected to match coordinates of the stations.
        :param stat_ID: (int | str)
        :param buffer_geo_df: (GeoPandas.GeoDataFrame) GeoDataFrame of the polygon defining the boundary buffer.
        :return:
        """
        # Check if station lies within the boundary buffer
        if not buffer_geo_df.contains(stat_gdf.loc[int(stat_ID)].geometry).all():
            self.cleaned_station_ids.add(stat_ID)
        else:
            self.removed_station_ids.add(stat_ID)
            self.removed_stats_reasons[stat_ID].append('boundary_buffer')

    def outlier_detection_SVM(self, date_list, decision_dist, threshold, kernel='rbf', nu=0.5, gamma=0.0):
        #TODO consider using **kwargs to allow for different SVM implementations.
        """
        Uses 1-class SVM to determine the portion of days that are outliers. The keep or remove decision is based on
        hyperparameter

        :param date_list: ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
        :param kernel:
        :param nu:
        :param decision_dist: (int | float) Distance from decision boundary for declaring outlier. NOTE: with the
        sklearn OneClassSVM classifier, the "other" class has negative distances.
        :param threshold: (float) Acceptable fraction of days beyond decision_dist from classification boundary. If more
        than threshold fraction of days are beyond, then we remove the stat_ID.
        :param gamma:
        :return:
        """
        self.iter_time_seris = True
        # Initialize the classifier
        clf = svm.OneClassSVM(kernel=kernel, nu=nu, gamma=0.001)
        partial_outlier_detection_SVM = partial(self.__outlier_detection_SVM, clf=clf,
                                                date_list=date_list,
                                                decision_dist=decision_dist,
                                                threshold=threshold)
        self.filters.append(partial_outlier_detection_SVM)

    def __outlier_detection_SVM(self, stat_ID,ts_df, clf, date_list,  decision_dist=4, threshold=0.05, ):
        """
        Hidden implementation of outlier_detection.
        :param stat_ID: (int | str)
        :param clf: (sklearn.svm.OneClassSVM) Initialized classifier.
        :param date_list: ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
        :param decision_dist: (float) Cutoff distance for defining outliers. Default found through manual testing.
        :param threshold: (float) Fraction of outlier days for a station to be removed. Default chosen based on
        manual testing.
        :return:
        """
        # Check if the self.ts_df is synced with the current stat_ID being called)
        if str(ts_df['Station'][0]) != str(stat_ID):
            sys.exit("ERROR: current self.ts_df does not match stat_ID")

        ##
        # Build the feature matrix, X (each day is a single row)
        ##

        # Make a small df of only days matching the target day date. It is much much faster to resample the smaller one
        vol_5min = ts_df[ts_df['date'].isin(date_list)][['Total_Flow', 'hour', 'date']] # all 5-min observations on desired dates
        vol_5min.index = pd.to_datetime(vol_5min.index)
        # Group by dates
        groups = vol_5min.groupby(['date'])
        n = len(groups.groups.keys())  # number of unique groups
        X = np.empty((n, 24))  # Feature vector n x p
        for i, (dt, g) in enumerate(groups):
            # dt is a date string, g is dataframe
            X[i,:] = g.resample('1h').sum().values.flatten()  # Rollup the 5-minute counts to 1-hr
        # Drop any days w/ NaN
        if np.isnan(X).any():
            # row_mask = ~np.any(np.isnan(X), axis=1)
            # X = X[row_mask, :]  # remove the rows w/ NaN values
            # self.removed_station_ids.add(stat_ID)
            # self.removed_stats_reasons[stat_ID].append('outlier_detection_SVM')
            return

        preprocessing.scale(X, axis=1, copy=False)


        ##
        # Calculate distance from boundary for each day
        ##
        clf.fit(X)  # Train SVM
        dists = clf.decision_function(X)

        ##
        # Count the "outliers" and decide whether or not to keep sensor
        ##
        n_out = np.sum(dists < -1*decision_dist)
        fract_out = np.true_divide(n_out, dists.shape[0])
        if fract_out <= threshold:
            self.cleaned_station_ids.add(stat_ID)
        else:
            self.removed_station_ids.add(stat_ID)
            self.removed_stats_reasons[stat_ID].append('outlier_detection_SVM')

    def observed(self, date_list, threshold=0.5):
        """
        Filter station if a single day is detected where the station has less than threshold observed. The
         PeMS observed attribute describes the fraction of lanes w/ working sensors.
        :param date_list: ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
        :param threshold: (float) Minimum acceptable fraction of observed data.
        :return:
        """
        self.iter_time_seris = True
        partial_observed = partial(self.__observed, date_list=date_list, threshold=threshold)
        self.filters.append(partial_observed)

    def __observed(self, stat_ID, ts_df, date_list, threshold):
        """
        Hidden implementation of observed filter.
        :param stat_ID: (int | str)
        :param date_list: ([str]) List of date strings. Each data should follow the '%m/%d/%Y' format (e.g. 12/31/199)
        :param threshold: (float) Minimum acceptable fraction of observed data.
        :return:
        """
        obsv_5min = ts_df[ts_df['date'].isin(date_list)][['Observed', 'date']] # all 5-min observations on desired dates
        means = obsv_5min.groupby(['date']).mean()
        if not (means['Observed'] < threshold).any():
            self.cleaned_station_ids.add(stat_ID)
        else:
            self.removed_station_ids.add(stat_ID)
            self.removed_stats_reasons[stat_ID].append('observed')


    def run_filters(self):
        """
        Run all the filters in self.filters.
        :param check_removed: (bool) Check if as a station has already been added to the removed_station_ids by a
         previous filter. Skips the remaining filters.
        :return:
        """
        # Check if filters have been initialized before running.
        if not bool(self.filters):
            sys.exit("ERROR run_filters: no filters have been initialized.")
        # Get list of all stations in the time series folder
        o_dir = os.getcwd()
        os.chdir(self.ts_path)
        # stations = [n for n in os.listdir('.') if n.isdigit()]  # list of all station folder names

        # Iterate through all the stations and apply filters

        stations = self.stations

        Parallel(n_jobs=11, prefer="threads")(delayed(self.apply_filter)(stat) for stat in stations)

        # Remove all stations from the cleaned list that are in the removed list.
        self.cleaned_station_ids = set(self.stations).difference(self.removed_station_ids)
        os.chdir(o_dir)

    def apply_filter(self, stat):
        print 'Processing station: %s' % stat

        ts_df = pd.read_csv('./%s/time_series.csv' % stat, index_col='Timestamp')
        ts_df['date'] = [d[0:10] for d in ts_df.index]
        ts_df['hour'] = [d[-8:-6] for d in ts_df.index]
        # Apply all the filters in the self.filters
        for filter in self.filters:
            # TODO setting check_removed to False will cause the OneClass_SVM filtering to break due to empty features (Andrew 16/07/25)
            if stat in self.removed_station_ids:
                break
            filter(str(stat), ts_df)

    def set_stations(self, stat_list):
        """
        Manually define the stat_IDs to run the filters on. This is useful for testing a single station that is
        causing erros.
        :param stat_list: ([str]) List of stat_IDs
        :return:
        """
        self.stations = stat_list

    def write_cleaned_stations(self, cleaned_out_path):
        df = pd.DataFrame({'ID': self.cleaned_station_ids})
        meta_xy = self.meta_df[['ID', 'Latitude', 'Longitude']].drop_duplicates()
        meta_xy['ID'] = meta_xy['ID'].astype(str)
        merged = pd.merge(df, meta_xy, left_on='ID', right_on='ID', how='inner')
        merged.to_csv(cleaned_out_path, index=False)

    def write_removed_stations(self, removed_out_path):
        df = pd.DataFrame({'ID': self.removed_station_ids})
        meta_xy = self.meta_df[['ID', 'Latitude', 'Longitude']].drop_duplicates()
        meta_xy['ID'] = meta_xy['ID'].astype(str)
        merged = pd.merge(df, meta_xy, left_on='ID', right_on='ID', how='inner')
        merged.to_csv(removed_out_path, index=False)

    def write_removed_reasons_log(self, removed_reasons_path):
        with open(removed_reasons_path, 'w') as fo:
            for stat in self.removed_stats_reasons.items():
                fo.write(str(stat)[1:-1] + '\n')








        








