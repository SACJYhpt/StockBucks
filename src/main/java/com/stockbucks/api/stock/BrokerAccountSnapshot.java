package com.stockbucks.api.stock;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 券商帳戶摘要。
 *
 * 只作查詢用途，不包含下單功能。
 */
public class BrokerAccountSnapshot {
    private final String accountId; // 帳戶代號，來源未提供時可為空。
    private final double cashBalance; // 現金餘額。
    private final double marketValue; // 持股市值。
    private final double totalEquity; // 總權益，若來源未提供則用現金 + 市值估算。
    private final List<BrokerPosition> positions; // 庫存清單。
    private final String provider; // 券商來源。
    private final LocalDateTime fetchedAt; // 本地抓取時間。

    public BrokerAccountSnapshot(String accountId,
                                 double cashBalance,
                                 double marketValue,
                                 double totalEquity,
                                 List<BrokerPosition> positions,
                                 String provider) {
        this.accountId = accountId == null ? "" : accountId;
        this.cashBalance = cashBalance;
        this.marketValue = marketValue;
        this.totalEquity = totalEquity;
        this.positions = positions == null ? List.of() : List.copyOf(positions);
        this.provider = provider == null ? "" : provider;
        this.fetchedAt = LocalDateTime.now();
    }

    public String getAccountId() {
        return accountId;
    }

    public double getCashBalance() {
        return cashBalance;
    }

    public double getMarketValue() {
        return marketValue;
    }

    public double getTotalEquity() {
        return totalEquity;
    }

    public List<BrokerPosition> getPositions() {
        return positions;
    }

    public String getProvider() {
        return provider;
    }

    public LocalDateTime getFetchedAt() {
        return fetchedAt;
    }
}
