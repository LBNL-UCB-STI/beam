package beam.playground.jdeqsim.akka.parallel.qsim;

import akka.actor.UntypedActor;

public class QFakeModelActor extends UntypedActor {

    private QFakeModel qFakeModel;

    public QFakeModelActor() {
        qFakeModel = new QFakeModel();
    }

    @Override
    public void onReceive(Object msg) throws Exception {

        qFakeModel.moveLinks();

        getSender().tell("linkMessage", getSelf());

//		 if(msg instanceof String) {
//			 String s=(String) msg;
//			 if (s.equalsIgnoreCase("start")){
//				 qFakeModel.moveLinks();
//			 }
//		 } 
    }

}
