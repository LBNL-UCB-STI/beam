package beam.physsim.jdeqsim;

import org.matsim.core.mobsim.jdeqsim.Message;
import org.matsim.core.mobsim.jdeqsim.MessageQueue;

import java.util.PriorityQueue;

public class LockingMessageQueue implements MessageQueue {
    private final PriorityQueue<Message> queue1 = new PriorityQueue<Message>();
    private int queueSize = 0;

    /**
     *
     * Putting a message into the queue
     *
     * @param m
     */
    @Override
    public synchronized void putMessage(Message m) {
        queue1.add(m);
        queueSize++;
    }

    /**
     *
     * Remove the message from the queue and discard it. - queue1.remove(m) does
     * not function, because it discards all message with the same priority as m
     * from the queue. - This java api bug is reported at:
     * http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6207984
     *
     * => queue1.removeAll(Collections.singletonList(m)); can be used, but it has
     * been removed because of just putting a flag to kill a message is more efficient.
     *
     * @param m
     */
    @Override
    public synchronized void removeMessage(Message m) {
        m.killMessage();
        queueSize--;
    }

    /**
     *
     * get the first message in the queue (with least time stamp)
     *
     * @return
     */

    @Override
    public synchronized Message getNextMessage() {
        Message m = null;
        if (queue1.peek() != null) {
            // skip over dead messages
            while ((m = queue1.poll()) != null && !m.isAlive()) {

            }
            // only decrement, if message fetched
            if (m != null) {
                queueSize--;
            }
        }

        return m;
    }

    @Override
    public synchronized boolean isEmpty() {
        return queue1.size() == 0;
    }

    @Override
    public synchronized int getQueueSize() {
        return queue1.size();
    }

}