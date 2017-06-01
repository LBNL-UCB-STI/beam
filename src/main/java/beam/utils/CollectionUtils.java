package beam.utils;

import java.util.Collection;

/**
 * Created by ahmar.nadeem on 6/6/2017.
 */
public final class CollectionUtils {

    /**
     *
     * @param collection
     * @return
     */
    public static boolean isNotEmpty(Collection collection) {

        return !isEmpty(collection);
    }

    /**
     *
     *
     * @param collection
     * @return
     */
    public static boolean isEmpty(Collection collection){
        if( collection == null || collection.size() == 0 ){
            return true;
        }
        return false;
    }
}
