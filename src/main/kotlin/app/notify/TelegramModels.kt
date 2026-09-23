package app.notify

data class Button(val text: String, val callbackData: String)

/** [command] carries its leading slash here, the way every other mention of it in this
 *  codebase does; Telegram's API wants it without, and the client strips it on the way out. */
data class BotCommand(val command: String, val description: String)
