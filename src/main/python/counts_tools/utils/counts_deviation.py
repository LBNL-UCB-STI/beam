import numpy as np
import pandas as pd


class CountsDeviation(object):
    """
    Class to calculate and store various measures of count deviation from some central tendency (mean, median, etc.) for
    a single sensor station and one or more days. Must be using the 5-minute rollups as "raw" data.

    The point of having this class is that we could calculate many measures while only having to open the Station
    Time_Series once.
    """

    #NEXT STEPS:
    # 0 - I want to be able to calc SSqD for both a mean and median line. Thus I need to be able to store multiple
    #   measures of the same type.
    # 1 - all for custom bins of times for rolling up (not just hours)

    def __init__(self, ts_path, dates, measure_names=None):
        """

        :param self:
        :param ts_path: (str) Path to the station TimeSeries
        :param measure_names:
        :param dates: ([str]) List of strings defining dates in the '%m/%d/%Y' format.
        :return:
        """
        self.names = measure_names
        self.dates = dates
        self.measures = {}
        self.day_measures = {}
        self.ts = pd.DataFrame(pd.read_csv(ts_path, index_col='Timestamp')['Total_Flow'])  # This is the big heavy time_series file we only want to
        self.ts['date'] = [dt[0:10] for dt in self.ts.index]  # '%m/%d/%Y'
        self.ts['hour'] = [dt[-8:-6] for dt in self.ts.index]

        # Prune the time_series to only include the target dates
        self.ts = self.ts[[d in dates for d in self.ts['date']]]  # much faster to use date-strings then datetimes

        # Test for missing data
        #TODO perhaps we could use a threshold instead of simply cutting it off if a single 5-minute bin is missing?
        self.missing = self.ts.shape[0] != len(self.dates)*24*12
        if self.missing:
            print "WARNING: missing data"
            print

        # Now, if no data is missing, roll up the queueStartTime bins to whatever specified by time_incr
        # if not(self.missing) and time_incr not in ['5min', '5Min']:
        # if time_incr not in ['5min', '5Min']:
        #     self.ts.index = self.ts.index.to_datetime()
        #     self.ts = self.ts.resample(time_incr, how="sum").dropna()
        #     #WARNING we have now switched the type of the index. Need to pick one !

        if not(self.missing):  # roll it up into one-hour
            self.ts = self.ts.groupby(['date', 'hour']).sum().reset_index()

    def calc_measure(self, measure, **kwargs):
        #TODO consider changing these to *args. The **kwargs dict is confusing. Hard to know you need to use "reference=variable"
        dev = self.deviation_factory(measure, **kwargs)
        self.measures[measure] = dev.get_measure()

    def calc_all_meausures(self, **kwargs):
        """
        :param kwargs: ([[]]) List of lists. Each sublist is the kwargs for the corresponding measure in
        self.names.
        :returns: (dict) Dict of measures. Key is measure name, value is an array or scalar.
        """
        for m, k in zip(self.names, kwargs):
            #  Use a factory_method here to implement different deviation measures
            dev = self.deviation_factory(m, k)
        return dev.get_measure()

    def deviation_factory(self, name, **kwargs):
        """
        Factory to build the deviation measure.
        """
        deviations = {'RMSD': RMSD, 'SSqD': SSqD, 'SSqDR': SSqDR, 'SignedSSqDR': SignedSSqDR,
                      'SignedSDR': SignedSDR}
        return deviations[name](self, **kwargs)


class SSqD(object):
    """
    Sum of squared deviations over whole day. Returns a scalar, s:
    s = sum_i=1:24 (c_i - c_bar_i)^2
    where c_i is the actual count at hour_i and c_bar_i is the reference count
    (mean, median, etc.)
    """

    def __init__(self, CD, reference):
        """
        :param CD: Self of the calling CountsDeviation.
        :param reference: (pd.Series) The reference tendency to measure against (i.e. 24 hours of mean flows)
        """
        self.__reference = reference
        self.__measure = None
        # Compare each day in the dates to reference
        # Create a DF w/ the date strings as the indeces and the SSqD for that day as the only column
        values = np.zeros(len(CD.dates))
        for i, dt in enumerate(CD.dates):
            temp = CD.ts[CD.ts['date'] == dt]  # subset of only days matching the date of dt
            values[i] = np.sum(np.square(temp['Total_Flow'].values - reference.values))
        self.__measure = values

    def get_measure(self):
        return self.__measure

