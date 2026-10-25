package io.theficos.usuals.relay

import io.theficos.usuals.sync.Wire
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class MailConfiguration(val host: String, val port: Int, val from: String, val startTls: Boolean = true,
    val username: String? = null, internal val password: String? = null, local: Boolean = false,
    val senderLimit: Int = 10, val recipientLimit: Int = 2, val instanceLimit: Int = 60) {
    init {
        require(host.length in 1..253 && host.all { it.isLetterOrDigit() || it in ".-:" })
        require(port in 1..65535 && (startTls || local))
        require((username == null) == (password == null))
        require(username == null || username.length in 1..320 && username.none(Char::isISOControl))
        require(password == null || password.length in 1..8192 && password.none(Char::isISOControl))
        emailAddress(from)
        require(senderLimit in 1..100 && recipientLimit in 1..10 && instanceLimit in 1..1000)
    }
    override fun toString() = "MailConfiguration([redacted])"
    companion object {
        fun fromEnvironment(origin: String, env: Map<String, String> = System.getenv()): MailConfiguration? {
            val enabled = env["USUALS_EMAIL_INVITATIONS_ENABLED"] ?: "false"
            require(enabled in setOf("true", "false"))
            if (enabled == "false") return null
            fun required(key: String) = requireNotNull(env[key]) { "missing_config:$key" }
            val tls = (env["USUALS_SMTP_STARTTLS"] ?: "true").also { require(it in setOf("true", "false")) } == "true"
            val password = env["USUALS_SMTP_PASSWORD_FILE"]?.let { file ->
                val path = Path.of(file)
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) in 1..8193)
                require(Files.getPosixFilePermissions(path).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") })
                Files.readString(path).trim()
            }
            require(origin == canonicalOrigin(origin, true))
            return MailConfiguration(required("USUALS_SMTP_HOST"), (env["USUALS_SMTP_PORT"] ?: "587").toInt(), required("USUALS_SMTP_FROM"),
                tls, env["USUALS_SMTP_USERNAME"], password, local = URI(origin).scheme == "http",
                senderLimit = (env["USUALS_INVITATIONS_PER_SENDER_MINUTE"] ?: "10").toInt(),
                recipientLimit = (env["USUALS_INVITATIONS_PER_RECIPIENT_MINUTE"] ?: "2").toInt(),
                instanceLimit = (env["USUALS_INVITATIONS_PER_MINUTE"] ?: "60").toInt())
        }
    }
}

/** Case-insensitive ASCII mailbox matching; plus tags and dots remain significant. */
internal fun emailAddress(value: String): String {
    val email = value.trim()
    expect(email.length in 3..254 && email.all { it.code in 33..126 } && email.count { it == '@' } == 1, "invalid_email")
    val parts = email.split('@')
    expect(parts[0].length in 1..64 && Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+").matches(parts[0]) &&
        !parts[0].startsWith('.') && !parts[0].endsWith('.') && ".." !in parts[0] &&
        parts[1].split('.').size >= 2 && parts[1].split('.').all { it.length in 1..63 && Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?").matches(it) }, "invalid_email")
    return email.lowercase(java.util.Locale.ROOT)
}

@Serializable internal data class InvitationMail(val to: String, val sender: String, val link: String, val locale: String) {
    override fun toString() = "InvitationMail([redacted])"
}
internal interface InvitationMailer : AutoCloseable {
    fun send(message: InvitationMail)
    override fun close() {}
}

/** Jakarta Mail supplies SMTP, MIME and TLS; no application SMTP parser or provider SDK. */
internal class SmtpInvitationMailer(private val configuration: MailConfiguration) : InvitationMailer {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "invitation-smtp").apply { isDaemon = true } }
    override fun send(message: InvitationMail) {
        val properties = Properties().apply {
            setProperty("mail.smtp.host", configuration.host); setProperty("mail.smtp.port", configuration.port.toString())
            setProperty("mail.smtp.starttls.enable", configuration.startTls.toString())
            setProperty("mail.smtp.starttls.required", configuration.startTls.toString())
            setProperty("mail.smtp.ssl.checkserveridentity", "true")
            setProperty("mail.smtp.connectiontimeout", "2000"); setProperty("mail.smtp.timeout", "2000"); setProperty("mail.smtp.writetimeout", "2000")
            setProperty("mail.smtp.auth", (configuration.username != null).toString())
        }
        val session = Session.getInstance(properties)
        val transport = session.getTransport("smtp")
        val task = executor.submit {
            try {
                val text = template(message)
                val mail = MimeMessage(session).apply {
                    setFrom(InternetAddress(configuration.from)); setRecipient(Message.RecipientType.TO, InternetAddress(emailAddress(message.to)))
                    setSubject(text.first, "UTF-8")
                    setContent(MimeMultipart("alternative").apply {
                        addBodyPart(MimeBodyPart().apply { setText(text.second, "UTF-8") })
                        addBodyPart(MimeBodyPart().apply { setContent(text.third, "text/html; charset=UTF-8") })
                    })
                }
                transport.connect(configuration.host, configuration.port, configuration.username, configuration.password)
                transport.sendMessage(mail, mail.allRecipients)
            } finally { runCatching { transport.close() } }
        }
        try { task.get(8, TimeUnit.SECONDS) }
        catch (_: Exception) { task.cancel(true); runCatching { transport.close() }; throw IllegalStateException("mail_unavailable") }
    }
    override fun close() { executor.shutdownNow() }
    private fun template(message: InvitationMail): Triple<String, String, String> {
        val code = mailLocale(message.locale)
        val catalog = javaClass.getResourceAsStream("/web/locales/$code.json")!!.use { Wire.json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
        fun text(key: String) = catalog.getValue(key).jsonPrimitive.content.replace("{name}", message.sender.ifBlank { catalog.getValue("inviteSomeone").jsonPrimitive.content })
        val body = text("inviteEmailBody"); val action = text("openInvitation"); val footer = text("inviteEmailFooter")
        return Triple(text("inviteEmailSubject"), "$body\n\n$action: ${message.link}\n\n$footer\n",
            "<!doctype html><html lang=\"$code\"><body><p>${escape(body)}</p><p><a href=\"${escape(message.link)}\">${escape(action)}</a></p><p>${escape(footer)}</p></body></html>")
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
private val mailLocales by lazy {
    SmtpInvitationMailer::class.java.getResourceAsStream("/web/locales.json")!!.use {
        Wire.json.parseToJsonElement(it.readBytes().decodeToString()).jsonArray.map { code -> code.jsonPrimitive.content }.toSet()
    }
}
internal fun mailLocale(value: String) = value.takeIf { it in mailLocales } ?: "en"
