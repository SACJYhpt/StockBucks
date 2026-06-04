package com.stockbucks.api.stock;

import java.util.List;

/**
 * 可選能力：代表這個資料來源能讀取使用者的券商帳戶資料。
 *
 * 目前設計為只查詢，不提供下單功能。
 */
public interface BrokerAccountClient {
    boolean isBrokerConfigured();

    String getMissingBrokerConfigName();

    BrokerAccountSnapshot fetchAccountSnapshot();

    List<BrokerPosition> fetchPositions();

    List<IntradayBar> fetchIntradayBars(String stockId, String interval);
}
