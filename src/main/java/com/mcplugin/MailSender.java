package com.mcplugin;

import javax.mail.*;
import javax.mail.internet.*;
import java.util.Properties;

public class MailSender {
    private final WebBridgePlugin plugin;
    private final boolean enabled;
    private final String host, username, password;
    private final int port;

    public MailSender(WebBridgePlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("mail.enabled", false);
        this.host = plugin.getConfig().getString("mail.smtp_host", "smtp.qq.com");
        this.port = plugin.getConfig().getInt("mail.smtp_port", 587);
        this.username = plugin.getConfig().getString("mail.username", "");
        this.password = plugin.getConfig().getString("mail.password", "");
    }

    public boolean sendVerificationCode(String toEmail, String code) {
        if (!enabled) { plugin.getLogger().info("开发模式验证码: " + code); return false; }
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            Session session = Session.getInstance(props, new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            });
            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(username));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            msg.setSubject("[" + plugin.getConfig().getString("web.title") + "] 验证码");
            msg.setText("验证码: " + code + "\n5分钟内有效");
            Transport.send(msg);
            return true;
        } catch (Exception e) { plugin.getLogger().warning("邮件失败: " + e.getMessage()); return false; }
    }

    public boolean isEnabled() { return enabled; }
}