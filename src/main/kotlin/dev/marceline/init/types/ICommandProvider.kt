package dev.marceline.init.types

import dev.marceline.commands.CommandBuilder

interface ICommandProvider {
    fun CommandBuilder.command()
}