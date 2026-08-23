package com.knowledge;

import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.*;

public class ApiServer {
    // 会话表：ConcurrentHashMap 保证线程安全；sessionLastAccess 支持过期淘汰
    private static final Map<String, String> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> sessionRoles = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, Long> sessionLastAccess = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SESSION_TTL_MS = 24L * 60 * 60 * 1000; // 24 小时
    // KB 根目录：优先环境变量 KNOWLEDGE_KB_ROOT，未设置时回退到工作目录下 ./KB
    private static final String DEFAULT_KB_ROOT =
        System.getenv("KNOWLEDGE_KB_ROOT") != null && !System.getenv("KNOWLEDGE_KB_ROOT").trim().isEmpty()
            ? System.getenv("KNOWLEDGE_KB_ROOT").trim() : "./KB";
    // 请求体上限 25MB（附件 base64 膨胀约 33%，足够常规文档）
    private static final int MAX_BODY_BYTES = 25 * 1024 * 1024;
    // 请求处理线程池：避免每连接无限建线程
    private final java.util.concurrent.ExecutorService requestPool;
    private final DatabaseHelper dbHelper;
    private final String webRoot;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private int serverPort;

    public ApiServer(DatabaseHelper dbHelper, String webRoot) {
        this.dbHelper = dbHelper;
        this.webRoot = webRoot;
        int poolSize = Math.max(8, Runtime.getRuntime().availableProcessors() * 4);
        this.requestPool = java.util.concurrent.Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "http-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(int port) throws IOException {
        serverPort = port;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        System.out.println("Server started on http://127.0.0.1:" + port);
        // acceptor 保持非守护线程以维持 JVM 存活；工作线程为守护线程，stop() 关闭端口后 JVM 可正常退出
        Thread acceptor = new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    requestPool.execute(() -> handle(socket));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "http-acceptor");
        acceptor.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void handle(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream os = socket.getOutputStream()) {

            String line = readLine(in);
            if (line == null) { socket.close(); return; }
            String[] parts = line.split(" ", 3);
            if (parts.length < 2) { socket.close(); return; }
            String method = parts[0];
            String fullPath = parts[1];
            String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
            Map<String, String> query = parseQuery(fullPath.contains("?") ? fullPath.substring(fullPath.indexOf('?') + 1) : "");

            Map<String, String> headers = new HashMap<>();
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(": ");
                if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(), line.substring(colon + 2));
            }

            int contentLength = 0;
            if (headers.containsKey("content-length")) {
                try {
                    contentLength = Integer.parseInt(headers.get("content-length").trim());
                } catch (NumberFormatException nfe) {
                    contentLength = 0;
                }
            }
            // 防御超大 Content-Length 导致 OOM
            if (contentLength > MAX_BODY_BYTES) {
                String resp = jsonError("请求体过大（上限 25MB）");
                os.write(("HTTP/1.1 413 Payload Too Large\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + resp.getBytes("UTF-8").length + "\r\n\r\n" + resp).getBytes("UTF-8"));
                os.flush();
                return;
            }
            String body = "";
            if (contentLength > 0) {
                byte[] tmp = new byte[8192];
                ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(contentLength, MAX_BODY_BYTES));
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int n = in.read(tmp, 0, Math.min(tmp.length, contentLength - totalRead));
                    if (n == -1) break;
                    baos.write(tmp, 0, n);
                    totalRead += n;
                }
                body = baos.toString("UTF-8");
            }

            boolean isApi = path.startsWith("/api/");
            if (isApi) {
                String response;
                try {
                    // SQLite 单连接：整体串行化，避免多线程并发写冲突
                    synchronized (dbHelper) {
                        response = route(method, path, query, body, headers);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    response = jsonError("服务器错误");
                }
                byte[] respBytes = ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\nAccess-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type,Authorization\r\nContent-Length: " + response.getBytes("UTF-8").length + "\r\n\r\n" + response).getBytes("UTF-8");
                os.write(respBytes);
            } else if (path.matches("/share/[a-f0-9]+")) {
                serveStaticFile("/share.html", os);
            } else {
                serveStaticFile(path, os);
            }
            os.flush();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    private String route(String method, String path, Map<String, String> query, String body, Map<String, String> headers) throws Exception {
        Connection conn = dbHelper.getConnection();

        if (method.equals("OPTIONS")) return "{\"code\":0}";

        // ===== Knowledge Entries =====
        if (path.equals("/api/entries")) {
            if (method.equals("GET")) {
                StringBuilder sql = new StringBuilder(
                    "SELECT e.*, " +
                    "(SELECT GROUP_CONCAT(t.name, ',') FROM entry_tags et JOIN tags t ON et.tag_id=t.id WHERE et.entry_id=e.id) as tag_names, " +
                    "(SELECT COUNT(*) FROM entry_links WHERE source_id=e.id) as outlink_count, " +
                    "(SELECT COUNT(*) FROM entry_links WHERE target_id=e.id) as inlink_count, " +
                    "(SELECT CASE WHEN EXISTS(SELECT 1 FROM shares WHERE entry_id=e.id AND is_active=1) THEN 'active' WHEN EXISTS(SELECT 1 FROM shares WHERE entry_id=e.id) THEN 'disabled' ELSE 'none' END) as share_status " +
                    "FROM knowledge_entries e WHERE 1=1");
                List<String> argsList = new ArrayList<>();

                if (query.containsKey("type")) {
                    sql.append(" AND e.entry_type=?");
                    argsList.add(query.get("type"));
                }
                if (query.containsKey("status")) {
                    sql.append(" AND e.status=?");
                    argsList.add(query.get("status"));
                }
                if (query.containsKey("q")) {
                    sql.append(" AND (e.title LIKE ? OR e.content LIKE ? OR e.summary LIKE ?)");
                    String q = "%" + query.get("q") + "%";
                    argsList.add(q); argsList.add(q); argsList.add(q);
                }
                if (query.containsKey("tag") && !query.get("tag").isEmpty()) {
                    sql.append(buildTagInClause(collectTagTreeIds(conn, query.get("tag")), argsList));
                }
                if (query.containsKey("date_from")) {
                    sql.append(" AND e.updated_at >= ?");
                    argsList.add(query.get("date_from") + " 00:00:00");
                }
                if (query.containsKey("date_to")) {
                    sql.append(" AND e.updated_at <= ?");
                    argsList.add(query.get("date_to") + " 23:59:59");
                }
                if (query.containsKey("favorites")) {
                    sql.append(" AND e.id IN (SELECT entry_id FROM favorites)");
                }

                sql.append(" ORDER BY e.updated_at DESC");

                int page = 1, pageSize = 50;
                if (query.containsKey("page")) page = Integer.parseInt(query.get("page"));
                if (query.containsKey("pageSize")) pageSize = Integer.parseInt(query.get("pageSize"));
                sql.append(" LIMIT ").append(pageSize).append(" OFFSET ").append((page - 1) * pageSize);

                try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < argsList.size(); i++) {
                        stmt.setString(i + 1, argsList.get(i));
                    }
                    ResultSet rs = stmt.executeQuery();
                    return "{\"code\":0,\"data\":{\"list\":" + DatabaseHelper.resultSetToJson(rs) + ",\"page\":" + page + ",\"pageSize\":" + pageSize + "}}";
                }
            }
            if (method.equals("POST")) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                String title = json.optString("title", "").trim();
                if (title.isEmpty()) return jsonError("标题不能为空");
                String content = json.optString("content", "");
                String summary = json.optString("summary", "");
                String entryType = json.optString("entry_type", "note");
                int importance = json.optInt("importance", 0);
                String ts = now();

                String entryUser = authUser(headers, query);
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO knowledge_entries (title, content, summary, entry_type, importance, created_by, updated_at, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                    stmt.setString(1, title);
                    stmt.setString(2, content);
                    stmt.setString(3, summary);
                    stmt.setString(4, entryType);
                    stmt.setInt(5, importance);
                    stmt.setString(6, entryUser);
                    stmt.setString(7, ts);
                    stmt.setString(8, ts);
                    stmt.executeUpdate();
                }

                long id;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT last_insert_rowid()");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }

                handleTags(conn, id, json.optJSONArray("tags"));
                saveVersion(conn, id, title, content, "初版创建");

                String entrySrc = query.getOrDefault("source", "web");
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO operation_logs (action, detail, source, username) VALUES (?,?,?,?)")) {
                    stmt.setString(1, "create_entry");
                    stmt.setString(2, "创建知识条目: " + title);
                    stmt.setString(3, entrySrc);
                    stmt.setString(4, entryUser);
                    stmt.executeUpdate();
                }

                return "{\"code\":0,\"data\":{\"id\":" + id + "},\"msg\":\"创建成功\"}";
            }
        }

        if (path.matches("/api/entries/\\d+")) {
            String id = path.replace("/api/entries/", "");
            if (method.equals("GET")) {
                String sql = "SELECT e.*, " +
                    "(SELECT GROUP_CONCAT(t.name, ',') FROM entry_tags et JOIN tags t ON et.tag_id=t.id WHERE et.entry_id=e.id) as tag_names, " +
                    "(SELECT COUNT(*) FROM entry_links WHERE source_id=e.id) as outlink_count, " +
                    "(SELECT COUNT(*) FROM entry_links WHERE target_id=e.id) as inlink_count, " +
                    "(SELECT CASE WHEN EXISTS(SELECT 1 FROM shares WHERE entry_id=e.id AND is_active=1) THEN 'active' WHEN EXISTS(SELECT 1 FROM shares WHERE entry_id=e.id) THEN 'disabled' ELSE 'none' END) as share_status " +
                    "FROM knowledge_entries e WHERE e.id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    ResultSet rs = stmt.executeQuery();
                    String entry = DatabaseHelper.singleResultToJson(rs);
                    if (entry.equals("null")) return "{\"code\":404,\"msg\":\"条目不存在\"}";
                    return "{\"code\":0,\"data\":" + entry + "}";
                }
            }
            if (method.equals("PUT")) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                String title = json.optString("title", "").trim();
                if (title.isEmpty()) return jsonError("标题不能为空");
                String content = json.optString("content", "");
                String summary = json.optString("summary", "");
                String entryType = json.optString("entry_type", "");
                String status = json.optString("status", "");
                int importance = json.optInt("importance", -1);
                String ts = now();

                StringBuilder usql = new StringBuilder("UPDATE knowledge_entries SET updated_at=?");
                List<String> uargs = new ArrayList<>();
                uargs.add(ts);
                usql.append(",title=?"); uargs.add(title);
                usql.append(",content=?"); uargs.add(content);
                usql.append(",summary=?"); uargs.add(summary);
                if (!entryType.isEmpty()) { usql.append(",entry_type=?"); uargs.add(entryType); }
                if (!status.isEmpty()) { usql.append(",status=?"); uargs.add(status); }
                if (importance >= 0) { usql.append(",importance=?"); uargs.add(String.valueOf(importance)); }
                usql.append(" WHERE id=?");
                uargs.add(id);

                try (PreparedStatement stmt = conn.prepareStatement(usql.toString())) {
                    for (int i = 0; i < uargs.size(); i++) {
                        stmt.setString(i + 1, uargs.get(i));
                    }
                    stmt.executeUpdate();
                }

                handleTags(conn, Long.parseLong(id), json.optJSONArray("tags"));
                saveVersion(conn, Long.parseLong(id), title, content, json.optString("change_summary", "更新内容"));

                return "{\"code\":0,\"msg\":\"保存成功\"}";
            }
            if (method.equals("DELETE")) {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM knowledge_entries WHERE id=?")) {
                    stmt.setString(1, id);
                    stmt.executeUpdate();
                }
                return "{\"code\":0,\"msg\":\"已删除\"}";
            }
        }

        // ===== Entry Tags =====
        if (path.matches("/api/entries/\\d+/tags") && method.equals("GET")) {
            String id = path.replace("/api/entries/", "").replace("/tags", "");
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT t.* FROM tags t JOIN entry_tags et ON et.tag_id=t.id WHERE et.entry_id=?")) {
                stmt.setString(1, id);
                ResultSet rs = stmt.executeQuery();
                return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(rs) + "}";
            }
        }

        // ===== Entry Versions =====
        if (path.matches("/api/entries/\\d+/versions") && method.equals("GET")) {
            String id = path.replace("/api/entries/", "").replace("/versions", "");
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT * FROM entry_versions WHERE entry_id=? ORDER BY version DESC")) {
                stmt.setString(1, id);
                ResultSet rs = stmt.executeQuery();
                return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(rs) + "}";
            }
        }

        // ===== Backlinks =====
        if (path.matches("/api/entries/\\d+/backlinks") && method.equals("GET")) {
            String id = path.replace("/api/entries/", "").replace("/backlinks", "");
            String inlinks, outlinks;
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT e.*, el.link_type FROM entry_links el JOIN knowledge_entries e ON el.source_id=e.id WHERE el.target_id=?")) {
                stmt.setString(1, id);
                inlinks = DatabaseHelper.resultSetToJson(stmt.executeQuery());
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT e.*, el.link_type FROM entry_links el JOIN knowledge_entries e ON el.target_id=e.id WHERE el.source_id=?")) {
                stmt.setString(1, id);
                outlinks = DatabaseHelper.resultSetToJson(stmt.executeQuery());
            }
            return "{\"code\":0,\"data\":{\"inlinks\":" + inlinks + ",\"outlinks\":" + outlinks + "}}";
        }

        // ===== Entry Links =====
        if (path.matches("/api/entries/\\d+/links")) {
            String id = path.replace("/api/entries/", "").replace("/links", "");
            if (method.equals("GET")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT el.*, e.title as target_title FROM entry_links el JOIN knowledge_entries e ON el.target_id=e.id WHERE el.source_id=?")) {
                    stmt.setString(1, id);
                    ResultSet rs = stmt.executeQuery();
                    return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(rs) + "}";
                }
            }
            if (method.equals("POST")) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                long targetId = json.getLong("target_id");
                String linkType = json.optString("link_type", "reference");
                try (PreparedStatement check = conn.prepareStatement(
                    "SELECT id FROM entry_links WHERE source_id=? AND target_id=?")) {
                    check.setString(1, id);
                    check.setString(2, String.valueOf(targetId));
                    ResultSet rs = check.executeQuery();
                    if (rs.next()) { return "{\"code\":400,\"msg\":\"链接已存在\"}"; }
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO entry_links (source_id, target_id, link_type) VALUES (?,?,?)")) {
                    stmt.setString(1, id);
                    stmt.setString(2, String.valueOf(targetId));
                    stmt.setString(3, linkType);
                    stmt.executeUpdate();
                }
                return "{\"code\":0,\"msg\":\"链接创建成功\"}";
            }
        }

        if (path.matches("/api/entries/\\d+/links/\\d+") && method.equals("DELETE")) {
            String[] parts = path.split("/");
            String linkId = parts[parts.length - 1];
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM entry_links WHERE id=?")) {
                stmt.setString(1, linkId);
                stmt.executeUpdate();
            }
            return "{\"code\":0,\"msg\":\"链接已删除\"}";
        }

        // ===== Favorites =====
        if (path.matches("/api/entries/\\d+/favorite")) {
            String id = path.replace("/api/entries/", "").replace("/favorite", "");
            if (method.equals("POST")) {
                try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO favorites (entry_id) VALUES (?)")) {
                    stmt.setString(1, id);
                    stmt.executeUpdate();
                }
                return "{\"code\":0,\"msg\":\"已收藏\"}";
            }
            if (method.equals("DELETE")) {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM favorites WHERE entry_id=?")) {
                    stmt.setString(1, id);
                    stmt.executeUpdate();
                }
                return "{\"code\":0,\"msg\":\"已取消收藏\"}";
            }
        }


        // ===== Share =====
        if (path.matches("/api/entries/\\d+/share")) {
            String entryId = path.replace("/api/entries/", "").replace("/share", "");
            if (method.equals("POST")) {
                // Check if already shared
                try (PreparedStatement chk = conn.prepareStatement(
                    "SELECT share_token FROM shares WHERE entry_id=? AND is_active=1 LIMIT 1")) {
                    chk.setString(1, entryId);
                    ResultSet crs = chk.executeQuery();
                    if (crs.next()) {
                        String oldToken = crs.getString("share_token");
                        String shareUrl = "http://" + getLanIp() + ":" + serverPort + "/share/" + oldToken;
                        return "{\"code\":0,\"data\":{\"share_token\":\"" + oldToken + "\",\"share_url\":\"" + shareUrl + "\"},\"msg\":\"分享已存在\"}";
                    }
                }
                String token = UUID.randomUUID().toString().replace("-", "");
                String shareUser = authUser(headers, query);
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO shares (entry_id, share_token, created_by) VALUES (?,?,?)")) {
                    stmt.setString(1, entryId);
                    stmt.setString(2, token);
                    stmt.setString(3, shareUser);
                    stmt.executeUpdate();
                }
                String shareUrl = "http://" + getLanIp() + ":" + serverPort + "/share/" + token;
                return "{\"code\":0,\"data\":{\"share_token\":\"" + token + "\",\"share_url\":\"" + shareUrl + "\"},\"msg\":\"分享已开启\"}";
            }
            if (method.equals("GET")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT id, entry_id, share_token, is_active, created_at FROM shares WHERE entry_id=? AND is_active=1 ORDER BY created_at DESC LIMIT 1")) {
                    stmt.setString(1, entryId);
                    ResultSet rs = stmt.executeQuery();
                    String result = DatabaseHelper.singleResultToJson(rs);
                    if (result.equals("null")) return "{\"code\":0,\"data\":null}";
                    org.json.JSONObject obj = new org.json.JSONObject(result);
                    String shareUrl = "http://" + getLanIp() + ":" + serverPort + "/share/" + obj.optString("share_token", "");
                    obj.put("share_url", shareUrl);
                    return "{\"code\":0,\"data\":" + obj.toString() + "}";
                }
            }
            if (method.equals("DELETE")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE shares SET is_active=0 WHERE entry_id=? AND is_active=1")) {
                    stmt.setString(1, entryId);
                    stmt.executeUpdate();
                }
                return "{\"code\":0,\"msg\":\"分享已停用\"}";
            }
        }

        // ===== Public Share View =====
        if (path.matches("/api/share/[a-f0-9]+") && method.equals("GET")) {
            String token = path.replace("/api/share/", "");
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT e.id, e.title, e.content, e.summary, e.entry_type, e.created_at, e.created_by FROM shares s JOIN knowledge_entries e ON s.entry_id=e.id WHERE s.share_token=? AND s.is_active=1 AND e.status='active'")) {
                stmt.setString(1, token);
                ResultSet rs = stmt.executeQuery();
                String entry = DatabaseHelper.singleResultToJson(rs);
                if (entry.equals("null")) return "{\"code\":404,\"msg\":\"分享链接已失效\"}";
                return "{\"code\":0,\"data\":" + entry + "}";
            }
        }
        // ===== Batch Operations =====
        if (path.equals("/api/entries/batch") && method.equals("POST")) {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONArray ids = json.getJSONArray("ids");
            String action = json.optString("action", "");
            String ts = now();
            switch (action) {
                case "delete":
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM knowledge_entries WHERE id=?")) {
                        for (int i = 0; i < ids.length(); i++) {
                            stmt.setLong(1, ids.getLong(i));
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                    break;
                case "archive":
                    try (PreparedStatement stmt = conn.prepareStatement("UPDATE knowledge_entries SET status='archived', updated_at=? WHERE id=?")) {
                        for (int i = 0; i < ids.length(); i++) {
                            stmt.setString(1, ts);
                            stmt.setLong(2, ids.getLong(i));
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                    break;
                case "add-tag":
                    String tagName = json.optString("tag", "");
                    if (!tagName.isEmpty()) {
                        long tagId;
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM tags WHERE name=?")) {
                            stmt.setString(1, tagName);
                            ResultSet rs = stmt.executeQuery();
                            if (rs.next()) {
                                tagId = rs.getLong("id");
                            } else {
                                try (PreparedStatement ins = conn.prepareStatement("INSERT INTO tags (name) VALUES (?)")) {
                                    ins.setString(1, tagName);
                                    ins.executeUpdate();
                                }
                                try (PreparedStatement ins = conn.prepareStatement("SELECT last_insert_rowid()")) {
                                    ResultSet r2 = ins.executeQuery();
                                    r2.next(); tagId = r2.getLong(1);
                                }
                            }
                        }
                        try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO entry_tags (entry_id, tag_id) VALUES (?,?)")) {
                            for (int i = 0; i < ids.length(); i++) {
                                stmt.setLong(1, ids.getLong(i));
                                stmt.setLong(2, tagId);
                                stmt.addBatch();
                            }
                            stmt.executeBatch();
                        }
                    }
                    break;
            }
            return "{\"code\":0,\"msg\":\"批量操作完成\"}";
        }

        if (path.equals("/api/records/batch") && method.equals("POST")) {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONArray ids = json.getJSONArray("ids");
            String action = json.optString("action", "");
            String ts = now();
            switch (action) {
                case "delete":
                    try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM work_records WHERE id=?")) {
                        for (int i = 0; i < ids.length(); i++) {
                            stmt.setLong(1, ids.getLong(i));
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                    break;
                case "archive":
                    try (PreparedStatement stmt = conn.prepareStatement("UPDATE work_records SET status='archived', updated_at=? WHERE id=?")) {
                        for (int i = 0; i < ids.length(); i++) {
                            stmt.setString(1, ts);
                            stmt.setLong(2, ids.getLong(i));
                            stmt.addBatch();
                        }
                        stmt.executeBatch();
                    }
                    break;
                case "add-tag":
                    String tagName = json.optString("tag", "");
                    if (!tagName.isEmpty()) {
                        long tagId;
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM tags WHERE name=?")) {
                            stmt.setString(1, tagName);
                            ResultSet rs = stmt.executeQuery();
                            if (rs.next()) {
                                tagId = rs.getLong("id");
                            } else {
                                try (PreparedStatement ins = conn.prepareStatement("INSERT INTO tags (name) VALUES (?)")) {
                                    ins.setString(1, tagName);
                                    ins.executeUpdate();
                                }
                                try (PreparedStatement ins = conn.prepareStatement("SELECT last_insert_rowid()")) {
                                    ResultSet r2 = ins.executeQuery();
                                    r2.next(); tagId = r2.getLong(1);
                                }
                            }
                        }
                        try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO record_tags (record_id, tag_id) VALUES (?,?)")) {
                            for (int i = 0; i < ids.length(); i++) {
                                stmt.setLong(1, ids.getLong(i));
                                stmt.setLong(2, tagId);
                                stmt.addBatch();
                            }
                            stmt.executeBatch();
                        }
                    }
                    break;
            }
            return "{\"code\":0,\"msg\":\"批量操作完成\"}";
        }

        // ===== Tags =====
        if (path.equals("/api/tags")) {
            if (method.equals("GET")) {
                try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT t.*, (SELECT COUNT(*) FROM entry_tags WHERE tag_id=t.id) as entry_count FROM tags t ORDER BY t.parent_id, t.name")) {
                    ResultSet rs = stmt.executeQuery();
                    return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(rs) + "}";
                }
            }
            if (method.equals("POST")) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                String name = json.optString("name", "").trim();
                if (name.isEmpty()) return jsonError("标签名不能为空");
                String color = json.optString("color", "#1a73e8");
                long parentId = json.has("parent_id") && !json.isNull("parent_id") ? json.optLong("parent_id", 0) : 0;
                try {
                    if (parentId > 0) {
                        try (PreparedStatement check = conn.prepareStatement("SELECT id FROM tags WHERE id=?")) {
                            check.setLong(1, parentId);
                            ResultSet c = check.executeQuery();
                            if (!c.next()) return jsonError("父标签不存在");
                        }
                    }
                    try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO tags (name, color, parent_id) VALUES (?,?,?)")) {
                        stmt.setString(1, name);
                        stmt.setString(2, color);
                        if (parentId > 0) stmt.setLong(3, parentId);
                        else stmt.setNull(3, java.sql.Types.INTEGER);
                        stmt.executeUpdate();
                    }
                } catch (Exception e) {
                    return "{\"code\":400,\"msg\":\"标签名已存在\"}";
                }
                return "{\"code\":0,\"msg\":\"创建成功\"}";
            }
        }

        if (path.matches("/api/tags/\\d+")) {
            String id = path.replace("/api/tags/", "");
            if (method.equals("PUT")) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                String name = json.optString("name", "").trim();
                String color = json.optString("color", "");
                StringBuilder usql = new StringBuilder("UPDATE tags SET ");
                List<String> uargs = new ArrayList<>();
                if (!name.isEmpty()) { usql.append("name=?,"); uargs.add(name); }
                if (!color.isEmpty()) { usql.append("color=?,"); uargs.add(color); }
                if (json.has("parent_id") && !json.isNull("parent_id")) {
                    long parentId = json.optLong("parent_id", 0);
                    if (parentId == Long.parseLong(id)) return jsonError("父标签不能是自己");
                    if (parentId > 0) {
                        try (PreparedStatement check = conn.prepareStatement("SELECT id FROM tags WHERE id=?")) {
                            check.setLong(1, parentId);
                            ResultSet c = check.executeQuery();
                            if (!c.next()) return jsonError("父标签不存在");
                        }
                    }
                    usql.append("parent_id=?,");
                    if (parentId > 0) uargs.add(String.valueOf(parentId));
                    else uargs.add(null);
                }
                if (uargs.size() > 0) {
                    usql.setLength(usql.length() - 1);
                    usql.append(" WHERE id=?");
                    uargs.add(id);
                    try (PreparedStatement stmt = conn.prepareStatement(usql.toString())) {
                        for (int i = 0; i < uargs.size(); i++) {
                            if (uargs.get(i) == null) stmt.setNull(i + 1, java.sql.Types.INTEGER);
                            else stmt.setString(i + 1, uargs.get(i));
                        }
                        stmt.executeUpdate();
                    }
                }
                return "{\"code\":0,\"msg\":\"更新成功\"}";
            }
            if (method.equals("DELETE")) {
                try (PreparedStatement orphan = conn.prepareStatement("UPDATE tags SET parent_id=NULL WHERE parent_id=?")) {
                    orphan.setLong(1, Long.parseLong(id));
                    orphan.executeUpdate();
                }
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM tags WHERE id=?")) {
                    stmt.setString(1, id);
                    stmt.executeUpdate();
                }
                return "{\"code\":0,\"msg\":\"已删除\"}";
            }
        }

        // ===== Work Records =====
        if (path.equals("/api/records")) {
            if (method.equals("GET")) {
                StringBuilder sql = new StringBuilder(
                    "SELECT r.*, " +
                    "(SELECT GROUP_CONCAT(t.name, ',') FROM record_tags rt JOIN tags t ON rt.tag_id=t.id WHERE rt.record_id=r.id) as tag_names " +
                    "FROM work_records r WHERE 1=1");
                List<String> argsList = new ArrayList<>();

                if (query.containsKey("type")) {
                    sql.append(" AND r.record_type=?");
                    argsList.add(query.get("type"));
                }
                if (query.containsKey("status")) {
                    sql.append(" AND r.status=?");
                    argsList.add(query.get("status"));
                }
                if (query.containsKey("q")) {
                    sql.append(" AND (r.title LIKE ? OR r.content LIKE ?)");
                    String q = "%" + query.get("q") + "%";
                    argsList.add(q); argsList.add(q);
                }
                if (query.containsKey("tag") && !query.get("tag").isEmpty()) {
                    sql.append(buildRecordTagInClause(collectTagTreeIds(conn, query.get("tag")), argsList));
                }
                if (query.containsKey("date_from")) {
                    sql.append(" AND r.updated_at >= ?");
                    argsList.add(query.get("date_from") + " 00:00:00");
                }
                if (query.containsKey("date_to")) {
                    sql.append(" AND r.updated_at <= ?");
                    argsList.add(query.get("date_to") + " 23:59:59");
                }

                String recUser = authUser(headers, query);
                String recRole = sessionRoles.getOrDefault(tokenOf(headers, query), "user");
                if (!"admin".equals(recRole) && !recUser.isEmpty()) {
                    sql.append(" AND r.created_by=?");
                    argsList.add(recUser);
                }

                sql.append(" ORDER BY r.updated_at DESC");

                int page = 1, pageSize = 50;
                if (query.containsKey("page")) page = Integer.parseInt(query.get("page"));
                if (query.containsKey("pageSize")) pageSize = Integer.parseInt(query.get("pageSize"));
                sql.append(" LIMIT ").append(pageSize).append(" OFFSET ").append((page - 1) * pageSize);

                try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < argsList.size(); i++) {
                        stmt.setString(i + 1, argsList.get(i));
                    }
                    ResultSet rs = stmt.executeQuery();
                    return "{\"code\":0,\"data\":{\"list\":" + DatabaseHelper.resultSetToJson(rs) + ",\"page\":" + page + ",\"pageSize\":" + pageSize + "}}";
                }
            }
            if (method.equals("POST")) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                String title = json.optString("title", "").trim();
                if (title.isEmpty()) return jsonError("标题不能为空");
                String content = json.optString("content", "");
                String recordType = json.optString("record_type", "daily");
                int importance = json.optInt("importance", 0);
                String ts = now();

                String recUser = authUser(headers, query);
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO work_records (title, content, record_type, importance, created_by, updated_at, created_at) VALUES (?,?,?,?,?,?,?)")) {
                    stmt.setString(1, title);
                    stmt.setString(2, content);
                    stmt.setString(3, recordType);
                    stmt.setInt(4, importance);
                    stmt.setString(5, recUser);
                    stmt.setString(6, ts);
                    stmt.setString(7, ts);
                    stmt.executeUpdate();
                }

                long id;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT last_insert_rowid()");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }

                handleRecordTags(conn, id, json.optJSONArray("tags"));
                handleRecordLinks(conn, id, json.optJSONArray("linked_entries"));

                String recSrc = query.getOrDefault("source", "web");
                try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO operation_logs (action, detail, source, username) VALUES (?,?,?,?)")) {
                    stmt.setString(1, "create_record");
                    stmt.setString(2, "创建日常记录: " + title);
                    stmt.setString(3, recSrc);
                    stmt.setString(4, recUser);
                    stmt.executeUpdate();
                }

                return "{\"code\":0,\"data\":{\"id\":" + id + "},\"msg\":\"创建成功\"}";
            }
        }

        if (path.matches("/api/records/\\d+")) {
            String id = path.replace("/api/records/", "");
            if (method.equals("GET")) {
                String sql = "SELECT r.*, " +
                    "(SELECT GROUP_CONCAT(t.name, ',') FROM record_tags rt JOIN tags t ON rt.tag_id=t.id WHERE rt.record_id=r.id) as tag_names " +
                    "FROM work_records r WHERE r.id=?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    ResultSet rs = stmt.executeQuery();
                    String record = DatabaseHelper.singleResultToJson(rs);
                    if (record.equals("null")) return "{\"code\":404,\"msg\":\"记录不存在\"}";
                    return "{\"code\":0,\"data\":" + record + "}";
                }
            }
            if (method.equals("PUT")) {
                org.json.JSONObject json = new org.json.JSONObject(body);
                String title = json.optString("title", "").trim();
                if (title.isEmpty()) return jsonError("标题不能为空");
                String content = json.optString("content", "");
                String recordType = json.optString("record_type", "");
                String status = json.optString("status", "");
                int importance = json.optInt("importance", -1);
                String ts = now();

                StringBuilder usql = new StringBuilder("UPDATE work_records SET updated_at=?");
                List<String> uargs = new ArrayList<>();
                uargs.add(ts);
                usql.append(",title=?"); uargs.add(title);
                usql.append(",content=?"); uargs.add(content);
                if (!recordType.isEmpty()) { usql.append(",record_type=?"); uargs.add(recordType); }
                if (!status.isEmpty()) { usql.append(",status=?"); uargs.add(status); }
                if (importance >= 0) { usql.append(",importance=?"); uargs.add(String.valueOf(importance)); }
                usql.append(" WHERE id=?");
                uargs.add(id);

                try (PreparedStatement stmt = conn.prepareStatement(usql.toString())) {
                    for (int i = 0; i < uargs.size(); i++) {
                        stmt.setString(i + 1, uargs.get(i));
                    }
                    stmt.executeUpdate();
                }

                handleRecordTags(conn, Long.parseLong(id), json.optJSONArray("tags"));
                return "{\"code\":0,\"msg\":\"保存成功\"}";
            }
            if (method.equals("DELETE")) {
                try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM work_records WHERE id=?")) {
                    stmt.setString(1, id);
                    stmt.executeUpdate();
                }
                return "{\"code\":0,\"msg\":\"已删除\"}";
            }
        }

        // ===== Knowledge Graph =====
        if (path.equals("/api/graph") && method.equals("GET")) {
            String nodesJson, edgesJson;
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, title, entry_type FROM knowledge_entries WHERE status='active' ORDER BY id")) {
                nodesJson = DatabaseHelper.resultSetToJson(stmt.executeQuery());
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT el.id, el.source_id, el.target_id, el.link_type, " +
                "s.title as source_title, t.title as target_title " +
                "FROM entry_links el " +
                "JOIN knowledge_entries s ON el.source_id=s.id AND s.status='active' " +
                "JOIN knowledge_entries t ON el.target_id=t.id AND t.status='active'")) {
                edgesJson = DatabaseHelper.resultSetToJson(stmt.executeQuery());
            }
            return "{\"code\":0,\"data\":{\"nodes\":" + nodesJson + ",\"edges\":" + edgesJson + "}}";
        }

        if (path.equals("/api/graph/layout") && method.equals("GET")) {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT entry_id, x, y FROM graph_layouts")) {
                return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(stmt.executeQuery()) + "}";
            }
        }
        if (path.equals("/api/graph/layout") && method.equals("POST")) {
            org.json.JSONObject json = new org.json.JSONObject(body);
            org.json.JSONArray arr = json.optJSONArray("positions");
            if (arr == null) return jsonError("缺少 positions");
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM graph_layouts")) {
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement(
                "INSERT INTO graph_layouts (entry_id, x, y) VALUES (?,?,?)")) {
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject p = arr.getJSONObject(i);
                    ins.setLong(1, p.getLong("id"));
                    ins.setDouble(2, p.optDouble("x", 0));
                    ins.setDouble(3, p.optDouble("y", 0));
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            return "{\"code\":0,\"msg\":\"布局已保存\"}";
        }

        // ===== Search =====
        if (path.equals("/api/search") && method.equals("GET")) {
            String q = query.getOrDefault("q", "");
            String type = query.getOrDefault("type", "all");
            String tag = query.getOrDefault("tag", "");
            String dateFrom = query.getOrDefault("date_from", "");
            String dateTo = query.getOrDefault("date_to", "");

            String entries = "[]", records = "[]";
            if (type.equals("all") || type.equals("entry")) {
                StringBuilder sql = new StringBuilder(
                    "SELECT e.*, 'entry' as search_type, " +
                    "(SELECT GROUP_CONCAT(t.name, ',') FROM entry_tags et JOIN tags t ON et.tag_id=t.id WHERE et.entry_id=e.id) as tag_names " +
                    "FROM knowledge_entries e WHERE e.status='active'");
                List<String> args = new ArrayList<>();
                if (!q.isEmpty()) {
                    sql.append(" AND (e.title LIKE ? OR e.content LIKE ? OR e.summary LIKE ?)");
                    String likeQ = "%" + q + "%";
                    args.add(likeQ); args.add(likeQ); args.add(likeQ);
                }
                if (!tag.isEmpty()) {
                    List<Long> tagIds = collectTagTreeIds(conn, tag);
                    if (!tagIds.isEmpty()) {
                        StringBuilder in = new StringBuilder(" AND e.id IN (SELECT et.entry_id FROM entry_tags et WHERE et.tag_id IN (");
                        for (int i = 0; i < tagIds.size(); i++) {
                            if (i > 0) in.append(",");
                            in.append("?");
                            args.add(String.valueOf(tagIds.get(i)));
                        }
                        in.append("))");
                        sql.append(in);
                    } else {
                        sql.append(" AND 1=0");
                    }
                }
                if (!dateFrom.isEmpty()) { sql.append(" AND e.updated_at >= ?"); args.add(dateFrom + " 00:00:00"); }
                if (!dateTo.isEmpty()) { sql.append(" AND e.updated_at <= ?"); args.add(dateTo + " 23:59:59"); }
                sql.append(" ORDER BY e.updated_at DESC LIMIT 50");
                try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < args.size(); i++) stmt.setString(i + 1, args.get(i));
                    entries = DatabaseHelper.resultSetToJson(stmt.executeQuery());
                }
            }
            if (type.equals("all") || type.equals("record")) {
                StringBuilder sql = new StringBuilder(
                    "SELECT r.*, 'record' as search_type, " +
                    "(SELECT GROUP_CONCAT(t.name, ',') FROM record_tags rt JOIN tags t ON rt.tag_id=t.id WHERE rt.record_id=r.id) as tag_names " +
                    "FROM work_records r WHERE 1=1");
                List<String> args = new ArrayList<>();
                if (!q.isEmpty()) {
                    sql.append(" AND (r.title LIKE ? OR r.content LIKE ?)");
                    String likeQ = "%" + q + "%";
                    args.add(likeQ); args.add(likeQ);
                }
                if (!dateFrom.isEmpty()) { sql.append(" AND r.updated_at >= ?"); args.add(dateFrom + " 00:00:00"); }
                if (!dateTo.isEmpty()) { sql.append(" AND r.updated_at <= ?"); args.add(dateTo + " 23:59:59"); }
                String srchUser = authUser(headers, query);
                String srchRole = sessionRoles.getOrDefault(tokenOf(headers, query), "user");
                if (!"admin".equals(srchRole) && !srchUser.isEmpty()) {
                    sql.append(" AND r.created_by=?");
                    args.add(srchUser);
                }
                sql.append(" ORDER BY r.updated_at DESC LIMIT 50");
                try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                    for (int i = 0; i < args.size(); i++) stmt.setString(i + 1, args.get(i));
                    records = DatabaseHelper.resultSetToJson(stmt.executeQuery());
                }
            }
            return "{\"code\":0,\"data\":{\"entries\":" + entries + ",\"records\":" + records + "}}";
        }

        // ===== Stats =====
        if (path.equals("/api/stats") && method.equals("GET")) {
            int entryCount, recordCount, tagCount, linkCount, favCount, shareCount;
            String recent;
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM knowledge_entries WHERE status='active'")) {
                rs.next(); entryCount = rs.getInt(1);
            }
            String statsUser = authUser(headers, query);
            String statsRole = sessionRoles.getOrDefault(tokenOf(headers, query), "user");
            if ("admin".equals(statsRole) || statsUser.isEmpty()) {
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM work_records")) {
                    rs.next(); recordCount = rs.getInt(1);
                }
            } else {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM work_records WHERE created_by=?")) {
                    stmt.setString(1, statsUser);
                    ResultSet rs = stmt.executeQuery();
                    rs.next(); recordCount = rs.getInt(1);
                }
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM tags")) {
                rs.next(); tagCount = rs.getInt(1);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM entry_links")) {
                rs.next(); linkCount = rs.getInt(1);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM favorites")) {
                rs.next(); favCount = rs.getInt(1);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM shares WHERE is_active=1")) {
                rs.next(); shareCount = rs.getInt(1);
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT title, updated_at FROM knowledge_entries WHERE status='active' ORDER BY updated_at DESC LIMIT 5")) {
                recent = DatabaseHelper.resultSetToJson(stmt.executeQuery());
            }
            return "{\"code\":0,\"data\":{" +
                "\"entry_count\":" + entryCount + "," +
                "\"record_count\":" + recordCount + "," +
                "\"tag_count\":" + tagCount + "," +
                "\"link_count\":" + linkCount + "," +
                "\"favorite_count\":" + favCount + "," +
                "\"share_count\":" + shareCount + "," +
                "\"recent_entries\":" + recent + "}}";
        }

        // ===== Export =====
        if (path.equals("/api/export") && method.equals("GET")) {
            String expUser = authUser(headers, query); if (expUser.isEmpty()) expUser = "unknown";
            String expSource = query.getOrDefault("source", "web");
            System.out.println("[" + now() + "] EXPORT by " + expUser + " from " + expSource);
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO operation_logs (action, detail, source, username) VALUES (?,?,?,?)")) {
                stmt.setString(1, "export");
                stmt.setString(2, "导出全部数据");
                stmt.setString(3, expSource);
                stmt.setString(4, expUser);
                stmt.executeUpdate();
            }
            String entriesJson, recordsJson, tagsJson, entryTagsJson, linksJson;
            String recordTagsJson, recordLinksJson, versionsJson, favoritesJson, sharesJson, layoutsJson;
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM knowledge_entries ORDER BY id")) {
                entriesJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM work_records ORDER BY id")) {
                recordsJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM tags ORDER BY id")) {
                tagsJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM entry_tags")) {
                entryTagsJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM record_tags")) {
                recordTagsJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM record_links")) {
                recordLinksJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM entry_links")) {
                linksJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM entry_versions")) {
                versionsJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM favorites")) {
                favoritesJson = DatabaseHelper.resultSetToJson(rs);
            }
