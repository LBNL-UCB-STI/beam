package beam.sim;

import beam.EVGlobalData;
import beam.transEnergySim.chargingInfrastructure.management.ChargingSitePolicy;
import beam.utils.CSVUtil;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.utils.io.tabularFileParser.TabularFileHandler;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParser;
import org.matsim.core.utils.io.tabularFileParser.TabularFileParserConfig;

import java.io.File;
import java.util.LinkedHashMap;

/**
 * BEAM
 */
public class LinkAttributeLoader {
    private static final Logger log = Logger.getLogger(GlobalActions.class);

    public static LinkedHashMap<String,LinkedHashMap<String,String>> loadLinkAttributes() {
        LinkedHashMap<String,LinkedHashMap<String,String>> result = new LinkedHashMap<String,LinkedHashMap<String,String>>();

        if((new File(EVGlobalData.data.LINK_ATTRIBUTE_FILEPATH)).exists()) {
            log.info("Loading link attribute data...")

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
                        String linkId = CSVUtil.getValue("linkid",row,headerMap);
                        String groupId = CSVUtil.getValue("group",row,headerMap);

                        LinkedHashMap<String, String> linkMap = new LinkedHashMap<String, String>();
                        linkMap.put("group", groupId);

                        result.put(linkId, linkMap);
                    }
                }
            };
            fileParser.parse(fileParserConfig, handler);
        }else{
            log.warn("No link attribute file found @ " + EVGlobalData.data.LINK_ATTRIBUTE_FILEPATH);
        }
        for(Link link : EVGlobalData.data.controler.getScenario().getNetwork().getLinks().values()){
            if(!result.containsKey(link.getId().toString())) {
                LinkedHashMap<String, String> linkMap = new LinkedHashMap<String, String>();
                linkMap.put("group", link.getId().toString());
                result.put(link.getId().toString(), linkMap);
            }
        }

        return result;
    }
}
