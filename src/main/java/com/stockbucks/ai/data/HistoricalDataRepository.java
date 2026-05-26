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
                    stock_name TEXT DEFAULT '',
                    trade_date TEXT NOT NULL,
                    open_price REAL NOT NULL,
                    high_price REAL NOT NULL,
                    low_price REAL NOT NULL,
                    close_price REAL NOT NULL,
                    volume INTEGER DEFAULT 0,
                    turnover INTEGER DEFAULT 0,
                    transaction_count INTEGER DEFAULT 0,
                    price_change REAL DEFAULT 0,
                    source TEXT DEFAULT 'TWSE',
                    updated_at TEXT DEFAULT '',
                    UNIQUE(stock_id, trade_date)
                )
                """);

        addColumnIfMissing("historical_stock_data", "stock_name", "TEXT DEFAULT ''");
        addColumnIfMissing("historical_stock_data", "volume", "INTEGER DEFAULT 0");
        addColumnIfMissing("historical_stock_data", "turnover", "INTEGER DEFAULT 0");
        addColumnIfMissing("historical_stock_data", "transaction_count", "INTEGER DEFAULT 0");
        addColumnIfMissing("historical_stock_data", "price_change", "REAL DEFAULT 0");
        addColumnIfMissing("historical_stock_data", "source", "TEXT DEFAULT 'TWSE'");
        addColumnIfMissing("historical_stock_data", "updated_at", "TEXT DEFAULT ''");

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

        sqliteManager.execute("""
                CREATE TABLE IF NOT EXISTS stock_profile (
                    stock_id TEXT PRIMARY KEY,
                    stock_name TEXT NOT NULL,
                    market_type TEXT NOT NULL,
                    updated_at TEXT NOT NULL
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
                (stock_id, stock_name, trade_date, open_price, high_price, low_price, close_price,
                 volume, turnover, transaction_count, price_change, source, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TWSE', datetime('now'))
                """;

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (StockData data : historyData) {
                ps.setString(1, data.getStockID());
                ps.setString(2, data.getStockName());
                ps.setString(3, data.getDate());
                ps.setDouble(4, data.getOpen());
                ps.setDouble(5, data.getHigh());
                ps.setDouble(6, data.getLow());
                ps.setDouble(7, data.getClose());
                ps.setLong(8, data.getVolume());
                ps.setLong(9, data.getTurnover());
                ps.setLong(10, data.getTransactionCount());
                ps.setDouble(11, data.getPriceChange());
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
                SELECT stock_id, stock_name, trade_date, open_price, high_price, low_price, close_price,
                       volume, turnover, transaction_count, price_change
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
                SELECT stock_id, stock_name, trade_date, open_price, high_price, low_price, close_price,
                       volume, turnover, transaction_count, price_change
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
                SELECT stock_id, stock_name, trade_date, open_price, high_price, low_price, close_price,
                       volume, turnover, transaction_count, price_change
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

    private void addColumnIfMissing(String tableName, String columnName, String columnDefinition) {
        String sql = "ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition;
        try {
            sqliteManager.execute(sql);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (!message.contains("duplicate column")) {
                throw e;
            }
        }
    }

    private StockData toStockData(ResultSet rs) throws SQLException {
        return new StockData(
                rs.getString("stock_id"),
                rs.getString("stock_name"),
                rs.getString("trade_date"),
                String.valueOf(rs.getDouble("open_price")),
                String.valueOf(rs.getDouble("high_price")),
                String.valueOf(rs.getDouble("low_price")),
                String.valueOf(rs.getDouble("close_price")),
                String.valueOf(rs.getLong("volume")),
                String.valueOf(rs.getLong("turnover")),
                String.valueOf(rs.getLong("transaction_count")),
                String.valueOf(rs.getDouble("price_change"))
        );
    }

    public int saveStockProfiles(List<StockProfile> profiles) {
        if (profiles == null || profiles.isEmpty()) {
            return 0;
        }

        String sql = """
                INSERT OR REPLACE INTO stock_profile
                (stock_id, stock_name, market_type, updated_at)
                VALUES (?, ?, ?, datetime('now'))
                """;

        try (Connection conn = sqliteManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);
            for (StockProfile profile : profiles) {
                ps.setString(1, profile.getStockId());
                ps.setString(2, profile.getStockName());
                ps.setString(3, profile.getMarketType());
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            conn.commit();
            return sumUpdatedRows(counts);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save stock profiles: " + e.getMessage(), e);
        }
    }

    public List<StockProfile> findStockProfiles() {
        List<StockProfile> result = new ArrayList<>();
        String sql = "SELECT stock_id, stock_name, market_type FROM stock_profile ORDER BY stock_id";

        try (Connection conn = sqliteManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                result.add(new StockProfile(
                        rs.getString("stock_id"),
                        rs.getString("stock_name"),
                        rs.getString("market_type")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read stock profiles: " + e.getMessage(), e);
        }

        return result;
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
