package com.stockbucks.ai.data;

import com.stockbucks.StockData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class HistoricalDataRepository {

    private final SQLiteManager sqliteManager;

    public HistoricalDataRepository(String jdbcUrl) {
        this.sqliteManager = new SQLiteManager(jdbcUrl);
        initTable();
    }

    private void initTable() {
        sqliteManager.execute("""
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
                """);

        sqliteManager.execute("""
                CREATE INDEX IF NOT EXISTS idx_historical_stock_data_stock_date
                ON historical_stock_data(stock_id, trade_date)
                """);

        sqliteManager.execute("""
                CREATE TABLE IF NOT EXISTS market_data_sync_log (
                    stock_id TEXT PRIMARY KEY,
                    latest_trade_date TEXT,
                    updated_at TEXT NOT NULL,
                    source TEXT NOT NULL
                )
                """);
    }

    public void saveAll(List<StockData> historyData) {
        saveAllAndCount(historyData);
    }

    public int saveAllAndCount(List<StockData> historyData) {
        if (historyData == null || historyData.isEmpty()) {
            return 0;
        }

        String sql = """
                INSERT OR REPLACE INTO historical_stock_data
                (stock_id, trade_date, open_price, high_price, low_price, close_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (StockData data : historyData) {
                ps.setString(1, data.getStockID());
                ps.setString(2, data.getDate());
                ps.setDouble(3, data.getOpen());
                ps.setDouble(4, data.getHigh());
                ps.setDouble(5, data.getLow());
                ps.setDouble(6, data.getClose());
                ps.addBatch();
            }

            int[] counts = ps.executeBatch();
            updateSyncLog(conn, historyData.get(0).getStockID(), latestDateOf(historyData), "TWSE_STOCK_DAY");
            conn.commit();
            return sumUpdatedRows(counts);

        } catch (SQLException e) {
            throw new RuntimeException("Failed to save historical stock data: " + e.getMessage(), e);
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
                    result.add(toStockData(rs));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException("Failed to load historical stock data: " + e.getMessage(), e);
        }

        return result;
    }

    public Optional<String> findLatestDate(String stockId) {
        return findSingleDate("SELECT MAX(trade_date) AS trade_date FROM historical_stock_data WHERE stock_id = ?", stockId);
    }

    public Optional<String> findEarliestDate(String stockId) {
        return findSingleDate("SELECT MIN(trade_date) AS trade_date FROM historical_stock_data WHERE stock_id = ?", stockId);
    }

    public int countByStockId(String stockId) {
        String sql = "SELECT COUNT(*) AS data_count FROM historical_stock_data WHERE stock_id = ?";

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stockId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("data_count") : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count historical stock data: " + e.getMessage(), e);
        }
    }

    public List<String> findAvailableStockIds() {
        List<String> result = new ArrayList<>();
        String sql = "SELECT DISTINCT stock_id FROM historical_stock_data ORDER BY stock_id";

        try (Connection conn = sqliteManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                result.add(rs.getString("stock_id"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list stock ids: " + e.getMessage(), e);
        }

        return result;
    }

    public List<StockData> findRandomByStockId(String stockId, int limit) {
        List<StockData> result = new ArrayList<>();
        String sql = """
                SELECT stock_id, trade_date, open_price, high_price, low_price, close_price
                FROM historical_stock_data
                WHERE stock_id = ?
                ORDER BY RANDOM()
                LIMIT ?
                """;

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stockId);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(toStockData(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read random historical stock data: " + e.getMessage(), e);
        }

        return result;
    }

    public List<StockData> findRandomWindow(String stockId, int windowSize) {
        List<StockData> result = new ArrayList<>();
        String sql = """
                WITH start_row AS (
                    SELECT trade_date
                    FROM historical_stock_data
                    WHERE stock_id = ?
                    ORDER BY RANDOM()
                    LIMIT 1
                )
                SELECT stock_id, trade_date, open_price, high_price, low_price, close_price
                FROM historical_stock_data
                WHERE stock_id = ?
                  AND trade_date >= (SELECT trade_date FROM start_row)
                ORDER BY trade_date
                LIMIT ?
                """;

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stockId);
            ps.setString(2, stockId);
            ps.setInt(3, Math.max(1, windowSize));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(toStockData(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read random historical window: " + e.getMessage(), e);
        }

        return result;
    }

    private Optional<String> findSingleDate(String sql, String stockId) {
        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, stockId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String date = rs.getString("trade_date");
                    return date == null ? Optional.empty() : Optional.of(date);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read historical date: " + e.getMessage(), e);
        }

        return Optional.empty();
    }

    private StockData toStockData(ResultSet rs) throws SQLException {
        return new StockData(
                rs.getString("stock_id"),
                rs.getString("trade_date"),
                String.valueOf(rs.getDouble("open_price")),
                String.valueOf(rs.getDouble("high_price")),
                String.valueOf(rs.getDouble("low_price")),
                String.valueOf(rs.getDouble("close_price"))
        );
    }

    private int sumUpdatedRows(int[] counts) {
        int total = 0;
        for (int count : counts) {
            if (count > 0) {
                total += count;
            }
        }
        return total;
    }

    private String latestDateOf(List<StockData> historyData) {
        String latest = historyData.get(0).getDate();
        for (StockData data : historyData) {
            if (data.getDate().compareTo(latest) > 0) {
                latest = data.getDate();
            }
        }
        return latest;
    }

    private void updateSyncLog(Connection conn, String stockId, String latestDate, String source) throws SQLException {
        String sql = """
                INSERT OR REPLACE INTO market_data_sync_log
                (stock_id, latest_trade_date, updated_at, source)
                VALUES (?, ?, datetime('now'), ?)
                """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stockId);
            ps.setString(2, latestDate);
            ps.setString(3, source);
            ps.executeUpdate();
        }
    }
}
