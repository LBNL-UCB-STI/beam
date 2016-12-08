package beam.playground;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import Action.FSMAction;
import FSM.FSM;
import beam.playground.agents.PersonAgent;
import beam.playground.states.ChoosingMode;
import beam.playground.states.InActivity;
import beam.playground.states.State;
import beam.playground.states.Walking;
import beam.playground.transitions.TransitionFromInActivityToChoosingMode;
import beam.playground.transitions.TransitionFromWalkingToInActivity;
import beam.playground.transitions.TransitionFromChoosingModeToWalking;

public class PlaygroundFun {

	public static void testBeamFSM(){
		State inActivity = new InActivity();
		State choosingMode = new ChoosingMode();
		State walking = new Walking();

		inActivity.addTransition(new TransitionFromInActivityToChoosingMode(inActivity, choosingMode, false));
		choosingMode.addTransition(new TransitionFromChoosingModeToWalking(choosingMode, walking, true));
		walking.addTransition(new TransitionFromWalkingToInActivity(walking, inActivity, false));
		
		PersonAgent agent = new PersonAgent();
		agent.setState(inActivity); 
	}
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
            Logger.getLogger(PlaygroundFun.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SAXException ex) {
            Logger.getLogger(PlaygroundFun.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(PlaygroundFun.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
