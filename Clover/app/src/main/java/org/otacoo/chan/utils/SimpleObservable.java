package org.otacoo.chan.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Modern replacement for java.util.Observable
 */
public class SimpleObservable<T> {
    public interface SimpleObserver<T> {
        void onUpdate(SimpleObservable<T> observable, T arg);
    }

    private final List<SimpleObserver<T>> observers = new ArrayList<>();

    public void addObserver(SimpleObserver<T> observer) {
        synchronized (observers) {
            if (!observers.contains(observer)) {
                observers.add(observer);
            }
        }
    }

    public void deleteObserver(SimpleObserver<T> observer) {
        synchronized (observers) {
            observers.remove(observer);
        }
    }

    public void notifyObservers() {
        notifyObservers(null);
    }

    public void notifyObservers(T arg) {
        List<SimpleObserver<T>> targets;
        synchronized (observers) {
            targets = new ArrayList<>(observers);
        }
        for (SimpleObserver<T> observer : targets) {
            observer.onUpdate(this, arg);
        }
    }
}
