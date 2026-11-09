package dev.marceline.event

import dev.marceline.Marceline
import dev.marceline.utils.ChatUtils
import dev.marceline.utils.remove

abstract class Event(val cancelable: Boolean = false) {
    @Volatile
    open var isCanceled = false
        set(value) {
            if (! cancelable && value) throw RuntimeException("tried to cancel an uncancelable event")
            if (value && ! field && "cancel" in Marceline.debugFlags) captureSource()
            field = value
        }

    open fun cancel() {
        isCanceled = true
    }

    private fun captureSource() {
        val stack = Thread.currentThread().stackTrace

        for (i in 3 until stack.size) {
            val element = stack[i]
            val className = element.className

            if (className.startsWith("dev.marceline.event") ||
                className.startsWith("java.lang") ||
                className.startsWith("kotlin.") ||
                className.contains("EventBus")
            ) continue

            val eventName = this.javaClass.name.remove("dev.marceline.event.impl.")
            val fileName = element.fileName ?: "Unknown File"
            val lineNumber = element.lineNumber
            val methodName = element.methodName

            val caller = "$fileName:$lineNumber ($methodName)"
            ChatUtils.modMessage("§c$eventName canceled by: §e$caller")

            break
        }
    }
}