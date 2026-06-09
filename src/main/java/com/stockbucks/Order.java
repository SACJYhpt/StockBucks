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

    public long getCommission() { // 取得手續費 (只有成交 FILLED 時才產生費用，其餘為 0)
        if (this.status != OrderStatus.FILLED) return 0;
        double totalCost = this.shares * this.limitPrice; // 註：這裡若撮合有改用 matchPrice 亦可調整
        return (long) Math.max(1, Math.floor(totalCost * 0.001425));
    }

    public long getTax() { // 取得證交稅 (只有賣出且成交 FILLED 時才扣證交稅)
        if (this.status != OrderStatus.FILLED || this.isBuy()) return 0;
        double totalCost = this.shares * this.limitPrice;
        return (long) Math.floor(totalCost * 0.003);
    }

    public double getTotalSettlementAmount() { // 取得交割總金額 (買入扣款變負數，賣出入帳變正數，未成交為 0)
        if (this.status != OrderStatus.FILLED) return 0.0;
        double totalCost = this.shares * this.limitPrice;
        long commission = getCommission();

        if (this.isBuy()) { // 買入：扣款總額 = 本金 + 手續費 (以負數呈現代表流出)
            return -(totalCost + commission);
        }
        else { // 賣出：入帳總額 = 本金 - 手續費 - 證交稅 (正數代表流入) 
            long tax = getTax();
            return totalCost - (commission + tax);
        }
    }
}
