package dev.riftwalk.event.impl

import dev.riftwalk.event.Event
import dev.riftwalk.utils.PetUtils.Pet

abstract class PetEvent(val pet: Pet?): Event(cancelable = false) {
    class Change(pet: Pet?, val cause: Cause): PetEvent(pet)
    class LevelUp(pet: Pet): PetEvent(pet)

    enum class Cause { SUMMON, DESPAWN, AUTOPET, MENU }
}