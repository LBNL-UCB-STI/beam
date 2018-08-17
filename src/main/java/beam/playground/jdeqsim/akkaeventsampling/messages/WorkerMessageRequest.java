package beam.playground.jdeqsim.akkaeventsampling.messages;

import java.io.Serializable;


public class WorkerMessageRequest implements IRequest, Serializable {
    private RouterMessageRequest routerMessage;

    public WorkerMessageRequest(RouterMessageRequest routerMessage) {
        this.routerMessage = routerMessage;
    }

    public RouterMessageRequest getRouterMessage() {
        return routerMessage;
    }
}
