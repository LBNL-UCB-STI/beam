package beam.agentsim.events.handling;

import java.util.Optional;

/**
 * BEAM
 */
public enum BeamEventsFileFormats {
    XML("xml"), CSV("csv"), XML_GZ("xml.gz"), CSV_GZ("csv.gz"), PARQUET("parquet");

    private final String suffix;

    BeamEventsFileFormats(String suffix) {
        this.suffix = suffix;
    }

    public String getSuffix() {
        return suffix;
    }

    public static Optional<BeamEventsFileFormats> from(String format) {
        BeamEventsFileFormats fmt = null;
        if (format.equalsIgnoreCase(XML.suffix)) {
            fmt = BeamEventsFileFormats.XML;
        } else if (format.equalsIgnoreCase(XML_GZ.suffix)) {
            fmt = BeamEventsFileFormats.XML_GZ;
        } else if (format.equalsIgnoreCase(CSV.suffix)) {
            fmt = BeamEventsFileFormats.CSV;
        } else if (format.equalsIgnoreCase(CSV_GZ.suffix)) {
            fmt = BeamEventsFileFormats.CSV_GZ;
        } else if (format.equalsIgnoreCase(PARQUET.suffix)) {
            fmt = BeamEventsFileFormats.PARQUET;
        }

        return Optional.ofNullable(fmt);
    }

}
