package com.knowledge;

import java.security.MessageDigest;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;

public class DatabaseHelper {
    private Connection connection;

    public DatabaseHelper(String dbPath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA journal_mode = WAL");
            stmt.execute("PRAGMA busy_timeout = 5000");
        }
        initTables();
    }

    private void initTables() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS users (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  username TEXT UNIQUE NOT NULL," +
                "  password TEXT NOT NULL," +
                "  role TEXT DEFAULT 'user' CHECK(role IN ('admin','user'))," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS knowledge_entries (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  title TEXT NOT NULL," +
                "  content TEXT DEFAULT ''," +
                "  summary TEXT DEFAULT ''," +
                "  entry_type TEXT DEFAULT 'note' CHECK(entry_type IN ('note','skill','task','idea','article'))," +
                "  status TEXT DEFAULT 'active' CHECK(status IN ('active','archived'))," +
                "  importance INTEGER DEFAULT 0," +
                "  created_by TEXT DEFAULT ''," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))," +
                "  updated_at DATETIME DEFAULT (datetime('now','localtime'))," +
                "  kb_source TEXT DEFAULT ''," +
                "  kb_mtime TEXT DEFAULT ''" +
                ")"
            );
            // Add columns to existing databases (migration)
            try { stmt.executeUpdate("ALTER TABLE knowledge_entries ADD COLUMN created_by TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE work_records ADD COLUMN created_by TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE knowledge_entries ADD COLUMN kb_source TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE knowledge_entries ADD COLUMN kb_mtime TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE work_records ADD COLUMN kb_source TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { stmt.executeUpdate("ALTER TABLE work_records ADD COLUMN kb_mtime TEXT DEFAULT ''"); } catch (Exception ignored) {}

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS tags (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  name TEXT UNIQUE NOT NULL," +
                "  color TEXT DEFAULT '#1a73e8'," +
                "  parent_id INTEGER REFERENCES tags(id) ON DELETE SET NULL," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))" +
                ")"
            );
            try { stmt.executeUpdate("ALTER TABLE tags ADD COLUMN parent_id INTEGER REFERENCES tags(id) ON DELETE SET NULL"); } catch (Exception ignored) {}
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS entry_tags (" +
                "  entry_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE," +
                "  PRIMARY KEY(entry_id, tag_id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS entry_links (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  source_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  target_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  link_type TEXT DEFAULT 'reference' CHECK(link_type IN ('reference','dependency','related'))," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))," +
                "  UNIQUE(source_id, target_id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS entry_versions (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  entry_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  title TEXT NOT NULL," +
                "  content TEXT DEFAULT ''," +
                "  version INTEGER NOT NULL," +
                "  change_summary TEXT DEFAULT ''," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS work_records (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  title TEXT NOT NULL," +
                "  content TEXT DEFAULT ''," +
                "  record_type TEXT DEFAULT 'daily' CHECK(record_type IN ('daily','meeting','idea','note','task'))," +
                "  voice_path TEXT," +
                "  duration INTEGER DEFAULT 0," +
                "  status TEXT DEFAULT 'active' CHECK(status IN ('active','completed','archived'))," +
                "  importance INTEGER DEFAULT 0," +
                "  created_by TEXT DEFAULT ''," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))," +
                "  updated_at DATETIME DEFAULT (datetime('now','localtime'))," +
                "  kb_source TEXT DEFAULT ''," +
                "  kb_mtime TEXT DEFAULT ''" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS record_tags (" +
                "  record_id INTEGER NOT NULL REFERENCES work_records(id) ON DELETE CASCADE," +
                "  tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE," +
                "  PRIMARY KEY(record_id, tag_id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS record_links (" +
                "  record_id INTEGER NOT NULL REFERENCES work_records(id) ON DELETE CASCADE," +
                "  entry_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  PRIMARY KEY(record_id, entry_id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS favorites (" +
                "  entry_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))," +
                "  PRIMARY KEY(entry_id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS graph_layouts (" +
                "  entry_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  x REAL NOT NULL DEFAULT 0," +
                "  y REAL NOT NULL DEFAULT 0," +
                "  updated_at DATETIME DEFAULT (datetime('now','localtime'))," +
                "  PRIMARY KEY(entry_id)" +
                ")"
            );
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS shares (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  entry_id INTEGER NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE," +
                "  share_token TEXT UNIQUE NOT NULL," +
                "  created_by TEXT DEFAULT ''," +
                "  is_active INTEGER DEFAULT 1," +
                "  expire_at DATETIME," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))" +
                ")"
            );
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_shares_token ON shares(share_token)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_shares_entry ON shares(entry_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_title ON knowledge_entries(title)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_type ON knowledge_entries(entry_type)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_status ON knowledge_entries(status)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entries_updated ON knowledge_entries(updated_at)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_type ON work_records(record_type)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_records_updated ON work_records(updated_at)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_versions_entry ON entry_versions(entry_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_links_source ON entry_links(source_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_links_target ON entry_links(target_id)");

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS attachments (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  source_type TEXT NOT NULL CHECK(source_type IN ('entry','record'))," +
                "  source_id INTEGER NOT NULL," +
                "  filename TEXT NOT NULL," +
                "  filepath TEXT NOT NULL," +
                "  file_size INTEGER DEFAULT 0," +
                "  mime_type TEXT DEFAULT ''," +
                "  created_by TEXT DEFAULT ''," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))" +
                ")"
            );
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_attach_source ON attachments(source_type, source_id)");

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS operation_logs (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  action TEXT NOT NULL," +
                "  detail TEXT DEFAULT ''," +
                "  source TEXT DEFAULT 'web'," +
                "  username TEXT DEFAULT ''," +
                "  created_at DATETIME DEFAULT (datetime('now','localtime'))" +
                ")"
            );
        }
        seedIfEmpty();
    }

    private void seedIfEmpty() throws Exception {
        try (Statement stmt = connection.createStatement()) {
            // Ensure default admin exists with a strong seed password (no hardcoded literal).
            // Password comes from env KNOWLEDGE_SEED_ADMIN_PASSWORD, else generated at startup
            // (printed once to stdout) — avoids shipping a known default credential.
            int adminCount = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users WHERE username='admin'")) {
                if (rs.next()) adminCount = rs.getInt(1);
            }
            if (adminCount == 0) {
                String seedPass = System.getenv("KNOWLEDGE_SEED_ADMIN_PASSWORD");
                if (seedPass != null && seedPass.length() < 8) {
                    System.out.println("[init] KNOWLEDGE_SEED_ADMIN_PASSWORD 太短（至少 8 位），已忽略并生成随机密码");
                    seedPass = null;
                }
                String newPass = (seedPass != null && !seedPass.isEmpty()) ? seedPass
                    : "K" + Long.toHexString(System.nanoTime()).substring(4) + (int) (Math.random() * 1000000);
                try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO users (username, password, role) VALUES (?, ?, 'admin')")) {
                    ps.setString(1, "admin");
                    ps.setString(2, hashPassword(newPass));
                    ps.executeUpdate();
                }
                System.out.println("[init] Created default admin account 'admin'; initial password: " + newPass + "  (rotate it after first login)");
            }
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM knowledge_entries")) {
            rs.next();
            if (rs.getInt(1) > 0) return;
        }

        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());

        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO tags (name, color) VALUES ('Android', '#1a73e8')");
            stmt.executeUpdate("INSERT INTO tags (name, color) VALUES ('Java', '#e37400')");
            stmt.executeUpdate("INSERT INTO tags (name, color) VALUES ('架构设计', '#7c3aed')");
            stmt.executeUpdate("INSERT INTO tags (name, color) VALUES ('经验总结', '#059669')");

            stmt.executeUpdate("INSERT INTO knowledge_entries (title, content, summary, entry_type, importance, created_at, updated_at) VALUES (" +
                "'Android Room 数据库使用指南','# Android Room 数据库使用指南\\n\\n## 基本概念\\nRoom 是 Android Jetpack 中的持久化库。\\n\\n## 核心组件\\n- **Entity**: 数据实体类\\n- **DAO**: 数据访问对象\\n- **Database**: 数据库类','Room 数据库的核心概念和使用方法','note',3,'" + ts + "','" + ts + "')");
            stmt.executeUpdate("INSERT INTO knowledge_entries (title, content, summary, entry_type, importance, created_at, updated_at) VALUES (" +
                "'嵌入式 HTTP 服务器实现方案','# 嵌入式 HTTP 服务器实现方案\\n\\n## 方案概述\\n在应用中嵌入 HTTP 服务器，实现本地 API 服务。\\n\\n## 技术选型\\n- 使用 Java ServerSocket 实现基础 HTTP 服务器\\n- 使用 SQLite 作为数据存储\\n- JSON 格式数据交换','嵌入式 HTTP Server 的实现方案','skill',3,'" + ts + "','" + ts + "')");
            stmt.executeUpdate("INSERT INTO knowledge_entries (title, content, summary, entry_type, importance, created_at, updated_at) VALUES (" +
                "'知识管理系统需求分析','# 知识管理系统需求分析\\n\\n## 核心需求\\n1. **知识资产化**\\n2. **双链关联**\\n3. **版本管理**\\n4. **智能检索**\\n5. **知识图谱**','个人知识管理平台的核心需求分析','article',4,'" + ts + "','" + ts + "')");

            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (1, 1)");
            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (1, 2)");
            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (1, 3)");
            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (2, 1)");
            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (2, 2)");
            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (2, 3)");
            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (3, 3)");
            stmt.executeUpdate("INSERT INTO entry_tags (entry_id, tag_id) VALUES (3, 4)");

            stmt.executeUpdate("INSERT INTO entry_links (source_id, target_id, link_type) VALUES (1, 2, 'related')");
            stmt.executeUpdate("INSERT INTO entry_links (source_id, target_id, link_type) VALUES (2, 1, 'related')");
            stmt.executeUpdate("INSERT INTO entry_links (source_id, target_id, link_type) VALUES (3, 1, 'reference')");
            stmt.executeUpdate("INSERT INTO entry_links (source_id, target_id, link_type) VALUES (3, 2, 'reference')");

            stmt.executeUpdate("INSERT INTO entry_versions (entry_id, title, content, version, change_summary, created_at) VALUES " +
                "(1, 'Android Room 数据库使用指南', '# Android Room 数据库使用指南\\n\\n## 基本概念', 1, '初版创建', '" + ts + "')");
            stmt.executeUpdate("INSERT INTO entry_versions (entry_id, title, content, version, change_summary, created_at) VALUES " +
                "(1, 'Android Room 数据库使用指南', '# Android Room 数据库使用指南\\n\\n## 基本概念\\nRoom 是 Android Jetpack 中的持久化库。', 2, '补充基本概念', '" + ts + "')");

            stmt.executeUpdate("INSERT INTO work_records (title, content, record_type, importance, created_at, updated_at) VALUES (" +
                "'完成数据库接口设计', '今天完成了知识库 App 的数据库表结构设计，包括知识条目表、标签表、关联表等。使用了 SQLite 作为本地存储方案。','daily',3,'" + ts + "','" + ts + "')");
            stmt.executeUpdate("INSERT INTO work_records (title, content, record_type, importance, created_at, updated_at) VALUES (" +
                "'技术方案评审会议', '讨论了知识图谱可视化方案，决定使用 Canvas 进行渲染。确定了离线优先的架构原则。','meeting',4,'" + ts + "','" + ts + "')");
        }
    }

    public Connection getConnection() {
        return connection;
    }

    public static String resultSetToJson(ResultSet rs) throws Exception {
        StringBuilder arr = new StringBuilder("[");
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rs.next()) {
            if (arr.length() > 1) arr.append(",");
            arr.append("{");
            for (int i = 1; i <= colCount; i++) {
                if (i > 1) arr.append(",");
                String col = meta.getColumnName(i);
                arr.append("\"").append(col).append("\":");
                int type = meta.getColumnType(i);
                switch (type) {
                    case Types.INTEGER:
                    case Types.BIGINT:
                    case Types.SMALLINT:
                    case Types.TINYINT:
                        long lv = rs.getLong(i);
                        arr.append(rs.wasNull() ? "null" : String.valueOf(lv));
                        break;
                    case Types.REAL:
                    case Types.FLOAT:
                    case Types.DOUBLE:
                        double dv = rs.getDouble(i);
                        arr.append(rs.wasNull() ? "null" : String.valueOf(dv));
                        break;
                    case Types.BLOB:
                        byte[] bytes = rs.getBytes(i);
                        if (bytes == null) { arr.append("null"); break; }
                        String b64 = Base64.getEncoder().encodeToString(bytes);
                        arr.append("\"").append(b64.replace("\"", "\\\"")).append("\"");
                        break;
                    default:
                        String val = rs.getString(i);
                        arr.append(val == null ? "null" : "\"" + escapeJson(val) + "\"");
                }
            }
            arr.append("}");
        }
        arr.append("]");
        rs.close();
        return arr.toString();
    }

    public static String singleResultToJson(ResultSet rs) throws Exception {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        if (rs.next()) {
            StringBuilder obj = new StringBuilder("{");
            for (int i = 1; i <= colCount; i++) {
                if (i > 1) obj.append(",");
                String col = meta.getColumnName(i);
                obj.append("\"").append(col).append("\":");
                int type = meta.getColumnType(i);
                switch (type) {
                    case Types.INTEGER:
                    case Types.BIGINT:
                    case Types.SMALLINT:
                    case Types.TINYINT:
                        long lv = rs.getLong(i);
                        obj.append(rs.wasNull() ? "null" : String.valueOf(lv));
                        break;
                    case Types.REAL:
                    case Types.FLOAT:
                    case Types.DOUBLE:
                        double dv = rs.getDouble(i);
                        obj.append(rs.wasNull() ? "null" : String.valueOf(dv));
                        break;
                    case Types.BLOB:
                        byte[] bytes = rs.getBytes(i);
                        if (bytes == null) { obj.append("null"); break; }
                        String b64 = Base64.getEncoder().encodeToString(bytes);
                        obj.append("\"").append(b64.replace("\"", "\\\"")).append("\"");
                        break;
                    default:
                        String val = rs.getString(i);
                        obj.append(val == null ? "null" : "\"" + escapeJson(val) + "\"");
                }
            }
            obj.append("}");
            rs.close();
            return obj.toString();
        }
        rs.close();
        return "null";
    }

    public static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    public void close() {
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
    }

    // ===== 密码哈希（PBKDF2-HmacSHA256，带随机盐；兼容旧的无盐 SHA-256 并支持透明升级）=====
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    /** 生成 PBKDF2 哈希，格式: pbkdf2$<iterations>$<saltB64>$<hashB64> */
    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[PBKDF2_SALT_BYTES];
            SECURE_RANDOM.nextBytes(salt);
            byte[] key = pbkdf2(password, salt, PBKDF2_ITERATIONS);
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(key);
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }

    /** 校验密码：自动识别 PBKDF2 格式与旧版无盐 SHA-256（64 位十六进制） */
    public static boolean verifyPassword(String password, String stored) {
        if (password == null || stored == null || stored.isEmpty()) return false;
        if (stored.startsWith("pbkdf2$")) {
            try {
                String[] parts = stored.split("\\$");
                if (parts.length != 4) return false;
                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                byte[] actual = pbkdf2(password, salt, iterations);
                return MessageDigest.isEqual(expected, actual);
            } catch (Exception e) {
                return false;
            }
        }
        // 旧版：无盐 SHA-256 十六进制
        if (stored.length() == 64 && stored.matches("[0-9a-f]{64}")) {
            String legacy = legacySha256Hex(password);
            return MessageDigest.isEqual(legacy.getBytes(), stored.getBytes());
        }
        return false;
    }

    /** 是否需要重新哈希升级（旧格式 → PBKDF2） */
    public static boolean needsRehash(String stored) {
        return stored != null && !stored.startsWith("pbkdf2$");
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
            password.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
        javax.crypto.SecretKeyFactory factory =
            javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    // 仅供旧数据校验使用
    private static String legacySha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 不可用", e);
        }
    }
}