package beam.sim;

import beam.EVGlobalData;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSitePolicy;
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;

import java.util.LinkedHashMap;

/**
 * BEAM
 */
public class LinkAttributeLoader {
    public static LinkedHashMap<String,LinkedHashMap<String,String>> loadLinkAttributes() {
        LinkedHashMap<String,LinkedHashMap<String,String>> result = new LinkedHashMap<String,LinkedHashMap<String,String>>();

        TabularFileParser fileParser = new TabularFileParser();
        TabularFileParserConfig fileParserConfig = new TabularFileParserConfig();

        fileParserConfig.setFileName(EVGlobalData.data.LINK_ATTRIBUTE_FILEPATH);
        fileParserConfig.setDelimiterRegex(",");
        TabularFileHandler handler = new TabularFileHandler() {
            public LinkedHashMap<String, Integer> headerMap;

            @Override
            public void startRow(String[] row) {
                if (headerMap == null) {
                    headerMap = new LinkedHashMap<String, Integer>();
                    for (int i = 0; i < row.length; i++) {
                        String colName = row[i].toLowerCase();
                        if (colName.startsWith("\"")) {
                            colName = colName.substring(1, colName.length() - 1);
                        }
                        headerMap.put(colName, i);
                    }
                } else {
                    String linkId = row[headerMap.get("linkid")];
                    String groupId = row[headerMap.get("group")];

                    LinkedHashMap<String,String> linkMap = new LinkedHashMap<String,String>();
                    linkMap.put("group",groupId);

                    result.put(linkId,linkMap);
                }
            }
        };
        fileParser.parse(fileParserConfig, handler);

        return result;
    }
}
