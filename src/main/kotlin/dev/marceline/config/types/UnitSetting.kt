package dev.marceline.config.types

import dev.marceline.config.ConfigHolder

class UnitSetting(name: String): ConfigHolder<Unit>(name, Unit)