package beam.utils.exception;

/**
 * Created by ahmar.nadeem on 7/19/2017.
 */
public class BeamException extends RuntimeException {

    public BeamException() {
        super();
    }

    public BeamException(String message) {
        super(message);
    }

    public BeamException(Throwable throwable) {
        super(throwable);
    }

    public BeamException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
