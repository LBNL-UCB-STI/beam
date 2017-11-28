package beam.utils;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Id;

/**
 * BEAM
 */
public class DebugLib {
    private static final Logger log = Logger.getLogger(DebugLib.class);

    public static void traceAgent(Id personId){
        if (personId.toString().equalsIgnoreCase("3711631")){
            emptyFunctionForSettingBreakPoint();
        }
    }
    public static void traceAgent(Id personId, String idToCatch){
        if (personId.toString().equalsIgnoreCase(idToCatch)){
            emptyFunctionForSettingBreakPoint();
        }
    }

    public static void traceAgent(Id personId, int flag){
        if (personId.toString().equalsIgnoreCase("128") && flag==19){
            emptyFunctionForSettingBreakPoint();
        }

        if (personId.toString().equalsIgnoreCase("1364464") && flag==2){
            emptyFunctionForSettingBreakPoint();
        }
    }

    public static void assertTrue(boolean val, String errorString){
        if (!val){
            stopSystemAndReportInconsistency(errorString);
        }
    }

    public static void stopSystemAndReportMethodWhichShouldNeverHaveBeenCalled(){
        DebugLib.stopSystemAndReportInconsistency("this method should never be called");
    }

    public static void startDebuggingInIteration(int iterationNumber){
        if (iterationNumber==18){
            System.out.println();
        }
    }

    public static void stopSystemAndReportUnknownMessageType(){
        stopSystemAndReportInconsistency("unknown message type");
    }

    public static void stopSystemAndReportInconsistency(String errorString){
        String msg = "system is in inconsistent state: " + errorString;
        log.error(msg);
        throw new Error("system is in inconsistent state: " + errorString);
    }

    public static void stopSystemAndReportInconsistency(){
        String msg = "system is in inconsistent state";
        log.error(msg);
        throw new Error("system is in inconsistent state");
    }

    public static void stopSystemWithError(){
        stopSystemAndReportInconsistency();
    }

    public static void criticalTODO(){
        stopSystemAndReportInconsistency("critical TODO still missing here");
    }

    public static void continueHere(){

    }

    public static void emptyFunctionForSettingBreakPoint(){

    }

    public static void haltSystemToPrintCrutialHint(String hintString){
        log.error(hintString);
        throw new Error(hintString);
    }
}