package com.mcplugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

public class WebAPIServer {
    private final WebBridgePlugin plugin;
    private final DatabaseManager db;
    private final ChatSyncListener chat;
    private final MailSender mailSender;
    private HttpServer server;
    private final Gson gson = new Gson();
    private final Map<String, String> verificationCodes = new ConcurrentHashMap<>();
    private final Map<String, Long> codeExpiry = new ConcurrentHashMap<>();

    public WebAPIServer(WebBridgePlugin p, DatabaseManager d, ChatSyncListener c, MailSender m) {
        this.plugin = p; this.db = d; this.chat = c; this.mailSender = m;
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
        } catch (IOException e) { plugin.getLogger().severe("启动失败: " + e.getMessage()); }
    }

    public void stop() { if (server != null) server.stop(0); }

    class ResourceHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            InputStream is = getClass().getClassLoader().getResourceAsStream("web/default_avatar.png");
            if (is == null) { String msg = "404"; ex.sendResponseHeaders(404, msg.length()); ex.getResponseBody().write(msg.getBytes()); ex.getResponseBody().close(); return; }
            byte[] buf = new byte[8192]; int len; ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            byte[] bytes = bos.toByteArray(); is.close();
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes); ex.getResponseBody().close();
        }
    }

    class FileHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            File file = new File(plugin.getDataFolder(), "uploads" + path.replace("/uploads", ""));
            if (file.exists()) {
                ex.getResponseHeaders().set("Content-Type", path.endsWith(".png") ? "image/png" : "image/jpeg");
                ex.sendResponseHeaders(200, file.length());
                FileInputStream fis = new FileInputStream(file); OutputStream os = ex.getResponseBody();
                byte[] buf = new byte[8192]; int len;
                while ((len = fis.read(buf)) != -1) os.write(buf, 0, len);
                fis.close(); os.close();
            } else { String msg = "404"; ex.sendResponseHeaders(404, msg.length()); ex.getResponseBody().write(msg.getBytes()); ex.getResponseBody().close(); }
        }
    }

    private String readBody(HttpExchange ex) throws IOException {
        InputStream is = ex.getRequestBody(); ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[1024]; int n;
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
        ex.getResponseBody().write(bytes); ex.getResponseBody().close();
    }

    private String esc(String s) { return s == null ? "" : s.replace("'", "''"); }
    private String getToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String c : cookie.split(";")) {
            String[] p = c.trim().split("=", 2);
            if (p.length == 2 && p[0].equals("token")) return p[1];
        }
        return null;
    }
    private Map<String, Object> validate(HttpExchange ex) {
        String token = getToken(ex); if (token == null) return null;
        Map<String, Object> d = db.validateToken(token); if (d.isEmpty()) return null;
        if ("admin".equals(d.get("type"))) { d.put("isAdmin", true); d.put("username", d.get("uuid")); }
        else { d.put("isAdmin", false); Map<String, Object> ud = db.getPlayerData((String)d.get("uuid")); if (!ud.isEmpty()) d.putAll(ud); }
        return d;
    }
    private Map<String, Object> M(Object... kv) { Map<String, Object> m = new HashMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String)kv[i], kv[i+1]); return m; }

    class MainHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            byte[] bytes = buildPage().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes); ex.getResponseBody().close();
        }
    }

    class APIHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            if ("OPTIONS".equals(method)) { sendJson(ex, 200, "ok"); return; }
            try {
                switch (path) {
                    case "/api/login": login(ex); break; case "/api/register": register(ex); break;
                    case "/api/sendCode": sendCode(ex); break; case "/api/logout": logout(ex); break;
                    case "/api/user/info": userInfo(ex); break; case "/api/user/update": userUpdate(ex); break;
                    case "/api/user/changePassword": changePassword(ex); break;
                    case "/api/user/uploadAvatar": uploadAvatar(ex); break;
                    case "/api/user/posts": userPosts(ex); break;
                    case "/api/server/info": serverInfo(ex); break;
                    case "/api/chat/messages": chatMessages(ex); break; case "/api/chat/send": chatSend(ex); break;
                    case "/api/announcements": announcements(ex); break;
                    case "/api/community/posts": communityPosts(ex); break;
                    case "/api/community/react": communityReact(ex); break;
                    case "/api/community/upload": communityUpload(ex); break;
                    case "/api/suggestions": suggestions(ex); break;
                    case "/api/admin/announcement": adminAnnouncement(ex); break;
                    case "/api/admin/users": adminUsers(ex); break;
                    case "/api/admin/suggestions": adminSuggestions(ex); break;
                    case "/api/admin/deletePost": adminDeletePost(ex); break;
                    case "/api/admin/pinPost": adminPinPost(ex); break;
                    case "/api/admin/ban": adminBan(ex); break;
                    case "/api/admin/unban": adminUnban(ex); break;
                    case "/api/admin/bans": adminBans(ex); break;
                    case "/api/admin/updateAbout": adminUpdateAbout(ex); break;
                    case "/api/admin/getAbout": adminGetAbout(ex); break;
                    default: sendJson(ex, 404, M("error", "Not Found"));
                }
            } catch (Exception e) { sendJson(ex, 500, M("error", e.getMessage())); }
        }
    }

    // ====== API方法（与之前一致，省略实现以节省篇幅，直接复制之前的） ======
    private void login(HttpExchange ex) throws IOException {
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        try {
            ResultSet rs = db.query("SELECT * FROM admins WHERE username='"+esc(p.get("email"))+"'");
            if (rs.next() && rs.getString("password").equals(p.get("password"))) {
                String token = db.createToken(rs.getString("username"), "admin", true);
                sendJson(ex, 200, M("success",true,"type","admin","data",M("username",rs.getString("username")),"token",token));
                return;
            }
            Map<String, Object> ud = db.getPlayerByEmail(p.get("email"));
            if (!ud.isEmpty() && ud.get("password").equals(p.get("password"))) {
                String token = db.createToken((String)ud.get("uuid"), "user", true);
                ud.remove("password"); sendJson(ex, 200, M("success",true,"type","user","data",ud,"token",token));
            } else sendJson(ex, 200, M("success",false,"message","账号或密码错误"));
        } catch (SQLException e) { sendJson(ex, 500, M("success",false)); }
    }
    private void register(HttpExchange ex) throws IOException {
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String email = p.get("email"), code = p.get("code");
        if (p.get("username")==null||p.get("username").length()<3) { sendJson(ex,200,M("success",false,"message","用户名至少3字符")); return; }
        if (mailSender.isEnabled() && (code==null||code.isEmpty())) { sendJson(ex,200,M("success",false,"message","请输入验证码")); return; }
        if (mailSender.isEnabled() && !code.equals(verificationCodes.get(email))) { sendJson(ex,200,M("success",false,"message","验证码错误")); return; }
        String bindCode = UUID.randomUUID().toString().replace("-","").substring(0,16);
        try { PreparedStatement ps = db.prepare("INSERT INTO mc_accounts (username,email,password,bind_code) VALUES (?,?,?,?)");
            ps.setString(1,p.get("username")); ps.setString(2,email); ps.setString(3,p.get("password")); ps.setString(4,bindCode);
            ps.executeUpdate(); sendJson(ex,200,M("success",true,"bind_code",bindCode));
        } catch (SQLException e) { sendJson(ex,200,M("success",false,"message","邮箱已存在")); }
    }
    private void sendCode(HttpExchange ex) throws IOException {
        Map<String, String> p = gson.fromJson(readBody(ex), new TypeToken<Map<String,String>>(){}.getType());
        String email = p.get("email"), code = String.format("%06d", new Random().nextInt(999999));
        verificationCodes.put(email, code);
        if (mailSender.isEnabled()) mailSender.sendVerificationCode(email, code);
        sendJson(ex,200,M("success",true,"message",mailSender.isEnabled()?"已发送":"开发模式:"+code,"dev_code",mailSender.isEnabled()?null:code));
    }
    private void logout(HttpExchange ex) throws IOException { String t=getToken(ex); if(t!=null)db.removeToken(t); sendJson(ex,200,M("success",true)); }
    private void userInfo(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); sendJson(ex,a==null?401:200,M("success",a!=null,"data",a)); }
    private void userUpdate(HttpExchange ex) throws IOException {
        Map<String,Object> a=validate(ex); if(a==null)return;
        Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType());
        try { if(p.containsKey("display_name")) db.update("UPDATE mc_accounts SET display_name='"+esc(p.get("display_name"))+"' WHERE uuid='"+esc((String)a.get("uuid"))+"'");
            if(p.containsKey("bio")) db.update("UPDATE mc_accounts SET bio='"+esc(p.get("bio"))+"' WHERE uuid='"+esc((String)a.get("uuid"))+"'");
            sendJson(ex,200,M("success",true)); } catch (SQLException e) { sendJson(ex,200,M("success",false)); }
    }
    private void changePassword(HttpExchange ex) throws IOException {
        Map<String,Object> a=validate(ex); if(a==null||(boolean)a.get("isAdmin"))return;
        Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType());
        try { ResultSet rs=db.query("SELECT password FROM mc_accounts WHERE uuid='"+esc((String)a.get("uuid"))+"'");
            if(rs.next()&&rs.getString("password").equals(p.get("oldPassword")))
                db.update("UPDATE mc_accounts SET password='"+esc(p.get("newPassword"))+"' WHERE uuid='"+esc((String)a.get("uuid"))+"'");
            sendJson(ex,200,M("success",true)); } catch (SQLException e) { sendJson(ex,200,M("success",false)); }
    }
    private void uploadAvatar(HttpExchange ex) throws IOException {
        Map<String,Object> a=validate(ex); if(a==null||(boolean)a.get("isAdmin"))return;
        InputStream is=ex.getRequestBody(); ByteArrayOutputStream bos=new ByteArrayOutputStream();
        byte[] b=new byte[1024]; int n; while((n=is.read(b))!=-1)bos.write(b,0,n);
        byte[] body=bos.toByteArray(); String bodyStr=new String(body,0,Math.min(body.length,100000),StandardCharsets.ISO_8859_1);
        String ext=bodyStr.contains("image/png")?".png":".jpg";
        int ds=bodyStr.indexOf("\r\n\r\n")+4, de=bodyStr.lastIndexOf("------");
        if(ds<4||de<0){sendJson(ex,200,M("success",false,"message","上传失败"));return;}
        byte[] fd=Arrays.copyOfRange(body,ds,de-2);
        File dir=new File(plugin.getDataFolder(),"uploads"); dir.mkdirs();
        String fn="avatar_"+a.get("uuid")+ext;
        java.nio.file.Files.write(new File(dir,fn).toPath(),fd);
        String url="/uploads/"+fn;
        try{db.update("UPDATE mc_accounts SET avatar_url='"+url+"' WHERE uuid='"+esc((String)a.get("uuid"))+"'");}catch(SQLException e){}
        sendJson(ex,200,M("success",true,"url",url));
    }
    private void userPosts(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null)return; List<Map<String,Object>> list=new ArrayList<>(); try{ResultSet rs=db.query("SELECT * FROM community_posts WHERE player_uuid='"+esc((String)a.get("uuid"))+"' AND is_deleted=FALSE ORDER BY created_at DESC LIMIT 50"); while(rs.next())list.add(M("id",rs.getInt("id"),"content",rs.getString("content"),"category",rs.getString("category"),"image_url",rs.getString("image_url"),"created_at",rs.getString("created_at")));}catch(SQLException e){} sendJson(ex,200,M("success",true,"data",list)); }
    private void serverInfo(HttpExchange ex) throws IOException { sendJson(ex,200,M("success",true,"data",M("online_players",chat.getOnlineCount(),"player_list",chat.getOnlinePlayers(),"total_users",db.getTotalUsers(),"server_ip",plugin.getConfig().getString("server.ip","localhost")))); }
    private void chatMessages(HttpExchange ex) throws IOException { sendJson(ex,200,M("success",true,"data",chat.getRecentMessages(0))); }
    private void chatSend(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null)return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); chat.sendWebMessageToGame((String)a.getOrDefault("username","游客"),p.get("message")); sendJson(ex,200,M("success",true)); }
    private void announcements(HttpExchange ex) throws IOException { List<Map<String,Object>> list=new ArrayList<>(); try{ResultSet rs=db.query("SELECT * FROM announcements WHERE is_active=TRUE ORDER BY created_at DESC LIMIT 10"); while(rs.next())list.add(M("id",rs.getInt("id"),"title",rs.getString("title"),"content",rs.getString("content"),"created_at",rs.getString("created_at")));}catch(SQLException e){} sendJson(ex,200,M("success",true,"data",list)); }
    private void communityPosts(HttpExchange ex) throws IOException { String cat="all", q=ex.getRequestURI().getQuery(); if(q!=null&&q.contains("category=")) cat=q.split("category=")[1].split("&")[0]; if("POST".equals(ex.getRequestMethod())){ Map<String,Object> a=validate(ex); if(a==null)return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); try{ PreparedStatement ps=db.prepare("INSERT INTO community_posts (player_uuid,player_name,content,category,image_url,skin_head_url) VALUES (?,?,?,?,?,?)"); ps.setString(1,(String)a.get("uuid")); ps.setString(2,(String)a.get("username")); ps.setString(3,p.get("content")); ps.setString(4,p.getOrDefault("category","chat")); ps.setString(5,p.getOrDefault("image_url","")); ps.setString(6,(String)a.getOrDefault("avatar_url","/default_avatar.png")); ps.executeUpdate(); sendJson(ex,200,M("success",true)); }catch(SQLException e){sendJson(ex,200,M("success",false));} }else{ List<Map<String,Object>> list=new ArrayList<>(); try{String w="WHERE is_deleted=FALSE"; if(!"all".equals(cat)) w+=" AND category='"+esc(cat)+"'"; ResultSet rs=db.query("SELECT * FROM community_posts "+w+" ORDER BY is_pinned DESC, created_at DESC LIMIT 50"); while(rs.next())list.add(M("id",rs.getInt("id"),"player_uuid",rs.getString("player_uuid"),"player_name",rs.getString("player_name"),"content",rs.getString("content"),"image_url",rs.getString("image_url"),"category",rs.getString("category"),"skin_head_url",rs.getString("skin_head_url"),"likes",rs.getInt("likes"),"dislikes",rs.getInt("dislikes"),"is_pinned",rs.getBoolean("is_pinned"),"created_at",rs.getString("created_at")));}catch(SQLException e){} sendJson(ex,200,M("success",true,"data",list)); } }
    private void communityReact(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null)return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); try{ String puuid=(String)a.getOrDefault("uuid",a.get("username")); if(!db.query("SELECT * FROM post_reactions WHERE post_id="+p.get("post_id")+" AND player_uuid='"+esc(puuid)+"'").next()){ db.update("INSERT INTO post_reactions (post_id,player_uuid,reaction_type) VALUES ("+p.get("post_id")+",'"+esc(puuid)+"','"+p.get("type")+"')"); db.update("UPDATE community_posts SET "+("like".equals(p.get("type"))?"likes":"dislikes")+"="+("like".equals(p.get("type"))?"likes":"dislikes")+"+1 WHERE id="+p.get("post_id")); } sendJson(ex,200,M("success",true)); }catch(SQLException e){sendJson(ex,200,M("success",false));} }
    private void communityUpload(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null)return; InputStream is=ex.getRequestBody(); ByteArrayOutputStream bos=new ByteArrayOutputStream(); byte[] b=new byte[1024]; int n; while((n=is.read(b))!=-1)bos.write(b,0,n); byte[] body=bos.toByteArray(); String bodyStr=new String(body,0,Math.min(body.length,100000),StandardCharsets.ISO_8859_1); String ext=bodyStr.contains("image/png")?".png":".jpg"; int ds=bodyStr.indexOf("\r\n\r\n")+4, de=bodyStr.lastIndexOf("------"); if(ds<4||de<0){sendJson(ex,200,M("success",false));return;} byte[] fd=Arrays.copyOfRange(body,ds,de-2); File dir=new File(plugin.getDataFolder(),"uploads"); dir.mkdirs(); String fn="post_"+System.currentTimeMillis()+ext; java.nio.file.Files.write(new File(dir,fn).toPath(),fd); sendJson(ex,200,M("success",true,"url","/uploads/"+fn)); }
    private void suggestions(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null)return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); try{ PreparedStatement ps=db.prepare("INSERT INTO suggestions (player_uuid,player_name,content) VALUES (?,?,?)"); ps.setString(1,(String)a.getOrDefault("uuid","")); ps.setString(2,(String)a.getOrDefault("username","访客")); ps.setString(3,p.get("content")); ps.executeUpdate(); sendJson(ex,200,M("success",true)); }catch(SQLException e){sendJson(ex,200,M("success",false));} }
    private void adminAnnouncement(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; if("POST".equals(ex.getRequestMethod())){Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType());try{db.update("INSERT INTO announcements (title,content,created_by) VALUES ('"+esc(p.get("title"))+"','"+esc(p.get("content"))+"','admin')");}catch(SQLException e){}}else if("DELETE".equals(ex.getRequestMethod())){Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType());try{db.update("DELETE FROM announcements WHERE id="+p.get("id"));}catch(SQLException e){}}sendJson(ex,200,M("success",true)); }
    private void adminUsers(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; List<Map<String,Object>> list=new ArrayList<>(); try{ResultSet rs=db.query("SELECT * FROM mc_accounts ORDER BY id DESC"); while(rs.next())list.add(M("id",rs.getInt("id"),"username",rs.getString("username"),"uuid",rs.getString("uuid"),"email",rs.getString("email"),"is_bound",rs.getBoolean("is_bound"),"is_banned",rs.getBoolean("is_banned"),"avatar_url",rs.getString("avatar_url"),"reg_date",rs.getString("reg_date")));}catch(SQLException e){}sendJson(ex,200,M("success",true,"data",list)); }
    private void adminSuggestions(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; List<Map<String,Object>> list=new ArrayList<>(); try{ResultSet rs=db.query("SELECT * FROM suggestions ORDER BY created_at DESC LIMIT 100"); while(rs.next())list.add(M("id",rs.getInt("id"),"player_name",rs.getString("player_name"),"content",rs.getString("content"),"created_at",rs.getString("created_at")));}catch(SQLException e){}sendJson(ex,200,M("success",true,"data",list)); }
    private void adminDeletePost(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); try{db.update("UPDATE community_posts SET is_deleted=TRUE WHERE id="+p.get("id"));}catch(SQLException e){}sendJson(ex,200,M("success",true)); }
    private void adminPinPost(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); try{db.update("UPDATE community_posts SET is_pinned=NOT is_pinned WHERE id="+p.get("id"));}catch(SQLException e){}sendJson(ex,200,M("success",true)); }
    private void adminBan(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); db.banPlayer(p.get("uuid"),p.getOrDefault("reason","违规"),(String)a.get("username")); sendJson(ex,200,M("success",true)); }
    private void adminUnban(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); db.unbanPlayer(p.get("uuid")); sendJson(ex,200,M("success",true)); }
    private void adminBans(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; List<Map<String,Object>> list=new ArrayList<>(); try{ResultSet rs=db.query("SELECT * FROM bans WHERE is_active=TRUE ORDER BY banned_at DESC"); while(rs.next())list.add(M("id",rs.getInt("id"),"player_name",rs.getString("player_name"),"reason",rs.getString("reason"),"banned_by",rs.getString("banned_by"),"banned_at",rs.getString("banned_at")));}catch(SQLException e){}sendJson(ex,200,M("success",true,"data",list)); }
    private void adminUpdateAbout(HttpExchange ex) throws IOException { Map<String,Object> a=validate(ex); if(a==null||!(boolean)a.get("isAdmin"))return; Map<String,String> p=gson.fromJson(readBody(ex),new TypeToken<Map<String,String>>(){}.getType()); plugin.getConfig().set("web.about_content",p.get("content")); plugin.saveConfig(); sendJson(ex,200,M("success",true)); }
    private void adminGetAbout(HttpExchange ex) throws IOException { sendJson(ex,200,M("success",true,"content",plugin.getConfig().getString("web.about_content","欢迎！"))); }

    // ====== HTML页面（完整版 - 所有功能） ======
    private String buildPage() {
        String title = plugin.getConfig().getString("web.title", "MC服务器");
        String icon = plugin.getConfig().getString("web.icon", "⛏");
        String defaultAvatar = plugin.getConfig().getString("web.default_avatar", "/default_avatar.png");
        boolean mailOn = mailSender.isEnabled();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>").append(title).append("</title><style>");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}body{font-family:Arial;min-height:100vh;transition:background .3s,color .3s}");
        sb.append("body.t2{background:#0d1117;color:#c9d1d9}.t2 .navbar{background:#161b22;border-bottom:2px solid #58a6ff}.t2 .brand{color:#58a6ff}.t2 .card{background:#161b22;border:1px solid #30363d;transition:all .3s}.t2 .card h3{color:#58a6ff}.t2 button{background:#238636;color:#fff;transition:all .2s}.t2 button:hover{opacity:.85;transform:translateY(-1px)}.t2 input,.t2 textarea,.t2 select{background:#0d1117;border:1px solid #30363d;color:#c9d1d9;transition:border .2s}.t2 input:focus,.t2 textarea:focus{border-color:#58a6ff}.t2 .tab{background:#21262d;transition:all .2s}.t2 .tab.active{background:#58a6ff;color:#fff}.t2 .modal{background:#161b22;border:2px solid #58a6ff;animation:modalIn .3s ease}.t2 .post strong{color:#58a6ff}");
        sb.append("body.t1{background:#f5f5f5;color:#333}.t1 .navbar{background:#fff;border-bottom:2px solid #ffc107}.t1 .brand{color:#ffc107}.t1 .card{background:#fff;border:1px solid #ddd}.t1 .card h3{color:#ffc107}.t1 button{background:#333;color:#ffc107}.t1 button:hover{opacity:.85}.t1 input,.t1 textarea,.t1 select{background:#fff;border:1px solid #ccc;color:#333}.t1 .tab{background:#eee}.t1 .tab.active{background:#ffc107;color:#333}.t1 .modal{background:#fff;border:2px solid #ffc107}");
        sb.append("body.t3{background:#ffe4e6;color:#333}.t3 .navbar{background:#fff;border-bottom:2px solid #ec4899}.t3 .brand{color:#ec4899}.t3 .card{background:#fff;border:1px solid #fbcfe8}.t3 button{background:#ec4899;color:#fff}.t3 input,.t3 textarea,.t3 select{background:#fff;border:1px solid #fbcfe8;color:#333}.t3 .tab{background:#fce7f3}.t3 .tab.active{background:#ec4899;color:#fff}.t3 .modal{background:#fff;border:2px solid #ec4899}");
        sb.append(".navbar{padding:10px 16px;display:flex;justify-content:space-between;align-items:center;transition:all .3s}.brand{font-weight:700;font-size:18px}.nav-links{display:flex;gap:6px;align-items:center}");
        sb.append("button{cursor:pointer;padding:8px 14px;border:none;border-radius:6px;font-size:13px;margin:2px;transition:all .2s}.container{max-width:1100px;margin:0 auto;padding:16px;display:flex;gap:16px}.sidebar{width:280px}.main{flex:1}");
        sb.append(".card{border-radius:10px;padding:16px;margin-bottom:12px;transition:all .3s;animation:fadeInUp .4s ease}.card h3{margin-bottom:10px}input,textarea,select{width:100%;padding:8px;margin-bottom:8px;border-radius:6px;font-size:13px;transition:all .2s}");
        sb.append(".tabs{display:flex;gap:4px;margin-bottom:12px}.tab{padding:6px 12px;border-radius:6px;cursor:pointer;font-size:12px;transition:all .2s}.tab:hover{transform:translateY(-1px)}");
        sb.append(".modal-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.7);z-index:2000;justify-content:center;align-items:center;animation:fadeIn .2s ease}");
        sb.append(".modal-overlay.active{display:flex}.modal{border-radius:12px;padding:24px;max-width:500px;width:92%;max-height:85vh;overflow-y:auto;position:relative;animation:modalIn .3s ease}.modal.full{max-width:850px}");
        sb.append(".modal-close{position:absolute;top:10px;right:14px;background:none!important;border:none;font-size:20px;cursor:pointer;color:var(--muted);padding:4px 8px;line-height:1;transition:all .2s}.modal-close:hover{color:#da3633;transform:scale(1.2)}");
        sb.append(".modal h2{margin-bottom:16px}.post{display:flex;gap:10px;padding:12px 0;border-bottom:1px solid rgba(128,128,128,.2);animation:fadeInUp .3s ease;transition:all .2s}.post:hover{background:rgba(128,128,128,.03)}");
        sb.append(".post img.avatar{cursor:pointer;width:40px;height:40px;border-radius:6px;flex-shrink:0;object-fit:cover;transition:transform .2s}.post img.avatar:hover{transform:scale(1.1)}");
        sb.append(".post strong{font-size:13px;cursor:pointer;transition:color .2s}.admin-badge{background:#da3633;color:#fff;font-size:10px;padding:1px 6px;border-radius:4px;margin-left:4px}");
        sb.append(".img-preview{cursor:pointer;max-width:200px;border-radius:8px;transition:transform .2s}.img-preview:hover{transform:scale(1.02)}");
        sb.append(".img-fullscreen{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.9);z-index:3000;justify-content:center;align-items:center;animation:fadeIn .2s ease}");
        sb.append(".img-fullscreen.active{display:flex}.img-fullscreen img{max-width:95%;max-height:95%;border-radius:8px;animation:modalIn .3s ease}");
        sb.append(".avatar-upload{position:relative;display:inline-block;cursor:pointer;transition:transform .2s}.avatar-upload:hover{transform:scale(1.05)}.avatar-upload img{width:80px;height:80px;border-radius:50%;border:3px solid #58a6ff;object-fit:cover;transition:all .3s}");
        sb.append(".avatar-upload span{position:absolute;bottom:0;right:0;background:#58a6ff;color:#fff;border-radius:50%;width:24px;height:24px;text-align:center;line-height:24px;font-size:14px;transition:all .2s}");
        sb.append(".drop-zone{border:2px dashed #555;border-radius:8px;padding:20px;text-align:center;cursor:pointer;margin:8px 0;transition:all .3s}.drop-zone:hover{border-color:#58a6ff;background:rgba(88,166,255,.05)}");
        sb.append(".reaction-btn{display:inline-flex;align-items:center;gap:4px;padding:6px 12px;border-radius:20px;font-size:12px;cursor:pointer;transition:all .2s;background:rgba(128,128,128,.1)}.reaction-btn:hover{background:rgba(88,166,255,.2);transform:scale(1.05)}");
        sb.append(".carousel{position:relative;overflow:hidden;border-radius:12px;margin-bottom:12px;min-height:120px;animation:fadeInUp .4s ease}");
        sb.append(".carousel-slide{display:none;padding:20px;border-radius:12px;background:linear-gradient(135deg,#1a1a3e,#1e2a4a);border:2px solid #58a6ff;animation:fadeIn .3s ease}.carousel-slide.active{display:block}");
        sb.append(".carousel-dots{display:flex;justify-content:center;gap:8px;margin-top:8px}.carousel-dot{width:10px;height:10px;border-radius:50%;background:#555;cursor:pointer;transition:all .3s}.carousel-dot.active{background:#58a6ff;transform:scale(1.3)}");
        sb.append(".logout-btn{background:#da3633!important;font-size:11px;padding:4px 10px;margin-top:4px}");
        sb.append(".toast{position:fixed;bottom:20px;right:20px;padding:12px 20px;border-radius:8px;color:#fff;z-index:3000;animation:slideIn .3s ease}.toast.success{background:#238636}.toast.error{background:#da3633}");
        sb.append("@keyframes fadeIn{from{opacity:0}to{opacity:1}}@keyframes fadeInUp{from{opacity:0;transform:translateY(10px)}to{opacity:1;transform:translateY(0)}}@keyframes modalIn{from{opacity:0;transform:scale(.95)}to{opacity:1;transform:scale(1)}}@keyframes slideIn{from{opacity:0;transform:translateX(20px)}to{opacity:1;transform:translateX(0)}}");
        sb.append(".crop-overlay{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.85);z-index:4000;justify-content:center;align-items:center;flex-direction:column}");
        sb.append(".crop-overlay.active{display:flex}.crop-container{position:relative;max-width:90%;max-height:70vh}.crop-container img{max-width:100%;max-height:70vh}.crop-box{position:absolute;border:2px dashed #fff;cursor:move}.crop-actions{margin-top:12px;display:flex;gap:10px}");
        sb.append("@media(max-width:768px){.container{flex-direction:column}.sidebar{width:100%}}</style></head><body class='t2'>");

        // 导航栏
        sb.append("<nav class='navbar'><span class='brand'>").append(icon).append(" ").append(title).append("</span><div class='nav-links' id='nl'>");
        sb.append("<button onclick='O(\"login\")'>登录</button><button onclick='O(\"register\")'>注册</button>");
        sb.append("<select onchange='TH(this.value)' style='width:auto;padding:6px;border-radius:6px'><option value='t2' selected>🌙黑蓝</option><option value='t1'>☀白黄</option><option value='t3'>🌸粉白</option></select>");
        sb.append("</div></nav>");

        // 图片全屏
        sb.append("<div class='img-fullscreen' id='imgFS' onclick='this.classList.remove(\"active\")'><img id='imgFSImg'></div>");

        // 裁剪遮罩
        sb.append("<div class='crop-overlay' id='cropOverlay'><div class='crop-container' id='cropContainer'><img id='cropImg'><div class='crop-box' id='cropBox'></div></div><div class='crop-actions'><button onclick='confirmCrop()'>确认裁剪</button><button style='background:#da3633' onclick='cancelCrop()'>取消</button></div></div>");

        // 主布局
        sb.append("<div class='container'><div class='sidebar'><div class='card'><h3>服务器</h3><p>在线:<span id='oc'>-</span></p><p>注册:<span id='tu'>-</span></p><p>IP:<span id='sip'>-</span></p><div id='plist' style='font-size:11px'></div></div>");
        sb.append("<div class='card'><h3>聊天</h3><div id='cm' style='max-height:250px;overflow-y:auto'></div><div id='ci' style='display:none'><input id='cmsg' placeholder='发送...'><button onclick='SC()' style='width:100%'>发送</button></div></div></div>");
        sb.append("<div class='main'><div class='carousel' id='carouselContainer'></div><div class='tabs' id='mt'><span class='tab active' onclick='ST(this,\"ann\")'>公告</span><span class='tab' onclick='ST(this,\"com\")'>社区</span><span class='tab' onclick='ST(this,\"abt\")'>关于</span><span class='tab' onclick='ST(this,\"con\")'>联系</span></div><div id='mc'></div></div></div>");

        // 登录弹窗
        sb.append("<div class='modal-overlay' id='modal-login' onclick='if(event.target===this)C(\"login\")'><div class='modal'><span class='modal-close' onclick='C(\"login\")'>✕</span><h2>登录</h2><input id='le' placeholder='邮箱或用户名'><input id='lp' type='password' placeholder='密码'><button onclick='L()' style='width:100%'>登录</button><p id='lerr' style='color:#da3633;font-size:12px;margin-top:8px'></p></div></div>");

        // 注册弹窗
        sb.append("<div class='modal-overlay' id='modal-register' onclick='if(event.target===this)C(\"register\")'><div class='modal'><span class='modal-close' onclick='C(\"register\")'>✕</span><h2>注册</h2><input id='ru' placeholder='用户名'><input id='re' placeholder='邮箱'><input id='rp' type='password' placeholder='密码'>");
        sb.append("<div style='display:flex;gap:8px'><input id='rvc' placeholder='验证码' style='flex:1'><button onclick='SDC()' style='white-space:nowrap' id='sdcBtn'>获取验证码</button></div>");
        sb.append("<button onclick='R()' style='width:100%'>注册</button><div id='rr' style='margin-top:8px;font-size:13px'></div></div></div>");

        // 个人中心弹窗
        sb.append("<div class='modal-overlay' id='modal-profile' onclick='if(event.target===this)C(\"profile\")'><div class='modal full'><span class='modal-close' onclick='C(\"profile\")'>✕</span><h2>个人主页</h2>");
        sb.append("<div style='display:flex;gap:6px;margin-bottom:12px'><span class='tab active' onclick='PT(\"info\")'>账号</span><span class='tab' onclick='PT(\"pw\")'>密码</span><span class='tab' onclick='PT(\"edit\")'>资料</span><span class='tab' onclick='PT(\"posts\")'>帖子</span></div>");
        sb.append("<div id='pc'></div></div></div>");

        // 查看他人资料弹窗
        sb.append("<div class='modal-overlay' id='modal-viewuser' onclick='if(event.target===this)C(\"viewuser\")'><div class='modal full'><span class='modal-close' onclick='C(\"viewuser\")'>✕</span><h2>玩家资料</h2><div id='vu'></div></div></div>");

        sb.append("<div id='tc'></div><script>");
        sb.append("var A='/api',CU=null,CA=null,DAV='").append(defaultAvatar).append("',carouselData=[],carouselIndex=0,carouselTimer=null,cropFile=null;");
        sb.append("function TH(t){document.body.className=t;localStorage.setItem('theme',t)}");
        sb.append("function FS(url){document.getElementById('imgFSImg').src=url;document.getElementById('imgFS').classList.add('active')}");
        sb.append("function T(m,t){var d=document.createElement('div');d.className='toast '+t;d.textContent=m;document.getElementById('tc').appendChild(d);setTimeout(function(){d.remove()},3000)}");
        sb.append("function O(id){document.querySelectorAll('.modal-overlay').forEach(function(m){m.classList.remove('active')});var el=document.getElementById('modal-'+id);if(el){el.classList.add('active');if(id==='profile'&&CU)PT('info')}}");
        sb.append("function C(id){document.getElementById('modal-'+id).classList.remove('active')}");
        sb.append("function GT(){var m=document.cookie.match(/token=([^;]+)/);return m?m[1]:null}");

        // 导航更新（无"个人"按钮）
        sb.append("function UN(){var nav=document.getElementById('nl'),ci=document.getElementById('ci'),tabs=document.getElementById('mt');if(CU||CA){var name=CU?(CU.display_name||CU.username):CA.username;var av=CU&&CU.avatar_url?CU.avatar_url:DAV;nav.innerHTML='<img src='+av+' style=width:32px;height:32px;border-radius:50%;cursor:pointer;object-fit:cover;transition:transform .2s;margin-right:4px onclick=O(\"profile\") onmouseenter=this.style.transform=\"scale(1.1)\" onmouseleave=this.style.transform=\"scale(1)\"><span style=font-weight:700>'+name+'</span>'+(CA?'<span class=admin-badge>管理员</span>':'')+'<select onchange=TH(this.value) style=width:auto;padding:6px;border-radius:6px><option value=t2 selected>🌙</option><option value=t1>☀</option><option value=t3>🌸</option></select>';if(ci)ci.style.display='block';if(tabs)tabs.innerHTML='<span class=tab onclick=ST(this,\"ann\")>公告</span><span class=tab onclick=ST(this,\"com\")>社区</span><span class=tab onclick=ST(this,\"abt\")>关于</span><span class=tab onclick=ST(this,\"con\")>联系</span>'+(CA?'<span class=tab onclick=ST(this,\"adm\")>管理</span>':'')}else{nav.innerHTML='<button onclick=O(\"login\")>登录</button><button onclick=O(\"register\")>注册</button><select onchange=TH(this.value) style=width:auto;padding:6px;border-radius:6px><option value=t2 selected>🌙</option><option value=t1>☀</option><option value=t3>🌸</option></select>';if(ci)ci.style.display='none';if(tabs)tabs.innerHTML='<span class=tab onclick=ST(this,\"ann\")>公告</span><span class=tab onclick=ST(this,\"com\")>社区</span><span class=tab onclick=ST(this,\"abt\")>关于</span><span class=tab onclick=ST(this,\"con\")>联系</span>'}}");

        // 登录/注册
        sb.append("async function L(){var e=document.getElementById('le').value,p=document.getElementById('lp').value;try{var r=await fetch(A+'/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:e,password:p})});var d=await r.json();if(d.success){document.cookie='token='+d.token+';path=/;max-age=2592000';if(d.type==='admin'){CA=d.data;CU=null}else{CU=d.data;CA=null}UN();C('login');T('成功','success')}else{document.getElementById('lerr').textContent=d.message}}catch(ex){}}");
        sb.append("async function R(){var u=document.getElementById('ru').value,e=document.getElementById('re').value,p=document.getElementById('rp').value,vc=document.getElementById('rvc'),r=document.getElementById('rr');if(!u||!e||!p){r.innerHTML='<span>请填写所有字段</span>';return}var body={username:u,email:e,password:p};if(vc)body.code=vc.value;try{var res=await fetch(A+'/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});var d=await res.json();if(d.success){r.innerHTML='<span style=color:#3fb950>成功！绑定码:'+d.bind_code+'</span>'}else{r.innerHTML='<span>'+d.message+'</span>'}}catch(ex){}}");
        sb.append("async function SDC(){var e=document.getElementById('re').value;if(!e){T('请输入邮箱','error');return}var btn=document.getElementById('sdcBtn');btn.disabled=true;btn.textContent='...';try{var r=await fetch(A+'/sendCode',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({email:e})});var d=await r.json();T(d.message,d.success?'success':'error');if(d.dev_code)document.getElementById('rvc').value=d.dev_code}catch(ex){}btn.disabled=false;btn.textContent='获取验证码'}");
        sb.append("function LO(){document.cookie='token=;path=/;max-age=0';CU=null;CA=null;UN();document.querySelectorAll('.modal-overlay').forEach(function(m){m.classList.remove('active')})}");
        sb.append("function ST(el,tab){document.querySelectorAll('#mt .tab').forEach(function(t){t.classList.remove('active')});el.classList.add('active');if(tab==='ann')LAT();else if(tab==='com')LC();else if(tab==='abt')LAB();else if(tab==='con')LCT();else if(tab==='adm')LAD()}");

        // 服务器信息
        sb.append("async function LSI(){try{var r=await fetch(A+'/server/info');var d=await r.json();if(d.success){document.getElementById('oc').textContent=d.data.online_players;document.getElementById('tu').textContent=d.data.total_users;document.getElementById('sip').textContent=d.data.server_ip;document.getElementById('plist').innerHTML=d.data.player_list.map(function(p){return'<span style=cursor:pointer onclick=VU(\"'+p+'\")>🟢 '+p+'</span>'}).join(' ')||'无在线'}}catch(e){}}");

        // 查看他人资料
        sb.append("async function VU(name){try{var r=await fetch(A+'/admin/users');var d=await r.json();var u=d.data.find(function(x){return x.username===name});if(!u){T('玩家不存在','error');return}var av=u.avatar_url||DAV;var h='<div style=text-align:center><img src='+av+' style=width:80px;height:80px;border-radius:50%;object-fit:cover;border:3px solid #58a6ff><h3>'+u.username+'</h3></div>';h+='<p>邮箱:'+(u.email||'-')+'</p><p>注册:'+(u.reg_date||'-')+'</p>';if(CA&&!u.is_banned)h+='<button style=background:#da3633 onclick=ABN(\"'+u.uuid+'\")>封禁</button>';if(CA&&u.is_banned)h+='<button onclick=AUBN(\"'+u.uuid+'\")>解封</button>';document.getElementById('vu').innerHTML=h;O('viewuser')}catch(e){}}");
        sb.append("function ABN(uuid){var r=prompt('封禁原因:');if(!r)return;fetch(A+'/admin/ban',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({uuid:uuid,reason:r})}).then(function(){C('viewuser')})}");
        sb.append("function AUBN(uuid){fetch(A+'/admin/unban',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({uuid:uuid})}).then(function(){C('viewuser')})}");

        // 聊天
        sb.append("async function LCH(){try{var r=await fetch(A+'/chat/messages');var d=await r.json();if(d.success)document.getElementById('cm').innerHTML=d.data.slice(0,20).map(function(m){return'<div><strong style=cursor:pointer onclick=VU(\"'+m.playerName+'\")>'+m.playerName+'</strong>: '+m.message+'</div>'}).join('')}catch(e){}setTimeout(LCH,5000)}");
        sb.append("async function SC(){if(!CU&&!CA){T('请先登录','error');return}var msg=document.getElementById('cmsg');if(!msg.value.trim())return;await fetch(A+'/chat/send',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({message:msg.value})});msg.value=''}");

        // 公告轮播
        sb.append("async function loadCarousel(){try{var r=await fetch(A+'/announcements');var d=await r.json();if(d.success&&d.data.length>0){carouselData=d.data;carouselIndex=0;showCarousel();startCarousel()}else{document.getElementById('carouselContainer').innerHTML=''}}catch(e){}setTimeout(loadCarousel,30000)}");
        sb.append("function showCarousel(){if(carouselData.length===0)return;var a=carouselData[carouselIndex];var h='<div class=carousel-slide active><h4>'+a.title+'</h4><p>'+a.content+'</p><small>'+a.created_at+'</small></div>';var dots='';for(var i=0;i<carouselData.length;i++)dots+='<span class=carousel-dot'+(i===carouselIndex?' active':'')+' onclick=\"carouselIndex='+i+';showCarousel();resetCarousel()\"></span>';document.getElementById('carouselContainer').innerHTML=h+'<div class=carousel-dots>'+dots+'</div>'}");
        sb.append("function startCarousel(){if(carouselTimer)clearInterval(carouselTimer);if(carouselData.length>1)carouselTimer=setInterval(function(){carouselIndex=(carouselIndex+1)%carouselData.length;showCarousel()},5000)}function resetCarousel(){if(carouselTimer)clearInterval(carouselTimer);startCarousel()}");

        // 公告管理
        sb.append("async function LAT(){try{var r=await fetch(A+'/announcements');var d=await r.json();var h='';if(CA)h+='<div class=card><h3>发布公告</h3><input id=ati placeholder=标题><textarea id=aco placeholder=内容></textarea><button onclick=AA()>发布</button></div>';if(d.success)d.data.forEach(function(a){h+='<div class=card><h3>'+a.title+'</h3><p>'+a.content+'</p><small>'+a.created_at+'</small>'+(CA?'<br><button style=background:#da3633 onclick=DA('+a.id+')>删除</button>':'')+'</div>'});document.getElementById('mc').innerHTML=h||'<p>暂无公告</p>'}catch(e){}}");
        sb.append("async function AA(){var t=document.getElementById('ati').value,c=document.getElementById('aco').value;if(!t||!c)return;await fetch(A+'/admin/announcement',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({title:t,content:c})});LAT();loadCarousel()}");
        sb.append("async function DA(id){await fetch(A+'/admin/announcement',{method:'DELETE',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LAT();loadCarousel()}");

        // 社区
        sb.append("function LC(cat){if(!cat)cat='all';var h='<div style=margin-bottom:8px><span class=tab'+(cat==='all'?' active':'')+' onclick=LC(\"all\")>全部</span><span class=tab'+(cat==='chat'?' active':'')+' onclick=LC(\"chat\")>💬留言</span><span class=tab'+(cat==='talk'?' active':'')+' onclick=LC(\"talk\")>🗣交流</span><span class=tab'+(cat==='ad'?' active':'')+' onclick=LC(\"ad\")>📢广告</span></div>';if(CU||CA)h+='<div class=card><textarea id=pct placeholder=内容...></textarea><div class=drop-zone id=dz onclick=document.getElementById(\"pimg\").click()>拖拽或点击上传图片<input type=file id=pimg accept=image/* style=display:none onchange=UPI()></div><div id=pimgp></div><select id=pcat><option value=chat>留言</option><option value=talk>交流</option><option value=ad>广告</option></select><button onclick=SP()>发布</button></div>';h+='<div id=pl>加载中...</div>';document.getElementById('mc').innerHTML=h;LPL(cat);setupDZ()}");
        sb.append("var pendingImg=null;function setupDZ(){var dz=document.getElementById('dz');if(!dz)return;dz.ondragover=function(e){e.preventDefault();dz.style.borderColor='#58a6ff'};dz.ondragleave=function(){dz.style.borderColor='#555'};dz.ondrop=function(e){e.preventDefault();dz.style.borderColor='#555';var f=e.dataTransfer.files[0];if(f)uploadImg(f)}}");
        sb.append("function UPI(){var f=document.getElementById('pimg').files[0];if(f)uploadImg(f)}");
        sb.append("function uploadImg(file){var fd=new FormData();fd.append('file',file);fetch(A+'/community/upload',{method:'POST',headers:{'Authorization':'Bearer '+GT()},body:fd}).then(function(r){return r.json()}).then(function(d){if(d.success){pendingImg=d.url;document.getElementById('pimgp').innerHTML='<img src='+d.url+' style=max-width:200px;border-radius:8px;cursor:pointer onclick=FS(\"'+d.url+'\")>'}})}");
        sb.append("async function LPL(cat){try{var url=A+'/community/posts';if(cat&&cat!=='all')url+='?category='+cat;var r=await fetch(url);var d=await r.json();var h='';if(d.success)d.data.forEach(function(p){var isMine=CU&&CU.uuid===p.player_uuid;var av=p.skin_head_url||DAV;h+='<div class=post><img class=avatar src='+av+' onclick=VU(\"'+p.player_name+'\")><div><strong onclick=VU(\"'+p.player_name+'\")>'+p.player_name+'</strong>'+(p.is_admin?'<span class=admin-badge>管理员</span>':'')+' <small>['+p.category+'] '+p.created_at+'</small>'+(p.is_pinned?'📌':'')+'<p>'+p.content+'</p>'+(p.image_url?'<img class=img-preview src='+p.image_url+' onclick=FS(\"'+p.image_url+'\")>':'')+'<div style=margin-top:6px><span class=reaction-btn onclick=RP('+p.id+',\"like\")>🌸 '+p.likes+'</span><span class=reaction-btn onclick=RP('+p.id+',\"dislike\")>🥚 '+p.dislikes+'</span>'+(isMine?'<button style=background:#da3633;font-size:11px;padding:4px 8px;margin-left:6px onclick=DP('+p.id+')>删除</button>':'')+(CA&&!isMine?'<button style=background:#da3633;font-size:11px;padding:4px 8px;margin-left:6px onclick=ADP('+p.id+')>🗑</button><button style=font-size:11px;padding:4px 8px;margin-left:4px onclick=APP('+p.id+')>📌</button>':'')+'</div></div></div>'});document.getElementById('pl').innerHTML=h||'<p>暂无帖子</p>'}catch(e){}}");
        sb.append("async function SP(){if(!CU&&!CA){T('请先登录','error');return}var c=document.getElementById('pct'),cat=document.getElementById('pcat').value;var body={content:c.value,category:cat};if(pendingImg)body.image_url=pendingImg;await fetch(A+'/community/posts',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify(body)});c.value='';pendingImg=null;document.getElementById('pimgp').innerHTML='';LPL(cat)}");
        sb.append("async function RP(id,type){if(!CU&&!CA){T('请先登录','error');return}await fetch(A+'/community/react',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({post_id:id,type:type})});LPL('all')}");
        sb.append("async function DP(id){if(!confirm('确定删除？'))return;await fetch(A+'/admin/deletePost',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LC()}");
        sb.append("async function ADP(id){await fetch(A+'/admin/deletePost',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LC()}");
        sb.append("async function APP(id){await fetch(A+'/admin/pinPost',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({id:id})});LC()}");

        // 关于/联系/管理
        sb.append("async function LAB(){try{var r=await fetch(A+'/admin/getAbout');var d=await r.json();var h='<div class=card><h3>关于我们</h3><div id=abc>'+d.content+'</div>';if(CA)h+='<button onclick=EAB()>编辑</button>';h+='</div>';document.getElementById('mc').innerHTML=h}catch(e){}}");
        sb.append("function EAB(){var c=document.getElementById('abc').innerHTML;document.getElementById('mc').innerHTML='<div class=card><h3>编辑关于</h3><textarea id=abe>'+c+'</textarea><button onclick=SAB()>保存</button></div>'}");
        sb.append("async function SAB(){var c=document.getElementById('abe').value;await fetch(A+'/admin/updateAbout',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({content:c})});LAB()}");
        sb.append("function LCT(){var h='<div class=card><h3>提出建议</h3>';if(CU||CA)h+='<textarea id=suc></textarea><button onclick=SS()>提交</button>';else h+='<p>请登录后提交建议</p>';h+='</div>';document.getElementById('mc').innerHTML=h}");
        sb.append("async function SS(){var c=document.getElementById('suc');if(!c.value.trim())return;await fetch(A+'/suggestions',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({content:c.value})});c.value='';T('已提交','success')}");
        sb.append("async function LAD(){var h='<div class=card><h3>管理面板</h3>';h+='<button onclick=LAU() style=margin:4px>用户</button>';h+='<button onclick=LAS() style=margin:4px>建议</button>';h+='<button onclick=LABN() style=margin:4px>封禁</button>';h+='</div><div id=adc></div>';document.getElementById('mc').innerHTML=h}");
        sb.append("async function LAU(){var r=await fetch(A+'/admin/users',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';d.data.forEach(function(u){var av=u.avatar_url||DAV;h+='<div class=card><img src='+av+' style=width:24px;height:24px;border-radius:50%;object-fit:cover;vertical-align:middle> <strong>'+u.username+'</strong> '+(u.is_bound?'✅':'❌')+' '+(u.is_banned?'🚫':'')+'<button onclick=VU(\"'+u.username+'\")>查看</button></div>'});document.getElementById('adc').innerHTML=h}");
        sb.append("async function LAS(){var r=await fetch(A+'/admin/suggestions',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';d.data.forEach(function(s){h+='<div class=card><strong>'+s.player_name+'</strong><p>'+s.content+'</p></div>'});document.getElementById('adc').innerHTML=h||'<p>暂无</p>'}");
        sb.append("async function LABN(){var r=await fetch(A+'/admin/bans',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';d.data.forEach(function(b){h+='<div class=card><strong>'+b.player_name+'</strong><br>原因:'+b.reason+'<br>'+b.banned_at+'</div>'});document.getElementById('adc').innerHTML=h||'<p>无</p>'}");

        // 个人中心（含头像裁剪、退出按钮缩小）
        sb.append("function PT(tab){document.querySelectorAll('#modal-profile .tab').forEach(function(t){t.classList.remove('active')});event.target.classList.add('active');var pc=document.getElementById('pc');var d=CU||CA;if(tab==='info'){var av=d.avatar_url||DAV;pc.innerHTML='<div style=text-align:center><div class=avatar-upload onclick=document.getElementById(\"avf\").click()><img src='+av+' id=avimg><span>✎</span></div><input type=file id=avf accept=image/* style=display:none onchange=startCrop(this)><h3>'+d.username+'</h3></div><p>邮箱:'+(d.email||'-')+'</p><p>注册时间:'+(d.reg_date||'-')+'</p><button class=logout-btn onclick=LO()>退出登录</button>'}else if(tab==='pw'){pc.innerHTML='<div style=border:2px solid #da3633;padding:12px;border-radius:8px><p style=color:#da3633;font-weight:700>⚠修改密码</p><input id=op type=password placeholder=原密码><input id=np type=password placeholder=新密码><input id=cp type=password placeholder=确认><button onclick=CPW()>修改</button><p style=font-size:11px;color:#da3633;margin-top:8px>忘记密码请联系管理员</p></div>'}else if(tab==='edit'){pc.innerHTML='<input id=en placeholder=昵称 value='+(CU?(CU.display_name||''):'')+'><textarea id=eb placeholder=简介>'+(CU?(CU.bio||''):'')+'</textarea><button onclick=UP()>保存</button>'}else if(tab==='posts'){LP()}}");

        // 裁剪功能
        sb.append("function startCrop(input){var f=input.files[0];if(!f)return;if(!f.type.match(/image\\/(jpeg|png)/)){T('仅支持jpg/png','error');return}var reader=new FileReader();reader.onload=function(e){cropFile=f;document.getElementById('cropImg').src=e.target.result;document.getElementById('cropOverlay').classList.add('active');initCropBox()};reader.readAsDataURL(f)}");
        sb.append("function initCropBox(){var img=document.getElementById('cropImg');var box=document.getElementById('cropBox');img.onload=function(){var size=Math.min(img.width,img.height,300);box.style.width=size+'px';box.style.height=size+'px';box.style.left=((img.width-size)/2)+'px';box.style.top=((img.height-size)/2)+'px';makeDraggable(box)}}");
        sb.append("function makeDraggable(el){var pos={x:0,y:0};el.onmousedown=function(e){e.preventDefault();pos.x=e.clientX;pos.y=e.clientY;document.onmousemove=function(e){var dx=e.clientX-pos.x;var dy=e.clientY-pos.y;pos.x=e.clientX;pos.y=e.clientY;el.style.left=(el.offsetLeft+dx)+'px';el.style.top=(el.offsetTop+dy)+'px'};document.onmouseup=function(){document.onmousemove=null;document.onmouseup=null}}}");
        sb.append("function confirmCrop(){var img=document.getElementById('cropImg');var box=document.getElementById('cropBox');var canvas=document.createElement('canvas');var size=box.offsetWidth;canvas.width=size;canvas.height=size;var ctx=canvas.getContext('2d');ctx.drawImage(img,box.offsetLeft,box.offsetTop,size,size,0,0,size,size);canvas.toBlob(function(blob){var fd=new FormData();fd.append('file',blob,'avatar.png');fetch(A+'/user/uploadAvatar',{method:'POST',headers:{'Authorization':'Bearer '+GT()},body:fd}).then(function(r){return r.json()}).then(function(d){if(d.success){CU.avatar_url=d.url;document.getElementById('avimg').src=d.url+'?t='+Date.now();UN();T('头像已更新','success')}});document.getElementById('cropOverlay').classList.remove('active')},'image/png')}");
        sb.append("function cancelCrop(){document.getElementById('cropOverlay').classList.remove('active');cropFile=null}");
        sb.append("async function CPW(){var o=document.getElementById('op').value,n=document.getElementById('np').value,c=document.getElementById('cp').value;if(!o||!n||n!==c){T('检查输入','error');return}var r=await fetch(A+'/user/changePassword',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({oldPassword:o,newPassword:n})});T('已修改','success')}");
        sb.append("async function UP(){var n=document.getElementById('en').value,b=document.getElementById('eb').value;await fetch(A+'/user/update',{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+GT()},body:JSON.stringify({display_name:n,bio:b})});if(CU){CU.display_name=n;CU.bio=b}UN();T('已更新','success')}");
        sb.append("async function LP(){var r=await fetch(A+'/user/posts',{headers:{'Authorization':'Bearer '+GT()}});var d=await r.json();var h='';if(d.success)d.data.forEach(function(p){h+='<div class=card><small>['+p.category+'] '+p.created_at+'</small><p>'+p.content+'</p>'+(p.image_url?'<img src='+p.image_url+' style=max-width:200px;border-radius:8px;cursor:pointer onclick=FS(\"'+p.image_url+'\")>':'')+'</div>'});document.getElementById('pc').innerHTML=h||'<p>暂无帖子</p>'}");

        // 初始化
        sb.append("document.addEventListener('DOMContentLoaded',function(){var t=localStorage.getItem('theme')||'t2';document.body.className=t;var token=GT();if(token){fetch(A+'/user/info',{headers:{'Authorization':'Bearer '+token}}).then(function(r){return r.json()}).then(function(d){if(d.success){if(d.data.isAdmin){CA=d.data}else{CU=d.data}UN()}}).catch(function(){})}UN();LSI();LCH();loadCarousel();LAT();setInterval(LSI,30000)});");
        sb.append("</script></body></html>");
        return sb.toString();
    }
}