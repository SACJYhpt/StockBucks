package com.stockbucks;

public class Order {
    public enum OrderStatus {
        PENDING,    // 委託成功等待成交
        FAILED,     // 委託失敗 (不足委託)
        FILLED,     // 已成交
        CANCELLED,  // 已取消 (手動刪單)
        INVALID     // 已無效 (跨日自動失效)
    }
    private User user;
    private String stockID;
    private String date;
    private int time;
    private int shares;
    private double limitPrice;
    private boolean isBuy;

    private OrderStatus status = OrderStatus.PENDING;

    public Order(User user, String stockID, String date, int time, int shares, double limitPrice, boolean isBuy) {
        this.user = user;
        this.stockID = stockID;
        this.date = date;
        this.time = time;
        this.shares = shares;
        this.limitPrice = limitPrice;
        this.isBuy = isBuy;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getStatusString() {
        switch (this.status) {
            case PENDING:
                return "委託成功";
            case FILLED:
                return "已成交";
            case CANCELLED:
                return "已取消";
            case INVALID:
                return "已無效";
            default:
                return "未知狀態";
        }
    }

    private String convertMinToTimeString(int time) {
        time += 9*60;
        return String.format("%02d:%02d", time/60, time%60);
    }

    public User getUser() {
        return user;
    }

    public String getStockID() {
        return stockID;
    }

    public String getDate() {
        return date;
    }

    public String getTime() {
        return convertMinToTimeString(time);
    }


    public int getShares() {
        return shares;
    }

    public double getLimitPrice() {
        return limitPrice;
    }

    public boolean isBuy() {
        return isBuy;
    }
}
