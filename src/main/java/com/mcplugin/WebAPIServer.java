package com.mcplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class WebAPIServer {

    private final WebBridgePlugin plugin;
    private final DatabaseManager db;
    private final ChatSyncListener chat;
    private final MailSender mailSender;
    private final EconomyConfig economyConfig;
    private HttpServer server;
    private final Gson gson = new Gson();
    private final Map<String, String> verificationCodes = new ConcurrentHashMap<String, String>();
    private final Map<String, Long> codeExpiry = new ConcurrentHashMap<String, Long>();
    private final Set<String> onlineWebAdmins = ConcurrentHashMap.newKeySet();

    public WebAPIServer(WebBridgePlugin plugin, DatabaseManager db, ChatSyncListener chat, MailSender mailSender, EconomyConfig economyConfig) {
        this.plugin = plugin;
        this.db = db;
        this.chat = chat;
        this.mailSender = mailSender;
        this.economyConfig = economyConfig;
    }

    public List<String> getOnlineWebAdmins() {
        return new ArrayList<String>(onlineWebAdmins);
    }

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new MainHandler());
            server.createContext("/api/", new APIHandler());
            server.createContext("/uploads/", new FileHandler());
            server.createContext("/default_avatar.png", new ResourceHandler());
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            plugin.getLogger().info("Web服务器已启动: http://localhost:" + port);
        } catch (IOException e) {
            plugin.getLogger().severe("Web服务器启动失败: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // ====== 资源处理器 ======
    class ResourceHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            InputStream is = getClass().getClassLoader().getResourceAsStream("web/default_avatar.png");
            if (is == null) {
                String msg = "404";
                ex.sendResponseHeaders(404, msg.length());
                ex.getResponseBody().write(msg.getBytes());
                ex.getResponseBody().close();
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            byte[] bytes = bos.toByteArray();
            is.close();
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.getResponseBody().close();
        }
    }

    class FileHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            // 尝试从 avatars 目录加载
            File file = new File(plugin.getDataFolder(), "avatars" + path.replace("/uploads", ""));
            if (!file.exists()) {
                // 回退到 uploads 目录
                file = new File(plugin.getDataFolder(), "uploads" + path.replace("/uploads", ""));
            }
            if (file.exists()) {
                String contentType = path.endsWith(".png") ? "image/png" : path.endsWith(".gif") ? "image/gif" : "image/jpeg";
                ex.getResponseHeaders().set("Content-Type", contentType);
                ex.sendResponseHeaders(200, file.length());
                FileInputStream fis = new FileInputStream(file);
                OutputStream os = ex.getResponseBody();
                byte[] buf = new byte[8192];
                int len;
                while ((len = fis.read(buf)) != -1) os.write(buf, 0, len);
                fis.close();
                os.close();
            } else {
                String msg = "404";
                ex.sendResponseHeaders(404, msg.length());
                ex.getResponseBody().write(msg.getBytes());
                ex.getResponseBody().close();
            }
        }
    }

    // ====== 工具方法 ======
    private String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int n;
        while ((n = is.read(b)) != -1) bos.write(b, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void sendJson(HttpExchange ex, int code, Object data) throws IOException {
        String json = gson.toJson(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
        ex.sendResponseHeaders(code, bytes.length);
        OutputStream os = ex.getResponseBody();
        os.write(bytes);
        os.close();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }

    private String getToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            String[] parts = cookie.split(";");
            for (String c : parts) {
                String[] p = c.trim().split("=", 2);
                if (p.length == 2 && p[0].equals("token")) return p[1];
            }
        }
        return null;
    }

    private Map<String, Object> validate(HttpExchange ex) {
        String token = getToken(ex);
        if (token == null) return null;
        Map<String, Object> d = db.validateToken(token);
        if (d.isEmpty()) return null;
        if ("admin".equals(d.get("type"))) {
            d.put("isAdmin", true);
            d.put("username", d.get("uuid"));
            try {
                ResultSet rs = db.query("SELECT level, display_name FROM admins WHERE username='" + esc((String)d.get("uuid")) + "'");
                if (rs.next()) {
                    d.put("isSuperAdmin", "super".equals(rs.getString("level")));
                    d.put("display_name", rs.getString("display_name"));
                    d.put("level", rs.getString("level"));
                }
            } catch (SQLException e) {
                d.put("isSuperAdmin", false);
            }
            onlineWebAdmins.add((String)d.get("username"));
            // 管理员也获取用户数据
            Map<String, Object> ud = db.getPlayerData("admin");
            if (!ud.isEmpty()) {
                d.put("email", ud.get("email"));
                d.put("balance", ud.get("balance"));
                d.put("points", ud.get("points"));
                d.put("bio", ud.get("bio"));
                d.put("avatar_url", ud.get("avatar_url"));
                d.put("hasUserData", true);
            }
        } else {
            d.put("isAdmin", false);
            d.put("isSuperAdmin", false);
            Map<String, Object> ud = db.getPlayerData((String)d.get("uuid"));
            if (!ud.isEmpty()) d.putAll(ud);
        }
        return d;
    }

    private Map<String, Object> M(Object... kv) {
        Map<String, Object> m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String)kv[i], kv[i+1]);
        return m;
    }

    private String formatDateTime(String timestamp) {
        if (timestamp == null || timestamp.length() < 19) return "";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(sdf.parse(timestamp.substring(0, 19)));
        } catch (Exception e) {
            return timestamp;
        }
    }

    private Object getDefaultByType(String type) {
        if ("double".equals(type)) return 0.0;
        if ("integer".equals(type)) return 0;
        return "-";
    }

    // ====== 主页面处理器 ======
    class MainHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            byte[] bytes = buildPage().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.getResponseBody().close();
        }
    }

    // ====== API路由器 ======
    class APIHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) { sendJson(ex, 200, "ok"); return; }
            try {
                if ("/api/login".equals(path)) login(ex);
                else if ("/api/register".equals(path)) register(ex);
                else if ("/api/sendCode".equals(path)) sendCode(ex);
                else if ("/api/logout".equals(path)) logout(ex);
                else if ("/api/user/info".equals(path)) userInfo(ex);
                else if ("/api/user/update".equals(path)) userUpdate(ex);
                else if ("/api/user/changePassword".equals(path)) changePassword(ex);
                else if ("/api/user/changeEmail".equals(path)) changeEmail(ex);
                else if ("/api/admin/changeUserPassword".equals(path)) adminChangeUserPassword(ex);
                else if ("/api/user/uploadAvatar".equals(path)) uploadAvatar(ex);
                else if ("/api/user/posts".equals(path)) userPosts(ex);
                else if ("/api/user/economy".equals(path)) userEconomy(ex);
                else if ("/api/user/reviews".equals(path)) userReviews(ex);
                else if ("/api/user/reviews/add".equals(path)) addUserReview(ex);
                else if ("/api/user/reviews/reply".equals(path)) replyToReview(ex);
                else if ("/api/user/privacy".equals(path)) togglePrivacy(ex);
                else if ("/api/server/info".equals(path)) serverInfo(ex);
                else if ("/api/server/motd".equals(path)) serverMOTD(ex);
                else if ("/api/server/resources".equals(path)) serverResources(ex);
                else if ("/api/server/adminOnline".equals(path)) adminOnline(ex);
                else if ("/api/chat/messages".equals(path)) chatMessages(ex);
                else if ("/api/chat/send".equals(path)) chatSend(ex);
                else if ("/api/chat/pm".equals(path)) sendPM(ex);
                else if ("/api/announcements".equals(path)) announcements(ex);
                else if ("/api/community/posts".equals(path)) communityPosts(ex);
                else if ("/api/community/react".equals(path)) communityReact(ex);
                else if ("/api/community/upload".equals(path)) communityUpload(ex);
                else if ("/api/community/replies".equals(path)) communityReplies(ex);
                else if ("/api/community/reply/react".equals(path)) communityReplyReact(ex);
                else if ("/api/suggestions".equals(path)) suggestions(ex);
                else if ("/api/suggestions/reply".equals(path)) replySuggestion(ex);
                else if ("/api/suggestions/my".equals(path)) mySuggestions(ex);
                else if ("/api/notifications".equals(path)) getNotifications(ex);
                else if ("/api/notifications/read".equals(path)) markNotificationRead(ex);
                else if ("/api/notifications/reply".equals(path)) replyNotification(ex);
                else if ("/api/admin/announcement".equals(path)) adminAnnouncement(ex);
                else if ("/api/admin/users".equals(path)) adminUsers(ex);
                else if ("/api/admin/admins".equals(path)) adminAdmins(ex);
                else if ("/api/admin/addAdmin".equals(path)) adminAddAdmin(ex);
                else if ("/api/admin/removeAdmin".equals(path)) adminRemoveAdmin(ex);
                else if ("/api/admin/suggestions".equals(path)) adminSuggestions(ex);
                else if ("/api/admin/deletePost".equals(path)) adminDeletePost(ex);
                else if ("/api/admin/pinPost".equals(path)) adminPinPost(ex);
                else if ("/api/admin/ban".equals(path)) adminBan(ex);
                else if ("/api/admin/unban".equals(path)) adminUnban(ex);
                else if ("/api/admin/bans".equals(path)) adminBans(ex);
                else if ("/api/admin/updateAbout".equals(path)) adminUpdateAbout(ex);
                else if ("/api/admin/getAbout".equals(path)) adminGetAbout(ex);
                else sendJson(ex, 404, M("error", "Not Found"));
            } catch (Exception e) {
                plugin.getLogger().warning("API错误: " + path + " - " + e.getMessage());
                sendJson(ex, 500, M("error", "服务器错误"));
            }
        }
    }

    // ====== 认证API ======
    private void login(HttpExchange ex) throws IOException {
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        boolean rem = "true".equals(p.get("remember"));
        try {
            ResultSet rs = db.query("SELECT * FROM admins WHERE username='" + esc(p.get("email")) + "' OR email='" + esc(p.get("email")) + "'");
            if (rs.next() && rs.getString("password").equals(p.get("password"))) {
                String token = db.createToken(rs.getString("username"), "admin", rem);
                Map<String, Object> data = M("username", rs.getString("username"), "display_name", rs.getString("display_name"), "level", rs.getString("level"), "isSuperAdmin", "super".equals(rs.getString("level")));
                Map<String, Object> adminUd = db.getPlayerData("admin");
                if (!adminUd.isEmpty()) {
                    data.put("uuid", "admin");
                    data.put("email", adminUd.get("email"));
                    data.put("balance", adminUd.get("balance"));
                    data.put("points", adminUd.get("points"));
                    data.put("bio", adminUd.get("bio"));
                    data.put("avatar_url", adminUd.get("avatar_url"));
                    data.put("hasUserData", true);
                }
                sendJson(ex, 200, M("success", true, "type", "admin", "data", data, "token", token));
                return;
            }
            Map<String, Object> ud = db.getPlayerByEmail(p.get("email"));
            if (!ud.isEmpty()) {
                if (Boolean.TRUE.equals(ud.get("is_banned"))) {
                    sendJson(ex, 200, M("success", false, "message", "账号已被封禁")); return;
                }
                if (ud.get("password").equals(p.get("password"))) {
                    String token = db.createToken((String)ud.get("uuid"), "user", rem);
                    ud.remove("password");
                    ud.put("isSuperAdmin", false);
                    sendJson(ex, 200, M("success", true, "type", "user", "data", ud, "token", token));
                } else {
                    sendJson(ex, 200, M("success", false, "message", "账号或密码错误"));
                }
            } else {
                sendJson(ex, 200, M("success", false, "message", "账号或密码错误"));
            }
        } catch (SQLException e) {
            sendJson(ex, 500, M("success", false));
        }
    }

    private void register(HttpExchange ex) throws IOException {
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String email = p.get("email");
        String code = p.get("code");
        String username = p.get("username");
        String password = p.get("password");
        if (username == null || username.length() < 3) { sendJson(ex, 200, M("success", false, "message", "用户名至少3字符")); return; }
        if (email == null || !email.matches("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) { sendJson(ex, 200, M("success", false, "message", "邮箱格式错误")); return; }
        if (password == null || password.length() < 6) { sendJson(ex, 200, M("success", false, "message", "密码至少6位")); return; }
        if (mailSender.isEnabled()) {
            if (code == null || code.isEmpty()) { sendJson(ex, 200, M("success", false, "message", "请输入验证码")); return; }
            String stored = verificationCodes.get(email);
            Long expiry = codeExpiry.get(email);
            if (stored == null || expiry == null || System.currentTimeMillis() > expiry) { sendJson(ex, 200, M("success", false, "message", "验证码已过期")); return; }
            if (!stored.equals(code)) { sendJson(ex, 200, M("success", false, "message", "验证码错误")); return; }
            verificationCodes.remove(email);
            codeExpiry.remove(email);
        }
        String bindCode = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try {
            PreparedStatement ps = db.prepare("INSERT INTO mc_accounts (username,email,password,bind_code,email_verified) VALUES (?,?,?,?,?)");
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, password);
            ps.setString(4, bindCode);
            ps.setBoolean(5, mailSender.isEnabled());
            ps.executeUpdate();
            sendJson(ex, 200, M("success", true, "bind_code", bindCode));
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                sendJson(ex, 200, M("success", false, "message", "邮箱已存在"));
            } else {
                sendJson(ex, 200, M("success", false, "message", "注册失败"));
            }
        }
    }

    private void sendCode(HttpExchange ex) throws IOException {
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String email = p.get("email");
        String code = String.format("%06d", new Random().nextInt(999999));
        verificationCodes.put(email, code);
        codeExpiry.put(email, System.currentTimeMillis() + 300000);
        boolean sent = mailSender.sendVerificationCode(email, code);
        if (sent) {
            sendJson(ex, 200, M("success", true, "message", "验证码已发送"));
        } else if (!mailSender.isEnabled()) {
            sendJson(ex, 200, M("success", true, "message", "开发模式:" + code, "dev_code", code));
        } else {
            sendJson(ex, 200, M("success", false, "message", "发送失败"));
        }
    }

    private void logout(HttpExchange ex) throws IOException {
        String t = getToken(ex);
        if (t != null) {
            Map<String, Object> a = db.validateToken(t);
            if ("admin".equals(a.get("type"))) onlineWebAdmins.remove(a.get("uuid"));
            db.removeToken(t);
        }
        sendJson(ex, 200, M("success", true));
    }

    private void userInfo(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null) { sendJson(ex, 401, M("success", false)); return; }
        sendJson(ex, 200, M("success", true, "data", a));
    }

    private void userUpdate(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            if ((boolean)a.get("isAdmin")) {
                if (p.containsKey("display_name")) {
                    db.update("UPDATE admins SET display_name='" + esc(p.get("display_name")) + "' WHERE username='" + esc((String)a.get("username")) + "'");
                }
            } else {
                if (p.containsKey("display_name")) {
                    db.update("UPDATE mc_accounts SET display_name='" + esc(p.get("display_name")) + "' WHERE uuid='" + esc((String)a.get("uuid")) + "'");
                }
                if (p.containsKey("bio")) {
                    db.update("UPDATE mc_accounts SET bio='" + esc(p.get("bio")) + "' WHERE uuid='" + esc((String)a.get("uuid")) + "'");
                }
            }
            sendJson(ex, 200, M("success", true));
        } catch (SQLException e) {
            sendJson(ex, 200, M("success", false));
        }
    }

    private void changePassword(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            if ((boolean)a.get("isAdmin")) {
                ResultSet rs = db.query("SELECT password FROM admins WHERE username='" + esc((String)a.get("username")) + "'");
                if (rs.next() && rs.getString("password").equals(p.get("oldPassword"))) {
                    db.update("UPDATE admins SET password='" + esc(p.get("newPassword")) + "' WHERE username='" + esc((String)a.get("username")) + "'");
                    sendJson(ex, 200, M("success", true, "message", "密码修改成功"));
                } else {
                    sendJson(ex, 200, M("success", false, "message", "原密码错误"));
                }
            } else {
                ResultSet rs = db.query("SELECT password FROM mc_accounts WHERE uuid='" + esc((String)a.get("uuid")) + "'");
                if (rs.next() && rs.getString("password").equals(p.get("oldPassword"))) {
                    db.update("UPDATE mc_accounts SET password='" + esc(p.get("newPassword")) + "' WHERE uuid='" + esc((String)a.get("uuid")) + "'");
                    sendJson(ex, 200, M("success", true, "message", "密码修改成功"));
                } else {
                    sendJson(ex, 200, M("success", false, "message", "原密码错误"));
                }
            }
        } catch (SQLException e) {
            sendJson(ex, 200, M("success", false));
        }
    }

    // ====== 修改邮箱 ======
    private void changeEmail(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || (boolean)a.get("isAdmin")) { sendJson(ex, 401, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String newEmail = p.get("email");
        String password = p.get("password");
        if (newEmail == null || !newEmail.matches("^[\\w-.]+@([\\w-]+\\.)+[\\w-]{2,4}$")) {
            sendJson(ex, 200, M("success", false, "message", "邮箱格式不正确")); return;
        }
        try {
            ResultSet rs = db.query("SELECT password FROM mc_accounts WHERE uuid='" + esc((String)a.get("uuid")) + "'");
            if (rs.next() && rs.getString("password").equals(password)) {
                db.update("UPDATE mc_accounts SET email='" + esc(newEmail) + "' WHERE uuid='" + esc((String)a.get("uuid")) + "'");
                sendJson(ex, 200, M("success", true, "message", "邮箱修改成功"));
            } else {
                sendJson(ex, 200, M("success", false, "message", "密码错误"));
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                sendJson(ex, 200, M("success", false, "message", "邮箱已被使用"));
            } else {
                sendJson(ex, 200, M("success", false));
            }
        }
    }

    private void adminChangeUserPassword(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) { sendJson(ex, 403, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            db.update("UPDATE mc_accounts SET password='" + esc(p.get("newPassword")) + "' WHERE uuid='" + esc(p.get("uuid")) + "'");
            sendJson(ex, 200, M("success", true));
        } catch (SQLException e) {
            sendJson(ex, 200, M("success", false));
        }
    }

    // ====== 修复后的头像上传（保存到 plugins/WebBridge/avatars/） ======
    private void uploadAvatar(HttpExchange ex) throws IOException {
        Map<String, Object> auth = validate(ex);
        if (auth == null) { sendJson(ex, 401, M("success", false)); return; }
        try {
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.contains("multipart/form-data")) {
                sendJson(ex, 200, M("success", false, "message", "请上传图片")); return;
            }
            String boundary = null;
            String[] parts = contentType.split(";");
            for (String part : parts) {
                part = part.trim();
                if (part.startsWith("boundary=")) {
                    boundary = part.substring(9);
                    if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                        boundary = boundary.substring(1, boundary.length() - 1);
                    }
                    break;
                }
            }
            if (boundary == null) { sendJson(ex, 200, M("success", false, "message", "无法解析")); return; }

            InputStream is = ex.getRequestBody();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            byte[] body = bos.toByteArray();

            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
            int fileStart = -1;
            int fileEnd = body.length;

            // 找文件开始位置（空行之后）
            for (int i = 0; i < body.length - 4; i++) {
                if (fileStart < 0 && body[i] == '\r' && body[i+1] == '\n' && body[i+2] == '\r' && body[i+3] == '\n') {
                    fileStart = i + 4;
                }
                // 找boundary结束位置
                if (fileStart >= 0 && i + boundaryBytes.length < body.length) {
                    boolean match = true;
                    for (int j = 0; j < boundaryBytes.length; j++) {
                        if (body[i+j] != boundaryBytes[j]) { match = false; break; }
                    }
                    if (match) {
                        fileEnd = i;
                        if (fileEnd >= 2 && body[fileEnd-1] == '\n' && body[fileEnd-2] == '\r') {
                            fileEnd -= 2;
                        }
                        break;
                    }
                }
            }

            if (fileStart < 0) { sendJson(ex, 200, M("success", false, "message", "无法解析文件")); return; }
            if (fileEnd <= fileStart) fileEnd = body.length;

            // 去除尾部多余字节
            while (fileEnd > fileStart && (body[fileEnd-1] == '\r' || body[fileEnd-1] == '\n' || body[fileEnd-1] == '-')) {
                fileEnd--;
            }
            if (fileEnd <= fileStart) { sendJson(ex, 200, M("success", false, "message", "文件数据为空")); return; }

            String headerPart = new String(body, 0, Math.min(fileStart, 500), StandardCharsets.UTF_8);
            String ext = ".jpg";
            if (headerPart.contains("image/png")) ext = ".png";
            else if (headerPart.contains("image/gif")) ext = ".gif";

            byte[] fileData = Arrays.copyOfRange(body, fileStart, fileEnd);

            // 保存到 plugins/WebBridge/avatars/
            File avatarsDir = new File(plugin.getDataFolder(), "avatars");
            if (!avatarsDir.exists()) avatarsDir.mkdirs();
            String fn = "avatar_" + auth.get("uuid") + ext;
            File avatarFile = new File(avatarsDir, fn);
            java.nio.file.Files.write(avatarFile.toPath(), fileData);

            String url = "/uploads/" + fn + "?t=" + System.currentTimeMillis();
            db.update("UPDATE mc_accounts SET avatar_url='" + url + "' WHERE uuid='" + esc((String)auth.get("uuid")) + "'");
            sendJson(ex, 200, M("success", true, "url", url));
        } catch (Exception e) {
            plugin.getLogger().warning("头像上传失败: " + e.getMessage());
            sendJson(ex, 200, M("success", false, "message", "上传失败: " + e.getMessage()));
        }
    }

    // ====== 聊天 ======
    private void chatSend(HttpExchange ex) throws IOException {
        Map<String, Object> auth = validate(ex);
        if (auth == null || Boolean.TRUE.equals(auth.get("is_banned"))) {
            sendJson(ex, auth == null ? 401 : 403, M("success", false, "message", auth == null ? "请登录" : "已被封禁")); return;
        }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String msg = p.get("message");
        if (msg == null || msg.trim().isEmpty()) return;
        String displayName = (String) auth.get("display_name");
        String username = (String) auth.get("username");
        String name = (displayName != null && !displayName.isEmpty()) ? displayName : (username != null && !username.isEmpty()) ? username : "游客";
        chat.sendWebMessageToGame(name, msg);
        sendJson(ex, 200, M("success", true));
    }

    private void sendPM(HttpExchange ex) throws IOException {
        Map<String, Object> auth = validate(ex);
        if (auth == null) { sendJson(ex, 401, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String targetName = p.get("target");
        String msg = p.get("message");
        if (targetName == null || msg == null || msg.trim().isEmpty()) { sendJson(ex, 200, M("success", false)); return; }
        String displayName = (String) auth.get("display_name");
        String username = (String) auth.get("username");
        String senderName = (displayName != null && !displayName.isEmpty()) ? displayName : (username != null && !username.isEmpty()) ? username : "游客";
        String senderUuid = (String) auth.get("uuid");
        if (senderUuid == null) senderUuid = "";
        String targetUuid = null;
        try {
            ResultSet rs = db.query("SELECT uuid FROM mc_accounts WHERE username='" + esc(targetName) + "' AND is_bound=TRUE");
            if (rs.next()) targetUuid = rs.getString("uuid");
        } catch (SQLException e) {}
        if (targetUuid == null) {
            org.bukkit.entity.Player tp = org.bukkit.Bukkit.getPlayer(targetName);
            if (tp != null) targetUuid = tp.getUniqueId().toString();
        }
        if (targetUuid != null) {
            chat.sendPMAndNotify(senderName, senderUuid, targetName, targetUuid, msg);
            sendJson(ex, 200, M("success", true));
        } else {
            sendJson(ex, 200, M("success", false, "message", "玩家不存在"));
        }
    }

    // ====== 经济API ======
    private void userEconomy(HttpExchange ex) throws IOException {
        Map<String, Object> auth = validate(ex);
        if (auth == null) { sendJson(ex, 401, M("success", false)); return; }
        Map<String, Object> data = new HashMap<String, Object>();
        String lookupUuid = (boolean)auth.get("isAdmin") ? "admin" : (String)auth.get("uuid");
        Map<String, Object> playerData = db.getPlayerData(lookupUuid);
        List<Map<String, String>> fields = economyConfig.getFields();
        for (Map<String, String> field : fields) {
            String key = field.get("key");
            Object val = null;
            if (!playerData.isEmpty()) val = playerData.get(key);
            if (val == null && !auth.isEmpty()) val = auth.get(key);
            if (val != null) data.put(key, val);
            else data.put(key, getDefaultByType(field.get("type")));
        }
        sendJson(ex, 200, M("success", true, "data", data));
    }

    // ====== 用户帖子 ======
    private void userPosts(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null) return;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            String uuid = (boolean)a.get("isAdmin") ? "%" : esc((String)a.get("uuid"));
            ResultSet rs = db.query("SELECT * FROM community_posts WHERE player_uuid LIKE '" + uuid + "' AND is_deleted=FALSE AND category!='review' ORDER BY created_at DESC LIMIT 50");
            while (rs.next()) {
                list.add(M("id", rs.getInt("id"), "content", rs.getString("content"), "category", rs.getString("category"), "image_url", rs.getString("image_url"), "created_at", formatDateTime(rs.getString("created_at"))));
            }
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list));
    }

    // ====== 评价API（支持分页） ======
    private void userReviews(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        String targetUuid = null;
        int page = 1;
        if (q != null) {
            if (q.contains("uuid=")) targetUuid = q.split("uuid=")[1].split("&")[0];
            if (q.contains("page=")) page = Integer.parseInt(q.split("page=")[1].split("&")[0]);
        }
        if (targetUuid == null) { sendJson(ex, 200, M("success", false)); return; }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        int total = 0;
        try {
            ResultSet rc = db.query("SELECT COUNT(*) FROM community_posts WHERE category='review' AND player_uuid='" + esc(targetUuid) + "' AND is_deleted=FALSE");
            if (rc.next()) total = rc.getInt(1);
            int offset = (page - 1) * 5;
            ResultSet rs = db.query("SELECT * FROM community_posts WHERE category='review' AND player_uuid='" + esc(targetUuid) + "' AND is_deleted=FALSE ORDER BY created_at DESC LIMIT 5 OFFSET " + offset);
            while (rs.next()) {
                list.add(M("id", rs.getInt("id"), "player_name", rs.getString("player_name"), "content", rs.getString("content"), "reply_to", rs.getString("reply_to"), "reply_content", rs.getString("reply_content"), "created_at", formatDateTime(rs.getString("created_at"))));
            }
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list, "total", total, "pages", (int)Math.ceil((double)total / 5)));
    }

    private void addUserReview(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || Boolean.TRUE.equals(a.get("is_banned"))) { sendJson(ex, a == null ? 401 : 403, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String targetUuid = p.get("uuid");
        String content = p.get("content");
        if (targetUuid == null || content == null || content.trim().isEmpty()) { sendJson(ex, 200, M("success", false)); return; }
        String displayName = (String) a.get("display_name");
        String username = (String) a.get("username");
        String name = (displayName != null && !displayName.isEmpty()) ? displayName : (username != null && !username.isEmpty()) ? username : "游客";
        try {
            PreparedStatement ps = db.prepare("INSERT INTO community_posts (player_uuid,player_name,content,category,skin_head_url) VALUES (?,?,?,'review',?)");
            ps.setString(1, targetUuid); ps.setString(2, name); ps.setString(3, content); ps.setString(4, "/default_avatar.png");
            ps.executeUpdate();
            sendJson(ex, 200, M("success", true));
        } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
    }

    private void replyToReview(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null) { sendJson(ex, 401, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String replyUser = (String) a.get("username");
        if (replyUser == null) replyUser = "";
        try {
            db.update("UPDATE community_posts SET reply_content='" + esc(p.get("reply")) + "', reply_to='" + esc(replyUser) + "' WHERE id=" + p.get("id"));
            sendJson(ex, 200, M("success", true));
        } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
    }

    private void togglePrivacy(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || (boolean)a.get("isAdmin")) { sendJson(ex, 401, M("success", false)); return; }
        try {
            ResultSet rs = db.query("SELECT privacy_public FROM mc_accounts WHERE uuid='" + esc((String)a.get("uuid")) + "'");
            boolean current = rs.next() && rs.getBoolean("privacy_public");
            db.update("UPDATE mc_accounts SET privacy_public=" + (!current) + " WHERE uuid='" + esc((String)a.get("uuid")) + "'");
            sendJson(ex, 200, M("success", true, "privacy_public", !current));
        } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
    }

    // ====== 服务器信息 ======
    private void serverInfo(HttpExchange ex) throws IOException {
        sendJson(ex, 200, M("success", true, "data", M("online_players", chat.getOnlineCount(), "player_list", chat.getOnlinePlayers(), "total_users", db.getTotalUsers(), "server_ip", plugin.getConfig().getString("server.ip", "localhost"))));
    }

    private void serverMOTD(HttpExchange ex) throws IOException {
        sendJson(ex, 200, M("success", true, "motd", plugin.getMOTD().replace("&", "§")));
    }

    private void serverResources(HttpExchange ex) throws IOException {
        sendJson(ex, 200, M("success", true, "data", M("cpu", String.format("%.1f", plugin.getCPUUsage()), "memory_total", plugin.getTotalMemory(), "memory_used", plugin.getUsedMemory(), "memory_free", plugin.getFreeMemory())));
    }

    private void adminOnline(HttpExchange ex) throws IOException {
        sendJson(ex, 200, M("success", true, "data", chat.getOnlineAdmins(onlineWebAdmins)));
    }

    private void chatMessages(HttpExchange ex) throws IOException {
        sendJson(ex, 200, M("success", true, "data", chat.getRecentMessages(0)));
    }

    private void announcements(HttpExchange ex) throws IOException {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSet rs = db.query("SELECT * FROM announcements WHERE is_active=TRUE ORDER BY created_at DESC LIMIT 10");
            while (rs.next()) list.add(M("id", rs.getInt("id"), "title", rs.getString("title"), "content", rs.getString("content"), "created_at", formatDateTime(rs.getString("created_at"))));
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list));
    }

    // ====== 社区帖子 ======
    private void communityPosts(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        String cat = "all";
        String search = "";
        if (q != null) {
            if (q.contains("category=")) cat = q.split("category=")[1].split("&")[0];
            if (q.contains("search=")) search = q.split("search=")[1].split("&")[0];
        }
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, Object> a = validate(ex);
            if (a == null || Boolean.TRUE.equals(a.get("is_banned"))) { sendJson(ex, a == null ? 401 : 403, M("success", false)); return; }
            Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
            String content = p.get("content");
            if (content == null || content.trim().isEmpty()) { sendJson(ex, 200, M("success", false, "message", "内容不能为空")); return; }
            String catVal = p.get("category"); if (catVal == null) catVal = "chat";
            String imgVal = p.get("image_url"); if (imgVal == null) imgVal = "";
            String avatarUrl = (String) a.get("avatar_url"); if (avatarUrl == null) avatarUrl = "/default_avatar.png";
            try {
                PreparedStatement ps = db.prepare("INSERT INTO community_posts (player_uuid,player_name,content,category,image_url,skin_head_url) VALUES (?,?,?,?,?,?)");
                ps.setString(1, (String)a.get("uuid")); ps.setString(2, (String)a.get("username")); ps.setString(3, content); ps.setString(4, catVal); ps.setString(5, imgVal); ps.setString(6, avatarUrl);
                ps.executeUpdate(); sendJson(ex, 200, M("success", true));
            } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
        } else {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            try {
                String w = "WHERE is_deleted=FALSE AND category!='review'";
                if (!"all".equals(cat)) w += " AND category='" + esc(cat) + "'";
                if (!search.isEmpty()) w += " AND (player_name LIKE '%" + esc(search) + "%' OR content LIKE '%" + esc(search) + "%')";
                ResultSet rs = db.query("SELECT * FROM community_posts " + w + " ORDER BY is_pinned DESC, likes DESC, created_at DESC");
                while (rs.next()) {
                    int pid = rs.getInt("id");
                    int rc = 0;
                    try { ResultSet rcc = db.query("SELECT COUNT(*) FROM post_replies WHERE post_id=" + pid); if (rcc.next()) rc = rcc.getInt(1); } catch (SQLException e) {}
                    list.add(M("id", pid, "player_uuid", rs.getString("player_uuid"), "player_name", rs.getString("player_name"), "content", rs.getString("content"), "image_url", rs.getString("image_url"), "category", rs.getString("category"), "skin_head_url", rs.getString("skin_head_url"), "likes", rs.getInt("likes"), "dislikes", rs.getInt("dislikes"), "is_pinned", rs.getBoolean("is_pinned"), "reply_count", rc, "created_at", formatDateTime(rs.getString("created_at"))));
                }
            } catch (SQLException e) {}
            sendJson(ex, 200, M("success", true, "data", list));
        }
    }

    private void communityReact(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || Boolean.TRUE.equals(a.get("is_banned"))) { sendJson(ex, a == null ? 401 : 403, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            String puuid = (String) a.get("uuid"); if (puuid == null) puuid = (String) a.get("username");
            ResultSet ck = db.query("SELECT * FROM post_reactions WHERE post_id=" + p.get("post_id") + " AND player_uuid='" + esc(puuid) + "'");
            if (!ck.next()) {
                db.update("INSERT INTO post_reactions (post_id,player_uuid,reaction_type) VALUES (" + p.get("post_id") + ",'" + esc(puuid) + "','" + p.get("type") + "')");
                String col = "like".equals(p.get("type")) ? "likes" : "dislikes";
                db.update("UPDATE community_posts SET " + col + "=" + col + "+1 WHERE id=" + p.get("post_id"));
                sendJson(ex, 200, M("success", true));
            } else { sendJson(ex, 200, M("success", false, "message", "已经操作过了")); }
        } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
    }

    private void communityUpload(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || Boolean.TRUE.equals(a.get("is_banned"))) { sendJson(ex, a == null ? 401 : 403, M("success", false)); return; }
        try {
            String ct = ex.getRequestHeaders().getFirst("Content-Type");
            if (ct == null || !ct.contains("multipart/form-data")) { sendJson(ex, 200, M("success", false)); return; }
            String boundary = null;
            for (String pt : ct.split(";")) { pt = pt.trim(); if (pt.startsWith("boundary=")) { boundary = pt.substring(9); if (boundary.startsWith("\"") && boundary.endsWith("\"")) boundary = boundary.substring(1, boundary.length() - 1); break; } }
            if (boundary == null) { sendJson(ex, 200, M("success", false)); return; }
            InputStream is = ex.getRequestBody(); ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = is.read(b)) != -1) bos.write(b, 0, n);
            byte[] body = bos.toByteArray();
            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
            int fs = -1, fe = body.length;
            for (int i = 0; i < body.length - 4; i++) {
                if (fs < 0 && body[i] == '\r' && body[i+1] == '\n' && body[i+2] == '\r' && body[i+3] == '\n') { fs = i + 4; }
                if (fs >= 0 && i + boundaryBytes.length < body.length) {
                    boolean match = true;
                    for (int j = 0; j < boundaryBytes.length; j++) { if (body[i+j] != boundaryBytes[j]) { match = false; break; } }
                    if (match) { fe = i; if (fe >= 2 && body[fe-1] == '\n' && body[fe-2] == '\r') fe -= 2; break; }
                }
            }
            if (fs < 0) { sendJson(ex, 200, M("success", false)); return; }
            if (fe <= fs) fe = body.length;
            while (fe > fs && (body[fe-1] == '\r' || body[fe-1] == '\n' || body[fe-1] == '-')) fe--;
            if (fe <= fs) { sendJson(ex, 200, M("success", false)); return; }
            String mid = new String(body, 0, Math.min(fs, 500), StandardCharsets.ISO_8859_1);
            String ext = mid.contains("image/png") ? ".png" : mid.contains("image/gif") ? ".gif" : ".jpg";
            byte[] fd = Arrays.copyOfRange(body, fs, fe);
            File dir = new File(plugin.getDataFolder(), "uploads"); dir.mkdirs();
            String fn = "post_" + System.currentTimeMillis() + ext;
            java.nio.file.Files.write(new File(dir, fn).toPath(), fd);
            sendJson(ex, 200, M("success", true, "url", "/uploads/" + fn));
        } catch (Exception e) { sendJson(ex, 200, M("success", false)); }
    }

    private void communityReplies(HttpExchange ex) throws IOException {
        String q = ex.getRequestURI().getQuery();
        if (q == null || !q.contains("post_id=")) { sendJson(ex, 200, M("success", false)); return; }
        int postId = Integer.parseInt(q.split("post_id=")[1].split("&")[0]);
        int page = 1;
        if (q.contains("page=")) page = Integer.parseInt(q.split("page=")[1].split("&")[0]);
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, Object> a = validate(ex);
            if (a == null || Boolean.TRUE.equals(a.get("is_banned"))) { sendJson(ex, a == null ? 401 : 403, M("success", false)); return; }
            Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
            String content = p.get("content");
            if (content == null || content.trim().isEmpty()) { sendJson(ex, 200, M("success", false)); return; }
            try {
                PreparedStatement ps = db.prepare("INSERT INTO post_replies (post_id,player_uuid,player_name,content) VALUES (?,?,?,?)");
                ps.setInt(1, postId); ps.setString(2, (String)a.get("uuid")); ps.setString(3, (String)a.get("username")); ps.setString(4, content);
                ps.executeUpdate(); sendJson(ex, 200, M("success", true));
            } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
        } else {
            List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
            try {
                int offset = (page - 1) * 5;
                ResultSet rs = db.query("SELECT * FROM post_replies WHERE post_id=" + postId + " ORDER BY likes DESC, created_at ASC LIMIT 5 OFFSET " + offset);
                while (rs.next()) list.add(M("id", rs.getInt("id"), "player_uuid", rs.getString("player_uuid"), "player_name", rs.getString("player_name"), "content", rs.getString("content"), "likes", rs.getInt("likes"), "created_at", formatDateTime(rs.getString("created_at"))));
            } catch (SQLException e) {}
            int total = 0;
            try { ResultSet rc = db.query("SELECT COUNT(*) FROM post_replies WHERE post_id=" + postId); if (rc.next()) total = rc.getInt(1); } catch (SQLException e) {}
            sendJson(ex, 200, M("success", true, "data", list, "total", total, "pages", (int)Math.ceil((double)total / 5)));
        }
    }

    private void communityReplyReact(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || Boolean.TRUE.equals(a.get("is_banned"))) { sendJson(ex, a == null ? 401 : 403, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            String puuid = (String) a.get("uuid"); if (puuid == null) puuid = (String) a.get("username");
            ResultSet ck = db.query("SELECT * FROM reply_reactions WHERE reply_id=" + p.get("reply_id") + " AND player_uuid='" + esc(puuid) + "'");
            if (!ck.next()) {
                db.update("INSERT INTO reply_reactions (reply_id,player_uuid,reaction_type) VALUES (" + p.get("reply_id") + ",'" + esc(puuid) + "','" + p.get("type") + "')");
                db.update("UPDATE post_replies SET likes=likes+1 WHERE id=" + p.get("reply_id"));
                sendJson(ex, 200, M("success", true));
            } else { sendJson(ex, 200, M("success", false)); }
        } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
    }

    // ====== 建议 ======
    private void suggestions(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || Boolean.TRUE.equals(a.get("is_banned"))) { sendJson(ex, a == null ? 401 : 403, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String content = p.get("content");
        if (content == null || content.trim().isEmpty()) { sendJson(ex, 200, M("success", false, "message", "内容不能为空")); return; }
        String displayName = (String) a.get("display_name");
        String username = (String) a.get("username");
        String name = (displayName != null && !displayName.isEmpty()) ? displayName : (username != null && !username.isEmpty()) ? username : "访客";
        String uuid = (String) a.get("uuid"); if (uuid == null) uuid = "";
        try {
            PreparedStatement ps = db.prepare("INSERT INTO suggestions (player_uuid,player_name,content) VALUES (?,?,?)");
            ps.setString(1, uuid); ps.setString(2, name); ps.setString(3, content);
            ps.executeUpdate(); sendJson(ex, 200, M("success", true));
        } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
    }

    private void replySuggestion(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) { sendJson(ex, 403, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try { db.update("UPDATE suggestions SET admin_reply='" + esc(p.get("reply")) + "', is_read=TRUE WHERE id=" + p.get("id")); } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true));
    }

    private void mySuggestions(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || (boolean)a.get("isAdmin")) { sendJson(ex, 401, M("success", false)); return; }
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSet rs = db.query("SELECT * FROM suggestions WHERE player_uuid='" + esc((String)a.get("uuid")) + "' ORDER BY created_at DESC LIMIT 50");
            while (rs.next()) list.add(M("id", rs.getInt("id"), "player_name", rs.getString("player_name"), "content", rs.getString("content"), "admin_reply", rs.getString("admin_reply"), "is_read", rs.getBoolean("is_read"), "created_at", formatDateTime(rs.getString("created_at"))));
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list));
    }

    // ====== 通知API ======
    private void getNotifications(HttpExchange ex) throws IOException {
        Map<String, Object> auth = validate(ex);
        if (auth == null) { sendJson(ex, 401, M("success", false)); return; }
        String uuid = (boolean)auth.get("isAdmin") ? (String)auth.get("username") : (String)auth.get("uuid");
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSet rs = db.query("SELECT * FROM notifications WHERE recipient_uuid='" + esc(uuid) + "' ORDER BY created_at DESC LIMIT 30");
            while (rs.next()) list.add(M("id", rs.getInt("id"), "sender_name", rs.getString("sender_name"), "message", rs.getString("message"), "source", rs.getString("source"), "is_read", rs.getBoolean("is_read"), "is_replied", rs.getBoolean("is_replied"), "reply_content", rs.getString("reply_content"), "created_at", formatDateTime(rs.getString("created_at"))));
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list));
    }

    private void markNotificationRead(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex); if (a == null) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try { db.update("UPDATE notifications SET is_read=TRUE WHERE id=" + p.get("id")); } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true));
    }

    private void replyNotification(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex); if (a == null) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            db.update("UPDATE notifications SET is_replied=TRUE, reply_content='" + esc(p.get("reply")) + "', replied_at=CURRENT_TIMESTAMP WHERE id=" + p.get("id"));
            ResultSet rs = db.query("SELECT sender_name FROM notifications WHERE id=" + p.get("id"));
            if (rs.next()) {
                String targetName = rs.getString("sender_name");
                String myName = (String) a.get("display_name"); if (myName == null) myName = (String) a.get("username"); if (myName == null) myName = "游客";
                org.bukkit.entity.Player tp = org.bukkit.Bukkit.getPlayer(targetName);
                if (tp != null) tp.sendMessage("§d[私信回复] §e" + myName + "§7: §f" + p.get("reply"));
            }
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true));
    }

    // ====== 管理员API ======
    private void adminAnnouncement(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        if ("POST".equals(ex.getRequestMethod())) {
            Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
            try {
                db.update("INSERT INTO announcements (title,content,created_by) VALUES ('" + esc(p.get("title")) + "','" + esc(p.get("content")) + "','admin')");
                sendJson(ex, 200, M("success", true));
            } catch (SQLException e) { sendJson(ex, 200, M("success", false)); }
        } else if ("DELETE".equals(ex.getRequestMethod())) {
            Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
            try { db.update("DELETE FROM announcements WHERE id=" + p.get("id")); } catch (SQLException e) {}
            sendJson(ex, 200, M("success", true));
        }
    }

    private void adminUsers(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSet rs = db.query("SELECT * FROM mc_accounts ORDER BY id DESC");
            while (rs.next()) {
                list.add(M("id", rs.getInt("id"), "username", rs.getString("username"), "display_name", rs.getString("display_name"), "uuid", rs.getString("uuid"), "email", rs.getString("email"), "is_bound", rs.getBoolean("is_bound"), "is_banned", rs.getBoolean("is_banned"), "ban_reason", rs.getString("ban_reason"), "privacy_public", rs.getBoolean("privacy_public"), "avatar_url", rs.getString("avatar_url"), "bio", rs.getString("bio"), "reg_date", formatDateTime(rs.getString("reg_date")), "last_login", formatDateTime(rs.getString("last_login"))));
            }
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list, "total", list.size()));
    }

    private void adminAdmins(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSet rs = db.query("SELECT * FROM admins ORDER BY level, username");
            while (rs.next()) list.add(M("id", rs.getInt("id"), "username", rs.getString("username"), "display_name", rs.getString("display_name"), "level", rs.getString("level"), "created_at", formatDateTime(rs.getString("created_at"))));
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list));
    }

    private void adminAddAdmin(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !Boolean.TRUE.equals(a.get("isSuperAdmin"))) { sendJson(ex, 403, M("success", false, "message", "仅超级管理员可操作")); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            db.update("INSERT INTO admins (username,password,level) VALUES ('" + esc(p.get("username")) + "','" + esc(p.get("password")) + "','normal')");
            sendJson(ex, 200, M("success", true));
        } catch (SQLException e) { sendJson(ex, 200, M("success", false, "message", "用户名已存在")); }
    }

    private void adminRemoveAdmin(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !Boolean.TRUE.equals(a.get("isSuperAdmin"))) { sendJson(ex, 403, M("success", false, "message", "仅超级管理员可操作")); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try { db.update("DELETE FROM admins WHERE username='" + esc(p.get("username")) + "' AND level!='super'"); } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true));
    }

    private void adminSuggestions(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSet rs = db.query("SELECT * FROM suggestions ORDER BY created_at DESC LIMIT 100");
            while (rs.next()) list.add(M("id", rs.getInt("id"), "player_name", rs.getString("player_name"), "content", rs.getString("content"), "admin_reply", rs.getString("admin_reply"), "is_read", rs.getBoolean("is_read"), "created_at", formatDateTime(rs.getString("created_at"))));
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list));
    }

    private void adminDeletePost(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try { db.update("UPDATE community_posts SET is_deleted=TRUE WHERE id=" + p.get("id")); } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true));
    }

    private void adminPinPost(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try { db.update("UPDATE community_posts SET is_pinned=NOT is_pinned WHERE id=" + p.get("id")); } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true));
    }

    private void adminBan(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) { sendJson(ex, 403, M("success", false)); return; }
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        final String uuid = p.get("uuid");

        // 检查目标是否是管理员
        try {
            ResultSet rs = db.query("SELECT level FROM admins WHERE username=(SELECT username FROM mc_accounts WHERE uuid='" + esc(uuid) + "')");
            if (rs.next()) {
                String targetLevel = rs.getString("level");
                // 超级管理员不能被任何人封禁
                if ("super".equals(targetLevel)) {
                    sendJson(ex, 200, M("success", false, "message", "不能封禁超级管理员"));
                    return;
                }
                // 普通管理员只能被超级管理员封禁
                if (!Boolean.TRUE.equals(a.get("isSuperAdmin"))) {
                    sendJson(ex, 200, M("success", false, "message", "只有超级管理员才能封禁管理员"));
                    return;
                }
            }
        } catch (SQLException e) {}

        String reason = p.containsKey("reason") ? p.get("reason") : "违规行为";
        final String bannedBy = (String)a.get("username");
        db.banPlayer(uuid, reason, bannedBy);
        org.bukkit.Bukkit.getScheduler().runTask(plugin, new Runnable() {
            public void run() {
                org.bukkit.OfflinePlayer target = org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(uuid));
                if (target.isOnline()) ((org.bukkit.entity.Player)target).kickPlayer("§c您已被封禁！\n§7原因: " + reason);
            }
        });
        sendJson(ex, 200, M("success", true));
    }

    private void adminUnban(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        db.unbanPlayer(p.get("uuid"));
        sendJson(ex, 200, M("success", true));
    }

    private void adminBans(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        try {
            ResultSet rs = db.query("SELECT * FROM bans WHERE is_active=TRUE ORDER BY banned_at DESC");
            while (rs.next()) list.add(M("id", rs.getInt("id"), "player_name", rs.getString("player_name"), "reason", rs.getString("reason"), "banned_by", rs.getString("banned_by"), "banned_at", formatDateTime(rs.getString("banned_at"))));
        } catch (SQLException e) {}
        sendJson(ex, 200, M("success", true, "data", list));
    }

    private void adminUpdateAbout(HttpExchange ex) throws IOException {
        Map<String, Object> a = validate(ex);
        if (a == null || !(boolean)a.get("isAdmin")) return;
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        plugin.getConfig().set("web.about_content", p.get("content"));
        plugin.saveConfig();
        sendJson(ex, 200, M("success", true));
    }

    private void adminGetAbout(HttpExchange ex) throws IOException {
        sendJson(ex, 200, M("success", true, "content", plugin.getConfig().getString("web.about_content", "欢迎来到服务器！")));
    }


