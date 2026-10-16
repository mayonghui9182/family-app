package com.familyapp.auth.recovery;

import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Properties;

/** 专用邮件客户端，不把 SMTP 密码、收件人或重置链接写入日志。 */
@Component
public class RecoveryMail {
    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;
    private final String origin;
    private final boolean configured;
    public RecoveryMail(Environment env) {
        String prefix = "familyapp.password-reset.";
        from = env.getProperty(prefix + "from", "");
        origin = env.getProperty(prefix + "public-url", "").replaceAll("/+$", "");
        String host = env.getProperty(prefix + "smtp-host", "");
        sender.setHost(host);
        sender.setPort(env.getProperty(prefix + "smtp-port", Integer.class, 587));
        sender.setUsername(env.getProperty(prefix + "smtp-username", ""));
        sender.setPassword(env.getProperty(prefix + "smtp-password", ""));
        sender.setDefaultEncoding("UTF-8");
        Properties props = sender.getJavaMailProperties();
        props.setProperty("mail.smtp.auth", String.valueOf(!sender.getUsername().isBlank()));
        boolean tls = env.getProperty(prefix + "starttls", Boolean.class, true);
        props.setProperty("mail.smtp.starttls.enable", String.valueOf(tls));
        props.setProperty("mail.smtp.starttls.required", String.valueOf(tls));
        props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        props.setProperty("mail.smtp.connectiontimeout", "5000");
        props.setProperty("mail.smtp.timeout", "5000");
        props.setProperty("mail.smtp.writetimeout", "5000");
        boolean valid = false;
        try {
            URI uri = URI.create(origin);
            boolean localHttp = env.getProperty(prefix + "allow-local-http", Boolean.class, false)
                    && "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            new jakarta.mail.internet.InternetAddress(from, true).validate();
            valid = ("https".equals(uri.getScheme()) || localHttp) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && (uri.getPath() == null || uri.getPath().isEmpty()) && !from.contains("\r") && !from.contains("\n");
        } catch (Exception ignored) { /* 配置不完整时保持关闭，申请接口返回不可用。 */ }
        configured = env.getProperty(prefix + "enabled", Boolean.class, false) && !host.isBlank() && valid;
    }
    public boolean available() { return configured; }
    public void send(PasswordResetTokens.Delivery delivery) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from); message.setTo(delivery.email());
        message.setSubject("家庭助手：重设密码");
        message.setText("请打开以下链接设置新密码，15分钟内有效，只能使用一次：\n"
                + origin + "/reset-password#token=" + delivery.token()
                + "\n\n如果不是您本人申请，请忽略此邮件。请勿转发此链接。");
        sender.send(message);
    }
}
