package com.amitjha.kosh.ui.family

import kotlinx.coroutines.flow.MutableStateFlow

/** Text shared into Kosh from another app (a bank SMS forwarded as a chat message), until the Family screen files it. */
object SharedSms {
    val pending = MutableStateFlow<String?>(null)
}
