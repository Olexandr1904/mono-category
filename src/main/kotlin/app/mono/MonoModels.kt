package app.mono

import app.budget.Txn
import app.budget.UAH_CURRENCY_CODE
import app.budget.monthKeyOf
import kotlinx.serialization.Serializable

@Serializable
data class MonoAccount(
    val id: String,
    val sendId: String = "",
    val balance: Long = 0,
    val creditLimit: Long = 0,
    val type: String = "",
    val currencyCode: Int,
    val maskedPan: List<String> = emptyList(),
) {
    val isUah: Boolean get() = currencyCode == UAH_CURRENCY_CODE
}

@Serializable
data class MonoClientInfo(
    val clientId: String = "",
    val name: String = "",
    val webHookUrl: String = "",
    val accounts: List<MonoAccount> = emptyList(),
)

@Serializable
data class StatementItem(
    val id: String,
    val time: Long,
    val description: String = "",
    val mcc: Int? = null,
    val originalMcc: Int? = null,
    val hold: Boolean = false,
    val amount: Long,
    val operationAmount: Long = 0,
    val currencyCode: Int = UAH_CURRENCY_CODE,
    val comment: String? = null,
    val counterName: String? = null,
    val counterIban: String? = null,
    val counterEdrpou: String? = null,
)

/**
 * A statement item together with the exact JSON Monobank sent for it.
 *
 * The typed model is deliberately not the source of truth for storage: whichever field
 * turns out to matter next is unknowable today, and history cannot be re-fetched — the
 * statement window is 31 days and the endpoint allows one request per minute. So we keep
 * the element verbatim and decode a view of it.
 */
data class RawItem(val item: StatementItem, val raw: String)

fun StatementItem.toTxn(accountId: String, rawJson: String): Txn = Txn(
    id = id,
    accountId = accountId,
    occurredAt = time,
    month = monthKeyOf(time),
    amountMinor = amount,
    currencyCode = currencyCode,
    description = description,
    mcc = mcc,
    originalMcc = originalMcc,
    hold = hold,
    categoryId = null,
    manuallyCategorized = false,
    rawJson = rawJson,
)
