package app.notify

/** A question meant for one person, and the markup that asks only that person. */
data class AddressedQuestion(val text: String, val forceReply: Boolean, val selective: Boolean)

/**
 * Works out how to put [question] to the one person who asked for it — whoever pressed the
 * button or answered the last one.
 *
 * In a private chat a plain force reply already targets the only person there, and a
 * mention would be noise. In a group it has to name them: Telegram aims a selective force
 * reply at the users @-mentioned in the text, so `selective` on a message that mentions
 * nobody targets nobody, which is worse than not setting it at all.
 *
 * With no username to mention — Telegram accounts need not have one, and a plain-text
 * mention is the only kind this client can send — we ask without a force reply rather than
 * spring every member's keyboard open. The question's own words still say to answer with a
 * reply, which they must in every case: under privacy mode the bot never sees a message
 * that is not a reply to its own, so an answer typed straight into the room is lost.
 *
 * [chat] is null only for a callback with no message attached, which is a stale press on an
 * old message; private is the conservative reading.
 */
fun addressQuestion(question: String, chat: TgChat?, asker: TgUser?): AddressedQuestion {
    if (chat?.isPrivate ?: true) {
        return AddressedQuestion(question, forceReply = true, selective = false)
    }
    val username = asker?.username?.takeIf { it.isNotBlank() }
        ?: return AddressedQuestion(question, forceReply = false, selective = false)
    return AddressedQuestion("@$username, $question", forceReply = true, selective = true)
}
