package dev.riftwalk.init.types

import dev.riftwalk.commands.CommandBuilder

interface ICommandProvider {
    fun CommandBuilder.command()
}