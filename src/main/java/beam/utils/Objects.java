package beam.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Created by salma_000 on 8/25/2017.
 */

public class Objects {

    static public Object deepCopy(Object oldObj) throws Exception {
        ObjectOutputStream oos = null;
        ObjectInputStream ois = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream(bos);
            oos.writeObject(oldObj);
            oos.flush();
            ByteArrayInputStream bin = new ByteArrayInputStream(bos.toByteArray());
            ois = new ObjectInputStream(bin);
            return ois.readObject();
        }
        catch(Exception e) {
            System.out.println("Exception in ObjectCloning = " + e);
            throw(e);
        }
        finally {
            oos.close();
            ois.close();
        }
    }
}
