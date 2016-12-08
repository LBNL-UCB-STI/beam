package beam.playground;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import Action.FSMAction;
import FSM.FSM;

public class PlayWithGraphs {

	public static void testFSM() {
        try {
            FSM f = new FSM("/Users/critter/Dropbox/ucb/vto/beam-all/easyfsm/src/config/config.xml", new FSMAction() {
                @Override
                public boolean action(String curState, String message, String nextState, Object args) {
                    System.out.println(curState + ":" + message + " : " + nextState);
                    /*
                     * Here we can implement our logic of how we wish to handle
                     * an action
                     */
                    return true;
                }
            });
            f.ProcessFSM("MOVELEFT");
            f.ProcessFSM("MOVE");
            f.ProcessFSM("MOVERIGHT");
        } catch (ParserConfigurationException ex) {
            Logger.getLogger(PlayWithGraphs.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SAXException ex) {
            Logger.getLogger(PlayWithGraphs.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(PlayWithGraphs.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