try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, entry_id, share_token, created_by, is_active, created_at FROM shares")) {
                sharesJson = DatabaseHelper.resultSetToJson(rs);
            }
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT entry_id, x, y FROM graph_layouts")) {
                layoutsJson = DatabaseHelper.resultSetToJson(rs);
            }
            return "{\"code\":0,\"data\":{"
                +
                "\"entries\":" + entriesJson + "," +
                "\"records\":" + recordsJson + "," +
                "\"tags\":" + tagsJson + "," +
                "\"entry_tags\":" + entryTagsJson + "," +
                "\"record_tags\":" + recordTagsJson + "," +
                "\"record_links\":" + recordLinksJson + "," +
                "\"links\":" + linksJson + "," +
                "\"entry_versions\":" + versionsJson + "," +
                "\"favorites\":" + favoritesJson + "," +
                "\"shares\":" + sharesJson + "," +
                "\"graph_layouts\":" + layoutsJson + "}}";
        }

        // ===== Import =====
        if (path.equals("/api/import") && method.equals("POST")) {
            String impUser = authUser(headers, query); if (impUser.isEmpty()) impUser = "unknown";
            String impSource = query.getOrDefault("source", "web");
            System.out.println("[" + now() + "] IMPORT by " + impUser + " from " + impSource);
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO operation_logs (action, detail, source, username) VALUES (?,?,?,?)")) {
                stmt.setString(1, "import");
                stmt.setString(2, "导入数据");
                stmt.setString(3, impSource);
                stmt.setString(4, impUser);
                stmt.executeUpdate();
            }
            org.json.JSONObject json = new org.json.JSONObject(body).getJSONObject("data");
            // Import entries
            org.json.JSONArray entriesArr = json.optJSONArray("entries");
            if (entriesArr != null) {
                for (int i = 0; i < entriesArr.length(); i++) {
                    org.json.JSONObject e = entriesArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO knowledge_entries (id, title, content, summary, entry_type, status, importance, created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                        stmt.setLong(1, e.getLong("id"));
                        stmt.setString(2, e.optString("title", ""));
                        stmt.setString(3, e.optString("content", ""));
                        stmt.setString(4, e.optString("summary", ""));
                        stmt.setString(5, e.optString("entry_type", "note"));
                        stmt.setString(6, e.optString("status", "active"));
                        stmt.setInt(7, e.optInt("importance", 0));
                        stmt.setString(8, e.optString("created_by", ""));
                        stmt.setString(9, e.optString("created_at", now()));
                        stmt.setString(10, e.optString("updated_at", now()));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import records
            org.json.JSONArray recordsArr = json.optJSONArray("records");
            if (recordsArr != null) {
                for (int i = 0; i < recordsArr.length(); i++) {
                    org.json.JSONObject r = recordsArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO work_records (id, title, content, record_type, status, importance, created_by, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
                        stmt.setLong(1, r.getLong("id"));
                        stmt.setString(2, r.optString("title", ""));
                        stmt.setString(3, r.optString("content", ""));
                        stmt.setString(4, r.optString("record_type", "daily"));
                        stmt.setString(5, r.optString("status", "active"));
                        stmt.setInt(6, r.optInt("importance", 0));
                        stmt.setString(7, r.optString("created_by", ""));
                        stmt.setString(8, r.optString("created_at", now()));
                        stmt.setString(9, r.optString("updated_at", now()));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import tags
            org.json.JSONArray tagsArr = json.optJSONArray("tags");
            if (tagsArr != null) {
                for (int i = 0; i < tagsArr.length(); i++) {
                    org.json.JSONObject t = tagsArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO tags (id, name, color) VALUES (?,?,?)")) {
                        stmt.setLong(1, t.getLong("id"));
                        stmt.setString(2, t.optString("name", ""));
                        stmt.setString(3, t.optString("color", "#1a73e8"));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import entry_tags
            org.json.JSONArray entryTagsArr = json.optJSONArray("entry_tags");
            if (entryTagsArr != null) {
                for (int i = 0; i < entryTagsArr.length(); i++) {
                    org.json.JSONObject et = entryTagsArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO entry_tags (entry_id, tag_id) VALUES (?,?)")) {
                        stmt.setLong(1, et.getLong("entry_id"));
                        stmt.setLong(2, et.getLong("tag_id"));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import record_tags
            org.json.JSONArray recordTagsArr = json.optJSONArray("record_tags");
            if (recordTagsArr != null) {
                for (int i = 0; i < recordTagsArr.length(); i++) {
                    org.json.JSONObject rt = recordTagsArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO record_tags (record_id, tag_id) VALUES (?,?)")) {
                        stmt.setLong(1, rt.getLong("record_id"));
                        stmt.setLong(2, rt.getLong("tag_id"));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import record_links
            org.json.JSONArray recordLinksArr = json.optJSONArray("record_links");
            if (recordLinksArr != null) {
                for (int i = 0; i < recordLinksArr.length(); i++) {
                    org.json.JSONObject rl = recordLinksArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO record_links (record_id, entry_id) VALUES (?,?)")) {
                        stmt.setLong(1, rl.getLong("record_id"));
                        stmt.setLong(2, rl.getLong("entry_id"));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import entry_links
            org.json.JSONArray linksArr = json.optJSONArray("links");
            if (linksArr != null) {
                for (int i = 0; i < linksArr.length(); i++) {
                    org.json.JSONObject l = linksArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO entry_links (source_id, target_id, link_type) VALUES (?,?,?)")) {
                        stmt.setLong(1, l.getLong("source_id"));
                        stmt.setLong(2, l.getLong("target_id"));
                        stmt.setString(3, l.optString("link_type", "reference"));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import entry_versions
            org.json.JSONArray versionsArr = json.optJSONArray("entry_versions");
            if (versionsArr != null) {
                for (int i = 0; i < versionsArr.length(); i++) {
                    org.json.JSONObject v = versionsArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO entry_versions (id, entry_id, title, content, version, change_summary, created_at) VALUES (?,?,?,?,?,?,?)")) {
                        stmt.setLong(1, v.getLong("id"));
                        stmt.setLong(2, v.getLong("entry_id"));
                        stmt.setString(3, v.optString("title", ""));
                        stmt.setString(4, v.optString("content", ""));
                        stmt.setInt(5, v.optInt("version", 1));
                        stmt.setString(6, v.optString("change_summary", ""));
                        stmt.setString(7, v.optString("created_at", now()));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import favorites
            org.json.JSONArray favoritesArr = json.optJSONArray("favorites");
            if (favoritesArr != null) {
                for (int i = 0; i < favoritesArr.length(); i++) {
                    org.json.JSONObject f = favoritesArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO favorites (entry_id, created_at) VALUES (?,?)")) {
                        stmt.setLong(1, f.getLong("entry_id"));
                        stmt.setString(2, f.optString("created_at", now()));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import shares
            org.json.JSONArray sharesArr = json.optJSONArray("shares");
            if (sharesArr != null) {
                for (int i = 0; i < sharesArr.length(); i++) {
                    org.json.JSONObject s = sharesArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR IGNORE INTO shares (id, entry_id, share_token, created_by, created_at, is_active) VALUES (?,?,?,?,?,?)")) {
                        stmt.setLong(1, s.getLong("id"));
                        stmt.setLong(2, s.getLong("entry_id"));
                        stmt.setString(3, s.optString("share_token", ""));
                        stmt.setString(4, s.optString("created_by", ""));
                        stmt.setString(5, s.optString("created_at", now()));
                        stmt.setInt(6, s.optInt("is_active", 1));
                        stmt.executeUpdate();
                    }
                }
            }
            // Import graph_layouts (node coordinates)
            org.json.JSONArray layoutsArr = json.optJSONArray("graph_layouts");
            if (layoutsArr != null) {
                for (int i = 0; i < layoutsArr.length(); i++) {
                    org.json.JSONObject gl = layoutsArr.getJSONObject(i);
                    try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO graph_layouts (entry_id, x, y) VALUES (?,?,?)")) {
                        stmt.setLong(1, gl.getLong("entry_id"));
                        stmt.setFloat(2, (float) gl.optDouble("x", 0));
                        stmt.setFloat(3, (float) gl.optDouble("y", 0));
                        stmt.executeUpdate();
                    }
                }
            }
            return "{\"code\":0,\"msg\":\"导入成功\"}";
        }

        // ===== KB Directory Import =====
        if (path.equals("/api/import/kb") && method.equals("POST")) {
            String kbu = authUser(headers, query);
            if (kbu.isEmpty()) return jsonError("未登录");
            // 安全：仅允许导入配置的 KB 根目录，忽略/拒绝任意 path 参数（防目录穿越读取敏感文件）
            String kbRoot = query.getOrDefault("path", "");
            if (kbRoot.isEmpty()) kbRoot = DEFAULT_KB_ROOT;
            java.nio.file.Path requested = Paths.get(kbRoot).toAbsolutePath().normalize();
            java.nio.file.Path allowedRoot = Paths.get(DEFAULT_KB_ROOT).toAbsolutePath().normalize();
            if (!requested.equals(allowedRoot) && !requested.startsWith(allowedRoot)) {
                return jsonError("仅允许导入配置的 KB 根目录: " + DEFAULT_KB_ROOT);
            }
            if (!Files.exists(requested) || !Files.isDirectory(requested)) {
                return jsonError("KB目录不存在: " + kbRoot);
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO operation_logs (action, detail, source, username) VALUES (?,?,?,?)")) {
                stmt.setString(1, "import_kb");
                stmt.setString(2, "从KB目录导入");
                stmt.setString(3, "kb");
                stmt.setString(4, kbu);
                stmt.executeUpdate();
            }
            long[] counters = new long[9]; // 0:entries_new 1:entries_skip 2:records_new 3:records_skip 4:links 5:tags 6:entries_updated 7:records_updated 8:relinks
            boolean relinks = "1".equals(query.getOrDefault("relinks", ""));
            List<String> errs = new ArrayList<>();
            importKbIntoDb(conn, Paths.get(kbRoot), counters, errs, relinks);
            return "{\"code\":0,\"msg\":\"KB目录导入完成\",\"data\":{\"entries_new\":" + counters[0]
                + ",\"entries_skip\":" + counters[1]
                + ",\"records_new\":" + counters[2]
                + ",\"records_skip\":" + counters[3]
                + ",\"entries_updated\":" + counters[6]
                + ",\"records_updated\":" + counters[7]
                + ",\"relinks\":" + counters[8]
                + ",\"links\":\"" + counters[4] + "\",\"tags\":\"" + counters[5]
                + "\",\"errors\":[" + String.join(",", errs.stream().map(s -> "\"" + s.replace("\"", "\\\"") + "\"").collect(java.util.stream.Collectors.toList())) + "]}}";
        }

        // ===== Attachments =====
        if (path.equals("/api/attachments/upload") && method.equals("POST")) {
            org.json.JSONObject json = new org.json.JSONObject(body);
            String sourceType = json.optString("source_type", "");
            long sourceId = json.optLong("source_id", 0);
            String filename = sanitizeFilename(json.optString("filename", "file"));
            String mimeType = json.optString("mime_type", "application/octet-stream");
            String fileData = json.optString("data", "");
            if (sourceType.isEmpty() || sourceId <= 0 || fileData.isEmpty()) {
                return jsonError("参数不完整");
            }
            if (filename == null) return jsonError("文件名不合法或类型不允许");
            byte[] fileBytes;
            try {
                fileBytes = java.util.Base64.getDecoder().decode(fileData);
            } catch (IllegalArgumentException iae) {
                return jsonError("附件数据不是合法的 base64");
            }
            String attachDir = "data/attachments/" + sourceType + "/" + sourceId;
            Files.createDirectories(Paths.get(attachDir));
            String filePath = attachDir + "/" + filename;
            Files.write(Paths.get(filePath), fileBytes);

            String attUser = authUser(headers, query);
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO attachments (source_type, source_id, filename, filepath, file_size, mime_type, created_by) VALUES (?,?,?,?,?,?,?)")) {
                stmt.setString(1, sourceType);
                stmt.setLong(2, sourceId);
                stmt.setString(3, filename);
                stmt.setString(4, filePath);
                stmt.setLong(5, fileBytes.length);
                stmt.setString(6, mimeType);
                stmt.setString(7, attUser);
                stmt.executeUpdate();
            }
            long attId;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT last_insert_rowid()"); ResultSet rs = stmt.executeQuery()) {
                rs.next(); attId = rs.getLong(1);
            }
            return "{\"code\":0,\"data\":{\"id\":" + attId + "},\"msg\":\"上传成功\"}";
        }

        if (path.equals("/api/attachments") && method.equals("GET")) {
            String sourceType = query.getOrDefault("source_type", "");
            String sourceId = query.getOrDefault("source_id", "0");
            if (sourceType.isEmpty()) {
                try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM attachments ORDER BY id")) {
                    return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(rs) + "}";
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM attachments WHERE source_type=? AND source_id=? ORDER BY id")) {
                stmt.setString(1, sourceType);
                stmt.setString(2, sourceId);
                ResultSet rs = stmt.executeQuery();
                return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(rs) + "}";
            }
        }

        if (path.matches("/api/attachments/\\d+/download") && method.equals("GET")) {
            // not used via API for now; files are served directly
            return "{\"code\":0}";
        }

        if (path.matches("/api/attachments/\\d+") && method.equals("DELETE")) {
            String attId = path.replace("/api/attachments/", "").replace("/download", "");
            try (PreparedStatement stmt = conn.prepareStatement("SELECT filepath FROM attachments WHERE id=?")) {
                stmt.setString(1, attId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String fp = rs.getString("filepath");
                    // 安全：仅允许删除 data/attachments 目录内的文件
                    try {
                        java.nio.file.Path target = Paths.get(fp).toAbsolutePath().normalize();
                        java.nio.file.Path base = Paths.get("data/attachments").toAbsolutePath().normalize();
                        if (target.startsWith(base)) Files.deleteIfExists(target);
                    } catch (Exception ignored) {}
                }
            }
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM attachments WHERE id=?")) {
                stmt.setString(1, attId);
                stmt.executeUpdate();
            }
            return "{\"code\":0,\"msg\":\"已删除\"}";
        }

        // ===== Operation Logs =====
        if (path.equals("/api/oplogs") && method.equals("GET")) {
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(
                "SELECT * FROM operation_logs ORDER BY created_at DESC LIMIT 50")) {
                return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(rs) + "}";
            }
        }

        // ===== Auth =====
        if (path.equals("/api/auth/register") && method.equals("POST")) {
            org.json.JSONObject json = new org.json.JSONObject(body);
            String username = json.optString("username", "").trim();
            String password = json.optString("password", "").trim();
            if (username.isEmpty() || password.isEmpty()) return jsonError("用户名和密码不能为空");
            String policyErr = passwordPolicyError(password);
            if (policyErr != null) return jsonError(policyErr);
            String hashed = DatabaseHelper.hashPassword(password);
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (username, password) VALUES (?,?)")) {
                stmt.setString(1, username);
                stmt.setString(2, hashed);
                stmt.executeUpdate();
            } catch (Exception e) {
                return "{\"code\":400,\"msg\":\"用户名已存在\"}";
            }
            return "{\"code\":0,\"msg\":\"注册成功\"}";
        }

        if (path.equals("/api/auth/login") && method.equals("POST")) {
            org.json.JSONObject json = new org.json.JSONObject(body);
            String username = json.optString("username", "").trim();
            String password = json.optString("password", "").trim();
            // 先按用户名取记录，用 verifyPassword 兼容旧 SHA-256 哈希并在登录成功时透明升级为 PBKDF2
            String stored = null;
            String role = "user";
            long userId = -1;
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id, role, password FROM users WHERE username=?")) {
                stmt.setString(1, username);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    userId = rs.getLong("id");
                    role = rs.getString("role");
                    stored = rs.getString("password");
                }
            }
            if (userId > 0 && stored != null && DatabaseHelper.verifyPassword(password, stored)) {
                if (DatabaseHelper.needsRehash(stored)) {
                    // 旧格式（无盐 SHA-256）→ 登录成功后透明升级为 PBKDF2
                    try (PreparedStatement up = conn.prepareStatement("UPDATE users SET password=? WHERE id=?")) {
                        up.setString(1, DatabaseHelper.hashPassword(password));
                        up.setLong(2, userId);
                        up.executeUpdate();
                    }
                }
                String token = UUID.randomUUID().toString().replace("-", "");
                putSession(token, username, role);
                return "{\"code\":0,\"data\":{\"token\":\"" + token + "\",\"username\":\"" + username + "\",\"role\":\"" + role + "\"}}";
            }
            return jsonError("用户名或密码错误");
        }

        if (path.equals("/api/auth/verify") && method.equals("GET")) {
            String user = authUser(headers, query);
            if (user.isEmpty()) return jsonError("未登录");
            String token = tokenOf(headers, query);
            return "{\"code\":0,\"data\":{\"username\":\"" + user + "\",\"role\":\"" + sessionRoles.getOrDefault(token, "user") + "\"}}";
        }

        if (path.equals("/api/auth/logout") && method.equals("POST")) {
            removeSession(headers, query);
            return "{\"code\":0,\"msg\":\"已退出登录\"}";
        }

        // ===== Admin =====
        if (path.equals("/api/admin/users") && method.equals("GET")) {
            if (!isAdmin(headers, query)) return jsonError("需要管理员权限");
            try (PreparedStatement stmt = conn.prepareStatement("SELECT id, username, role, created_at FROM users ORDER BY id")) {
                return "{\"code\":0,\"data\":" + DatabaseHelper.resultSetToJson(stmt.executeQuery()) + "}";
            }
        }

        if (path.equals("/api/admin/users") && method.equals("POST")) {
            if (!isAdmin(headers, query)) return jsonError("需要管理员权限");
            org.json.JSONObject json = new org.json.JSONObject(body);
            String username = json.optString("username", "").trim();
            String password = json.optString("password", "").trim();
            String role = json.optString("role", "user");
            if (username.isEmpty() || password.isEmpty()) return jsonError("用户名和密码不能为空");
            if (!role.equals("admin") && !role.equals("user")) return jsonError("角色不合法");
            String policyErr = passwordPolicyError(password);
            if (policyErr != null) return jsonError(policyErr);
            String hashed = DatabaseHelper.hashPassword(password);
            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO users (username, password, role) VALUES (?,?,?)")) {
                stmt.setString(1, username);
                stmt.setString(2, hashed);
                stmt.setString(3, role);
                stmt.executeUpdate();
            } catch (Exception e) {
                return "{\"code\":400,\"msg\":\"用户名已存在\"}";
            }
            return "{\"code\":0,\"msg\":\"创建成功\"}";
        }

        if (path.matches("/api/admin/users/\\d+") && method.equals("DELETE")) {
            if (!isAdmin(headers, query)) return jsonError("需要管理员权限");
            String uid = path.replace("/api/admin/users/", "");
            long uidNum;
            try { uidNum = Long.parseLong(uid); } catch (NumberFormatException nfe) { return jsonError("参数错误"); }
            if (uidNum == 1) return jsonError("不能删除内置管理员账号");
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM users WHERE id=?")) {
                stmt.setLong(1, uidNum);
                stmt.executeUpdate();
            }
            return "{\"code\":0,\"msg\":\"已删除\"}";
        }

        if (path.matches("/api/admin/users/\\d+") && method.equals("PUT")) {
            if (!isAdmin(headers, query)) return jsonError("需要管理员权限");
            String uid = path.replace("/api/admin/users/", "");
            org.json.JSONObject json = new org.json.JSONObject(body);
            String password = json.optString("password", "").trim();
            String role = json.optString("role", "").trim();
            StringBuilder usql = new StringBuilder("UPDATE users SET ");
            List<String> uargs = new ArrayList<>();
            if (!password.isEmpty()) {
                String policyErr = passwordPolicyError(password);
                if (policyErr != null) return jsonError(policyErr);
                usql.append("password=?,");
                uargs.add(DatabaseHelper.hashPassword(password));
            }
            if (!role.isEmpty()) {
                if (!role.equals("admin") && !role.equals("user")) return jsonError("角色不合法");
                usql.append("role=?,");
                uargs.add(role);
            }
            if (uargs.isEmpty()) return jsonError("没有需要更新的字段");
            usql.setLength(usql.length() - 1);
            usql.append(" WHERE id=?");
            uargs.add(uid);
            try (PreparedStatement stmt = conn.prepareStatement(usql.toString())) {
                for (int i = 0; i < uargs.size(); i++) {
                    stmt.setString(i + 1, uargs.get(i));
                }
                stmt.executeUpdate();
            }
            return "{\"code\":0,\"msg\":\"更新成功\"}";
        }

        return "{\"code\":404,\"msg\":\"接口不存在\"}";
    }

    // ===== 认证辅助 =====
    // Token 优先取 Authorization: Bearer 头，回退 query ?token=（兼容旧客户端）
    private String tokenOf(Map<String, String> headers, Map<String, String> query) {
        String auth = headers != null ? headers.getOrDefault("authorization", "") : "";
        if (auth.startsWith("Bearer ")) return auth.substring(7).trim();
        return query.getOrDefault("token", "");
    }

    // 校验会话并滑动续期；过期会话自动清除
    private String authUser(Map<String, String> headers, Map<String, String> query) {
        String token = tokenOf(headers, query);
        if (token.isEmpty() || !sessions.containsKey(token)) return "";
        Long last = sessionLastAccess.get(token);
        long now = System.currentTimeMillis();
        if (last != null && now - last > SESSION_TTL_MS) {
            sessions.remove(token); sessionRoles.remove(token); sessionLastAccess.remove(token);
            return "";
        }
        sessionLastAccess.put(token, now);
        return sessions.get(token);
    }

    private boolean isAdmin(Map<String, String> headers, Map<String, String> query) {
        String token = tokenOf(headers, query);
        if (authUser(headers, query).isEmpty()) return false;
        return "admin".equals(sessionRoles.get(token));
    }

    private void putSession(String token, String username, String role) {
        sessions.put(token, username);
        sessionRoles.put(token, role);
        sessionLastAccess.put(token, System.currentTimeMillis());
    }

    private boolean removeSession(Map<String, String> headers, Map<String, String> query) {
        String token = tokenOf(headers, query);
        boolean existed = !token.isEmpty() && sessions.remove(token) != null;
        sessionRoles.remove(token);
        sessionLastAccess.remove(token);
        return existed;
    }

    // 密码策略：至少 8 位，须包含字母和数字
    private String passwordPolicyError(String password) {
        if (password == null || password.length() < 8) return "密码至少 8 位";
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) return "密码必须同时包含字母和数字";
        return null;
    }

    // 文件名安全净化：剥离路径分量、拒绝危险扩展名；不合法返回 null
    private String sanitizeFilename(String raw) {
        if (raw == null) return null;
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("..")) return null;
        if (name.length() > 150) return null;
        String lower = name.toLowerCase();
        String[] denied = {".exe", ".bat", ".cmd", ".com", ".scr", ".pif", ".jar", ".sh", ".vbs", ".ps1",
                           ".js", ".html", ".htm", ".svg", ".dll", ".msi", ".reg"};
        for (String ext : denied) {
            if (lower.endsWith(ext)) return null;
        }
        // 仅允许常见字符（含中文、空格、括号等）
        if (!name.matches("[\\w\\u4e00-\\u9fa5. ()（）\\[\\]【】-]+")) return null;
        return name;
    }

    // 完整的 JSON 字符串转义（jsonError 等拼接场景使用）
    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    private List<Long> collectTagTreeIds(Connection conn, String tagName) {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement find = conn.prepareStatement("SELECT id FROM tags WHERE name=?")) {
            find.setString(1, tagName);
            ResultSet rs = find.executeQuery();
            if (!rs.next()) return ids;
            long rootId = rs.getLong(1);
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(rootId);
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                ids.add(cur);
                try (PreparedStatement sub = conn.prepareStatement("SELECT id FROM tags WHERE parent_id=?")) {
                    sub.setLong(1, cur);
                    ResultSet subs = sub.executeQuery();
                    while (subs.next()) queue.add(subs.getLong(1));
                }
            }
        } catch (Exception e) {
            ids = new ArrayList<>();
            try (PreparedStatement one = conn.prepareStatement("SELECT id FROM tags WHERE name=?")) {
                one.setString(1, tagName);
                ResultSet rs = one.executeQuery();
                if (rs.next()) ids.add(rs.getLong(1));
            } catch (Exception ignored) {}
        }
        return ids;
    }

    private String buildTagInClause(List<Long> ids, List<String> argsList) {
        if (ids.isEmpty()) return " AND 1=0";
        StringBuilder in = new StringBuilder(" AND e.id IN (SELECT et.entry_id FROM entry_tags et WHERE et.tag_id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
            argsList.add(String.valueOf(ids.get(i)));
        }
        in.append("))");
        return in.toString();
    }

    private String buildRecordTagInClause(List<Long> ids, List<String> argsList) {
        if (ids.isEmpty()) return " AND 1=0";
        StringBuilder in = new StringBuilder(" AND r.id IN (SELECT rt.record_id FROM record_tags rt WHERE rt.tag_id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) in.append(",");
            in.append("?");
            argsList.add(String.valueOf(ids.get(i)));
        }
        in.append("))");
        return in.toString();
    }

    private void handleTags(Connection conn, long entryId, org.json.JSONArray tags) throws Exception {
        if (tags == null) return;
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM entry_tags WHERE entry_id=?")) {
            del.setLong(1, entryId);
            del.executeUpdate();
        }
        for (int i = 0; i < tags.length(); i++) {
            String tagName = tags.getString(i).trim();
            if (tagName.isEmpty()) continue;
            long tagId;
            try (PreparedStatement sel = conn.prepareStatement("SELECT id FROM tags WHERE name=?")) {
                sel.setString(1, tagName);
                ResultSet c = sel.executeQuery();
                if (c.next()) {
                    tagId = c.getLong(1);
                } else {
                    try (PreparedStatement ins = conn.prepareStatement("INSERT INTO tags (name) VALUES (?)")) {
                        ins.setString(1, tagName);
                        ins.executeUpdate();
                    }
                    try (PreparedStatement li = conn.prepareStatement("SELECT last_insert_rowid()");
                         ResultSet nc = li.executeQuery()) {
                        nc.next();
                        tagId = nc.getLong(1);
                    }
                }
            }
            try (PreparedStatement ins = conn.prepareStatement("INSERT OR IGNORE INTO entry_tags (entry_id, tag_id) VALUES (?,?)")) {
                ins.setLong(1, entryId);
                ins.setLong(2, tagId);
                ins.executeUpdate();
            }
        }
    }

    private void handleRecordTags(Connection conn, long recordId, org.json.JSONArray tags) throws Exception {
        if (tags == null) return;
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM record_tags WHERE record_id=?")) {
            del.setLong(1, recordId);
            del.executeUpdate();
        }
        for (int i = 0; i < tags.length(); i++) {
            String tagName = tags.getString(i).trim();
            if (tagName.isEmpty()) continue;
            long tagId;
            try (PreparedStatement sel = conn.prepareStatement("SELECT id FROM tags WHERE name=?")) {
                sel.setString(1, tagName);
                ResultSet c = sel.executeQuery();
                if (c.next()) {
                    tagId = c.getLong(1);
                } else {
                    try (PreparedStatement ins = conn.prepareStatement("INSERT INTO tags (name) VALUES (?)")) {
                        ins.setString(1, tagName);
                        ins.executeUpdate();
                    }
                    try (PreparedStatement li = conn.prepareStatement("SELECT last_insert_rowid()");
                         ResultSet nc = li.executeQuery()) {
                        nc.next();
                        tagId = nc.getLong(1);
                    }
                }
            }
            try (PreparedStatement ins = conn.prepareStatement("INSERT OR IGNORE INTO record_tags (record_id, tag_id) VALUES (?,?)")) {
                ins.setLong(1, recordId);
                ins.setLong(2, tagId);
                ins.executeUpdate();
            }
        }
    }

    private void handleRecordLinks(Connection conn, long recordId, org.json.JSONArray linkedEntries) throws Exception {
        if (linkedEntries == null) return;
        for (int i = 0; i < linkedEntries.length(); i++) {
            long entryId = linkedEntries.getLong(i);
            try (PreparedStatement ins = conn.prepareStatement("INSERT OR IGNORE INTO record_links (record_id, entry_id) VALUES (?,?)")) {
                ins.setLong(1, recordId);
                ins.setLong(2, entryId);
                ins.executeUpdate();
            }
        }
    }

    private void importKbIntoDb(Connection conn, Path root, long[] counters, List<String> errs, boolean relinks) {
        java.io.File rootFile = root.toFile();
        if (!rootFile.exists() || !rootFile.isDirectory()) return;

        // Discover files: relative subdir -> (type, tagSource)
        // wiki -> note (个人知识), external-wiki -> article, team-wiki -> note (团队知识), skills -> skill
        // outputs -> 日报 records, team-outputs -> 团队日报 records, raw -> 日志 records, external-raw -> 资料 records
        List<Path> wikiFiles = new ArrayList<>();
        List<Path> externalFiles = new ArrayList<>();
        List<Path> teamWikiFiles = new ArrayList<>();
        List<Path> skillFiles = new ArrayList<>();
        List<Path> outputFiles = new ArrayList<>();
        List<Path> teamOutputFiles = new ArrayList<>();
        List<Path> rawFiles = new ArrayList<>();
        List<Path> externalRawFiles = new ArrayList<>();

        collectKbFiles(root.resolve("wiki"), ".md", wikiFiles, true);
        collectKbFiles(root.resolve("external-wiki"), ".md", externalFiles, true);
        collectKbFiles(root.resolve("team-wiki"), ".md", teamWikiFiles, true);
        collectKbFiles(root.resolve("skills"), ".yaml", skillFiles, true);
        collectKbFiles(root.resolve("skills"), ".md", skillFiles, true);
        collectKbFiles(root.resolve("outputs"), ".md", outputFiles, false);
        collectKbFiles(root.resolve("team-outputs"), ".md", teamOutputFiles, false);
        collectKbFiles(root.resolve("raw"), ".md", rawFiles, false);
        collectKbFiles(root.resolve("external-raw"), ".md", externalRawFiles, false);

        // Load existing titles to support incremental (skip on re-import)
        Set<String> existingEntryTitles = new HashSet<>();
        Set<String> existingRecordTitles = new HashSet<>();
        Map<String, Long> entryTitleToId = new HashMap<>();
        Set<String> existingTagNames = new HashSet<>();
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id, title FROM knowledge_entries")) {
                while (rs.next()) { existingEntryTitles.add(rs.getString("title")); entryTitleToId.put(rs.getString("title"), rs.getLong("id")); }
            }
            try (ResultSet rs = st.executeQuery("SELECT title FROM work_records")) {
                while (rs.next()) existingRecordTitles.add(rs.getString("title"));
            }
            try (ResultSet rs = st.executeQuery("SELECT name FROM tags")) {
                while (rs.next()) existingTagNames.add(rs.getString("name"));
            }
        } catch (Exception e) {
            errs.add("读取现有数据失败: " + e.getMessage());
        }

        // Import wiki entries
        for (Path p : wikiFiles) {
            importKbEntry(conn, p, "note", "个人知识", kbSourceOf(root, p), mtimeOf(p),
                existingEntryTitles, entryTitleToId, existingTagNames, counters, errs);
        }
        for (Path p : externalFiles) {
            if (p.getFileName().toString().equals("README.md")) continue;
            importKbEntry(conn, p, "article", "外部知识", kbSourceOf(root, p), mtimeOf(p),
                existingEntryTitles, entryTitleToId, existingTagNames, counters, errs);
        }
        for (Path p : teamWikiFiles) {
            if (p.getFileName().toString().equals("README.md")) continue;
            importKbEntry(conn, p, "note", "团队知识", kbSourceOf(root, p), mtimeOf(p),
                existingEntryTitles, entryTitleToId, existingTagNames, counters, errs);
        }
        for (Path p : skillFiles) {
            java.io.File parent = p.toFile().getParentFile();
            String parName = parent != null ? parent.getName() : "";
            boolean isSkillsRoot = root.resolve("skills").toFile().getName().equals(parName);
            String tag = isSkillsRoot || parName.isEmpty() ? "技能" : parName;
            importKbEntry(conn, p, "skill", tag, kbSourceOf(root, p), mtimeOf(p),
                existingEntryTitles, entryTitleToId, existingTagNames, counters, errs);
        }
        // Import records
        for (Path p : outputFiles) {
            importKbRecord(conn, p, "日报", kbSourceOf(root, p), mtimeOf(p),
                existingRecordTitles, existingTagNames, counters, errs);
        }
        for (Path p : teamOutputFiles) {
            importKbRecord(conn, p, "团队日报", kbSourceOf(root, p), mtimeOf(p),
                existingRecordTitles, existingTagNames, counters, errs);
        }
        for (Path p : rawFiles) {
            importKbRecord(conn, p, "日志", kbSourceOf(root, p), mtimeOf(p),
                existingRecordTitles, existingTagNames, counters, errs);
        }
        for (Path p : externalRawFiles) {
            importKbRecord(conn, p, "资料", kbSourceOf(root, p), mtimeOf(p),
                existingRecordTitles, existingTagNames, counters, errs);
        }

        // relinks 模式：强制重新解析所有已有条目的双链（修复历史遗漏/路径式链接/手动新增的链接）
        if (relinks) {
            try {
                // 重建 titleToId（包括本次新增的条目）
                Map<String, Long> fullTitleToId = new HashMap<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, title FROM knowledge_entries WHERE status='active'")) {
                    while (rs.next()) fullTitleToId.put(rs.getString("title"), rs.getLong("id"));
                }
                // 先清空所有旧链接，再全部重建
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM entry_links");
                }
                // 对每条有内容的条目重新解析双链
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT id, title, content FROM knowledge_entries WHERE status='active' AND content IS NOT NULL AND content != ''")) {
                    while (rs.next()) {
                        long eid = rs.getLong("id");
                        String title = rs.getString("title");
                        String content = rs.getString("content");
                        resolveEntryLinks(conn, eid, content, fullTitleToId, counters);
                        counters[8]++;
                    }
                }
            } catch (Exception e) {
                errs.add("relinks error: " + e.getMessage());
            }
        }
    }

    private String kbSourceOf(Path root, Path p) {
        try {
            return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
        } catch (Exception e) {
            return p.getFileName().toString();
        }
    }

    private String mtimeOf(Path p) {
        try { return String.valueOf(Files.getLastModifiedTime(p).toMillis()); }
        catch (Exception e) { return ""; }
    }

    private void collectKbFiles(Path dir, String suffix, List<Path> out, boolean recursive) {
        java.io.File f = dir.toFile();
        if (!f.exists() || !f.isDirectory()) return;
        java.io.File[] files = f.listFiles();
        if (files == null) return;
        for (java.io.File child : files) {
            if (child.isDirectory()) {
                if (recursive) collectKbFiles(child.toPath(), suffix, out, true);
            } else if (child.getName().toLowerCase().endsWith(suffix)) {
                out.add(child.toPath());
            }
        }
    }

    private void importKbEntry(Connection conn, Path p, String type, String tag,
                               String source, String mtime,
                               Set<String> existingTitles, Map<String, Long> titleToId,
                               Set<String> existingTagNames, long[] counters, List<String> errs) {
        String title = p.getFileName().toString();
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        try {
            String content = readKbText(p);
            if (existingTitles.contains(title)) {
                syncEntryIfChanged(conn, title, source, mtime, content, counters);
                // 即使内容未变，也重新解析双链（补全历史缺失、路径式链接兼容、同批次新入库目标）
                Long existingId = titleToId.get(title);
                if (existingId != null && existingId > 0) {
                    resolveEntryLinks(conn, existingId, content, titleToId, counters);
                }
                return;
            }
            String summary = makeSummary(content);
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO knowledge_entries (title, content, summary, entry_type, status, importance, created_by, created_at, updated_at, kb_source, kb_mtime) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                stmt.setString(1, title);
                stmt.setString(2, content);
                stmt.setString(3, summary);
                stmt.setString(4, type);
                stmt.setString(5, "active");
                stmt.setInt(6, 0);
                stmt.setString(7, "kb-import");
                String ts = now();
                stmt.setString(8, ts);
                stmt.setString(9, ts);
                stmt.setString(10, source);
                stmt.setString(11, mtime);
                stmt.executeUpdate();
            }
            long entryId;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next(); entryId = rs.getLong(1);
            }
            existingTitles.add(title);
            titleToId.put(title, entryId);
            counters[0]++;
            long tagId = getOrCreateTag(conn, tag, existingTagNames, counters);
            if (tagId > 0) {
                try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO entry_tags (entry_id, tag_id) VALUES (?,?)")) {
                    stmt.setLong(1, entryId);
                    stmt.setLong(2, tagId);
                    stmt.executeUpdate();
                }
            }
            resolveEntryLinks(conn, entryId, content, titleToId, counters);
        } catch (Exception e) {
            errs.add(title + ": " + e.getMessage());
        }
    }

    // 已存在条目：源文件更新时间变化（或首次同步/乱码）时覆盖更新
    // 返回 true 表示本次执行了内容同步（覆盖），false 表示跳过
    private boolean syncEntryIfChanged(Connection conn, String title, String source, String mtime,
                                       String correct, long[] counters) {
        try (PreparedStatement sel = conn.prepareStatement(
            "SELECT id, content, kb_mtime FROM knowledge_entries WHERE title=?")) {
            sel.setString(1, title);
            ResultSet c = sel.executeQuery();
            if (!c.next()) return false;
            long id = c.getLong("id");
            String stored = c.getString("content");
            String storedMtime = c.getString("kb_mtime");
            if (storedMtime == null) storedMtime = "";
            boolean needSync = stored != null && stored.contains("\uFFFD");
            if (!needSync && storedMtime.isEmpty()) needSync = true;
            if (!needSync) {
                try {
                    long src = Long.parseLong(mtime);
                    long cur = Long.parseLong(storedMtime);
                    if (src > cur) needSync = true;
                } catch (NumberFormatException e) {
                    needSync = true;
                }
            }
            if (!needSync) { counters[1]++; return false; }
            try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE knowledge_entries SET content=?, summary=?, kb_source=?, kb_mtime=?, updated_at=? WHERE id=?")) {
                upd.setString(1, correct);
                upd.setString(2, makeSummary(correct));
                upd.setString(3, source);
                upd.setString(4, mtime);
                upd.setString(5, now());
                upd.setLong(6, id);
                upd.executeUpdate();
            }
            counters[6]++;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void importKbRecord(Connection conn, Path p, String tag,
                                String source, String mtime,
                                Set<String> existingTitles, Set<String> existingTagNames,
                                long[] counters, List<String> errs) {
        String title = p.getFileName().toString();
        int dot = title.lastIndexOf('.');
        if (dot > 0) title = title.substring(0, dot);
        try {
            String content = readKbText(p);
            if (existingTitles.contains(title)) {
                syncRecordIfChanged(conn, title, source, mtime, content, counters);
                return;
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT INTO work_records (title, content, record_type, status, importance, created_by, created_at, updated_at, kb_source, kb_mtime) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                stmt.setString(1, title);
                stmt.setString(2, content);
                stmt.setString(3, "daily");
                stmt.setString(4, "active");
                stmt.setInt(5, 0);
                stmt.setString(6, "kb-import");
                String ts = now();
                stmt.setString(7, ts);
                stmt.setString(8, ts);
                stmt.setString(9, source);
                stmt.setString(10, mtime);
                stmt.executeUpdate();
            }
            long recordId;
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next(); recordId = rs.getLong(1);
            }
            existingTitles.add(title);
            counters[2]++;
            long tagId = getOrCreateTag(conn, tag, existingTagNames, counters);
            if (tagId > 0) {
                try (PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO record_tags (record_id, tag_id) VALUES (?,?)")) {
                    stmt.setLong(1, recordId);
                    stmt.setLong(2, tagId);
                    stmt.executeUpdate();
                }
            }
        } catch (Exception e) {
            errs.add(title + ": " + e.getMessage());
        }
    }

    // 已存在记录：源文件更新时间变化（或首次同步/乱码）时覆盖更新
    private boolean syncRecordIfChanged(Connection conn, String title, String source, String mtime,
                                        String correct, long[] counters) {
        try (PreparedStatement sel = conn.prepareStatement(
            "SELECT id, content, kb_mtime FROM work_records WHERE title=?")) {
            sel.setString(1, title);
            ResultSet c = sel.executeQuery();
            if (!c.next()) return false;
            long id = c.getLong("id");
            String stored = c.getString("content");
            String storedMtime = c.getString("kb_mtime");
            if (storedMtime == null) storedMtime = "";
            boolean needSync = stored != null && stored.contains("\uFFFD");
            if (!needSync && storedMtime.isEmpty()) needSync = true;
            if (!needSync) {
                try {
                    long src = Long.parseLong(mtime);
                    long cur = Long.parseLong(storedMtime);
                    if (src > cur) needSync = true;
                } catch (NumberFormatException e) {
                    needSync = true;
                }
            }
            if (!needSync) { counters[3]++; return false; }
            try (PreparedStatement upd = conn.prepareStatement(
                "UPDATE work_records SET content=?, kb_source=?, kb_mtime=?, updated_at=? WHERE id=?")) {
                upd.setString(1, correct);
                upd.setString(2, source);
                upd.setString(3, mtime);
                upd.setString(4, now());
                upd.setLong(5, id);
                upd.executeUpdate();
            }
            counters[7]++;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private long getOrCreateTag(Connection conn, String name, Set<String> existingNames, long[] counters) throws Exception {
        if (name == null || name.trim().isEmpty()) return 0;
        name = name.trim();
        if (existingNames.contains(name)) {
            try (PreparedStatement sel = conn.prepareStatement("SELECT id FROM tags WHERE name=?")) {
                sel.setString(1, name);
                ResultSet c = sel.executeQuery();
                if (c.next()) return c.getLong(1);
            }
        }
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO tags (name, color) VALUES (?, '#1a73e8')")) {
            ins.setString(1, name);
            ins.executeUpdate();
        }
        long tagId;
        try (PreparedStatement li = conn.prepareStatement("SELECT last_insert_rowid()"); ResultSet nc = li.executeQuery()) {
            nc.next(); tagId = nc.getLong(1);
        }
        existingNames.add(name);
        counters[5]++;
        return tagId;
    }

    private String readKbText(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            try {
                return dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                return new String(bytes, "GB18030");
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String makeSummary(String content) {
        String s = content.trim();
        if (s.isEmpty()) return "";
        String[] lines = s.split("\n");
        for (String line : lines) {
            String t = line.replaceAll("^[#\\s>\\-\\*]+", "").trim();
            if (!t.isEmpty()) {
                if (t.length() > 200) t = t.substring(0, 200) + "...";
                return t;
            }
        }
        return "";
    }

    private void resolveEntryLinks(Connection conn, long entryId, String content, Map<String, Long> titleToId, long[] counters) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[\\[([^\\]\\|]+)\\|?[^\\]]*\\]\\]").matcher(content);
        while (m.find()) {
            String targetTitle = m.group(1).trim();
            Long targetId = titleToId.get(targetTitle);
            // 路径式链接回退：[[目录/标题]] 匹配不到时，取末段（KB 子目录中的条目标题=文件名）
            if (targetId == null) {
                String base = targetTitle.contains("/")
                    ? targetTitle.substring(targetTitle.lastIndexOf('/') + 1).trim()
                    : (targetTitle.contains("\\") ? targetTitle.substring(targetTitle.lastIndexOf('\\') + 1).trim() : null);
                if (base != null && !base.isEmpty()) targetId = titleToId.get(base);
            }
            if (targetId == null || targetId == entryId) continue;
            try (PreparedStatement stmt = conn.prepareStatement(
                "INSERT OR IGNORE INTO entry_links (source_id, target_id, link_type) VALUES (?,?,?)")) {
                stmt.setLong(1, entryId);
                stmt.setLong(2, targetId);
                stmt.setString(3, "reference");
                if (stmt.executeUpdate() > 0) counters[4]++;
            } catch (Exception ignored) {}
        }
    }

    private void saveVersion(Connection conn, long entryId, String title, String content, String changeSummary) throws Exception {
        int version;
        try (PreparedStatement sel = conn.prepareStatement("SELECT COALESCE(MAX(version), 0) FROM entry_versions WHERE entry_id=?")) {
            sel.setLong(1, entryId);
            ResultSet c = sel.executeQuery();
            c.next();
            version = c.getInt(1) + 1;
        }
        try (PreparedStatement ins = conn.prepareStatement(
            "INSERT INTO entry_versions (entry_id, title, content, version, change_summary) VALUES (?,?,?,?,?)")) {
            ins.setLong(1, entryId);
            ins.setString(2, title);
            ins.setString(3, content);
            ins.setInt(4, version);
            ins.setString(5, changeSummary);
            ins.executeUpdate();
        }
    }

    private String urlDecode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private Map<String, String> parseQuery(String qs) {
        Map<String, String> map = new HashMap<>();
        if (qs.isEmpty()) return map;
        for (String pair : qs.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(urlDecode(kv[0]), kv.length > 1 ? urlDecode(kv[1]) : "");
        }
        return map;
    }

    private String getLanIp() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private String jsonError(String msg) {
        return "{\"code\":500,\"msg\":\"" + jsonEscape(msg) + "\"}";
    }

    private void serveStaticFile(String path, OutputStream os) throws IOException {
        String filePath = path.equals("/") ? "/index.html" : path;
        // Security: 规范化后必须仍在 webRoot 内（防目录穿越，含编码变体）
        Path webRootAbs = Paths.get(webRoot).toAbsolutePath().normalize();
        Path fullPath = webRootAbs.resolve(filePath.startsWith("/") ? filePath.substring(1) : filePath).normalize();
        if (!fullPath.startsWith(webRootAbs)) {
            String error = "<html><body><h1>403 Forbidden</h1></body></html>";
            byte[] errBytes = error.getBytes("UTF-8");
            os.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\nContent-Length: " + errBytes.length + "\r\n\r\n").getBytes("UTF-8"));
            os.write(errBytes);
            return;
        }
        // 不对外暴露备份/日志/文档源文件
        String lowerName = fullPath.getFileName().toString().toLowerCase();
        if (lowerName.endsWith(".bak") || lowerName.endsWith(".log") || lowerName.endsWith(".db")
            || lowerName.endsWith(".md") || lowerName.endsWith(".bat") || lowerName.endsWith(".sh")) {
            String error = "<html><body><h1>403 Forbidden</h1></body></html>";
            byte[] errBytes = error.getBytes("UTF-8");
            os.write(("HTTP/1.1 403 Forbidden\r\nContent-Type: text/html; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\nContent-Length: " + errBytes.length + "\r\n\r\n").getBytes("UTF-8"));
            os.write(errBytes);
            return;
        }
        if (!Files.exists(fullPath) || !Files.isRegularFile(fullPath)) {
            // fallback to index.html for SPA
            fullPath = Paths.get(webRoot, "index.html");
            if (!Files.exists(fullPath)) {
                String error = "<html><body><h1>404 Not Found</h1></body></html>";
                byte[] errBytes = error.getBytes("UTF-8");
                os.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/html; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\nContent-Length: " + errBytes.length + "\r\n\r\n").getBytes("UTF-8"));
                os.write(errBytes);
                return;
            }
        }

        String mime = "text/html";
        String name = fullPath.toString().toLowerCase();
        if (name.endsWith(".css")) mime = "text/css";
        else if (name.endsWith(".js")) mime = "application/javascript";
        else if (name.endsWith(".png")) mime = "image/png";
        else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) mime = "image/jpeg";
        else if (name.endsWith(".svg")) mime = "image/svg+xml";
        else if (name.endsWith(".json")) mime = "application/json";
        else if (name.endsWith(".ico")) mime = "image/x-icon";

        byte[] data = Files.readAllBytes(fullPath);
        String header = "HTTP/1.1 200 OK\r\nContent-Type: " + mime + "; charset=utf-8\r\nX-Content-Type-Options: nosniff\r\nContent-Length: " + data.length + "\r\n\r\n";
        os.write(header.getBytes("UTF-8"));
        os.write(data);
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("UTF-8");
    }
}