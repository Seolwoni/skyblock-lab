package dev.riftwalk.event

import dev.riftwalk.Riftwalk
import dev.riftwalk.utils.ChatUtils
import dev.riftwalk.utils.remove

abstract class Event(val cancelable: Boolean = false) {
    @Volatile
    open var isCanceled = false
        set(value) {
            if (! cancelable && value) throw RuntimeException("tried to cancel an uncancelable event")
            if (value && ! field && "cancel" in Riftwalk.debugFlags) captureSource()
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

            if (className.startsWith("dev.riftwalk.event") ||
                className.startsWith("java.lang") ||
                className.startsWith("kotlin.") ||
                className.contains("EventBus")
            ) continue

            val eventName = this.javaClass.name.remove("dev.riftwalk.event.impl.")
            val fileName = element.fileName ?: "Unknown File"
            val lineNumber = element.lineNumber
            val methodName = element.methodName

            val caller = "$fileName:$lineNumber ($methodName)"
            ChatUtils.modMessage("§c$eventName canceled by: §e$caller")

            break
        }
    }
}