// ====== HTML页面构建 ======
private String buildPage() {
    String title = plugin.getConfig().getString("web.title", "MC服务器");
    String icon = plugin.getConfig().getString("web.icon", "⛏");
    String defaultAvatar = plugin.getConfig().getString("web.default_avatar", "/default_avatar.png");
    boolean mailOn = mailSender.isEnabled();
    StringBuilder sb = new StringBuilder();

    sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>").append(title).append("</title><style>");
    sb.append("*{margin:0;padding:0;box-sizing:border-box}body{font-family:'Segoe UI',Arial,sans-serif;min-height:100vh;transition:all .3s}");
    sb.append("@keyframes float{0%,100%{transform:translateY(0)}50%{transform:translateY(-8px)}}");
    sb.append("@keyframes glow{0%,100%{box-shadow:0 0 15px var(--glow)}50%{box-shadow:0 0 35px var(--glow)}}");
    sb.append("@keyframes slideUp{from{opacity:0;transform:translateY(20px)}to{opacity:1;transform:translateY(0)}}");
    sb.append("@keyframes fadeIn{from{opacity:0}to{opacity:1}}");
    sb.append("@keyframes modalIn{from{opacity:0;transform:scale(.9)}to{opacity:1;transform:scale(1)}}");
    sb.append("@keyframes rainbowBorder{0%{border-color:#ff6b6b}17%{border-color:#ffd93d}33%{border-color:#6bcb77}50%{border-color:#4d96ff}67%{border-color:#a78bfa}83%{border-color:#f472b6}100%{border-color:#ff6b6b}}");
    sb.append("@keyframes bellRing{0%,100%{transform:rotate(0)}10%{transform:rotate(15deg)}20%{transform:rotate(-15deg)}30%{transform:rotate(10deg)}40%{transform:rotate(-10deg)}50%{transform:rotate(0)}}");

    // 主题1：黑蓝
    sb.append("body.t2{--bg:#0d1117;--card:#161b22;--border:#30363d;--text:#c9d1d9;--muted:#8b949e;--blue:#58a6ff;--green:#238636;--red:#da3633;--hover:#21262d;--glow:rgba(88,166,255,.4);--btn-bg:linear-gradient(135deg,#238636,#2ea043);background:var(--bg);color:var(--text)}");
    sb.append(".t2 .navbar{background:rgba(22,27,34,.95);border-bottom:2px solid var(--blue);backdrop-filter:blur(12px);animation:rainbowBorder 8s linear infinite}.t2 .brand{color:var(--blue)}");
    sb.append(".t2 .card{background:var(--card);border:1px solid var(--border);animation:slideUp .4s ease}.t2 .card:hover{border-color:var(--blue)}");
    sb.append(".t2 button{background:var(--btn-bg);color:#fff}.t2 button:hover{transform:translateY(-2px)}");
    sb.append(".t2 input,.t2 textarea,.t2 select{background:#0d1117;border:1px solid var(--border);color:var(--text)}");
    sb.append(".t2 .tab{background:var(--hover)}.t2 .tab.active{background:linear-gradient(135deg,#58a6ff,#8b5cf6);color:#fff}");
    sb.append(".t2 .modal{background:rgba(22,27,34,.98);border:2px solid var(--blue);animation:rainbowBorder 8s linear infinite;backdrop-filter:blur(16px)}");
    sb.append(".t2 .motd-bar{background:linear-gradient(135deg,#1a2a4a,#1e3a5c);border:2px solid var(--blue);color:#58a6ff;animation:glow 3s ease-in-out infinite}");

    // 主题2：白黄
    sb.append("body.t1{--bg:#f5f5f5;--card:#fff;--border:#d0d7de;--text:#24292f;--muted:#656d76;--blue:#f59e0b;--green:#1a7f37;--red:#cf222e;--hover:#f3f4f6;--glow:rgba(245,158,11,.4);--btn-bg:linear-gradient(135deg,#333,#555);background:var(--bg);color:var(--text)}");
    sb.append(".t1 .navbar{background:rgba(255,255,255,.95);border-bottom:2px solid var(--blue);animation:rainbowBorder 8s linear infinite}.t1 .brand{color:#b8860b}");
    sb.append(".t1 .card{background:var(--card);border:1px solid var(--border)}.t1 button{background:var(--btn-bg);color:var(--blue)}");
    sb.append(".t1 .tab.active{background:linear-gradient(135deg,#f59e0b,#fbbf24);color:#333}");
    sb.append(".t1 .modal{background:#fff;border:2px solid var(--blue)}");
    sb.append(".t1 .motd-bar{background:linear-gradient(135deg,#fff8e1,#fff3cd);border:2px solid var(--blue);color:#b8860b;animation:glow 3s ease-in-out infinite}");

    // 主题3：粉白
    sb.append("body.t3{--bg:#fff0f5;--card:#fff;--border:#fbcfe8;--text:#1a1a2e;--muted:#6b21a8;--blue:#ec4899;--green:#be185d;--red:#e11d48;--hover:#fce7f3;--glow:rgba(236,72,153,.5);--btn-bg:linear-gradient(135deg,#ec4899,#a855f7);background:var(--bg);color:var(--text)}");
    sb.append(".t3 .navbar{background:rgba(255,255,255,.95);border-bottom:2px solid var(--blue);animation:rainbowBorder 8s linear infinite}.t3 .brand{color:#a855f7}");
    sb.append(".t3 .card{background:var(--card);border:1px solid var(--border)}.t3 button{background:var(--btn-bg);color:#fff}");
    sb.append(".t3 .tab.active{background:linear-gradient(135deg,#ec4899,#a855f7);color:#fff}");
    sb.append(".t3 .modal{background:#fff;border:2px solid #ec4899;animation:rainbowBorder 8s linear infinite}");
    sb.append(".t3 .motd-bar{background:linear-gradient(135deg,#fce7f3,#ede9fe);border:2px solid #a855f7;color:#6b21a8;animation:glow 3s ease-in-out infinite}");

    // 通用样式
    sb.append(".navbar{padding:12px 20px;display:flex;justify-content:space-between;align-items:center;position:sticky;top:0;z-index:1000}.brand{font-weight:700;font-size:20px;animation:float 4s ease-in-out infinite}");
    sb.append(".nav-links{display:flex;gap:8px;align-items:center}button{cursor:pointer;padding:9px 16px;border:none;border-radius:8px;font-size:13px;margin:2px;font-weight:600;transition:all .3s}button:active{transform:scale(.94)}");
    sb.append(".container{max-width:1200px;margin:0 auto;padding:20px 16px;display:flex;gap:20px}.sidebar{width:300px;flex-shrink:0}.main{flex:1}");
    sb.append(".card{border-radius:12px;padding:18px;margin-bottom:14px;transition:all .35s}.card h3{margin-bottom:12px;font-size:16px}");
    sb.append("input,textarea,select{width:100%;padding:10px;margin-bottom:10px;border-radius:8px;font-size:13px;transition:all .3s}");
    sb.append(".tabs{display:flex;gap:6px;margin-bottom:14px;flex-wrap:wrap}.tab{padding:8px 16px;border-radius:8px;cursor:pointer;font-size:13px;transition:all .3s}.tab:hover{transform:translateY(-2px)}");
    sb.append(".modal-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.75);z-index:2000;justify-content:center;align-items:center;backdrop-filter:blur(4px)}");
    sb.append(".modal-overlay.active{display:flex}.modal{border-radius:16px;padding:28px;max-width:520px;width:94%;max-height:85vh;overflow-y:auto;position:relative;animation:modalIn .35s ease}.modal.full{max-width:900px}");
    sb.append(".modal-close{position:absolute;top:12px;right:16px;background:none!important;border:none;font-size:22px;cursor:pointer;padding:4px 8px;transition:all .2s}.modal-close:hover{color:var(--red);transform:rotate(90deg)}");
    sb.append(".post{display:flex;gap:12px;padding:14px 0;border-bottom:1px solid rgba(128,128,128,.15);animation:slideUp .4s ease}.post img.avatar{width:42px;height:42px;border-radius:8px;cursor:pointer;object-fit:cover;transition:all .3s}.post img.avatar:hover{transform:scale(1.15)}");
    sb.append(".admin-badge{background:linear-gradient(135deg,#da3633,#f85149);color:#fff;font-size:10px;padding:2px 8px;border-radius:10px;margin-left:6px;animation:pulse 2s ease-in-out infinite}");
    sb.append(".super-badge{background:linear-gradient(135deg,#ffc107,#f59e0b);color:#333;font-size:10px;padding:2px 8px;border-radius:10px;margin-left:4px}");
    sb.append(".banned-badge{background:#555;color:var(--red);font-size:10px;padding:2px 8px;border-radius:10px;margin-left:4px;text-decoration:line-through}");
    sb.append(".img-preview{cursor:pointer;max-width:220px;border-radius:10px;transition:all .3s}.img-preview:hover{transform:scale(1.03)}");
    sb.append(".img-fullscreen{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.95);z-index:3000;justify-content:center;align-items:center}");
    sb.append(".img-fullscreen.active{display:flex}.img-fullscreen img{max-width:95%;max-height:95%;border-radius:12px}");
    sb.append(".avatar-upload{position:relative;display:inline-block;cursor:pointer;transition:all .3s}.avatar-upload:hover{transform:scale(1.05)}");
    sb.append(".avatar-upload img{width:85px;height:85px;border-radius:50%;border:4px solid var(--blue);object-fit:cover;animation:glow 3s ease-in-out infinite}");
    sb.append(".avatar-upload span{position:absolute;bottom:2px;right:2px;background:linear-gradient(135deg,#58a6ff,#8b5cf6);color:#fff;border-radius:50%;width:26px;height:26px;text-align:center;line-height:26px;font-size:14px}");
    sb.append(".reaction-btn{display:inline-flex;align-items:center;gap:5px;padding:7px 14px;border-radius:20px;font-size:12px;cursor:pointer;transition:all .3s;background:rgba(128,128,128,.1)}.reaction-btn:hover{background:rgba(88,166,255,.25);transform:scale(1.08)}");
    sb.append(".carousel{position:relative;overflow:hidden;border-radius:14px;margin-bottom:14px;min-height:100px}");
    sb.append(".carousel-slide{display:none;padding:18px 22px;border-radius:14px;animation:fadeIn .4s ease}.carousel-slide.active{display:block}");
    sb.append(".carousel-dots{display:flex;justify-content:center;gap:10px;margin-top:8px}.carousel-dot{width:10px;height:10px;border-radius:50%;background:var(--muted);cursor:pointer;transition:all .3s}.carousel-dot.active{background:var(--blue);transform:scale(1.4)}");
    sb.append(".crop-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.92);z-index:4000;justify-content:center;align-items:center;flex-direction:column}");
    sb.append(".crop-overlay.active{display:flex}.crop-container{position:relative;display:inline-block;max-width:90vw;max-height:60vh;overflow:hidden;border-radius:12px}");
    sb.append(".crop-container img{display:block;max-width:100%;max-height:60vh}.crop-box{position:absolute;border:2px dashed #fff;cursor:move;box-shadow:0 0 0 9999px rgba(0,0,0,.5)}");
    sb.append(".crop-resize-handle{position:absolute;width:14px;height:14px;background:var(--blue);border:2px solid #fff;border-radius:2px;z-index:10}.crop-resize-handle.se{bottom:-7px;right:-7px;cursor:nwse-resize}");
    sb.append(".crop-preview{text-align:center;margin-bottom:16px}.crop-preview img{width:120px;height:120px;border-radius:50%;object-fit:cover;border:3px solid var(--blue)}");
    sb.append(".pagination{display:flex;justify-content:center;gap:8px;margin-top:12px}.pagination span{padding:6px 12px;border-radius:6px;cursor:pointer;background:var(--hover);font-size:13px}.pagination span.active{background:var(--blue);color:#fff}");
    sb.append(".search-bar{display:flex;gap:8px;margin-bottom:12px}.search-bar input{flex:1;margin-bottom:0}");
    sb.append(".bell-btn{position:relative;background:none!important;border:none;font-size:20px;cursor:pointer;padding:4px 8px}.bell-btn:hover{animation:bellRing .6s ease}.bell-btn .badge{position:absolute;top:-2px;right:-2px;width:10px;height:10px;background:var(--red);border-radius:50%;border:2px solid var(--bg)}.bell-btn .badge.hidden{display:none}");
    sb.append(".notif-dropdown{display:none;position:absolute;top:48px;right:10px;width:360px;max-height:400px;overflow-y:auto;background:var(--card);border:2px solid var(--blue);border-radius:12px;z-index:1500;box-shadow:0 8px 30px rgba(0,0,0,.5)}.notif-dropdown.active{display:block}");
    sb.append(".notif-item{padding:12px 16px;border-bottom:1px solid var(--border);cursor:pointer}.notif-item:hover{background:var(--hover)}.notif-item.unread{border-left:3px solid var(--red)}.notif-item.replied{border-left:3px solid var(--green)}");
    sb.append(".toast{position:fixed;bottom:24px;right:24px;padding:14px 24px;border-radius:10px;color:#fff;z-index:3000;animation:slideUp .4s ease;font-weight:600}");
    sb.append(".toast.success{background:linear-gradient(135deg,#238636,#2ea043)}.toast.error{background:linear-gradient(135deg,#da3633,#f85149)}");
    sb.append(".logout-btn{background:linear-gradient(135deg,#da3633,#f85149)!important;font-size:11px;padding:5px 12px;margin-top:6px}");
    sb.append(".admin-online{display:flex;flex-wrap:wrap;gap:6px;margin-top:8px}.admin-dot{padding:5px 12px;border-radius:15px;font-size:11px;cursor:pointer;background:var(--hover)}");
    sb.append(".reply-box{border-left:3px solid var(--blue);padding:8px 12px;margin-top:8px;font-size:12px;background:var(--hover);border-radius:4px}");
    sb.append(".replies-container{margin-left:20px;border-left:2px solid var(--border);padding-left:16px}.reply-item{padding:8px 0}");
    sb.append("@media(max-width:768px){.container{flex-direction:column}.sidebar{width:100%}.notif-dropdown{width:300px}}");
    sb.append("</style></head><body class='t2'>");

    // 导航栏
    sb.append("<nav class='navbar'><span class='brand'>").append(icon).append(" ").append(title).append("</span><div class='nav-links' id='nl'>");
    sb.append("<button onclick='O(\"login\")'>登录</button><button onclick='O(\"register\")'>注册</button>");
    sb.append("<select onchange='TH(this.value)' style='width:auto;padding:8px;border-radius:8px'><option value='t2' selected>🌙黑蓝</option><option value='t1'>☀白黄</option><option value='t3'>🌸粉白</option></select>");
    sb.append("</div></nav>");

    // 图片全屏
    sb.append("<div class='img-fullscreen' id='imgFS' onclick='this.classList.remove(\"active\")'><img id='imgFSImg'></div>");

    // 裁剪遮罩
    sb.append("<div class='crop-overlay' id='cropOverlay'><div class='crop-preview' id='cropPreview'></div><div class='crop-container' id='cropContainer'><img id='cropImg'><div class='crop-box' id='cropBox'><div class='crop-resize-handle se' id='cropResize'></div></div></div><div class='crop-actions' style='margin-top:14px;display:flex;gap:12px'><button onclick='confirmCrop()'>确认裁剪</button><button style='background:var(--red)' onclick='cancelCrop()'>取消</button></div></div>");

    // 主布局
    sb.append("<div class='container'><div class='sidebar'>");
    sb.append("<div class='card' id='economyCard' style='display:none'><h3>💰 经济</h3>");sb.append(economyConfig.renderHTML());sb.append("</div>");
    sb.append("<div class='card'><h3>🛡 在线管理</h3><div class='admin-online' id='adminOnline'>加载中...</div></div>");
    sb.append("<div class='card'><h3>📊 状态</h3><p>在线:<span id='oc'>-</span></p><p>注册:<span id='tu'>-</span></p><p>IP:<span id='sip'>-</span></p></div>");
    sb.append("<div class='card'><h3>💻 资源</h3><p>CPU:<span id='cpuUsage'>-</span>%</p><p>内存:<span id='memUsage'>-</span>/<span id='memTotal'>-</span>MB</p></div>");
    sb.append("<div class='card'><h3>💬 聊天</h3><div id='cm' style='max-height:200px;overflow-y:auto'></div><div id='ci' style='display:none'><input id='cmsg' placeholder='发送...'><button onclick='SC()' style='width:100%'>发送</button></div></div>");
    sb.append("</div><div class='main'><div id='motdBar'></div><div class='carousel' id='carouselContainer'></div>");
    sb.append("<div class='tabs' id='mt'><span class='tab active' onclick='ST(this,\"ann\")'>📢公告</span><span class='tab' onclick='ST(this,\"com\")'>💬社区</span><span class='tab' onclick='ST(this,\"abt\")'>ℹ关于</span><span class='tab' onclick='ST(this,\"con\")'>📩联系</span></div><div id='mc'></div></div></div>");

    // 登录弹窗
    sb.append("<div class='modal-overlay' id='modal-login' onclick='if(event.target===this)C(\"login\")'><div class='modal'><span class='modal-close' onclick='C(\"login\")'>✕</span><h2>登录</h2><input id='le' placeholder='邮箱或用户名'><input id='lp' type='password' placeholder='密码'><div style='margin:8px 0'><label><input type='checkbox' id='rm' checked style='width:auto;margin-right:6px'>保持登录30天</label></div><button onclick='L()' style='width:100%'>登录</button><p id='lerr' style='color:var(--red);font-size:12px;margin-top:8px'></p></div></div>");

    // 注册弹窗
    sb.append("<div class='modal-overlay' id='modal-register' onclick='if(event.target===this)C(\"register\")'><div class='modal'><span class='modal-close' onclick='C(\"register\")'>✕</span><h2>注册</h2><input id='ru' placeholder='游戏用户名' maxlength='16'><input id='re' type='email' placeholder='邮箱地址'><input id='rp' type='password' placeholder='密码(至少6位)'>");
    sb.append("<div style='display:flex;gap:8px'><input id='rvc' placeholder='验证码' style='flex:1'><button onclick='SDC()' id='sdcBtn'>获取验证码</button></div>");
    sb.append("<button onclick='R()' style='width:100%'>注册</button><div id='rr' style='margin-top:8px;font-size:13px'></div></div></div>");

    // 个人中心弹窗
    sb.append("<div class='modal-overlay' id='modal-profile' onclick='if(event.target===this)C(\"profile\")'><div class='modal full'><span class='modal-close' onclick='C(\"profile\")'>✕</span><h2>个人主页</h2>");
    sb.append("<div style='display:flex;gap:6px;margin-bottom:14px'><span class='tab active' onclick='PT(\"info\")'>账号</span><span class='tab' onclick='PT(\"pw\")'>密码</span><span class='tab' onclick='PT(\"edit\")'>资料</span><span class='tab' onclick='PT(\"posts\")'>帖子</span></div>");
    sb.append("<div id='pc'></div></div></div>");

    // 查看他人资料弹窗
    sb.append("<div class='modal-overlay' id='modal-viewuser' onclick='if(event.target===this)C(\"viewuser\")'><div class='modal full'><span class='modal-close' onclick='C(\"viewuser\")'>✕</span><h2>玩家资料</h2><div id='vu'></div>");
    sb.append("<div class='card' style='margin-top:14px'><h3>玩家评价</h3><div id='vureviews'></div><textarea id='vureviewContent' placeholder='写下评价...'></textarea><button onclick='submitVUReview()'>提交评价</button></div></div></div>");

    // 通知下拉框
    sb.append("<div class='notif-dropdown' id='notifDropdown'><div style='padding:12px 16px;border-bottom:1px solid var(--border)'><strong>🔔 通知</strong></div><div id='notifList' style='max-height:320px;overflow-y:auto'></div></div>");

    sb.append("<div id='tc'></div>");
    sb.append("<script>");
    sb.append("var A='/api',CU=null,CA=null,DAV='").append(defaultAvatar).append("',carouselData=[],carouselIndex=0,carouselTimer=null,viewingUser=null,currentPage=1,postsPerPage=5,replyPage={},reviewPage={};");

    // 基础函数
    sb.append("function TH(t){document.body.className=t;localStorage.setItem('theme',t)}");
    sb.append("function FS(url){document.getElementById('imgFSImg').src=url;document.getElementById('imgFS').classList.add('active')}");
    sb.append("function T(m,t){var d=document.createElement('div');d.className='toast '+t;d.textContent=m;document.getElementById('tc').appendChild(d);setTimeout(function(){d.remove()},3000)}");
    sb.append("function O(id){document.querySelectorAll('.modal-overlay').forEach(function(m){m.classList.remove('active')});var el=document.getElementById('modal-'+id);if(el){el.classList.add('active');if(id==='profile'&&CU)PT('info')}}");
    sb.append("function C(id){document.getElementById('modal-'+id).classList.remove('active')}");
    sb.append("function GT(){var m=document.cookie.match(/token=([^;]+)/);return m?m[1]:null}");

    // 导航更新
    sb.append("function UN(){var nav=document.getElementById('nl'),ci=document.getElementById('ci'),tabs=document.getElementById('mt'),ec=document.getElementById('economyCard');if(CU||CA){var isBan=CU&&CU.is_banned;var name=CU?(CU.display_name||CU.username):(CA.display_name||CA.username);var av=CU&&CU.avatar_url?CU.avatar_url:DAV;var superBadge=CA&&CA.isSuperAdmin?'<span class=super-badge>超级管理</span>':'';var adminBadge=CA?'<span class=admin-badge>管理</span>':'';nav.innerHTML='<img src='+(isBan?'/default_avatar.png':av)+' style=width:32px;height:32px;border-radius:50%;cursor:pointer;object-fit:cover;'+(isBan?'filter:grayscale(100%);opacity:.6':'')+' onclick=O(\"profile\")><span style=font-weight:700;'+(isBan?'text-decoration:line-through;color:var(--red)':'')+'>'+name+'</span>'+superBadge+adminBadge+(isBan?'<span class=banned-badge>BAN</span>':'')+'<button class=bell-btn onclick=\"toggleNotif()\" id=bellBtn>🔔<span class=\"badge hidden\" id=bellBadge></span></button><select onchange=TH(this.value) style=width:auto;padding:8px;border-radius:8px><option value=t2 selected>🌙</option><option value=t1>☀</option><option value=t3>🌸</option></select>';if(ci)ci.style.display=isBan?'none':'block';if(ec)ec.style.display=CU?'block':'none';if(tabs)tabs.innerHTML='<span class=tab onclick=ST(this,\"ann\")>📢公告</span><span class=tab onclick=ST(this,\"com\")>💬社区</span><span class=tab onclick=ST(this,\"abt\")>ℹ关于</span><span class=tab onclick=ST(this,\"con\")>📩联系</span>'+(CA?'<span class=tab onclick=ST(this,\"adm\")>⚙管理</span>':'')}else{nav.innerHTML='<button onclick=O(\"login\")>登录</button><button onclick=O(\"register\")>注册</button><select onchange=TH(this.value) style=width:auto;padding:8px;border-radius:8px><option value=t2 selected>🌙</option><option value=t1>☀</option><option value=t3>🌸</option></select>';if(ci)ci.style.display='none';if(ec)ec.style.display='none';if(tabs)tabs.innerHTML='<span class=tab onclick=ST(this,\"ann\")>📢公告</span><span class=tab onclick=ST(this,\"com\")>💬社区</span><span class=tab onclick=ST(this,\"abt\")>ℹ关于</span><span class=tab onclick=ST(this,\"con\")>📩联系</span>'}}");

    // 登录/注册/验证码
    sb.append("async function L(){var e=document.getElementById('le').value,p=document.getElementById('lp').value,r=document.getElementById('rm').checked;try{var res=await fetch(A+'/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:e,password:p,remember:r})});var d=await res.json();if(d.success){document.cookie='token='+d.token+';path=/;max-age='+(r?2592000:86400);if(d.type==='admin'){CA=d.data;CU=null}else{CU=d.data;CA=null}UN();C('login');T('登录成功','success')}else{document.getElementById('lerr').textContent=d.message}}catch(ex){}}");
    sb.append("async function R(){var u=document.getElementById('ru').value,e=document.getElementById('re').value,p=document.getElementById('rp').value,vc=document.getElementById('rvc'),r=document.getElementById('rr');if(!u||!e||!p){r.innerHTML='<span style=color:var(--red)>请填写所有字段</span>';return}var body={username:u,email:e,password:p};if(vc)body.code=vc.value;try{var res=await fetch(A+'/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});var d=await res.json();if(d.success){r.innerHTML='<span style=color:var(--green)>注册成功！请在游戏内输入：<br><code>/mr '+d.bind_code+'</code></span>'}else{r.innerHTML='<span style=color:var(--red)>'+d.message+'</span>'}}catch(ex){}}");
    sb.append("async function SDC(){var e=document.getElementById('re').value;if(!e){T('请先输入邮箱','error');return}var btn=document.getElementById('sdcBtn');btn.disabled=true;btn.textContent='...';try{var r=await fetch(A+'/sendCode',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:e})});var d=await r.json();T(d.message,d.success?'success':'error');if(d.dev_code)document.getElementById('rvc').value=d.dev_code}catch(ex){}btn.disabled=false;btn.textContent='获取验证码'}");
    sb.append("function LO(){document.cookie='token=;path=/;max-age=0';CU=null;CA=null;UN();document.querySelectorAll('.modal-overlay').forEach(function(m){m.classList.remove('active')})}");
    sb.append("function ST(el,tab){document.querySelectorAll('#mt .tab').forEach(function(t){t.classList.remove('active')});el.classList.add('active');if(tab==='ann')LAT();else if(tab==='com')LC();else if(tab==='abt')LAB();else if(tab==='con')LCT();else if(tab==='adm')LAD()}");

    // 经济
    sb.append("async function loadEconomy(){if(!CU&&!CA)return;try{var r=await fetch(A+'/user/economy',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();if(d.success){").append(economyConfig.renderJS()).append("}}catch(e){}}");

    // 在线管理
    sb.append("async function loadAdminOnline(){try{var r=await fetch(A+'/server/adminOnline');var d=await r.json();if(d.success){var h='';d.data.forEach(function(a){var cls=a.source==='game'?'game':a.source==='web'?'web':'both';var label=a.source==='game'?'🎮':a.source==='web'?'🌐':'🎮🌐';h+='<span class=\"admin-dot '+cls+'\" onclick=PM(\"'+a.name+'\")>'+label+' '+a.name+'</span>'});document.getElementById('adminOnline').innerHTML=h||'暂无管理员在线'}}catch(e){}setTimeout(loadAdminOnline,30000)}");
    sb.append("function PM(target){if(!CU&&!CA){T('请先登录','error');return}var msg=prompt('私信给 '+target+':');if(!msg||!msg.trim())return;fetch(A+'/chat/pm',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({target:target,message:msg})}).then(function(r){return r.json()}).then(function(d){T(d.success?'私信已发送':'玩家不在线',d.success?'success':'error')})}");

    // 服务器信息
    sb.append("async function LSI(){try{var r=await fetch(A+'/server/info');var d=await r.json();if(d.success){document.getElementById('oc').textContent=d.data.online_players;document.getElementById('tu').textContent=d.data.total_users;document.getElementById('sip').textContent=d.data.server_ip}}catch(e){}}");
    sb.append("async function loadMOTD(){try{var r=await fetch(A+'/server/motd');var d=await r.json();if(d.success&&d.motd)document.getElementById('motdBar').innerHTML='<div class=motd-bar>'+d.motd+'</div>'}catch(e){}}");
    sb.append("async function loadResources(){try{var r=await fetch(A+'/server/resources');var d=await r.json();if(d.success){document.getElementById('cpuUsage').textContent=d.data.cpu;document.getElementById('memUsage').textContent=d.data.memory_used;document.getElementById('memTotal').textContent=d.data.memory_total}}catch(e){}setTimeout(loadResources,5000)}");

    // 聊天
    sb.append("async function LCH(){try{var r=await fetch(A+'/chat/messages');var d=await r.json();if(d.success)document.getElementById('cm').innerHTML=d.data.slice(0,20).map(function(m){return'<div><strong onclick=VU(\"'+m.playerName+'\") style=cursor:pointer>'+m.playerName+'</strong>: '+m.message+'</div>'}).join('')}catch(e){}setTimeout(LCH,5000)}");
    sb.append("async function SC(){if(!CU&&!CA){T('请先登录','error');return}if(CU&&CU.is_banned){T('已被封禁','error');return}var msg=document.getElementById('cmsg');if(!msg.value.trim())return;await fetch(A+'/chat/send',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({message:msg.value})});msg.value='';LCH()}");

    // 通知铃铛（修复：点击空白处关闭、滚轮关闭、输入框不冒泡）
    sb.append("function toggleNotif(){var d=document.getElementById('notifDropdown');d.classList.toggle('active');if(d.classList.contains('active'))loadNotifications()}");
    sb.append("async function loadNotifications(){try{var r=await fetch(A+'/notifications',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';var unread=0;if(d.success&&d.data.length>0){d.data.forEach(function(n){var cls=n.is_replied?'replied':(n.is_read?'':'unread');if(!n.is_read)unread++;h+='<div class=\"notif-item '+cls+'\" onclick=markRead('+n.id+')><div style=font-weight:600;color:var(--blue)>'+n.sender_name+'</div><div style=font-size:11px;color:var(--muted)>'+n.created_at+'</div><div style=font-size:12px;margin-top:4px>'+n.message+'</div>'+(n.is_replied?'<div style=font-size:11px;color:var(--green);margin-top:4px>已回复: '+n.reply_content+'</div>':'<div style=margin-top:6px;display:flex;gap:6px onclick=\"event.stopPropagation()\"><input id=nr_'+n.id+' placeholder=快捷回复... style=font-size:11px;padding:4px;flex:1 onclick=\"event.stopPropagation()\"><button style=font-size:10px;padding:3px 8px onclick=\"event.stopPropagation();replyNotif('+n.id+')\">回复</button></div>')+'</div>'});document.getElementById('notifList').innerHTML=h||'<p style=padding:16px;color:var(--muted)>暂无通知</p>'}if(unread>0){document.getElementById('bellBadge').classList.remove('hidden');document.getElementById('bellBadge').textContent=unread}else{document.getElementById('bellBadge').classList.add('hidden')}}catch(e){}}");
    sb.append("async function markRead(id){await fetch(A+'/notifications/read',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});loadNotifications()}");
    sb.append("async function replyNotif(id){var inp=document.getElementById('nr_'+id);if(!inp||!inp.value.trim())return;var reply=inp.value;await fetch(A+'/notifications/reply',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id,reply:reply})});inp.value='';loadNotifications();T('已回复','success')}");

    // 公告轮播
    sb.append("async function loadCarousel(){try{var r=await fetch(A+'/announcements');var d=await r.json();if(d.success&&d.data.length>0){carouselData=d.data;carouselIndex=0;showCarousel();startCarousel()}else{document.getElementById('carouselContainer').innerHTML=''}}catch(e){}setTimeout(loadCarousel,30000)}");
    sb.append("function showCarousel(){if(carouselData.length===0)return;var a=carouselData[carouselIndex];var h='<div class=carousel-slide active><h4>'+a.title+'</h4><p>'+a.content+'</p><small>'+a.created_at+'</small></div>';var dots='';for(var i=0;i<carouselData.length;i++)dots+='<span class=carousel-dot'+(i===carouselIndex?' active':'')+' onclick=\"carouselIndex='+i+';showCarousel();resetCarousel()\"></span>';document.getElementById('carouselContainer').innerHTML=h+'<div class=carousel-dots>'+dots+'</div>'}");
    sb.append("function startCarousel(){if(carouselTimer)clearInterval(carouselTimer);if(carouselData.length>1)carouselTimer=setInterval(function(){carouselIndex=(carouselIndex+1)%carouselData.length;showCarousel()},5000)}function resetCarousel(){if(carouselTimer)clearInterval(carouselTimer);startCarousel()}");

    // 公告管理
    sb.append("async function LAT(){try{var r=await fetch(A+'/announcements');var d=await r.json();var h='';if(CA)h+='<div class=card><h3>发布公告</h3><input id=ati placeholder=标题><textarea id=aco placeholder=内容></textarea><button onclick=AA()>发布</button></div>';if(d.success)d.data.forEach(function(a){h+='<div class=card><h3>'+a.title+'</h3><p>'+a.content+'</p><small>'+a.created_at+'</small>'+(CA?'<br><button style=background:var(--red) onclick=DA('+a.id+')>删除</button>':'')+'</div>'});document.getElementById('mc').innerHTML=h||'<p>暂无公告</p>'}catch(e){}}");
    sb.append("async function AA(){var t=document.getElementById('ati').value,c=document.getElementById('aco').value;if(!t||!c)return;await fetch(A+'/admin/announcement',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({title:t,content:c})});LAT();loadCarousel()}");
    sb.append("async function DA(id){await fetch(A+'/admin/announcement',{method:'DELETE',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LAT();loadCarousel()}");

    // 社区
    sb.append("function LC(cat){if(!cat)cat='all';currentPage=1;var h='<div class=search-bar><input id=searchInput placeholder=搜索帖子...><button onclick=\"LPL(\\''+(cat||'all')+'\\')\">搜索</button></div>';h+='<div style=margin-bottom:10px><span class=tab'+(cat==='all'?' active':'')+' onclick=LC(\"all\")>全部</span><span class=tab'+(cat==='chat'?' active':'')+' onclick=LC(\"chat\")>💬留言</span><span class=tab'+(cat==='talk'?' active':'')+' onclick=LC(\"talk\")>🗣交流</span><span class=tab'+(cat==='ad'?' active':'')+' onclick=LC(\"ad\")>📢广告</span></div>';if((CU||CA)&&!(CU&&CU.is_banned))h+='<div class=card><textarea id=pct placeholder=分享你的想法...></textarea><button onclick=SP()>发布</button></div>';h+='<div id=pl>加载中...</div>';document.getElementById('mc').innerHTML=h;LPL(cat)}");
    sb.append("async function LPL(cat){try{var search=document.getElementById('searchInput');var url=A+'/community/posts';if(cat&&cat!=='all')url+='?category='+cat;if(search&&search.value.trim())url+=(url.indexOf('?')>=0?'&':'?')+'search='+encodeURIComponent(search.value.trim());var r=await fetch(url);var d=await r.json();var allPosts=[];if(d.success)allPosts=d.data;var totalPages=Math.ceil(allPosts.length/postsPerPage);if(currentPage>totalPages)currentPage=totalPages||1;var start=(currentPage-1)*postsPerPage;var pagePosts=allPosts.slice(start,start+postsPerPage);var h='';pagePosts.forEach(function(p){var isMine=CU&&CU.uuid===p.player_uuid;var av=p.skin_head_url||DAV;var content=p.content.replace(/\\[img\\](.*?)\\[\\/img\\]/g,'<img class=img-preview src=\"$1\" onclick=FS(\"$1\")>');var replyCount=p.reply_count||0;h+='<div class=post><img class=avatar src='+av+' onclick=VU(\"'+p.player_name+'\")><div style=flex:1><strong onclick=VU(\"'+p.player_name+'\")>'+p.player_name+'</strong> <small>['+p.category+'] '+p.created_at+'</small>'+(p.is_pinned?'📌':'')+'<p style=margin-top:6px>'+content+'</p><div style=margin-top:8px;display:flex;align-items:center;gap:8px><span class=reaction-btn onclick=RP('+p.id+',\"like\")>🌸 '+p.likes+'</span><span class=reaction-btn onclick=RP('+p.id+',\"dislike\")>🥚 '+p.dislikes+'</span>'+(isMine?'<button style=background:var(--red);font-size:11px;padding:4px 8px onclick=DP('+p.id+')>删除</button>':'')+(CA&&!isMine?'<button style=background:var(--red);font-size:11px;padding:4px 8px onclick=ADP('+p.id+')>🗑</button><button style=font-size:11px;padding:4px 8px onclick=APP('+p.id+')>📌</button>':'')+'<button style=font-size:11px;padding:4px 8px onclick=\"toggleReplies('+p.id+')\">💬 '+(replyCount>0?replyCount+'条回复':'回复')+'</button></div><div class=replies-container id=replies-'+p.id+' style=display:none></div></div></div>'});var pagination='';if(totalPages>1){pagination='<div class=pagination>';for(var i=1;i<=totalPages;i++)pagination+='<span'+(i===currentPage?' class=active':'')+' onclick=\"currentPage='+i+';LPL(\\''+cat+'\\')\">'+i+'</span>';pagination+='</div>'}document.getElementById('pl').innerHTML=h+(allPosts.length===0?'<p>暂无帖子</p>':'')+pagination}catch(e){}}");
    sb.append("async function SP(){if(!CU&&!CA){T('请先登录','error');return}if(CU&&CU.is_banned){T('已被封禁','error');return}var c=document.getElementById('pct');if(!c.value.trim()){T('内容不能为空','error');return}await fetch(A+'/community/posts',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({content:c.value,category:'chat'})});c.value='';LPL('all')}");
    sb.append("async function RP(id,type){if(!CU&&!CA){T('请先登录','error');return}if(CU&&CU.is_banned){T('已被封禁','error');return}await fetch(A+'/community/react',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({post_id:id,type:type})});LPL('all')}");
    sb.append("async function DP(id){if(!confirm('确定删除？'))return;await fetch(A+'/admin/deletePost',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LPL('all')}");
    sb.append("async function ADP(id){await fetch(A+'/admin/deletePost',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LPL('all')}");
    sb.append("async function APP(id){await fetch(A+'/admin/pinPost',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LPL('all')}");

    // 回复功能
    sb.append("async function toggleReplies(postId){var container=document.getElementById('replies-'+postId);if(container.style.display==='block'){container.style.display='none';return}container.style.display='block';if(!replyPage[postId])replyPage[postId]=1;loadReplies(postId,replyPage[postId])}");
    sb.append("async function loadReplies(postId,page){var r=await fetch(A+'/community/replies?post_id='+postId+'&page='+page);var d=await r.json();var h='';if(d.success&&d.data.length>0){d.data.forEach(function(rp){h+='<div class=reply-item><strong onclick=VU(\"'+rp.player_name+'\") style=cursor:pointer>'+rp.player_name+'</strong> <small>'+rp.created_at+'</small><p>'+rp.content+'</p><div style=display:flex;gap:6px;margin-top:4px><span style=font-size:11px;cursor:pointer onclick=RR('+rp.id+',\"like\")>👍 '+rp.likes+'</span></div></div>'});var pag='';if(d.pages>1){pag='<div class=pagination>';for(var i=1;i<=d.pages;i++)pag+='<span'+(i===page?' class=active':'')+' onclick=\"loadReplies('+postId+','+i+')\">'+i+'</span>';pag+='</div>'}h+=pag}h+='<div style=margin-top:8px><input id=replyInput-'+postId+' placeholder=回复... style=font-size:12px><button style=font-size:11px;padding:4px 8px onclick=submitReply('+postId+')>回复</button></div>';document.getElementById('replies-'+postId).innerHTML=h}");
    sb.append("async function submitReply(postId){var c=document.getElementById('replyInput-'+postId);if(!c.value.trim())return;await fetch(A+'/community/replies?post_id='+postId,{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({content:c.value})});c.value='';replyPage[postId]=1;loadReplies(postId,1)}");
    sb.append("async function RR(replyId,type){await fetch(A+'/community/reply/react',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({reply_id:replyId,type:type})})}");

    // 关于
    sb.append("async function LAB(){try{var r=await fetch(A+'/admin/getAbout');var d=await r.json();var h='<div class=card><h3>关于我们</h3><div id=abc>'+d.content+'</div>';if(CA)h+='<button onclick=EAB()>编辑</button>';h+='</div>';document.getElementById('mc').innerHTML=h}catch(e){}}");
    sb.append("function EAB(){var c=document.getElementById('abc').innerHTML;document.getElementById('mc').innerHTML='<div class=card><h3>编辑关于</h3><textarea id=abe>'+c+'</textarea><button onclick=SAB()>保存</button></div>'}");
    sb.append("async function SAB(){var c=document.getElementById('abe').value;await fetch(A+'/admin/updateAbout',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({content:c})});LAB()}");

    // 联系
    sb.append("function LCT(){var h='<div class=card><h3>提出建议</h3>';if((CU||CA)&&!(CU&&CU.is_banned))h+='<textarea id=suc></textarea><button onclick=SS()>提交</button>';h+='<div id=sugList style=margin-top:12px></div></div>';document.getElementById('mc').innerHTML=h;loadMySuggestions()}");
    sb.append("async function SS(){if(CU&&CU.is_banned){T('已被封禁','error');return}var c=document.getElementById('suc');if(!c.value.trim()){T('内容不能为空','error');return}await fetch(A+'/suggestions',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({content:c.value})});c.value='';T('已提交','success');loadMySuggestions()}");
    sb.append("async function loadMySuggestions(){if(!CU&&!CA)return;try{var r;if(CA){r=await fetch(A+'/admin/suggestions',{headers:{'Authorization':'Bearer '+GT()}})}else{r=await fetch(A+'/suggestions/my',{headers:{'Authorization':'Bearer '+GT()}})}var d=await r.json();var h='<h4>建议列表</h4>';if(d.success&&d.data.length>0){d.data.forEach(function(s){if(CA||s.player_name===CU.username||s.player_name===CU.display_name){h+='<div class=card><strong>'+s.player_name+'</strong> <small>'+s.created_at+'</small><p>'+s.content+'</p>'+(s.admin_reply?'<p style=color:var(--blue)>管理员回复: '+s.admin_reply+'</p>':'<p style=color:var(--muted)>等待回复...</p>')+'</div>'}})}else{h+='<p>暂无建议</p>'}document.getElementById('sugList').innerHTML=h}catch(e){}}");

    // 管理面板
    sb.append("async function LAD(){var h='<div class=card><h3>管理面板</h3>';h+='<button onclick=LAU() style=margin:4px>用户</button>';h+='<button onclick=LAS() style=margin:4px>建议</button>';h+='<button onclick=LABN() style=margin:4px>封禁</button>';if(CA&&CA.isSuperAdmin)h+='<button onclick=LAA() style=margin:4px>管理员</button>';h+='</div><div id=adc></div>';document.getElementById('mc').innerHTML=h}");
    sb.append("async function LAU(){var r=await fetch(A+'/admin/users',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';d.data.forEach(function(u){var isBan=u.is_banned;var av=u.avatar_url||DAV;h+='<div class=card><img src='+(isBan?'/default_avatar.png':av)+' style=width:28px;height:28px;border-radius:50%;vertical-align:middle;object-fit:cover;'+(isBan?'filter:grayscale(100%);opacity:.6':'')+'><strong style='+(isBan?'text-decoration:line-through;color:var(--red)':'')+'>'+u.username+'</strong> '+(u.is_bound?'✅':'❌')+' '+(isBan?'🚫':'')+'<button onclick=VU(\"'+u.username+'\")>查看</button></div>'});document.getElementById('adc').innerHTML=h}");
    sb.append("async function LAS(){var r=await fetch(A+'/admin/suggestions',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';d.data.forEach(function(s){h+='<div class=card><strong>'+s.player_name+'</strong> <small>'+s.created_at+'</small><p>'+s.content+'</p>'+(s.admin_reply?'<p style=color:var(--blue)>已回复: '+s.admin_reply+'</p>':'<input id=reply_'+s.id+' placeholder=回复...><button onclick=RS('+s.id+')>回复</button>')+'</div>'});document.getElementById('adc').innerHTML=h||'<p>暂无</p>'}");
    sb.append("async function RS(id){var r=document.getElementById('reply_'+id).value;if(!r.trim())return;await fetch(A+'/suggestions/reply',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id,reply:r})});LAS()}");
    sb.append("async function LABN(){var r=await fetch(A+'/admin/bans',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';d.data.forEach(function(b){h+='<div class=card><strong>'+b.player_name+'</strong><br>原因:'+b.reason+'<br>'+b.banned_at+'</div>'});document.getElementById('adc').innerHTML=h||'<p>无</p>'}");
    sb.append("async function LAA(){var r=await fetch(A+'/admin/admins',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='<h4>管理员列表</h4><div class=card><input id=newAdminUser placeholder=用户名><input id=newAdminPass type=password placeholder=密码><button onclick=AAD()>添加管理员</button></div>';d.data.forEach(function(a){h+='<div class=card><strong>'+a.username+'</strong> <small>'+a.level+'</small>'+(a.level!=='super'?'<button style=background:var(--red);font-size:11px onclick=ARD(\"'+a.username+'\")>移除</button>':'')+'</div>'});document.getElementById('adc').innerHTML=h}");
    sb.append("async function AAD(){var u=document.getElementById('newAdminUser').value,p=document.getElementById('newAdminPass').value;if(!u||!p){T('请填写完整','error');return}var r=await fetch(A+'/admin/addAdmin',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({username:u,password:p})});var d=await r.json();T(d.message||(d.success?'已添加':'失败'),d.success?'success':'error');if(d.success)LAA()}");
    sb.append("async function ARD(u){if(!confirm('移除管理员 '+u+'?'))return;await fetch(A+'/admin/removeAdmin',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({username:u})});LAA()}");

    // 查看他人资料
    sb.append("async function VU(name){try{var r=await fetch(A+'/admin/users');var d=await r.json();var u=d.data.find(function(x){return x.username===name});if(!u){T('玩家不存在','error');return}var isBan=u.is_banned;var canView=CA||u.privacy_public||(CU&&CU.uuid===u.uuid);if(!canView){T('该用户设置了隐私保护','error');return}viewingUser=u;var av=u.avatar_url||DAV;var h='<div style=text-align:center><img src='+(isBan?'/default_avatar.png':av)+' style=width:85px;height:85px;border-radius:50%;object-fit:cover;border:4px solid '+(isBan?'var(--red)':'var(--blue)')+';'+(isBan?'filter:grayscale(100%);opacity:.6':'')+'><h3 style='+(isBan?'text-decoration:line-through;color:var(--red)':'')+'>'+(u.display_name||'')+'</h3><p style=color:var(--muted)>@'+u.username+'</p>'+(isBan?'<span class=banned-badge>BAN</span>':'')+'</div>';h+='<p>游戏ID: '+u.username+'</p><p>邮箱: '+(u.email||'-')+'</p><p>简介: '+(u.bio||'无')+'</p><p>注册: '+u.reg_date+'</p>';if(CA){if(!isBan)h+='<button style=background:var(--red) onclick=ABN(\"'+u.uuid+'\")>封禁</button>';if(isBan)h+='<button onclick=AUBN(\"'+u.uuid+'\")>解封</button>';h+='<button onclick=ACPW(\"'+u.uuid+'\",\"'+u.username+'\") style=margin-left:6px>修改密码</button>'}document.getElementById('vu').innerHTML=h;loadVUReviews(u.uuid,1);O('viewuser')}catch(e){}}");
    sb.append("function ABN(uuid){var r=prompt('封禁原因:');if(!r)return;fetch(A+'/admin/ban',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({uuid:uuid,reason:r})}).then(function(){C('viewuser')})}");
    sb.append("function AUBN(uuid){fetch(A+'/admin/unban',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({uuid:uuid})}).then(function(){C('viewuser')})}");
    sb.append("function ACPW(uuid,name){var np=prompt('为 '+name+' 设置新密码(至少6位):');if(!np||np.length<6){T('密码至少6位','error');return}fetch(A+'/admin/changeUserPassword',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({uuid:uuid,newPassword:np})}).then(function(r){return r.json()}).then(function(d){T(d.success?'密码已修改':'修改失败',d.success?'success':'error')})}");

    // 评价分页
    sb.append("async function loadVUReviews(uuid,page){if(!page)page=1;if(!reviewPage[uuid])reviewPage[uuid]=1;reviewPage[uuid]=page;try{var r=await fetch(A+'/user/reviews?uuid='+uuid+'&page='+page);var d=await r.json();var h='';if(d.success&&d.data.length>0){d.data.forEach(function(rv){h+='<div class=post><div><strong>'+rv.player_name+'</strong> <small>'+rv.created_at+'</small><p>'+rv.content+'</p>'+(rv.reply_content?'<div class=reply-box><strong>'+rv.reply_to+' 回复:</strong><p>'+rv.reply_content+'</p></div>':'')+'</div></div>'});var pag='';if(d.pages>1){pag='<div class=pagination>';for(var i=1;i<=d.pages;i++)pag+='<span'+(i===page?' class=active':'')+' onclick=\"loadVUReviews(\\''+uuid+'\\','+i+')\">'+i+'</span>';pag+='</div>'}h+=pag}document.getElementById('vureviews').innerHTML=h||'<p>暂无评价</p>'}catch(e){}}");
    sb.append("async function submitVUReview(){if(!CU&&!CA){T('请先登录','error');return}if(!viewingUser)return;var c=document.getElementById('vureviewContent');if(!c.value.trim())return;await fetch(A+'/user/reviews/add',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({uuid:viewingUser.uuid,content:c.value})});c.value='';loadVUReviews(viewingUser.uuid,1);T('已提交','success')}");

    // 个人中心
    sb.append("function PT(tab){document.querySelectorAll('#modal-profile .tab').forEach(function(t){t.classList.remove('active')});event.target.classList.add('active');var pc=document.getElementById('pc');var d=CU||CA;if(tab==='info'){var av=d.avatar_url||DAV;var isBan=CU&&CU.is_banned;var dn=d.display_name||'';pc.innerHTML='<div style=text-align:center><div class=avatar-upload onclick=document.getElementById(\"avf\").click()><img src='+(isBan?'/default_avatar.png':av)+' id=avimg style='+(isBan?'filter:grayscale(100%);opacity:.6;border-color:var(--red)':'')+'><span>✎</span></div><input type=file id=avf accept=image/* style=display:none onchange=startCrop(this)'+(isBan?' disabled':'')+'><h3>'+dn+'</h3><p style=color:var(--muted)>@'+d.username+'</p>'+(isBan?'<span class=banned-badge>BAN</span>':'')+'</div><p>游戏ID: '+d.username+'</p><p>邮箱: '+(d.email||'-')+' <button style=font-size:10px;padding:2px 8px onclick=\"PT(\\'email\\')\">修改</button></p><p>简介: '+(d.bio||'无')+'</p><p>注册: '+(d.reg_date||'-')+'</p><p>金币: '+(d.balance||0)+'</p><p>点券: '+(d.points||0)+'</p><button class=logout-btn onclick=LO()>退出登录</button>'+(CU?'<div class=card style=margin-top:12px><h4>他人评价</h4><div id=myReviews></div></div>':'');if(CU)loadMyReviews(CU.uuid,1)}else if(tab==='pw'){pc.innerHTML='<div style=border:2px solid var(--red);padding:14px;border-radius:10px><p style=color:var(--red);font-weight:700>⚠修改密码</p><input id=op type=password placeholder=原密码><input id=np type=password placeholder=新密码><input id=cp type=password placeholder=确认><button onclick=CPW()>修改</button></div>'}else if(tab==='email'){pc.innerHTML='<div class=card><h3>修改邮箱</h3><input id=newEmail type=email placeholder=新邮箱><input id=emailPw type=password placeholder=确认密码><button onclick=CE()>修改</button></div>'}else if(tab==='edit'){pc.innerHTML='<input id=en placeholder=昵称 value='+(CU?(CU.display_name||''):(CA.display_name||''))+'><textarea id=eb placeholder=简介>'+(CU?(CU.bio||''):'')+'</textarea><div style=display:flex;align-items:center;gap:10px;margin:10px 0><label>允许他人查看我的资料</label><input type=checkbox id=privacyToggle '+(CU&&CU.privacy_public!==false?'checked':'')+' onchange=TP() style=width:auto;margin:0></div><button onclick=UP()>保存</button>'}else if(tab==='posts'){LP()}}");
    sb.append("async function CE(){var e=document.getElementById('newEmail').value,p=document.getElementById('emailPw').value;if(!e||!p){T('请填写完整','error');return}var r=await fetch(A+'/user/changeEmail',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({email:e,password:p})});var d=await r.json();T(d.message||(d.success?'修改成功':'修改失败'),d.success?'success':'error')}");
    sb.append("async function TP(){var r=await fetch(A+'/user/privacy',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:'{}'});var d=await r.json();if(d.success){CU.privacy_public=d.privacy_public;T(d.privacy_public?'已设为公开':'已设为隐私','success')}}");
    sb.append("async function loadMyReviews(uuid,page){if(!page)page=1;try{var r=await fetch(A+'/user/reviews?uuid='+uuid+'&page='+page);var d=await r.json();var h='';if(d.success&&d.data.length>0){d.data.forEach(function(rv){h+='<div class=post><div><strong>'+rv.player_name+'</strong> <small>'+rv.created_at+'</small><p>'+rv.content+'</p><input id=replyTo_'+rv.id+' placeholder=回复... style=font-size:11px><button style=font-size:10px;padding:3px 8px onclick=RRV('+rv.id+')>回复</button>'+(rv.reply_content?'<div class=reply-box><strong>你的回复:</strong><p>'+rv.reply_content+'</p></div>':'')+'</div></div>'});var pag='';if(d.pages>1){pag='<div class=pagination>';for(var i=1;i<=d.pages;i++)pag+='<span'+(i===page?' class=active':'')+' onclick=\"loadMyReviews(\\''+uuid+'\\','+i+')\">'+i+'</span>';pag+='</div>'}h+=pag}var el=document.getElementById('myReviews');if(el)el.innerHTML=h||'<p>暂无评价</p>'}catch(e){}}");
    sb.append("async function RRV(id){var r=document.getElementById('replyTo_'+id).value;if(!r.trim())return;await fetch(A+'/user/reviews/reply',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id,reply:r})});loadMyReviews(CU.uuid,1);T('已回复','success')}");
    sb.append("async function CPW(){var o=document.getElementById('op').value,n=document.getElementById('np').value,c=document.getElementById('cp').value;if(!o||!n||n!==c){T('检查输入','error');return}var r=await fetch(A+'/user/changePassword',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({oldPassword:o,newPassword:n})});T(r.message||(r.success?'已修改':'失败'),r.success?'success':'error')}");
    sb.append("async function UP(){var n=document.getElementById('en').value,b=document.getElementById('eb').value;await fetch(A+'/user/update',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({display_name:n,bio:b})});if(CU){CU.display_name=n;CU.bio=b}else if(CA){CA.display_name=n}UN();PT('info');T('已更新','success')}");
    sb.append("async function LP(){var r=await fetch(A+'/user/posts',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';if(d.success)d.data.forEach(function(p){h+='<div class=card><small>['+p.category+'] '+p.created_at+'</small><p>'+p.content.replace(/\\[img\\](.*?)\\[\\/img\\]/g,'<img src=\"$1\" style=max-width:200px;border-radius:8px;cursor:pointer onclick=FS(\"$1\")>')+'</p><button style=background:var(--red);font-size:11px;margin-top:4px onclick=DP('+p.id+')>删除</button></div>'});document.getElementById('pc').innerHTML=h||'<p>暂无帖子</p>'}");

    // 头像裁剪
    sb.append("function startCrop(input){var f=input.files[0];if(!f)return;if(!f.type.match(/image\\/(jpeg|png|gif)/)){T('仅支持jpg/png/gif','error');return}var reader=new FileReader();reader.onload=function(e){cropFile=f;var img=document.getElementById('cropImg');img.src=e.target.result;img.onload=function(){var maxW=Math.min(img.naturalWidth,window.innerWidth*0.85);var maxH=Math.min(img.naturalHeight,window.innerHeight*0.45);var ratio=Math.min(maxW/img.naturalWidth,maxH/img.naturalHeight);img.style.width=(img.naturalWidth*ratio)+'px';img.style.height=(img.naturalHeight*ratio)+'px';var box=document.getElementById('cropBox');cropSize=Math.floor(Math.min(img.offsetWidth,img.offsetHeight)*0.6);cropSize=Math.max(60,Math.min(400,cropSize));box.style.width=cropSize+'px';box.style.height=cropSize+'px';box.style.left=Math.floor((img.offsetWidth-cropSize)/2)+'px';box.style.top=Math.floor((img.offsetHeight-cropSize)/2)+'px';box.style.display='block';makeDraggable(box,img);makeResizable(box,img);updateCropPreview(img,box)};document.getElementById('cropOverlay').classList.add('active')};reader.readAsDataURL(f)}");
    sb.append("function makeDraggable(el,img){var sx,sy,ol,ot;el.onmousedown=function(e){if(e.target===document.getElementById('cropResize'))return;e.preventDefault();sx=e.clientX;sy=e.clientY;ol=el.offsetLeft;ot=el.offsetTop;document.onmousemove=function(e){var nl=ol+(e.clientX-sx);var nt=ot+(e.clientY-sy);nl=Math.max(0,Math.min(nl,img.offsetWidth-el.offsetWidth));nt=Math.max(0,Math.min(nt,img.offsetHeight-el.offsetHeight));el.style.left=nl+'px';el.style.top=nt+'px';updateCropPreview(img,el)};document.onmouseup=function(){document.onmousemove=null;document.onmouseup=null}};el.addEventListener('touchstart',function(e){var t=e.touches[0];sx=t.clientX;sy=t.clientY;ol=el.offsetLeft;ot=el.offsetTop});el.addEventListener('touchmove',function(e){e.preventDefault();var t=e.touches[0];var nl=ol+(t.clientX-sx);var nt=ot+(t.clientY-sy);nl=Math.max(0,Math.min(nl,img.offsetWidth-el.offsetWidth));nt=Math.max(0,Math.min(nt,img.offsetHeight-el.offsetHeight));el.style.left=nl+'px';el.style.top=nt+'px';updateCropPreview(img,el)})}");
    sb.append("function makeResizable(el,img){var handle=document.getElementById('cropResize');handle.onmousedown=function(e){e.preventDefault();e.stopPropagation();var sx=e.clientX;var sy=e.clientY;var ow=el.offsetWidth;document.onmousemove=function(e){var nw=Math.max(60,Math.min(ow+(e.clientX-sx),img.offsetWidth-el.offsetLeft,img.offsetHeight-el.offsetTop));nw=Math.min(nw,400);el.style.width=nw+'px';el.style.height=nw+'px';cropSize=nw;updateCropPreview(img,el)};document.onmouseup=function(){document.onmousemove=null;document.onmouseup=null}}}");
    sb.append("function updateCropPreview(img,box){var canvas=document.createElement('canvas');var size=120;canvas.width=size;canvas.height=size;var ctx=canvas.getContext('2d');var scaleX=img.naturalWidth/img.offsetWidth;var scaleY=img.naturalHeight/img.offsetHeight;ctx.drawImage(img,box.offsetLeft*scaleX,box.offsetTop*scaleY,cropSize*scaleX,cropSize*scaleY,0,0,size,size);document.getElementById('cropPreview').innerHTML='<p>预览:</p><img src='+canvas.toDataURL()+'>'}");
    sb.append("function confirmCrop(){var img=document.getElementById('cropImg');var box=document.getElementById('cropBox');var canvas=document.createElement('canvas');canvas.width=cropSize;canvas.height=cropSize;var ctx=canvas.getContext('2d');var scaleX=img.naturalWidth/img.offsetWidth;var scaleY=img.naturalHeight/img.offsetHeight;ctx.drawImage(img,box.offsetLeft*scaleX,box.offsetTop*scaleY,cropSize*scaleX,cropSize*scaleY,0,0,cropSize,cropSize);canvas.toBlob(function(blob){var fd=new FormData();fd.append('file',blob,'avatar.png');fetch(A+'/user/uploadAvatar',{method:'POST',headers:{'Authorization':'Bearer '+GT()},body:fd}).then(function(r){return r.json()}).then(function(d){if(d.success){CU.avatar_url=d.url;var newUrl=d.url+'?t='+Date.now();document.getElementById('avimg').src=newUrl;UN();T('头像已更新','success')}else{T('上传失败','error')}document.getElementById('cropOverlay').classList.remove('active')})},'image/png')}");
    sb.append("function cancelCrop(){document.getElementById('cropOverlay').classList.remove('active');cropFile=null}");

    // 初始化 + 铃铛关闭事件
    sb.append("document.addEventListener('click',function(e){var d=document.getElementById('notifDropdown');var b=document.getElementById('bellBtn');if(d&&d.classList.contains('active')&&!d.contains(e.target)&&e.target!==b&&!b.contains(e.target)){d.classList.remove('active')}});");
    sb.append("document.addEventListener('wheel',function(){var d=document.getElementById('notifDropdown');if(d&&d.classList.contains('active'))d.classList.remove('active')});");
    sb.append("document.addEventListener('scroll',function(){var d=document.getElementById('notifDropdown');if(d&&d.classList.contains('active'))d.classList.remove('active')});");
    sb.append("document.addEventListener('DOMContentLoaded',function(){var t=localStorage.getItem('theme')||'t2';document.body.className=t;var token=GT();if(token){fetch(A+'/user/info',{headers:{'Authorization':'Bearer '+token}}).then(function(r){return r.json()}).then(function(d){if(d.success){if(d.data.isAdmin){CA=d.data}else{CU=d.data}UN();loadEconomy();loadNotifications()}}).catch(function(){})}UN();LSI();loadMOTD();loadResources();loadAdminOnline();LCH();loadCarousel();LAT();setInterval(LSI,30000)});");
    sb.append("</script></body></html>");
    return sb.toString();
}
}
