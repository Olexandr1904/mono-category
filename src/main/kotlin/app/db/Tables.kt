package app.db

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table

object Accounts : Table("accounts") {
    val id = text("id")
    val maskedPan = text("masked_pan")
    val type = text("type")
    val currencyCode = integer("currency_code")
    val balanceMinor = long("balance_minor")
    val active = bool("active")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object Categories : LongIdTable("categories") {
    val name = text("name")
    val emoji = text("emoji")
    val monthlyLimitMinor = long("monthly_limit_minor")
    val thresholdPct = integer("threshold_pct")
    val notifyWarning = bool("notify_warning")
    val notifyExceeded = bool("notify_exceeded")
    val enabled = bool("enabled")
    val position = integer("position")
    val createdAt = long("created_at")
    // V4: money that only ever moved between the owner's own accounts (a ФОП account
    // paying its own black card, one card paying another) is not spending. Default true —
    // every category keeps counting until the owner opts one out.
    val countsAsSpending = bool("counts_as_spending")
}

object CategoryMcc : Table("category_mcc") {
    val mcc = integer("mcc")
    // Plain Long, not reference(): the FK is declared in V1__init.sql, and every
    // read site treats this as a Long rather than an EntityID.
    val categoryId = long("category_id")
    override val primaryKey = PrimaryKey(mcc)
}

object Transactions : Table("transactions") {
    val id = text("id")
    val accountId = text("account_id")
    val occurredAt = long("occurred_at")
    val month = text("month")
    val amountMinor = long("amount_minor")
    val currencyCode = integer("currency_code")
    val description = text("description")
    val mcc = integer("mcc").nullable()
    val originalMcc = integer("original_mcc").nullable()
    val hold = bool("hold")
    val categoryId = long("category_id").nullable()
    val manuallyCategorized = bool("manually_categorized")
    val rawJson = text("raw_json")
    val createdAt = long("created_at")
    val counterpartyKey = text("counterparty_key").nullable()
    val counterpartySource = text("counterparty_source").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WebhookEvents : LongIdTable("webhook_events") {
    val payload = text("payload")
    val receivedAt = long("received_at")
    val processedAt = long("processed_at").nullable()
}

object NotificationEvents : LongIdTable("notification_events") {
    val categoryId = long("category_id")
    val month = text("month")
    val threshold = integer("threshold")
    val notifiedAt = long("notified_at")
}

object MccPrompts : LongIdTable("mcc_prompts") {
    val mcc = integer("mcc")
    val transactionId = text("transaction_id")
    val messageId = long("message_id").nullable()
    val createdAt = long("created_at")
    val resolvedAt = long("resolved_at").nullable()
    val kind = text("kind")
    val replyMessageId = long("reply_message_id").nullable()
}

object LimitPrompts : LongIdTable("limit_prompts") {
    val categoryId = long("category_id")
    val keyboardMessageId = long("keyboard_message_id").nullable()
    val replyMessageId = long("reply_message_id").nullable()
    val createdAt = long("created_at")
    val resolvedAt = long("resolved_at").nullable()
}

object Settings : Table("settings") {
    val key = text("key")
    val value = text("value")
    val encrypted = bool("encrypted")
    override val primaryKey = PrimaryKey(key)
}

object ConduitMcc : Table("conduit_mcc") {
    val mcc = integer("mcc")
    val note = text("note")
    override val primaryKey = PrimaryKey(mcc)
}

object CategoryCounterparty : Table("category_counterparty") {
    val counterpartyKey = text("counterparty_key")
    // Plain Long, not reference(): the FK is declared in SQL and every read site treats
    // this as a Long, matching CategoryMcc.
    val categoryId = long("category_id")
    val displayName = text("display_name")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(counterpartyKey)
}
