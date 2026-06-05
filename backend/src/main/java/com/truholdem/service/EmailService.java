package com.truholdem.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:noreply@truholdem.com}")
    private String fromEmail;

    @Value("${app.mail.from-name:TruHoldem}")
    private String fromName;

    @Value("${app.mail.verification-url:http://localhost:4200/auth/verify-email}")
    private String verificationBaseUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    public boolean isMailEnabled() {
        return mailEnabled;
    }

    @Async
    public void sendVerificationEmail(String to, String username, String verificationToken) {
        if (!mailEnabled) {
            logger.info("Email sending is disabled. Skipping verification email to: {}", to);
            return;
        }

        String subject = "Verify your TruHoldem account";
        String verificationUrl = verificationBaseUrl + "?token=" + verificationToken;

        String htmlContent = buildVerificationEmailHtml(username, verificationUrl);

        sendHtmlEmail(to, subject, htmlContent);
    }

    @Async
    public void sendWelcomeEmail(String to, String username) {
        if (!mailEnabled) {
            logger.info("Email sending is disabled. Skipping welcome email to: {}", to);
            return;
        }

        String subject = "Welcome to TruHoldem!";
        String htmlContent = buildWelcomeEmailHtml(username);

        sendHtmlEmail(to, subject, htmlContent);
    }

    @Async
    public void sendPasswordResetEmail(String to, String username, String resetToken) {
        if (!mailEnabled) {
            logger.info("Email sending is disabled. Skipping password reset email to: {}", to);
            return;
        }

        String subject = "Reset your TruHoldem password";
        String resetUrl = verificationBaseUrl.replace("/verify-email", "/reset-password") + "?token=" + resetToken;

        String htmlContent = buildPasswordResetEmailHtml(username, resetUrl);

        sendHtmlEmail(to, subject, htmlContent);
    }

    /**
     * Notify a registrant that an under-filled tournament's start time has been moved. Best-effort and
     * @Async; short-circuits (logs only) when mail is disabled, exactly like the other templates.
     */
    @Async
    public void sendTournamentRescheduledEmail(String to, String username, String tournamentName,
            String previousStart, String newStart) {
        if (!mailEnabled) {
            logger.info("Email sending is disabled. Skipping reschedule email to: {}", to);
            return;
        }
        String subject = "Tournament rescheduled: " + tournamentName;
        String htmlContent = buildTournamentRescheduledHtml(username, tournamentName, previousStart, newStart);
        sendHtmlEmail(to, subject, htmlContent);
    }

    /**
     * Notify a federated-pyramid finalist (a shard winner) that the final has been scheduled. Best-effort and
     * @Async; short-circuits (logs only) when mail is disabled.
     */
    @Async
    public void sendFederationFinalScheduledEmail(String to, String username, String federationName,
            String finalStart) {
        if (!mailEnabled) {
            logger.info("Email sending is disabled. Skipping federation-final email to: {}", to);
            return;
        }
        String subject = "You're in the final: " + federationName;
        String htmlContent = buildFederationFinalScheduledHtml(username, federationName, finalStart);
        sendHtmlEmail(to, subject, htmlContent);
    }

    private void sendHtmlEmail(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            logger.info("Email sent successfully to: {}", to);

        } catch (MessagingException e) {
            logger.error("Failed to send email to: {} - {}", to, e.getMessage());
        } catch (Exception e) {
            logger.error("Unexpected error sending email to: {} - {}", to, e.getMessage());
        }
    }

    private String buildVerificationEmailHtml(String username, String verificationUrl) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .header h1 { margin: 0; font-size: 28px; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .button { display: inline-block; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 14px 30px; text-decoration: none; border-radius: 8px; font-weight: bold; margin: 20px 0; }
                    .footer { text-align: center; color: #888; font-size: 12px; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>&#9824; TruHoldem</h1>
                    </div>
                    <div class="content">
                        <h2>Hello %s!</h2>
                        <p>Thank you for registering at TruHoldem. Please verify your email address by clicking the button below:</p>
                        <p style="text-align: center;">
                            <a href="%s" class="button">Verify Email Address</a>
                        </p>
                        <p>Or copy and paste this link into your browser:</p>
                        <p style="word-break: break-all; color: #667eea;">%s</p>
                        <p>This link will expire in 24 hours.</p>
                        <p>If you didn't create an account, you can safely ignore this email.</p>
                    </div>
                    <div class="footer">
                        <p>&copy; 2024 TruHoldem. All rights reserved.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(username, verificationUrl, verificationUrl);
    }

    private String buildWelcomeEmailHtml(String username) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .header h1 { margin: 0; font-size: 28px; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .feature { background: white; padding: 15px; margin: 10px 0; border-radius: 8px; border-left: 4px solid #667eea; }
                    .footer { text-align: center; color: #888; font-size: 12px; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>&#9824; Welcome to TruHoldem!</h1>
                    </div>
                    <div class="content">
                        <h2>Hello %s!</h2>
                        <p>Your account has been verified and you're ready to play!</p>
                        <h3>What you can do:</h3>
                        <div class="feature">&#127183; Play Texas Hold'em against smart bots</div>
                        <div class="feature">&#128200; Track your statistics and improve</div>
                        <div class="feature">&#127942; Compete on the leaderboard</div>
                        <div class="feature">&#128214; Review your hand history</div>
                        <p>Good luck at the tables!</p>
                    </div>
                    <div class="footer">
                        <p>&copy; 2024 TruHoldem. All rights reserved.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(username);
    }

    private String buildPasswordResetEmailHtml(String username, String resetUrl) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .header h1 { margin: 0; font-size: 28px; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .button { display: inline-block; background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 14px 30px; text-decoration: none; border-radius: 8px; font-weight: bold; margin: 20px 0; }
                    .warning { background: #fff3cd; border: 1px solid #ffc107; padding: 15px; border-radius: 8px; margin: 15px 0; }
                    .footer { text-align: center; color: #888; font-size: 12px; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>&#9824; TruHoldem</h1>
                    </div>
                    <div class="content">
                        <h2>Password Reset Request</h2>
                        <p>Hello %s,</p>
                        <p>We received a request to reset your password. Click the button below to create a new password:</p>
                        <p style="text-align: center;">
                            <a href="%s" class="button">Reset Password</a>
                        </p>
                        <p>Or copy and paste this link into your browser:</p>
                        <p style="word-break: break-all; color: #667eea;">%s</p>
                        <div class="warning">
                            <strong>&#9888; Security Notice:</strong> This link will expire in 1 hour. If you didn't request a password reset, please ignore this email or contact support if you're concerned about your account security.
                        </div>
                    </div>
                    <div class="footer">
                        <p>&copy; 2024 TruHoldem. All rights reserved.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(username, resetUrl, resetUrl);
    }

    private String buildTournamentRescheduledHtml(String username, String tournamentName,
            String previousStart, String newStart) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .header h1 { margin: 0; font-size: 28px; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .slot { background: white; padding: 15px; margin: 10px 0; border-radius: 8px; border-left: 4px solid #667eea; }
                    .footer { text-align: center; color: #888; font-size: 12px; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>&#9824; TruHoldem</h1>
                    </div>
                    <div class="content">
                        <h2>Hello %s!</h2>
                        <p>The tournament <strong>%s</strong> did not reach the required number of players in time,
                           so its start has been postponed. Your registration is kept &mdash; no action is needed.</p>
                        <div class="slot">Previous start: <strong>%s</strong></div>
                        <div class="slot">New start: <strong>%s</strong></div>
                        <p>We'll see you at the new time. Good luck at the tables!</p>
                    </div>
                    <div class="footer">
                        <p>&copy; 2024 TruHoldem. All rights reserved.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(username, tournamentName, previousStart, newStart);
    }

    private String buildFederationFinalScheduledHtml(String username, String federationName, String finalStart) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: 'Segoe UI', Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #f59e0b 0%%, #b45309 100%%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .header h1 { margin: 0; font-size: 28px; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .slot { background: white; padding: 15px; margin: 10px 0; border-radius: 8px; border-left: 4px solid #f59e0b; }
                    .footer { text-align: center; color: #888; font-size: 12px; margin-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>&#127942; You reached the final!</h1>
                    </div>
                    <div class="content">
                        <h2>Congratulations, %s!</h2>
                        <p>You won your shard of <strong>%s</strong> and have qualified for the grand final
                           among all the shard winners. The final has been scheduled:</p>
                        <div class="slot">Final starts: <strong>%s</strong></div>
                        <p>Be at the tables on time. Good luck &mdash; one of you becomes the champion!</p>
                    </div>
                    <div class="footer">
                        <p>&copy; 2024 TruHoldem. All rights reserved.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(username, federationName, finalStart);
    }
}
