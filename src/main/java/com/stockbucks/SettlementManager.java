package com.stockbucks;

import java.util.Queue;
import java.util.Iterator;
import java.util.LinkedList;
import java.io.Serializable;

public class SettlementManager implements Serializable {
    private static final long serialVersionUID = 1L;
    private Queue <Settlement> q = new LinkedList<>();

    public static class Settlement implements Serializable {
        private static final long serialVersionUID = 1L;
        private String date;
        private double price;

        public Settlement(String date, double price) {
            this.date = date;
            this.price = price;
        }
        public String getDate() {
            return date;
        }

        public double getPrice() {
            return price;
        }
    }

    public void addSettlement(String date, double price) {
        q.add(new Settlement(date, price));
    }

    public void SettlementClearing(String today, User user) {
        Iterator <Settlement> iterator = q.iterator();
        while (iterator.hasNext()) {
            Settlement set = iterator.next();
            if (set.date.compareTo(today) <= 0) {
                user.addCash(set.price);
                System.out.println(set.date+"交割金額："+set.price);
                iterator.remove();
            }
        }
    }

    public Queue<Settlement> getSettlementQueue() {
        return this.q;
    }
}
