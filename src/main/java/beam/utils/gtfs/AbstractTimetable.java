package beam.utils.gtfs;

import com.conveyal.r5.analyst.scenario.AddTrips;

public abstract class AbstractTimetable {
    public String _id;

    /** Days of the week on which this service is active, 0 is Monday */
    public boolean monday, tuesday, wednesday, thursday, friday, saturday, sunday;

    /** allow naming entries for organizational purposes */
    public String name;

    /** start time (seconds since GTFS midnight) */
    public int startTime;

    /** end time for frequency-based trips (seconds since GTFS midnight) */
    public int endTime;

    /** headway for frequency-based patterns */
    public int headwaySecs;

    /** Should this frequency entry use exact times? */
    public boolean exactTimes;

    /** Phase at a stop that is in this modification */
    public String phaseAtStop;

    /**
     * Phase from a timetable (frequency entry) on another modification.
     * Syntax is `${modification._id}:${timetable._id}`
     */
    public String phaseFromTimetable;

    /** Phase from a stop that can be found in the phased from modification's stops */
    public String phaseFromStop;

    /** Amount of time to phase from the other lines frequency */
    public int phaseSeconds;

    public AddTrips.PatternTimetable toBaseR5Timetable () {
        AddTrips.PatternTimetable pt = new AddTrips.PatternTimetable();
        pt.entryId = _id;

        // Days
        pt.monday = monday;
        pt.tuesday = tuesday;
        pt.wednesday = wednesday;
        pt.thursday = thursday;
        pt.friday = friday;
        pt.saturday = saturday;
        pt.sunday = sunday;

        if (exactTimes) {
            int totalDepartures = (endTime - startTime) / headwaySecs;
            pt.firstDepartures = new int[totalDepartures];
            for (int i = 0, departure = startTime; departure < endTime; i++) {
                pt.firstDepartures[i] = departure;
                departure += headwaySecs;
            }
        } else {
            if (phaseAtStop != null) {
                pt.phaseAtStop = phaseAtStop;
                pt.phaseFromStop = phaseFromStop;
                pt.phaseSeconds = phaseSeconds;

                if (phaseFromTimetable != null && phaseFromTimetable.length() > 0) {
                    pt.phaseFromTimetable = phaseFromTimetable.split(":")[1];
                }
            }

            pt.startTime = startTime;
            pt.endTime = endTime;
            pt.headwaySecs = headwaySecs;
        }

        return pt;
    }
}
