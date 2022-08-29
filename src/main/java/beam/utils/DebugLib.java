package beam.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class DebugLib {
    private static final Logger log = LoggerFactory.getLogger(DebugLib.class);

    public static void stopSystemAndReportInconsistency(String errorString) {
        throw new IllegalStateException("system is in inconsistent state: " + errorString);
    }

    public static void stopSystemAndReportInconsistency() {
        throw new IllegalStateException("system is in inconsistent state");
    }

    public static void emptyFunctionForSettingBreakPoint() {

    }

    public static String getMemoryLogMessage(String message) {
        long jvmTotalMemoryInBytes = Runtime.getRuntime().totalMemory();
        long jvmFreeMemoryInBytes = Runtime.getRuntime().freeMemory();
        long jvmMemoryInUseInBytes = jvmTotalMemoryInBytes - jvmFreeMemoryInBytes;
        double jvmMemoryInUseInGigabytes = jvmMemoryInUseInBytes / Math.pow(1000, 3);
        return message + String.format("%.2f (GB)", jvmMemoryInUseInGigabytes);
    }

    public static void busyWait(int nanos) {
        long start = System.nanoTime();
        while(System.nanoTime() - start < nanos);
    }

    /*
    Dumps all current threads
    */
    public static List<String> currentThreadsDump() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = bean.dumpAllThreads(true, true);
        return Arrays.stream(infos).map(Object::toString).collect(Collectors.toList());
    }
}
