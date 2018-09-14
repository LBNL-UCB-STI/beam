package beam.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class BashUtil {
    /**
     * Method returns the git commit hash or HEAD if git not present
     * git rev-parse --abbrev-ref HEAD
     *
     * @return returns the git commit hash or HEAD if git not present
     */
    public static String getCommitHash() {
        String resp = readCommandResponse("git rev-parse HEAD");
        if (resp != null) return resp;
        return "HEAD"; //for the env where git is not present
    }

    /**
     * Method returns the git branch or master if git not present
     *
     * @return returns the current git branch
     */
    public static String getBranch() {
        String resp = readCommandResponse("git rev-parse --abbrev-ref HEAD");
        if (resp != null) return resp;
        return "master"; //for the env where git is not present
    }

    private static String readCommandResponse(String command) {
        Runtime runtime = Runtime.getRuntime();
        try {
            Process process = runtime.exec(command);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream())
            )) {
                return reader.readLine();
            }
        } catch (Exception e) {
            return null; //for the env where command is not recognized
        }
    }
}
