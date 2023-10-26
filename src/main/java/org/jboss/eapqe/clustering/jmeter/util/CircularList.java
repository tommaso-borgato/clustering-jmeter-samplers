package org.jboss.eapqe.clustering.jmeter.util;

import java.util.ArrayList;

public class CircularList<E> extends ArrayList<E> {

    private int index = 0;

    public E getNext() {
        if (index == size()) index = 0;
        return super.get(index++);
    }

    @Override
    public E get(int index) {
        return super.get(index % size());
    }

    public ArrayList<E> getAllExcept(E url){
        final ArrayList<E> others = new ArrayList<>();
        this.forEach(
                tmp -> {
                    if (url instanceof String && tmp instanceof String) {
                        if (!String.class.cast(tmp).equalsIgnoreCase(String.class.cast(url))) {
                            others.add(tmp);
                        }
                    } else {
                        if (!tmp.equals(url)) {
                            others.add(tmp);
                        }
                    }
                }
        );
        return others;
    }
}