class SSqDR(object):
    """
    Sum of Squared Deviation Ratio. over whole day. This is the normalized version of SSqD.
    Returns a scalar, s:
    s = sum_i=1:24 [(c_i - c_bar_i)^2 / c_bar_i^2]
    where c_i is the actual count at hour_i and c_bar_i is the reference count
    (mean, median, etc.)
    """

    def __init__(self, CD, reference):
        """
        :param CD: Self of the calling CountsDeviation.
        :param reference: (pd.Series) The reference tendency to measure against (i.e. 24 hours of mean flows)
        """
        self.__reference = reference
        self.__measure = None
        # Compare each day in the dates to reference
        # Create a DF w/ the date strings as the indeces and the SSqD for that day as the only column
        values = np.zeros(len(CD.dates))
        for i, dt in enumerate(CD.dates):
            temp = CD.ts[CD.ts['date'] == dt]  # subset of only days matching the date of dt
            values[i] = np.divide(np.sum(np.square(temp['Total_Flow'].values - reference.values)),
                                  np.sum(np.square(reference.values)))
        self.__measure = values

    def get_measure(self):
        return self.__measure


class SignedSSqDR(object):
    """
    Signed Sum of Squared Deviation Ratio. This is the  signed and normalized version of SSqD.
    Returns a scalar, s:
    s = sign(c_i - c_bar_i)*sum_i=1:24 [(c_i - c_bar_i)^2 / c_bar_i^2]
    where c_i is the actual count at hour_i and c_bar_i is the reference count
    (mean, median, etc.)
    """

    def __init__(self, CD, reference):
        """
        :param CD: Self of the calling CountsDeviation.
        :param reference: (pd.Series) The reference tendency to measure against (i.e. 24 hours of mean flows)
        """
        self.__reference = reference
        self.__measure = None
        # Compare each day in the dates to reference
        # Create a DF w/ the date strings as the indeces and the SSqD for that day as the only column
        values = np.zeros(len(CD.dates))
        for i, dt in enumerate(CD.dates):
            temp = CD.ts[CD.ts['date'] == dt]  # subset of only days matching the date of dt
            diff = temp['Total_Flow'].values - reference.values
            numr = np.sum(np.multiply(np.sign(diff), np.square(np.multiply(diff, temp['Total_Flow'].values - reference.values))))
            denom = np.sum(np.square(reference.values))
            values[i] = np.divide(numr, denom)
        self.__measure = values

    def get_measure(self):
        return self.__measure

class SignedSDR(object):
    """
    Signed Sum Deviation Ratio. This is a first order polynomial version of SignedSSqDR.
    Returns a scalar, s:
    s = sign(c_i - c_bar_i)*sum_i=1:24 [(c_i - c_bar_i) / c_bar_i]
    where c_i is the actual count at hour_i and c_bar_i is the reference count
    (mean, median, etc.)
    """

    def __init__(self, CD, reference):
        """
        :param CD: Self of the calling CountsDeviation.
        :param reference: (pd.Series) The reference tendency to measure against (i.e. 24 hours of mean flows)
        """
        self.__reference = reference
        self.__measure = None
        # Compare each day in the dates to reference
        # Create a DF w/ the date strings as the indeces and the SSqD for that day as the only column
        values = np.zeros(len(CD.dates))
        for i, dt in enumerate(CD.dates):
            temp = CD.ts[CD.ts['date'] == dt]  # subset of only days matching the date of dt
            diff = temp['Total_Flow'].values - reference.values
            numr = np.sum(np.multiply(np.sign(diff), np.multiply(diff, temp['Total_Flow'].values - reference.values)))
            denom = np.sum(reference.values)
            values[i] = np.divide(numr, denom)
        self.__measure = values

    def get_measure(self):
        return self.__measure

class RMSD(object):
    """
    Root Mean Squared Deviation
    """







