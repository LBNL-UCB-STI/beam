package beam.utils;

import java.util.Collection;
import java.util.function.Consumer;
import static org.apache.commons.collections4.CollectionUtils.*;

/**
 * Created by ahmar.nadeem on 6/6/2017.
 */
public final class Collections {
    /**
     * If a collection is present, invoke the specified action for each of the member of collection,
     * otherwise do nothing.
     *
     * @param collection collection under action if non empty, otherwise do nothing
     * @param action block to be executed for each member if a collection is non empty
     * @throws NullPointerException if collection is present and {@code action} is
     * null
     */
    public static <T> void ifPresentThenForEach(Collection<T> collection, Consumer<T> action) {
        if (isNotEmpty(collection)) {
            collection.forEach(action);
        }
    }
}
