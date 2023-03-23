package beam.utils.gtfs;

import beam.sim.config.BeamConfig;
import com.univocity.parsers.common.processor.RowListProcessor;
import com.univocity.parsers.csv.CsvParserSettings;
import com.univocity.parsers.csv.CsvWriter;
import com.univocity.parsers.csv.CsvWriterSettings;
import com.univocity.parsers.csv.CsvParser;
import org.matsim.core.utils.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Takes care of retrieving {@link Operator} data.
 * <p>
 * Created by sfeygin on 11/11/16.
 */
public class OperatorDataUtility {

    private static final Logger log = LoggerFactory.getLogger(TransitDataDownloader.class);

    private String opMapPath;
    private String apiKey;

    public OperatorDataUtility(BeamConfig config) {
//        opMapPath = config.beam().routing().gtfs().operatorsFile();
//        apiKey=BEAM_CONFIG.beam().routing().gtfs().apiKey();
    }


    public Map<String, String> getOperatorMap() {
        Map<String, String> operatorMap;
        if (new File(opMapPath).exists()) {
            operatorMap = readOperatorMapFromFile(opMapPath);
        } else {
            log.info("Operator key file not found. Downloading and saving...");
            operatorMap = downloadOperatorMap(apiKey);
            saveOperatorMap(opMapPath, operatorMap);
        }
        return operatorMap;
    }


    private void saveOperatorMap(String opMapPath, Map<String, String> operatorMap) {
        try {
            CsvWriter csvWriter = new CsvWriter(IOUtils.getBufferedWriter(opMapPath), new CsvWriterSettings());
            final String[] opKeyArray = operatorMap.keySet().toArray(new String[0]);
            csvWriter.writeHeaders(opKeyArray);
            for (Map.Entry<String, String> entry : operatorMap.entrySet()) {
                csvWriter.addValue(entry.getKey(), entry.getValue());
            }
            csvWriter.writeValuesToRow();
            csvWriter.close();
        } catch (Exception e) {
            log.error("exception occurred due to ", e);
        }
        log.info(String.format("Operator key file saved at %s", opMapPath));
    }

    private Map<String, String> readOperatorMapFromFile(String opMapPath) {
        CsvParserSettings csvParserSettings = new CsvParserSettings();
        csvParserSettings.setHeaderExtractionEnabled(true);
        RowListProcessor rowProcessor = new RowListProcessor();
        csvParserSettings.setProcessor(rowProcessor);
        CsvParser csvParser = new CsvParser(csvParserSettings);
        final String[] headers;
        Map<String, String> res = null;
        try {
            csvParser.parse(IOUtils.getBufferedReader(opMapPath));
            headers = rowProcessor.getHeaders();
            String[] rows = rowProcessor.getRows().get(0);
            int index = 0;
            for (String header : headers){
                res.put(header, rows[index++]);
            }
        } catch (Exception e) {
            log.error("exception occurred due to ", e);
        }
        return res;
    }

    private Map<String, String> downloadOperatorMap(String apiKey) {
        final TransitDataDownloader downloader = TransitDataDownloader.getInstance(apiKey);
        List<Operator> transitOperatorList = downloader.getTransitOperatorList();
        return transitOperatorList.stream().distinct().collect(Collectors.toMap(Operator::getName, Operator::getPrivateCode));
    }


}
