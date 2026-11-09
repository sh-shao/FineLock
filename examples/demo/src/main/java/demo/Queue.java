package demo;

/** Uses wait/notify: FineLock leaves it unchanged. */
public class Queue {
    private Object item;

    public synchronized void put(Object o) {
        item = o;
        notifyAll();
    }

    public synchronized Object take() throws InterruptedException {
        while (item == null) wait();
        Object o = item;
        item = null;
        return o;
    }
}
