package com.stockbucks.ai.data;

import com.stockbucks.StockData;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class HistoricalDataRepository {

    private final SQLiteManager sqliteManager;

    public HistoricalDataRepository(String jdbcUrl) {
        this.sqliteManager = new SQLiteManager(jdbcUrl);
        initTable();
    }

    private void initTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS historical_stock_data (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    stock_id TEXT NOT NULL,
                    trade_date TEXT NOT NULL,
                    open_price REAL NOT NULL,
                    high_price REAL NOT NULL,
                    low_price REAL NOT NULL,
                    close_price REAL NOT NULL,
                    UNIQUE(stock_id, trade_date)
                )
                """;
        sqliteManager.execute(sql);
    }

    public void saveAll(List<StockData> historyData) {
        if (historyData == null || historyData.isEmpty()) return;

        String sql = """
                INSERT OR REPLACE INTO historical_stock_data
                (stock_id, trade_date, open_price, high_price, low_price, close_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (StockData data : historyData) {
                ps.setString(1, data.getStockID());
                ps.setString(2, data.getDate());
                ps.setDouble(3, data.getOpen());
                ps.setDouble(4, data.getHigh());
                ps.setDouble(5, data.getLow());
                ps.setDouble(6, data.getClose());
                ps.addBatch();
            }
            ps.executeBatch();

        } catch (SQLException e) {
            throw new RuntimeException("寫入歷史資料失敗: " + e.getMessage(), e);
        }
    }

    public List<StockData> findByStockId(String stockId) {
        List<StockData> result = new ArrayList<>();

        String sql = """
                SELECT stock_id, trade_date, open_price, high_price, low_price, close_price
                FROM historical_stock_data
                WHERE stock_id = ?
                ORDER BY trade_date
                """;

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stockId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new StockData(
                            rs.getString("stock_id"),
                            rs.getString("trade_date"),
                            String.valueOf(rs.getDouble("open_price")),
                            String.valueOf(rs.getDouble("high_price")),
                            String.valueOf(rs.getDouble("low_price")),
                            String.valueOf(rs.getDouble("close_price"))
                    ));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("查詢歷史資料失敗: " + e.getMessage(), e);
        }

        return result;
    }
}