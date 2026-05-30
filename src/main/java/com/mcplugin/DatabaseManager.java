package com.mcplugin;

import java.sql.*;
import java.util.*;

public class DatabaseManager {
    private final WebBridgePlugin plugin;
    private Connection conn;

    public DatabaseManager(WebBridgePlugin p) { this.plugin = p; }

    public boolean connect() {
        try {
            Class.forName("org.h2.Driver");
            conn = DriverManager.getConnection("jdbc:h2:file:" + plugin.getDataFolder().getAbsolutePath() + "/database;MODE=MySQL;AUTO_SERVER=TRUE", "sa", "");
            createTables();
            plugin.getLogger().info("数据库连接成功");
            return true;
        } catch (Exception e) { plugin.getLogger().severe("数据库失败: " + e.getMessage()); return false; }
    }

    private void createTables() throws SQLException {
        Statement s = conn.createStatement();
        s.execute("CREATE TABLE IF NOT EXISTS mc_accounts (id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(16), display_name VARCHAR(50), uuid VARCHAR(36) UNIQUE, email VARCHAR(100) UNIQUE, password VARCHAR(255), email_verified BOOLEAN DEFAULT FALSE, bind_code VARCHAR(32), is_bound BOOLEAN DEFAULT FALSE, is_banned BOOLEAN DEFAULT FALSE, ban_reason VARCHAR(500), balance DECIMAL(10,2) DEFAULT 0.00, points INT DEFAULT 0, bio TEXT, avatar_url VARCHAR(500), last_login TIMESTAMP, reg_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE IF NOT EXISTS admins (id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) UNIQUE, password VARCHAR(255), email VARCHAR(100), level VARCHAR(10) DEFAULT 'normal', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE IF NOT EXISTS announcements (id INT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(200), content TEXT, image_url VARCHAR(500), category VARCHAR(50), is_active BOOLEAN DEFAULT TRUE, created_by VARCHAR(50), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE IF NOT EXISTS community_posts (id INT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36), player_name VARCHAR(16), content TEXT, image_url VARCHAR(500), category VARCHAR(20) DEFAULT 'chat', skin_head_url VARCHAR(255), likes INT DEFAULT 0, dislikes INT DEFAULT 0, is_pinned BOOLEAN DEFAULT FALSE, is_deleted BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE IF NOT EXISTS post_reactions (id INT AUTO_INCREMENT PRIMARY KEY, post_id INT, player_uuid VARCHAR(36), reaction_type VARCHAR(10), UNIQUE(post_id, player_uuid))");
        s.execute("CREATE TABLE IF NOT EXISTS suggestions (id INT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36), player_name VARCHAR(16), content TEXT, is_read BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE IF NOT EXISTS chat_messages (id INT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36), player_name VARCHAR(16), message TEXT, source VARCHAR(10), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE IF NOT EXISTS login_tokens (id INT AUTO_INCREMENT PRIMARY KEY, token VARCHAR(64) UNIQUE, player_uuid VARCHAR(36), type VARCHAR(10), expiry BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("CREATE TABLE IF NOT EXISTS bans (id INT AUTO_INCREMENT PRIMARY KEY, player_uuid VARCHAR(36), player_name VARCHAR(16), reason VARCHAR(500), banned_by VARCHAR(50), banned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, is_active BOOLEAN DEFAULT TRUE)");
        s.execute("CREATE TABLE IF NOT EXISTS notifications (id INT AUTO_INCREMENT PRIMARY KEY, recipient_uuid VARCHAR(36), sender_name VARCHAR(50), type VARCHAR(20), content TEXT, is_read BOOLEAN DEFAULT FALSE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        s.execute("MERGE INTO admins (username, password, level) KEY(username) VALUES ('admin', 'admin', 'super')");
        s.close();
    }

    public void disconnect() { try { if (conn != null && !conn.isClosed()) conn.close(); } catch (SQLException e) {} }
    public Connection getConnection() throws SQLException { if (conn == null || conn.isClosed()) connect(); return conn; }
    public ResultSet query(String sql) throws SQLException { return getConnection().createStatement().executeQuery(sql); }
    public int update(String sql) throws SQLException { return getConnection().createStatement().executeUpdate(sql); }
    public PreparedStatement prepare(String sql) throws SQLException { return getConnection().prepareStatement(sql); }

    public boolean bindPlayer(String uuid, String name, String code) {
        try (PreparedStatement ps = prepare("UPDATE mc_accounts SET uuid=?, username=?, is_bound=TRUE, bind_code=NULL WHERE bind_code=? AND is_bound=FALSE")) {
            ps.setString(1, uuid); ps.setString(2, name); ps.setString(3, code);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }
    public boolean unbindPlayer(String uuid) {
        try (PreparedStatement ps = prepare("UPDATE mc_accounts SET is_bound=FALSE WHERE uuid=?")) {
            ps.setString(1, uuid); return ps.executeUpdate() > 0;
        } catch (SQLException e) { return false; }
    }
    public boolean isPlayerBound(String uuid) {
        try (PreparedStatement ps = prepare("SELECT COUNT(*) FROM mc_accounts WHERE uuid=? AND is_bound=TRUE")) {
            ps.setString(1, uuid); ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        } catch (SQLException e) { return false; }
    }
    public Map<String, Object> getPlayerData(String uuid) {
        Map<String, Object> d = new HashMap<>();
        try (PreparedStatement ps = prepare("SELECT * FROM mc_accounts WHERE uuid=? AND is_bound=TRUE")) {
            ps.setString(1, uuid); ResultSet rs = ps.executeQuery();
            if (rs.next()) { d.put("id", rs.getInt("id")); d.put("username", rs.getString("username")); d.put("display_name", rs.getString("display_name")); d.put("uuid", rs.getString("uuid")); d.put("email", rs.getString("email")); d.put("is_banned", rs.getBoolean("is_banned")); d.put("bio", rs.getString("bio")); d.put("avatar_url", rs.getString("avatar_url")); d.put("reg_date", rs.getString("reg_date")); }
        } catch (SQLException e) {}
        return d;
    }
    public Map<String, Object> getPlayerByEmail(String email) {
        Map<String, Object> d = new HashMap<>();
        try (PreparedStatement ps = prepare("SELECT * FROM mc_accounts WHERE email=?")) {
            ps.setString(1, email); ResultSet rs = ps.executeQuery();
            if (rs.next()) { d.put("id", rs.getInt("id")); d.put("username", rs.getString("username")); d.put("uuid", rs.getString("uuid")); d.put("email", rs.getString("email")); d.put("password", rs.getString("password")); d.put("is_banned", rs.getBoolean("is_banned")); d.put("display_name", rs.getString("display_name")); d.put("bio", rs.getString("bio")); d.put("avatar_url", rs.getString("avatar_url")); d.put("reg_date", rs.getString("reg_date")); }
        } catch (SQLException e) {}
        return d;
    }
    public int getTotalUsers() { try (ResultSet rs = query("SELECT COUNT(*) FROM mc_accounts")) { if (rs.next()) return rs.getInt(1); } catch (SQLException e) {} return 0; }
    public String createToken(String uuid, String type, boolean rem) {
        String token = UUID.randomUUID().toString();
        long expiry = rem ? System.currentTimeMillis() + 30L*24*60*60*1000 : System.currentTimeMillis() + 24*60*60*1000;
        try (PreparedStatement ps = prepare("INSERT INTO login_tokens (token,player_uuid,type,expiry) VALUES (?,?,?,?)")) {
            ps.setString(1, token); ps.setString(2, uuid); ps.setString(3, type); ps.setLong(4, expiry);
            ps.executeUpdate(); return token;
        } catch (SQLException e) { return null; }
    }
    public Map<String, Object> validateToken(String token) {
        Map<String, Object> d = new HashMap<>();
        try (PreparedStatement ps = prepare("SELECT * FROM login_tokens WHERE token=? AND expiry>?")) {
            ps.setString(1, token); ps.setLong(2, System.currentTimeMillis());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) { d.put("uuid", rs.getString("player_uuid")); d.put("type", rs.getString("type")); }
        } catch (SQLException e) {}
        return d;
    }
    public void removeToken(String token) { try { update("DELETE FROM login_tokens WHERE token='" + token.replace("'", "''") + "'"); } catch (SQLException e) {} }
    public void banPlayer(String uuid, String reason, String by) {
        try { update("UPDATE mc_accounts SET is_banned=TRUE, ban_reason='" + reason.replace("'", "''") + "' WHERE uuid='" + uuid.replace("'", "''") + "'");
            update("INSERT INTO bans (player_uuid, player_name, reason, banned_by) SELECT uuid, username, '" + reason.replace("'", "''") + "', '" + by.replace("'", "''") + "' FROM mc_accounts WHERE uuid='" + uuid.replace("'", "''") + "'");
        } catch (SQLException e) {}
    }
    public void unbanPlayer(String uuid) {
        try { update("UPDATE mc_accounts SET is_banned=FALSE WHERE uuid='" + uuid.replace("'", "''") + "'");
            update("UPDATE bans SET is_active=FALSE WHERE player_uuid='" + uuid.replace("'", "''") + "' AND is_active=TRUE");
        } catch (SQLException e) {}
    }
}