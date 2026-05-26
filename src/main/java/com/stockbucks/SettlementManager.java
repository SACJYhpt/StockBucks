package com.stockbucks;

import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

public class SettlementManager implements Serializable {
    private static final long serialVersionUID = 1L;
    private Queue<Settlement> q = new LinkedList<>();

    public static class Settlement implements Serializable {
        private static final long serialVersionUID = 1L;
        private String date;
        private double price;

        public Settlement(String date, double price) {
            this.date = date;
            this.price = price;
        }
    }

    public void addSettlement(String date, double price) {
        q.add(new Settlement(date, price));
    }

    public void SettlementClearing(String today, User user) {
        Iterator<Settlement> iterator = q.iterator();
        while (iterator.hasNext()) {
            Settlement set = iterator.next();
            if (set.date.compareTo(today) <= 0) {
                System.out.println(set.date + " settlement recorded: " + set.price);
                iterator.remove();
            }
        }
    }
}
